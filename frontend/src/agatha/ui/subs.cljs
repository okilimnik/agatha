(ns agatha.ui.subs
  (:require [re-frame.core :refer [reg-sub]]))

(reg-sub
  :config
  (fn [db _]
    (:config db)))

(reg-sub
  :auth0
  (fn [db _]
    (:auth0 db)))

(reg-sub
  :authenticated?
  (fn [db _]
    (:authenticated? db)))