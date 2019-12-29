(ns agatha.pvid
  (:require [oops.core :refer [oget oset! ocall]]
            [promesa.async-cljs :refer-macros [async]]
            [agatha.util :refer [await->]]
            ["node-rsa" :as NodeRSA]
            ["fs" :as fs]
            ["jsbn" :refer [BigInteger]]))

(def rsa-key (atom nil))
(def key-properties (atom nil))

(def write-file (fn [file data encoding]
                  (js/Promise. (fn [resolve reject]
                                 (ocall fs :writeFile file data encoding (fn [err]
                                                                           (if err
                                                                             (reject err)
                                                                             (resolve))))))))

(def read-file (fn [file encoding]
                 (js/Promise. (fn [resolve reject]
                                (ocall fs :readFile file encoding (fn [err buf]
                                                                    (if err
                                                                      (resolve nil)
                                                                      (resolve buf))))))))

(defn init-key-properties []
  (reset! key-properties {:E (-> @rsa-key
                                 (oget "keyPair.e")
                                 (.toString)
                                 (BigInteger.))
                          :N (oget @rsa-key "keyPair.n")
                          :D (oget @rsa-key "keyPair.d")}))

(defn create-new-key []
  (async
    (try
      (do
        (reset! rsa-key (NodeRSA. #js {:b 4096}))
        (await-> (write-file "./private_key.pem" (ocall @rsa-key :exportKey "private") "utf8"))
        (await-> (write-file "./public_key.pem" (ocall @rsa-key :exportKey "public") "utf8"))
        (init-key-properties)
        (js/console.log "Successfully created new signature key"))
      (catch js/Error e (js/console.log "Failed to create new signature key")))))

(defn init []
  (async
    (let [private-key (await-> (read-file "./private_key.pem" "utf-8"))
          public-key (await-> (read-file "./public_key.pem" "utf-8"))]
      (if (and private-key public-key)
        (let [saved-key (-> (NodeRSA.)
                            (ocall :importKey (.toString private-key) "private")
                            (ocall :importKey (.toString public-key) "public"))]
          (reset! rsa-key saved-key)
          (init-key-properties)
          (js/console.log "Successfully read signature key components"))
        (create-new-key)))))

(defn sign [blinded]
  (let [{:keys [N D]} @key-properties]
    (-> blinded
        (.toString)
        (BigInteger.)
        (ocall :modPow D N)
        (.toString))))