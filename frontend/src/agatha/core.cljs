(ns agatha.core
      (:require [reagent.core :as reagent]
                [re-frame.core :as re-frame]
                [agatha.ui.events]
                [agatha.ui.subs]
                [agatha.ui.views :as views]
                [agatha.ui.routes :as routes]))

(defn ^:dev/after-load mount-root []
      (re-frame/clear-subscription-cache!)
      (reagent/render [views/app-root]
                      (.getElementById js/document "app")))

(defn ^:export init []
      (re-frame/dispatch-sync [:initialize-db])
      (routes/app-routes re-frame/dispatch)
      (mount-root))

(defn  stop []
      (js/console.log "stop"))

