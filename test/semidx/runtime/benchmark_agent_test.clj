(ns semidx.runtime.benchmark-agent-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.benchmark-agent :as agent]
            [semidx.runtime.benchmark-harness :as harness]
            [semidx.runtime.usage-metrics :as usage])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --------------------------------------------------------------------------
;; Stub provider
;; --------------------------------------------------------------------------

(defn- usage-metadata [prompt candidates]
  {:promptTokenCount prompt :candidatesTokenCount candidates})

(defn- call-response [calls]
  {:candidates [{:content {:role "model"
                           :parts (mapv (fn [[name args]]
                                          {:functionCall {:name name :args args}})
                                        calls)}
                 :finishReason "STOP"}]
   :usageMetadata (usage-metadata 900 40)})

(defn- text-response [text]
  {:candidates [{:content {:role "model" :parts [{:text text}]}
                 :finishReason "STOP"}]
   :usageMetadata (usage-metadata 1200 120)})

(def answer-json
  (str "{\"paths\": [\"src/app/core.clj\"], \"symbols\": [\"handle-request\"], "
       "\"facts\": [\"routes through handle-request\"], "
       "\"answer_text\": \"handle-request lives in src/app/core.clj\", "
       "\"confidence_level\": \"high\", \"snapshot_id\": \"model-invented\", "
       "\"context_tokens\": 1}"))

(defn- stub-provider
  "Provider stub that replays a fixed script and records every request."
  [responses]
  (let [remaining (atom (vec responses))
        seen (atom [])]
    {:calls seen
     :fn (fn [request]
           (swap! seen conj request)
           (let [[next & more] @remaining]
             (reset! remaining (vec more))
             (or next (text-response answer-json))))}))

;; --------------------------------------------------------------------------
;; Workspace and context fixtures
;; --------------------------------------------------------------------------

(defn- temp-workspace! []
  (let [root (str (Files/createTempDirectory "semidx-agent-test"
                                             (make-array FileAttribute 0)))
        target (io/file root "src/app/core.clj")]
    (io/make-parents target)
    (spit target "(ns app.core)\n\n(defn handle-request [req] req)\n")
    root))

(def evaluated
  {:evaluated_provider "google"
   :evaluated_api_surface "generate-content"
   :evaluated_model "gemini-2.5-flash"
   :evaluated_model_revision "gemini-2.5-flash"
   :evaluated_service_tier "on-demand"})

(defn- context-for
  ([workspace arm] (context-for workspace arm {}))
  ([workspace arm overrides]
   (merge {:benchmark_run_id "run-1"
           :task_attempt_id "attempt-1"
           :arm arm
           :arm_policy_id (str "arm_" arm)
           :allowed_tools (vec (sort (get-in harness/arm-policies [arm :allowed_tools])))
           :command_denylist (vec (get-in harness/arm-policies [arm :command_denylist]))
           :prompt "Where is the request handler defined?"
           :task {:task_id "t1" :prompt "Where is the request handler defined?"}
           :attempt (merge evaluated {:task_attempt_id "attempt-1" :arm arm})
           :workspace_path workspace
           :execution_budget {:max_wall_clock_seconds 300 :max_tool_calls 30}
           :usage_context {:surface "benchmark" :session_id "run-1" :task_id "attempt-1"}
           :trace {:trace_id (str (java.util.UUID/randomUUID))
                   :request_id (str (java.util.UUID/randomUUID))
                   :session_id "run-1"
                   :task_id "attempt-1"
                   :actor_id "agent-1"}}
          overrides)))

;; --------------------------------------------------------------------------
;; Tool loop
;; --------------------------------------------------------------------------

(deftest tool-loop-records-turns-tool-calls-and-answer-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(call-response [["view_file" {:path "src/app/core.clj"}]])
                                 (text-response answer-json)])
        result (agent/run-attempt (context-for workspace "B" {:allowed_tools ["view_file"]})
                                  {:generate-content (:fn provider)})]
    (is (= "success" (:outcome result)))
    (is (= 2 (count (:turns result))))
    (is (= ["gemini-generate-content" "gemini-generate-content"]
           (mapv :adapter_id (:turns result))))
    (is (= 900 (get-in result [:turns 0 :raw_usage :promptTokenCount]))
        "raw provider usage is preserved for the price schedule")
    (is (= [{:tool_id "view_file"}] (:tool_calls result)))
    (is (= ["src/app/core.clj"] (get-in result [:answer :paths])))
    (is (= ["handle-request"] (get-in result [:answer :symbols])))
    (testing "the tool result was fed back to the model"
      (let [second-request (second @(:calls provider))
            payload (pr-str (:contents second-request))]
        (is (str/includes? payload "handle-request"))))))

(deftest runner-reports-observed-snapshot-and-context-cost-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(call-response [["view_file" {:path "src/app/core.clj"}]])
                                 (text-response answer-json)])
        result (agent/run-attempt (context-for workspace "B" {:allowed_tools ["view_file"]})
                                  {:generate-content (:fn provider)})]
    (is (nil? (get-in result [:answer :snapshot_id]))
        "a lexical arm has no snapshot, and the model's invented one is discarded")
    (is (pos? (get-in result [:answer :context_tokens])))
    (is (not= 1 (get-in result [:answer :context_tokens]))
        "context cost is measured by the runner, not self-reported by the model")))

(deftest forbidden-tool-is-refused-but-still-reported-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(call-response [["bash" {:command "ls"}]])
                                 (text-response answer-json)])
        result (agent/run-attempt (context-for workspace "A")
                                  {:generate-content (:fn provider)})]
    (is (= [{:tool_id "bash" :command "ls"}] (:tool_calls result))
        "a policy breach must reach the harness audit, not be absorbed here")
    (let [payload (pr-str (:contents (second @(:calls provider))))]
      (is (str/includes? payload "is not allowed for arm A")))))

(deftest arm-a-tools-are-the-only-ones-offered-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(text-response answer-json)])]
    (agent/run-attempt (context-for workspace "A") {:generate-content (:fn provider)})
    (let [declared (->> (first @(:calls provider)) :tools (map :name) set)]
      (is (= #{"resolve_context" "expand_context" "fetch_context_detail"} declared)
          "the model is only offered the tools its arm allows"))))

;; --------------------------------------------------------------------------
;; Refusals and budget
;; --------------------------------------------------------------------------

(deftest arm-c-without-a-language-server-is-not-applicable-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(text-response answer-json)])
        result (agent/run-attempt (context-for workspace "C")
                                  {:generate-content (:fn provider)})]
    (is (= "not_applicable" (:outcome result)))
    (is (str/includes? (:not_applicable_reason result) "language server"))
    (is (empty? @(:calls provider))
        "an unavailable arm must not spend provider tokens")
    (is (empty? (:turns result)))))

(deftest attempt-without-an-evaluated-model-revision-is-refused-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(text-response answer-json)])
        context (context-for workspace "B" {:allowed_tools ["view_file"]
                                            :attempt {:task_attempt_id "attempt-1"}})
        result (agent/run-attempt context {:generate-content (:fn provider)})]
    (is (= "error" (:outcome result)))
    (is (str/includes? (:error_reason result) "evaluated_model_revision"))
    (is (empty? @(:calls provider)))))

(deftest lexical-arm-preflight-checks-ripgrep-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(text-response answer-json)])
        result (agent/run-attempt (context-for workspace "B")
                                  {:generate-content (:fn provider)})]
    (if (zero? (:exit (clojure.java.shell/sh "sh" "-c" "command -v rg")))
      (is (= "success" (:outcome result))
          "with rg present the lexical arm runs normally")
      (do (is (= "error" (:outcome result)))
          (is (str/includes? (:error_reason result) "ripgrep_unavailable"))))))

(deftest budget-exhaustion-stops-the-loop-test
  (let [workspace (temp-workspace!)
        provider (stub-provider (repeat 6 (call-response [["view_file" {:path "src/app/core.clj"}]])))
        result (agent/run-attempt (context-for workspace "B"
                                               {:allowed_tools ["view_file"]
                                                :execution_budget {:max_wall_clock_seconds 300
                                                                   :max_tool_calls 1}})
                                  {:generate-content (:fn provider)})]
    (is (= 1 (count (:tool_calls result)))
        "the runner stops at the budget instead of overrunning it")
    (is (= "error" (:outcome result)))
    (is (= "execution_budget_exhausted_without_answer" (:error_reason result)))
    (is (nil? (:tools (last @(:calls provider))))
        "the final request withdraws the tools so the model must answer")))

(deftest unparseable-answer-is-an-error-test
  (let [workspace (temp-workspace!)
        provider (stub-provider [(text-response "I could not find it, sorry.")])
        result (agent/run-attempt (context-for workspace "B" {:allowed_tools ["view_file"]})
                                  {:generate-content (:fn provider)})]
    (is (= "error" (:outcome result)))
    (is (= "agent_answer_unparseable" (:error_reason result)))))

(deftest provider-failure-is-reported-not-swallowed-test
  (let [workspace (temp-workspace!)
        result (agent/run-attempt (context-for workspace "B" {:allowed_tools ["view_file"]})
                                  {:generate-content (fn [_] (throw (ex-info "429 quota" {})))})]
    (is (= "error" (:outcome result)))
    (is (str/includes? (:error_reason result) "provider_error"))
    (is (str/includes? (:error_reason result) "429 quota"))))

;; --------------------------------------------------------------------------
;; Tool behavior
;; --------------------------------------------------------------------------

(deftest arm-d-denylisted-command-is-refused-test
  (let [workspace (temp-workspace!)
        context (context-for workspace "D")
        {:keys [result]} (agent/execute-tool {:context_tokens 0} context "bash"
                                             {:command "rg foo | semidx resolve --intent bar"})]
    (is (true? (:refused result)))
    (is (str/includes? (:error result) "forbids"))))

(deftest arm-d-allowed-command-runs-in-the-workspace-test
  (let [workspace (temp-workspace!)
        context (context-for workspace "D")
        {:keys [result]} (agent/execute-tool {:context_tokens 0} context "bash"
                                             {:command "ls src"})]
    (is (zero? (:exit result)))
    (is (str/includes? (:stdout result) "app"))))

(deftest paths-outside-the-workspace-are-refused-test
  (let [workspace (temp-workspace!)
        context (context-for workspace "B" {:allowed_tools ["view_file"]})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (agent/execute-tool {:context_tokens 0} context "view_file"
                                     {:path "../../../etc/hosts"})))))

(deftest view-file-window-is-bounded-test
  (let [workspace (temp-workspace!)
        context (context-for workspace "B" {:allowed_tools ["view_file"]})
        {:keys [result]} (agent/execute-tool {:context_tokens 0} context "view_file"
                                             {:path "src/app/core.clj" :offset 1 :limit 2})]
    (is (= 2 (:lines result)))
    (is (str/includes? (:content result) "(ns app.core)"))))

(deftest parse-answer-accepts-fenced-json-test
  (is (= ["a.clj"] (:paths (agent/parse-answer (str "```json\n{\"paths\": [\"a.clj\"]}\n```")))))
  (is (nil? (agent/parse-answer "no json here"))))

;; --------------------------------------------------------------------------
;; Harness integration
;; --------------------------------------------------------------------------

(def integration-task
  {:task_id "agent_integration_v1"
   :task_type "symbol_lookup"
   :repo_key "semidx"
   :arms ["B"]
   :prompt "Where is the request handler defined?"
   :ground_truth {:required_paths ["src/app/core.clj"]
                  :required_symbols ["handle-request"]}})

(deftest live-runner-satisfies-the-harness-arm-runner-contract-test
  (let [workspace (temp-workspace!)
        sink (usage/in-memory-usage-metrics)
        run {:benchmark_run_id "run-agent-1"
             :suite_version "benchmark_task_suite_v1"
             :repo_key "semidx"
             :repo_path workspace
             :repo_revision "rev-1"
             :dirty_state false
             :task_prompt_policy_id harness/task-prompt-policy-id
             :arm_policy_bundle_id harness/arm-policy-bundle-id
             :execution_budget_policy_id harness/execution-budget-policy-id
             :cache_protocol_id harness/cache-protocol-id
             :harness_version harness/harness-version}
        attempt (harness/new-task-attempt run integration-task "B"
                                          (merge evaluated {:seed 1
                                                            :sequence_index 0
                                                            :agent_id agent/agent-id
                                                            :agent_build_id agent/agent-build-id}))
        provider (stub-provider [(call-response [["view_file" {:path "src/app/core.clj"}]])
                                 (text-response answer-json)])
        completed (harness/run-attempt!
                   {:run run
                    :task integration-task
                    :attempt attempt
                    :runner (agent/live-arm-runner {:generate-content (:fn provider)
                                                    :usage_metrics sink})
                    :sink sink
                    :workspace {:workspace_path workspace :isolated true}})
        feedback (first (usage/emitted-feedback sink))]
    (is (= "success" (:outcome completed))
        "the harness scores the live runner's answer against ground truth")
    (is (= 2 (count (:usage_matrix completed))))
    (is (= "resolved" (get-in completed [:usage_totals :pricing_status]))
        "turns produced by the live runner price cleanly")
    (is (pos? (get-in completed [:usage_totals :cost_usd])))
    (is (= agent/agent-id (:actor_id feedback)))
    (is (= "benchmark_attempt" (:operation feedback)))
    (is (= (:task_attempt_id attempt) (:task_id feedback)))))
