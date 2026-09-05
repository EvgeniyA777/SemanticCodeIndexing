(ns semidx.runtime.benchmark-report-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.benchmark-harness :as harness]
            [semidx.runtime.benchmark-report :as report]
            [semidx.runtime.benchmark-usage :as bu]
            [semidx.runtime.usage-metrics :as usage]))

;; --------------------------------------------------------------------------
;; Fixtures
;; --------------------------------------------------------------------------

(def gemini-flash
  {:evaluated_provider "google"
   :evaluated_api_surface "generate-content"
   :evaluated_model "gemini-2.5-flash"
   :evaluated_model_revision "gemini-2.5-flash"
   :evaluated_service_tier "on-demand"})

(def unpriced-model
  (assoc gemini-flash
         :evaluated_model "gemini-9.9-ultra"
         :evaluated_model_revision "gemini-9.9-ultra"))

(def historical-model
  {:evaluated_provider "anthropic"
   :evaluated_api_surface "messages"
   :evaluated_model "claude-3-5-sonnet-20240620"
   :evaluated_model_revision "claude-3-5-sonnet-20240620"
   :evaluated_service_tier "on-demand"})

(defn- run-fixture [run-id repo-key]
  {:benchmark_run_id run-id
   :repo_key repo-key
   :repo_revision "rev-1"
   :task_prompt_policy_id harness/task-prompt-policy-id
   :arm_policy_bundle_id harness/arm-policy-bundle-id
   :execution_budget_policy_id harness/execution-budget-policy-id
   :cache_protocol_id harness/cache-protocol-id})

(defn- attempt-fixture
  [{:keys [run task arm seed model] :or {run "run-1" seed 1 arm "A"}}]
  (merge (or model gemini-flash)
         {:benchmark_run_id run
          :task_id task
          :task_attempt_id (str run "|" task "|" arm "|" seed)
          :arm arm
          :arm_policy_id (str "arm_" arm "_v1")
          :seed seed
          :sequence_index 0
          :agent_id "agent-1"
          :agent_build_id "build-1"}))

(defn- matrix-rows [run attempt turn-tokens]
  (vec (map-indexed
        (fn [index tokens]
          (bu/matrix-row {:run run
                          :attempt attempt
                          :turn {:turn_index index
                                 :adapter_id (if (= "anthropic" (:evaluated_provider attempt))
                                               "anthropic-messages"
                                               "gemini-generate-content")
                                 :raw_usage (if (= "anthropic" (:evaluated_provider attempt))
                                              {:input_tokens tokens
                                               :output_tokens (quot tokens 10)}
                                              {:promptTokenCount tokens
                                               :candidatesTokenCount (quot tokens 10)})}}))
        turn-tokens)))

(defn- feedback-fixture
  "Build one recorded benchmark attempt in the shape the harness writes."
  [{:keys [run task arm seed repo outcome turns occurred_at wall_clock_ms
           not_applicable_reason stale excess recorded_totals price_schedule_id]
    :or {run "run-1" arm "A" seed 1 repo "semidx" outcome "success"
         turns [1000] wall_clock_ms 4000 occurred_at "2026-08-27T10:00:00Z"}
    :as spec}]
  (let [run-map (run-fixture run repo)
        attempt (attempt-fixture spec)
        rows (matrix-rows run-map attempt turns)
        derived (bu/aggregate-attempt-usage rows)]
    (usage/normalize-feedback
     {:surface report/attempt-surface
      :operation report/attempt-operation
      :occurred_at occurred_at
      :session_id run
      :task_id (:task_attempt_id attempt)
      :actor_id "agent-1"
      :feedback_outcome outcome
      :payload (merge attempt
                      {:suite_version "benchmark_task_suite_v1"
                       :harness_version harness/harness-version
                       :task_type "symbol_lookup"
                       :arm_policy_bundle_id harness/arm-policy-bundle-id
                       :task_prompt_policy_id harness/task-prompt-policy-id
                       :execution_budget_policy_id harness/execution-budget-policy-id
                       :cache_protocol_id harness/cache-protocol-id
                       :price_schedule_id (or price_schedule_id bu/price-schedule-id)
                       :repo_key repo
                       :repo_revision "rev-1"
                       :dirty_state false
                       :benchmark_outcome outcome
                       :not_applicable_reason not_applicable_reason
                       :stale_snapshot_reuse (boolean stale)
                       :excess_context_cost (boolean excess)
                       :wall_clock_ms wall_clock_ms
                       :usage_matrix rows
                       :usage_totals (or recorded_totals derived)})})))

(defn- report-for [records & [opts]]
  (report/report-from-records {:feedback records
                               :events []
                               :opts (merge {:external_repo_keys ["aegis-zig"]} opts)}))

(defn- arm-entry [report arm]
  (first (filter #(= arm (:arm %)) (:arms report))))

;; A/B pair where A is materially cheaper and equally successful.
(defn- cheap-vs-expensive-records []
  (for [task ["task_1" "task_2"]
        [arm turns wall] [["A" [800] 3000] ["B" [6000] 4000]]]
    (feedback-fixture {:task task :arm arm :turns turns :wall_clock_ms wall
                       :outcome "success"})))

;; --------------------------------------------------------------------------
;; Attempt-first aggregation
;; --------------------------------------------------------------------------

(deftest attempts-are-keyed-by-task-attempt-id-test
  (let [records [(feedback-fixture {:task "task_1" :arm "A" :seed 1})
                 (feedback-fixture {:task "task_1" :arm "A" :seed 2})]
        {:keys [attempts duplicate_attempt_records]} (report/attempts-from-records records)]
    (is (= 2 (count attempts)) "two seeds of one task are two attempts")
    (is (= 2 (count (distinct (map :task_attempt_id attempts)))))
    (is (zero? duplicate_attempt_records))))

(deftest repeated-records-for-one-attempt-collapse-to-the-latest-test
  (let [records [(feedback-fixture {:task "task_1" :arm "A" :seed 1
                                    :outcome "failure"
                                    :occurred_at "2026-08-27T10:00:00Z"})
                 (feedback-fixture {:task "task_1" :arm "A" :seed 1
                                    :outcome "success"
                                    :occurred_at "2026-08-27T11:00:00Z"})]
        {:keys [attempts duplicate_attempt_records]} (report/attempts-from-records records)]
    (is (= 1 (count attempts)) "one attempt recorded twice is one observation")
    (is (= "success" (:outcome (first attempts))))
    (is (= 1 duplicate_attempt_records))))

(deftest records-without-an-attempt-id-are-rejected-test
  (let [record (assoc (feedback-fixture {:task "task_1"})
                      :task_id nil
                      :payload (dissoc (:payload (feedback-fixture {:task "task_1"}))
                                       :task_attempt_id))
        {:keys [attempts unusable_records]} (report/attempts-from-records [record])]
    (is (empty? attempts))
    (is (= 1 unusable_records))))

(deftest repeated-runs-of-one-task-do-not-collapse-in-the-rollup-test
  (let [records [(feedback-fixture {:run "run-1" :task "task_1" :arm "A"})
                 (feedback-fixture {:run "run-2" :task "task_1" :arm "A"})]
        rollup (:task_arm_rollup (report-for records))]
    (is (= 2 (count rollup)))
    (is (= #{"run-1" "run-2"} (set (map :benchmark_run_id rollup))))
    (is (every? #(= 1 (:attempts %)) rollup))))

;; --------------------------------------------------------------------------
;; Cost derivation and eligibility
;; --------------------------------------------------------------------------

(deftest cost-is-derived-from-the-matrix-not-from-recorded-totals-test
  (let [record (feedback-fixture {:task "task_1" :arm "A" :turns [1000]
                                  :recorded_totals {:cost_usd 999.0
                                                    :grand_total 1
                                                    :pricing_status "resolved"}})
        report (report-for [record])
        attempt (first (:attempts (report/attempts-from-records [record])))]
    (is (< (:cost_usd attempt) 1.0) "the recorded total must not be trusted")
    (is (pos? (:cost_usd attempt)))
    (is (contains? (set (:usage_totals_mismatch attempt)) :cost_usd))
    (is (= 1 (get-in report [:inputs :attempts_with_usage_totals_mismatch])))))

(deftest unpriced-attempts-are-excluded-from-cost-not-priced-at-zero-test
  (let [record (feedback-fixture {:task "task_1" :arm "A" :model unpriced-model})
        report (report-for [record])
        arm-a (arm-entry report "A")]
    (is (= "unresolved" (:pricing_status (first (:attempts (report/attempts-from-records [record]))))))
    (is (zero? (get-in arm-a [:cost :eligible_attempts])))
    (is (= 1 (get-in arm-a [:cost :excluded_attempts])))
    (is (nil? (get-in arm-a [:cost :total_cost_usd]))
        "an excluded attempt must not contribute a zero cost")
    (is (= {"unresolved" 1} (into {} (get-in arm-a [:cost :exclusion_reasons]))))))

(deftest historical-only-attempts-are-excluded-from-the-cost-verdict-test
  (let [record (feedback-fixture {:task "task_1" :arm "A" :model historical-model})
        arm-a (arm-entry (report-for [record]) "A")]
    (is (= {"historical_only" 1} (into {} (get-in arm-a [:cost :exclusion_reasons]))))
    (is (zero? (get-in arm-a [:cost :eligible_attempts])))))

(deftest attempts-without-turns-cannot-enter-the-cost-verdict-test
  (let [record (feedback-fixture {:task "task_1" :arm "B" :outcome "error" :turns []})
        arm-b (arm-entry (report-for [record]) "B")]
    (is (= 1 (get-in arm-b [:outcomes :error])))
    (is (zero? (get-in arm-b [:cost :eligible_attempts])))))

;; --------------------------------------------------------------------------
;; Per-arm summary
;; --------------------------------------------------------------------------

(deftest arm-summary-reports-success-per-cost-test
  (let [records [(feedback-fixture {:task "task_1" :arm "A" :outcome "success"})
                 (feedback-fixture {:task "task_2" :arm "A" :outcome "failure"})]
        arm-a (arm-entry (report-for records) "A")
        cost (get-in arm-a [:cost :total_cost_usd])]
    (is (= 2 (get-in arm-a [:success :scored_attempts])))
    (is (= 0.5 (get-in arm-a [:success :success_rate])))
    (is (some? (get-in arm-a [:success :confidence_interval])))
    (is (= 2 (get-in arm-a [:cost :eligible_attempts])))
    (is (< (Math/abs (- (get-in arm-a [:cost :success_per_usd]) (/ 1.0 cost))) 1e-9))
    (is (< (Math/abs (- (get-in arm-a [:cost :cost_per_success_usd]) cost)) 1e-9))))

(deftest not-applicable-attempts-leave-the-success-denominator-test
  (let [records [(feedback-fixture {:task "task_1" :arm "C" :outcome "success"})
                 (feedback-fixture {:task "task_2" :arm "C" :outcome "not_applicable"
                                    :not_applicable_reason "no lsp for zig"})]
        arm-c (arm-entry (report-for records) "C")]
    (is (= 1 (get-in arm-c [:success :scored_attempts])))
    (is (= 1.0 (get-in arm-c [:success :success_rate])))
    (is (= 1 (get-in arm-c [:not_applicable :attempts])))
    (is (= ["no lsp for zig"] (get-in arm-c [:not_applicable :reasons])))
    (is (= "diagnostic_control" (:verdict_role arm-c)))))

(deftest arm-signals-preserve-negative-utility-observations-test
  (let [records [(feedback-fixture {:task "task_1" :arm "A" :outcome "failure" :stale true})
                 (feedback-fixture {:task "task_2" :arm "A" :outcome "success" :excess true})]
        arm-a (arm-entry (report-for records) "A")]
    (is (= 1 (get-in arm-a [:signals :stale_snapshot_reuse])))
    (is (= 1 (get-in arm-a [:signals :excess_context_cost])))))

;; --------------------------------------------------------------------------
;; Paired comparison
;; --------------------------------------------------------------------------

(deftest comparison-is-paired-per-task-test
  (let [records (concat (cheap-vs-expensive-records)
                        ;; A-only task: must not enter the comparison.
                        [(feedback-fixture {:task "task_3" :arm "A" :turns [10]})])
        primary (get-in (report-for records) [:comparison :primary])]
    (is (= 2 (:tasks_compared primary)))
    (is (:comparable primary))
    (is (pos? (:cost_reduction_pct primary)))
    (is (> (:cost_ratio primary) 2.0))
    (is (= 0.0 (:success_delta_pp primary)))
    (is (< (:wall_clock_ratio primary) 1.5))))

(deftest comparison-is-not-comparable-without-a-shared-task-test
  (let [records [(feedback-fixture {:task "task_1" :arm "A"})
                 (feedback-fixture {:task "task_2" :arm "B"})]
        primary (get-in (report-for records) [:comparison :primary])]
    (is (not (:comparable primary)))
    (is (zero? (:tasks_compared primary)))))

(deftest controls-are-reported-separately-from-the-primary-comparator-test
  (let [records (concat (cheap-vs-expensive-records)
                        [(feedback-fixture {:task "task_1" :arm "D" :turns [4000]})])
        report (report-for records)]
    (is (= "B" (get-in report [:comparison :primary :comparator_arm])))
    (is (= ["D"] (mapv :candidate_arm (get-in report [:comparison :controls]))))
    (is (= "ecological_control" (:verdict_role (arm-entry report "D"))))))

;; --------------------------------------------------------------------------
;; Stop rule
;; --------------------------------------------------------------------------

(deftest verdict-is-withheld-while-the-threshold-lock-is-pending-test
  (let [stop (:stop_rule (report-for (cheap-vs-expensive-records)
                                     {:threshold {:min_tasks 2
                                                  :requires_external_repository false}}))]
    (is (false? (:threshold_locked stop)))
    (is (= "success" (:provisional_signal stop)))
    (is (= "pending_threshold_lock" (:verdict stop))
        "a verdict must not be emitted before the Stage 0 lock")
    (is (some #{"stage_0_calibration_pilot_and_threshold_lock_pending"}
              (:verdict_blockers stop)))))

(deftest a-locked-threshold-produces-a-verdict-test
  (let [stop (:stop_rule (report-for (cheap-vs-expensive-records)
                                     {:threshold {:locked true
                                                  :threshold_id "pilot_locked_v1"
                                                  :min_tasks 2
                                                  :requires_external_repository false}}))]
    (is (true? (:threshold_locked stop)))
    (is (= "success" (:verdict stop)))
    (is (empty? (:verdict_blockers stop)))))

(deftest failing-the-cost-gate-yields-the-failure-signal-test
  (let [records (for [task ["task_1" "task_2"]
                      [arm turns] [["A" [5000]] ["B" [6000]]]]
                  (feedback-fixture {:task task :arm arm :turns turns :outcome "success"}))
        stop (:stop_rule (report-for records {:threshold {:locked true
                                                          :min_tasks 2
                                                          :requires_external_repository false}}))
        cost-check (first (filter #(= "cost_reduction" (:check %)) (:checks stop)))]
    (is (false? (:passed cost-check)))
    (is (= "failure" (:verdict stop)))))

(deftest an-unmet-statistical-floor-is-indeterminate-not-a-failure-test
  (let [stop (:stop_rule (report-for (cheap-vs-expensive-records)
                                     {:threshold {:locked true}}))
        floor-check (first (filter #(= "statistical_floor" (:check %)) (:checks stop)))]
    (is (false? (:passed floor-check)))
    (is (= "indeterminate" (:verdict stop))
        "a small suite must not trigger the kill criterion")))

(deftest statistical-floor-requires-an-external-repository-test
  (let [floor (:statistical_floor (report-for (cheap-vs-expensive-records)))]
    (is (= 2 (:tasks_compared floor)))
    (is (false? (:tasks_floor_met floor)))
    (is (= ["semidx"] (:repositories_compared floor)))
    (is (false? (:external_repository_present floor)))
    (is (= "provided" (:external_repository_source floor)))))

(deftest external-repository-presence-is-recognised-test
  (let [records (concat (cheap-vs-expensive-records)
                        (for [[arm turns] [["A" [800]] ["B" [6000]]]]
                          (feedback-fixture {:task "zig_task" :arm arm :turns turns
                                             :repo "aegis-zig"})))
        floor (:statistical_floor (report-for records))]
    (is (true? (:external_repository_present floor)))
    (is (= ["aegis-zig"] (:external_repositories_compared floor)))))

(deftest inconsistent-pooling-identities-block-the-verdict-test
  (let [records (concat (cheap-vs-expensive-records)
                        [(feedback-fixture {:task "task_1" :arm "A" :seed 9
                                            :price_schedule_id "2027-01-01-eligible-v2"})])
        report (report-for records {:threshold {:locked true
                                                :min_tasks 1
                                                :requires_external_repository false}})
        stop (:stop_rule report)]
    (is (false? (get-in report [:pooling :consistent])))
    (is (= [:price_schedule_id] (mapv :field (get-in report [:pooling :violations]))))
    (is (= "indeterminate" (:provisional_signal stop)))
    (is (= "pending_threshold_lock" (:verdict stop)))
    (is (some #{"pooling_identities_inconsistent"} (:verdict_blockers stop)))))

;; --------------------------------------------------------------------------
;; semidx-internal cost diagnostics
;; --------------------------------------------------------------------------

(deftest internal-token-diagnostic-uses-stage-specific-token-fields-test
  (let [record (feedback-fixture {:task "task_1" :arm "A"})
        attempt-id (get-in record [:payload :task_attempt_id])
        events [(usage/normalize-event {:surface "benchmark"
                                        :operation "resolve_context"
                                        :session_id "run-1"
                                        :task_id attempt-id
                                        :payload {:estimated_tokens 150
                                                  :returned_tokens nil}})
                (usage/normalize-event {:surface "benchmark"
                                        :operation "expand_context"
                                        :session_id "run-1"
                                        :task_id attempt-id
                                        :payload {:estimated_tokens 900
                                                  :returned_tokens 800}})]
        diagnostics (:semidx_internal_tokens
                     (:diagnostics (report/report-from-records
                                    {:feedback [record] :events events :opts {}})))
        arm-a (first (filter #(= "A" (:arm %)) diagnostics))]
    (is (= 150 (get-in arm-a [:selection :estimated_tokens]))
        "the selection stage has no measured return; its cost is the estimate")
    (is (= 800 (get-in arm-a [:expand :returned_tokens]))
        "expand contributes its measured return, not its estimate")
    (is (= 950 (:packet_tokens arm-a)))))

(deftest internal-token-diagnostic-ignores-events-of-other-attempts-test
  (let [record (feedback-fixture {:task "task_1" :arm "A"})
        events [(usage/normalize-event {:surface "benchmark"
                                        :operation "resolve_context"
                                        :session_id "run-1"
                                        :task_id "unrelated-attempt"
                                        :payload {:estimated_tokens 5000}})]
        diagnostics (:semidx_internal_tokens
                     (:diagnostics (report/report-from-records
                                    {:feedback [record] :events events :opts {}})))]
    (is (empty? diagnostics))))

;; --------------------------------------------------------------------------
;; Sink paths
;; --------------------------------------------------------------------------

(def sample-run
  {:benchmark_run_id "e2e-run-1"
   :suite_version "benchmark_task_suite_v1"
   :repo_key "semidx"
   :repo_path "."
   :repo_revision "rev-e2e"
   :dirty_state false
   :task_prompt_policy_id harness/task-prompt-policy-id
   :arm_policy_bundle_id harness/arm-policy-bundle-id
   :execution_budget_policy_id harness/execution-budget-policy-id
   :cache_protocol_id harness/cache-protocol-id
   :harness_version harness/harness-version})

(defn- e2e-task [task-id]
  {:task_id task-id
   :task_type "symbol_lookup"
   :repo_key "semidx"
   :arms ["A" "B"]
   :prompt "Where is the selection produced?"
   :ground_truth {:required_paths ["src/semidx/core.clj"]
                  :required_symbols ["resolve-context"]}})

(def e2e-answer
  {:paths ["src/semidx/core.clj"]
   :symbols ["resolve-context"]
   :answer_text "resolve-context in src/semidx/core.clj"
   :confidence_level "high"})

(defn- e2e-runner []
  (harness/scripted-arm-runner
   {"A" {:outcome "success"
         :answer e2e-answer
         :turns [{:turn_index 0
                  :adapter_id "gemini-generate-content"
                  :raw_usage {:promptTokenCount 900 :candidatesTokenCount 100}}]
         :tool_calls [{:tool_id "resolve_context"}]
         :wall_clock_ms 3000}
    "B" {:outcome "success"
         :answer e2e-answer
         :turns [{:turn_index 0
                  :adapter_id "gemini-generate-content"
                  :raw_usage {:promptTokenCount 8000 :candidatesTokenCount 400}}]
         :tool_calls [{:tool_id "grep_search"}]
         :wall_clock_ms 3600}}))

(defn- e2e-sink []
  (let [sink (usage/in-memory-usage-metrics)]
    (doseq [task-id ["e2e_task_1" "e2e_task_2"]]
      (harness/run-task! {:run sample-run
                          :task (e2e-task task-id)
                          :runner (e2e-runner)
                          :sink sink
                          :seed 5
                          :evaluated gemini-flash
                          :agent {:agent_id "agent-1" :agent_build_id "build-1"}}))
    sink))

(deftest report-runs-against-the-in-memory-sink-without-postgres-test
  (let [report (report/benchmark-report (e2e-sink) {:external_repo_keys ["aegis-zig"]})
        primary (get-in report [:comparison :primary])]
    (is (= 4 (get-in report [:inputs :attempts])))
    (is (zero? (get-in report [:inputs :attempts_with_usage_totals_mismatch]))
        "the harness totals must agree with the aggregator derivation")
    (is (= #{"A" "B"} (set (map :arm (:arms report)))))
    (is (= 2 (:tasks_compared primary)))
    (is (> (:cost_ratio primary) 2.0))
    (is (true? (get-in report [:pooling :consistent])))
    (is (= "pending_threshold_lock" (get-in report [:stop_rule :verdict])))
    (testing "the report is serializable for the evaluation command"
      (is (string? (json/write-str report :escape-slash false))))))

(deftest report-scope-can-select-one-benchmark-run-test
  (let [records [(feedback-fixture {:run "run-1" :task "task_1" :arm "A"})
                 (feedback-fixture {:run "run-2" :task "task_1" :arm "A"})]
        report (report-for records {:benchmark_run_ids ["run-2"]})]
    (is (= 1 (get-in report [:inputs :attempts])))
    (is (= ["run-2"] (mapv :benchmark_run_id (get-in report [:provenance :benchmark_runs]))))))

(deftest postgres-benchmark-payload-survives-the-jsonb-round-trip-test
  (if-let [jdbc-url (System/getenv "SEMIDX_TEST_POSTGRES_URL")]
    (let [sink (usage/postgres-usage-metrics {:jdbc-url jdbc-url})
          run-id (str "pg-run-" (System/currentTimeMillis))
          records (for [task ["pg_task_1" "pg_task_2"]
                        [arm turns] [["A" [800]] ["B" [6000]]]]
                    (feedback-fixture {:run run-id :task task :arm arm :turns turns
                                       :outcome "success"}))
          in-memory (report/report-from-records {:feedback records :events []
                                                 :opts {:external_repo_keys ["aegis-zig"]}})]
      (usage/init-usage-metrics! sink)
      (doseq [record records]
        (is (true? (usage/record-feedback! sink record))))
      (let [round-tripped (report/benchmark-report sink {:benchmark_run_ids [run-id]
                                                         :external_repo_keys ["aegis-zig"]})]
        (is (= 4 (get-in round-tripped [:inputs :attempts])))
        (is (zero? (get-in round-tripped [:inputs :attempts_with_usage_totals_mismatch]))
            "the usage matrix must survive the jsonb round trip")
        (is (= (get-in in-memory [:comparison :primary :cost_ratio])
               (get-in round-tripped [:comparison :primary :cost_ratio])))
        (is (= (get-in in-memory [:comparison :primary :tasks_compared])
               (get-in round-tripped [:comparison :primary :tasks_compared])))))
    (is true "SEMIDX_TEST_POSTGRES_URL is not set; skipping postgres benchmark payload round-trip test.")))

;; --------------------------------------------------------------------------
;; Success weighting regression (review finding, 2026-08-28)
;; --------------------------------------------------------------------------

(deftest paired-success-is-weighted-by-task-not-by-attempt-count-test
  (let [records (concat
                 ;; task_1: ten A attempts, all successful; one B attempt.
                 (for [seed (range 10)]
                   (feedback-fixture {:task "task_1" :arm "A" :seed seed :turns [800]
                                      :outcome "success"}))
                 [(feedback-fixture {:task "task_1" :arm "B" :seed 0 :turns [6000]
                                     :outcome "success"})]
                 ;; task_2: one failed A attempt against a successful B attempt.
                 [(feedback-fixture {:task "task_2" :arm "A" :seed 0 :turns [800]
                                     :outcome "failure"})
                  (feedback-fixture {:task "task_2" :arm "B" :seed 0 :turns [6000]
                                     :outcome "success"})])
        primary (get-in (report-for records) [:comparison :primary])]
    (is (= 2 (:tasks_compared primary)))
    (is (= "task_mean" (:success_weighting primary)))
    (is (= 0.5 (get-in primary [:candidate :success_rate]))
        "a ten-seed task must not outweigh a one-seed task")
    (is (= 1.0 (get-in primary [:comparator :success_rate])))
    (is (= -50.0 (:success_delta_pp primary))
        "success must be weighted like cost, per task")
    (testing "the unweighted attempt view stays available"
      (is (< (Math/abs (- (get-in primary [:candidate :attempt_success_rate]) (/ 10.0 11.0)))
             1e-9))
      (is (= 11 (get-in primary [:candidate :attempts])))
      (is (some? (get-in primary [:candidate :confidence_interval]))))))

(deftest missing-snapshot-evidence-is-reported-per-arm-test
  (let [record (assoc-in (feedback-fixture {:task "task_1" :arm "A" :outcome "failure"})
                         [:payload :missing_snapshot_evidence] true)
        arm-a (arm-entry (report-for [record]) "A")]
    (is (= 1 (get-in arm-a [:signals :missing_snapshot_evidence])))
    (is (zero? (get-in arm-a [:signals :stale_snapshot_reuse])))))
