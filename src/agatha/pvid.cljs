(ns agatha.pvid
  (:require [oops.core :refer [oget oset! ocall]]
            ["node-rsa" :as NodeRSA]
            ["fs" :as fs]
            ["jsbn" :refer [BigInteger]]))

(def private-key (atom nil))
(def key-properties (atom nil))

(defn init []
  (ocall fs :readFile "./signature.pem" "utf-8"
         (fn [err buf]
           (if err
             (do (reset! private-key (NodeRSA. #js {:b 2048}))
                 (ocall fs :writeFile "./signature.pem" (ocall @private-key :exportKey "pkcs8") "utf8"
                        (fn [err]
                          (if err
                            (js/console.error "Error saving signature file")
                            (js/console.log "Successfully created and saved signature file")))))
             (do
               (reset! private-key (ocall (NodeRSA.) :importKey (.toString buf) "pkcs8"))
               (js/console.log "Successfully read signature file")))

           (reset! key-properties {:E (-> @private-key
                                          (oget "keyPair.e")
                                          (.toString)
                                          (BigInteger.))
                                   :N (oget @private-key "keyPair.n")
                                   :D (oget @private-key "keyPair.d")}))))

(defn sign [blinded]
  (let [{:keys [N D]} @key-properties]
    (-> blinded
        (.toString)
        (BigInteger.)
        (ocall :modPow D N))))