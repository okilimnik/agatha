(ns agatha.net
  (:require [oops.core :refer [ocall oset! oget]]
            [cljs.reader :as edn]
            [clojure.core.async :refer [go]]))

(def connection (atom nil))
(def client-id (atom nil))
(def client-username (atom nil))
(def target-username (atom nil))
(def peer-connection (atom nil))
(def transceiver (atom nil))
(def webcam-stream (atom nil))

(defn log
  "Output logging information to console"
  [& args]
  (js/console.log (str "[" (.toLocaleTimeString (js/Date.)) "] " (apply str args))))

(defn log-error
  "Output an error information to console"
  [& args]
  (js/console.trace (str "[" (.toLocaleTimeString (js/Date.)) "] " (apply str args))))

(defn report-error
  "Handles reporting errors. Currently, we just dump stuff to console but
  in a real-world application, an appropriate (and user-friendly)
  error message should be displayed."
  [err-message]
  (log-error "Error " (oget err-message "name") ": " (oget err-message "message")))

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
  (reset! client-username (oget (ocall js/document :getElementById "name") "value"))
  (send-to-server #js {:name @client-username
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
                       :id   @client-id
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
  (log "*** Negotiation needed")

  (try
    (do (log "---> Creating offer")
        (-> peer-connection
            (.createOffer)
            (.then (fn [offer]
                     ;;  If the connection hasn't yet achieved the "stable" state,
                     ;; return to the caller. Another negotiationneeded event
                     ;; will be fired when the state stabilizes.
                     (if-not (= (oget peer-connection "signalingState" "stable"))
                       (log "     -- The connection isn't stable yet; postponing...")

                       ;; Establish the offer as the local peer's current
                       ;; description
                       (do (log "---> Setting local description to the offer")
                           (-> peer-connection
                               (.setLocalDescription offer)
                               (.then (fn []
                                        ;; Send the offer to the remote peer.
                                        (log "---> Sending the offer to the remote peer")
                                        (send-to-server #js {:name   @client-username
                                                             :target @target-username
                                                             :type   "video-offer"
                                                             :sdp    (oget @peer-connection "localDescription")}))))))))
            (.catch (fn [err]
                      (log "*** The following error occurred while handling the negotiationneeded event:")
                      (report-error err)))))))

(defn handle-track-event
  "Called by the WebRTC layer when events occur on the media tracks
   on our WebRTC call. This includes when streams are added to and
   removed from the call.

   track events include the following fields:

   RTCRtpReceiver       receiver
   MediaStreamTrack     track
   MediaStream[]        streams
   RTCRtpTransceiver    transceiver

   In our case, we're just taking the first stream found and attaching
   it to the <video> element for incoming media."
  [event]
  (log "*** Track event")
  (oset! (oget (ocall js/document :getElementById "received_video") "srcObject") (nth (oget event "streams") 0))
  (oset! (oget (ocall js/document :getElementById "hangup_button") "disabled") false))

(defn handle-ice-candidate-event
  "Handles |icecandidate| events by forwarding the specified
  ICE candidate (created by our local ICE agent) to the other
  peer through the signaling server."
  [event]
  (when (oget event "candidate")
    (log "*** Outgoing ICE candidate: " (oget event "candidate.candidate"))

    (send-to-server #js {:type      "new-ice-candidate"
                         :target    @target-username
                         :candidate (oget event "candidate")})))

(defn handle-ice-connection-state-change-event
  "Handle |iceconnectionstatechange| events. This will detect
  when the ICE connection is closed, failed, or disconnected.

  This is called when the state of the ICE agent changes."
  [_]
  (let [state (oget @peer-connection "iceConnectionState")]
    (log "*** ICE connection state changed to " state)

    (when (or (= state "closed")
              (= state "failed")
              (= state "disconnected"))
      (close-video-call))))

(defn handle-signaling-state-change-event
  "Set up a |signalingstatechange| event handler. This will detect when
  the signaling connection is closed.

  NOTE: This will actually move to the new RTCPeerConnectionState enum
  returned in the property RTCPeerConnection.connectionState when
  browsers catch up with the latest version of the specification!"
  [_]
  (let [state (oget @peer-connection "signalingState")]
    (log "*** WebRTC signaling state changed to: " state)
    (when (= state "closed")
      (close-video-call))))

(defn handle-ice-gathering-state-change-event
  "Handle the |icegatheringstatechange| event. This lets us know what the
  ICE engine is currently working on: 'new' means no networking has happened
  yet, 'gathering' means the ICE engine is currently gathering candidates,
  and 'complete' means gathering is complete. Note that the engine can
  alternate between 'gathering' and 'complete' repeatedly as needs and
  circumstances change.

  We don't need to do anything when this happens, but we log it to the
  console so you can see what's going on when playing with the sample."
  [_]
  (log "*** ICE gathering state changed to: " (oget @peer-connection "iceGatheringState")))

(def options #js {;:ordered              false      ;; If the data channel should guarantee order or not
                  ;:maxPacketLifeTime    3000       ;; The maximum time to try and retransmit a failed message
                  ;:maxRetransmits    5                      ;; The maximum number of times to try and retransmit a failed message
                  ;:protocol          "?"                    ;; Allows a subprotocol to be used which provides meta information towards the application
                  ;:negotiated        false                  ;;  If set to true, it removes the automatic setting up of a data channel on the other peer, meaning that you are provided your own way to create a data channel with the same id on the other side
                  ;:id                "?"                    ;; Allows you to provide your own ID for the channel (can only be used in combination with negotiated set to true)
                  :iceServers #js ["stun:stun.l.google.com:19302"]
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
  (go
    (log "Setting up a connection...")

    ;; Create an RTCPeerConnection which knows to use our chosen
    ;; STUN server.

    (reset! peer-connection (js/RTCPeerConnection. options))

    ;; Set up event handlers for the ICE negotiation process.

    (oset! (oget peer-connection "onicecandidate") handle-ice-candidate-event)
    (oset! (oget peer-connection "oniceconnectionstatechange") handle-ice-connection-state-change-event)
    (oset! (oget peer-connection "onicegatheringstatechange") handle-ice-gathering-state-change-event)
    (oset! (oget peer-connection "onsignalingstatechange") handle-signaling-state-change-event)
    (oset! (oget peer-connection "onnegotiationneeded") handle-negotiation-needed-event)
    (oset! (oget peer-connection "ontrack") handle-track-event)))