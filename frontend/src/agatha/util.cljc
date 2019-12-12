(ns agatha.util
  (:require [promesa.core :as p]))

#?(:clj
   (defmacro await-> [thenable & thens]
     `(-> ~thenable
          ~@thens
          ~'js/Promise.resolve
          p/await)))