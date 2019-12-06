(ns agatha.core
  (:require [oops.core :refer [oget oset! ocall]]
            [clojure.string]
            ["websocket" :refer [server]]
            ["http" :as http]
            ["https" :as https]
            ["fs" :as fs]))

;; Pathnames of the SSL key and certificate files to use for
;; HTTPS connections.

(def key-file-path "/etc/pki/tls/private/mdn-samples.mozilla.org.key")
(def cert-file-path "/etc/pki/tls/certs/mdn-samples.mozilla.org.crt")

;; Used for managing the text chat user list.

(def connection-array (atom []))
(def next-id js/Date.now)
(def append-to-make-unique 1)

(defn log
  "Output logging information to console"
  [text]
  (js/console.log (str "[" (.toLocaleTimeString (js/Date.)) "] " text)))

(defn origin-is-allowed?
  "If you want to implement support for blocking specific origins, this is
  where you do it. Just return false to refuse WebSocket connections given
  the specified origin.
  We will accept all connections."
  [origin]
  true)

(defn is-username-unique?
  "Scans the list of users and see if the specified name is unique. If it is,
  return true. Otherwise, returns false. We want all users to have unique
  names."
  [username]
  (assert (not (clojure.string/blank? username)))
  (not (some #(= username (oget % "username")) @connection-array)))

(defn send-to-one-user
  "Sends a message (which is already stringified JSON) to a single
  user, given their username. We use this for the WebRTC signaling,
  and we could use it for private text messaging."
  [target msg-string]
  (ocall (first (filter #(= target (oget % "username")) @connection-array)) :sendUTF msg-string))

(defn get-connection-for-id
  "Scan the list of connections and return the one for the specified
  clientID. Each login gets an ID that doesn't change during the session,
  so it can be tracked across username changes."
  [id]
  (first (filter #(= id (oget % "clientID")) @connection-array)))

(defn make-user-list-message
  "Builds a message object of type \"userlist\" which contains the names of
  all connected users. Used to ramp up newly logged-in users and,
  inefficiently, to handle name change notifications."
  []
  {:type "userlist"
   :users (mapv #(oget % "username") @connection-array)})

(defn send-user-list-to-all
  "Sends a 'userlist' message to all chat members. This is a cheesy way
  to ensure that every join/drop is reflected everywhere. It would be more
  efficient to send simple join/drop messages to each user, but this is
  good enough for this simple example."
  []
  (let [user-list-msg-str (prn-str (make-user-list-message))]
    (mapv #(ocall % :sendUTF user-list-msg-str) @connection-array)))



(defn reload! []
  (println "Code updated."))

(defn main! []
  )