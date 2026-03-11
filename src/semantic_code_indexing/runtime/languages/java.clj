(ns semantic-code-indexing.runtime.languages.java
  (:require [semantic-code-indexing.runtime.adapters :as adapters]))

(defn parse-file [root-path path lines parser-opts]
  (adapters/parse-java-file root-path path lines parser-opts))
