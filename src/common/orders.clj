(ns common.orders
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [ardoq.analytics-clj :as segment]
            [cheshire.core :refer [generate-string]]
            [common.coupons :as coupons]
            [common.config :as config]
            [common.couriers :refer [set-courier-busy update-courier-busy]]
            [common.db :refer [mysql-escape-str conn !select !update]]
            [common.payment :as payment]
            [common.users :as users]
            [common.util :refer [cents->dollars cents->dollars-str in?
                                 segment-client unix->DateTime unix->full]]
            [common.zoning :refer [order->market-id]]))

(defn get-by-id
  "Gets an order from db by order's id."
  [db-conn id]
  (first (!select db-conn
                  "orders"
                  ["*"]
                  {:id id})))

(defn update-status
  "Assumed to have been auth'd properly already."
  [db-conn order-id status]
  (sql/with-connection db-conn
    (sql/do-prepared
     (str "UPDATE orders SET "
          ;; change status
          "status = \"" (mysql-escape-str status) "\", "
          ;; update event log
          "event_log = CONCAT(event_log, \""
          (mysql-escape-str status) " " (quot (System/currentTimeMillis) 1000)
          "\", '|') WHERE id = \""
          (mysql-escape-str order-id)
          "\""))))

(defn segment-props
  "Get a map of all the standard properties we track on orders via segment."
  [o]
  (assoc (select-keys o [:vehicle_id :gallons :gas_type :lat :lng
                         :address_street :address_city :address_state
                         :address_zip :license_plate :coupon_code
                         :referral_gallons_used :tire_pressure_check])
         :order_id (:id o)
         :gas_price (cents->dollars (:gas_price o))
         :service_fee (cents->dollars (:service_fee o))
         :total_price (cents->dollars (:total_price o))
         :target_time_start (unix->DateTime (:target_time_start o))
         :target_time_end (unix->DateTime (:target_time_end o))
         :market_id (order->market-id o)))

(defn stamp-with-charge
  "Give it a charge object from Stripe."
  [db-conn order-id charge]
  (!update db-conn
           "orders"
           {:paid (:captured charge) ;; NOT THE SAME as (:paid charge)
            :stripe_charge_id (:id charge)
            :stripe_customer_id_charged (:customer charge)
            :stripe_balance_transaction_id (:balance_transaction charge)
            :time_paid (:created charge)
            :payment_info (-> charge
                              :source
                              (select-keys
                               [:id :brand :exp_month :exp_year :last4])
                              generate-string)}
           {:id order-id}))

(defn unpaid-balance
  [db-conn user-id]
  (reduce +
          (map :total_price
               (!select db-conn
                        "orders"
                        [:total_price]
                        {:user_id user-id
                         :status "complete"
                         :paid 0}
                        :append "AND total_price > 0")))) ; $0 order = no charge

(defn new-order-text
  [db-conn o charge-authorized?]
  (str "New order:"
       (if charge-authorized?
         "\nCharge Authorized."
         "\n!CHARGE FAILED TO AUTHORIZE!")
       (let [unpaid-balance (unpaid-balance
                             db-conn (:user_id o))]
         (when (> unpaid-balance 0)
           (str "\n!UNPAID BALANCE: $"
                (cents->dollars-str unpaid-balance))))
       "\nDue: " (unix->full
                  (:target_time_end o))
       "\n" (:address_street o) ", "
       (:address_zip o)
       "\n" (:gallons o)
       " Gallons of " (:gas_type o)))

(defn stamp-with-refund
  "Give it a refund object from Stripe."
  [db-conn order-id refund]
  (!update db-conn
           "orders"
           {:stripe_refund_id (:id refund)}
           {:id order-id}))

(defn begin-route
  "This is a courier action."
  [db-conn o]
  (do (update-status db-conn (:id o) "enroute")
      (users/send-push
       db-conn
       (:user_id o)
       (str "A courier is enroute to your location. Please ensure that your"
            " fueling door is open."))))

(defn service
  "This is a courier action."
  [db-conn o]
  (do (update-status db-conn (:id o) "servicing")
      (users/send-push
       db-conn
       (:user_id o)
       "We are currently servicing your vehicle.")))

(defn after-payment
  [db-conn o]
  (do (when-not (s/blank? (:coupon_code o))
        (when-let [user-id (-> (!select db-conn "users" [:id] {:referral_code
                                                               (:coupon_code o)})
                               first ;; if this when-let fails, that means this
                               :id)] ;; is a standard coupon not referral coupon
          (coupons/apply-referral-bonus db-conn user-id)))
      (segment/track segment-client (:user_id o) "Complete Order"
                     (assoc (segment-props o)
                            :revenue (cents->dollars (:total_price o))))
      (users/send-push db-conn (:user_id o)
                       (let [user (users/get-user-by-id db-conn (:user_id o))]
                         (str "Your delivery has been completed."
                              (when-not (users/is-managed-account? user)
                                (str " Share your code "
                                     (:referral_code user)
                                     " to earn free gas"
                                     (when (not (.contains (:arn_endpoint user) "GCM/Purple"))
                                       " \ue112") ; iOS gift emoji
                                     "."))
                              " Thank you!")))))

(defn complete
  "Completes order and charges user."
  [db-conn o]
  (do (update-status db-conn (:id o) "complete")
      (update-courier-busy db-conn (:courier_id o))
      (if (or (zero? (:total_price o))
              (s/blank? (:stripe_charge_id o)))
        (after-payment db-conn o)
        (let [capture-result (payment/capture-stripe-charge
                              (:stripe_charge_id o))]
          (if (:success capture-result)
            (do (stamp-with-charge db-conn (:id o) (:charge capture-result))
                (after-payment db-conn o))
            capture-result)))))

(defn next-status
  [status]
  (get config/status->next-status status))

;; note that it takes order-id, not order
(defn accept
  [db-conn order-id]
  (do (update-status db-conn order-id "accepted")
      {:success true}))

;; note that it takes order-id, not order
(defn assign
  [db-conn order-id courier-id & {:keys [no-reassigns]}]
  (let [o (get-by-id db-conn order-id)]
    (when (or (not no-reassigns)
              (= "unassigned" (:status o)))
      (update-status db-conn order-id "assigned")
      (!update db-conn "orders" {:courier_id courier-id} {:id order-id})
      (set-courier-busy db-conn courier-id true)
      (users/send-push db-conn courier-id "You have been assigned a new order.")
      (users/text-user db-conn courier-id (new-order-text db-conn o true))
      {:success true})))

(defn cancel
  [db-conn user-id order-id & {:keys [origin-was-dashboard
                                      notify-customer
                                      suppress-user-details
                                      override-cancellable-statuses]}]
  (if-let [o (get-by-id db-conn order-id)]
    (if (in? (or override-cancellable-statuses
                 config/cancellable-statuses)
             (:status o))
      (do (update-status db-conn order-id "cancelled")
          (future
            ;; return any free gallons that may have been used
            (when (not= 0 (:referral_gallons_used o))
              (coupons/mark-gallons-as-unused db-conn
                                              (:user_id o)
                                              (:referral_gallons_used o))
              (!update db-conn
                       "orders"
                       {:referral_gallons_used 0}
                       {:id order-id}))
            ;; free up that coupon code for that vehicle
            (when-not (s/blank? (:coupon_code o))
              (coupons/mark-code-as-unused db-conn
                                           (:coupon_code o)
                                           (:vehicle_id o)
                                           (:user_id o))
              (!update db-conn
                       "orders"
                       {:coupon_code ""}
                       {:id order-id}))
            ;; let the courier know the order has been cancelled
            (when-not (s/blank? (:courier_id o))
              (update-courier-busy db-conn (:courier_id o))
              (users/send-push db-conn (:courier_id o)
                               "The current order has been cancelled."))
            ;; let the user know the order has been cancelled
            (when notify-customer
              (users/send-push
               db-conn user-id
               (str "Your order has been cancelled. If you have any questions,"
                    " please email us at info@purpleapp.com or use the Feedback"
                    " form on the left-hand menu.")))
            (when-not (s/blank? (:stripe_charge_id o))
              (let [refund-result (payment/refund-stripe-charge
                                   (:stripe_charge_id o))]
                (when (:success refund-result)
                  (stamp-with-refund db-conn
                                     order-id
                                     (:refund refund-result)))))
            (segment/track
             segment-client (:user_id o) "Cancel Order"
             (assoc (segment-props o)
                    :cancelled-by-user (not origin-was-dashboard))))
          (if suppress-user-details
            {:success true}
            (users/details db-conn user-id)))
      {:success false
       :message "Sorry, it is too late for this order to be cancelled."})
    {:success false
     :message "An order with that ID could not be found."}))
