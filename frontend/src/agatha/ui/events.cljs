(ns agatha.ui.events
  (:require [re-frame.core :refer [reg-event-db dispatch]]
            [oops.core :refer [ocall oset! oget]]
            [promesa.async-cljs :refer-macros [async]]
            [agatha.util :refer [await-> read-config]]))

(reg-event-db
  :initialize-db
  (fn [_ _]
    {:config (read-config)}))

(reg-event-db
  :write-to
  (fn [db [_ [path value]]]
    (assoc-in db path value)))

(reg-event-db
  :configure-auth0
  (fn [db _]
    (let [config (:auth0 (:idp (:config db)))]
      (-> (js/createAuth0Client #js {:domain    (:domain config)
                                     :client_id (:clientId config)})
          (.then (fn [auth0]
                   (dispatch [:write-to [[:auth0] auth0]])
                   (-> auth0
                       (.isAuthenticated)
                       (.then (fn [result]
                                (dispatch [:write-to [[:authenticated?] result]])
                                (when-not result
                                  (let [query (oget js/window "location.search")]
                                    (when (or (clojure.string/includes? query "code=")
                                              (clojure.string/includes? query "state="))
                                      (-> auth0
                                          (.handleRedirectCallback)
                                          (.then (fn []
                                                   (-> auth0
                                                       (.isAuthenticated)
                                                       (.then (fn [result2]
                                                                (dispatch [:write-to [[:authenticated?] result2]])
                                                                (ocall (oget js/window "history") :replaceState #js {} (oget js/document "title") "/"))))))))))))))))
      db)))

(reg-event-db
  :login
  (fn [db _]
    (-> (:auth0 db)
        (.loginWithRedirect #js {:redirect_uri (oget js/window "location.origin")}))
    db))

(reg-event-db
  :logout
  (fn [db _]
    (-> (:auth0 db)
        (.logout #js {:returnTo (oget js/window "location.origin")}))
    db))

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