(ns agatha.ui.events
  (:require [re-frame.core :refer [reg-event-db]]))

(reg-event-db
  :initialize-db
  (fn [_ _]
    {:page :home}))

(reg-event-db
  :routes/home
  (fn [db _]
    (-> db
        (assoc :page :home))))

(reg-event-db
  :routes/about
  (fn [db _]
    (-> db
        (assoc :page :about))))