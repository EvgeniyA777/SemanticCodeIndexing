(ns semidx.runtime.benchmark-harness-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [semidx.contracts.schemas :as schemas]
            [semidx.runtime.benchmark-harness :as harness]
            [semidx.runtime.benchmark-suite :as bs]
            [semidx.runtime.usage-metrics :as usage])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def sample-run
  {:benchmark_run_id "run-1"
   :suite_version "benchmark_task_suite_v1"
   :repo_key "semidx"
   :repo_path "."
   :repo_revision "abc123"
   :dirty_state false
   :task_prompt_policy_id harness/task-prompt-policy-id
   :arm_policy_bundle_id harness/arm-policy-bundle-id
   :execution_budget_policy_id harness/execution-budget-policy-id
   :cache_protocol_id harness/cache-protocol-id
   :harness_version harness/harness-version})

(def sample-task
  {:task_id "sample_task_v1"
   :task_type "symbol_lookup"
   :repo_key "semidx"
   :arms ["A" "B" "C" "D"]
   :prompt "Where is the selection produced?"
   :ground_truth {:required_paths ["src/semidx/core.clj"]
                  :required_symbols ["resolve-context"]}})

(def evaluated
  {:evaluated_provider "google"
   :evaluated_api_surface "generate-content"
   :evaluated_model "gemini-2.5-flash"
   :evaluated_model_revision "gemini-2.5-flash"
   :evaluated_service_tier "on-demand"})

(def sample-agent {:agent_id "agent-1" :agent_build_id "build-1"})

(defn- attempt-for [arm]
  (harness/new-task-attempt sample-run sample-task arm
                            (merge evaluated sample-agent {:seed 7 :sequence_index 0})))

(defn- turn [index]
  {:turn_index index
   :adapter_id "gemini-generate-content"
   :raw_usage {:promptTokenCount 900 :candidatesTokenCount 120}})

(def good-answer
  {:paths ["src/semidx/core.clj"]
   :symbols ["resolve-context"]
   :answer_text "resolve-context in src/semidx/core.clj"
   :confidence_level "high"})

;; --------------------------------------------------------------------------
;; Identity and controls
;; --------------------------------------------------------------------------

(deftest arm-order-is-seeded-and-total-test
  (is (= (harness/arm-order ["A" "B" "C" "D"] 7)
         (harness/arm-order ["A" "B" "C" "D"] 7)))
  (is (= #{"A" "B" "C" "D"} (set (harness/arm-order ["A" "B" "C" "D"] 7))))
  (is (some (fn [seed] (not= ["A" "B" "C" "D"] (harness/arm-order ["A" "B" "C" "D"] seed)))
            (range 20))
      "arm order must actually vary across seeds"))

(deftest attempt-tagging-carries-run-and-attempt-identity-test
  (let [attempt (attempt-for "A")
        usage-context (harness/attempt-usage-context sample-run attempt)
        trace (harness/attempt-trace sample-run attempt)]
    (is (= "benchmark" (:surface usage-context)))
    (is (= "run-1" (:session_id usage-context)))
    (is (= (:task_attempt_id attempt) (:task_id usage-context)))
    (is (= "run-1" (:session_id trace)))
    (is (= (:task_attempt_id attempt) (:task_id trace)))
    (is (some? (:trace_id trace)))))

(deftest attempt-trace-satisfies-the-retrieval-query-contract-test
  (let [trace (harness/attempt-trace sample-run (attempt-for "A"))]
    (is (nil? (m/explain schemas/trace-ref trace))
        "an Arm A query carrying this trace must pass query validation")))

(deftest new-task-attempt-rejects-unknown-arm-test
  (is (thrown? clojure.lang.ExceptionInfo
               (harness/new-task-attempt sample-run sample-task "Z" {:seed 1}))))

(deftest attempt-context-is-arm-symmetric-test
  (let [context-a (harness/attempt-context {:run sample-run :task sample-task
                                            :attempt (attempt-for "A")
                                            :workspace {:workspace_path "."}})
        context-b (harness/attempt-context {:run sample-run :task sample-task
                                            :attempt (attempt-for "B")
                                            :workspace {:workspace_path "."}})]
    (is (= (:prompt context-a) (:prompt context-b))
        "arms must receive the same task wording")
    (is (str/includes? (:prompt context-a) (:prompt sample-task)))
    (is (not= (:allowed_tools context-a) (:allowed_tools context-b)))
    (is (= ["expand_context" "fetch_context_detail" "resolve_context"]
           (:allowed_tools context-a)))))

;; --------------------------------------------------------------------------
;; Arm policy audit
;; --------------------------------------------------------------------------

(deftest audit-passes-a-compliant-attempt-test
  (is (nil? (harness/audit-tool-calls "B" [{:tool_id "grep_search"} {:tool_id "view_file"}])))
  (is (nil? (harness/audit-tool-calls "D" [{:tool_id "bash" :command "rg ControlledRuntime src"}]))))

(deftest audit-rejects-tools-outside-the-arm-allowlist-test
  (let [violation (harness/audit-tool-calls "A" [{:tool_id "resolve_context"} {:tool_id "bash"}])]
    (is (= "arm_tool_policy_violation" (:reason violation)))
    (is (= ["bash"] (:forbidden_tool_ids violation)))))

(deftest audit-rejects-arm-d-semantic-navigation-commands-test
  (let [violation (harness/audit-tool-calls
                   "D" [{:tool_id "bash" :command "rg foo | semidx resolve --intent bar"}])]
    (is (= "arm_d_forbidden_tool_violation" (:reason violation)))
    (is (= 1 (count (:denied_commands violation)))))
  (testing "the denylist matches command prefixes, not arbitrary substrings"
    (is (nil? (harness/audit-tool-calls
               "D" [{:tool_id "bash" :command "rg 'semidx' docs/code-context.md"}])))))

(deftest budget-violations-are-detected-test
  (is (nil? (harness/budget-violation {:wall_clock_ms 1000 :tool_calls [{:tool_id "bash"}]})))
  (is (= "max_wall_clock_seconds"
         (:limit (harness/budget-violation {:wall_clock_ms 301000 :tool_calls []}))))
  (is (= "max_tool_calls"
         (:limit (harness/budget-violation {:wall_clock_ms 1000
                                            :tool_calls (repeat 31 {:tool_id "bash"})})))))

;; --------------------------------------------------------------------------
;; Scoring
;; --------------------------------------------------------------------------

(deftest scoring-accepts-a-complete-answer-test
  (let [scoring (harness/score-answer sample-task good-answer {})]
    (is (= "success" (:outcome scoring)))
    (is (false? (:false_negative scoring)))
    (is (empty? (:retrieval_issue_codes scoring)))))

(deftest scoring-records-missing-facts-as-false-negatives-test
  (let [task (assoc-in sample-task [:ground_truth :required_facts] ["capability_workers"])
        scoring (harness/score-answer task good-answer {})]
    (is (= "failure" (:outcome scoring)))
    (is (true? (:false_negative scoring)))
    (is (= ["capability_workers"] (get-in scoring [:missing :facts])))
    (is (= ["missing_required_fact"] (:retrieval_issue_codes scoring)))))

(deftest scoring-fails-an-unrelated-seed-selection-test
  (let [task (assoc-in sample-task [:ground_truth :forbidden_path_prefixes] ["src/adapters/"])
        scoring (harness/score-answer
                 task
                 (update good-answer :paths conj "src/adapters/ollama.zig")
                 {})]
    (is (= "failure" (:outcome scoring)))
    (is (= ["src/adapters/ollama.zig"] (:forbidden_paths_hit scoring)))
    (is (some #{"unrelated_seed_selection"} (:retrieval_issue_codes scoring)))))

(deftest scoring-separates-stale-snapshot-reuse-test
  (let [task (assoc sample-task :freshness_check {:require_post_mutation_snapshot true})
        scoring (harness/score-answer task
                                      (assoc good-answer :snapshot_id "old-snapshot")
                                      {:current_snapshot_id "new-snapshot"})]
    (is (= "failure" (:outcome scoring)))
    (is (true? (:stale_snapshot_reuse scoring)))
    (is (some #{"stale_snapshot_reuse"} (:retrieval_issue_codes scoring)))
    (is (false? (:false_negative scoring))
        "stale snapshot reuse must stay separate from ranking quality")))

(deftest scoring-records-excess-context-without-failing-a-correct-answer-test
  (let [task (assoc sample-task :cost_ceiling {:max_returned_tokens 100})
        scoring (harness/score-answer task (assoc good-answer :context_tokens 4000) {})]
    (is (= "success" (:outcome scoring)))
    (is (true? (:excess_context_cost scoring)))
    (is (= ["excess_context_cost"] (:retrieval_issue_codes scoring)))))

;; --------------------------------------------------------------------------
;; Attempt execution and write-back
;; --------------------------------------------------------------------------

(defn- run-one [arm response]
  (let [sink (usage/in-memory-usage-metrics)
        completed (harness/run-attempt!
                   {:run sample-run
                    :task sample-task
                    :attempt (attempt-for arm)
                    :runner (harness/scripted-arm-runner {arm response})
                    :sink sink
                    :workspace {:workspace_path "." :isolated false}})]
    {:attempt completed
     :feedback (first (usage/emitted-feedback sink))}))

(deftest successful-attempt-is-recorded-with-its-usage-matrix-test
  (let [{:keys [attempt feedback]}
        (run-one "A" {:outcome "success"
                      :answer good-answer
                      :turns [(turn 0) (turn 1)]
                      :tool_calls [{:tool_id "resolve_context"}]
                      :wall_clock_ms 4200})]
    (is (= "success" (:outcome attempt)))
    (is (= 2 (count (:usage_matrix attempt))))
    (is (= "resolved" (get-in attempt [:usage_totals :pricing_status])))
    (is (pos? (get-in attempt [:usage_totals :cost_usd])))
    (testing "the outcome reaches the feedback sink keyed by task_attempt_id"
      (is (= "benchmark" (:surface feedback)))
      (is (= "benchmark_attempt" (:operation feedback)))
      (is (= (:task_attempt_id attempt) (:task_id feedback)))
      (is (= "run-1" (:session_id feedback)))
      (is (= "success" (:feedback_outcome feedback)))
      (is (= ["src/semidx/core.clj"] (:ground_truth_paths feedback))))
    (testing "the response/usage matrix travels in the feedback payload"
      (is (= 2 (count (get-in feedback [:payload :usage_matrix]))))
      (is (= {:promptTokenCount 900 :candidatesTokenCount 120}
             (get-in feedback [:payload :usage_matrix 0 :raw_usage])))
      (is (= "sample_task_v1" (get-in feedback [:payload :task_id])))
      (is (= (:task_attempt_id attempt) (get-in feedback [:payload :task_attempt_id])))
      (is (= 1 (get-in feedback [:payload :tool_call_count]))))))

(deftest failed-attempt-records-issue-codes-test
  (let [{:keys [attempt feedback]}
        (run-one "B" {:outcome "success"
                      :answer {:paths ["docs/code-context.md"] :answer_text "not here"}
                      :turns [(turn 0)]
                      :tool_calls [{:tool_id "grep_search"}]})]
    (is (= "failure" (:outcome attempt)))
    (is (= "failure" (:feedback_outcome feedback)))
    (is (= ["missing_required_fact"] (:retrieval_issue_codes feedback)))))

(deftest tool-policy-violation-overrides-a-self-reported-success-test
  (let [{:keys [attempt feedback]}
        (run-one "D" {:outcome "success"
                      :answer good-answer
                      :turns [(turn 0)]
                      :tool_calls [{:tool_id "bash" :command "semidx resolve_context --intent x"}]})]
    (is (= "error" (:outcome attempt)))
    (is (= "arm_d_forbidden_tool_violation" (:feedback_reason feedback)))
    (is (= "arm_d_forbidden_tool_violation"
           (get-in feedback [:payload :policy_violation :reason])))))

(deftest unavailable-arm-c-is-recorded-as-not-applicable-test
  (let [{:keys [attempt feedback]}
        (run-one "C" {:outcome "not_applicable"
                      :not_applicable_reason "no LSP/SCIP index for zig on this host"
                      :turns []
                      :tool_calls []})]
    (is (= "not_applicable" (:outcome attempt)))
    (is (= "no LSP/SCIP index for zig on this host" (:not_applicable_reason attempt)))
    (is (= "not_applicable" (:feedback_outcome feedback)))
    (is (= "unresolved" (get-in feedback [:payload :usage_totals :pricing_status])))))

(deftest not-applicable-without-a-reason-is-rejected-test
  (is (thrown? clojure.lang.ExceptionInfo
               (run-one "C" {:outcome "not_applicable" :turns [] :tool_calls []}))))

(deftest runner-exception-is-recorded-not-swallowed-test
  (let [sink (usage/in-memory-usage-metrics)
        runner (harness/scripted-arm-runner
                {"A" (fn [_] (throw (ex-info "provider exploded" {})))})
        completed (harness/run-attempt! {:run sample-run :task sample-task
                                         :attempt (attempt-for "A")
                                         :runner runner :sink sink
                                         :workspace {:workspace_path "."}})
        feedback (first (usage/emitted-feedback sink))]
    (is (= "error" (:outcome completed)))
    (is (str/starts-with? (:feedback_reason feedback) "arm_runner_exception"))
    (is (str/includes? (:feedback_reason feedback) "provider exploded"))))

(deftest run-task-executes-every-arm-once-test
  (let [sink (usage/in-memory-usage-metrics)
        runner (harness/scripted-arm-runner
                (into {} (map (fn [arm]
                                [arm {:outcome "success"
                                      :answer good-answer
                                      :turns [(turn 0)]
                                      :tool_calls [{:tool_id (if (= "A" arm)
                                                               "resolve_context"
                                                               "grep_search")}]}])
                              ["A" "B" "C" "D"])))
        attempts (harness/run-task! {:run sample-run :task sample-task :runner runner
                                     :sink sink :seed 3 :evaluated evaluated :agent sample-agent})]
    (is (= 4 (count attempts)))
    (is (= #{"A" "B" "C" "D"} (set (map :arm attempts))))
    (is (= [0 1 2 3] (mapv :sequence_index attempts)))
    (is (= 4 (count (usage/emitted-feedback sink))))
    (is (every? (fn [attempt] (= "success" (:outcome attempt))) attempts))
    (is (= 4 (count (distinct (map :task_attempt_id attempts)))))))

(deftest run-task-refuses-a-mutation-without-workspace-isolation-test
  (let [task (assoc sample-task :workspace_mutation {:kind "append_text"
                                                     :path "src/x.clj"
                                                     :content ";; noop"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (harness/run-task! {:run sample-run :task task
                                     :runner (harness/scripted-arm-runner {})
                                     :seed 1 :isolate_workspace false})))))

;; --------------------------------------------------------------------------
;; Workspace handling
;; --------------------------------------------------------------------------

(defn- temp-dir []
  (.getAbsolutePath (.toFile (Files/createTempDirectory "semidx-bench-test-"
                                                        (make-array FileAttribute 0)))))

(deftest workspace-mutation-is-applied-inside-the-workspace-test
  (let [dir (temp-dir)
        task (bs/task (bs/load-suite) "stale_snapshot_after_edit_v1")
        applied (harness/apply-workspace-mutation! dir (:workspace_mutation task))
        target (io/file dir (get-in task [:workspace_mutation :path]))]
    (is (.exists target))
    (is (str/includes? (slurp target) "benchmark-freshness-probe-marker"))
    (is (some? (:applied_at applied)))
    (harness/cleanup-workspace! dir)
    (is (not (.exists (io/file dir))))))

(deftest unsupported-mutation-kind-is-rejected-test
  (let [dir (temp-dir)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (harness/apply-workspace-mutation! dir {:kind "delete_file" :path "x"})))
    (harness/cleanup-workspace! dir)))

(deftest cleanup-refuses-paths-outside-the-temp-root-test
  (is (thrown? clojure.lang.ExceptionInfo (harness/cleanup-workspace! "."))))

(deftest process-arm-runner-reads-an-agent-result-from-stdout-test
  (let [runner (harness/process-arm-runner
                ["/bin/sh" "-c"
                 (str "cat > /dev/null; "
                      "echo '{\"outcome\":\"success\",\"turns\":[],\"tool_calls\":[],"
                      "\"answer\":{\"paths\":[\"src/semidx/core.clj\"],"
                      "\"symbols\":[\"resolve-context\"]}}'")])
        result (harness/run-arm-attempt
                runner
                (harness/attempt-context {:run sample-run :task sample-task
                                          :attempt (attempt-for "B")
                                          :workspace {:workspace_path "."}}))]
    (is (= "success" (:outcome result)))
    (is (= ["src/semidx/core.clj"] (get-in result [:answer :paths])))))

(deftest process-arm-runner-surfaces-a-failing-agent-test
  (let [runner (harness/process-arm-runner ["/bin/sh" "-c" "cat > /dev/null; exit 3"])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (harness/run-arm-attempt
                  runner
                  (harness/attempt-context {:run sample-run :task sample-task
                                            :attempt (attempt-for "B")
                                            :workspace {:workspace_path "."}}))))))

(deftest benchmark-run-resolves-the-repository-revision-test
  (let [run (harness/new-benchmark-run {:suite (bs/load-suite) :repo_key "semidx"})]
    (is (= "semidx" (:repo_key run)))
    (is (re-matches #"[0-9a-f]{40}" (:repo_revision run)))
    (is (contains? #{true false} (:dirty_state run)))
    (is (= harness/harness-version (:harness_version run)))
    (is (= "implicit_cache_observed_v1" (:cache_protocol_id run)))))

;; --------------------------------------------------------------------------
;; Freshness and fact-matching regressions (review findings, 2026-08-28)
;; --------------------------------------------------------------------------

(deftest freshness-task-without-answer-snapshot-fails-test
  (let [task (assoc sample-task :freshness_check {:require_post_mutation_snapshot true})
        scoring (harness/score-answer task good-answer {:current_snapshot_id "new-snapshot"
                                                        :arm "A"})]
    (is (= "failure" (:outcome scoring))
        "a freshness task must not pass without snapshot evidence")
    (is (true? (:missing_snapshot_evidence scoring)))
    (is (false? (:stale_snapshot_reuse scoring))
        "an absent snapshot is not reuse of a stale one")
    (is (some #{"missing_snapshot_evidence"} (:retrieval_issue_codes scoring)))))

(deftest freshness-task-refuses-to-score-without-a-current-snapshot-test
  (let [task (assoc sample-task :freshness_check {:require_post_mutation_snapshot true})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (harness/score-answer task (assoc good-answer :snapshot_id "s1") {:arm "A"}))
        "a run that cannot supply the current snapshot must refuse, not degrade")))

(deftest freshness-evidence-is-required-only-from-snapshot-bearing-arms-test
  (let [task (assoc sample-task :freshness_check {:require_post_mutation_snapshot true})]
    (testing "a lexical or native arm reads the working tree and has no snapshot"
      (doseq [arm ["B" "C" "D"]]
        (let [scoring (harness/score-answer task good-answer {:arm arm})]
          (is (= "success" (:outcome scoring))
              (str "arm " arm " must not be failed for lacking a snapshot it cannot have"))
          (is (false? (:snapshot_evidence_required scoring)))
          (is (false? (:missing_snapshot_evidence scoring))))))
    (testing "a volunteered stale snapshot still fails any arm"
      (let [scoring (harness/score-answer task (assoc good-answer :snapshot_id "old-snapshot")
                                          {:arm "B" :current_snapshot_id "new-snapshot"})]
        (is (= "failure" (:outcome scoring)))
        (is (true? (:stale_snapshot_reuse scoring)))))
    (testing "an unknown arm fails closed"
      (is (true? (:missing_snapshot_evidence
                  (harness/score-answer task good-answer {:arm "Z"
                                                          :current_snapshot_id "new-snapshot"})))))))

(deftest freshness-task-passes-on-the-current-snapshot-test
  (let [task (assoc sample-task :freshness_check {:require_post_mutation_snapshot true})
        scoring (harness/score-answer task (assoc good-answer :snapshot_id "new-snapshot")
                                      {:current_snapshot_id "new-snapshot" :arm "A"})]
    (is (= "success" (:outcome scoring)))
    (is (false? (:missing_snapshot_evidence scoring)))
    (is (false? (:stale_snapshot_reuse scoring)))))

(deftest required-facts-are-matched-on-token-boundaries-test
  (let [task (assoc sample-task
                    :ground_truth {:required_facts ["ids"]})]
    (is (= "failure"
           (:outcome (harness/score-answer
                      task {:answer_text "This merely forbids unsafe operations."} {})))
        "a short required fact must not be satisfied by an unrelated substring")
    (is (= "success"
           (:outcome (harness/score-answer
                      task {:answer_text "The config carries ids and clock."} {}))))
    (is (= "success"
           (:outcome (harness/score-answer task {:facts ["ids"]} {})))
        "an explicit fact entry still counts")))

(deftest qualified-required-facts-still-match-in-text-test
  (let [task (assoc sample-task
                    :ground_truth {:required_facts ["ActorEngine.init"]})]
    (is (= "success"
           (:outcome (harness/score-answer
                      task {:answer_text "Consumed by ActorEngine.init at startup."} {}))))
    (is (= "failure"
           (:outcome (harness/score-answer
                      task {:answer_text "Consumed by ActorEngine.initialize only."} {})))
        "a longer identifier must not satisfy a shorter required fact")))
