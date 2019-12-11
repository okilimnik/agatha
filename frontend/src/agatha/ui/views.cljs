(ns agatha.ui.views
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [agatha.net :refer [connect hang-up-call handle-key handle-send-button]]))

(defn app-root []
  [:div.container
   [:div.infobox
    [:p "This is a simple chat system implemented using WebSockets. It works by sending packets of JSON back and forth with the server."
     [:a {:href "https://github.com/mdn/samples-server/tree/master/s/webrtc-from-chat"}] "Check out the source</a> on Github."]
    [:p.mdn-disclaimer "This text and audio/video chat example is offered as-is for demonstration purposes only, and should not be used for any other purpose."]
    [:p "Click a username in the user list to ask them to enter a one-on-one video chat with you."]
    [:p "Enter a username:"
     [:input {:id "name" :type "text" :maxlength "12" :required true :autocomplete "username" :inputmode "verbatim" :placeholder "Username"}]
     [:input {:type "button" :name "login" :value "Log in" :onclick connect}]]]
   [:ul.userlistbox]
   [:div.chatbox]
   [:div.camerabox
    [:video {:id "received_video" :autoplay true}]
    [:video {:id "local_video" :autoplay true :muted true}]
    [:button {:id "hangup_button" :onclick hang-up-call :role "button" :disabled true} "Hang Up"]]
   [:div.empty-container]
   [:div.chat-controls "Chat:" [:br]
    [:input {:id "text" :type "text" :name "text" :size "100" :maxlength "256" :placeholder "Say something meaningful..." :autocomplete "off" :onkeyup handle-key :disabled true}]
    [:input {:type "button" :id "send" :name "send" :value "Send" :onclick handle-send-button :disabled true}]]])