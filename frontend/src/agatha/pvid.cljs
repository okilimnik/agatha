(ns agatha.pvid
  (:require [oops.core :refer [oget oset! ocall]]
            ["node-rsa" :as NodeRSA]
            ["jsbn" :refer [BigInteger]]
            ["js-sha256" :as sha256]
            ["secure-random" :as secureRandom]))

(defn blind [message N E]
  (let [message-hash (-> message
                         (sha256)
                         (BigInteger. 16))
        big-one (BigInteger. "1")
        r (atom nil)
        gcd (atom nil)
        N! (BigInteger. N)
        E! (BigInteger. E)]
    (loop []
      (when (or (not (.equals @gcd big-one))
              (>= (.compareTo @r N!))
              (<= (.compareTo @r big-one)))
        (do
          (reset! r (ocall (BigInteger. (secureRandom 64)) :mod N!))
          (reset! gcd (ocall @r :gcd N!))
          (recur))))
    (let [blinded (-> message-hash
                      (ocall :multiply (ocall @r :modPow E! N!))
                      (ocall :mod N!))]
      {:r @r
       :blinded blinded})))

(defn create-identities []
  (let [identities (loop [i 0
                          result []]
                     (if (= (count result) 10)
                       result
                       (recur (inc i) (conj result (NodeRSA. #js {:b 4096})))))]))