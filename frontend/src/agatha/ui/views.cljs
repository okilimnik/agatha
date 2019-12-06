(ns agatha.ui.views
  (:require [reagent.core :as r]
            [agatha.ui.routes :as routes]
            [re-frame.core :refer [subscribe]]))

(defn page-view [{:keys [header content]}]
  [:div.page-wrapper
   [:header
    [:a.logo {:href (routes/home)} "Home"]
    [:h1 "Demo"]]
   [:main content]])

(defn about []
  [page-view
   {:content "This is about it."}])

(defn home []
  (let [keypair (atom nil)]
    (r/create-class
      {:component-did-mount
       (fn []
         ;(reset! keypair (wallet/create))
         ;(prn (wallet/sign "some bananas" @keypair))
         ;(prn (wallet/verify "some bananas" @keypair (wallet/sign "some bananas" @keypair)))
         )
       :reagent-render
       (fn []
         [page-view
          {:content [:a {:href (routes/about)} "Learn More"]}])})))

(defn app-view [{:keys [page-id]}]
  (case page-id
    :home
    [home]
    :about
    [about]))

(defn app-root []
  (app-view @(subscribe [:app-view])))