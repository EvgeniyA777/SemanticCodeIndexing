(ns semantic-code-indexing.runtime.languages.clojure
  (:require [semantic-code-indexing.runtime.adapters :as adapters]))

(defn parse-file [root-path path lines parser-opts]
  (adapters/parse-clojure-file root-path path lines parser-opts))
