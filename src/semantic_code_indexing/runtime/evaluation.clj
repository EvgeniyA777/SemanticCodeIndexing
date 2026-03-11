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

(declare prior-governance-runs
         selected-policy-match?
         candidate-streak-length
         cooldown-active?)

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
                        (let [result (sci/resolve-context-detail index query {:retrieval_policy policy})
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

(defn- iso->instant [value]
  (when (seq (str value))
    (java.time.Instant/parse (str value))))

(defn- instant->artifact-token [instant]
  (-> (str instant)
      (str/replace ":" "")
      (str/replace "." "-")))

(defn- ensure-parent-dir! [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent)))

(defn- read-json-if-exists [path]
  (when (.exists (io/file path))
    (read-json path)))

(defn- write-json-file! [path data]
  (ensure-parent-dir! path)
  (write-json path data)
  path)

(defn- artifact-files
  ([artifacts-dir]
   (artifact-files artifacts-dir "policy-review-" "policy-review-manifest.json"))
  ([artifacts-dir prefix manifest-name]
   (let [dir (io/file artifacts-dir)]
     (if (.isDirectory dir)
       (->> (.listFiles dir)
            (filter #(.isFile %))
            (filter #(str/starts-with? (.getName %) prefix))
            (remove #(= manifest-name (.getName %)))
            (filter #(str/ends-with? (.getName %) ".json"))
            (sort-by #(.getName %) compare)
            vec)
       []))))

(defn- prune-artifacts!
  ([artifacts-dir retention_runs]
   (prune-artifacts! artifacts-dir retention_runs "policy-review-" "policy-review-manifest.json"))
  ([artifacts-dir retention_runs prefix manifest-name]
   (let [files (artifact-files artifacts-dir prefix manifest-name)
         keep-count (max 0 (long (or retention_runs 0)))
         delete-count (max 0 (- (count files) keep-count))
         doomed (take delete-count files)]
     (doseq [file doomed]
       (.delete file))
     {:deleted_artifacts (mapv #(.getAbsolutePath %) doomed)
      :retained_artifact_count (min keep-count (max 0 (- (count files) delete-count)))})))

(defn- manifest-path
  ([artifacts-dir]
   (manifest-path artifacts-dir "policy-review-manifest.json"))
  ([artifacts-dir manifest-name]
   (.getAbsolutePath (io/file artifacts-dir manifest-name))))

(defn- retained-index-path [artifacts-dir index-name]
  (.getAbsolutePath (io/file artifacts-dir index-name)))

(defn- read-retained-index [artifacts-dir index-name]
  (or (read-json-if-exists (retained-index-path artifacts-dir index-name))
      {:schema_version "1.0"
       :runs []}))

(defn- artifact-exists? [path]
  (and (seq (str path))
       (.exists (io/file path))))

(defn- select-retained-runs [runs retention-runs]
  (let [keep-count (when retention-runs (max 0 (long retention-runs)))]
    (cond->> (sort-by :generated_at runs)
      keep-count (take-last keep-count)
      true vec)))

(defn- write-retained-index!
  [{:keys [artifacts_dir index_name generated_at retention_runs run_summary run_key_fn]}]
  (let [index-path (retained-index-path artifacts_dir index_name)
        current-index (read-retained-index artifacts_dir index_name)
        current-runs (:runs current-index)
        deduped-runs (reduce (fn [acc run]
                               (assoc acc (run_key_fn run) run))
                             {}
                             current-runs)
        runs-with-new (assoc deduped-runs (run_key_fn run_summary) run_summary)
        live-runs (->> (vals runs-with-new)
                       (filter #(artifact-exists? (get-in % [:artifact_paths :primary])))
                       vec)
        retained-runs (select-retained-runs live-runs retention_runs)
        retained-index {:schema_version "1.0"
                        :updated_at generated_at
                        :runs retained-runs}]
    (write-json-file! index-path retained-index)
    {:path index-path
     :index retained-index}))

(defn- derive-since [{:keys [since lookback_days manifest]}]
  (cond
    since since
    (get manifest :last_run_at) (get manifest :last_run_at)
    lookback_days (str (.minus (java.time.Instant/now)
                               (java.time.Duration/ofDays (long lookback_days))))
    :else nil))

(defn- failed-checks [decision]
  (->> (:checks decision)
       (remove :passed?)
       vec))

(defn- governance-review [entry gate-eligible?]
  (let [governance (rp/effective-governance entry)
        promotion-mode (:promotion_mode governance)
        approval-tier (:approval_tier governance)
        auto-eligible? (and gate-eligible? (rp/auto-promotable? entry))
        manual-review? (and gate-eligible? (rp/manual-approval-required? entry))
        blocked-by-governance? (and gate-eligible? (rp/blocked? entry))
        reason (cond
                 (not gate-eligible?) "replay_gates_failed"
                 auto-eligible? "auto_promotable"
                 manual-review? "manual_approval_required"
                 blocked-by-governance? "promotion_blocked"
                 :else "unknown")]
    {:governance governance
     :approval_tier approval-tier
     :promotion_mode promotion-mode
     :eligible_for_auto_promotion auto-eligible?
     :manual_review_required manual-review?
     :blocked_by_governance blocked-by-governance?
     :governance_reason reason}))

(defn- promote-governance-decision [entry replay-decision manual-approval]
  (let [replay-eligible? (:eligible? replay-decision)
        governance (rp/effective-governance entry)
        promotion-mode (:promotion_mode governance)
        approval-tier (:approval_tier governance)
        manual-approval? (boolean manual-approval)
        governance-eligible? (cond
                               (not replay-eligible?) false
                               (rp/blocked? entry) false
                               (rp/manual-approval-required? entry) manual-approval?
                               :else true)
        eligible? (cond
                    (not replay-eligible?) false
                    (rp/blocked? entry) false
                    (rp/manual-approval-required? entry) manual-approval?
                    :else true)
        governance-reason (cond
                            (not replay-eligible?) "replay_gates_failed"
                            (rp/blocked? entry) "promotion_blocked"
                            (and (rp/manual-approval-required? entry) (not manual-approval?)) "manual_approval_required"
                            (and (rp/manual-approval-required? entry) manual-approval?) "manual_approval_granted"
                            :else "auto_promotable")]
    {:eligible? eligible?
     :replay_eligible replay-eligible?
     :governance_eligible governance-eligible?
     :promotion_mode promotion-mode
     :approval_tier approval-tier
     :manual_approval_supplied manual-approval?
     :governance_reason governance-reason
     :final_outcome (if eligible? "promotion_allowed" "promotion_denied")
     :checks (:checks replay-decision)}))

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
                              decision (promotion-gate-decision comparison)
                              gate-eligible? (:eligible? decision)
                              governance-review* (governance-review entry gate-eligible?)]
                          (merge
                           {:policy_summary (rp/policy-summary candidate-policy)
                            :state (:state entry)
                            :baseline_policy (rp/policy-summary baseline-policy)
                            :reviewed_at reviewed-at
                            :eligible_for_promotion gate-eligible?
                            :failed_checks (failed-checks decision)
                            :protected_case_summary (:protected_case_summary comparison)
                            :scorecard (get-in comparison [:candidate :scorecard])
                            :protected_summary (get-in comparison [:candidate :protected_summary])}
                           governance-review*)))
                      shadow-entries)
        ready (count (filter :eligible_for_promotion reviews))
        blocked (- (count reviews) ready)
        auto-promotable (count (filter :eligible_for_auto_promotion reviews))
        manual-review (count (filter :manual_review_required reviews))
        governance-blocked (count (filter :blocked_by_governance reviews))]
    {:active_policy {:policy_summary (rp/policy-summary baseline-policy)
                     :state (:state baseline-entry)}
     :summary {:shadow_candidates (count reviews)
               :ready_for_promotion ready
               :blocked blocked
               :ready_for_auto_promotion auto-promotable
               :manual_review_required manual-review
               :governance_blocked governance-blocked
               :reviewed_at reviewed-at}
     :shadow_candidates reviews}))

(defn apply-shadow-review
  [registry report]
  (reduce (fn [acc {:keys [policy_summary reviewed_at eligible_for_promotion eligible_for_auto_promotion manual_review_required blocked_by_governance promotion_mode approval_tier governance_reason failed_checks protected_case_summary]}]
            (if-let [entry (rp/resolve-registry-entry acc
                                                      (:policy_id policy_summary)
                                                      (:version policy_summary))]
              (rp/upsert-registry-entry
               acc
               (assoc entry
                      :shadow_review {:reviewed_at reviewed_at
                                      :eligible_for_promotion eligible_for_promotion
                                      :eligible_for_auto_promotion eligible_for_auto_promotion
                                      :manual_review_required manual_review_required
                                      :blocked_by_governance blocked_by_governance
                                      :promotion_mode promotion_mode
                                      :approval_tier approval_tier
                                      :governance_reason governance_reason
                                      :failed_checks failed_checks
                                      :protected_case_summary protected_case_summary}))
              acc))
          (rp/normalize-registry registry)
          (:shadow_candidates report)))

(defn promote-policy
  [{:keys [registry candidate_policy_id candidate_version comparison dry_run manual_approval]
    :or {dry_run false}}]
  (let [registry* (rp/normalize-registry registry)
        candidate (rp/resolve-registry-entry registry* candidate_policy_id candidate_version)
        baseline (rp/active-registry-entry registry*)
        replay-decision (promotion-gate-decision comparison)]
    (when-not candidate
      (throw (ex-info "candidate policy not found in registry"
                      {:type :invalid_request
                       :message "candidate policy not found in registry"})))
    (let [decision (promote-governance-decision candidate replay-decision manual_approval)
          eligible? (:eligible? decision)
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
                   :state_before (:state candidate)
                   :governance (rp/effective-governance candidate)}
       :baseline (when baseline
                   {:policy_id (:policy_id baseline)
                    :version (:version baseline)
                    :state_before (:state baseline)})
       :decision decision
       :replay_decision replay-decision
       :promoted? (and eligible? (not dry_run))
       :registry promoted-registry})))

(defn policy-review-pipeline
  [{:keys [root_path usage_metrics parser_opts registry surface tenant_id since write_registry]
    :or {root_path "."
         write_registry false}}]
  (let [filters (cond-> {:root_path root_path}
                  surface (assoc :surface surface)
                  tenant_id (assoc :tenant_id tenant_id)
                  since (assoc :since since))
        weekly-review (sci/weekly-review-report usage_metrics filters)
        protected-dataset (sci/review-report->protected-replay-dataset weekly-review)
        registry* (rp/normalize-registry registry)
        shadow-entries (->> (rp/list-registry-entries registry*)
                            (filter #(= "shadow" (:state %)))
                            vec)
        active-entry (rp/active-registry-entry registry*)
        shadow-review (cond
                        (empty? (:queries protected-dataset))
                        {:skipped true
                         :reason "no_protected_queries"}

                        (nil? active-entry)
                        {:skipped true
                         :reason "active_policy_not_found"}

                        (empty? shadow-entries)
                        {:skipped true
                         :reason "no_shadow_policies"}

                        :else
                        (shadow-review-report {:root_path root_path
                                               :dataset protected-dataset
                                               :parser_opts parser_opts
                                               :registry registry*}))
        registry-after (if (and write_registry (not (:skipped shadow-review)))
                         (apply-shadow-review registry* shadow-review)
                         registry*)]
    {:schema_version "1.0"
     :generated_at (now-iso)
     :root_path root_path
     :weekly_review_report weekly-review
     :protected_replay_dataset protected-dataset
     :shadow_review_report shadow-review
     :registry registry-after}))

(defn scheduled-policy-review
  [{:keys [root_path usage_metrics parser_opts registry artifacts_dir surface tenant_id since lookback_days retention_runs write_registry]
    :or {root_path "."
         lookback_days 7
         retention_runs 8
         write_registry true}}]
  (let [artifacts-dir* (or artifacts_dir ".tmp/policy-review")
        manifest* (or (read-json-if-exists (manifest-path artifacts-dir*)) {})
        since* (derive-since {:since since
                              :lookback_days lookback_days
                              :manifest manifest*})
        bundle (policy-review-pipeline {:root_path root_path
                                        :usage_metrics usage_metrics
                                        :parser_opts parser_opts
                                        :registry registry
                                        :surface surface
                                        :tenant_id tenant_id
                                        :since since*
                                        :write_registry write_registry})
        generated-at (or (:generated_at bundle) (now-iso))
        artifact-token (instant->artifact-token (iso->instant generated-at))
        artifacts-root (io/file artifacts-dir*)
        _ (.mkdirs artifacts-root)
        weekly-review-path (.getAbsolutePath
                            (io/file artifacts-root
                                     (str "weekly-review-" artifact-token ".json")))
        protected-dataset-path (.getAbsolutePath
                                (io/file artifacts-root
                                         (str "protected-replay-dataset-" artifact-token ".json")))
        shadow-review-path (.getAbsolutePath
                            (io/file artifacts-root
                                     (str "shadow-review-" artifact-token ".json")))
        artifact-path (.getAbsolutePath
                       (io/file artifacts-root
                                (str "policy-review-" artifact-token ".json")))
        _ (write-json-file! weekly-review-path (:weekly_review_report bundle))
        _ (write-json-file! protected-dataset-path (:protected_replay_dataset bundle))
        _ (write-json-file! shadow-review-path (:shadow_review_report bundle))
        _ (write-json-file! artifact-path bundle)
        retention (merge
                   (prune-artifacts! artifacts-dir* retention_runs)
                   {:weekly_review_deleted_artifacts (:deleted_artifacts
                                                      (prune-artifacts! artifacts-dir*
                                                                        retention_runs
                                                                        "weekly-review-"
                                                                        "policy-review-manifest.json"))
                    :protected_replay_dataset_deleted_artifacts (:deleted_artifacts
                                                                 (prune-artifacts! artifacts-dir*
                                                                                   retention_runs
                                                                                   "protected-replay-dataset-"
                                                                                   "policy-review-manifest.json"))
                    :shadow_review_deleted_artifacts (:deleted_artifacts
                                                      (prune-artifacts! artifacts-dir*
                                                                        retention_runs
                                                                        "shadow-review-"
                                                                        "policy-review-manifest.json"))})
        run-id artifact-token
        review-run-summary {:run_id run-id
                            :generated_at generated-at
                            :scope {:root_path root_path
                                    :surface surface
                                    :tenant_id tenant_id
                                    :since since*}
                            :artifact_paths {:primary artifact-path
                                             :policy_review_bundle artifact-path
                                             :weekly_review weekly-review-path
                                             :protected_replay_dataset protected-dataset-path
                                             :shadow_review shadow-review-path}
                            :weekly_review_summary {:total_queries (get-in bundle [:weekly_review_report :summary :total_queries] 0)
                                                    :protected_queries (count (filter :protected_case
                                                                                      (get-in bundle [:protected_replay_dataset :queries] [])))}
                            :protected_replay_summary {:total_queries (count (get-in bundle [:protected_replay_dataset :queries] []))
                                                       :protected_queries (count (filter :protected_case
                                                                                         (get-in bundle [:protected_replay_dataset :queries] [])))}
                            :shadow_review_summary {:skipped (boolean (get-in bundle [:shadow_review_report :skipped]))
                                                    :reason (get-in bundle [:shadow_review_report :reason])
                                                    :shadow_candidates (get-in bundle [:shadow_review_report :summary :shadow_candidates] 0)
                                                    :ready_for_promotion (get-in bundle [:shadow_review_report :summary :ready_for_promotion] 0)
                                                    :ready_for_auto_promotion (get-in bundle [:shadow_review_report :summary :ready_for_auto_promotion] 0)
                                                    :manual_review_candidates (->> (get-in bundle [:shadow_review_report :shadow_candidates] [])
                                                                                   (filter :manual_review_required)
                                                                                   (mapv :policy_summary))
                                                    :governance_blocked_candidates (->> (get-in bundle [:shadow_review_report :shadow_candidates] [])
                                                                                        (filter :blocked_by_governance)
                                                                                        (mapv :policy_summary))}}
        review-index-write (write-retained-index! {:artifacts_dir artifacts-dir*
                                                   :index_name "review-run-index.json"
                                                   :generated_at generated-at
                                                   :retention_runs retention_runs
                                                   :run_summary review-run-summary
                                                   :run_key_fn :run_id})
        manifest-data {:schema_version "1.0"
                       :updated_at generated-at
                       :last_run_at generated-at
                       :root_path root_path
                       :surface surface
                       :tenant_id tenant_id
                       :since since*
                       :artifacts_dir (.getAbsolutePath artifacts-root)
                       :latest_artifact_path artifact-path
                       :latest_weekly_review_path weekly-review-path
                       :latest_protected_replay_dataset_path protected-dataset-path
                       :latest_shadow_review_path shadow-review-path
                       :latest_run_id run-id
                       :review_index_path (:path review-index-write)
                       :retention_runs retention_runs
                       :lookback_days lookback_days
                       :write_registry write_registry}
        manifest-path* (manifest-path artifacts-dir*)
        _ (write-json-file! manifest-path* manifest-data)]
    {:schema_version "1.0"
     :scheduled_run {:generated_at generated-at
                     :run_id run-id
                     :since since*
                     :lookback_days lookback_days
                     :retention_runs retention_runs
                     :artifact_path artifact-path
                     :weekly_review_path weekly-review-path
                     :protected_replay_dataset_path protected-dataset-path
                     :shadow_review_path shadow-review-path
                     :manifest_path manifest-path*
                     :deleted_artifacts (:deleted_artifacts retention)
                     :weekly_review_deleted_artifacts (:weekly_review_deleted_artifacts retention)
                     :protected_replay_dataset_deleted_artifacts (:protected_replay_dataset_deleted_artifacts retention)
                     :shadow_review_deleted_artifacts (:shadow_review_deleted_artifacts retention)
                     :review_index_path (:path review-index-write)
                     :write_registry write_registry}
     :bundle bundle
     :review_run_summary review-run-summary
     :review_index (:index review-index-write)
     :manifest manifest-data}))

(defn- candidate-ranking-vector [candidate]
  (let [scorecard (:scorecard candidate)
        protected-scorecard (get-in candidate [:protected_summary :scorecard])
        candidate-scorecard (or protected-scorecard scorecard)
        policy-summary (:policy_summary candidate)]
    [(- (double (get candidate-scorecard :pass_rate 0.0)))
     (- (double (get candidate-scorecard :top_authority_hit_rate 0.0)))
     (- (double (get candidate-scorecard :required_path_hit_rate 0.0)))
     (- (double (get candidate-scorecard :minimum_confidence_pass_rate 0.0)))
     (double (get candidate-scorecard :degraded_rate 1.0))
     (double (get candidate-scorecard :fallback_rate 1.0))
     (double (get-in candidate-scorecard [:confidence_calibration :mean_absolute_error] 1.0))
     (str (:policy_id policy-summary))
     (str (:version policy-summary))]))

(defn- rank-eligible-candidates [shadow-review]
  (->> (:shadow_candidates shadow-review)
       (filter :eligible_for_auto_promotion)
       (sort-by candidate-ranking-vector)
       (mapv (fn [candidate]
               {:policy_summary (:policy_summary candidate)
                :ranking_vector (candidate-ranking-vector candidate)
                :scorecard (:scorecard candidate)
                :protected_summary (:protected_summary candidate)
                :governance (:governance candidate)
                :approval_tier (:approval_tier candidate)
                :promotion_mode (:promotion_mode candidate)}))))

(defn- candidate-history-stats [runs policy-summary]
  (let [matching (filter #(selected-policy-match? % policy-summary) runs)
        promoted (filter :promoted matching)
        skipped (filter :skipped matching)
        selected-runs (count matching)
        promoted-runs (count promoted)]
    {:selected_runs selected-runs
     :promoted_runs promoted-runs
     :skipped_runs (count skipped)
     :promotion_rate (if (pos? selected-runs)
                       (/ (double promoted-runs) (double selected-runs))
                       0.0)}))

(defn- history-aware-selection-vector [entry prior-runs]
  (let [policy-summary (:policy_summary entry)
        stats (candidate-history-stats prior-runs policy-summary)]
    [(- (double (:promotion_rate stats)))
     (- (long (:promoted_runs stats)))
     (- (long (:selected_runs stats)))
     (long (:skipped_runs stats))
     (:ranking_vector entry)]))

(defn- apply-history-aware-ranking [candidate-ranking prior-runs]
  (->> candidate-ranking
       (mapv (fn [entry]
               (assoc entry
                      :history_stats (candidate-history-stats prior-runs (:policy_summary entry))
                      :selection_vector (history-aware-selection-vector entry prior-runs))))
       (sort-by :selection_vector)
       vec))

(defn- promote-selected-candidate [{:keys [root_path parser_opts registry protected_dataset candidate]}]
  (let [baseline-policy (or (some-> (rp/active-registry-entry registry)
                                    rp/policy-from-entry)
                            (rp/default-retrieval-policy))
        candidate-policy (or (some-> (rp/resolve-registry-entry registry
                                                                (:policy_id candidate)
                                                                (:version candidate))
                                     rp/policy-from-entry)
                             (throw (ex-info "eligible candidate policy not found in registry"
                                             {:type :invalid_request
                                              :message "eligible candidate policy not found in registry"})))
        comparison (compare-policies {:root_path root_path
                                      :dataset protected_dataset
                                      :parser_opts parser_opts
                                      :baseline_policy baseline-policy
                                      :candidate_policy candidate-policy})
        result (promote-policy {:registry registry
                                :candidate_policy_id (:policy_id candidate)
                                :candidate_version (:version candidate)
                                :comparison comparison
                                :dry_run false})]
    (assoc result :comparison comparison)))

(defn scheduled-governance-cycle
  [{:keys [root_path usage_metrics parser_opts registry artifacts_dir surface tenant_id since lookback_days retention_runs write_registry auto_promote select_best_candidate history_aware_selection required_candidate_streak_runs promotion_cooldown_runs]
    :or {root_path "."
         lookback_days 7
         retention_runs 8
         write_registry true
         auto_promote false
         select_best_candidate false
         history_aware_selection false
         required_candidate_streak_runs 1
         promotion_cooldown_runs 0}}]
  (let [review-run (scheduled-policy-review {:root_path root_path
                                             :usage_metrics usage_metrics
                                             :parser_opts parser_opts
                                             :registry registry
                                             :artifacts_dir artifacts_dir
                                             :surface surface
                                             :tenant_id tenant_id
                                             :since since
                                             :lookback_days lookback_days
                                             :retention_runs retention_runs
                                             :write_registry write_registry})
        bundle (:bundle review-run)
        registry-after-review (:registry bundle)
        shadow-review (:shadow_review_report bundle)
        protected-dataset (:protected_replay_dataset bundle)
        prior-runs (prior-governance-runs {:artifacts_dir (or artifacts_dir ".tmp/policy-review")
                                           :limit retention_runs})
        candidate-ranking-base (rank-eligible-candidates shadow-review)
        candidate-ranking (if history_aware_selection
                            (apply-history-aware-ranking candidate-ranking-base prior-runs)
                            candidate-ranking-base)
        eligible-candidate-entries candidate-ranking
        eligible-candidates (mapv :policy_summary eligible-candidate-entries)
        manual-review-candidates (->> (:shadow_candidates shadow-review)
                                      (filter :manual_review_required)
                                      (mapv :policy_summary))
        governance-blocked-candidates (->> (:shadow_candidates shadow-review)
                                           (filter :blocked_by_governance)
                                           (mapv :policy_summary))
        promotion (cond
                    (:skipped shadow-review)
                    {:skipped true
                     :reason (str "shadow_review_" (:reason shadow-review))}

                    (not auto_promote)
                    {:skipped true
                     :reason "auto_promote_disabled"
                     :eligible_candidates eligible-candidates}

                    (empty? eligible-candidates)
                    {:skipped true
                     :reason (cond
                               (seq manual-review-candidates) "no_auto_promotable_candidates"
                               (seq governance-blocked-candidates) "all_candidates_blocked_by_governance"
                               :else "no_eligible_candidates")
                     :manual_review_candidates manual-review-candidates
                     :governance_blocked_candidates governance-blocked-candidates}

                    (and (> (count eligible-candidates) 1)
                         (not select_best_candidate))
                    {:skipped true
                     :reason "multiple_eligible_candidates"
                     :eligible_candidates eligible-candidates
                     :candidate_ranking candidate-ranking}

                    :else
                    (let [candidate-entry (first eligible-candidate-entries)
                          candidate (:policy_summary candidate-entry)
                          selection-mode (if (> (count eligible-candidates) 1)
                                           "best_eligible_candidate"
                                           "single_eligible_candidate")
                          candidate-streak (candidate-streak-length prior-runs candidate)]
                      (cond
                        (cooldown-active? prior-runs promotion_cooldown_runs)
                        {:skipped true
                         :reason "promotion_cooldown_active"
                         :selected_candidate candidate
                         :candidate_ranking candidate-ranking
                         :selection_mode selection-mode
                         :required_candidate_streak_runs required_candidate_streak_runs
                         :candidate_streak_runs candidate-streak
                         :promotion_cooldown_runs promotion_cooldown_runs}

                        (< candidate-streak (max 1 (long required_candidate_streak_runs)))
                        {:skipped true
                         :reason "insufficient_candidate_streak"
                         :selected_candidate candidate
                         :candidate_ranking candidate-ranking
                         :selection_mode selection-mode
                         :required_candidate_streak_runs required_candidate_streak_runs
                         :candidate_streak_runs candidate-streak
                         :promotion_cooldown_runs promotion_cooldown_runs}

                        :else
                        (let [result (promote-selected-candidate {:root_path root_path
                                                                  :parser_opts parser_opts
                                                                  :registry registry-after-review
                                                                  :protected_dataset protected-dataset
                                                                  :candidate candidate})]
                          (assoc result
                                 :selected_candidate (assoc candidate
                                                            :governance (:governance candidate-entry)
                                                            :approval_tier (:approval_tier candidate-entry)
                                                            :promotion_mode (:promotion_mode candidate-entry))
                                 :candidate_ranking candidate-ranking
                                 :selection_mode selection-mode
                                 :required_candidate_streak_runs required_candidate_streak_runs
                                 :candidate_streak_runs candidate-streak
                                 :promotion_cooldown_runs promotion_cooldown_runs)))))
        final-registry (if (:promoted? promotion)
                         (:registry promotion)
                         registry-after-review)
        generated-at (or (get-in review-run [:scheduled_run :generated_at]) (now-iso))
        governance-artifact-path (.getAbsolutePath
                                  (io/file (or artifacts_dir ".tmp/policy-review")
                                           (str "governance-cycle-"
                                                (instant->artifact-token (iso->instant generated-at))
                                                ".json")))
        review-run-ref {:run_id (get-in review-run [:scheduled_run :run_id])
                        :artifact_path (get-in review-run [:scheduled_run :artifact_path])
                        :weekly_review_path (get-in review-run [:scheduled_run :weekly_review_path])
                        :protected_replay_dataset_path (get-in review-run [:scheduled_run :protected_replay_dataset_path])
                        :shadow_review_path (get-in review-run [:scheduled_run :shadow_review_path])}
        governance-artifact {:schema_version "1.0"
                             :generated_at generated-at
                             :review_run_ref review-run-ref
                             :review_run review-run
                             :promotion promotion
                             :registry final-registry}
        _ (write-json-file! governance-artifact-path governance-artifact)
        governance-retention (prune-artifacts! (or artifacts_dir ".tmp/policy-review")
                                               retention_runs
                                               "governance-cycle-"
                                               "governance-cycle-manifest.json")
        governance-run-summary {:run_id (instant->artifact-token (iso->instant generated-at))
                                :generated_at generated-at
                                :scope {:root_path root_path
                                        :surface surface
                                        :tenant_id tenant_id
                                        :since (get-in review-run [:scheduled_run :since])}
                                :artifact_paths {:primary governance-artifact-path
                                                 :governance_cycle governance-artifact-path
                                                 :policy_review_bundle (:artifact_path review-run-ref)
                                                 :weekly_review (:weekly_review_path review-run-ref)
                                                 :protected_replay_dataset (:protected_replay_dataset_path review-run-ref)
                                                 :shadow_review (:shadow_review_path review-run-ref)}
                                :review_run_ref review-run-ref
                                :promotion_summary {:skipped (boolean (:skipped promotion))
                                                    :promoted (boolean (:promoted? promotion))
                                                    :reason (:reason promotion)
                                                    :selection_mode (:selection_mode promotion)
                                                    :selected_candidate (:selected_candidate promotion)
                                                    :selected_policy_id (get-in promotion [:selected_candidate :policy_id])
                                                    :selected_version (get-in promotion [:selected_candidate :version])
                                                    :selected_approval_tier (get-in promotion [:selected_candidate :approval_tier])
                                                    :selected_promotion_mode (get-in promotion [:selected_candidate :promotion_mode])
                                                    :candidate_ranking_size (count (or (:candidate_ranking promotion) candidate-ranking []))
                                                    :eligible_candidates (mapv identity (or (:eligible_candidates promotion) []))
                                                    :manual_review_candidates (mapv identity (or (:manual_review_candidates promotion) []))
                                                    :governance_blocked_candidates (mapv identity (or (:governance_blocked_candidates promotion) []))}}
        governance-index-write (write-retained-index! {:artifacts_dir (or artifacts_dir ".tmp/policy-review")
                                                       :index_name "governance-run-index.json"
                                                       :generated_at generated-at
                                                       :retention_runs retention_runs
                                                       :run_summary governance-run-summary
                                                       :run_key_fn :run_id})
        governance-manifest {:schema_version "1.0"
                             :updated_at generated-at
                             :last_run_at generated-at
                             :root_path root_path
                             :surface surface
                             :tenant_id tenant_id
                             :since (get-in review-run [:scheduled_run :since])
                             :artifacts_dir (.getAbsolutePath (io/file (or artifacts_dir ".tmp/policy-review")))
                             :latest_artifact_path governance-artifact-path
                             :latest_run_id (:run_id governance-run-summary)
                             :latest_review_run_id (:run_id review-run-ref)
                             :latest_policy_review_artifact_path (:artifact_path review-run-ref)
                             :latest_protected_replay_dataset_path (:protected_replay_dataset_path review-run-ref)
                             :latest_shadow_review_path (:shadow_review_path review-run-ref)
                             :governance_index_path (:path governance-index-write)
                             :retention_runs retention_runs
                             :lookback_days lookback_days
                             :write_registry write_registry
                             :auto_promote auto_promote
                             :select_best_candidate select_best_candidate
                             :history_aware_selection history_aware_selection
                             :required_candidate_streak_runs required_candidate_streak_runs
                             :promotion_cooldown_runs promotion_cooldown_runs}
        governance-manifest-path* (manifest-path (or artifacts_dir ".tmp/policy-review")
                                                 "governance-cycle-manifest.json")
        _ (write-json-file! governance-manifest-path* governance-manifest)]
    {:schema_version "1.0"
     :scheduled_run {:generated_at generated-at
                     :run_id (:run_id governance-run-summary)
                     :artifact_path governance-artifact-path
                     :manifest_path governance-manifest-path*
                     :deleted_artifacts (:deleted_artifacts governance-retention)
                     :governance_index_path (:path governance-index-write)
                     :auto_promote auto_promote
                     :select_best_candidate select_best_candidate
                     :history_aware_selection history_aware_selection
                     :required_candidate_streak_runs required_candidate_streak_runs
                     :promotion_cooldown_runs promotion_cooldown_runs}
     :review_run review-run
     :review_run_ref review-run-ref
     :candidate_ranking candidate-ranking
     :promotion promotion
     :registry final-registry
     :governance_run_summary governance-run-summary
     :governance_index (:index governance-index-write)
     :manifest governance-manifest}))

(defn- legacy-governance-runs
  [{:keys [artifacts_dir limit]
    :or {artifacts_dir ".tmp/policy-review"}}]
  (let [files (artifact-files artifacts_dir "governance-cycle-" "governance-cycle-manifest.json")
        selected-files (if limit
                         (take-last (max 0 (long limit)) files)
                         files)]
    (mapv (fn [file]
            (let [artifact (read-json (.getAbsolutePath file))
                  promotion (:promotion artifact)
                  selected-candidate (:selected_candidate promotion)]
              {:run_id (or (get-in artifact [:scheduled_run :run_id])
                           (instant->artifact-token (iso->instant (or (:generated_at artifact)
                                                                      (get-in artifact [:scheduled_run :generated_at])))))
               :generated_at (or (:generated_at artifact)
                                 (get-in artifact [:scheduled_run :generated_at]))
               :artifact_path (.getAbsolutePath file)
               :artifact_paths {:primary (.getAbsolutePath file)
                                :governance_cycle (.getAbsolutePath file)}
               :review_run_ref (:review_run_ref artifact)
               :promoted (boolean (:promoted? promotion))
               :skipped (boolean (:skipped promotion))
               :selection_mode (:selection_mode promotion)
               :promotion_reason (:reason promotion)
               :selected_candidate selected-candidate
               :selected_policy_id (:policy_id selected-candidate)
               :selected_version (:version selected-candidate)
               :selected_approval_tier (:approval_tier selected-candidate)
               :selected_promotion_mode (:promotion_mode selected-candidate)
               :candidate_ranking_size (count (or (:candidate_ranking artifact)
                                                  (get promotion :candidate_ranking)
                                                  []))}))
          selected-files)))

(defn governance-history-report
  [{:keys [artifacts_dir limit]
    :or {artifacts_dir ".tmp/policy-review"}}]
  (let [manifest* (or (read-json-if-exists (manifest-path artifacts_dir "governance-cycle-manifest.json")) {})
        indexed-runs (->> (get-in (read-retained-index artifacts_dir "governance-run-index.json") [:runs])
                          (mapv (fn [run]
                                  {:run_id (:run_id run)
                                   :generated_at (:generated_at run)
                                   :artifact_path (get-in run [:artifact_paths :governance_cycle])
                                   :artifact_paths (:artifact_paths run)
                                   :review_run_ref (:review_run_ref run)
                                   :promoted (get-in run [:promotion_summary :promoted])
                                   :skipped (get-in run [:promotion_summary :skipped])
                                   :selection_mode (get-in run [:promotion_summary :selection_mode])
                                   :promotion_reason (get-in run [:promotion_summary :reason])
                                   :selected_candidate (get-in run [:promotion_summary :selected_candidate])
                                   :selected_policy_id (get-in run [:promotion_summary :selected_policy_id])
                                   :selected_version (get-in run [:promotion_summary :selected_version])
                                   :selected_approval_tier (get-in run [:promotion_summary :selected_approval_tier])
                                   :selected_promotion_mode (get-in run [:promotion_summary :selected_promotion_mode])
                                   :candidate_ranking_size (get-in run [:promotion_summary :candidate_ranking_size])})))
        runs (let [source-runs (if (seq indexed-runs)
                                 indexed-runs
                                 (legacy-governance-runs {:artifacts_dir artifacts_dir :limit nil}))]
               (if limit
                 (vec (take-last (max 0 (long limit)) source-runs))
                 (vec source-runs)))
        summary {:total_runs (count runs)
                 :promoted_runs (count (filter :promoted runs))
                 :skipped_runs (count (filter :skipped runs))
                 :selection_mode_counts (->> runs
                                             (keep :selection_mode)
                                             frequencies
                                             (into (sorted-map)))
                 :promotion_reason_counts (->> runs
                                               (keep :promotion_reason)
                                               frequencies
                                               (into (sorted-map)))
                 :selected_policy_counts (->> runs
                                              (keep :selected_policy_id)
                                              frequencies
                                              (into (sorted-map)))
                 :selected_approval_tier_counts (->> runs
                                                     (keep :selected_approval_tier)
                                                     frequencies
                                                     (into (sorted-map)))
                 :selected_promotion_mode_counts (->> runs
                                                      (keep :selected_promotion_mode)
                                                      frequencies
                                                      (into (sorted-map)))
                 :latest_run_at (some-> runs last :generated_at)}]
    {:schema_version "1.0"
     :artifacts_dir (.getAbsolutePath (io/file artifacts_dir))
     :manifest manifest*
     :index_used (boolean (seq indexed-runs))
     :summary summary
     :runs runs}))

(defn- prior-governance-runs
  [{:keys [artifacts_dir limit]
    :or {artifacts_dir ".tmp/policy-review"}}]
  (:runs (governance-history-report {:artifacts_dir artifacts_dir
                                     :limit limit})))

(defn- selected-policy-match? [run policy-summary]
  (and policy-summary
       (= (:policy_id policy-summary) (:selected_policy_id run))
       (= (:version policy-summary) (:selected_version run))))

(defn- candidate-streak-length [runs candidate]
  (inc
   (count
    (take-while #(selected-policy-match? % candidate)
                (reverse runs)))))

(defn- cooldown-active? [runs promotion-cooldown-runs]
  (let [cooldown (max 0 (long (or promotion-cooldown-runs 0)))]
    (when (pos? cooldown)
      (some :promoted (take-last cooldown runs)))))

(defn phase5-review-queue
  [{:keys [artifacts_dir limit]
    :or {artifacts_dir ".tmp/policy-review"}}]
  (let [review-runs (->> (get-in (read-retained-index artifacts_dir "review-run-index.json") [:runs])
                         (sort-by :generated_at)
                         vec)
        governance-runs (:runs (governance-history-report {:artifacts_dir artifacts_dir
                                                           :limit nil}))
        review-items (reduce (fn [acc run]
                               (let [base-item {:generated_at (:generated_at run)
                                                :run_id (:run_id run)
                                                :review_run_id (:run_id run)
                                                :scope (:scope run)
                                                :artifact_paths (:artifact_paths run)}
                                     with-gap-item (if (= "no_protected_queries" (get-in run [:shadow_review_summary :reason]))
                                                     (conj acc
                                                           (merge base-item
                                                                  {:item_id (str (:run_id run) ":no_protected_queries")
                                                                   :item_key [:no_protected_queries
                                                                              (get-in run [:scope :root_path])
                                                                              (get-in run [:scope :surface])
                                                                              (get-in run [:scope :tenant_id])]
                                                                   :reason_code "no_protected_queries"
                                                                   :required_action "collect_more_feedback"}))
                                                     acc)]
                                 (into with-gap-item
                                       (for [candidate (get-in run [:shadow_review_summary :manual_review_candidates])]
                                         (merge base-item
                                                {:item_id (str (:run_id run) ":manual:" (:policy_id candidate) ":" (:version candidate))
                                                 :item_key [:manual_approval_required (:policy_id candidate) (:version candidate)]
                                                 :policy_summary candidate
                                                 :reason_code "manual_approval_required"
                                                 :required_action "review_candidate_and_decide_promotion"})))))
                             []
                             review-runs)
        governance-items (reduce (fn [acc run]
                                   (let [reason (:promotion_reason run)
                                         base-item {:generated_at (:generated_at run)
                                                    :run_id (:run_id run)
                                                    :governance_run_id (:run_id run)
                                                    :artifact_paths (:artifact_paths run)
                                                    :review_run_ref (:review_run_ref run)}]
                                     (case reason
                                       "multiple_eligible_candidates"
                                       (conj acc (merge base-item
                                                        {:item_id (str (:run_id run) ":multiple_eligible_candidates")
                                                         :item_key [:multiple_eligible_candidates
                                                                    (get-in run [:review_run_ref :run_id])]
                                                         :reason_code reason
                                                         :required_action "choose_best_candidate_from_ranking"}))

                                       "insufficient_candidate_streak"
                                       (conj acc (merge base-item
                                                        {:item_id (str (:run_id run) ":insufficient_candidate_streak")
                                                         :item_key [:insufficient_candidate_streak
                                                                    (:selected_policy_id run)
                                                                    (:selected_version run)]
                                                         :policy_summary {:policy_id (:selected_policy_id run)
                                                                          :version (:selected_version run)}
                                                         :reason_code reason
                                                         :required_action "wait_for_next_consistent_review_run"}))

                                       "promotion_cooldown_active"
                                       (conj acc (merge base-item
                                                        {:item_id (str (:run_id run) ":promotion_cooldown_active")
                                                         :item_key [:promotion_cooldown_active
                                                                    (:selected_policy_id run)
                                                                    (:selected_version run)]
                                                         :policy_summary {:policy_id (:selected_policy_id run)
                                                                          :version (:selected_version run)}
                                                         :reason_code reason
                                                         :required_action "wait_for_cooldown_window_to_expire"}))

                                       "all_candidates_blocked_by_governance"
                                       (conj acc (merge base-item
                                                        {:item_id (str (:run_id run) ":all_candidates_blocked_by_governance")
                                                         :item_key [:all_candidates_blocked_by_governance
                                                                    (get-in run [:review_run_ref :run_id])]
                                                         :reason_code reason
                                                         :required_action "review_governance_tier_and_candidate_policy"}))
                                       acc)))
                                 []
                                 governance-runs)
        latest-items (->> (concat review-items governance-items)
                          (sort-by :generated_at)
                          (reduce (fn [acc item]
                                    (assoc acc (:item_key item) (dissoc item :item_key)))
                                  {})
                          vals
                          (sort-by :generated_at)
                          vec)
        items (if limit
                (vec (take-last (max 0 (long limit)) latest-items))
                latest-items)
        summary {:total_items (count items)
                 :reason_counts (->> items
                                     (map :reason_code)
                                     frequencies
                                     (into (sorted-map)))
                 :latest_item_at (some-> items last :generated_at)}]
    {:schema_version "1.0"
     :artifacts_dir (.getAbsolutePath (io/file artifacts_dir))
     :summary summary
     :items items}))

(defn phase5-status-report
  [{:keys [artifacts_dir limit]
    :or {artifacts_dir ".tmp/policy-review"
         limit 20}}]
  (let [review-runs (->> (get-in (read-retained-index artifacts_dir "review-run-index.json") [:runs])
                         (sort-by :generated_at)
                         vec)
        selected-review-runs (vec (take-last (max 0 (long limit)) review-runs))
        governance-history (governance-history-report {:artifacts_dir artifacts_dir
                                                       :limit limit})
        queue (phase5-review-queue {:artifacts_dir artifacts_dir
                                    :limit limit})
        summary {:review_runs (count selected-review-runs)
                 :governance_runs (count (:runs governance-history))
                 :pending_queue_items (get-in queue [:summary :total_items] 0)
                 :protected_queries_total (reduce + 0 (map #(get-in % [:protected_replay_summary :protected_queries] 0)
                                                           selected-review-runs))
                 :latest_review_at (some-> selected-review-runs last :generated_at)
                 :latest_governance_at (get-in governance-history [:summary :latest_run_at])
                 :pending_reason_counts (get-in queue [:summary :reason_counts] {})
                 :promoted_runs (get-in governance-history [:summary :promoted_runs] 0)
                 :skipped_runs (get-in governance-history [:summary :skipped_runs] 0)}]
    {:schema_version "1.0"
     :artifacts_dir (.getAbsolutePath (io/file artifacts_dir))
     :summary summary
     :review_runs selected-review-runs
     :governance_runs (:runs governance-history)
     :pending_queue (:items queue)}))

(defn scheduled-phase5-cycle
  [{:keys [root_path usage_metrics parser_opts registry artifacts_dir surface tenant_id since lookback_days retention_runs write_registry auto_promote select_best_candidate history_aware_selection required_candidate_streak_runs promotion_cooldown_runs limit]
    :or {root_path "."
         lookback_days 7
         retention_runs 8
         write_registry true
         auto_promote false
         select_best_candidate false
         history_aware_selection false
         required_candidate_streak_runs 1
         promotion_cooldown_runs 0
         limit 20}}]
  (let [artifacts-dir* (or artifacts_dir ".tmp/policy-review")
        governance-cycle (scheduled-governance-cycle {:root_path root_path
                                                      :usage_metrics usage_metrics
                                                      :parser_opts parser_opts
                                                      :registry registry
                                                      :artifacts_dir artifacts-dir*
                                                      :surface surface
                                                      :tenant_id tenant_id
                                                      :since since
                                                      :lookback_days lookback_days
                                                      :retention_runs retention_runs
                                                      :write_registry write_registry
                                                      :auto_promote auto_promote
                                                      :select_best_candidate select_best_candidate
                                                      :history_aware_selection history_aware_selection
                                                      :required_candidate_streak_runs required_candidate_streak_runs
                                                      :promotion_cooldown_runs promotion_cooldown_runs})
        run-id (get-in governance-cycle [:scheduled_run :run_id])
        generated-at (or (get-in governance-cycle [:scheduled_run :generated_at]) (now-iso))
        review-run-ref (:review_run_ref governance-cycle)
        governance-run-ref {:run_id run-id
                            :artifact_path (get-in governance-cycle [:scheduled_run :artifact_path])
                            :manifest_path (get-in governance-cycle [:scheduled_run :manifest_path])}
        queue (phase5-review-queue {:artifacts_dir artifacts-dir*
                                    :limit limit})
        current-queue-items (->> (:items queue)
                                 (filter #(= run-id (:run_id %)))
                                 vec)
        status-report (phase5-status-report {:artifacts_dir artifacts-dir*
                                             :limit limit})
        artifact-path (.getAbsolutePath
                       (io/file artifacts-dir*
                                (str "phase5-cycle-"
                                     (instant->artifact-token (iso->instant generated-at))
                                     ".json")))
        artifact-paths {:primary artifact-path
                        :phase5_cycle artifact-path
                        :governance_cycle (:artifact_path governance-run-ref)
                        :policy_review_bundle (:artifact_path review-run-ref)
                        :weekly_review (:weekly_review_path review-run-ref)
                        :protected_replay_dataset (:protected_replay_dataset_path review-run-ref)
                        :shadow_review (:shadow_review_path review-run-ref)}
        phase5-artifact {:schema_version "1.0"
                         :generated_at generated-at
                         :review_run_ref review-run-ref
                         :governance_run_ref governance-run-ref
                         :artifact_paths artifact-paths
                         :governance_run_summary (:governance_run_summary governance-cycle)
                         :queue_summary (:summary queue)
                         :current_queue_items current-queue-items
                         :status_summary (:summary status-report)}
        _ (write-json-file! artifact-path phase5-artifact)
        retention (prune-artifacts! artifacts-dir*
                                    retention_runs
                                    "phase5-cycle-"
                                    "phase5-cycle-manifest.json")
        phase5-run-summary {:run_id run-id
                            :generated_at generated-at
                            :scope {:root_path root_path
                                    :surface surface
                                    :tenant_id tenant_id
                                    :since (get-in governance-cycle [:review_run :scheduled_run :since])}
                            :artifact_paths artifact-paths
                            :review_run_ref review-run-ref
                            :governance_run_ref governance-run-ref
                            :promotion_summary (get-in governance-cycle [:governance_run_summary :promotion_summary])
                            :queue_summary (:summary queue)
                            :current_queue_reason_counts (->> current-queue-items
                                                              (map :reason_code)
                                                              frequencies
                                                              (into (sorted-map)))
                            :status_summary (:summary status-report)}
        phase5-index-write (write-retained-index! {:artifacts_dir artifacts-dir*
                                                   :index_name "phase5-run-index.json"
                                                   :generated_at generated-at
                                                   :retention_runs retention_runs
                                                   :run_summary phase5-run-summary
                                                   :run_key_fn :run_id})
        manifest-data {:schema_version "1.0"
                       :updated_at generated-at
                       :last_run_at generated-at
                       :root_path root_path
                       :surface surface
                       :tenant_id tenant_id
                       :since (get-in governance-cycle [:review_run :scheduled_run :since])
                       :artifacts_dir (.getAbsolutePath (io/file artifacts-dir*))
                       :latest_artifact_path artifact-path
                       :latest_run_id run-id
                       :latest_review_run_id (:run_id review-run-ref)
                       :latest_governance_run_id run-id
                       :latest_policy_review_artifact_path (:artifact_path review-run-ref)
                       :latest_governance_cycle_artifact_path (:artifact_path governance-run-ref)
                       :latest_weekly_review_path (:weekly_review_path review-run-ref)
                       :latest_protected_replay_dataset_path (:protected_replay_dataset_path review-run-ref)
                       :latest_shadow_review_path (:shadow_review_path review-run-ref)
                       :phase5_index_path (:path phase5-index-write)
                       :retention_runs retention_runs
                       :lookback_days lookback_days
                       :write_registry write_registry
                       :auto_promote auto_promote
                       :select_best_candidate select_best_candidate
                       :history_aware_selection history_aware_selection
                       :required_candidate_streak_runs required_candidate_streak_runs
                       :promotion_cooldown_runs promotion_cooldown_runs
                       :limit limit}
        manifest-path* (manifest-path artifacts-dir* "phase5-cycle-manifest.json")
        _ (write-json-file! manifest-path* manifest-data)]
    {:schema_version "1.0"
     :scheduled_run {:generated_at generated-at
                     :run_id run-id
                     :artifact_path artifact-path
                     :manifest_path manifest-path*
                     :deleted_artifacts (:deleted_artifacts retention)
                     :phase5_index_path (:path phase5-index-write)
                     :limit limit}
     :review_run_ref review-run-ref
     :governance_run_ref governance-run-ref
     :governance_cycle governance-cycle
     :queue queue
     :current_queue_items current-queue-items
     :status_report status-report
     :phase5_run_summary phase5-run-summary
     :phase5_index (:index phase5-index-write)
     :manifest manifest-data}))

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
          "--weekly-review" (recur (assoc m :weekly_review_path v) rest)
          "--surface" (recur (assoc m :surface v) rest)
          "--tenant-id" (recur (assoc m :tenant_id v) rest)
          "--since" (recur (assoc m :since v) rest)
          "--artifacts-dir" (recur (assoc m :artifacts_dir v) rest)
          "--retention-runs" (recur (assoc m :retention_runs (Long/parseLong v)) rest)
          "--lookback-days" (recur (assoc m :lookback_days (Long/parseLong v)) rest)
          "--limit" (recur (assoc m :limit (Long/parseLong v)) rest)
          "--required-candidate-streak-runs" (recur (assoc m :required_candidate_streak_runs (Long/parseLong v)) rest)
          "--promotion-cooldown-runs" (recur (assoc m :promotion_cooldown_runs (Long/parseLong v)) rest)
          "--out" (recur (assoc m :out_path v) rest)
          "--write-registry" (recur (assoc m :write_registry true) rest)
          "--manual-approval" (recur (assoc m :manual_approval true) rest)
          "--auto-promote" (recur (assoc m :auto_promote true) rest)
          "--select-best-candidate" (recur (assoc m :select_best_candidate true) rest)
          "--history-aware-selection" (recur (assoc m :history_aware_selection true) rest)
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

(defn- run-promote-policy-command [{:keys [root_path dataset_path registry_path candidate_policy_id candidate_version out_path write_registry dry_run manual_approval]}]
  (when-not (and dataset_path registry_path candidate_policy_id)
    (println "Usage: clojure -M:eval promote-policy --root <repo-root> --dataset <dataset.json> --registry <registry.edn> --candidate-policy-id <id> [--candidate-version <version>] [--manual-approval] [--write-registry] [--dry-run] [--out <output.json>]")
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
                                :manual_approval manual_approval
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

(defn- run-protected-replay-dataset-command [{:keys [weekly_review_path out_path]}]
  (when-not weekly_review_path
    (println "Usage: clojure -M:eval protected-replay-dataset --weekly-review <weekly-review.json> [--out <output.json>]")
    (System/exit 1))
  (let [weekly-review (read-json weekly_review_path)
        result (sci/review-report->protected-replay-dataset weekly-review)]
    (print-or-write! out_path result)
    (System/exit 0)))

(defn- run-policy-review-pipeline-command [{:keys [root_path usage_metrics_jdbc_url registry_path surface tenant_id since out_path write_registry]}]
  (when-not (and root_path usage_metrics_jdbc_url registry_path)
    (println "Usage: clojure -M:eval policy-review-pipeline --root <repo-root> --usage-metrics-jdbc-url <jdbc-url> --registry <registry.edn> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--write-registry] [--out <output.json>]")
    (System/exit 1))
  (let [metrics (sci/postgres-usage-metrics {:jdbc-url usage_metrics_jdbc_url
                                             :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                             :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")})
        registry (rp/load-registry registry_path)
        result (policy-review-pipeline {:root_path root_path
                                        :usage_metrics metrics
                                        :registry registry
                                        :surface surface
                                        :tenant_id tenant_id
                                        :since since
                                        :write_registry write_registry})]
    (when write_registry
      (rp/write-registry! registry_path (:registry result)))
    (print-or-write! out_path result)
    (System/exit 0)))

(defn- run-scheduled-policy-review-command [{:keys [root_path usage_metrics_jdbc_url registry_path surface tenant_id since artifacts_dir retention_runs lookback_days out_path write_registry]}]
  (when-not (and root_path usage_metrics_jdbc_url registry_path)
    (println "Usage: clojure -M:eval scheduled-policy-review --root <repo-root> --usage-metrics-jdbc-url <jdbc-url> --registry <registry.edn> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--artifacts-dir <dir>] [--retention-runs <n>] [--lookback-days <n>] [--write-registry] [--out <output.json>]")
    (System/exit 1))
  (let [metrics (sci/postgres-usage-metrics {:jdbc-url usage_metrics_jdbc_url
                                             :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                             :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")})
        registry (rp/load-registry registry_path)
        result (scheduled-policy-review {:root_path root_path
                                         :usage_metrics metrics
                                         :registry registry
                                         :surface surface
                                         :tenant_id tenant_id
                                         :since since
                                         :artifacts_dir artifacts_dir
                                         :retention_runs retention_runs
                                         :lookback_days lookback_days
                                         :write_registry write_registry})]
    (when write_registry
      (rp/write-registry! registry_path (get-in result [:bundle :registry])))
    (print-or-write! out_path result)
    (System/exit 0)))

(defn- run-scheduled-governance-cycle-command [{:keys [root_path usage_metrics_jdbc_url registry_path surface tenant_id since artifacts_dir retention_runs lookback_days out_path write_registry auto_promote select_best_candidate history_aware_selection required_candidate_streak_runs promotion_cooldown_runs]}]
  (when-not (and root_path usage_metrics_jdbc_url registry_path)
    (println "Usage: clojure -M:eval scheduled-governance-cycle --root <repo-root> --usage-metrics-jdbc-url <jdbc-url> --registry <registry.edn> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--artifacts-dir <dir>] [--retention-runs <n>] [--lookback-days <n>] [--required-candidate-streak-runs <n>] [--promotion-cooldown-runs <n>] [--write-registry] [--auto-promote] [--select-best-candidate] [--history-aware-selection] [--out <output.json>]")
    (System/exit 1))
  (let [metrics (sci/postgres-usage-metrics {:jdbc-url usage_metrics_jdbc_url
                                             :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                             :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")})
        registry (rp/load-registry registry_path)
        result (scheduled-governance-cycle {:root_path root_path
                                            :usage_metrics metrics
                                            :registry registry
                                            :surface surface
                                            :tenant_id tenant_id
                                            :since since
                                            :artifacts_dir artifacts_dir
                                            :retention_runs retention_runs
                                            :lookback_days lookback_days
                                            :write_registry write_registry
                                            :auto_promote auto_promote
                                            :select_best_candidate select_best_candidate
                                            :history_aware_selection history_aware_selection
                                            :required_candidate_streak_runs required_candidate_streak_runs
                                            :promotion_cooldown_runs promotion_cooldown_runs})]
    (when write_registry
      (rp/write-registry! registry_path (:registry result)))
    (print-or-write! out_path result)
    (System/exit (if (or (:promoted? (:promotion result))
                         (:skipped (:promotion result)))
                   0
                   1))))

(defn- run-governance-history-report-command [{:keys [artifacts_dir limit out_path]}]
  (when-not artifacts_dir
    (println "Usage: clojure -M:eval governance-history-report --artifacts-dir <dir> [--limit <n>] [--out <output.json>]")
    (System/exit 1))
  (let [result (governance-history-report {:artifacts_dir artifacts_dir
                                           :limit limit})]
    (print-or-write! out_path result)
    (System/exit 0)))

(defn- run-phase5-review-queue-command [{:keys [artifacts_dir limit out_path]}]
  (when-not artifacts_dir
    (println "Usage: clojure -M:eval phase5-review-queue --artifacts-dir <dir> [--limit <n>] [--out <output.json>]")
    (System/exit 1))
  (let [result (phase5-review-queue {:artifacts_dir artifacts_dir
                                     :limit limit})]
    (print-or-write! out_path result)
    (System/exit 0)))

(defn- run-phase5-status-report-command [{:keys [artifacts_dir limit out_path]}]
  (when-not artifacts_dir
    (println "Usage: clojure -M:eval phase5-status-report --artifacts-dir <dir> [--limit <n>] [--out <output.json>]")
    (System/exit 1))
  (let [result (phase5-status-report {:artifacts_dir artifacts_dir
                                      :limit limit})]
    (print-or-write! out_path result)
    (System/exit 0)))

(defn- run-scheduled-phase5-cycle-command [{:keys [root_path usage_metrics_jdbc_url registry_path surface tenant_id since artifacts_dir retention_runs lookback_days out_path write_registry auto_promote select_best_candidate history_aware_selection required_candidate_streak_runs promotion_cooldown_runs limit]}]
  (when (or (nil? usage_metrics_jdbc_url) (nil? registry_path))
    (println "Usage: clojure -M:eval scheduled-phase5-cycle --root <repo-root> --usage-metrics-jdbc-url <jdbc-url> --registry <registry.edn> [--surface <surface>] [--tenant-id <tenant>] [--since <iso-timestamp>] [--artifacts-dir <dir>] [--retention-runs <n>] [--lookback-days <n>] [--required-candidate-streak-runs <n>] [--promotion-cooldown-runs <n>] [--limit <n>] [--write-registry] [--auto-promote] [--select-best-candidate] [--history-aware-selection] [--out <output.json>]")
    (System/exit 1))
  (let [usage-metrics (sci/postgres-usage-metrics {:jdbc-url usage_metrics_jdbc_url
                                                   :table-name "usage_events"})
        registry (read-string (slurp registry_path))
        result (scheduled-phase5-cycle {:root_path root_path
                                        :usage_metrics usage-metrics
                                        :registry registry
                                        :artifacts_dir artifacts_dir
                                        :surface surface
                                        :tenant_id tenant_id
                                        :since since
                                        :lookback_days lookback_days
                                        :retention_runs retention_runs
                                        :write_registry write_registry
                                        :auto_promote auto_promote
                                        :select_best_candidate select_best_candidate
                                        :history_aware_selection history_aware_selection
                                        :required_candidate_streak_runs required_candidate_streak_runs
                                        :promotion_cooldown_runs promotion_cooldown_runs
                                        :limit limit})]
    (print-or-write! out_path result)
    (System/exit 0)))

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
      "protected-replay-dataset" (run-protected-replay-dataset-command (parse-args rest-args))
      "policy-review-pipeline" (run-policy-review-pipeline-command (parse-args rest-args))
      "scheduled-policy-review" (run-scheduled-policy-review-command (parse-args rest-args))
      "scheduled-governance-cycle" (run-scheduled-governance-cycle-command (parse-args rest-args))
      "scheduled-phase5-cycle" (run-scheduled-phase5-cycle-command (parse-args rest-args))
      "governance-history-report" (run-governance-history-report-command (parse-args rest-args))
      "phase5-review-queue" (run-phase5-review-queue-command (parse-args rest-args))
      "phase5-status-report" (run-phase5-status-report-command (parse-args rest-args))
      (run-replay-command (parse-args args)))))
