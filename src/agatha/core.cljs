(ns agatha.core
  (:require [oops.core :refer [oget oset! ocall]]
            [clojure.string]
            [agatha.util :refer [read-config]]
            ["websocket" :refer [server]]
            ["http" :as http]
            ["https" :as https]
            ["fs" :as fs]
            ["finalhandler" :as finalhandler]
            ["serve-static" :as serve-static]
            ["node-turn" :as turn]
            ["minimist" :as minimist]))

;; Pathnames of the SSL key and certificate files to use for
;; HTTPS connections.

;(def key-file-path "/etc/pki/tls/private/mdn-samples.mozilla.org.key")
;(def cert-file-path "/etc/pki/tls/certs/mdn-samples.mozilla.org.crt")

;; Servers and connection options

(def config (atom {}))
(def web-server (atom nil))
(def https-options (atom {}))
(def ws-server (atom nil))

;; Used for managing the text chat user list.

(def connection-array (atom []))
(def next-id (atom (js/Date.now)))
(def append-to-make-unique (atom 1))

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
  (not (some #(= username (oget % "?username")) @connection-array)))

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
       :users (clj->js (mapv #(oget % "username") @connection-array))
       :date  (js/Date.now)})

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

(defn on-message
  "Set up a handler for the 'message' event received over WebSocket. This
  is a message sent by a client, and may be text to share with other
  users, a private message (text or signaling) for one user, or a command
  to the server."
  [message]
  (when (= (oget message "type") "utf8")
    (log "Received Message: " (oget message "utf8Data"))

    ;; Process incoming data.

    (let [send-to-clients (atom true)
          msg (js/JSON.parse (oget message "utf8Data"))
          connect (get-connection-for-id (oget msg "id"))]

      ;; Take a look at the incoming object and act on it based
      ;; on its type. Unknown message types are passed through,
      ;; since they may be used to implement client-side features.
      ;; Messages with a "target" property are sent only to a user
      ;; by that name.

      (case (oget msg "type")
        ;; Public, textual message
        "message" (do (oset! msg "!username" (oget connect "username"))
                      (oset! msg "!text" (clojure.string/replace (oget msg "text") #"/(<([^>]+)>)/ig" "")))

        ;; Username change
        "username" (let [name-changed? (atom false)
                         orig-name (oget msg "username")]

                     ;; Ensure the name is unique by appending a number to it
                     ;; if it's not; keep trying that until it works.

                     (loop []
                       (when-not (is-username-unique? (oget msg "username"))
                         (oset! msg "!username" (str orig-name @append-to-make-unique))
                         (swap! append-to-make-unique inc)
                         (reset! name-changed? true)
                         (recur)))

                     ;; If the name had to be changed, we send a "rejectusername"
                     ;; message back to the user so they know their name has been
                     ;; altered by the server.

                     (when @name-changed?
                       (ocall connect :sendUTF (js/JSON.stringify #js {:id       (oget msg "id")
                                                                       :type     "rejectusername"
                                                                       :date     (js/Date.now)
                                                                       :username (oget msg "name")})))

                     ;; Set this connection's final username and send out the
                     ;; updated user list to all users. Yeah, we're sending a full
                     ;; list instead of just updating. It's horribly inefficient
                     ;; but this is a demo. Don't do this in a real app.

                     (oset! connect "!username" (oget msg "username"))
                     (send-user-list-to-all)
                     (reset! send-to-clients false))
        "default")

      ;; Convert the revised message back to JSON and send it out
      ;; to the specified client or all clients, as appropriate. We
      ;; pass through any messages not specifically handled
      ;; in the select block above. This allows the clients to
      ;; exchange signaling and other control objects unimpeded.

      (when @send-to-clients
        (let [msg-string (js/JSON.stringify msg)]

          ;; If the message specifies a target username, only send the
          ;; message to them. Otherwise, send it to every user.

          (if-not (clojure.string/blank? (oget msg "target"))
            (send-to-one-user (oget msg "target") msg-string)
            (mapv #(ocall % :sendUTF msg-string) connection-array)))))))

(defn on-close
  "Handle the WebSocket 'close' event; this means a user has logged off
  or has been disconnected."
  [connection reason description]

  ;; First, remove the connection from the list of connections.

  (reset! connection-array (filterv #(oget % "connected") @connection-array))

  ;; Now send the updated user list. Again, please don't do this in a
  ;; real application. Your users won't like you very much.

  (send-user-list-to-all)

  ;; Build and output log output for close information.

  (log "Connection closed: " (oget connection "remoteAddress") " (" reason
       (if (clojure.string/blank? description) "" (str ": " description)) ")"))

(defn on-request
  "Set up a 'connect' message handler on our WebSocket server. This is
  called whenever a user connects to the server's port using the
  WebSocket protocol."
  [request]
  (let [origin (oget request "origin")]
    (if-not (origin-is-allowed? origin)
      (do (ocall request :reject)
          (log "Connection from " origin " rejected."))

      ;; Accept the request and get a connection.

      (let [connection (ocall request :accept "json" origin)]

        ;; Add the new connection to our list of connections.

        (log "Connection accepted from " (oget connection "remoteAddress") ".")
        (swap! connection-array conj connection)

        (oset! connection "!clientID" @next-id)
        (swap! next-id inc)

        ;; Send the new client its token; it send back a "username" message to
        ;; tell us what username they want to use.

        (ocall connection :sendUTF (js/JSON.stringify #js {:type "id"
                                                           :id   (oget connection "clientID")
                                                           :date (js/Date.now)}))

        (ocall connection :on "message" on-message)
        (ocall connection :on "close" (partial on-close connection))))))

(defn start
  []
  (reset! config (read-config))

  (js/console.log @config)
  ;; Try to load the key and certificate files for SSL so we can
  ;; do HTTPS (required for non-local WebRTC).

  (try
    (do (swap! https-options assoc :key (ocall fs :readFileSync (get-in @config [:ssl :key])))
        (swap! https-options assoc :cert (ocall fs :readFileSync (get-in @config [:ssl :cert]))))
    (catch js/Error e (reset! https-options {})))

  ;; If we were able to get the key and certificate files, try to
  ;; start up an HTTPS server.

  (try
    (when-not (empty? @https-options)
      (let [serve (serve-static "./public")]
        (reset! web-server (ocall https :createServer (clj->js @https-options) (fn [req res] (serve req res (finalhandler req res)))))))
    (catch js/Error e (do (reset! web-server nil)
                          (log "Error attempting to create HTTPS server: " (ocall e :toString)))))

  (when-not @web-server
    (try
      (let [serve (serve-static "./public")]
        (reset! web-server (ocall http :createServer #js {} (fn [req res] (serve req res (finalhandler req res))))))
      (catch js/Error e (do (reset! web-server nil)
                            (log "Error attempting to create HTTP server: " (ocall e :toString))))))

  ;; Spin up the HTTPS server on the port assigned to this sample.
  ;; This will be turned into a WebSocket port very shortly.

  (let [host (get-in @config [:host])
        port (get-in @config [:port])]
    (ocall @web-server :listen port host #(log "Server is listening on " host ":" port)))

  ;; Create the WebSocket server by converting the HTTPS server into one.

  (reset! ws-server (server. #js {:httpServer            @web-server
                                  :autoAcceptConnections false}))

  (when-not @ws-server (log "ERROR: Unable to create WebSocket server!"))

  (ocall @ws-server :on "request" on-request)

  (comment (-> (turn. (clj->js {:listeningPort (get-in @config [:turn :port])
                                :listeningIps  [(get-in @config [:host])]
                                :minPort       (get-in @config [:turn :minPort])
                                :maxPort       (get-in @config [:turn :maxPort])
                                :authMech      (get-in @config [:turn :authMech])
                                :credentials   {(keyword (get-in @config [:turn :username])) (get-in @config [:turn :password])}
                                :realm         (get-in @config [:turn :realm])
                                :debugLevel    (get-in @config [:turn :debugLevel])
                                :debug         (fn [level message]
                                                 (log level ":" message))}))
               (ocall :start))))

(defn reload!
  []
  (println "Code updated."))

(defn main!
  []
  (start))