(ns semantic-code-indexing.runtime.evaluation
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [semantic-code-indexing.core :as sci]))

(def ^:private confidence-rank {"low" 0 "medium" 1 "high" 2})
(def ^:private feedback-score-map
  {"helpful" 1.0
   "partially_helpful" 0.5
   "not_helpful" 0.0
   "abandoned" -0.25})

(def ^:private issue-penalty
  {"resolved_target_correct" 0.0
   "missing_authority" 0.45
   "wrong_scope" 0.35
   "too_broad" 0.20
   "too_shallow" 0.20
   "latency_too_high" 0.10
   "confidence_miscalibrated" 0.15})

(defn- read-json [path]
  (with-open [rdr (io/reader path)]
    (json/read rdr :key-fn keyword)))

(defn- write-json [path data]
  (with-open [w (io/writer path)]
    (json/write data w :indent true)))

(defn- read-edn [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))]
    (edn/read rdr)))

(defn feedback-score [feedback]
  (let [base (get feedback-score-map (:feedback_outcome feedback) 0.0)
        penalty (->> (:retrieval_issue_codes feedback)
                     (map #(get issue-penalty % 0.0))
                     (reduce + 0.0))]
    (max -1.0 (- base penalty))))

(defn evaluate-feedback-records [feedback-records]
  (let [records (vec feedback-records)
        scores (mapv feedback-score records)
        issue-counts (frequencies (mapcat :retrieval_issue_codes records))
        mean-score (if (seq scores)
                     (/ (reduce + 0.0 scores) (double (count scores)))
                     0.0)]
    {:total_feedback (count records)
     :mean_feedback_score mean-score
     :outcome_counts (frequencies (map :feedback_outcome records))
     :issue_counts issue-counts
     :confidence_counts (frequencies (keep :confidence_level records))}))

(defn- unit-id-match? [actual expected]
  (or (= actual expected)
      (= (str actual) (str expected))))

(defn- evaluate-query-result [result expected]
  (let [relevant-units (get-in result [:context_packet :relevant_units])
        selected-unit-ids (mapv :unit_id relevant-units)
        selected-paths (->> relevant-units (map :path) distinct vec)
        confidence (get-in result [:context_packet :confidence :level])
        top-authority (->> relevant-units
                           (filter #(= "top_authority" (:rank_band %)))
                           (mapv :unit_id))
        top-match? (if-let [required-top (seq (:top_authority_unit_ids expected))]
                     (every? (fn [required]
                               (some #(unit-id-match? % required) top-authority))
                             required-top)
                     true)
        required-paths? (if-let [required-paths (seq (:required_paths expected))]
                          (every? (set selected-paths) required-paths)
                          true)
        min-confidence? (if-let [min-confidence (:min_confidence_level expected)]
                          (>= (get confidence-rank confidence -1)
                              (get confidence-rank min-confidence 0))
                          true)]
    {:ok (and top-match? required-paths? min-confidence?)
     :top_authority_match top-match?
     :required_paths_match required-paths?
     :confidence_match min-confidence?
     :selected_unit_ids selected-unit-ids
     :selected_paths selected-paths
     :confidence_level confidence}))

(defn replay-query-dataset
  [{:keys [root_path dataset parser_opts retrieval_policy]
    :or {root_path "."}}]
  (let [dataset* (if (map? dataset) dataset {:queries dataset})
        index (sci/create-index {:root_path root_path
                                 :parser_opts parser_opts})
        results (mapv (fn [{:keys [query expected query_id]}]
                        (let [result (sci/resolve-context index query {:retrieval_policy retrieval_policy})
                              evaluation (evaluate-query-result result expected)]
                          {:query_id (or query_id (get-in query [:trace :request_id]) "query")
                           :ok (:ok evaluation)
                           :evaluation evaluation
                           :retrieval_policy (get-in result [:diagnostics_trace :retrieval_policy])
                           :capabilities (get-in result [:diagnostics_trace :capabilities])}))
                      (:queries dataset*))
        pass-count (count (filter :ok results))]
    {:total_queries (count results)
     :passed_queries pass-count
     :failed_queries (- (count results) pass-count)
     :pass_rate (if (seq results) (/ pass-count (double (count results))) 0.0)
     :results results}))

(defn- parse-args [args]
  (loop [m {:root_path "."} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--root" (recur (assoc m :root_path v) rest)
          "--dataset" (recur (assoc m :dataset_path v) rest)
          "--policy-file" (recur (assoc m :policy_path v) rest)
          "--out" (recur (assoc m :out_path v) rest)
          (recur m rest))))))

(defn -main [& args]
  (let [{:keys [root_path dataset_path policy_path out_path]} (parse-args args)]
    (when-not dataset_path
      (println "Usage: clojure -M:eval --root <repo-root> --dataset <dataset.json> [--policy-file <policy.edn>] [--out <output.json>]")
      (System/exit 1))
    (let [dataset (read-json dataset_path)
          retrieval-policy (when policy_path (read-edn policy_path))
          result (replay-query-dataset {:root_path root_path
                                        :dataset dataset
                                        :retrieval_policy retrieval-policy})]
      (if out_path
        (do (write-json out_path result)
            (println (str "wrote " out_path)))
        (println (json/write-str result :escape-slash false)))
      (System/exit (if (zero? (:failed_queries result)) 0 1)))))
