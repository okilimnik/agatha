(ns agatha.net
  (:require [oops.core :refer [ocall oset! oget]]
            [cljs.reader :as edn]
            [promesa.core :as p]
            [promesa.async-cljs :refer-macros [async]]))

(defmacro await-> [thenable & thens]
  `(-> ~thenable
       ~@thens
       ~'js/Promise.resolve
       p/await))

(def connection (atom nil))
(def client-id (atom nil))
(def client-username (atom nil))
(def target-username (atom nil))
(def peer-connection (atom nil))
(def transceiver (atom nil))
(def webcam-stream (atom nil))

(def media-constraints #js {:audio true                     ;;We want an audio track
                            :video #js {:aspectRatio #js {:ideal 1.333333}}}) ;; 3:2 aspect is preferred

(declare create-peer-connection)
(declare invite)

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

(defn handle-userlist-msg
  "Given a message containing a list of usernames, this function
  populates the user list box with those names, making each item
  clickable to allow starting a video call."
  [msg]
  (let [list-item (-> js/document
                      (ocall :querySelector ".userlistbox"))]

    ;; Remove all current list members. We could do this smarter,
    ;; by adding and updating users instead of rebuilding from
    ;; scratch but this will do for this sample.

    (while (oget list-item "firstChild")
      (ocall list-item :removeChild (oget list-item "firstChild")))

    ;; Add member names from the received list.

    (-> (oget msg "users")
        (ocall :forEach
               (fn [username]
                 (let [item (ocall js/document :createElement "li")]
                   (ocall item :appendChild (ocall js/document :createTextNode username))
                   (ocall item :addEventListener "click" invite false)
                   (ocall list-item :appendChild item)))))))

(defn close-video-call
  "Close the RTCPeerConnection and reset variables so that the user can
  make or receive another call if they wish. This is called both
  when the user hangs up, the other user hangs up, or if a connection
  failure is detected."
  []
  (let [local-video (ocall js/document :getElementById "local_video")]

    (log "Closing the call")

    ;; Close the RTCPeerConnection

    (when @peer-connection
      (log "--> Closing the peer connection")

      ;; Disconnect all our event listeners; we don't want stray events
      ;; to interfere with the hangup while it's ongoing.

      (oset! @peer-connection :onicecandidate nil)
      (oset! @peer-connection :oniceconnectionstatechange nil)
      (oset! @peer-connection :onicegatheringstatechange nil)
      (oset! @peer-connection :onsignalingstatechange nil)
      (oset! @peer-connection :onnegotiationneeded nil)
      (oset! @peer-connection :ontrack nil)

      ;; Stop all transceivers on the connection

      (-> @peer-connection
          (ocall :getTransceivers)
          (ocall :forEach #(ocall % :stop)))

      ;; Stop the webcam preview as well by pausing the <video>
      ;; element, then stopping each of the getUserMedia() tracks
      ;; on it.

      (when (oget local-video "srcObject")
        (ocall local-video :pause)
        (-> (oget local-video "srcObject")
            (ocall :getTracks)
            (ocall :forEach #(ocall % :stop))))

      ;; Close the peer connection

      (ocall @peer-connection :close)
      (reset! peer-connection nil)
      (reset! webcam-stream nil))

    ;; Disable the hangup button

    (oset! (ocall js/document :getElementById "hangup_button") :disabled true)
    (reset! target-username nil)))

(defn handle-get-user-media-error
  "Handle errors which occur when trying to access the local media
  hardware; that is, exceptions thrown by getUserMedia(). The two most
  likely scenarios are that the user has no camera and/or microphone
  or that they declined to share their equipment when prompted. If
  they simply opted not to share their media, that's not really an
  error, so we won't present a message in that situation."
  [e]
  (log-error e)
  (case (oget e "name")
    "NotFoundError" (js/alert "Unable to open your call because no camera and/or microphone were found.")
    "SecurityError" nil                                     ;; Do nothing; this is the same as the user canceling the call.
    "PermissionDeniedError" nil                             ;; Do nothing; this is the same as the user canceling the call.
    (js/alert (str "Error opening your camera and/or microphone: " (oget e "message"))))

  ;; Make sure we shut down our end of the RTCPeerConnection so we're
  ;; ready to try again.

  (close-video-call))

(defn handle-hang-up-msg
  "Handle the 'hang-up' message, which is sent if the other peer
  has hung up the call or otherwise disconnected."
  [_]
  (log "*** Received hang up notification from other peer")

  (close-video-call))

(defn hang-up-call
  "Hang up the call by closing our end of the connection, then
  sending a 'hang-up' message to the other peer (keep in mind that
  the signaling is done on a different connection). This notifies
  the other peer that the connection should be terminated and the UI
  returned to the 'no call in progress' state."
  []
  (close-video-call)

  (send-to-server #js {:name   @client-username
                       :target @target-username
                       :type   "hang-up"}))

(defn handle-video-offer-msg
  "Accept an offer to video chat. We configure our local settings,
  create our RTCPeerConnection, get and attach our local camera
  stream, then create and send an answer to the caller."
  [msg]
  (async
    (reset! target-username (oget msg "name"))

    ;; If we're not already connected, create an RTCPeerConnection
    ;; to be linked to the caller.

    (log "Received video chat offer from " @target-username)
    (when-not @peer-connection
      (create-peer-connection))

    ;; We need to set the remote description to the received SDP offer
    ;; so that our local WebRTC layer knows how to talk to the caller.

    (let [desc (js/RTCSessionDescription. (oget msg "sdp"))]

      ;; If the connection isn't stable yet, wait for it...

      (if-not (= (oget @peer-connection "signalingState") "stable")
        (do (log "  - But the signaling state isn't stable, so triggering rollback")

            ;; Set the local and remove descriptions for rollback; don't proceed
            ;; until both return.

            (await-> js/Promise
                     (ocall :all #js [(-> @peer-connection
                                          (ocall :setLocalDescription #js {:type "rollback"}))
                                      (-> @peer-connection
                                          (ocall :setRemoteDescription desc))])))

        (do (log "  - Setting remote description")
            (await-> @peer-connection
                     (ocall :setRemoteDescription desc))

            ;; Get the webcam stream if we don't already have it

            (when-not @webcam-stream
              (try
                (reset! webcam-stream (await-> (oget js/navigator "mediaDevices")
                                               (ocall :getUserMedia media-constraints)))
                (catch js/Error e (handle-get-user-media-error e)))

              (-> js/document
                  (ocall :getElementById "local_video")
                  (oset! :srcObject @webcam-stream))

              ;; Add the camera stream to the RTCPeerConnection

              (try
                (-> @webcam-stream
                    (ocall :getTracks)
                    (ocall :forEach
                           (fn [track]
                             (reset! transceiver track)
                             (-> @peer-connection
                                 (ocall :addTransceiver track #js {:streams #js [@webcam-stream]})))))
                (catch js/Error e (handle-get-user-media-error e)))

              (log "---> Creating and sending answer to caller")

              (await-> @peer-connection
                       (ocall :setLocalDescription (await-> (ocall @peer-connection :createAnswer))))

              (send-to-server #js {:name @client-username
                                   :target @target-username
                                   :type "video-answer"
                                   :sdp (oget @peer-connection "localDescription")})))))))

(defn handle-video-answer-msg
  "Responds to the 'video-answer' message sent to the caller
  once the callee has decided to accept our request to talk."
  [msg]
  (async
    (log "*** Call recipient has accepted our call")

    ;; Configure the remote description, which is the SDP payload
    ;; in our "video-answer" message.

    (try
      (await-> @peer-connection
               (ocall :setRemoteDescription (js/RTCSessionDescription. (oget msg "sdp"))))
      (catch js/Error e (report-error e)))))

(defn handle-new-ice-candidate-msg
  "A new ICE candidate has been received from the other peer. Call
  RTCPeerConnection.addIceCandidate() to send it along to the
  local ICE framework."
  [msg]
  (async
    (let [candidate (js/RTCIceCandidate. (oget msg "candidate"))]

      (log "*** Adding received ICE candidate: " (js/JSON.stringify candidate))
      (try
        (await-> @peer-connection
                 (ocall :addIceCandidate candidate))
        (catch js/Error e (report-error e))))))

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
    (oset! @connection :onopen
           (fn [_]
             (oset! (ocall js/document :getElementById "text") :disabled false)
             (oset! (ocall js/document :getElementById "send") :disabled false)))
    (oset! @connection :onerror #(js/console.dir %))
    (oset! @connection :onmessage (partial on-message text))

    ;; If there's text to insert into the chat buffer, do so now, then
    ;; scroll the chat panel so that the new text is visible.

    (when (oget @text "length")
      (oset! chat-box :innerHTML (str (oget chat-box "innerHTML") @text))
      (oset! chat-box :scrollTop (- (oget chat-box "scrollHeight") (oget chat-box "clientHeight"))))))

(defn handle-send-button
  "Handles a click on the Send button (or pressing return/enter) by
  building a 'message' object and sending it to the server."
  []
  (send-to-server #js {:text (oget (ocall js/document :getElementById "text") "value")
                       :type "message"
                       :id   @client-id
                       :date (js/Date.now)})
  (oset! (ocall js/document :getElementById "text") :value ""))

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
  (async
    (log "*** Negotiation needed")

    (try
      (do (log "---> Creating offer")
          (let [offer (await-> (ocall @peer-connection :createOffer))]
            ;;  If the connection hasn't yet achieved the "stable" state,
            ;; return to the caller. Another negotiationneeded event
            ;; will be fired when the state stabilizes.
            (if-not (= (oget peer-connection "signalingState" "stable"))
              (log "     -- The connection isn't stable yet; postponing...")

              ;; Establish the offer as the local peer's current
              ;; description
              (do (log "---> Setting local description to the offer")
                  (await-> @peer-connection
                           (ocall :setLocalDescription offer))
                  ;; Send the offer to the remote peer.
                  (log "---> Sending the offer to the remote peer")
                  (send-to-server #js {:name   @client-username
                                       :target @target-username
                                       :type   "video-offer"
                                       :sdp    (oget @peer-connection "localDescription")})))))
      (catch js/Error e (do (log "*** The following error occurred while handling the negotiationneeded event:")
                            (report-error e))))))

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
  (oset! (ocall js/document :getElementById "received_video") :srcObject (nth (oget event "streams") 0))
  (oset! (ocall js/document :getElementById "hangup_button") :disabled false))

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
  (async
    (log "Setting up a connection...")

    ;; Create an RTCPeerConnection which knows to use our chosen
    ;; STUN server.

    (reset! peer-connection (js/RTCPeerConnection. options))

    ;; Set up event handlers for the ICE negotiation process.

    (oset! @peer-connection :onicecandidate handle-ice-candidate-event)
    (oset! @peer-connection :oniceconnectionstatechange handle-ice-connection-state-change-event)
    (oset! @peer-connection :onicegatheringstatechange handle-ice-gathering-state-change-event)
    (oset! @peer-connection :onsignalingstatechange handle-signaling-state-change-event)
    (oset! @peer-connection :onnegotiationneeded handle-negotiation-needed-event)
    (oset! @peer-connection :ontrack handle-track-event)))

(defn invite
  "Handle a click on an item in the user list by inviting the clicked
  user to video chat. Note that we don't actually send a message to
  the callee here -- calling RTCPeerConnection.addTrack() issues
  a |notificationneeded| event, so we'll let our handler for that
  make the offer."
  [evt]
  (async
    (log "Starting to prepare an invitation")
    (if @peer-connection
      (js/alert "You can't start a call because you already have one open!")
      (let [clicked-username (oget evt "target.textContent")]

        ;; Don't allow users to call themselves, because weird.

        (if (= clicked-username @client-username)
          (js/alert "I'm afraid I can't let you talk to yourself. That would be weird.")
          (do

            ;; Record the username being called for future reference

            (reset! target-username clicked-username)
            (log "Inviting user " @target-username)

            ;; Call createPeerConnection() to create the RTCPeerConnection.
            ;; When this returns, peer-connection is our RTCPeerConnection
            ;; and webcam-stream is a stream coming from the camera. They are
            ;; not linked together in any way yet.

            (log "Setting up connection to invite user: " @target-username)
            (create-peer-connection)

            ;; Get access to the webcam stream and attach it to the
            ;; "preview" box (id "local_video").

            (try
              (do (reset! webcam-stream (await-> (oget js/navigator "mediaDevices")
                                                 (ocall :getUserMedia media-constraints)))
                  (-> js/document
                      (ocall :getElementById "local_video")
                      (oset! :srcObject @webcam-stream)))
              (catch js/Error e (handle-get-user-media-error e)))

            ;; Add the tracks from the stream to the RTCPeerConnection

            (try
              (-> @webcam-stream
                  (ocall :getTracks)
                  (ocall :forEach
                         (fn [track]
                           (reset! transceiver track)
                           (-> @peer-connection
                               (ocall :addTransceiver track #js {:streams #js [@webcam-stream]})))))
              (catch js/Error e (handle-get-user-media-error e)))))))))