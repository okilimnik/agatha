(ns agatha.net
  (:require [oops.core :refer [ocall oset! oget]]
            [cljs.reader :as edn]))

(def connection (atom nil))
(def client-id (atom nil))

(defn log
  "Output logging information to console"
  [& args]
  (js/console.log (str "[" (.toLocaleTimeString (js/Date.)) "] " (apply str args))))

(defn log-error
  "Output an error information to console"
  [& args]
  (js/console.trace (str "[" (.toLocaleTimeString (js/Date.)) "] " (apply str args))))

(defn send-to-server
  "Send a JavaScript object by converting it to JSON and sending\n
  it as a message on the WebSocket connection."
  [msg]
  (let [msg-json (js/JSON.stringify msg)]
    (log "Sending '" (oget msg "type") "' message: " msg-json)
    (ocall @connection :send msg-json)))

(defn set-username
  "Called when the 'id' message is received; this message is sent by the
  server to assign this login session a unique ID number; in response,
  this function sends a 'username' message to set our username for this
  session."
  []
  (send-to-server #js {:name (oget (ocall js/document :getElementById "name") "value")
                       :date (js/Date.now)
                       :id   @client-id
                       :type "username"}))

(defn on-message [text evt]
  (let [msg (js/JSON.parse (oget evt "data"))]
    (log "Message received: ")
    (js/console.dir msg)
    (let [time (js/Date. (oget msg "date"))
          time-str (ocall time :toLocaleTimeString)]

      (case (oget msg "type")

        "id" (do (reset! client-id (oget msg "id"))
                 (set-username))

        "username" (reset! text (str "<b>User <em>" (oget msg "name") "</em> signed in at " time-str "</b><br>"))

        "message" (reset! text (str "(" time-str ") <b>" (oget msg "name") "</b>: " (oget msg "text") "<br>"))

        "rejectusername" (let [username (oget msg "name")]
                           (reset! text (str "<b>Your username has been set to <em>" username
                                             "</em> because the name you chose is in use.</b><br>")))
        ;; Received an updated user list
        "userlist" (handle-userlist-msg msg)

        ;; Signaling messages: these messages are used to trade WebRTC
        ;; signaling information during negotiations leading up to a video
        ;; call.

        ;; Invitation and offer to chat
        "video-offer" (handle-video-offer-msg msg)

        ;; Callee has answered our offer
        "video-answer" (handle-video-answer-msg msg)

        ;; A new ICE candidate has been received
        "new-ice-candidate" (handle-new-ice-candidate-msg msg)

        ;; The other peer has hung up the call
        "hang-up" (handle-hang-up-msg msg)

        ;;Unknown message; output to console for debugging.
        (do (log-error "Unknown message received:")
            (log-error msg))))))

(defn connect []
  (let [host (or (oget js/window "location.hostname") "localhost")
        scheme (if (= (oget js/document "location.protocol") "https:") "wss" "ws")
        server-url (str scheme "://" host ":6503")
        chat-box (ocall js/document :querySelector ".chatbox")
        text (atom "")]
    (log "Connecting to server: " server-url)
    (reset! connection (js/WebSocket. server-url "json"))
    (oset! (oget @connection "onopen")
           (fn [_]
             (oset! (oget (ocall js/document :getElementById "text") "disabled") false)
             (oset! (oget (ocall js/document :getElementById "send") "disabled") false)))
    (oset! (oget @connection "onerror") #(js/console.dir %))
    (oset! (oget @connection "onmessage") (partial on-message text))

    ;; If there's text to insert into the chat buffer, do so now, then
    ;; scroll the chat panel so that the new text is visible.

    (when (oget @text "length")
      (oset! (oget chat-box "innerHTML") (str (oget chat-box "innerHTML") @text))
      (oset! (oget chat-box "scrollTop") (- (oget chat-box "scrollHeight") (oget chat-box "clientHeight"))))))

(defn hang-up-call []
  nil)

(defn handle-send-button
  "Handles a click on the Send button (or pressing return/enter) by
  building a 'message' object and sending it to the server."
  []
  (send-to-server #js {:text (oget (ocall js/document :getElementById "text") "value")
                       :type "message"
                       :id @client-id
                       :date (js/Date.now)})
  (oset! (oget (ocall js/document :getElementById "text") "value") ""))

(defn handle-key
  "Handler for keyboard events. This is used to intercept the return and
  enter keys so that we can call send() to transmit the entered text
  to the server."
  [evt]
  (let [key-code (oget evt "keyCode")]
    (when (or (= key-code 13) (= key-code 14))
      (when-not (oget (ocall js/document :getElementById "send") "disabled")
        (handle-send-button)))))

(defn handle-negotiation-needed-event
  "Called by the WebRTC layer to let us know when it's time to
  begin, resume, or restart ICE negotiation."
  []
  (log "*** Negotiation needed"))

(def options #js {                                          ;:ordered              false      ;; If the data channel should guarantee order or not
                  ;:maxPacketLifeTime    3000       ;; The maximum time to try and retransmit a failed message
                  ;:maxRetransmits    5                      ;; The maximum number of times to try and retransmit a failed message
                  ;:protocol          "?"                    ;; Allows a subprotocol to be used which provides meta information towards the application
                  ;:negotiated        false                  ;;  If set to true, it removes the automatic setting up of a data channel on the other peer, meaning that you are provided your own way to create a data channel with the same id on the other side
                  ;:id                "?"                    ;; Allows you to provide your own ID for the channel (can only be used in combination with negotiated set to true)
                  :iceServers           #js ["stun:stun.l.google.com:19302"]
                  ;:iceTransportPolicy   "all"      ;; "relay"
                  ;:iceCandidatePoolSize 5          ;; 0 - 10
                  })

(defn create-peer-connection
  "Create the RTCPeerConnection which knows how to talk to our
  selected STUN/TURN server and then uses getUserMedia() to find
  our camera and microphone and add that stream to the connection for
  use in our video call. Then we configure event handlers to get
  needed notifications on the call."
  []
  (log "Setting up a connection...")

  ;; Create an RTCPeerConnection which knows to use our chosen
  ;; STUN server.

  (let [peer-connection (js/RTCPeerConnection. options)]

    ;; Set up event handlers for the ICE negotiation process.

    (oset! (oget peer-connection "onicecandidate") (handle-ice-candidate-event))
    (oset! (oget peer-connection "oniceconnectionstatechange") (handle-ice-connection-state-change-event))
    (oset! (oget peer-connection "onicegatheringstatechange") (handle-ice-gathering-state-change-event))
    (oset! (oget peer-connection "onsignalingstatechange") (handle-signaling-state-change-event))
    (oset! (oget peer-connection "onnegotiationneeded") (handle-negotiation-needed-event))
    (oset! (oget peer-connection "ontrack") (handle-track-event))
    )
  )