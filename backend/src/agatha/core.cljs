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
(def next-id (atom (js/Date.now)))
(def append-to-make-unique 1)

(defn log
  "Output logging information to console"
  [& args]
  (js/console.log (str "[" (.toLocaleTimeString (js/Date.)) "] " (apply str args))))

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
  #js {:type  "userlist"
       :users (clj->js (mapv #(oget % "username") @connection-array))})

(defn send-user-list-to-all
  "Sends a 'userlist' message to all chat members. This is a cheesy way
  to ensure that every join/drop is reflected everywhere. It would be more
  efficient to send simple join/drop messages to each user, but this is
  good enough for this simple example."
  []
  (let [user-list-msg-str (js/JSON.stringify (make-user-list-message))]
    (mapv #(ocall % :sendUTF user-list-msg-str) @connection-array)))

(defn handle-web-request
  "Our HTTPS server does nothing but service WebSocket
  connections, so every request just returns 404. Real Web
  requests are handled by the main server on the box. If you
  want to, you can return real HTML here and serve Web content."
  [request response]
  (log "Received request for " (oget request "url"))
  (ocall response :writeHead 404)
  (ocall response :end))

;; Try to load the key and certificate files for SSL so we can
;; do HTTPS (required for non-local WebRTC).

(def https-options (atom {}))

(try
  (do (swap! https-options assoc :key (ocall fs :readFileSync key-file-path))
      (swap! https-options assoc :cert (ocall fs :readFileSync cert-file-path)))
  (catch js/Error e (reset! https-options {})))

;; If we were able to get the key and certificate files, try to
;; start up an HTTPS server.

(def web-server (atom nil))

(try
  (when-not (empty? @https-options)
    (reset! web-server (ocall https :createServer (clj->js @https-options) handle-web-request)))
  (catch js/Error e (reset! web-server nil)))

(when-not @web-server
  (try
    (reset! web-server (ocall http :createServer #js {} handle-web-request))
    (catch js/Error e (do (reset! web-server nil)
                          (log "Error attempting to create HTTP(s) server: " (ocall e :toString))))))

;; Spin up the HTTPS server on the port assigned to this sample.
;; This will be turned into a WebSocket port very shortly.

(ocall @web-server :listen 6503 #(log "Server is listening on port 6503"))

;; Create the WebSocket server by converting the HTTPS server into one.

(def ws-server (atom (server. #js {:httpServer            @web-server
                                   :autoAcceptConnections false})))

(when-not @ws-server
  (log "ERROR: Unable to create WebSocket server!"))

;; Set up a "connect" message handler on our WebSocket server. This is
;; called whenever a user connects to the server's port using the
;; WebSocket protocol.

(ocall @ws-server :on "request"
       (fn [request]
         (let [origin (oget request "origin")]
           (if-not (origin-is-allowed? origin)
             (do (ocall request :reject)
                 (log "Connection from " origin " rejected."))

             ;; Accept the request and get a connection.

             (let [connection (ocall request :accept "json" origin)]

               ;; Add the new connection to our list of connections.

               (log "Connection accepted from " + (oget connection "remoteAddress") + ".")
               (swap! connection-array conj connection)

               (oset! connection "clientID" @next-id)
               (swap! next-id inc)

               ;; Send the new client its token; it send back a "username" message to
               ;; tell us what username they want to use.

               (ocall connection :sendUTF (js/JSON.stringify #js {:type "id" :id (oget connection "clientID")}))

               ;; Set up a handler for the "message" event received over WebSocket. This
               ;; is a message sent by a client, and may be text to share with other
               ;; users, a private message (text or signaling) for one user, or a command
               ;; to the server.

               (ocall connection :on  "message"
                      (fn [message]
                        (when (= (oget message "type") "utf8")
                          (log "Received Message: " (oget message "utf8Data"))

                          ;; Process incoming data.

                          (let [msg (js/JSON.parse (oget message "utf8Data"))
                                connect (get-connection-for-id (oget msg "id"))]

                            ;; Take a look at the incoming object and act on it based
                            ;; on its type. Unknown message types are passed through,
                            ;; since they may be used to implement client-side features.
                            ;; Messages with a "target" property are sent only to a user
                            ;; by that name.

                            (case (oget msg "type")
                              ;; Public, textual message
                              "message" (do (oset! msg "name" (oget connect "username"))
                                            (oset! msg "text" (clojure.string/replace (oget msg "text") #"/(<([^>]+)>)/ig" "")))

                              ;; Username change
                              "username" (do )

                              )

                            )
                          )))
               )

             ))))


(defn reload! []
  (println "Code updated."))

(defn main! []
  )