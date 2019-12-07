(ns agatha.core
      (:require [reagent.core :as reagent]
                [re-frame.core :as re-frame]
                [agatha.ui.events]
                [agatha.ui.subs]
                [agatha.ui.views :as views]
                ["webrtc-adapter" :refer [adapter]]))

(defn ^:dev/after-load mount-root []
      (re-frame/clear-subscription-cache!)
      (reagent/render [views/app-root]
                      (.getElementById js/document "app")))

(defn ^:export init []
      (re-frame/dispatch-sync [:initialize-db])
      (mount-root))

(defn  stop []
      (js/console.log "stop"))

