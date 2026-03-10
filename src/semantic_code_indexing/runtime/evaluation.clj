(ns semantic-code-indexing.runtime.evaluation
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.core :as sci]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]))

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

(def ^:private protected-metrics
  [{:metric :top_authority_hit_rate
    :path [:top_authority_hit_rate]
    :direction :at_least}
   {:metric :required_path_hit_rate
    :path [:required_path_hit_rate]
    :direction :at_least}
   {:metric :minimum_confidence_pass_rate
    :path [:minimum_confidence_pass_rate]
    :direction :at_least}
   {:metric :degraded_rate
    :path [:degraded_rate]
    :direction :at_most}
   {:metric :fallback_rate
    :path [:fallback_rate]
    :direction :at_most}
   {:metric :confidence_calibration
    :path [:confidence_calibration :mean_absolute_error]
    :direction :at_most}])

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

(defn- rate [numerator denominator]
  (if (pos? denominator)
    (/ (double numerator) (double denominator))
    0.0))

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

(defn- result-fallback? [capabilities]
  (contains? #{"mixed" "fallback_only"} (get capabilities :coverage_level "unknown")))

(defn- enriched-result [query-id result evaluation policy protected-case]
  (let [diagnostics (:diagnostics_trace result)
        capabilities (get diagnostics :capabilities)
        confidence-level (:confidence_level evaluation)
        confidence-score (rp/confidence-score policy confidence-level)
        confidence-ceiling (:confidence_ceiling capabilities)
        degraded? (seq (:degradations diagnostics))
        fallback? (result-fallback? capabilities)]
    {:query_id query-id
     :ok (:ok evaluation)
     :evaluation evaluation
     :retrieval_policy (get diagnostics :retrieval_policy)
     :capabilities capabilities
     :degraded degraded?
     :fallback fallback?
     :protected_case (boolean protected-case)
     :confidence_level confidence-level
     :confidence_score confidence-score
     :confidence_ceiling confidence-ceiling}))

(defn- protected-results [results]
  (vec (filter :protected_case results)))

(defn scorecard [results]
  (let [results* (vec results)
        total (count results*)
        top-hits (count (filter #(get-in % [:evaluation :top_authority_match]) results*))
        path-hits (count (filter #(get-in % [:evaluation :required_paths_match]) results*))
        confidence-passes (count (filter #(get-in % [:evaluation :confidence_match]) results*))
        degraded-count (count (filter :degraded results*))
        fallback-count (count (filter :fallback results*))
        confidence-ceiling-distribution (->> results*
                                           (keep :confidence_ceiling)
                                           frequencies
                                           (into (sorted-map)))
        calibration-by-level
        (->> results*
             (group-by :confidence_level)
             (reduce-kv
              (fn [acc level rows]
                (let [rows* (vec rows)
                      count* (count rows*)
                      observed (rate (count (filter :ok rows*)) count*)
                      predicted (if (seq rows*)
                                  (/ (reduce + 0.0 (map :confidence_score rows*)) (double count*))
                                  0.0)
                      mae (if (seq rows*)
                            (/ (reduce + 0.0
                                       (map (fn [{:keys [ok confidence_score]}]
                                              (Math/abs (- confidence_score (if ok 1.0 0.0))))
                                            rows*))
                               (double count*))
                            0.0)]
                  (assoc acc level
                         {:count count*
                          :observed_success_rate observed
                          :mean_predicted_confidence predicted
                          :mean_absolute_error mae})))
              {})
             (into (sorted-map)))
        mae (if (seq results*)
              (/ (reduce + 0.0
                         (map (fn [{:keys [ok confidence_score]}]
                                (Math/abs (- confidence_score (if ok 1.0 0.0))))
                              results*))
                 (double total))
              0.0)]
    {:total_queries total
     :top_authority_hit_rate (rate top-hits total)
     :required_path_hit_rate (rate path-hits total)
     :minimum_confidence_pass_rate (rate confidence-passes total)
     :degraded_rate (rate degraded-count total)
     :fallback_rate (rate fallback-count total)
     :pass_rate (rate (count (filter :ok results*)) total)
     :confidence_ceiling_distribution confidence-ceiling-distribution
     :confidence_calibration {:mean_absolute_error mae
                              :by_level calibration-by-level}}))

(defn- protected-summary [results]
  (let [protected* (protected-results results)
        failed (->> protected*
                    (remove :ok)
                    (mapv :query_id))]
    {:total_queries (count protected*)
     :passed_queries (count (filter :ok protected*))
     :failed_queries (count failed)
     :pass_rate (rate (count (filter :ok protected*)) (count protected*))
     :failed_query_ids failed
     :scorecard (scorecard protected*)}))

(defn replay-query-dataset
  [{:keys [root_path dataset parser_opts retrieval_policy]
    :or {root_path "."}}]
  (let [dataset* (if (map? dataset) dataset {:queries dataset})
        policy (rp/normalize-policy retrieval_policy)
        index (sci/create-index {:root_path root_path
                                 :parser_opts parser_opts})
        results (mapv (fn [{:keys [query expected query_id protected_case]}]
                        (let [result (sci/resolve-context index query {:retrieval_policy policy})
                              evaluation (evaluate-query-result result expected)]
                          (enriched-result (or query_id (get-in query [:trace :request_id]) "query")
                                           result
                                           evaluation
                                           policy
                                           protected_case)))
                      (:queries dataset*))
        pass-count (count (filter :ok results))]
    {:total_queries (count results)
     :passed_queries pass-count
     :failed_queries (- (count results) pass-count)
     :pass_rate (rate pass-count (count results))
     :scorecard (scorecard results)
     :protected_summary (protected-summary results)
     :results results}))

(defn score-policy
  [{:keys [root_path dataset parser_opts retrieval_policy]
    :or {root_path "."}}]
  (let [policy (rp/normalize-policy retrieval_policy)
        replay (replay-query-dataset {:root_path root_path
                                      :dataset dataset
                                      :parser_opts parser_opts
                                      :retrieval_policy policy})]
    (assoc replay :policy_summary (rp/policy-summary policy))))

(defn compare-policies
  [{:keys [root_path dataset parser_opts baseline_policy candidate_policy]
    :or {root_path "."}}]
  (let [baseline (score-policy {:root_path root_path
                                :dataset dataset
                                :parser_opts parser_opts
                                :retrieval_policy baseline_policy})
        candidate (score-policy {:root_path root_path
                                 :dataset dataset
                                 :parser_opts parser_opts
                                 :retrieval_policy candidate_policy})
        baseline-scorecard (:scorecard baseline)
        candidate-scorecard (:scorecard candidate)
        baseline-protected (:protected_summary baseline)
        candidate-protected (:protected_summary candidate)
        protected-mode? (pos? (get baseline-protected :total_queries 0))
        baseline-gate-scorecard (if protected-mode?
                                  (:scorecard baseline-protected)
                                  baseline-scorecard)
        candidate-gate-scorecard (if protected-mode?
                                   (:scorecard candidate-protected)
                                   candidate-scorecard)
        deltas (reduce (fn [acc {:keys [metric path direction]}]
                         (let [baseline-value (get-in baseline-gate-scorecard path 0.0)
                               candidate-value (get-in candidate-gate-scorecard path 0.0)
                               delta (- (double candidate-value) (double baseline-value))]
                           (assoc acc metric
                                  {:scope (if protected-mode? "protected_only" "all_queries")
                                   :direction direction
                                   :baseline baseline-value
                                   :candidate candidate-value
                                   :delta delta
                                   :regressed? (case direction
                                                 :at_least (< delta 0.0)
                                                 :at_most (> delta 0.0)
                                                 false)})))
                       {}
                       protected-metrics)]
    {:baseline {:policy_summary (:policy_summary baseline)
                :scorecard baseline-scorecard
                :protected_summary baseline-protected}
     :candidate {:policy_summary (:policy_summary candidate)
                 :scorecard candidate-scorecard
                 :protected_summary candidate-protected}
     :protected_case_summary {:enabled? protected-mode?
                              :baseline_failed_query_ids (:failed_query_ids baseline-protected)
                              :candidate_failed_query_ids (:failed_query_ids candidate-protected)
                              :newly_failed_query_ids (vec (remove (set (:failed_query_ids baseline-protected))
                                                                   (:failed_query_ids candidate-protected)))}
     :protected_metrics deltas}))

(defn promotion-gate-decision [comparison]
  (let [checks (->> (:protected_metrics comparison)
                    (map (fn [[metric {:keys [direction baseline candidate delta regressed?]}]]
                           {:metric metric
                            :direction direction
                            :baseline baseline
                            :candidate candidate
                            :delta delta
                            :passed? (not regressed?)}))
                    (sort-by :metric)
                    vec)
        protected-case-summary (:protected_case_summary comparison)
        protected-check (when (:enabled? protected-case-summary)
                          {:metric :protected_query_regressions
                           :direction :at_most
                           :baseline 0
                           :candidate (count (:newly_failed_query_ids protected-case-summary))
                           :delta (count (:newly_failed_query_ids protected-case-summary))
                           :passed? (empty? (:newly_failed_query_ids protected-case-summary))
                           :newly_failed_query_ids (:newly_failed_query_ids protected-case-summary)})
        checks* (cond-> checks protected-check (conj protected-check))]
    {:eligible? (every? :passed? checks*)
     :checks checks*}))

(defn- now-iso []
  (.toString (java.time.Instant/now)))

(defn- failed-checks [decision]
  (->> (:checks decision)
       (remove :passed?)
       vec))

(defn shadow-review-report
  [{:keys [root_path dataset parser_opts registry]
    :or {root_path "."}}]
  (let [registry* (rp/normalize-registry registry)
        baseline-entry (rp/active-registry-entry registry*)
        _ (when-not baseline-entry
            (throw (ex-info "active policy not found in registry"
                            {:type :invalid_request
                             :message "active policy not found in registry"})))
        baseline-policy (rp/policy-from-entry baseline-entry)
        shadow-entries (->> (rp/list-registry-entries registry*)
                            (filter #(= "shadow" (:state %)))
                            vec)
        reviewed-at (now-iso)
        reviews (mapv (fn [entry]
                        (let [candidate-policy (rp/policy-from-entry entry)
                              comparison (compare-policies {:root_path root_path
                                                            :dataset dataset
                                                            :parser_opts parser_opts
                                                            :baseline_policy baseline-policy
                                                            :candidate_policy candidate-policy})
                              decision (promotion-gate-decision comparison)]
                          {:policy_summary (rp/policy-summary candidate-policy)
                           :state (:state entry)
                           :baseline_policy (rp/policy-summary baseline-policy)
                           :reviewed_at reviewed-at
                           :eligible_for_promotion (:eligible? decision)
                           :failed_checks (failed-checks decision)
                           :protected_case_summary (:protected_case_summary comparison)
                           :scorecard (get-in comparison [:candidate :scorecard])
                           :protected_summary (get-in comparison [:candidate :protected_summary])}))
                      shadow-entries)
        ready (count (filter :eligible_for_promotion reviews))
        blocked (- (count reviews) ready)]
    {:active_policy {:policy_summary (rp/policy-summary baseline-policy)
                     :state (:state baseline-entry)}
     :summary {:shadow_candidates (count reviews)
               :ready_for_promotion ready
               :blocked blocked
               :reviewed_at reviewed-at}
     :shadow_candidates reviews}))

(defn apply-shadow-review
  [registry report]
  (reduce (fn [acc {:keys [policy_summary reviewed_at eligible_for_promotion failed_checks protected_case_summary]}]
            (if-let [entry (rp/resolve-registry-entry acc
                                                      (:policy_id policy_summary)
                                                      (:version policy_summary))]
              (rp/upsert-registry-entry
               acc
               (assoc entry
                      :shadow_review {:reviewed_at reviewed_at
                                      :eligible_for_promotion eligible_for_promotion
                                      :failed_checks failed_checks
                                      :protected_case_summary protected_case_summary}))
              acc))
          (rp/normalize-registry registry)
          (:shadow_candidates report)))

(defn promote-policy
  [{:keys [registry candidate_policy_id candidate_version comparison dry_run]
    :or {dry_run false}}]
  (let [registry* (rp/normalize-registry registry)
        candidate (rp/resolve-registry-entry registry* candidate_policy_id candidate_version)
        baseline (rp/active-registry-entry registry*)
        decision (promotion-gate-decision comparison)]
    (when-not candidate
      (throw (ex-info "candidate policy not found in registry"
                      {:type :invalid_request
                       :message "candidate policy not found in registry"})))
    (let [eligible? (:eligible? decision)
          promoted-registry
          (if (and eligible? (not dry_run))
            (let [retired (if (and baseline
                                   (not (and (= (:policy_id baseline) (:policy_id candidate))
                                             (= (:version baseline) (:version candidate)))))
                            (rp/set-entry-state registry* (:policy_id baseline) (:version baseline) "retired")
                            registry*)]
              (rp/set-entry-state retired (:policy_id candidate) (:version candidate) "active"))
            registry*)]
      {:candidate {:policy_id (:policy_id candidate)
                   :version (:version candidate)
                   :state_before (:state candidate)}
       :baseline (when baseline
                   {:policy_id (:policy_id baseline)
                    :version (:version baseline)
                    :state_before (:state baseline)})
       :decision decision
       :promoted? (and eligible? (not dry_run))
       :registry promoted-registry})))

(defn- parse-bool [value]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str (or value "")))))

(defn- parse-args [args]
  (loop [m {} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--root" (recur (assoc m :root_path v) rest)
          "--dataset" (recur (assoc m :dataset_path v) rest)
          "--policy-file" (recur (assoc m :policy_path v) rest)
          "--registry" (recur (assoc m :registry_path v) rest)
          "--policy-id" (recur (assoc m :policy_id v) rest)
          "--version" (recur (assoc m :version v) rest)
          "--baseline-policy-file" (recur (assoc m :baseline_policy_path v) rest)
          "--baseline-policy-id" (recur (assoc m :baseline_policy_id v) rest)
          "--baseline-version" (recur (assoc m :baseline_version v) rest)
          "--candidate-policy-file" (recur (assoc m :candidate_policy_path v) rest)
          "--candidate-policy-id" (recur (assoc m :candidate_policy_id v) rest)
          "--candidate-version" (recur (assoc m :candidate_version v) rest)
          "--usage-metrics-jdbc-url" (recur (assoc m :usage_metrics_jdbc_url v) rest)
          "--surface" (recur (assoc m :surface v) rest)
          "--tenant-id" (recur (assoc m :tenant_id v) rest)
          "--since" (recur (assoc m :since v) rest)
          "--out" (recur (assoc m :out_path v) rest)
          "--write-registry" (recur (assoc m :write_registry true) rest)
          "--dry-run" (recur (assoc m :dry_run true) rest)
          "--pretty" (recur (assoc m :pretty (parse-bool v)) rest)
          (recur m rest))))))

(defn- resolve-policy-input [{:keys [policy_path registry_path policy_id version]}]
  (cond
    policy_path
    (rp/normalize-policy (read-edn policy_path))

    (and registry_path policy_id)
    (or (some-> (rp/load-registry registry_path)
                (rp/resolve-registry-entry policy_id version)
                rp/policy-from-entry)
        (throw (ex-info "policy not found in registry"
                        {:type :invalid_request
                         :message "policy not found in registry"})))

    :else
    (rp/default-retrieval-policy)))

(defn- print-or-write! [out-path data]
  (if out-path
    (do (write-json out-path data)
        (println (str "wrote " out-path)))
    (println (json/write-str data :escape-slash false))))

(defn- run-replay-command [{:keys [root_path dataset_path policy_path out_path]}]
  (when-not dataset_path
    (println "Usage: clojure -M:eval --root <repo-root> --dataset <dataset.json> [--policy-file <policy.edn>] [--out <output.json>]")
    (System/exit 1))
  (let [dataset (read-json dataset_path)
        retrieval-policy (when policy_path (read-edn policy_path))
        result (replay-query-dataset {:root_path (or root_path ".")
                                      :dataset dataset
                                      :retrieval_policy retrieval-policy})]
    (print-or-write! out_path result)
    (System/exit (if (zero? (:failed_queries result)) 0 1))))

(defn- run-score-policy-command [{:keys [root_path dataset_path out_path] :as args}]
  (when-not dataset_path
    (println "Usage: clojure -M:eval score-policy --root <repo-root> --dataset <dataset.json> [--policy-file <policy.edn> | --registry <registry.edn> --policy-id <id> [--version <version>]] [--out <output.json>]")
    (System/exit 1))
  (let [dataset (read-json dataset_path)
        result (score-policy {:root_path (or root_path ".")
                              :dataset dataset
                              :retrieval_policy (resolve-policy-input args)})]
    (print-or-write! out_path result)
    (System/exit (if (zero? (:failed_queries result)) 0 1))))

(defn- run-compare-policies-command [{:keys [root_path dataset_path out_path] :as args}]
  (when-not dataset_path
    (println "Usage: clojure -M:eval compare-policies --root <repo-root> --dataset <dataset.json> --baseline-policy-file <policy.edn>|(--registry <registry.edn> --baseline-policy-id <id> [--baseline-version <version>]) --candidate-policy-file <policy.edn>|(--registry <registry.edn> --candidate-policy-id <id> [--candidate-version <version>]) [--out <output.json>]")
    (System/exit 1))
  (let [dataset (read-json dataset_path)
        baseline-policy (resolve-policy-input {:policy_path (:baseline_policy_path args)
                                               :registry_path (:registry_path args)
                                               :policy_id (:baseline_policy_id args)
                                               :version (:baseline_version args)})
        candidate-policy (resolve-policy-input {:policy_path (:candidate_policy_path args)
                                                :registry_path (:registry_path args)
                                                :policy_id (:candidate_policy_id args)
                                                :version (:candidate_version args)})
        result (compare-policies {:root_path (or root_path ".")
                                  :dataset dataset
                                  :baseline_policy baseline-policy
                                  :candidate_policy candidate-policy})]
    (print-or-write! out_path result)
    (System/exit (if (every? :passed? (get-in (promotion-gate-decision result) [:checks])) 0 1))))

(defn- run-shadow-review-command [{:keys [root_path dataset_path registry_path out_path write_registry]}]
  (when-not (and dataset_path registry_path)
    (println "Usage: clojure -M:eval shadow-review --root <repo-root> --dataset <dataset.json> --registry <registry.edn> [--write-registry] [--out <output.json>]")
    (System/exit 1))
  (let [dataset (read-json dataset_path)
        registry (rp/load-registry registry_path)
        report (shadow-review-report {:root_path (or root_path ".")
                                      :dataset dataset
                                      :registry registry})
        registry* (if write_registry
                    (apply-shadow-review registry report)
                    registry)
        result (assoc report :registry registry*)]
    (when write_registry
      (rp/write-registry! registry_path registry*))
    (print-or-write! out_path result)
    (System/exit (if (zero? (get-in result [:summary :blocked])) 0 1))))

(defn- run-promote-policy-command [{:keys [root_path dataset_path registry_path candidate_policy_id candidate_version out_path write_registry dry_run]}]
  (when-not (and dataset_path registry_path candidate_policy_id)
    (println "Usage: clojure -M:eval promote-policy --root <repo-root> --dataset <dataset.json> --registry <registry.edn> --candidate-policy-id <id> [--candidate-version <version>] [--write-registry] [--dry-run] [--out <output.json>]")
    (System/exit 1))
  (let [dataset (read-json dataset_path)
        registry (rp/load-registry registry_path)
        baseline-entry (rp/active-registry-entry registry)
        baseline-policy (or (rp/policy-from-entry baseline-entry)
                            (rp/default-retrieval-policy))
        candidate-policy (or (some-> (rp/resolve-registry-entry registry candidate_policy_id candidate_version)
                                     rp/policy-from-entry)
                             (throw (ex-info "candidate policy not found in registry"
                                             {:type :invalid_request
                                              :message "candidate policy not found in registry"})))
        comparison (compare-policies {:root_path (or root_path ".")
                                      :dataset dataset
                                      :baseline_policy baseline-policy
                                      :candidate_policy candidate-policy})
        result (promote-policy {:registry registry
                                :candidate_policy_id candidate_policy_id
                                :candidate_version (or candidate_version (:version (rp/resolve-registry-entry registry candidate_policy_id candidate_version)))
                                :comparison comparison
                                :dry_run dry_run})
        result* (assoc result :comparison comparison)]
    (when (and write_registry (:promoted? result*))
      (rp/write-registry! registry_path (:registry result*)))
    (print-or-write! out_path result*)
    (System/exit (if (:eligible? (:decision result*)) 0 1))))

(defn- run-harvest-replay-dataset-command [{:keys [usage_metrics_jdbc_url surface tenant_id since out_path]}]
  (let [jdbc-url (or usage_metrics_jdbc_url (System/getenv "SCI_USAGE_METRICS_JDBC_URL"))]
    (when-not jdbc-url
      (println "Usage: clojure -M:eval harvest-replay-dataset --usage-metrics-jdbc-url <jdbc-url> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--out <output.json>]")
      (System/exit 1))
    (let [metrics (sci/postgres-usage-metrics {:jdbc-url jdbc-url
                                               :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                               :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")})
          result (sci/harvest-replay-dataset metrics
                                             (cond-> {}
                                               surface (assoc :surface surface)
                                               tenant_id (assoc :tenant_id tenant_id)
                                               since (assoc :since since)))]
      (print-or-write! out_path result)
      (System/exit 0))))

(defn- run-calibration-report-command [{:keys [usage_metrics_jdbc_url surface tenant_id since out_path]}]
  (let [jdbc-url (or usage_metrics_jdbc_url (System/getenv "SCI_USAGE_METRICS_JDBC_URL"))]
    (when-not jdbc-url
      (println "Usage: clojure -M:eval calibration-report --usage-metrics-jdbc-url <jdbc-url> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--out <output.json>]")
      (System/exit 1))
    (let [metrics (sci/postgres-usage-metrics {:jdbc-url jdbc-url
                                               :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                               :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")})
          result (sci/calibration-report metrics
                                         (cond-> {}
                                           surface (assoc :surface surface)
                                           tenant_id (assoc :tenant_id tenant_id)
                                           since (assoc :since since)))]
      (print-or-write! out_path result)
      (System/exit 0))))

(defn- run-weekly-review-report-command [{:keys [usage_metrics_jdbc_url surface tenant_id since out_path]}]
  (let [jdbc-url (or usage_metrics_jdbc_url (System/getenv "SCI_USAGE_METRICS_JDBC_URL"))]
    (when-not jdbc-url
      (println "Usage: clojure -M:eval weekly-review-report --usage-metrics-jdbc-url <jdbc-url> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--out <output.json>]")
      (System/exit 1))
    (let [metrics (sci/postgres-usage-metrics {:jdbc-url jdbc-url
                                               :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                               :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")})
          result (sci/weekly-review-report metrics
                                           (cond-> {}
                                             surface (assoc :surface surface)
                                             tenant_id (assoc :tenant_id tenant_id)
                                             since (assoc :since since)))]
      (print-or-write! out_path result)
      (System/exit 0))))

(defn -main [& args]
  (let [[command & rest-args] args]
    (case command
      "score-policy" (run-score-policy-command (parse-args rest-args))
      "compare-policies" (run-compare-policies-command (parse-args rest-args))
      "shadow-review" (run-shadow-review-command (parse-args rest-args))
      "promote-policy" (run-promote-policy-command (parse-args rest-args))
      "harvest-replay-dataset" (run-harvest-replay-dataset-command (parse-args rest-args))
      "calibration-report" (run-calibration-report-command (parse-args rest-args))
      "weekly-review-report" (run-weekly-review-report-command (parse-args rest-args))
      (run-replay-command (parse-args args)))))
