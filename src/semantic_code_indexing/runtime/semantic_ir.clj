(ns semantic-code-indexing.runtime.semantic-ir
  (:require [clojure.string :as str]))

(def ^:private semantic-pipeline-version "v1")

(defn pipeline-version []
  semantic-pipeline-version)

(defn- distinct-vec [xs]
  (->> xs
       (remove nil?)
       distinct
       vec))

(defn- normalize-string-vec [xs]
  (->> xs
       (map str)
       (remove str/blank?)
       distinct
       vec))

(defn- normalize-unit [file-path parser-mode unit]
  (-> unit
      (assoc :path (or (:path unit) file-path)
             :parser_mode (or (:parser_mode unit) parser-mode)
             :semantic_pipeline semantic-pipeline-version)
      (update :imports #(distinct-vec (or % [])))
      (update :calls #(normalize-string-vec (or % [])))
      (update :call_tokens #(normalize-string-vec (or % [])))))

(defn finalize-parsed-file [file-path language parsed]
  (let [parser-mode (or (:parser_mode parsed) "fallback")
        units (mapv #(normalize-unit file-path parser-mode %) (:units parsed))
        imports (distinct-vec (:imports parsed))
        diagnostics (vec (or (:diagnostics parsed) []))]
    (-> parsed
        (assoc :language (or (:language parsed) language "unknown")
               :module (:module parsed)
               :imports imports
               :units units
               :diagnostics diagnostics
               :parser_mode parser-mode
               :semantic_pipeline {:version semantic-pipeline-version
                                   :language (or (:language parsed) language "unknown")
                                   :parser_mode parser-mode}))))
