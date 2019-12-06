(ns agatha.ui.subs
  (:require [re-frame.core :refer [reg-sub]]))

(reg-sub
  :app-view
  (fn [{:keys [page]}]
    {:page-id page}))