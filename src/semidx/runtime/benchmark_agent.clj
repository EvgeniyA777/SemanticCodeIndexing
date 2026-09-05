(ns semidx.runtime.benchmark-agent
  "Live evaluated-model arm runner for the retrieval value benchmark
   (plans/020, the runner left open by Stage 2).

   The harness owns identity, policy, scoring, and accounting. This namespace
   owns only the thing it could not own: an actual agent that reads a repository
   under one arm policy and produces an answer, driven by a real provider model.

   Three rules keep the measurement honest:

   - The agent is offered exactly the tools its arm allows, so a policy breach
     needs a hallucinated function name rather than an available one; if it
     happens anyway the call is refused, still reported, and the harness audit
     fails the attempt.
   - An arm that cannot be run competently is refused before any provider call:
     a lexical arm without `rg` would be a strawman baseline, and Arm C without
     a language server is the preregistered `not_applicable` case.
   - Cost-bearing facts are recorded by the runner, not self-reported by the
     model: raw provider usage per turn, the snapshot actually retrieved, and
     the context tokens actually consumed."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [semidx.core :as sci]
            [semidx.runtime.benchmark-harness :as harness])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; Identities and bounds
;; ---------------------------------------------------------------------------

(def agent-id "semidx-benchmark-agent")
(def agent-build-id "benchmark_agent_v1")
(def adapter-id "gemini-generate-content")
(def api-base "https://generativelanguage.googleapis.com/v1beta")

(def limits
  {:max_model_turns 40
   :view_lines 200
   :max_view_lines 400
   :grep_results 60
   :tool_output_chars 6000
   :focus_units 20
   :provider_timeout_ms 120000
   :bash_timeout_ms 60000})

(def semidx-tools #{"resolve_context" "expand_context" "fetch_context_detail"})
(def lsp-tools #{"lsp_definition" "lsp_references"})

;; ---------------------------------------------------------------------------
;; Tool declarations offered to the model
;; ---------------------------------------------------------------------------

(def tool-declarations
  {"resolve_context"
   {:name "resolve_context"
    :description "semidx staged retrieval: resolve a compact, ranked selection of code units for an intent. Returns selection_id and snapshot_id for the follow-up stages."
    :parameters {:type "OBJECT"
                 :properties {:details {:type "STRING"
                                        :description "What you are trying to understand, in one or two sentences."}
                              :purpose {:type "STRING"
                                        :description "One of: code_understanding, change_impact, edit_preparation, test_targeting, review_support, bug_investigation."}
                              :paths {:type "ARRAY" :items {:type "STRING"}
                                      :description "Optional path targets."}
                              :symbols {:type "ARRAY" :items {:type "STRING"}
                                        :description "Optional symbol targets."}
                              :modules {:type "ARRAY" :items {:type "STRING"}
                                        :description "Optional module targets."}}
                 :required ["details"]}}

   "expand_context"
   {:name "expand_context"
    :description "Widen the current semidx selection with skeletons and impact hints."
    :parameters {:type "OBJECT" :properties {} :required []}}

   "fetch_context_detail"
   {:name "fetch_context_detail"
    :description "Fetch raw code and diagnostics for the current semidx selection."
    :parameters {:type "OBJECT"
                 :properties {:unit_ids {:type "ARRAY" :items {:type "STRING"}
                                         :description "Optional subset of unit ids from the selection."}}
                 :required []}}

   "grep_search"
   {:name "grep_search"
    :description "Search the repository with a regular expression (ripgrep). Returns matching file:line entries."
    :parameters {:type "OBJECT"
                 :properties {:pattern {:type "STRING" :description "Regular expression."}
                              :path {:type "STRING" :description "Optional path to search under."}
                              :glob {:type "STRING" :description "Optional file glob, for example *.zig."}}
                 :required ["pattern"]}}

   "list_dir"
   {:name "list_dir"
    :description "List the entries of a directory in the repository."
    :parameters {:type "OBJECT"
                 :properties {:path {:type "STRING" :description "Directory path relative to the repository root."}}
                 :required []}}

   "view_file"
   {:name "view_file"
    :description "Read a bounded window of a file."
    :parameters {:type "OBJECT"
                 :properties {:path {:type "STRING" :description "File path relative to the repository root."}
                              :offset {:type "INTEGER" :description "First line to read, 1-based."}
                              :limit {:type "INTEGER" :description "How many lines to read."}}
                 :required ["path"]}}

   "lsp_definition"
   {:name "lsp_definition"
    :description "Ask the language server for the definition of a symbol at a position."
    :parameters {:type "OBJECT"
                 :properties {:path {:type "STRING"} :line {:type "INTEGER"} :character {:type "INTEGER"}}
                 :required ["path" "line" "character"]}}

   "lsp_references"
   {:name "lsp_references"
    :description "Ask the language server for references to a symbol at a position."
    :parameters {:type "OBJECT"
                 :properties {:path {:type "STRING"} :line {:type "INTEGER"} :character {:type "INTEGER"}}
                 :required ["path" "line" "character"]}}

   "bash"
   {:name "bash"
    :description "Run a shell command in the repository root."
    :parameters {:type "OBJECT"
                 :properties {:command {:type "STRING" :description "Shell command."}}
                 :required ["command"]}}})

(def answer-instructions
  (str "Work with the tools you were given until you can answer, then reply with a single JSON object and no other text:\n"
       "{\"paths\": [\"repo/relative/path\"], \"symbols\": [\"Symbol\"], \"facts\": [\"fact\"], "
       "\"answer_text\": \"short prose answer\", \"confidence_level\": \"high|medium|low\"}\n"
       "List every file path your answer depends on in \"paths\". Put named declarations in \"symbols\" "
       "and any other required detail in \"facts\". Do not wrap the JSON in code fences."))

;; ---------------------------------------------------------------------------
;; Provider client
;; ---------------------------------------------------------------------------

(defn- http-post-json!
  [{:keys [url headers body timeout_ms]}]
  (let [timeout (long (or timeout_ms (:provider_timeout_ms limits)))
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis timeout))
                   (.build))
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout))
                    (.header "Content-Type" "application/json")
                    (#(reduce (fn [builder [k v]] (.header builder (str k) (str v))) % headers))
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        text (.body response)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Provider request failed"
                      {:error_code "benchmark_agent_provider_error"
                       :status status
                       :body (subs (str text) 0 (min 800 (count (str text))))})))
    (json/read-str text :key-fn keyword)))

(defn gemini-generate-content
  "Default provider call for the `gemini-generate-content` API surface.

   Returns the parsed response, whose `usageMetadata` is the raw usage the
   benchmark price schedule is defined against."
  [{:keys [model api_key contents tools system_instruction timeout_ms]}]
  (when (str/blank? (str api_key))
    (throw (ex-info "Evaluated provider API key is missing"
                    {:error_code "benchmark_agent_missing_api_key"})))
  (http-post-json!
   {:url (str api-base "/models/" model ":generateContent")
    :headers {"x-goog-api-key" api_key}
    :timeout_ms timeout_ms
    :body (json/write-str
           (cond-> {:contents contents}
             (seq tools) (assoc :tools [{:functionDeclarations tools}])
             system_instruction (assoc :systemInstruction
                                       {:parts [{:text system_instruction}]})))}))

;; ---------------------------------------------------------------------------
;; Workspace-bounded helpers
;; ---------------------------------------------------------------------------

(defn- bounded-text [text]
  (let [text (str text)
        limit (:tool_output_chars limits)]
    (if (> (count text) limit)
      (str (subs text 0 limit) "\n... output truncated at " limit " characters")
      text)))

(defn- workspace-file
  "Resolve `path` inside the workspace, refusing anything that escapes it."
  [workspace path]
  (let [root (.getCanonicalFile (io/file (str workspace)))
        target (.getCanonicalFile (io/file root (str (or path "."))))]
    (when-not (str/starts-with? (.getPath target) (.getPath root))
      (throw (ex-info "Path escapes the attempt workspace"
                      {:error_code "benchmark_agent_path_outside_workspace"
                       :path (str path)})))
    target))

(defn- estimate-tokens [text]
  (quot (count (str text)) 4))

;; ---------------------------------------------------------------------------
;; Lexical and shell tools
;; ---------------------------------------------------------------------------

(defn- ripgrep-available? []
  (try
    (zero? (:exit (shell/sh "rg" "--version")))
    (catch Exception _ false)))

(defn- tool-grep-search [workspace {:keys [pattern path glob]}]
  (let [target (workspace-file workspace (or path "."))
        args (cond-> ["rg" "--line-number" "--no-heading" "--color" "never"
                      "--max-count" "5" "-m" "5"]
               glob (conj "--glob" (str glob))
               true (conj (str pattern) (.getPath target)))
        result (apply shell/sh (concat args [:dir (str workspace)]))
        lines (->> (str/split-lines (str (:out result)))
                   (remove str/blank?)
                   (take (:grep_results limits))
                   vec)]
    (if (and (not (zero? (:exit result))) (empty? lines))
      {:matches [] :note (if (str/blank? (str (:err result)))
                           "no matches"
                           (bounded-text (:err result)))}
      {:matches (mapv (fn [line] (str/replace line (str workspace "/") "")) lines)
       :truncated (>= (count lines) (:grep_results limits))})))

(defn- tool-list-dir [workspace {:keys [path]}]
  (let [target (workspace-file workspace (or path "."))]
    (if-not (.isDirectory target)
      {:error (str "not a directory: " path)}
      {:entries (->> (.listFiles target)
                     (map (fn [f] (str (.getName f) (when (.isDirectory f) "/"))))
                     sort
                     (take 200)
                     vec)})))

(defn- tool-view-file [workspace {:keys [path offset limit]}]
  (let [target (workspace-file workspace path)]
    (if-not (.isFile target)
      {:error (str "not a file: " path)}
      (let [start (max 1 (long (or offset 1)))
            span (min (long (or limit (:view_lines limits))) (:max_view_lines limits))
            lines (with-open [rdr (io/reader target)]
                    (->> (line-seq rdr)
                         (drop (dec start))
                         (take span)
                         vec))]
        {:path (str path)
         :first_line start
         :lines (count lines)
         :content (bounded-text (str/join "\n" lines))}))))

(defn- denylisted-command? [denylist command]
  (let [normalized (str/lower-case (str command))]
    (boolean (some (fn [prefix]
                     (let [prefix (str/lower-case (str prefix))]
                       (or (str/starts-with? normalized prefix)
                           (str/includes? normalized (str " " prefix))
                           (str/includes? normalized (str "|" prefix))
                           (str/includes? normalized (str "| " prefix)))))
                   denylist))))

(defn- tool-bash [workspace denylist {:keys [command]}]
  (if (denylisted-command? denylist command)
    {:error "command uses a semantic-navigation tool that this arm forbids"
     :refused true}
    (let [run (future (shell/sh "sh" "-c" (str command) :dir (str workspace)))
          result (deref run (:bash_timeout_ms limits) ::timeout)]
      (if (= ::timeout result)
        (do (future-cancel run)
            {:error "command timed out"})
        {:exit (:exit result)
         :stdout (bounded-text (:out result))
         :stderr (bounded-text (:err result))}))))

;; ---------------------------------------------------------------------------
;; semidx staged tools
;; ---------------------------------------------------------------------------

(def ^:private valid-purposes
  #{"code_understanding" "change_impact" "edit_preparation"
    "test_targeting" "review_support" "bug_investigation"})

(defn- retrieval-query [context {:keys [details purpose paths symbols modules]}]
  (let [details (str details)
        targets (cond-> {}
                  (seq paths) (assoc :paths (vec (take 20 paths)))
                  (seq symbols) (assoc :symbols (vec (take 20 symbols)))
                  (seq modules) (assoc :modules (vec (take 20 modules))))]
    {:api_version "1.0"
     :schema_version "1.0"
     :intent {:purpose (if (contains? valid-purposes (str purpose))
                         (str purpose)
                         "code_understanding")
              :details details}
     ;; A query must carry at least one target. The intent text is the honest
     ;; fallback when the model named none, mirroring the MCP shorthand path.
     :targets (if (seq targets) targets {:diff_summary details})
     :constraints {:token_budget 4000 :freshness "current_snapshot"}
     :hints {}
     :options {:include_tests true :include_impact_hints true}
     :trace (:trace context)}))

(defn- compact-focus [units]
  (->> units
       (take (:focus_units limits))
       (mapv (fn [unit] (select-keys unit [:unit_id :symbol :path :kind :confidence_tier])))))

(defn- tool-resolve-context [state context args]
  (let [index @(:index state)
        result (sci/resolve-context index (retrieval-query context args))]
    {:state (assoc state
                   :selection {:selection_id (:selection_id result)
                               :snapshot_id (:snapshot_id result)}
                   :snapshot_id (:snapshot_id result)
                   :context_tokens (+ (:context_tokens state)
                                      (long (or (get-in result [:budget_summary :estimated_tokens]) 0))))
     :result {:selection_id (:selection_id result)
              :snapshot_id (:snapshot_id result)
              :confidence_level (:confidence_level result)
              :result_status (:result_status result)
              :focus (compact-focus (:focus result))
              :next_step (get-in result [:next_step :recommended_action])}}))

(defn- require-selection [state]
  (or (:selection state)
      (throw (ex-info "No selection yet; call resolve_context first"
                      {:error_code "benchmark_agent_no_selection"}))))

(defn- tool-expand-context [state _context _args]
  (let [index @(:index state)
        selection (require-selection state)
        result (sci/expand-context index selection)]
    {:state (update state :context_tokens
                    + (long (or (get-in result [:budget_summary :returned_tokens]) 0)))
     :result {:selection_id (:selection_id result)
              :snapshot_id (:snapshot_id result)
              :result_status (:result_status result)
              :skeletons (->> (:skeletons result)
                              (take (:focus_units limits))
                              (mapv (fn [s] (select-keys s [:unit_id :path :symbol :signature]))))
              :impact_hints (:impact_hints result)}}))

(defn- tool-fetch-context-detail [state _context {:keys [unit_ids]}]
  (let [index @(:index state)
        selection (require-selection state)
        result (sci/fetch-context-detail index (cond-> selection
                                                 (seq unit_ids) (assoc :unit_ids (vec unit_ids))))
        packet (:context_packet result)]
    {:state (update state :context_tokens
                    + (long (or (get-in packet [:budget :returned_tokens]) 0)))
     :result {:selection_id (:selection_id result)
              :snapshot_id (:snapshot_id result)
              :confidence (get-in packet [:confidence :level])
              :units (->> (:relevant_units packet)
                          (take (:focus_units limits))
                          (mapv (fn [unit]
                                  (-> (select-keys unit [:unit_id :path :symbol :kind])
                                      (assoc :code (bounded-text (or (:code unit)
                                                                     (:source unit)
                                                                     (:skeleton unit))))))))}}))

;; ---------------------------------------------------------------------------
;; Tool dispatch
;; ---------------------------------------------------------------------------

(defn- lsp-unavailable-result [tool-id]
  {:error (str tool-id " is unavailable: no language server is configured for this repository")})

(defn execute-tool
  "Execute one model-requested tool call under the arm policy.

   Returns `{:state .. :result ..}`. A tool outside the arm allowlist is refused
   here and still reported to the harness, so the audit sees the breach instead
   of the runner quietly absorbing it."
  [state context tool-id args]
  (let [workspace (:workspace_path context)
        allowed (set (:allowed_tools context))]
    (cond
      (not (contains? allowed tool-id))
      {:state state
       :result {:error (str tool-id " is not allowed for arm " (:arm context))
                :refused true}}

      (= "resolve_context" tool-id) (tool-resolve-context state context args)
      (= "expand_context" tool-id) (tool-expand-context state context args)
      (= "fetch_context_detail" tool-id) (tool-fetch-context-detail state context args)

      (= "grep_search" tool-id)
      (let [result (tool-grep-search workspace args)]
        {:state (update state :context_tokens + (estimate-tokens (pr-str result)))
         :result result})

      (= "list_dir" tool-id)
      (let [result (tool-list-dir workspace args)]
        {:state (update state :context_tokens + (estimate-tokens (pr-str result)))
         :result result})

      (= "view_file" tool-id)
      (let [result (tool-view-file workspace args)]
        {:state (update state :context_tokens + (estimate-tokens (:content result)))
         :result result})

      (contains? lsp-tools tool-id)
      {:state state :result (lsp-unavailable-result tool-id)}

      (= "bash" tool-id)
      (let [result (tool-bash workspace (:command_denylist context) args)]
        {:state (update state :context_tokens + (estimate-tokens (pr-str result)))
         :result result})

      :else
      {:state state :result {:error (str "unknown tool " tool-id)}})))

;; ---------------------------------------------------------------------------
;; Answer parsing
;; ---------------------------------------------------------------------------

(defn parse-answer
  "Parse the model's final JSON answer.

   Returns nil when the text is not a usable answer object; the caller reports
   that as an attempt error rather than inventing an empty answer."
  [text]
  (let [text (str/trim (str text))
        stripped (-> text
                     (str/replace #"(?s)^```(?:json)?\s*" "")
                     (str/replace #"(?s)\s*```$" ""))
        start (str/index-of stripped "{")
        end (str/last-index-of stripped "}")]
    (when (and start end (< start end))
      (try
        (let [parsed (json/read-str (subs stripped start (inc end)) :key-fn keyword)]
          (when (map? parsed)
            {:paths (vec (:paths parsed))
             :symbols (vec (:symbols parsed))
             :facts (vec (:facts parsed))
             :answer_text (str (:answer_text parsed))
             :confidence_level (or (:confidence_level parsed) "medium")}))
        (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; Attempt execution
;; ---------------------------------------------------------------------------

(defn- arm-index [context opts]
  (delay
    (sci/create-index (cond-> {:root_path (:workspace_path context)
                               :usage_context (:usage_context context)}
                        (:usage_metrics opts) (assoc :usage_metrics (:usage_metrics opts))))))

(defn- preflight
  "Refuse an arm that cannot be run competently, before any provider call."
  [context]
  (let [allowed (set (:allowed_tools context))]
    (cond
      (and (seq (filter allowed lsp-tools))
           (str/blank? (str (System/getenv "SEMIDX_BENCH_LSP_COMMAND"))))
      {:outcome "not_applicable"
       :not_applicable_reason (str "no language server configured for arm " (:arm context)
                                   "; set SEMIDX_BENCH_LSP_COMMAND to enable it")}

      (and (contains? allowed "grep_search") (not (ripgrep-available?)))
      {:outcome "error"
       :error_reason "ripgrep_unavailable: a lexical arm cannot be run competently without rg"}

      (str/blank? (str (get-in context [:attempt :evaluated_model_revision])))
      {:outcome "error"
       :error_reason "attempt has no evaluated_model_revision; cost could not be priced"}

      :else nil)))

(defn- function-calls [candidate]
  (->> (get-in candidate [:content :parts])
       (keep :functionCall)
       vec))

(defn- candidate-text [candidate]
  (->> (get-in candidate [:content :parts])
       (keep :text)
       (str/join "\n")))

(defn- turn-record [index response candidate call-count]
  {:turn_index index
   :adapter_id adapter-id
   :raw_usage (or (:usageMetadata response) {})
   :response_meta {:stop_reason (or (:finishReason candidate) "unknown")
                   :tool_call_count call-count
                   :output_chars (count (candidate-text candidate))}
   :tool_charges_usd 0})

(defn run-attempt
  "Run one benchmark attempt with a live evaluated model.

   `opts` may carry `:generate-content` (injected provider call), `:api_key`,
   and `:usage_metrics` (the sink the harness writes to, so Arm A's semidx
   calls are tagged to this attempt)."
  [context opts]
  (let [started (System/currentTimeMillis)
        budget (:execution_budget context)
        max-tool-calls (long (or (:max_tool_calls budget) 30))
        deadline (+ started (* 1000 (long (or (:max_wall_clock_seconds budget) 300))))
        generate (or (:generate-content opts) gemini-generate-content)
        api-key (or (:api_key opts) (System/getenv "GEMINI_API_KEY"))
        model (get-in context [:attempt :evaluated_model_revision])
        declarations (vec (keep tool-declarations (:allowed_tools context)))
        system-instruction (str (:prompt context) "\n\n" answer-instructions)]
    (if-let [refusal (preflight context)]
      (assoc refusal
             :wall_clock_ms (- (System/currentTimeMillis) started)
             :turns []
             :tool_calls [])
      (loop [state {:index (arm-index context opts)
                    :selection nil
                    :snapshot_id nil
                    :context_tokens 0}
             contents [{:role "user"
                        :parts [{:text (str "Task: " (:prompt context)
                                            "\n\nRepository root: " (:workspace_path context))}]}]
             turns []
             tool-calls []
             turn-index 0]
        (let [budget-spent? (or (>= (count tool-calls) max-tool-calls)
                                (>= (System/currentTimeMillis) deadline)
                                (>= turn-index (:max_model_turns limits)))
              response (try
                         (generate {:model model
                                    :api_key api-key
                                    :contents contents
                                    :tools (when-not budget-spent? declarations)
                                    :system_instruction system-instruction})
                         (catch Exception e
                           {::error (or (.getMessage e) (str (class e)))}))]
          (if-let [error (::error response)]
            {:outcome "error"
             :error_reason (str "provider_error: " error)
             :wall_clock_ms (- (System/currentTimeMillis) started)
             :turns turns
             :tool_calls tool-calls}
            (let [candidate (first (:candidates response))
                  calls (function-calls candidate)
                  turns (conj turns (turn-record turn-index response candidate (count calls)))]
              (if (and (seq calls) (not budget-spent?))
                (let [{:keys [state* responses recorded]}
                      (reduce (fn [acc call]
                                (let [tool-id (str (:name call))
                                      args (or (:args call) {})
                                      {:keys [state result]}
                                      (try
                                        (execute-tool (:state* acc) context tool-id args)
                                        (catch Exception e
                                          {:state (:state* acc)
                                           :result {:error (or (.getMessage e) (str (class e)))}}))]
                                  (-> acc
                                      (assoc :state* state)
                                      (update :responses conj
                                              {:functionResponse
                                               {:name tool-id
                                                :response {:result (bounded-text (json/write-str result))}}})
                                      (update :recorded conj
                                              (cond-> {:tool_id tool-id}
                                                (:command args) (assoc :command (str (:command args))))))))
                              {:state* state :responses [] :recorded []}
                              calls)]
                  (recur state*
                         (conj contents
                               (:content candidate)
                               {:role "user" :parts responses})
                         turns
                         (into tool-calls recorded)
                         (inc turn-index)))
                (let [answer (parse-answer (candidate-text candidate))]
                  (if answer
                    {:outcome "success"
                     :wall_clock_ms (- (System/currentTimeMillis) started)
                     :turns turns
                     :tool_calls tool-calls
                     ;; The snapshot and the context cost are facts the runner
                     ;; observed, never values the model reports about itself.
                     :answer (assoc answer
                                    :snapshot_id (:snapshot_id state)
                                    :context_tokens (:context_tokens state))}
                    {:outcome "error"
                     :error_reason (if budget-spent?
                                     "execution_budget_exhausted_without_answer"
                                     "agent_answer_unparseable")
                     :wall_clock_ms (- (System/currentTimeMillis) started)
                     :turns turns
                     :tool_calls tool-calls}))))))))))

;; ---------------------------------------------------------------------------
;; Runner and CLI entry point
;; ---------------------------------------------------------------------------

(defrecord LiveArmRunner [opts]
  harness/ArmRunner
  (run-arm-attempt [_ context] (run-attempt context opts)))

(defn live-arm-runner
  "In-process runner backed by a live evaluated model.

   Pass `:usage_metrics` so Arm A's semidx calls are recorded against this
   attempt, and `:generate-content` to drive it from a stub in tests."
  ([] (live-arm-runner {}))
  ([opts] (->LiveArmRunner opts)))

(defn -main
  "Out-of-process entry point matching the harness `process-arm-runner`
   contract: attempt context as JSON on stdin, attempt result as JSON on
   stdout."
  [& _args]
  (let [context (json/read-str (slurp *in*) :key-fn keyword)
        jdbc-url (System/getenv "SEMIDX_USAGE_METRICS_JDBC_URL")
        opts (cond-> {}
               jdbc-url (assoc :usage_metrics
                               (sci/postgres-usage-metrics
                                {:jdbc-url jdbc-url
                                 :user (System/getenv "SEMIDX_USAGE_METRICS_DB_USER")
                                 :password (System/getenv "SEMIDX_USAGE_METRICS_DB_PASSWORD")})))
        result (try
                 (run-attempt context opts)
                 (catch Exception e
                   {:outcome "error"
                    :error_reason (str "agent_exception: " (or (.getMessage e) (str (class e))))
                    :turns []
                    :tool_calls []}))]
    (println (json/write-str result :escape-slash false))
    (flush)
    (System/exit 0)))
