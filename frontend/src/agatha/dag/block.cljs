(ns agatha.dag.block
  (:require ["crypto-js/sha256" :as sha256]))

(defn calculate-hash [block]
  (-> (sha256 (+ (:timestamp block)
                 (:previousHash block)
                 (js/JSON.stringify (clj->js (:transactions block)))
                 (:nonce block)))
      (.toString)))

(defn make-block [block]
  (let [data (merge {:timestamp (js/Date.now)
                     :previousHash ""
                     :nonce        0
                     :transactions []}
                    block)]
    (assoc data :hash (calculate-hash data))))