(ns semidx.runtime.benchmark-harness
  "Four-arm benchmark harness for the retrieval value experiment
   (plans/020 Stage 2, preregistration reports/023).

   The harness owns run/attempt identity, the preregistered arm policies and
   their tool audit, workspace isolation, uniform outcome scoring against task
   ground truth, response/usage normalization, and outcome write-back through
   the usage-metrics feedback sink.

   It does not own the agent. One arm attempt is executed by an `ArmRunner`
   implementation, so the same scoring, auditing, and accounting apply to a
   scripted runner, an out-of-process agent, or a future in-process one."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [semidx.runtime.benchmark-suite :as suite]
            [semidx.runtime.benchmark-usage :as bu]
            [semidx.runtime.usage-metrics :as usage])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.util Random UUID]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Preregistered identities (reports/023 sections 2, 3, 4)
;; ---------------------------------------------------------------------------

(def harness-version "benchmark_harness_v1")
(def task-prompt-policy-id "agent_default_v1")
(def arm-policy-bundle-id "harness_v1")
(def execution-budget-policy-id "budget_v1")
(def cache-protocol-id bu/cache-protocol-id)

(def feedback-surface "benchmark")
(def feedback-operation "benchmark_attempt")

(def task-prompt-preamble
  "Shared task prompt policy `agent_default_v1`. Every arm receives the same
   wording; arms differ only by their registered tool policy."
  (str "You are an autonomous AI agent assigned to resolve an issue in this repository.\n"
       "Use your available tools to explore the codebase, analyze the problem, "
       "and implement a solution."))

(def execution-budget
  "Execution budget policy `budget_v1`."
  {:execution_budget_policy_id execution-budget-policy-id
   :max_wall_clock_seconds 300
   :max_tool_calls 30})

(def arm-d-command-denylist
  "Finite semantic-navigation command/service prefix denylist for Arm D `bash`.
   The same list governs the Arm D command-log audit rule."
  ["semidx" "scip" "sourcegraph" "sg" "codeql" "clojure -M:mcp" "clojure -M:mcp-http"])

(def arm-policies
  "Arm policy bundle `harness_v1`."
  {"A" {:arm "A"
        :arm_policy_id "arm_a_semidx_staged_v1"
        :name "semidx staged"
        :verdict_role "candidate"
        :allowed_tools #{"resolve_context" "expand_context" "fetch_context_detail"}
        :violation_reason "arm_tool_policy_violation"}
   "B" {:arm "B"
        :arm_policy_id "arm_b_competent_lexical_v1"
        :name "competent lexical"
        :verdict_role "primary_comparator"
        :allowed_tools #{"grep_search" "list_dir" "view_file"}
        :violation_reason "arm_tool_policy_violation"}
   "C" {:arm "C"
        :arm_policy_id "arm_c_language_navigation_v1"
        :name "language navigation"
        :verdict_role "diagnostic_control"
        :allowed_tools #{"grep_search" "list_dir" "view_file"
                         "lsp_definition" "lsp_references"}
        :violation_reason "arm_tool_policy_violation"}
   "D" {:arm "D"
        :arm_policy_id "arm_d_native_no_index_v1"
        :name "native no-index"
        :verdict_role "ecological_control"
        :allowed_tools #{"bash" "grep_search" "list_dir" "view_file"}
        :command_denylist arm-d-command-denylist
        :violation_reason "arm_d_forbidden_tool_violation"}})

(def valid-outcomes #{"success" "failure" "error" "not_applicable"})

;; ---------------------------------------------------------------------------
;; Small helpers
;; ---------------------------------------------------------------------------

(defn- now-iso [] (str (Instant/now)))
(defn- now-ms [] (System/currentTimeMillis))
(defn- uuid [] (str (UUID/randomUUID)))

(defn- git [repo-path & args]
  (apply shell/sh "git" "-C" (str repo-path) args))

(defn resolve-repo-state
  "Read the checked-out revision and dirty state of a repository.

   Revisions are resolved at run time rather than pinned in the suite fixture,
   so a BenchmarkRun always records the revision that was actually measured."
  [repo-path]
  (let [rev (git repo-path "rev-parse" "HEAD")
        status (git repo-path "status" "--porcelain")]
    (when-not (zero? (:exit rev))
      (throw (ex-info "Cannot resolve repository revision"
                      {:error_code "benchmark_repo_revision_unresolved"
                       :repo_path (str repo-path)
                       :stderr (str/trim (str (:err rev)))})))
    {:repo_revision (str/trim (:out rev))
     :dirty_state (not (str/blank? (str/trim (str (:out status)))))}))

;; ---------------------------------------------------------------------------
;; Run and attempt identity
;; ---------------------------------------------------------------------------

(defn new-benchmark-run
  "Build an immutable `BenchmarkRun` for one repository of a suite.

   Throws when the locked price schedule is no longer eligible: a run started
   on or after its eligible-until date cannot produce a valid cost verdict."
  [{:keys [suite repo_key repo_path benchmark_run_id started_at]}]
  (let [repository (suite/repository suite repo_key)
        _ (when-not repository
            (throw (ex-info "Repository is not declared in the suite"
                            {:error_code "benchmark_repo_not_declared"
                             :repo_key repo_key})))
        path (or repo_path (:workspace_path repository))
        started (or started_at (now-iso))
        run-date (subs (str started) 0 10)]
    (when-not (bu/price-schedule-eligible? run-date)
      (throw (ex-info "Locked price schedule is no longer eligible"
                      {:error_code "benchmark_price_schedule_expired"
                       :price_schedule_id bu/price-schedule-id
                       :eligible_until (:eligible_until bu/price-schedule)
                       :run_date run-date})))
    (merge {:benchmark_run_id (or benchmark_run_id (uuid))
            :suite_version (:suite_version suite)
            :started_at started
            :repo_key repo_key
            :repo_path (str path)
            :task_prompt_policy_id task-prompt-policy-id
            :arm_policy_bundle_id arm-policy-bundle-id
            :execution_budget_policy_id execution-budget-policy-id
            :cache_protocol_id cache-protocol-id
            :price_schedule_id bu/price-schedule-id
            :harness_version harness-version}
           (resolve-repo-state path))))

(defn new-task-attempt
  "Build a `TaskAttempt` identity record for one (task, arm, seed) execution."
  [run task arm {:keys [seed sequence_index agent_id agent_build_id
                        evaluated_provider evaluated_api_surface evaluated_model
                        evaluated_model_revision evaluated_service_tier
                        task_attempt_id]}]
  (let [policy (get arm-policies arm)]
    (when-not policy
      (throw (ex-info "Unknown benchmark arm"
                      {:error_code "benchmark_unknown_arm" :arm arm})))
    {:benchmark_run_id (:benchmark_run_id run)
     :task_id (:task_id task)
     :task_attempt_id (or task_attempt_id (uuid))
     :arm arm
     :arm_policy_id (:arm_policy_id policy)
     :sequence_index sequence_index
     :seed seed
     :agent_id agent_id
     :agent_build_id agent_build_id
     :evaluated_provider evaluated_provider
     :evaluated_api_surface evaluated_api_surface
     :evaluated_model evaluated_model
     :evaluated_model_revision evaluated_model_revision
     :evaluated_service_tier evaluated_service_tier
     :outcome nil
     :not_applicable_reason nil}))

(defn arm-order
  "Deterministic per-seed arm order. Arm order is randomized rather than fixed
   so provider-side implicit cache variation cannot line up with one arm."
  [arms seed]
  (let [rng (Random. (long (or seed 0)))]
    (->> arms
         (map (fn [arm] [(.nextLong rng) arm]))
         (sort-by first)
         (mapv second))))

(defn attempt-usage-context
  "Usage context that tags every semidx call of an attempt.

   `session_id` carries the benchmark run and `task_id` carries the task
   attempt, so `semantic_usage_events` rows join to the attempt that produced
   them without a many-to-many task join."
  [run attempt]
  {:surface feedback-surface
   :session_id (:benchmark_run_id run)
   :task_id (:task_attempt_id attempt)
   :actor_id (:agent_id attempt)})

(defn attempt-trace
  "Trace block an Arm A runner must pass into every semidx query.

   The retrieval query contract's `trace` is a closed map, so the agent is
   carried as `actor_id` only. Adding `agent_id` here would make every Arm A
   query fail validation instead of being measured."
  [run attempt]
  {:trace_id (uuid)
   :request_id (uuid)
   :session_id (:benchmark_run_id run)
   :task_id (:task_attempt_id attempt)
   :actor_id (:agent_id attempt)})

;; ---------------------------------------------------------------------------
;; Arm policy audit and execution budget
;; ---------------------------------------------------------------------------

(defn- command-segments [command]
  (->> (str/split (str command) #"\||&&|;")
       (map str/trim)
       (remove str/blank?)))

(defn- denylisted-command? [denylist command]
  (boolean
   (some (fn [segment]
           (some (fn [prefix]
                   (or (= segment prefix)
                       (str/starts-with? segment (str prefix " "))))
                 denylist))
         (command-segments command))))

(defn audit-tool-calls
  "Audit an attempt's tool log against its arm policy.

   Returns nil when the attempt is clean, otherwise a violation map whose
   `:reason` is the preregistered failure reason for that arm."
  [arm tool-calls]
  (let [policy (get arm-policies arm)
        allowed (:allowed_tools policy)
        denylist (:command_denylist policy)
        forbidden-tools (->> tool-calls
                             (map :tool_id)
                             (remove allowed)
                             distinct
                             vec)
        denied-commands (when (seq denylist)
                          (->> tool-calls
                               (filter (fn [call] (= "bash" (:tool_id call))))
                               (map :command)
                               (filter (partial denylisted-command? denylist))
                               distinct
                               vec))]
    (when (or (seq forbidden-tools) (seq denied-commands))
      {:reason (:violation_reason policy)
       :arm arm
       :forbidden_tool_ids forbidden-tools
       :denied_commands (vec denied-commands)})))

(defn budget-violation
  "Return a violation map when an attempt exceeded the `budget_v1` limits."
  [{:keys [wall_clock_ms tool_calls]}]
  (let [seconds (/ (double (or wall_clock_ms 0)) 1000.0)
        calls (count tool_calls)]
    (cond
      (> seconds (:max_wall_clock_seconds execution-budget))
      {:reason "execution_budget_exceeded"
       :limit "max_wall_clock_seconds"
       :observed seconds
       :allowed (:max_wall_clock_seconds execution-budget)}

      (> calls (:max_tool_calls execution-budget))
      {:reason "execution_budget_exceeded"
       :limit "max_tool_calls"
       :observed calls
       :allowed (:max_tool_calls execution-budget)}

      :else nil)))

;; ---------------------------------------------------------------------------
;; Scoring against task ground truth
;; ---------------------------------------------------------------------------

(defn- normalize-path [path]
  (-> (str path)
      (str/replace #"^\./" "")
      (str/replace #"^/+" "")))

(defn- path-covered? [answer-paths required]
  (let [required (normalize-path required)]
    (boolean (some (fn [candidate]
                     (let [candidate (normalize-path candidate)]
                       (or (= candidate required)
                           (str/ends-with? candidate (str "/" required)))))
                   answer-paths))))

(defn- word-boundary-pattern
  "Match `required` only as a whole token.

   A bare substring test lets an unrelated word satisfy a short required fact
   (`ids` inside `forbids`), which would score a false negative as a success."
  [required]
  (re-pattern (str "(?<![\\p{L}\\p{N}_])"
                   (java.util.regex.Pattern/quote (str required))
                   "(?![\\p{L}\\p{N}_])")))

(defn- token-covered? [answer required]
  (let [tokens (set (concat (map str (:symbols answer))
                            (map str (:facts answer))))
        text (str (:answer_text answer))]
    (boolean (or (contains? tokens (str required))
                 (and (seq text)
                      (re-find (word-boundary-pattern required) text))))))

(defn snapshot-bearing-arm?
  "True when an arm answers from a snapshot-bound index.

   Only these arms can prove freshness with a `snapshot_id`. A lexical or
   native arm reads the working tree directly and has no snapshot to report, so
   demanding one from it would fail a freshness task that it actually solved.
   An unknown arm is treated as snapshot-bearing, so a missing arm identity
   fails closed rather than skipping the check."
  [arm]
  (let [policy (get arm-policies arm)]
    (or (nil? policy)
        (boolean (some (:allowed_tools policy)
                       ["resolve_context" "expand_context" "fetch_context_detail"])))))

(defn score-answer
  "Score one arm answer against the task ground truth.

   Scoring is uniform across arms: the same required paths, symbols, and facts
   decide success for semidx and for every baseline, so an arm cannot win by
   answering a different question. Excess context cost and stale-snapshot reuse
   are recorded as separate signals rather than folded into ranking quality.

   A freshness task is passed on evidence, but only where evidence exists. A
   snapshot-bearing arm that reports no `snapshot_id` fails with
   `missing_snapshot_evidence` instead of passing on a correct-looking path, and
   a run that cannot supply the current snapshot id for such an arm is refused
   rather than silently degraded into an ordinary retrieval case. A lexical or
   native arm reads the working tree and is scored on ground truth alone."
  [task answer {:keys [current_snapshot_id arm]}]
  (let [gt (:ground_truth task)
        freshness-required? (get-in task [:freshness_check :require_post_mutation_snapshot])
        snapshot-evidence-required? (boolean (and freshness-required?
                                                  (snapshot-bearing-arm? arm)))
        _ (when (and snapshot-evidence-required? (str/blank? (str current_snapshot_id)))
            (throw (ex-info "Freshness task scored without a current snapshot id"
                            {:error_code "benchmark_missing_current_snapshot_for_freshness_task"
                             :task_id (:task_id task)
                             :arm arm})))
        answer-paths (or (:paths answer) [])
        missing-paths (vec (remove (partial path-covered? answer-paths)
                                   (:required_paths gt)))
        missing-symbols (vec (remove (partial token-covered? answer)
                                     (:required_symbols gt)))
        missing-facts (vec (remove (partial token-covered? answer)
                                   (:required_facts gt)))
        forbidden-hits (vec (for [candidate answer-paths
                                  prefix (:forbidden_path_prefixes gt)
                                  :when (str/starts-with? (normalize-path candidate)
                                                          (normalize-path prefix))]
                              candidate))
        ceiling (get-in task [:cost_ceiling :max_returned_tokens])
        context-tokens (:context_tokens answer)
        excess-context? (boolean (and ceiling context-tokens (> context-tokens ceiling)))
        answer-snapshot (:snapshot_id answer)
        missing-snapshot? (boolean (and snapshot-evidence-required?
                                        (str/blank? (str answer-snapshot))))
        ;; A reported snapshot is checked for every arm: an arm that volunteers a
        ;; stale snapshot id is reusing stale context whatever its policy is.
        stale-snapshot? (boolean (and freshness-required?
                                      (seq (str answer-snapshot))
                                      (seq (str current_snapshot_id))
                                      (not= current_snapshot_id answer-snapshot)))
        false-negative? (boolean (or (seq missing-paths)
                                     (seq missing-symbols)
                                     (seq missing-facts)))
        issue-codes (cond-> []
                      false-negative? (conj "missing_required_fact")
                      (seq forbidden-hits) (conj "unrelated_seed_selection")
                      missing-snapshot? (conj "missing_snapshot_evidence")
                      stale-snapshot? (conj "stale_snapshot_reuse")
                      excess-context? (conj "excess_context_cost"))]
    {:outcome (if (or false-negative? (seq forbidden-hits) missing-snapshot? stale-snapshot?)
                "failure"
                "success")
     :missing {:paths missing-paths
               :symbols missing-symbols
               :facts missing-facts}
     :forbidden_paths_hit forbidden-hits
     :false_negative false-negative?
     :snapshot_evidence_required snapshot-evidence-required?
     :missing_snapshot_evidence missing-snapshot?
     :stale_snapshot_reuse stale-snapshot?
     :excess_context_cost excess-context?
     :context_tokens context-tokens
     :max_returned_tokens ceiling
     :retrieval_issue_codes issue-codes}))

;; ---------------------------------------------------------------------------
;; Workspace isolation and task mutations
;; ---------------------------------------------------------------------------

(defn prepare-attempt-workspace!
  "Clone `repo-path` at `revision` into a fresh temporary directory.

   Every attempt runs in its own workspace so one arm cannot observe another
   arm's edits, and so a task mutation never touches the source checkout."
  [{:keys [repo_path repo_revision prefix]}]
  (let [dir (.toFile (Files/createTempDirectory (str (or prefix "semidx-bench-"))
                                                (make-array FileAttribute 0)))
        target (.getAbsolutePath dir)
        clone (shell/sh "git" "clone" "--local" "--no-hardlinks" "--quiet"
                        (str repo_path) target)]
    (when-not (zero? (:exit clone))
      (throw (ex-info "Cannot clone benchmark workspace"
                      {:error_code "benchmark_workspace_clone_failed"
                       :repo_path (str repo_path)
                       :target target
                       :stderr (str/trim (str (:err clone)))})))
    (when repo_revision
      (let [checkout (git target "checkout" "--quiet" (str repo_revision))]
        (when-not (zero? (:exit checkout))
          (throw (ex-info "Cannot check out benchmark revision"
                          {:error_code "benchmark_workspace_checkout_failed"
                           :revision repo_revision
                           :target target
                           :stderr (str/trim (str (:err checkout)))})))))
    {:workspace_path target
     :repo_revision repo_revision
     :isolated true}))

(defn cleanup-workspace!
  "Delete a workspace created by `prepare-attempt-workspace!`.

   Refuses any path outside the system temp directory so a misconfigured run
   cannot delete a real checkout."
  [workspace-path]
  (let [tmp-root (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")))
        target (io/file (str workspace-path))
        path (.getAbsolutePath target)]
    (when-not (str/starts-with? path tmp-root)
      (throw (ex-info "Refusing to delete a workspace outside the temp directory"
                      {:error_code "benchmark_workspace_unsafe_cleanup"
                       :workspace_path path})))
    (doseq [^File file (reverse (file-seq target))]
      (.delete file))
    true))

(defn apply-workspace-mutation!
  "Apply a task's declared workspace mutation inside an attempt workspace."
  [workspace-path {:keys [kind path content] :as mutation}]
  (when mutation
    (when-not (= "append_text" kind)
      (throw (ex-info "Unsupported workspace mutation kind"
                      {:error_code "benchmark_unsupported_mutation"
                       :kind kind})))
    (let [target (io/file (str workspace-path) (str path))]
      (io/make-parents target)
      (spit target (str content) :append true)
      {:kind kind
       :path (str path)
       :applied_at (now-iso)})))

;; ---------------------------------------------------------------------------
;; Arm runners
;; ---------------------------------------------------------------------------

(defprotocol ArmRunner
  "Executes one task attempt under one arm policy.

   Implementations receive a fully specified attempt context and return
   `{:outcome ... :turns [...] :tool_calls [...] :answer {...}}`. They never
   score themselves: outcome scoring, tool auditing, and cost normalization
   stay in the harness so all arms are treated identically."
  (run-arm-attempt [runner context]))

(defrecord ScriptedArmRunner [responses]
  ArmRunner
  (run-arm-attempt [_ context]
    (let [key [(get-in context [:task :task_id]) (:arm context)]
          response (or (get responses key)
                       (get responses (:arm context)))]
      (cond
        (nil? response)
        (throw (ex-info "No scripted response for attempt"
                        {:error_code "benchmark_scripted_response_missing"
                         :task_id (first key)
                         :arm (:arm context)}))

        (fn? response) (response context)
        :else response))))

(defn scripted-arm-runner
  "Runner backed by a fixed response map keyed by `[task_id arm]` or `arm`.
   Used for harness tests and dry runs; it never contacts a provider."
  [responses]
  (->ScriptedArmRunner responses))

(defn- context->json [context]
  (json/write-str
   (-> context
       (update :task select-keys [:task_id :task_type :repo_key :language :prompt])
       (update :attempt dissoc :outcome :not_applicable_reason))))

(defn- run-process-with-timeout
  "Run an agent process, feed it `payload` on stdin, and collect its output.

   Output is drained on separate threads before waiting, so a chatty agent
   cannot deadlock on a full pipe buffer, and a hung agent is destroyed at
   `timeout-ms` instead of stalling the whole run."
  [command payload timeout-ms]
  (let [process (.start (ProcessBuilder. ^java.util.List (vec command)))]
    (with-open [stdin (.getOutputStream process)]
      (.write stdin (.getBytes (str payload) "UTF-8"))
      (.flush stdin))
    (let [stdout (future (slurp (.getInputStream process)))
          stderr (future (slurp (.getErrorStream process)))
          finished? (if timeout-ms
                      (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                      (do (.waitFor process) true))]
      (when-not finished?
        (.destroyForcibly process)
        (throw (ex-info "Benchmark arm process timed out"
                        {:error_code "benchmark_arm_process_timeout"
                         :command (vec command)
                         :timeout_ms timeout-ms})))
      {:exit (.exitValue process)
       :out @stdout
       :err @stderr})))

(defrecord ProcessArmRunner [command timeout-ms]
  ArmRunner
  (run-arm-attempt [_ context]
    (let [payload (context->json context)
          result (run-process-with-timeout command payload timeout-ms)]
      (when-not (zero? (:exit result))
        (throw (ex-info "Benchmark arm process failed"
                        {:error_code "benchmark_arm_process_failed"
                         :command command
                         :exit (:exit result)
                         :stderr (str/trim (str (:err result)))})))
      (try
        (json/read-str (:out result) :key-fn keyword)
        (catch Exception e
          (throw (ex-info "Benchmark arm process returned unreadable JSON"
                          {:error_code "benchmark_arm_process_bad_output"
                           :command command
                           :stdout_head (subs (str (:out result))
                                              0 (min 500 (count (str (:out result)))))}
                          e)))))))

(defn process-arm-runner
  "Runner that executes an external agent process.

   The attempt context is written to the process stdin as JSON and the attempt
   result is read back from stdout as JSON. This is the binding point for a
   real evaluated model: the harness stays provider-agnostic and the agent
   stays out of process."
  ([command]
   (process-arm-runner command (* 1000 (:max_wall_clock_seconds execution-budget))))
  ([command timeout-ms] (->ProcessArmRunner (vec command) timeout-ms)))

;; ---------------------------------------------------------------------------
;; Attempt execution
;; ---------------------------------------------------------------------------

(defn attempt-context
  "The full, arm-symmetric execution context handed to an `ArmRunner`."
  [{:keys [run task attempt workspace current_snapshot_id]}]
  (let [policy (get arm-policies (:arm attempt))]
    {:benchmark_run_id (:benchmark_run_id run)
     :task_attempt_id (:task_attempt_id attempt)
     :arm (:arm attempt)
     :arm_policy_id (:arm_policy_id policy)
     :arm_policy_bundle_id arm-policy-bundle-id
     :allowed_tools (vec (sort (:allowed_tools policy)))
     :command_denylist (vec (:command_denylist policy))
     :task_prompt_policy_id task-prompt-policy-id
     :prompt (str task-prompt-preamble "\n\n" (:prompt task))
     :task task
     :attempt attempt
     :run (select-keys run [:benchmark_run_id :repo_key :repo_revision :suite_version])
     :workspace_path (:workspace_path workspace)
     :current_snapshot_id current_snapshot_id
     :execution_budget (dissoc execution-budget :execution_budget_policy_id)
     :cache_protocol_id cache-protocol-id
     :usage_context (attempt-usage-context run attempt)
     :trace (attempt-trace run attempt)}))

(defn- outcome-from-result
  "Derive the recorded outcome of an attempt.

   Audit and budget violations override the runner's own report, an explicit
   `not_applicable` (the only representation of an unavailable Arm C) is kept
   with its required reason, and everything else is scored against ground
   truth."
  [task result scoring violation]
  (let [reported (:outcome result)]
    (cond
      violation
      {:outcome "error"
       :feedback_reason (:reason violation)
       :not_applicable_reason nil}

      (= "not_applicable" reported)
      (do
        (when (str/blank? (str (:not_applicable_reason result)))
          (throw (ex-info "not_applicable attempt requires not_applicable_reason"
                          {:error_code "benchmark_missing_not_applicable_reason"
                           :task_id (:task_id task)})))
        {:outcome "not_applicable"
         :feedback_reason (:not_applicable_reason result)
         :not_applicable_reason (:not_applicable_reason result)})

      (= "error" reported)
      {:outcome "error"
       :feedback_reason (or (:error_reason result) "arm_reported_error")
       :not_applicable_reason nil}

      :else
      {:outcome (:outcome scoring)
       :feedback_reason (when (= "failure" (:outcome scoring))
                          (str/join "," (:retrieval_issue_codes scoring)))
       :not_applicable_reason nil})))

(defn- tool-call-summary [tool-calls]
  {:tool_call_count (count tool-calls)
   :tool_id_counts (frequencies (map :tool_id tool-calls))})

(defn run-attempt!
  "Execute, audit, score, normalize, and record one task attempt.

   Returns the completed attempt record. The outcome and the whole
   response/usage matrix of the attempt are written to the usage-metrics
   feedback sink keyed by `task_attempt_id`; a sink that refuses the record
   fails the attempt loudly rather than losing an observation."
  [{:keys [run task attempt runner sink workspace current_snapshot_id]}]
  (let [context (attempt-context {:run run :task task :attempt attempt
                                  :workspace workspace
                                  :current_snapshot_id current_snapshot_id})
        started-ms (now-ms)
        result (try
                 (run-arm-attempt runner context)
                 (catch Exception e
                   {:outcome "error"
                    :error_reason (str "arm_runner_exception: " (.getMessage e))
                    :turns []
                    :tool_calls []}))
        measured-ms (- (now-ms) started-ms)
        wall-clock-ms (or (:wall_clock_ms result) measured-ms)
        tool-calls (vec (:tool_calls result))
        violation (or (audit-tool-calls (:arm attempt) tool-calls)
                      (budget-violation {:wall_clock_ms wall-clock-ms
                                         :tool_calls tool-calls}))
        scoring (when (contains? #{"success" "failure" nil} (:outcome result))
                  (score-answer task (or (:answer result) {})
                                {:current_snapshot_id current_snapshot_id
                                 :arm (:arm attempt)}))
        {:keys [outcome feedback_reason not_applicable_reason]}
        (outcome-from-result task result scoring violation)
        matrix-rows (mapv (fn [turn]
                            (bu/matrix-row {:run run :attempt attempt :turn turn}))
                          (:turns result))
        usage-totals (bu/aggregate-attempt-usage matrix-rows)
        completed (assoc attempt
                         :outcome outcome
                         :not_applicable_reason not_applicable_reason)
        payload {:benchmark_run_id (:benchmark_run_id run)
                 :suite_version (:suite_version run)
                 :harness_version harness-version
                 :task_id (:task_id task)
                 :task_type (:task_type task)
                 :task_attempt_id (:task_attempt_id attempt)
                 :arm (:arm attempt)
                 :arm_policy_id (:arm_policy_id attempt)
                 :arm_policy_bundle_id arm-policy-bundle-id
                 :task_prompt_policy_id task-prompt-policy-id
                 :execution_budget_policy_id execution-budget-policy-id
                 :cache_protocol_id cache-protocol-id
                 :price_schedule_id bu/price-schedule-id
                 :repo_key (:repo_key run)
                 :repo_revision (:repo_revision run)
                 :dirty_state (:dirty_state run)
                 :sequence_index (:sequence_index attempt)
                 :seed (:seed attempt)
                 :agent_id (:agent_id attempt)
                 :agent_build_id (:agent_build_id attempt)
                 :evaluated_provider (:evaluated_provider attempt)
                 :evaluated_api_surface (:evaluated_api_surface attempt)
                 :evaluated_model (:evaluated_model attempt)
                 :evaluated_model_revision (:evaluated_model_revision attempt)
                 :evaluated_service_tier (:evaluated_service_tier attempt)
                 :benchmark_outcome outcome
                 :not_applicable_reason not_applicable_reason
                 :policy_violation violation
                 :scoring scoring
                 :stale_snapshot_reuse (boolean (:stale_snapshot_reuse scoring))
                 :missing_snapshot_evidence (boolean (:missing_snapshot_evidence scoring))
                 :excess_context_cost (boolean (:excess_context_cost scoring))
                 :wall_clock_ms wall-clock-ms
                 :measured_wall_clock_ms measured-ms
                 :workspace_isolated (boolean (:isolated workspace))
                 :usage_matrix matrix-rows
                 :usage_totals usage-totals}
        feedback {:surface feedback-surface
                  :operation feedback-operation
                  :session_id (:benchmark_run_id run)
                  :task_id (:task_attempt_id attempt)
                  :actor_id (:agent_id attempt)
                  :trace_id (get-in context [:trace :trace_id])
                  :request_id (get-in context [:trace :request_id])
                  :root_path_hash (usage/hash-root-path (:repo_path run))
                  :feedback_outcome outcome
                  :feedback_reason feedback_reason
                  :confidence_level (get-in result [:answer :confidence_level])
                  :retrieval_issue_codes (:retrieval_issue_codes scoring)
                  :ground_truth_unit_ids (get-in task [:ground_truth :unit_ids])
                  :ground_truth_paths (get-in task [:ground_truth :required_paths])
                  :payload (merge payload (tool-call-summary tool-calls))}]
    (when sink
      (when-not (usage/record-feedback! sink feedback)
        (throw (ex-info "Usage metrics sink refused a benchmark attempt outcome"
                        {:error_code "benchmark_feedback_not_recorded"
                         :task_attempt_id (:task_attempt_id attempt)}))))
    (assoc completed
           :feedback feedback
           :scoring scoring
           :policy_violation violation
           :usage_totals usage-totals
           :usage_matrix matrix-rows
           :wall_clock_ms wall-clock-ms)))

(defn run-task!
  "Run every registered arm of one task, in seeded arm order."
  [{:keys [run task runner sink seed evaluated agent sequence_index isolate_workspace
           current_snapshot_id]
    :or {sequence_index 0 isolate_workspace false}}]
  (let [arms (arm-order (or (:arms task) ["A" "B" "C" "D"]) seed)]
    ;; A task mutation must never be applied to the source checkout, so a task
    ;; that declares one cannot run without workspace isolation. Skipping the
    ;; mutation instead would silently turn a freshness case into an ordinary
    ;; retrieval case.
    (when (and (:workspace_mutation task) (not isolate_workspace))
      (throw (ex-info "Task declares a workspace mutation but isolation is disabled"
                      {:error_code "benchmark_mutation_requires_isolated_workspace"
                       :task_id (:task_id task)})))
    (loop [[arm & more] arms
           index sequence_index
           acc []]
      (if (nil? arm)
        acc
        (let [attempt (new-task-attempt run task arm
                                        (merge evaluated agent
                                               {:seed seed :sequence_index index}))
              workspace (if isolate_workspace
                          (prepare-attempt-workspace!
                           {:repo_path (:repo_path run)
                            :repo_revision (:repo_revision run)})
                          {:workspace_path (:repo_path run) :isolated false})
              mutation (try
                         (when isolate_workspace
                           (apply-workspace-mutation! (:workspace_path workspace)
                                                      (:workspace_mutation task)))
                         (catch Exception e
                           (when (:isolated workspace)
                             (cleanup-workspace! (:workspace_path workspace)))
                           (throw e)))
              completed (try
                          (run-attempt! {:run run :task task :attempt attempt
                                         :runner runner :sink sink
                                         :workspace workspace
                                         :current_snapshot_id current_snapshot_id})
                          (finally
                            (when (:isolated workspace)
                              (cleanup-workspace! (:workspace_path workspace)))))]
          (recur more (inc index) (conj acc (assoc completed :workspace_mutation mutation))))))))

(defn run-repository!
  "Run every task of one repository through all registered arms."
  [{:keys [suite repo_key repo_path runner sink seed evaluated agent isolate_workspace
           task_ids current_snapshot_id]
    :or {seed 1 isolate_workspace false}}]
  (let [validation (suite/validate-suite suite)
        _ (when-not (:valid? validation)
            (throw (ex-info "Benchmark task suite is invalid"
                            {:error_code "benchmark_suite_invalid"
                             :violations (:violations validation)})))
        run (new-benchmark-run {:suite suite :repo_key repo_key :repo_path repo_path})
        selected (cond->> (filter (fn [task] (= repo_key (:repo_key task))) (:tasks suite))
                   (seq task_ids) (filter (fn [task] (contains? (set task_ids) (:task_id task)))))]
    {:run run
     :attempts (loop [[task & more] selected
                      index 0
                      acc []]
                 (if (nil? task)
                   acc
                   (let [attempts (run-task! {:run run :task task :runner runner :sink sink
                                              :seed seed :evaluated evaluated :agent agent
                                              :sequence_index index
                                              :isolate_workspace isolate_workspace
                                              :current_snapshot_id current_snapshot_id})]
                     (recur more (+ index (count attempts)) (into acc attempts)))))}))

(defn run-suite!
  "Run the whole suite, one `BenchmarkRun` per repository.

   Each repository produces its own run identity because `BenchmarkRun` binds
   one repository revision. Runs stay poolable across repositories through
   their shared suite version, arm-policy bundle, cache protocol, and price
   schedule."
  [{:keys [suite] :as opts}]
  (let [suite (or suite (suite/validated-suite))
        repo-keys (->> (:tasks suite) (map :repo_key) distinct vec)]
    (mapv (fn [repo-key]
            (run-repository! (assoc opts :suite suite :repo_key repo-key)))
          repo-keys)))
