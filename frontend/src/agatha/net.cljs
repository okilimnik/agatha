(ns agatha.net
  (:require [oops.core :refer [ocall oset! oget]]
            [cljs.reader :as edn]))


(comment (def options #js {:ordered              false      ;; If the data channel should guarantee order or not
                           :maxPacketLifeTime    3000       ;; The maximum time to try and retransmit a failed message
                           ;:maxRetransmits    5                      ;; The maximum number of times to try and retransmit a failed message
                           ;:protocol          "?"                    ;; Allows a subprotocol to be used which provides meta information towards the application
                           ;:negotiated        false                  ;;  If set to true, it removes the automatic setting up of a data channel on the other peer, meaning that you are provided your own way to create a data channel with the same id on the other side
                           ;:id                "?"                    ;; Allows you to provide your own ID for the channel (can only be used in combination with negotiated set to true)
                           :iceServers           #js ["stun:stun.l.google.com:19302"]
                           :iceTransportPolicy   "all"      ;; "relay"
                           :iceCandidatePoolSize 5          ;; 0 - 10
                           })

         (defn write-servers-to-local-storage []
           (ocall (oget js/window :localStorage) :setItem "servers" (prn-str ["stun:stun.l.google.com:19302"])))

         (defn read-servers-from-local-storage []
           (edn/read-string (ocall (oget js/window :localStorage) :getItem "servers")))
         ;;transports all, relay
         ;;iceCandidatePool 0 - 10

         (defn on-ice-candidate [pc event]
           (-> (get-other-pc pc)
               (.add-ice-candidate (oget event :candidate))
               (.then #(prn "AddIceCandidate success.")
                      #(prn "Failed to add Ice Candidate: " (ocall % :toString))))
           (prn (:name pc) " ICE candidate: \n" (oget event "?candidate.candidate")))

         (defn on-ice-gathering-state-change [conn]
           (when (= (ocall conn :iceGatheringState) "complete")
             (ocall conn :close)))

         (defn got-description [conn desc]
           (ocall conn :setLocalDescription desc)
           (prn "Offer from localConnection \n" (oget desc :sdp)))

         (defn init []
           (let [conn (js/RTCPeerConnection.)
                 chan (ocall conn :createDataChannel "myLabel" options)
                 servers []]
             (oset! chan :onicecandidate on-ice-candidate)
             (oset! chan :onicegatheringstatechange #(on-ice-gathering-state-change conn))
             (oset! chan :onicecandidateerror #(prn "ICE Candidate Error:" %))
             (oset! chan :onerror #(prn "Data Channel Error:" %))
             (oset! chan :onmessage #(prn "Got Data Channel Message:" (oget % :data)))
             (oset! chan :onopen #(ocall chan :send "Hello World!"))
             (oset! chan :onclose #(prn "The Data Channel is Closed"))

             (-> (ocall conn :createOffer)
                 (.then #(got-description conn %)
                        #(prn "Failed to create session description: " (ocall % :toString)))))))

(defn connect []
  nil)

(defn hang-up-call []
  nil)