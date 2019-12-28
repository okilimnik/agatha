(ns agatha.util
  (:require [promesa.core :as p]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

#?(:clj
   (defmacro await-> [thenable & thens]
     `(-> ~thenable
          ~@thens
          ~'js/Promise.resolve
          p/await)))

#?(:clj
   (defmacro read-config []
     (edn/read-string (slurp "../config.edn"))))