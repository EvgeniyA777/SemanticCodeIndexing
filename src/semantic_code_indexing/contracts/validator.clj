(ns semantic-code-indexing.contracts.validator
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [semantic-code-indexing.contracts.schemas :as schemas]))

(def ^:private example-root "contracts/examples")
(def ^:private fixture-root "fixtures/retrieval")
(def ^:private schema-root "contracts/schemas")

(defn- read-json-file [path]
  (with-open [rdr (io/reader path)]
    (json/read rdr :key-fn keyword)))

(defn- json-files-under [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".json"))
       sort))

(defn- rel-path [path]
  (-> (io/file path)
      (.toPath)
      (.normalize)
      (.toString)))

(defn- schema-key-for-path [path]
  (let [p (rel-path path)]
    (cond
      (str/ends-with? p "contracts/examples/catalog.json") :example/catalog
      (str/includes? p "contracts/examples/queries/") :example/query
      (str/includes? p "contracts/examples/context-packets/") :example/context-packet
      (str/includes? p "contracts/examples/diagnostics/") :example/diagnostics-trace
      (str/includes? p "contracts/examples/events/") :example/stage-event
      (str/includes? p "contracts/examples/usage-events/") :example/usage-event
      (str/includes? p "contracts/examples/usage-feedback/") :example/usage-feedback
      (str/includes? p "contracts/examples/confidence/") :example/confidence
      (str/includes? p "contracts/examples/guardrails/") :example/guardrail-assessment
      (str/ends-with? p "contracts/examples/policy/override-record.json") :example/override-record
      (str/ends-with? p "contracts/examples/policy/human-review-approved.json") :example/human-review-record
      (str/ends-with? p "fixtures/retrieval/corpus.json") :fixture/corpus
      (and (str/includes? p "fixtures/retrieval/")
           (not (str/ends-with? p "fixtures/retrieval/corpus.json"))) :fixture/retrieval
      :else nil)))

(defn- validate-schema-json-file [path data]
  (let [missing (remove #(contains? data %) [:$schema :$id :title :description])]
    (if (empty? missing)
      {:path path :ok? true :schema :json-schema/meta}
      {:path path
       :ok? false
       :schema :json-schema/meta
       :errors [(str "schema json missing required keys: " (vec missing))]})))

(defn- validate-file [path]
  (let [data (read-json-file path)
        p (rel-path path)]
    (if (str/includes? p "contracts/schemas/")
      (validate-schema-json-file path data)
      (let [schema-k (schema-key-for-path path)]
        (if-not schema-k
          {:path path :ok? false :errors ["no schema mapping for path"]}
          (let [schema (get schemas/contracts schema-k)]
            (if-not schema
              {:path path :ok? false :errors [(str "unknown schema key: " schema-k)]}
              (if-let [explain (m/explain schema data)]
                {:path path
                 :ok? false
                 :schema schema-k
                 :errors [(pr-str (me/humanize explain))]}
                {:path path :ok? true :schema schema-k}))))))))

(defn- check-example-catalog-references []
  (let [catalog-path (str example-root "/catalog.json")
        catalog (read-json-file catalog-path)
        refs (:examples catalog)]
    (reduce
     (fn [acc {:keys [path schema example_id]}]
       (let [example-path (str example-root "/" path)
             schema-path (str example-root "/" schema)]
         (-> acc
             (cond-> (not (.exists (io/file example-path)))
               (conj {:path catalog-path
                      :ok? false
                      :errors [(str "example file missing for " example_id ": " example-path)]}))
             (cond-> (not (.exists (io/file schema-path)))
               (conj {:path catalog-path
                      :ok? false
                      :errors [(str "schema file missing for " example_id ": " schema-path)]})))))
     []
     refs)))

(defn- check-fixture-corpus-references []
  (let [corpus-path (str fixture-root "/corpus.json")
        corpus (read-json-file corpus-path)
        refs (:fixtures corpus)]
    (reduce
     (fn [acc {:keys [path fixture_id]}]
       (let [fixture-path (str fixture-root "/" path)]
         (cond-> acc
           (not (.exists (io/file fixture-path)))
           (conj {:path corpus-path
                  :ok? false
                  :errors [(str "fixture file missing for " fixture_id ": " fixture-path)]}))))
     []
     refs)))

(defn validate-contracts []
  (let [files (concat
               (json-files-under schema-root)
               (json-files-under example-root)
               (json-files-under fixture-root))
        file-results (map validate-file files)
        ref-results (concat
                     (check-example-catalog-references)
                     (check-fixture-corpus-references))
        failures (->> (concat file-results ref-results)
                      (remove :ok?)
                      vec)]
    {:checked (count files)
     :failures failures}))
