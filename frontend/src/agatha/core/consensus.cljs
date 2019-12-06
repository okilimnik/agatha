(ns agatha.core.consensus
  (:require [cljs.spec.alpha :as s]
            [cljs.spec.test.alpha :as stest]))

(defn digits
  "Takes just an int and returns the set of its digit characters."
  [just-an-int]
  (into #{} (str just-an-int)))

(s/fdef digits
        :args (s/cat :just-an-int int?)
        :ret (s/coll-of char? :kind set? :min-count 1))

(stest/instrument `digits)

(stest/check `digits)