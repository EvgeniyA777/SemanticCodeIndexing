(ns semidx.runtime.semantic-ir
  (:require [clojure.string :as str]
            [semidx.runtime.semantic-id :as semantic-id]))

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

(defn- normalize-unit [file-path language parser-mode unit]
  (-> unit
      (assoc :path (or (:path unit) file-path)
             :language (or (:language unit) language)
             :parser_mode (or (:parser_mode unit) parser-mode)
             :semantic_pipeline semantic-pipeline-version)
      (update :imports #(distinct-vec (or % [])))
      (update :calls #(normalize-string-vec (or % [])))
      (update :call_tokens #(normalize-string-vec (or % [])))
      semantic-id/enrich-unit))

(defn finalize-parsed-file [file-path language parsed]
  (let [parsed* (if (map? parsed)
                  parsed
                  {:diagnostics [{:code "invalid_parsed_file"
                                  :severity "error"
                                  :message "Parser returned a non-map result; coercing to fallback shape."
                                  :path file-path
                                  :language (or language "unknown")}]
                   :imports []
                   :units []})
        parser-mode (or (:parser_mode parsed*) "fallback")
        effective-language (or (:language parsed*) language "unknown")
        units (mapv #(normalize-unit file-path effective-language parser-mode %) (:units parsed*))
        imports (distinct-vec (:imports parsed*))
        diagnostics (vec (or (:diagnostics parsed*) []))]
    (-> parsed*
        (assoc :language effective-language
               :module (:module parsed)
               :imports imports
               :units units
               :diagnostics diagnostics
               :parser_mode parser-mode
               :semantic_pipeline {:version semantic-pipeline-version
                                   :language effective-language
                                   :parser_mode parser-mode}))))
