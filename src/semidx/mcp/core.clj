(ns semidx.mcp.core
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.core :as sci]
            [semidx.runtime.errors :as errors]
            [semidx.runtime.language-activation :as activation]
            [semidx.runtime.retrieval-policy :as rp]
            [semidx.runtime.usage-metrics :as usage])
  (:import [java.util UUID]))

(def default-protocol-version "2024-11-05")
(def server-name "semidx-mcp")
(def server-version "0.1.0")
(def default-max-indexes 8)
(def default-parser-opts
  {:clojure_engine :clj-kondo
   :tree_sitter_enabled false})

(def ^:private canonical-mcp-flow
  ["create_index" "repo_map" "resolve_context" "expand_context" "fetch_context_detail"])

(def ^:private mcp-first-usage-hint
  "Use semidx in MCP-first mode: call create_index, then repo_map, then continue with resolve_context -> expand_context -> fetch_context_detail. Prefer this over manual directory listing, wildcard search, or broad file reading until an MCP tool call fails.")

(def ^:private mcp-retrieval-query-schema
  {:type "object"
   :description "Structured retrieval query for compact semantic selection. Send a structured query object, not a free-form string."
   :properties {"api_version" {:type "string"
                               :description "Optional API version; defaults to 1.0 when omitted."}
                "schema_version" {:type "string"
                                  :description "Retrieval query schema version, normally 1.0."}
                "intent" {:type "object"
                          :description "Retrieval intent. Example: {\"purpose\":\"code_understanding\",\"details\":\"Find the main orchestration flow.\"}"
                          :properties {"purpose" {:type "string"
                                                  :enum ["code_understanding"
                                                         "change_impact"
                                                         "edit_preparation"
                                                         "test_targeting"
                                                         "review_support"
                                                         "bug_investigation"]}
                                       "details" {:type "string"}}
                          :required ["purpose"]
                          :additionalProperties false}
                "targets" {:type "object"
                           :description "What to retrieve around: paths, symbols, modules, tests, changed spans, or a diff summary."
                           :properties {"paths" {:type "array" :items {:type "string"}}
                                        "symbols" {:type "array" :items {:type "string"}}
                                        "modules" {:type "array" :items {:type "string"}}
                                        "tests" {:type "array" :items {:type "string"}}
                                        "changed_spans" {:type "array" :items {:type "object"}}
                                        "diff_summary" {:type "string"}}
                           :additionalProperties false}
                "constraints" {:type "object"
                               :properties {"token_budget" {:type "integer"}
                                            "snapshot_id" {:type "string"}
                                            "language_allowlist" {:type "array" :items {:type "string"}}
                                            "allowed_path_prefixes" {:type "array" :items {:type "string"}}
                                            "max_raw_code_level" {:type "string"}
                                            "freshness" {:type "string"
                                                         :enum ["current_snapshot" "allow_stale_if_flagged"]}}
                               :additionalProperties false}
                "hints" {:type "object"
                         :properties {"preferred_paths" {:type "array" :items {:type "string"}}
                                      "preferred_modules" {:type "array" :items {:type "string"}}
                                      "suspected_symbols" {:type "array" :items {:type "string"}}
                                      "focus_on_tests" {:type "boolean"}
                                      "prefer_definitions_over_callers" {:type "boolean"}
                                      "prefer_breadth_over_depth" {:type "boolean"}}
                         :additionalProperties false}
                "options" {:type "object"
                           :properties {"include_tests" {:type "boolean"}
                                        "include_impact_hints" {:type "boolean"}
                                        "allow_raw_code_escalation" {:type "boolean"}}
                           :additionalProperties false}
                "trace" {:type "object"
                         :properties {"trace_id" {:type "string"}
                                      "request_id" {:type "string"}
                                      "session_id" {:type "string"}
                                      "task_id" {:type "string"}
                                      "actor_id" {:type "string"}
                                      "agent_id" {:type "string"}}
                         :additionalProperties false}}
   :required ["intent"]
   :additionalProperties false})

(def ^:private mcp-query-default-constraints
  {:token_budget 3200
   :max_raw_code_level "enclosing_unit"
   :freshness "current_snapshot"})

(def ^:private purpose-token-budgets
  {"code_understanding" 3200
   "change_impact" 2400
   "edit_preparation" 1600
   "test_targeting" 2400
   "review_support" 3200
   "bug_investigation" 2400})

(def ^:private mcp-query-default-hints
  {:prefer_definitions_over_callers true
   :prefer_breadth_over_depth true})

(def ^:private mcp-query-default-options
  {:include_tests false
   :include_impact_hints true
   :allow_raw_code_escalation false})

(def ^:private required-query-sections
  [:schema_version :intent :targets :constraints :hints :options :trace])

(defn now-ms []
  (System/currentTimeMillis))

(defn tool-usage-context [state]
  (let [state* @state
        client-info (:client-info state*)]
    (cond-> {:surface "mcp"
             :session_id (:session_id state*)}
      (:name client-info) (assoc :actor_id (:name client-info))
      (:tenant_id state*) (assoc :tenant_id (:tenant_id state*)))))

(defn record-mcp-event! [state event]
  (when-let [sink (:usage_metrics @state)]
    (usage/safe-record-event! sink (merge (tool-usage-context state) event))))

(defn usage-fields-for-query [query]
  {:trace_id (get-in query [:trace :trace_id])
   :request_id (get-in query [:trace :request_id])
   :session_id (get-in query [:trace :session_id])
   :task_id (get-in query [:trace :task_id])
   :actor_id (or (get-in query [:trace :actor_id])
                 (get-in query [:trace :agent_id]))})

(defn with-usage-event [payload event-fields]
  (with-meta payload (merge (meta payload) {:usage_event_fields event-fields})))

(defn log! [& xs]
  (binding [*out* *err*]
    (apply println xs)
    (flush)))

(defn invalid-request
  ([message]
   (invalid-request message nil))
  ([message details]
   (throw (ex-info message {:type :invalid_request
                            :message message
                            :details details}))))

(defn index-not-found [index-id]
  (throw (ex-info "index_id not found"
                  {:type :index_not_found
                   :message "index_id not found"
                   :details {:index_id index-id}})))

(defn canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn normalize-rel-path
  ([path] (normalize-rel-path path nil))
  ([path root-path]
   (let [normalized (-> (str path)
                        (str/replace "\\" "/")
                        (str/replace #"^\./" ""))]
     (when (str/blank? normalized)
       (invalid-request "paths entries must be non-empty strings"))
     (let [normalized (if (and (str/starts-with? normalized "/") root-path)
                        (let [canon (canonical-path normalized)
                              root-canon (str root-path
                                              (when-not (str/ends-with? root-path "/") "/"))]
                          (if (str/starts-with? canon root-canon)
                            (subs canon (count root-canon))
                            normalized))
                        normalized)]
       (when (str/starts-with? normalized "/")
         (invalid-request "paths entries must be relative paths"))
       (when (re-find #"(^|/)\.\.(/|$)" normalized)
         (invalid-request "paths entries must not contain '..' traversal segments"))
       normalized))))

(defn ensure-string [value field-name]
  (when-not (string? value)
    (invalid-request (str field-name " must be a string")))
  value)

(defn ensure-map-or-nil [value field-name]
  (when (and (some? value) (not (map? value)))
    (invalid-request (str field-name " must be an object")))
  value)

(defn ensure-boolean-or-nil [value field-name]
  (when (and (some? value) (not (instance? Boolean value)))
    (invalid-request (str field-name " must be a boolean")))
  value)

(defn ensure-int-or-nil [value field-name]
  (when (and (some? value) (not (integer? value)))
    (invalid-request (str field-name " must be an integer")))
  value)

(defn ensure-string-coll [value field-name]
  (when-not (sequential? value)
    (invalid-request (str field-name " must be an array of strings")))
  (mapv (fn [entry]
          (ensure-string entry field-name))
        value))

(defn normalize-paths
  ([paths] (normalize-paths paths nil))
  ([paths root-path]
   (when (some? paths)
     (->> (ensure-string-coll paths "paths")
          (map #(normalize-rel-path % root-path))
          distinct
          vec))))

(defn normalize-unit-ids [unit-ids]
  (when (some? unit-ids)
    (->> (ensure-string-coll unit-ids "unit_ids")
         distinct
         vec)))

(defn normalize-parser-opts [parser-opts]
  (let [opts (ensure-map-or-nil parser-opts "parser_opts")]
    (if (nil? opts) default-parser-opts opts)))

(defn normalize-language-policy [language-policy]
  (let [policy (ensure-map-or-nil language-policy "language_policy")]
    (when policy
      (activation/normalize-language-policy policy))))

(defn sort-data [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (str %1) (str %2)))
          (map (fn [[k v]] [k (sort-data v)]))
          value)

    (vector? value)
    (mapv sort-data value)

    (sequential? value)
    (mapv sort-data value)

    :else value))

(defn cache-key [root-path paths parser-opts language-policy]
  (pr-str (sort-data {:root_path root-path
                      :paths paths
                      :parser_opts parser-opts
                      :language_policy language-policy})))

(defn path-within-root? [root-path candidate]
  (let [root (.toPath (io/file root-path))
        path (.toPath (io/file candidate))]
    (or (= root path)
        (.startsWith path root))))

(defn validate-root-path! [state root-path]
  (let [provided (ensure-string root-path "root_path")
        canonical (canonical-path provided)
        file (io/file canonical)]
    (when-not (.exists file)
      (invalid-request "root_path must exist"))
    (when-not (.isDirectory file)
      (invalid-request "root_path must be a directory"))
    canonical))

(defn index-summary [entry cache-hit?]
  (let [index (:index entry)]
    {:index_id (:index_id entry)
     :snapshot_id (:snapshot_id index)
     :indexed_at (:indexed_at index)
     :index_lifecycle (:index_lifecycle index)
     :root_path (:root_path entry)
     :file_count (count (:files index))
     :unit_count (count (:units index))
     :detected_languages (:detected_languages index)
     :active_languages (:active_languages index)
     :language_fingerprint (:language_fingerprint index)
     :activation_state (:activation_state index)
     :selection_hint (:selection_hint index)
     :recommended_next_step "repo_map"
     :recommended_flow canonical-mcp-flow
     :usage_hint mcp-first-usage-hint
     :cache_hit cache-hit?}))

(defn touch-index! [state index-id]
  (let [ts (now-ms)]
    (swap! state update-in [:indexes-by-id index-id]
           (fn [entry]
             (when entry
               (assoc entry :last_accessed_at ts))))
    (or (get-in @state [:indexes-by-id index-id])
        (index-not-found index-id))))

(defn remove-evicted-cache-keys [cache-index evicted-ids]
  (reduce-kv (fn [acc k v]
               (if (contains? evicted-ids v)
                 (dissoc acc k)
                 acc))
             cache-index
             cache-index))

(defn evict-excess! [state]
  (let [evicted (atom [])]
    (swap! state
           (fn [current]
             (let [entries (vals (:indexes-by-id current))
                   excess (- (count entries) (:max-indexes current))]
               (if (pos? excess)
                 (let [evicted-entries (->> entries
                                            (sort-by :last_accessed_at)
                                            (take excess)
                                            vec)
                       evicted-ids (set (map :index_id evicted-entries))]
                   (reset! evicted evicted-entries)
                   (-> current
                       (update :indexes-by-id #(apply dissoc % evicted-ids))
                       (update :cache-key->index-id remove-evicted-cache-keys evicted-ids)))
                 current))))
    (when (seq @evicted)
      (record-mcp-event!
       state
       {:operation "cache_eviction"
        :status "success"
        :payload {:evicted_index_count (count @evicted)
                  :evicted_index_ids (mapv :index_id @evicted)}}))))

(defn store-index! [state cache-key-value root-path paths parser-opts language-policy index]
  (let [ts (now-ms)
        index-id (str (UUID/randomUUID))
        entry {:index_id index-id
               :cache_key cache-key-value
               :root_path root-path
               :paths paths
               :parser_opts parser-opts
               :language_policy language-policy
               :index index
               :created_at ts
               :last_accessed_at ts}]
    (swap! state
           (fn [current]
             (-> current
                 (assoc-in [:indexes-by-id index-id] entry)
                 (assoc-in [:cache-key->index-id cache-key-value] index-id))))
    (evict-excess! state)
    (or (get-in @state [:indexes-by-id index-id])
        (index-not-found index-id))))

(defn find-cached-entry [state cache-key-value]
  (when-let [index-id (get-in @state [:cache-key->index-id cache-key-value])]
    (when-let [entry (get-in @state [:indexes-by-id index-id])]
      (touch-index! state (:index_id entry)))))

(defn resolve-entry! [state args]
  (let [index-id (ensure-string (:index_id args) "index_id")]
    (touch-index! state index-id)))

(defn tool-create-index [state args]
  (when-not (map? args)
    (invalid-request "create_index arguments must be an object"))
  (let [root-path (validate-root-path! state (or (:root_path args) "."))
        paths (normalize-paths (:paths args) root-path)
        parser-opts (normalize-parser-opts (:parser_opts args))
        language-policy (normalize-language-policy (:language_policy args))
        force-rebuild (boolean (ensure-boolean-or-nil (:force_rebuild args) "force_rebuild"))
        cache-key-value (cache-key root-path paths parser-opts language-policy)]
    (if-let [entry (when-not force-rebuild
                     (find-cached-entry state cache-key-value))]
      (with-usage-event
        (index-summary entry true)
        {:root_path_hash (usage/hash-root-path root-path)
         :file_count (count (get-in entry [:index :files]))
         :unit_count (count (get-in entry [:index :units]))
         :cache_hit true
         :payload {:force_rebuild force-rebuild
                   :paths_count (count paths)
                   :snapshot_id (get-in entry [:index :snapshot_id])}})
      (let [index (sci/create-index {:root_path root-path
                                     :paths paths
                                     :parser_opts parser-opts
                                     :language_policy language-policy
                                     :policy_registry (:policy_registry @state)
                                     :usage_metrics (:usage_metrics @state)
                                     :usage_context (tool-usage-context state)
                                     :selection_cache (:selection_cache @state)
                                     :suppress_usage_metrics true})
            entry (store-index! state cache-key-value root-path paths parser-opts language-policy index)]
        (with-usage-event
          (index-summary entry false)
          {:root_path_hash (usage/hash-root-path root-path)
           :file_count (count (get-in entry [:index :files]))
           :unit_count (count (get-in entry [:index :units]))
           :cache_hit false
           :payload {:force_rebuild force-rebuild
                     :paths_count (count paths)
                     :snapshot_id (get-in entry [:index :snapshot_id])}})))))

(defn project-context-for-entry [entry]
  {:detected_languages (get-in entry [:index :detected_languages])
   :active_languages (get-in entry [:index :active_languages])
   :supported_languages (get-in entry [:index :supported_languages])
   :language_fingerprint (get-in entry [:index :language_fingerprint])
   :activation_state (get-in entry [:index :activation_state])
   :selection_hint (get-in entry [:index :selection_hint])})

(defn add-next-step-guidance
  [payload next-step]
  (assoc payload
         :recommended_next_step next-step
         :recommended_flow canonical-mcp-flow
         :usage_hint mcp-first-usage-hint))

(defn- compact-continuation
  ([selection-id snapshot-id next-tool]
   (compact-continuation selection-id snapshot-id next-tool nil))
  ([selection-id snapshot-id next-tool query-summary]
   (cond-> {:continuation_mode "selection_artifact"
            :selection_id selection-id
            :snapshot_id snapshot-id
            :next_tool next-tool}
     query-summary (assoc :query_summary query-summary))))

(defn- canonical-query-shape? [query]
  (and (map? query)
       (string? (:schema_version query))
       (map? (:intent query))
       (map? (:targets query))
       (map? (:constraints query))
       (map? (:hints query))
       (map? (:options query))
       (map? (:trace query))))

(defn- fill-trace-defaults [state trace]
  (let [trace* (or trace {})
        actor-id (or (:actor_id trace*)
                     (:agent_id trace*)
                     (some-> @state :client-info :name)
                     "mcp_client")]
    (cond-> trace*
      (not (:trace_id trace*)) (assoc :trace_id (str (UUID/randomUUID)))
      (not (:request_id trace*)) (assoc :request_id (str "mcp-shorthand-" (UUID/randomUUID)))
      (not (:actor_id trace*)) (assoc :actor_id actor-id))))

(defn- shorthand-intent->map [intent]
  (cond
    (string? intent)
    {:purpose "code_understanding"
     :details intent}

    (map? intent)
    (cond-> {:purpose (or (:purpose intent) "code_understanding")}
      (:details intent) (assoc :details (:details intent)))

    :else nil))

(defn- normalized-query-summary [query]
  {:purpose (get-in query [:intent :purpose])
   :details (get-in query [:intent :details])
   :target_keys (->> (keys (:targets query))
                     (map name)
                     sort
                     vec)
   :token_budget (get-in query [:constraints :token_budget])
   :include_tests (boolean (get-in query [:options :include_tests]))})

(defn- normalize-mcp-query [state query]
  (cond
    (canonical-query-shape? query)
    {:query query
     :query_normalized false
     :query_ingress_mode "canonical"
     :normalized_query_summary (normalized-query-summary query)}

    (map? query)
    (when-let [intent-map (shorthand-intent->map (:intent query))]
      (let [purpose (:purpose intent-map)
            adaptive-budget (get purpose-token-budgets purpose (:token_budget mcp-query-default-constraints))
            base-constraints (assoc mcp-query-default-constraints :token_budget adaptive-budget)
            normalized {:api_version (or (:api_version query) "1.0")
                        :schema_version (or (:schema_version query) "1.0")
                        :intent intent-map
                        :targets (or (:targets query) {:paths ["."]})
                        :constraints (merge base-constraints (:constraints query))
                        :hints (merge mcp-query-default-hints (:hints query))
                        :options (merge mcp-query-default-options (:options query))
                        :trace (fill-trace-defaults state (:trace query))}]
        {:query normalized
         :query_normalized true
         :query_ingress_mode "mcp_shorthand"
         :normalized_query_summary (normalized-query-summary normalized)}))

    :else nil))

(defn- minimal-retrieval-query-skeleton []
  {:api_version "1.0"
   :schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Describe the coding task or repository question here."}
   :targets {:paths ["."]}
   :constraints {:token_budget 3200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:prefer_definitions_over_callers true
           :prefer_breadth_over_depth true}
   :options {:include_tests false
             :include_impact_hints true
             :allow_raw_code_escalation false}
   :trace {:trace_id "00000000-0000-4000-8000-000000000000"
           :request_id "retry-resolve-context-001"
           :actor_id "mcp_client"}})

(defn- missing-query-sections [query]
  (->> required-query-sections
       (remove #(contains? (or query {}) %))
       vec))

(defn- invalid-field-paths [query]
  (let [q (or query {})]
    (cond-> []
      (and (contains? q :intent)
           (not (string? (:intent q)))
           (not (map? (:intent q))))
      (conj {:path "intent"
             :error "must be a string or an object with {purpose, details}"})

      (and (contains? q :intent)
           (map? (:intent q))
           (not (string? (:purpose (:intent q)))))
      (conj {:path "intent.purpose"
             :error "must be a string (e.g. \"code_understanding\", \"bug_triage\", \"refactoring\")"})

      (and (contains? q :targets)
           (not (map? (:targets q))))
      (conj {:path "targets"
             :error "must be an object with at least one of: symbols, paths, modules, tests"})

      (and (contains? q :targets)
           (map? (:targets q))
           (empty? (select-keys (:targets q) [:symbols :paths :modules :tests])))
      (conj {:path "targets"
             :error "must contain at least one of: symbols, paths, modules, tests"})

      (and (contains? q :constraints)
           (not (map? (:constraints q))))
      (conj {:path "constraints"
             :error "must be an object with token_budget, max_raw_code_level, freshness"})

      (and (contains? q :hints)
           (not (map? (:hints q))))
      (conj {:path "hints"
             :error "must be an object"})

      (and (contains? q :options)
           (not (map? (:options q))))
      (conj {:path "options"
             :error "must be an object"})

      (and (contains? q :trace)
           (not (map? (:trace q))))
      (conj {:path "trace"
             :error "must be an object with trace_id, request_id, actor_id"}))))

(defn- enrich-invalid-query [message query]
  (ex-info message
           {:type :invalid_query
            :message message
            :details {:missing_sections (missing-query-sections query)
                      :invalid_field_paths (invalid-field-paths query)
                      :minimal_query_skeleton (minimal-retrieval-query-skeleton)
                      :recommended_next_step "retry_resolve_context_with_structured_query"}}))

(defn tool-repo-map [state args]
  (when-not (map? args)
    (invalid-request "repo_map arguments must be an object"))
  (let [entry (resolve-entry! state args)
        max-files (ensure-int-or-nil (:max_files args) "max_files")
        max-modules (ensure-int-or-nil (:max_modules args) "max_modules")
        opts (cond-> {}
               (some? max-files) (assoc :max_files max-files)
               (some? max-modules) (assoc :max_modules max-modules))
        result (if (seq opts)
                 (sci/repo-map (:index entry) (merge opts {:suppress_usage_metrics true}))
                 (sci/repo-map (:index entry) {:suppress_usage_metrics true}))]
    (with-usage-event
      (-> result
          (assoc :index_id (:index_id entry))
          (add-next-step-guidance "resolve_context"))
      {:root_path_hash (usage/hash-root-path (:root_path entry))
       :payload {:index_id (:index_id entry)
                 :snapshot_id (:snapshot_id result)
                 :file_count (count (:files result))
                 :module_count (count (:modules result))}})))

(defn tool-resolve-context [state args]
  (when-not (map? args)
    (invalid-request "resolve_context arguments must be an object"))
  (let [entry (resolve-entry! state args)
        intent-promoted? (and (string? (:intent args))
                              (not (contains? args :query)))
        args (if intent-promoted?
               (assoc args :query {:intent (:intent args)})
               args)
        query (ensure-map-or-nil (:query args) "query")
        retrieval-policy (ensure-map-or-nil (:retrieval_policy args) "retrieval_policy")]
    (when-not query
      (invalid-request "either intent (string) or query (object) is required"))
    (let [normalized (normalize-mcp-query state query)
          {:keys [query query_normalized normalized_query_summary]
           :or {query_normalized false}}
          (or normalized
              {:query query
               :query_normalized false
               :query_ingress_mode "canonical"})
          query_ingress_mode (if intent-promoted?
                               "intent_shorthand"
                               (or (:query_ingress_mode (or normalized {}))
                                   "canonical"))]
      (when (and (not normalized)
                 (not (canonical-query-shape? query)))
        (throw (enrich-invalid-query
                "resolve_context query shorthand could not be normalized safely"
                query)))
      (activation/ensure-request-languages-active!
       {:active_languages (get-in entry [:index :active_languages])
        :supported_languages (get-in entry [:index :supported_languages])}
       {:query query})
      (let [resolved (try
                       (sci/resolve-context (:index entry)
                                            query
                                            {:suppress_usage_metrics true
                                             :retrieval_policy retrieval-policy
                                             :policy_registry (:policy_registry @state)})
                       (catch Exception e
                         (if (= :invalid_query (:type (ex-data e)))
                           (throw (enrich-invalid-query "invalid retrieval query" query))
                           (throw e))))
            continuation-summary (or normalized_query_summary
                                     (normalized-query-summary query))
            result (-> (cond-> resolved
                         true (assoc :index_id (:index_id entry)
                                     :project_context (project-context-for-entry entry)
                                     :query_normalized query_normalized
                                     :query_ingress_mode query_ingress_mode
                                     :compact_continuation (compact-continuation
                                                            (:selection_id resolved)
                                                            (:snapshot_id resolved)
                                                            "expand_context"
                                                            continuation-summary))
                         continuation-summary (assoc :normalized_query_summary continuation-summary))
                       (add-next-step-guidance "expand_context"))
            result-meta (meta result)]
        (with-usage-event
          result
          (merge
           (usage-fields-for-query query)
           {:root_path_hash (usage/hash-root-path (:root_path entry))
            :selected_units_count (count (:focus result))
            :selected_files_count (count (distinct (map :path (:focus result))))
            :confidence_level (:confidence_level result)
            :result_status (:result_status result)
            :payload {:index_id (:index_id entry)
                      :selection_id (:selection_id result)
                      :snapshot_id (:snapshot_id result)
                      :estimated_tokens (get-in result [:budget_summary :estimated_tokens])
                      :requested_tokens (get-in result [:budget_summary :requested_tokens])
                      :policy_id (get-in result-meta [:retrieval_policy :policy_id])
                      :policy_version (get-in result-meta [:retrieval_policy :version])
                      :query_ingress_mode query_ingress_mode
                      :query_normalized query_normalized
                      :normalized_query_summary continuation-summary
                      :continuation_artifact {:selection_id (:selection_id result)
                                              :snapshot_id (:snapshot_id result)
                                              :next_tool "expand_context"}
                      :recommended_action (get-in result [:next_step :recommended_action])}}))))))

(defn tool-expand-context [state args]
  (when-not (map? args)
    (invalid-request "expand_context arguments must be an object"))
  (let [entry (resolve-entry! state args)
        selection-id (ensure-string (:selection_id args) "selection_id")
        snapshot-id (ensure-string (:snapshot_id args) "snapshot_id")
        unit-ids (normalize-unit-ids (:unit_ids args))
        include-impact-hints (ensure-boolean-or-nil (:include_impact_hints args) "include_impact_hints")
        result (-> (sci/expand-context (:index entry)
                                       {:selection_id selection-id
                                        :snapshot_id snapshot-id
                                        :unit_ids unit-ids
                                        :include_impact_hints include-impact-hints}
                                       {:suppress_usage_metrics true})
                   (assoc :index_id (:index_id entry)
                          :project_context (project-context-for-entry entry)
                          :compact_continuation (compact-continuation
                                                 selection-id
                                                 snapshot-id
                                                 "fetch_context_detail"))
                   (add-next-step-guidance "fetch_context_detail"))]
    (with-usage-event
      result
      {:root_path_hash (usage/hash-root-path (:root_path entry))
       :selected_units_count (count (:skeletons result))
       :payload {:index_id (:index_id entry)
                 :selection_id selection-id
                 :snapshot_id snapshot-id
                 :continuation_artifact {:selection_id selection-id
                                         :snapshot_id snapshot-id
                                         :next_tool "fetch_context_detail"}
                 :estimated_tokens (get-in result [:budget_summary :estimated_tokens])
                 :include_impact_hints (boolean include-impact-hints)
                 :impact_related_tests (count (get-in result [:impact_hints :related_tests]))}})))

(def ^:private valid-detail-levels
  #{"none" "target_span" "enclosing_unit" "local_neighborhood" "whole_file"})

(defn- validate-detail-level [detail-level]
  (when (and detail-level (not (valid-detail-levels detail-level)))
    (invalid-request (str "detail_level must be one of: "
                          (pr-str (sort valid-detail-levels))
                          ", got: " (pr-str detail-level)))))

(defn tool-fetch-context-detail [state args]
  (when-not (map? args)
    (invalid-request "fetch_context_detail arguments must be an object"))
  (let [entry (resolve-entry! state args)
        selection-id (ensure-string (:selection_id args) "selection_id")
        snapshot-id (ensure-string (:snapshot_id args) "snapshot_id")
        unit-ids (normalize-unit-ids (:unit_ids args))
        detail-level (some-> (:detail_level args) (ensure-string "detail_level"))
        _ (validate-detail-level detail-level)
        result (-> (sci/fetch-context-detail (:index entry)
                                             {:selection_id selection-id
                                              :snapshot_id snapshot-id
                                              :unit_ids unit-ids
                                              :detail_level detail-level}
                                             {:suppress_usage_metrics true})
                   (assoc :index_id (:index_id entry)
                          :project_context (project-context-for-entry entry)
                          :compact_continuation (compact-continuation
                                                 selection-id
                                                 snapshot-id
                                                 "resolve_context"))
                   (add-next-step-guidance "resolve_context"))]
    (with-usage-event
      result
      {:root_path_hash (usage/hash-root-path (:root_path entry))
       :selected_units_count (count (get-in result [:context_packet :relevant_units]))
       :selected_files_count (get-in result [:diagnostics_trace :result :selected_files_count])
       :confidence_level (get-in result [:context_packet :confidence :level])
       :autonomy_posture (get-in result [:guardrail_assessment :autonomy_posture])
       :result_status (get-in result [:diagnostics_trace :result :result_status])
       :raw_fetch_level (get-in result [:diagnostics_trace :result :raw_fetch_level_reached])
       :payload {:index_id (:index_id entry)
                 :selection_id selection-id
                 :snapshot_id snapshot-id
                 :continuation_artifact {:selection_id selection-id
                                         :snapshot_id snapshot-id
                                         :next_tool "resolve_context"}
                 :warning_count (count (get-in result [:diagnostics_trace :warnings]))
                 :degradation_count (count (get-in result [:diagnostics_trace :degradations]))
                 :estimated_tokens (get-in result [:context_packet :budget :estimated_tokens])}})))

(defn tool-literal-file-slice [state args]
  (when-not (map? args)
    (invalid-request "literal_file_slice arguments must be an object"))
  (let [entry (resolve-entry! state args)
        selection-id (some-> (:selection_id args) (ensure-string "selection_id"))
        snapshot-id (ensure-string (:snapshot_id args) "snapshot_id")
        path (-> (:path args)
                 (ensure-string "path")
                 (normalize-rel-path (:root_path entry)))
        start-line (ensure-int-or-nil (:start_line args) "start_line")
        end-line (ensure-int-or-nil (:end_line args) "end_line")]
    (when-not (some? start-line)
      (invalid-request "start_line is required"))
    (when-not (some? end-line)
      (invalid-request "end_line is required"))
    (let [result (-> (sci/literal-file-slice (:index entry)
                                             {:selection_id selection-id
                                              :snapshot_id snapshot-id
                                              :path path
                                              :start_line start-line
                                              :end_line end-line}
                                             {:suppress_usage_metrics true})
                     (assoc :index_id (:index_id entry)
                            :project_context (project-context-for-entry entry))
                     (add-next-step-guidance "resolve_context"))]
      (with-usage-event
        result
        {:root_path_hash (usage/hash-root-path (:root_path entry))
         :payload {:index_id (:index_id entry)
                   :selection_id selection-id
                   :snapshot_id snapshot-id
                   :path path
                   :requested_range (:requested_range result)
                   :returned_range (:returned_range result)
                   :line_count (:line_count result)
                   :byte_count (:byte_count result)
                   :truncated (:truncated result)
                   :truncation_reason (:truncation_reason result)}}))))

(defn tool-impact-analysis [state args]
  (when-not (map? args)
    (invalid-request "impact_analysis arguments must be an object"))
  (let [entry (resolve-entry! state args)
        query (ensure-map-or-nil (:query args) "query")]
    (when-not query
      (invalid-request "query is required"))
    (let [impact-hints (sci/impact-analysis (:index entry) query {:suppress_usage_metrics true})]
      (with-usage-event
        {:index_id (:index_id entry)
         :impact_hints impact-hints}
        (merge
         (usage-fields-for-query query)
         {:root_path_hash (usage/hash-root-path (:root_path entry))
          :payload {:index_id (:index_id entry)
                    :callers_count (count (:callers impact-hints))
                    :dependents_count (count (:dependents impact-hints))
                    :related_tests_count (count (:related_tests impact-hints))
                    :risky_neighbors_count (count (:risky_neighbors impact-hints))}})))))

(defn tool-skeletons [state args]
  (when-not (map? args)
    (invalid-request "skeletons arguments must be an object"))
  (let [entry (resolve-entry! state args)
        selector {:paths (normalize-paths (:paths args))
                  :unit_ids (normalize-unit-ids (:unit_ids args))}]
    (when-not (or (seq (:paths selector)) (seq (:unit_ids selector)))
      (invalid-request "skeletons requires paths or unit_ids"))
    (let [skeletons (sci/skeletons (:index entry) selector {:suppress_usage_metrics true})]
      (with-usage-event
        {:index_id (:index_id entry)
         :skeletons skeletons}
        {:root_path_hash (usage/hash-root-path (:root_path entry))
         :payload {:index_id (:index_id entry)
                   :skeleton_count (count skeletons)
                   :path_count (count (:paths selector))
                   :unit_id_count (count (:unit_ids selector))}}))))

(def tool-definitions
  [{:name "create_index"
    :description "Index a repository root or reuse a cached index. ALWAYS call this first before any codebase exploration. Returns an index_id for all subsequent calls. Use INSTEAD OF directory listing or glob search."
    :inputSchema {:type "object"
                  :properties {"root_path" {:type "string"}
                               "paths" {:type "array" :items {:type "string"}}
                               "parser_opts" {:type "object"}
                               "language_policy" {:type "object"}
                               "force_rebuild" {:type "boolean"}}
                  :required ["root_path"]
                  :additionalProperties false}}
   {:name "repo_map"
    :description "Return a compact repository map: files, modules, and structure. Call immediately after create_index. Use INSTEAD OF manual directory crawling or file listing for project overview."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "max_files" {:type "integer"}
                               "max_modules" {:type "integer"}}
                  :required ["index_id"]
                  :additionalProperties false}}
   {:name "resolve_context"
    :description "Find the most relevant code units for a task. Pass EITHER `intent` (a plain string describing your task — simplest) OR `query` (a structured retrieval object). Use INSTEAD OF grep or broad file reading. Pass result's selection_id to expand_context or fetch_context_detail for deeper inspection."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "intent" {:type "string"
                                         :description "Plain-text task description. Simplest way to call this tool. Mutually exclusive with query."}
                               "query" mcp-retrieval-query-schema
                               "retrieval_policy" {:type "object"}}
                  :required ["index_id"]
                  :additionalProperties false}}
   {:name "expand_context"
    :description "Widen a resolve_context selection with lightweight skeletons and impact hints. Use INSTEAD OF reading multiple files manually. Requires selection_id and snapshot_id from resolve_context."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "selection_id" {:type "string"}
                               "snapshot_id" {:type "string"}
                               "unit_ids" {:type "array" :items {:type "string"}}
                               "include_impact_hints" {:type "boolean"}}
                  :required ["index_id" "selection_id" "snapshot_id"]
                  :additionalProperties false}}
   {:name "fetch_context_detail"
    :description "Fetch raw code and full evidence for a selection. Use as the LAST resort before raw file access. Requires selection_id and snapshot_id from resolve_context. Returns code snippets, confidence assessment, and guardrail hints."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "selection_id" {:type "string"}
                               "snapshot_id" {:type "string"}
                               "unit_ids" {:type "array" :items {:type "string"}}
                               "detail_level" {:type "string"
                                                :enum ["none" "target_span" "enclosing_unit" "local_neighborhood" "whole_file"]}}
                  :required ["index_id" "selection_id" "snapshot_id"]
                  :additionalProperties false}}
   {:name "literal_file_slice"
    :description "Return an exact literal block of file text for a concrete path and line range. Prefer this after semantic narrowing when an agent needs patch-safe edit context."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "selection_id" {:type "string"}
                               "snapshot_id" {:type "string"}
                               "path" {:type "string"}
                               "start_line" {:type "integer"}
                               "end_line" {:type "integer"}}
                  :required ["index_id" "snapshot_id" "path" "start_line" "end_line"]
                  :additionalProperties false}}
   {:name "impact_analysis"
    :description "Estimate blast radius: which files, symbols, and tests are affected by a proposed change. Use for change planning, refactoring scope, or bug triage."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "query" {:type "object"}}
                  :required ["index_id" "query"]
                  :additionalProperties false}}
   {:name "skeletons"
    :description "Return lightweight code skeletons (signatures, structure) for files or units. Use INSTEAD OF reading full source files when you only need API shapes."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "paths" {:type "array" :items {:type "string"}}
                               "unit_ids" {:type "array" :items {:type "string"}}}
                  :required ["index_id"]
                  :additionalProperties false}}
   {:name "health"
    :description "Check if the SCI MCP server is alive and ready. Returns immediately with server status and uptime. Use to verify MCP availability before starting a workflow."
    :inputSchema {:type "object"
                  :properties {}
                  :additionalProperties false}}])

(defn tool-health [state _args]
  (let [state* @state
        index-count (count (:indexes-by-id state*))
        session-id (:session_id state*)]
    {:status "ok"
     :server_name server-name
     :server_version server-version
     :session_id session-id
     :uptime_ms (- (now-ms) (or (:started_at state*) (now-ms)))
     :index_count index-count}))

(def tool-handlers
  {"create_index" tool-create-index
   "repo_map" tool-repo-map
   "resolve_context" tool-resolve-context
   "expand_context" tool-expand-context
   "fetch_context_detail" tool-fetch-context-detail
   "literal_file_slice" tool-literal-file-slice
   "impact_analysis" tool-impact-analysis
   "skeletons" tool-skeletons
   "health" tool-health})

(defn format-json [payload]
  (json/write-str payload :escape-slash false))

(defn tool-success [payload]
  {:content [{:type "text"
              :text (format-json payload)}]
   :structuredContent payload})

(defn tool-error [message details]
  {:content [{:type "text"
              :text message}]
   :structuredContent (cond-> {:message message}
                        (some? details) (assoc :details details))
   :isError true})

(defn exception->tool-result [e]
  (let [info (errors/error-info e)
        message (:message info)
        details (errors/mcp-error-details e)]
    (tool-error message details)))

(defn jsonrpc-success [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn jsonrpc-error [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code
           :message message}})

(defn negotiate-protocol-version [message]
  (let [requested (get-in message [:params :protocolVersion])]
    (if (and (string? requested)
             (not (str/blank? requested)))
      requested
      default-protocol-version)))

(defn handle-tools-call [state params]
  (when-not (map? params)
    (invalid-request "tools/call params must be an object"))
  (let [tool-name (ensure-string (:name params) "name")
        arguments (or (:arguments params) {})
        started-at (now-ms)]
    (when-not (map? arguments)
      (invalid-request "tools/call arguments must be an object"))
    (if-let [handler (get tool-handlers tool-name)]
      (try
        (let [payload (handler state arguments)
              event-fields (:usage_event_fields (meta payload))]
          (record-mcp-event!
           state
           (merge event-fields
                  {:operation tool-name
                   :status "success"
                   :latency_ms (- (now-ms) started-at)}))
          (tool-success payload))
        (catch Exception e
          (log! "mcp_tool_error" tool-name (.getMessage e))
          (record-mcp-event!
           state
           (merge (usage-fields-for-query (:query arguments))
                  {:operation tool-name
                   :status "error"
                   :latency_ms (- (now-ms) started-at)
                   :root_path_hash (some-> (:root_path arguments) usage/hash-root-path)
                   :payload (errors/usage-error-payload e)}))
          (exception->tool-result e)))
      (do
        (record-mcp-event!
         state
         {:operation tool-name
          :status "error"
          :latency_ms (- (now-ms) started-at)
          :payload {:error_code "unknown_tool"
                    :tool_name tool-name}})
        (tool-error "unknown tool"
                    {:code "unknown_tool"
                     :category "client"
                     :details {:name tool-name}})))))

(defn handle-jsonrpc-message! [state message]
  (let [id (:id message)
        method (:method message)]
    (when-not (= "2.0" (:jsonrpc message))
      (when (contains? message :id)
        (jsonrpc-error id -32600 "jsonrpc must be 2.0")))
    (when (= "2.0" (:jsonrpc message))
      (case method
        "initialize"
        (do
          (swap! state assoc :client-info (get-in message [:params :clientInfo]))
          (jsonrpc-success id {:protocolVersion (negotiate-protocol-version message)
                               :capabilities {:tools {}}
                               :serverInfo {:name server-name
                                            :version server-version}}))

        "notifications/initialized"
        (do
          (swap! state assoc :initialized? true)
          nil)

        "ping"
        (when (contains? message :id)
          (jsonrpc-success id {}))

        "tools/list"
        (jsonrpc-success id {:tools tool-definitions})

        "tools/call"
        (jsonrpc-success id (handle-tools-call state (:params message)))

        (when (contains? message :id)
          (jsonrpc-error id -32601 (str "method not found: " method)))))))

(defn new-session-state [{:keys [max-indexes usage-metrics policy-registry session-id transport-format tenant-id]
                          :or {max-indexes default-max-indexes
                               session-id (str (UUID/randomUUID))}}]
  (atom (cond-> {:initialized? false
                 :transport-format transport-format
                 :max-indexes max-indexes
                 :policy_registry policy-registry
                 :selection_cache (atom {})
                 :session_id session-id
                 :started_at (now-ms)
                 :usage_metrics usage-metrics
                 :indexes-by-id {}
                 :cache-key->index-id {}}
          tenant-id (assoc :tenant_id tenant-id))))

(defn load-policy-registry [policy-registry-file]
  (when (seq policy-registry-file)
    (rp/load-registry policy-registry-file)))
