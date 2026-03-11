(ns semantic-code-indexing.runtime.languages.elixir
  (:require [semantic-code-indexing.runtime.adapters :as adapters]))

(defn parse-file [_root-path path lines _parser-opts]
  (adapters/parse-elixir-file path lines))
