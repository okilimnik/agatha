(ns agatha.pvid
  (:require [oops.core :refer [oget oset! ocall]]
            ["jsbn" :refer [BigInteger]]
            ["js-sha256" :as sha256]
            ["secure-random" :as secureRandom]
            ["elliptic" :refer [ec]]))

(def EC (ec. "secp256k1"))

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
      {:r       @r
       :blinded blinded})))

(defn create-identities []
  (let [key! (ocall EC :genKeyPair)
        public (ocall key! :getPublic)
        y (ocall public :getY)
        identities (loop [result []]
                     (if (= (count result) 10)
                       result
                       (let [last-identity (if (empty? result) public (last result))
                             new-identity (-> EC
                                              (ocall :keyFromPublic
                                                     #js {:x (.toString (ocall key! :derive last-identity) "hex")
                                                          :y (.toString y "hex")}
                                                     "hex")
                                              (ocall :getPublic))]
                         (js/console.log (str "new-identity: " (ocall new-identity :encode "hex")))
                         (recur (conj result new-identity)))))]))