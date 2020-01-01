(ns agatha.pvid
  (:require [oops.core :refer [oget oset! ocall]]
            [re-frame.core :refer [subscribe dispatch]]
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

(defn create-identities [& args]
  (let [{:keys [entropy priv pub]} (first args)
        key! (if priv
               (ocall EC :keyPair #js {:priv priv :pub  pub})
               (ocall EC :genKeyPair #js {:entropy entropy}))
        public (ocall key! :getPublic)
        identities (loop [result []]
                     (if (= (count result) 10)
                       result
                       (let [last-identity (if (empty? result) public (last result))
                             new-identity (ocall last-identity :mul (ocall key! :getPrivate))]
                         (recur (conj result new-identity)))))]
    (dispatch [:write-to [[:user] {:name       "User"
                                   :private    (ocall key! :getPrivate "hex")
                                   :public     (ocall key! :getPublic "hex")
                                   :identities (map #(-> %
                                                         (ocall :getX)
                                                         (.toString 16))
                                                    identities)}]])))