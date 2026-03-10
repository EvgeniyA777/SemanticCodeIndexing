(ns semantic-code-indexing.core
  (:require [semantic-code-indexing.runtime.index :as idx]
            [semantic-code-indexing.runtime.errors :as errors]
            [semantic-code-indexing.runtime.retrieval :as retrieval]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]
            [semantic-code-indexing.runtime.storage :as storage]
            [semantic-code-indexing.runtime.usage-metrics :as usage]))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- attach-runtime-context [index usage-metrics usage-context policy-registry]
  (with-meta index
    (merge (meta index)
           {:usage_metrics usage-metrics
            :usage_context (merge {:surface "library"} usage-context)
            :policy_registry policy-registry})))

(defn- resolve-usage-metrics [index opts]
  (or (:usage_metrics opts)
      (:usage_metrics (meta index))))

(defn- resolve-usage-context [index opts]
  (merge {:surface "library"}
         (:usage_context (meta index))
         (:usage_context opts)))

(defn- resolve-policy-registry [index opts]
  (or (:policy_registry opts)
      (some-> (:policy_registry_path opts) rp/resolve-registry-source)
      (:policy_registry (meta index))))

(defn- should-record-usage? [sink opts]
  (and sink (not (:suppress_usage_metrics opts))))

(defn- request-trace-fields [query]
  {:trace_id (get-in query [:trace :trace_id])
   :request_id (get-in query [:trace :request_id])
   :session_id (get-in query [:trace :session_id])
   :task_id (get-in query [:trace :task_id])
   :actor_id (or (get-in query [:trace :actor_id])
                 (get-in query [:trace :agent_id]))})

(defn- error-payload [e]
  (errors/usage-error-payload e))

(defn create-index
  "Create a new in-memory index from a repository root.

  Options:
  - :root_path string (default \".\")
  - :paths vector of relative source paths (optional subset indexing)"
  [opts]
  (let [sink (:usage_metrics opts)
        usage-context (resolve-usage-context nil opts)
        policy-registry (resolve-policy-registry nil opts)
        root-path (or (:root_path opts) ".")
        start-ms (now-ms)]
    (try
      (let [index (idx/create-index opts)
            index* (attach-runtime-context index sink usage-context policy-registry)]
        (when (should-record-usage? sink opts)
          (usage/safe-record-event!
           sink
           (merge usage-context
                  {:operation "create_index"
                   :status "success"
                   :latency_ms (- (now-ms) start-ms)
                   :root_path_hash (usage/hash-root-path (:root_path index*))
                   :file_count (count (:files index*))
                   :unit_count (count (:units index*))
                   :payload {:load_latest (boolean (:load_latest opts))
                             :paths_count (count (:paths opts))
                             :snapshot_id (:snapshot_id index*)}})))
        index*)
      (catch Exception e
        (when (should-record-usage? sink opts)
          (usage/safe-record-event!
           sink
           (merge usage-context
                  {:operation "create_index"
                   :status "error"
                   :latency_ms (- (now-ms) start-ms)
                   :root_path_hash (usage/hash-root-path root-path)
                   :payload (merge {:load_latest (boolean (:load_latest opts))
                                    :paths_count (count (:paths opts))}
                                   (error-payload e))})))
        (throw (errors/normalize-exception e))))))

(defn update-index
  "Incrementally update index with changed paths.

  Options:
  - :changed_paths vector of relative source paths"
  [index opts]
  (let [sink (resolve-usage-metrics index opts)
        usage-context (resolve-usage-context index opts)
        policy-registry (resolve-policy-registry index opts)
        start-ms (now-ms)]
    (try
      (let [updated (idx/update-index index opts)
            updated* (attach-runtime-context updated sink usage-context policy-registry)]
        (when (should-record-usage? sink opts)
          (usage/safe-record-event!
           sink
           (merge usage-context
                  {:operation "update_index"
                   :status "success"
                   :latency_ms (- (now-ms) start-ms)
                   :root_path_hash (usage/hash-root-path (:root_path updated*))
                   :file_count (count (:files updated*))
                   :unit_count (count (:units updated*))
                   :payload {:changed_paths_count (count (:changed_paths opts))
                             :snapshot_id (:snapshot_id updated*)}})))
        updated*)
      (catch Exception e
        (when (should-record-usage? sink opts)
          (usage/safe-record-event!
           sink
           (merge usage-context
                  {:operation "update_index"
                   :status "error"
                   :latency_ms (- (now-ms) start-ms)
                   :root_path_hash (usage/hash-root-path (:root_path index))
                   :payload (merge {:changed_paths_count (count (:changed_paths opts))}
                                   (error-payload e))})))
        (throw (errors/normalize-exception e))))))

(defn repo-map
  "Return compact repository map from current index." 
  ([index] (repo-map index {}))
  ([index opts]
   (let [sink (resolve-usage-metrics index opts)
         usage-context (resolve-usage-context index opts)
         start-ms (now-ms)
         repo-opts (select-keys opts [:max_files :max_modules])]
     (try
       (let [result (if (seq repo-opts)
                      (idx/repo-map index repo-opts)
                      (idx/repo-map index))]
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   {:operation "repo_map"
                    :status "success"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload {:snapshot_id (:snapshot_id index)
                              :file_count (count (:files result))
                              :module_count (count (:modules result))}})))
         result)
       (catch Exception e
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   {:operation "repo_map"
                    :status "error"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload (error-payload e)})))
         (throw (errors/normalize-exception e)))))))

(defn resolve-context
  "Resolve context packet, diagnostics trace and stage events for a retrieval query."
  ([index query]
   (resolve-context index query {}))
  ([index query opts]
   (let [sink (resolve-usage-metrics index opts)
         usage-context (resolve-usage-context index opts)
         policy-registry (resolve-policy-registry index opts)
         start-ms (now-ms)]
     (try
       (let [result (retrieval/resolve-context index query (assoc opts :policy_registry policy-registry))
             diagnostics (:diagnostics_trace result)
             packet (:context_packet result)
             guardrails (:guardrail_assessment result)]
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   (request-trace-fields query)
                   {:operation "resolve_context"
                    :status "success"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :selected_units_count (count (get-in packet [:relevant_units]))
                    :selected_files_count (get-in diagnostics [:result :selected_files_count])
                    :confidence_level (get-in packet [:confidence :level])
                    :autonomy_posture (:autonomy_posture guardrails)
                    :result_status (get-in diagnostics [:result :result_status])
                    :raw_fetch_level (get-in diagnostics [:result :raw_fetch_level_reached])
                    :payload {:estimated_tokens (get-in packet [:budget :estimated_tokens])
                              :requested_tokens (get-in packet [:budget :requested_tokens])
                              :warning_count (count (:warnings diagnostics))
                              :degradation_count (count (:degradations diagnostics))
                              :fallback_units (get-in diagnostics [:performance :parser_summary :fallback_units])
                              :policy_id (get-in diagnostics [:retrieval_policy :policy_id])
                              :policy_version (get-in diagnostics [:retrieval_policy :version])
                              :query query
                              :selected_unit_ids (mapv :unit_id (get-in packet [:relevant_units]))
                              :selected_paths (->> (get-in packet [:relevant_units])
                                                   (map :path)
                                                   distinct
                                                   vec)
                              :top_authority_unit_ids (get-in diagnostics [:result :top_authority_targets])
                              :outcome_summary {:confidence_level (get-in packet [:confidence :level])
                                                :confidence_score (get-in packet [:confidence :score])
                                                :result_status (get-in diagnostics [:result :result_status])
                                                :autonomy_posture (:autonomy_posture guardrails)}}})))
         result)
       (catch Exception e
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   (request-trace-fields query)
                   {:operation "resolve_context"
                    :status "error"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload (error-payload e)})))
         (throw (errors/normalize-exception e)))))))

(defn impact-analysis
  "Return impact hints for the same retrieval query semantics used by resolve-context."
  ([index query]
   (impact-analysis index query {}))
  ([index query opts]
   (let [sink (resolve-usage-metrics index opts)
         usage-context (resolve-usage-context index opts)
         start-ms (now-ms)]
     (try
       (let [result (retrieval/impact-analysis index query opts)]
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   (request-trace-fields query)
                   {:operation "impact_analysis"
                    :status "success"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload {:callers_count (count (:callers result))
                              :dependents_count (count (:dependents result))
                              :related_tests_count (count (:related_tests result))
                              :risky_neighbors_count (count (:risky_neighbors result))}})))
         result)
       (catch Exception e
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   (request-trace-fields query)
                   {:operation "impact_analysis"
                    :status "error"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload (error-payload e)})))
         (throw (errors/normalize-exception e)))))))

(defn skeletons
  "Return skeletons for selected units/paths.

  Selector:
  - :unit_ids vector of unit ids
  - :paths vector of file paths"
  ([index selector]
   (skeletons index selector {}))
  ([index selector opts]
   (let [sink (resolve-usage-metrics index opts)
         usage-context (resolve-usage-context index opts)
         start-ms (now-ms)]
     (try
       (let [result (retrieval/skeletons index selector)]
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   {:operation "skeletons"
                    :status "success"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload {:skeleton_count (count result)
                              :path_count (count (:paths selector))
                              :unit_id_count (count (:unit_ids selector))}})))
         result)
       (catch Exception e
         (when (should-record-usage? sink opts)
           (usage/safe-record-event!
            sink
            (merge usage-context
                   {:operation "skeletons"
                    :status "error"
                    :latency_ms (- (now-ms) start-ms)
                    :root_path_hash (usage/hash-root-path (:root_path index))
                    :payload (error-payload e)})))
         (throw (errors/normalize-exception e)))))))

(defn in-memory-storage
  "Create in-memory storage adapter for index snapshots."
  []
  (storage/in-memory-storage))

(defn postgres-storage
  "Create PostgreSQL storage adapter.

  Options:
  - :db-spec next.jdbc datasource spec map
  - OR :jdbc-url (+ optional :user, :password)"
  [opts]
  (storage/postgres-storage opts))

(defn no-op-usage-metrics
  "Create a usage metrics sink that drops all events."
  []
  (usage/no-op-usage-metrics))

(defn in-memory-usage-metrics
  "Create an in-memory usage metrics sink for tests and local inspection."
  []
  (usage/in-memory-usage-metrics))

(defn postgres-usage-metrics
  "Create PostgreSQL-backed usage metrics sink."
  [opts]
  (usage/postgres-usage-metrics opts))

(defn slo-report
  "Aggregate SLO-facing operational metrics from a usage metrics sink.

  Options:
  - :surface optional usage surface filter
  - :operation optional operation filter
  - :tenant_id optional tenant filter
  - :since optional ISO timestamp lower bound"
  ([usage-metrics-sink]
   (usage/slo-report usage-metrics-sink))
  ([usage-metrics-sink opts]
   (usage/slo-report usage-metrics-sink opts)))

(defn harvest-replay-dataset
  "Build a replay dataset from recorded usage events and feedback.

  Options:
  - :surface optional usage surface filter
  - :tenant_id optional tenant filter
  - :since optional ISO timestamp lower bound"
  ([usage-metrics-sink]
   (usage/harvest-replay-dataset usage-metrics-sink))
  ([usage-metrics-sink opts]
   (usage/harvest-replay-dataset usage-metrics-sink opts)))

(defn calibration-report
  "Aggregate confidence calibration against recorded feedback outcomes.

  Options:
  - :surface optional usage surface filter
  - :tenant_id optional tenant filter
  - :since optional ISO timestamp lower bound"
  ([usage-metrics-sink]
   (usage/calibration-report usage-metrics-sink))
  ([usage-metrics-sink opts]
   (usage/calibration-report usage-metrics-sink opts)))

(defn weekly-review-report
  "Build a review-oriented artifact linking query, selected context, feedback, and outcome.

  Options:
  - :surface optional usage surface filter
  - :tenant_id optional tenant filter
  - :since optional ISO timestamp lower bound"
  ([usage-metrics-sink]
   (usage/weekly-review-report usage-metrics-sink))
  ([usage-metrics-sink opts]
   (usage/weekly-review-report usage-metrics-sink opts)))

(defn record-feedback!
  "Record explicit host feedback for a prior retrieval flow.

  Accepts either a usage metrics sink or an index carrying usage metrics metadata."
  [target feedback]
  (let [sink (if (satisfies? usage/UsageMetricsSink target)
               target
               (:usage_metrics (meta target)))
        usage-context (if (satisfies? usage/UsageMetricsSink target)
                        {:surface "library"}
                        (:usage_context (meta target)))
        feedback* (merge usage-context
                         {:root_path_hash (when-not (contains? feedback :root_path_hash)
                                            (some-> target :root_path usage/hash-root-path))}
                         feedback)]
    (usage/safe-record-feedback! sink feedback*)
    true))

(defn query-units
  "Query persisted units from storage by root path and optional filters.

  Options:
  - :snapshot_id exact snapshot (optional, latest if omitted)
  - :module exact module name (optional)
  - :symbol exact symbol name (optional)
  - :limit max rows (default 100)"
  [storage-adapter root-path opts]
  (storage/query-units storage-adapter root-path opts))

(defn query-callers
  "Query persisted callers for a unit id."
  [storage-adapter root-path unit-id opts]
  (storage/query-callers storage-adapter root-path unit-id opts))

(defn query-callees
  "Query persisted callees for a unit id."
  [storage-adapter root-path unit-id opts]
  (storage/query-callees storage-adapter root-path unit-id opts))
