(ns agatha.util
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

#?(:clj
   (defmacro read-config []
     (edn/read-string (slurp "./config.edn"))))