(ns semidx.runtime.benchmark-report
  "Stage 3 aggregator for the retrieval value benchmark (plans/020 Stage 3).

   Aggregation is attempt-first: every recorded attempt is aggregated on
   `task_attempt_id` before any outcome join, and only then rolled up by
   (benchmark_run_id, task_id, arm). Repeated runs of the same task therefore
   never collapse, and no many-to-many join is required.

   Three cost rules are enforced rather than assumed:

   - Cost is re-derived from the stored response/usage matrix through the same
     versioned adapters and price schedule that recorded it. A harness-recorded
     total is compared against the derivation and a disagreement is reported,
     never silently trusted.
   - An attempt whose pricing is `unresolved` or `historical_only` is excluded
     from the cost verdict instead of being priced at zero.
   - The primary comparison is paired per task over cost-eligible attempts, so
     an arm cannot win by contributing a different set of tasks.

   The aggregator does not produce a Phase 1 verdict while the Stage 0 threshold
   lock is pending. Without a locked threshold it reports the observed
   comparison and a provisional signal only."
  (:require [clojure.string :as str]
            [semidx.runtime.benchmark-suite :as suite]
            [semidx.runtime.benchmark-usage :as bu]
            [semidx.runtime.usage-metrics :as usage])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Preregistered identities (reports/023, SPEC.md 5.1)
;; ---------------------------------------------------------------------------

(def report-schema-version "benchmark_report_v1")

(def attempt-surface "benchmark")
(def attempt-operation "benchmark_attempt")

(def candidate-arm "A")
(def comparator-arm "B")
(def control-arms ["C" "D"])

(def arm-verdict-roles
  {"A" "candidate"
   "B" "primary_comparator"
   "C" "diagnostic_control"
   "D" "ecological_control"})

(def provisional-threshold
  "SPEC.md 5.1 provisional (moderate) threshold.

   `locked` stays false until the Stage 0 calibration pilot has run and the
   final threshold has been fixed. A caller that has completed that gate passes
   its own locked threshold through the `:threshold` option."
  {:threshold_id "spec_5_1_provisional"
   :source "SPEC.md 5.1"
   :locked false
   :min_cost_reduction_pct 50.0
   :min_cost_ratio 2.0
   :min_success_delta_pp -5.0
   :max_wall_clock_ratio 1.5
   :min_tasks 30
   :requires_external_repository true})

(def pooling-fields
  "Fields that must agree before observations may be pooled into one verdict."
  [:suite_version :harness_version :task_prompt_policy_id :arm_policy_bundle_id
   :execution_budget_policy_id :cache_protocol_id :price_schedule_id])

(def compared-usage-fields
  [:input_uncached :input_cache_read :input_total :output_total :grand_total
   :cost_usd :pricing_status])

;; ---------------------------------------------------------------------------
;; Small helpers
;; ---------------------------------------------------------------------------

(defn- now-iso [] (str (Instant/now)))

(defn- mean [values]
  (when (seq values)
    (/ (reduce + 0.0 (map double values)) (count values))))

(defn- median [values]
  (when (seq values)
    (let [sorted (vec (sort (map double values)))
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0)))))

(defn- ratio-or-nil [numerator denominator]
  (when (and numerator denominator (pos? (double denominator)))
    (/ (double numerator) (double denominator))))

(defn wilson-interval
  "Wilson score interval for a success rate.

   Reported so success parity between arms can be read against the sampling
   noise instead of a bare point estimate."
  [successes trials]
  (when (pos? (long trials))
    (let [z 1.96
          n (double trials)
          p (/ (double successes) n)
          denom (+ 1.0 (/ (* z z) n))
          centre (/ (+ p (/ (* z z) (* 2.0 n))) denom)
          margin (/ (* z (Math/sqrt (+ (/ (* p (- 1.0 p)) n)
                                       (/ (* z z) (* 4.0 n n)))))
                    denom)]
      {:z z
       :lower (max 0.0 (- centre margin))
       :upper (min 1.0 (+ centre margin))})))

(defn- intervals-overlap? [a b]
  (when (and a b)
    (and (<= (:lower a) (:upper b))
         (<= (:lower b) (:upper a)))))

(defn- stage-name [event]
  (or (get-in event [:payload :stage_name])
      (case (:operation event)
        "resolve_context" "selection"
        "expand_context" "expand"
        "fetch_context_detail" "detail"
        nil)))

;; ---------------------------------------------------------------------------
;; Attempt-first aggregation
;; ---------------------------------------------------------------------------

(defn- usage-totals-mismatch
  "Fields where a harness-recorded attempt total disagrees with the derivation.

   Returns nil when no totals were recorded, so an absent record is not
   reported as a disagreement."
  [recorded derived]
  (when (map? recorded)
    (let [differing (vec (for [field compared-usage-fields
                               :let [a (get recorded field)
                                     b (get derived field)]
                               :when (if (and (number? a) (number? b))
                                       (> (Math/abs (- (double a) (double b))) 1e-9)
                                       (not= a b))]
                           field))]
      (when (seq differing) differing))))

(defn attempt-record->attempt
  "Aggregate one recorded feedback record into one benchmark attempt.

   Cost is derived from the attempt's own response/usage matrix. The recorded
   totals are kept only for the disagreement check."
  [record]
  (let [payload (:payload record)
        matrix (vec (:usage_matrix payload))
        derived (bu/aggregate-attempt-usage matrix)
        recorded (:usage_totals payload)]
    {:task_attempt_id (or (:task_attempt_id payload) (:task_id record))
     :benchmark_run_id (:benchmark_run_id payload)
     :task_id (:task_id payload)
     :task_type (:task_type payload)
     :arm (:arm payload)
     :arm_policy_id (:arm_policy_id payload)
     :repo_key (:repo_key payload)
     :repo_revision (:repo_revision payload)
     :dirty_state (:dirty_state payload)
     :seed (:seed payload)
     :sequence_index (:sequence_index payload)
     :occurred_at (:occurred_at record)
     :outcome (or (:benchmark_outcome payload) (:feedback_outcome record))
     :not_applicable_reason (:not_applicable_reason payload)
     :feedback_reason (:feedback_reason record)
     :retrieval_issue_codes (vec (:retrieval_issue_codes record))
     :policy_violation_reason (get-in payload [:policy_violation :reason])
     :stale_snapshot_reuse (boolean (:stale_snapshot_reuse payload))
     :missing_snapshot_evidence (boolean (:missing_snapshot_evidence payload))
     :excess_context_cost (boolean (:excess_context_cost payload))
     :wall_clock_ms (:wall_clock_ms payload)
     :turn_count (:turn_count derived)
     :cost_usd (:cost_usd derived)
     :pricing_status (:pricing_status derived)
     :pricing_status_reasons (vec (:pricing_status_reasons derived))
     :cost_verdict_eligible (boolean (:cost_verdict_eligible derived))
     :grand_total_tokens (:grand_total derived)
     :input_total_tokens (:input_total derived)
     :output_total_tokens (:output_total derived)
     :input_cache_read_tokens (:input_cache_read derived)
     :usage_totals derived
     :usage_totals_mismatch (usage-totals-mismatch recorded derived)
     :pooling_key (select-keys payload pooling-fields)}))

(defn- newer-record? [candidate incumbent]
  (pos? (compare (str (:occurred_at candidate)) (str (:occurred_at incumbent)))))

(defn attempts-from-records
  "Aggregate feedback records into attempts keyed by `task_attempt_id`.

   A repeated record for the same attempt is a re-record of one observation,
   not a second observation, so the latest one wins and the collision is
   counted. A record without a `task_attempt_id` is rejected instead of being
   folded into an arbitrary attempt."
  [records]
  (let [{:keys [attempts duplicates unusable]}
        (reduce (fn [acc record]
                  (let [attempt (attempt-record->attempt record)
                        id (:task_attempt_id attempt)]
                    (cond
                      (str/blank? (str id))
                      (update acc :unusable inc)

                      (contains? (:attempts acc) id)
                      (cond-> (update acc :duplicates inc)
                        (newer-record? attempt (get-in acc [:attempts id]))
                        (assoc-in [:attempts id] attempt))

                      :else
                      (assoc-in acc [:attempts id] attempt))))
                {:attempts {} :duplicates 0 :unusable 0}
                records)]
    {:attempts (vec (sort-by (juxt :benchmark_run_id :task_id :arm :sequence_index
                                   :task_attempt_id)
                             (vals attempts)))
     :duplicate_attempt_records duplicates
     :unusable_records unusable}))

;; ---------------------------------------------------------------------------
;; Roll-up
;; ---------------------------------------------------------------------------

(defn- outcome-counts [attempts]
  (let [freqs (frequencies (map :outcome attempts))]
    {:success (get freqs "success" 0)
     :failure (get freqs "failure" 0)
     :error (get freqs "error" 0)
     :not_applicable (get freqs "not_applicable" 0)}))

(defn- scored-attempts
  "Attempts that carry a success/failure signal.

   `not_applicable` is the preregistered representation of an unavailable arm
   capability, so it is excluded from the success denominator and reported
   separately rather than counted as a loss."
  [attempts]
  (vec (remove #(= "not_applicable" (:outcome %)) attempts)))

(defn- success-count [attempts]
  (count (filter #(= "success" (:outcome %)) attempts)))

(defn- cost-eligible [attempts]
  (vec (filter :cost_verdict_eligible attempts)))

(defn- cost-exclusion-reasons [attempts]
  (->> attempts
       (remove :cost_verdict_eligible)
       (map #(or (:pricing_status %) "unresolved"))
       frequencies
       (into (sorted-map))))

(defn- total-cost [attempts]
  (reduce + 0.0 (map #(double (or (:cost_usd %) 0.0)) attempts)))

(defn task-arm-rollup
  "Roll attempts up by (benchmark_run_id, task_id, arm).

   The key keeps `benchmark_run_id` so two runs of the same task never merge."
  [attempts]
  (->> attempts
       (group-by (juxt :benchmark_run_id :task_id :arm))
       (map (fn [[[run-id task-id arm] group]]
              (let [scored (scored-attempts group)
                    eligible (cost-eligible scored)]
                {:benchmark_run_id run-id
                 :task_id task-id
                 :task_type (:task_type (first group))
                 :repo_key (:repo_key (first group))
                 :arm arm
                 :attempts (count group)
                 :outcomes (outcome-counts group)
                 :scored_attempts (count scored)
                 :successes (success-count scored)
                 :success_rate (ratio-or-nil (success-count scored) (count scored))
                 :cost_eligible_attempts (count eligible)
                 :cost_excluded_attempts (- (count scored) (count eligible))
                 :cost_exclusion_reasons (cost-exclusion-reasons scored)
                 :total_cost_usd (when (seq eligible) (total-cost eligible))
                 :mean_cost_usd (when (seq eligible) (mean (map :cost_usd eligible)))
                 :mean_wall_clock_ms (mean (keep :wall_clock_ms scored))
                 :stale_snapshot_reuse (count (filter :stale_snapshot_reuse group))
                 :excess_context_cost (count (filter :excess_context_cost group))})))
       (sort-by (juxt :benchmark_run_id :task_id :arm))
       vec))

(defn- arm-summary [arm attempts]
  (let [scored (scored-attempts attempts)
        eligible (cost-eligible scored)
        eligible-successes (success-count eligible)
        cost (total-cost eligible)
        na (filter #(= "not_applicable" (:outcome %)) attempts)]
    {:arm arm
     :verdict_role (get arm-verdict-roles arm "unregistered_arm")
     :arm_policy_ids (vec (sort (distinct (keep :arm_policy_id attempts))))
     :attempts (count attempts)
     :outcomes (outcome-counts attempts)
     :not_applicable {:attempts (count na)
                      :reasons (vec (sort (distinct (keep :not_applicable_reason na))))}
     :success {:scored_attempts (count scored)
               :successes (success-count scored)
               :success_rate (ratio-or-nil (success-count scored) (count scored))
               :confidence_interval (wilson-interval (success-count scored) (count scored))}
     :cost {:eligible_attempts (count eligible)
            :excluded_attempts (- (count scored) (count eligible))
            :exclusion_reasons (cost-exclusion-reasons scored)
            :total_cost_usd (when (seq eligible) cost)
            :successes eligible-successes
            :success_rate (ratio-or-nil eligible-successes (count eligible))
            ;; success-per-cost uses one attempt set for numerator and
            ;; denominator: an attempt excluded from cost is excluded from both.
            :success_per_usd (ratio-or-nil eligible-successes cost)
            :cost_per_success_usd (ratio-or-nil cost eligible-successes)}
     :wall_clock {:attempts (count (keep :wall_clock_ms scored))
                  :mean_ms (mean (keep :wall_clock_ms scored))
                  :median_ms (median (keep :wall_clock_ms scored))}
     :tokens {:grand_total (reduce + 0 (keep :grand_total_tokens scored))
              :input_total (reduce + 0 (keep :input_total_tokens scored))
              :output_total (reduce + 0 (keep :output_total_tokens scored))
              :input_cache_read (reduce + 0 (keep :input_cache_read_tokens scored))}
     :signals {:stale_snapshot_reuse (count (filter :stale_snapshot_reuse attempts))
               :missing_snapshot_evidence (count (filter :missing_snapshot_evidence attempts))
               :excess_context_cost (count (filter :excess_context_cost attempts))
               :policy_violations (count (keep :policy_violation_reason attempts))
               :retrieval_issue_codes (->> attempts
                                           (mapcat :retrieval_issue_codes)
                                           frequencies
                                           (into (sorted-map)))}}))

;; ---------------------------------------------------------------------------
;; Paired comparison
;; ---------------------------------------------------------------------------

(defn- per-task-arm-stats [attempts]
  (let [eligible (cost-eligible (scored-attempts attempts))]
    (when (seq eligible)
      {:attempts (count eligible)
       :successes (success-count eligible)
       :success_rate (ratio-or-nil (success-count eligible) (count eligible))
       :mean_cost_usd (mean (map :cost_usd eligible))
       :mean_wall_clock_ms (mean (keep :wall_clock_ms eligible))})))

(defn paired-comparison
  "Compare a candidate arm against a comparator arm, paired per task.

   Only tasks where both arms produced at least one cost-eligible attempt enter
   the comparison, and each task contributes the mean of its own attempts, so
   unequal seed counts do not weight one task more than another.

   Cost and success are weighted the same way. `success_rate` is the mean of the
   per-task success rates and is what `success_delta_pp` compares, because a
   task with ten seeds must not outweigh a task with one. `attempt_success_rate`
   keeps the unweighted attempt view and carries the confidence interval, whose
   trial count is the attempt count."
  [attempts candidate comparator]
  (let [by-task (group-by (juxt :benchmark_run_id :task_id) attempts)
        pairs (keep (fn [[[run-id task-id] group]]
                      (let [by-arm (group-by :arm group)
                            cand (per-task-arm-stats (get by-arm candidate []))
                            comp (per-task-arm-stats (get by-arm comparator []))]
                        (when (and cand comp)
                          {:benchmark_run_id run-id
                           :task_id task-id
                           :repo_key (:repo_key (first group))
                           :candidate cand
                           :comparator comp})))
                    by-task)
        pairs (vec (sort-by (juxt :benchmark_run_id :task_id) pairs))
        arm-view (fn [role]
                   (let [attempt-count (reduce + 0 (map #(get-in % [role :attempts]) pairs))
                         successes (reduce + 0 (map #(get-in % [role :successes]) pairs))]
                     {:attempts attempt-count
                      :successes successes
                      :tasks (count pairs)
                      :success_rate (mean (keep #(get-in % [role :success_rate]) pairs))
                      :attempt_success_rate (ratio-or-nil successes attempt-count)
                      :confidence_interval (wilson-interval successes attempt-count)
                      :total_cost_usd (reduce + 0.0 (map #(get-in % [role :mean_cost_usd]) pairs))
                      :mean_wall_clock_ms (mean (keep #(get-in % [role :mean_wall_clock_ms])
                                                      pairs))}))
        cand-view (arm-view :candidate)
        comp-view (arm-view :comparator)
        cand-cost (:total_cost_usd cand-view)
        comp-cost (:total_cost_usd comp-view)
        cand-rate (:success_rate cand-view)
        comp-rate (:success_rate comp-view)]
    {:candidate_arm candidate
     :comparator_arm comparator
     :comparable (boolean (seq pairs))
     :tasks_compared (count pairs)
     :repositories_compared (vec (sort (distinct (keep :repo_key pairs))))
     :success_weighting "task_mean"
     :candidate cand-view
     :comparator comp-view
     :cost_reduction_pct (when (pos? comp-cost)
                           (* 100.0 (/ (- comp-cost cand-cost) comp-cost)))
     :cost_ratio (ratio-or-nil comp-cost cand-cost)
     :success_delta_pp (when (and cand-rate comp-rate)
                         (* 100.0 (- cand-rate comp-rate)))
     :success_interval_overlap (intervals-overlap? (:confidence_interval cand-view)
                                                   (:confidence_interval comp-view))
     :wall_clock_ratio (ratio-or-nil (:mean_wall_clock_ms cand-view)
                                     (:mean_wall_clock_ms comp-view))
     :pairs pairs}))

;; ---------------------------------------------------------------------------
;; Pooling, statistical floor, stop rule
;; ---------------------------------------------------------------------------

(defn pooling-check
  "Attempts may only be pooled when their frozen policy identities agree."
  [attempts]
  (let [values (into {} (map (fn [field]
                               [field (vec (sort (distinct (keep #(get (:pooling_key %) field)
                                                                 attempts))))])
                             pooling-fields))
        violations (vec (for [[field vs] values
                              :when (> (count vs) 1)]
                          {:field field :values vs}))]
    {:consistent (empty? violations)
     :values values
     :violations violations}))

(defn- external-repo-keys
  "Repository keys marked external in the frozen suite.

   The attempt payload records `repo_key` but not whether the repository is
   external, so the flag comes from the suite. When the suite cannot be read the
   check reports `unknown` rather than assuming either answer."
  [opts]
  (if-let [provided (:external_repo_keys opts)]
    {:status "provided" :repo_keys (set provided)}
    (let [loaded (try
                   {:suite (or (:suite opts) (suite/load-suite))}
                   (catch Exception e
                     {:error (str (.getMessage e))}))]
      (if-let [suite (:suite loaded)]
        {:status "suite"
         :repo_keys (set (keep (fn [repository]
                                 (when (:external repository) (:repo_key repository)))
                               (:repositories suite)))}
        {:status "unknown" :repo_keys #{} :error (:error loaded)}))))

(defn statistical-floor
  "Suite-size floor from SPEC.md 5.1: at least `min_tasks` distinct tasks and at
   least one external repository in the compared set."
  [comparison threshold opts]
  (let [tasks (:tasks_compared comparison)
        repos (set (:repositories_compared comparison))
        {:keys [status repo_keys error]} (external-repo-keys opts)
        external (vec (sort (filter repo_keys repos)))
        external-known? (not= "unknown" status)]
    {:tasks_compared tasks
     :min_tasks (:min_tasks threshold)
     :tasks_floor_met (>= tasks (:min_tasks threshold))
     :repositories_compared (vec (sort repos))
     :external_repository_source status
     :external_repository_lookup_error error
     :external_repositories_compared external
     :external_repository_present (when external-known? (boolean (seq external)))
     :external_repository_requirement_met (if (:requires_external_repository threshold)
                                            (and external-known? (boolean (seq external)))
                                            true)
     :success_interval_overlap (:success_interval_overlap comparison)}))

(defn- check [name observed required passed]
  {:check name :observed observed :required required :passed passed})

(defn stop-rule-decision
  "Apply the Stage 0 stop rule.

   Two conditions block a verdict outright: an unlocked threshold (the Stage 0
   calibration pilot and final lock are a separate gate) and inconsistent
   pooling identities. In both cases the observed comparison is still reported,
   labelled as a provisional signal."
  [{:keys [comparison threshold pooling floor]}]
  (let [{:keys [cost_reduction_pct cost_ratio success_delta_pp wall_clock_ratio]} comparison
        cost-check (check "cost_reduction"
                          {:cost_reduction_pct cost_reduction_pct :cost_ratio cost_ratio}
                          {:min_cost_reduction_pct (:min_cost_reduction_pct threshold)
                           :min_cost_ratio (:min_cost_ratio threshold)}
                          (boolean (and cost_reduction_pct cost_ratio
                                        (>= cost_reduction_pct (:min_cost_reduction_pct threshold))
                                        (>= cost_ratio (:min_cost_ratio threshold)))))
        success-check (check "success_parity"
                             {:success_delta_pp success_delta_pp}
                             {:min_success_delta_pp (:min_success_delta_pp threshold)}
                             (boolean (and success_delta_pp
                                           (>= success_delta_pp (:min_success_delta_pp threshold)))))
        wall-check (check "wall_clock_guardrail"
                          {:wall_clock_ratio wall_clock_ratio}
                          {:max_wall_clock_ratio (:max_wall_clock_ratio threshold)}
                          (boolean (and wall_clock_ratio
                                        (<= wall_clock_ratio (:max_wall_clock_ratio threshold)))))
        floor-check (check "statistical_floor"
                           {:tasks_compared (:tasks_compared floor)
                            :external_repository_present (:external_repository_present floor)}
                           {:min_tasks (:min_tasks floor)
                            :requires_external_repository (:requires_external_repository threshold)}
                           (boolean (and (:tasks_floor_met floor)
                                         (:external_repository_requirement_met floor))))
        pooling-check* (check "pooling_consistency"
                              {:violations (:violations pooling)}
                              {:consistent true}
                              (boolean (:consistent pooling)))
        checks [cost-check success-check wall-check floor-check pooling-check*]
        comparable? (and (:comparable comparison) (:consistent pooling))
        all-passed? (every? :passed checks)
        ;; SPEC 5.1 defines the failure signal on cost and success only. A
        ;; missing floor or a wall-clock guardrail breach is not evidence for
        ;; the kill criterion, so it yields `indeterminate` instead.
        failure? (and comparable?
                      (or (not (:passed cost-check)) (not (:passed success-check))))
        signal (cond
                 (not comparable?) "indeterminate"
                 all-passed? "success"
                 failure? "failure"
                 :else "indeterminate")
        locked? (boolean (:locked threshold))
        blockers (cond-> []
                   (not locked?) (conj "stage_0_calibration_pilot_and_threshold_lock_pending")
                   (not (:consistent pooling)) (conj "pooling_identities_inconsistent")
                   (not (:comparable comparison)) (conj "no_paired_cost_eligible_tasks"))]
    {:threshold threshold
     :threshold_locked locked?
     :checks checks
     :provisional_signal signal
     :verdict (if (and locked? comparable?) signal "pending_threshold_lock")
     :verdict_blockers blockers}))

;; ---------------------------------------------------------------------------
;; semidx-internal cost diagnostics
;; ---------------------------------------------------------------------------

(defn semidx-internal-tokens
  "Per-arm semidx packet cost, joined from `semantic_usage_events`.

   The join is `session_id = benchmark_run_id` and `task_id = task_attempt_id`,
   which is how the harness tags every semidx call of an attempt. Stage 1
   semantics are preserved: the selection stage has no measured return, so its
   cost is `estimated_tokens`, while expand and detail contribute their measured
   `returned_tokens`. This is a diagnostic for where an arm's cost went; the
   scored denominator remains the agent's `cost_usd`."
  [events attempts]
  (let [arm-by-key (into {} (map (juxt (juxt :benchmark_run_id :task_attempt_id) :arm)
                                 attempts))
        rows (keep (fn [event]
                     (when-let [arm (get arm-by-key [(:session_id event) (:task_id event)])]
                       {:arm arm
                        :stage (stage-name event)
                        :estimated (get-in event [:payload :estimated_tokens])
                        :returned (get-in event [:payload :returned_tokens])}))
                   events)]
    (->> (group-by :arm rows)
         (map (fn [[arm group]]
                (let [by-stage (group-by :stage group)
                      selection (get by-stage "selection" [])
                      expand (get by-stage "expand" [])
                      detail (get by-stage "detail" [])
                      sum (fn [entries field] (reduce + 0 (keep field entries)))
                      selection-tokens (sum selection :estimated)
                      expand-tokens (sum expand :returned)
                      detail-tokens (sum detail :returned)]
                  {:arm arm
                   :events (count group)
                   :selection {:events (count selection)
                               :estimated_tokens selection-tokens}
                   :expand {:events (count expand)
                            :returned_tokens expand-tokens}
                   :detail {:events (count detail)
                            :returned_tokens detail-tokens}
                   :packet_tokens (+ selection-tokens expand-tokens detail-tokens)})))
         (sort-by :arm)
         vec)))

;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn- provenance [attempts pooling]
  {:benchmark_runs (->> attempts
                        (group-by :benchmark_run_id)
                        (map (fn [[run-id group]]
                               {:benchmark_run_id run-id
                                :repo_key (:repo_key (first group))
                                :repo_revision (:repo_revision (first group))
                                :dirty_state (:dirty_state (first group))
                                :attempts (count group)
                                :tasks (count (distinct (map :task_id group)))}))
                        (sort-by :benchmark_run_id)
                        vec)
   :pooling_identities (:values pooling)})

(defn- select-attempts [attempts opts]
  (let [run-ids (some-> (:benchmark_run_ids opts) set)
        arms (some-> (:arms opts) set)]
    (cond->> attempts
      run-ids (filter #(contains? run-ids (:benchmark_run_id %)))
      arms (filter #(contains? arms (:arm %)))
      true vec)))

(defn report-from-records
  "Build the Stage 3 benchmark report from raw feedback and event records.

   This is the sink-independent core: the in-memory path, the PostgreSQL path,
   and an offline export all reduce to the same function."
  [{:keys [feedback events opts] :or {opts {}}}]
  (let [{:keys [attempts duplicate_attempt_records unusable_records]}
        (attempts-from-records feedback)
        attempts (select-attempts attempts opts)
        threshold (merge provisional-threshold (:threshold opts))
        pooling (pooling-check attempts)
        by-arm (group-by :arm attempts)
        arm-order (vec (sort (distinct (concat [candidate-arm comparator-arm]
                                               control-arms
                                               (keys by-arm)))))
        comparison (paired-comparison attempts candidate-arm comparator-arm)
        floor (statistical-floor comparison threshold opts)
        mismatches (filter :usage_totals_mismatch attempts)]
    {:schema_version report-schema-version
     :generated_at (now-iso)
     :scope {:surface attempt-surface
             :operation attempt-operation
             :since (:since opts)
             :tenant_id (:tenant_id opts)
             :benchmark_run_ids (vec (:benchmark_run_ids opts))}
     :inputs {:feedback_records (count feedback)
              :attempts (count attempts)
              :duplicate_attempt_records duplicate_attempt_records
              :records_without_attempt_id unusable_records
              :attempts_with_usage_totals_mismatch (count mismatches)
              :usage_totals_mismatches (vec (map #(select-keys % [:task_attempt_id
                                                                  :usage_totals_mismatch])
                                                 mismatches))
              :events (count events)}
     :provenance (provenance attempts pooling)
     :pooling pooling
     :arms (vec (for [arm arm-order
                      :let [group (get by-arm arm)]
                      :when (seq group)]
                  (arm-summary arm group)))
     :task_arm_rollup (task-arm-rollup attempts)
     :comparison {:primary comparison
                  :controls (vec (for [arm control-arms
                                       :when (seq (get by-arm arm))]
                                   (paired-comparison attempts arm comparator-arm)))}
     :statistical_floor floor
     :stop_rule (stop-rule-decision {:comparison comparison
                                     :threshold threshold
                                     :pooling pooling
                                     :floor (merge floor (select-keys threshold [:min_tasks]))})
     :diagnostics {:semidx_internal_tokens (semidx-internal-tokens events attempts)}}))

(defn benchmark-report
  "Read benchmark attempts and semidx events from a usage-metrics sink and
   aggregate them.

   Works against any sink implementation; PostgreSQL is optional and the
   in-memory sink is a first-class path."
  ([sink] (benchmark-report sink {}))
  ([sink opts]
   (let [filter-opts (select-keys opts [:since :tenant_id :root_path :root_path_hash])
         feedback (usage/sink-feedback sink (merge filter-opts
                                                   {:surface attempt-surface
                                                    :operation attempt-operation}))
         events (usage/sink-events sink (merge filter-opts {:surface attempt-surface}))]
     (report-from-records {:feedback feedback :events events :opts opts}))))
