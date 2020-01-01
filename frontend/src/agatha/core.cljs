(ns agatha.core
  (:require [reagent.core :as r]
            [re-frame.core :as re-frame :refer [subscribe]]
            [oops.core :refer [ocall oset! oget]]
            [agatha.events]
            [agatha.subs]
            [agatha.ui.views :as views]
            ["webrtc-adapter" :refer [adapter]]))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (r/render [views/app]
            (.getElementById js/document "app")))

(defn ^:export init []
  (re-frame/dispatch-sync [:initialize-db])
  (re-frame/dispatch [:configure-auth0])
  (mount-root))

(defn stop []
  (js/console.log "stop"))