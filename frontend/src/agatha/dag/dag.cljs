(ns agatha.dag.dag
  (:require [agatha.dag.block :as block]))

(defn create-genesis-block []
  (block/make-block {}))

(def DAG (atom
           {:graph               [(create-genesis-block)]
            :nodes               []
            :pendingTransactions []
            :miningReward        100}))

(defn create-tx [tx]
  (swap! DAG update :pendingTransactions conj tx))

(defn get-latest-block []
  (last (:graph @DAG)))

(defn mine-pending-txes [mining-reward-address]
  (let [block (block/make-block {:transactions (:pendingTransactions @DAG)
                                 :previousHash (:hash (get-latest-block))})]
    (swap! DAG update :graph conj block)
    (swap! DAG assoc :pendingTransactions [{:fromAddress nil
                                            :toAddress mining-reward-address
                                            :amount (:miningReward @DAG)}])))