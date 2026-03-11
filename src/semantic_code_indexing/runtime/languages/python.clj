(ns semantic-code-indexing.runtime.languages.python
  (:require [semantic-code-indexing.runtime.adapters :as adapters]))

(defn parse-file [_root-path path lines _parser-opts]
  (adapters/parse-python-file path lines))
