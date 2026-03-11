(ns semantic-code-indexing.runtime.usage-metrics
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(defprotocol UsageMetricsSink
  (init-usage-metrics! [sink])
  (record-event! [sink event])
  (record-feedback! [sink feedback])
  (flush-usage-metrics! [sink]))

(declare parse-iso-instant correlation-key)

(defn- now-iso []
  (str (Instant/now)))

(defn- today-sql-date []
  (java.sql.Date/valueOf (java.time.LocalDate/now java.time.ZoneOffset/UTC)))

(defn- uuid []
  (str (UUID/randomUUID)))

(defn- sha256-bytes [^String value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.digest digest (.getBytes value java.nio.charset.StandardCharsets/UTF_8))))

(defn hash-root-path [root-path]
  (when (seq (str root-path))
    (apply str (map #(format "%02x" (bit-and % 0xff))
                    (sha256-bytes (str root-path))))))

(defn- normalize-filter-opts [opts]
  (cond-> opts
    (and (:root_path opts) (not (:root_path_hash opts)))
    (assoc :root_path_hash (hash-root-path (:root_path opts)))))

(defn- compact-payload [payload]
  (cond
    (nil? payload) {}
    (map? payload) payload
    :else {:value (str payload)}))

(defn- normalize-string-array [values]
  (when (some? values)
    (->> values
         (map str)
         (remove str/blank?)
         distinct
         vec)))

(defn normalize-event [event]
  (let [payload (compact-payload (:payload event))]
    {:event_id (or (:event_id event) (uuid))
     :occurred_at (or (:occurred_at event) (now-iso))
     :surface (or (:surface event) "library")
     :operation (or (:operation event) "unknown")
     :status (or (:status event) "success")
     :trace_id (:trace_id event)
     :request_id (:request_id event)
     :session_id (:session_id event)
     :task_id (:task_id event)
     :actor_id (:actor_id event)
     :tenant_id (:tenant_id event)
     :root_path_hash (:root_path_hash event)
     :latency_ms (:latency_ms event)
     :file_count (:file_count event)
     :unit_count (:unit_count event)
     :selected_units_count (:selected_units_count event)
     :selected_files_count (:selected_files_count event)
     :cache_hit (:cache_hit event)
     :confidence_level (:confidence_level event)
     :autonomy_posture (:autonomy_posture event)
     :result_status (:result_status event)
     :raw_fetch_level (:raw_fetch_level event)
     :payload payload}))

(defn normalize-feedback [feedback]
  (let [payload (compact-payload (:payload feedback))]
    {:feedback_id (or (:feedback_id feedback) (uuid))
     :occurred_at (or (:occurred_at feedback) (now-iso))
     :surface (or (:surface feedback) "library")
     :operation (or (:operation feedback) "resolve_context")
     :trace_id (:trace_id feedback)
     :request_id (:request_id feedback)
     :session_id (:session_id feedback)
     :task_id (:task_id feedback)
     :actor_id (:actor_id feedback)
     :tenant_id (:tenant_id feedback)
     :root_path_hash (:root_path_hash feedback)
     :feedback_outcome (:feedback_outcome feedback)
     :feedback_reason (:feedback_reason feedback)
     :followup_action (:followup_action feedback)
     :confidence_level (:confidence_level feedback)
     :retrieval_issue_codes (normalize-string-array (:retrieval_issue_codes feedback))
     :ground_truth_unit_ids (normalize-string-array (:ground_truth_unit_ids feedback))
     :ground_truth_paths (normalize-string-array (:ground_truth_paths feedback))
     :payload payload}))

(defrecord NoOpUsageMetrics []
  UsageMetricsSink
  (init-usage-metrics! [_] true)
  (record-event! [_ _] true)
  (record-feedback! [_ _] true)
  (flush-usage-metrics! [_] true))

(defn no-op-usage-metrics []
  (->NoOpUsageMetrics))

(defrecord InMemoryUsageMetrics [state]
  UsageMetricsSink
  (init-usage-metrics! [_] true)
  (record-event! [_ event]
    (swap! state update :events conj (normalize-event event))
    true)
  (record-feedback! [_ feedback]
    (swap! state update :feedback conj (normalize-feedback feedback))
    true)
  (flush-usage-metrics! [_] true))

(defn in-memory-usage-metrics []
  (->InMemoryUsageMetrics (atom {:events [] :feedback []})))

(defn emitted-events [sink]
  (if (instance? InMemoryUsageMetrics sink)
    (:events @(:state sink))
    []))

(defn emitted-feedback [sink]
  (if (instance? InMemoryUsageMetrics sink)
    (:feedback @(:state sink))
    []))

(defn- feedback-matches? [feedback {:keys [surface operation tenant_id since root_path_hash]}]
  (and (if surface (= surface (:surface feedback)) true)
       (if operation (= operation (:operation feedback)) true)
       (if tenant_id (= tenant_id (:tenant_id feedback)) true)
       (if root_path_hash (= root_path_hash (:root_path_hash feedback)) true)
       (if since
         (not (.isBefore (parse-iso-instant (:occurred_at feedback))
                         (parse-iso-instant since)))
         true)))

(defn- parse-json [v]
  (cond
    (nil? v) nil
    (string? v) (json/read-str v :key-fn keyword)
    :else (json/read-str (str v) :key-fn keyword)))

(defn- normalize-db-spec [{:keys [db-spec jdbc-url user password]}]
  (cond
    db-spec db-spec
    jdbc-url (cond-> {:jdbcUrl jdbc-url}
               user (assoc :user user)
               password (assoc :password password))
    :else (throw (ex-info "usage metrics postgres sink requires :db-spec or :jdbc-url"
                          {:type :invalid_usage_metrics_config}))))

(defn- ->json [m]
  (json/write-str (or m {})))

(defn- ensure-initialized! [initialized? init-fn]
  (when (compare-and-set! initialized? false true)
    (init-fn))
  true)

(defn- event-rollup [event]
  {:metric_date (today-sql-date)
   :surface (:surface event)
   :operation (:operation event)
   :event_count 1
   :success_count (if (= "success" (:status event)) 1 0)
   :failure_count (if (= "success" (:status event)) 0 1)
   :cache_hit_count (if (true? (:cache_hit event)) 1 0)
   :total_latency_ms (long (or (:latency_ms event) 0))
   :resolve_context_count (if (= "resolve_context" (:operation event)) 1 0)
   :high_confidence_count (if (= "high" (:confidence_level event)) 1 0)
   :medium_confidence_count (if (= "medium" (:confidence_level event)) 1 0)
   :degraded_count (if (or (= "degraded" (:result_status event))
                           (= "error" (:status event)))
                     1
                     0)
   :helpful_count 0
   :partially_helpful_count 0
   :not_helpful_count 0
   :abandoned_count 0})

(defn- feedback-rollup [feedback]
  {:metric_date (today-sql-date)
   :surface (:surface feedback)
   :operation (:operation feedback)
   :event_count 0
   :success_count 0
   :failure_count 0
   :cache_hit_count 0
   :total_latency_ms 0
   :resolve_context_count 0
   :high_confidence_count 0
   :medium_confidence_count 0
   :degraded_count 0
   :helpful_count (if (= "helpful" (:feedback_outcome feedback)) 1 0)
   :partially_helpful_count (if (= "partially_helpful" (:feedback_outcome feedback)) 1 0)
   :not_helpful_count (if (= "not_helpful" (:feedback_outcome feedback)) 1 0)
   :abandoned_count (if (= "abandoned" (:feedback_outcome feedback)) 1 0)})

(defn- upsert-rollup! [tx rollup]
  (jdbc/execute! tx
                 ["insert into semantic_usage_daily_rollups
                   (metric_date, surface, operation, event_count, success_count, failure_count,
                    cache_hit_count, total_latency_ms, resolve_context_count,
                    high_confidence_count, medium_confidence_count, degraded_count,
                    helpful_count, partially_helpful_count, not_helpful_count, abandoned_count)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   on conflict (metric_date, surface, operation)
                   do update set
                     event_count = semantic_usage_daily_rollups.event_count + excluded.event_count,
                     success_count = semantic_usage_daily_rollups.success_count + excluded.success_count,
                     failure_count = semantic_usage_daily_rollups.failure_count + excluded.failure_count,
                     cache_hit_count = semantic_usage_daily_rollups.cache_hit_count + excluded.cache_hit_count,
                     total_latency_ms = semantic_usage_daily_rollups.total_latency_ms + excluded.total_latency_ms,
                     resolve_context_count = semantic_usage_daily_rollups.resolve_context_count + excluded.resolve_context_count,
                     high_confidence_count = semantic_usage_daily_rollups.high_confidence_count + excluded.high_confidence_count,
                     medium_confidence_count = semantic_usage_daily_rollups.medium_confidence_count + excluded.medium_confidence_count,
                     degraded_count = semantic_usage_daily_rollups.degraded_count + excluded.degraded_count,
                     helpful_count = semantic_usage_daily_rollups.helpful_count + excluded.helpful_count,
                     partially_helpful_count = semantic_usage_daily_rollups.partially_helpful_count + excluded.partially_helpful_count,
                     not_helpful_count = semantic_usage_daily_rollups.not_helpful_count + excluded.not_helpful_count,
                     abandoned_count = semantic_usage_daily_rollups.abandoned_count + excluded.abandoned_count"
                  (:metric_date rollup)
                  (:surface rollup)
                  (:operation rollup)
                  (:event_count rollup)
                  (:success_count rollup)
                  (:failure_count rollup)
                  (:cache_hit_count rollup)
                  (:total_latency_ms rollup)
                  (:resolve_context_count rollup)
                  (:high_confidence_count rollup)
                  (:medium_confidence_count rollup)
                  (:degraded_count rollup)
                  (:helpful_count rollup)
                  (:partially_helpful_count rollup)
                  (:not_helpful_count rollup)
                  (:abandoned_count rollup)]))

(defrecord PostgresUsageMetrics [datasource initialized?]
  UsageMetricsSink
  (init-usage-metrics! [_]
    (ensure-initialized!
     initialized?
     #(do
        (jdbc/execute! datasource
                       ["create table if not exists semantic_usage_events (
                           id bigserial primary key,
                           event_id text not null unique,
                           occurred_at timestamptz not null,
                           surface text not null,
                           operation text not null,
                           status text not null,
                           trace_id text,
                           request_id text,
                           session_id text,
                           task_id text,
                           actor_id text,
                           tenant_id text,
                           root_path_hash text,
                           latency_ms bigint,
                           file_count integer,
                           unit_count integer,
                           selected_units_count integer,
                           selected_files_count integer,
                           cache_hit boolean,
                           confidence_level text,
                           autonomy_posture text,
                           result_status text,
                           raw_fetch_level text,
                           payload jsonb not null
                         )"])
        (jdbc/execute! datasource
                       ["create index if not exists idx_semantic_usage_events_surface_operation
                         on semantic_usage_events(surface, operation, occurred_at desc)"])
        (jdbc/execute! datasource
                       ["create index if not exists idx_semantic_usage_events_trace_request
                         on semantic_usage_events(trace_id, request_id)"])
        (jdbc/execute! datasource
                       ["create table if not exists semantic_usage_feedback (
                           id bigserial primary key,
                           feedback_id text not null unique,
                           occurred_at timestamptz not null,
                           surface text not null,
                           operation text not null,
                           trace_id text,
                           request_id text,
                           session_id text,
                           task_id text,
                           actor_id text,
                           tenant_id text,
                           root_path_hash text,
                           feedback_outcome text not null,
                           feedback_reason text,
                           followup_action text,
                           confidence_level text,
                           retrieval_issue_codes jsonb,
                           ground_truth_unit_ids jsonb,
                           ground_truth_paths jsonb,
                           payload jsonb not null
                         )"])
        (jdbc/execute! datasource
                       ["create index if not exists idx_semantic_usage_feedback_trace_request
                         on semantic_usage_feedback(trace_id, request_id)"])
        (jdbc/execute! datasource
                       ["create table if not exists semantic_usage_daily_rollups (
                           metric_date date not null,
                           surface text not null,
                           operation text not null,
                           event_count bigint not null default 0,
                           success_count bigint not null default 0,
                           failure_count bigint not null default 0,
                           cache_hit_count bigint not null default 0,
                           total_latency_ms bigint not null default 0,
                           resolve_context_count bigint not null default 0,
                           high_confidence_count bigint not null default 0,
                           medium_confidence_count bigint not null default 0,
                           degraded_count bigint not null default 0,
                           helpful_count bigint not null default 0,
                           partially_helpful_count bigint not null default 0,
                           not_helpful_count bigint not null default 0,
                           abandoned_count bigint not null default 0,
                           primary key (metric_date, surface, operation)
                         )"]))))
  (record-event! [_ event]
    (let [event* (normalize-event event)]
      (init-usage-metrics! _)
      (jdbc/with-transaction [tx datasource]
        (jdbc/execute! tx
                       ["insert into semantic_usage_events
                         (event_id, occurred_at, surface, operation, status, trace_id, request_id,
                          session_id, task_id, actor_id, tenant_id, root_path_hash, latency_ms,
                          file_count, unit_count, selected_units_count, selected_files_count,
                          cache_hit, confidence_level, autonomy_posture, result_status,
                          raw_fetch_level, payload)
                         values (?, cast(? as timestamptz), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))"
                        (:event_id event*)
                        (:occurred_at event*)
                        (:surface event*)
                        (:operation event*)
                        (:status event*)
                        (:trace_id event*)
                        (:request_id event*)
                        (:session_id event*)
                        (:task_id event*)
                        (:actor_id event*)
                        (:tenant_id event*)
                        (:root_path_hash event*)
                        (:latency_ms event*)
                        (:file_count event*)
                        (:unit_count event*)
                        (:selected_units_count event*)
                        (:selected_files_count event*)
                        (:cache_hit event*)
                        (:confidence_level event*)
                        (:autonomy_posture event*)
                        (:result_status event*)
                        (:raw_fetch_level event*)
                        (->json (:payload event*))])
        (upsert-rollup! tx (event-rollup event*)))
      true))
  (record-feedback! [_ feedback]
    (let [feedback* (normalize-feedback feedback)]
      (init-usage-metrics! _)
      (jdbc/with-transaction [tx datasource]
        (jdbc/execute! tx
                       ["insert into semantic_usage_feedback
                         (feedback_id, occurred_at, surface, operation, trace_id, request_id,
                          session_id, task_id, actor_id, tenant_id, root_path_hash,
                          feedback_outcome, feedback_reason, followup_action, confidence_level,
                          retrieval_issue_codes, ground_truth_unit_ids, ground_truth_paths, payload)
                         values (?, cast(? as timestamptz), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))"
                        (:feedback_id feedback*)
                        (:occurred_at feedback*)
                        (:surface feedback*)
                        (:operation feedback*)
                        (:trace_id feedback*)
                        (:request_id feedback*)
                        (:session_id feedback*)
                        (:task_id feedback*)
                        (:actor_id feedback*)
                        (:tenant_id feedback*)
                        (:root_path_hash feedback*)
                        (:feedback_outcome feedback*)
                        (:feedback_reason feedback*)
                        (:followup_action feedback*)
                        (:confidence_level feedback*)
                        (->json (:retrieval_issue_codes feedback*))
                        (->json (:ground_truth_unit_ids feedback*))
                        (->json (:ground_truth_paths feedback*))
                        (->json (:payload feedback*))])
        (upsert-rollup! tx (feedback-rollup feedback*)))
      true))
  (flush-usage-metrics! [_] true))

(defn postgres-usage-metrics [opts]
  (->PostgresUsageMetrics (jdbc/get-datasource (normalize-db-spec opts))
                          (atom false)))

(defn safe-record-event! [sink event]
  (when sink
    (try
      (record-event! sink event)
      (catch Exception _ false))))

(defn safe-record-feedback! [sink feedback]
  (when sink
    (try
      (record-feedback! sink feedback)
      (catch Exception _ false))))

(defn- parse-iso-instant [value]
  (when (seq (str value))
    (Instant/parse (str value))))

(defn- event-matches? [event {:keys [surface operation tenant_id since root_path_hash]}]
  (and (if surface (= surface (:surface event)) true)
       (if operation (= operation (:operation event)) true)
       (if tenant_id (= tenant_id (:tenant_id event)) true)
       (if root_path_hash (= root_path_hash (:root_path_hash event)) true)
       (if since
         (not (.isBefore (parse-iso-instant (:occurred_at event))
                         (parse-iso-instant since)))
         true)))

(defn- postgres-events [sink opts]
  (let [ds (:datasource sink)
        {:keys [surface operation tenant_id since root_path_hash]} (normalize-filter-opts opts)
        sql (str "select occurred_at, surface, operation, status, trace_id, request_id, session_id,
                         task_id, actor_id, tenant_id, root_path_hash, latency_ms, file_count,
                         unit_count, selected_units_count, selected_files_count, cache_hit,
                         confidence_level, autonomy_posture, result_status, raw_fetch_level, payload
                  from semantic_usage_events
                  where 1=1"
                 (when surface " and surface = ?")
                 (when operation " and operation = ?")
                 (when tenant_id " and tenant_id = ?")
                 (when root_path_hash " and root_path_hash = ?")
                 (when since " and occurred_at >= cast(? as timestamptz)")
                 " order by occurred_at asc")
        params (cond-> [sql]
                 surface (conj surface)
                 operation (conj operation)
                 tenant_id (conj tenant_id)
                 root_path_hash (conj root_path_hash)
                 since (conj since))]
    (init-usage-metrics! sink)
    (->> (jdbc/execute! ds params)
         (mapv (fn [row]
                 (normalize-event
                  {:occurred_at (str (or (:semantic_usage_events/occurred_at row)
                                         (:occurred_at row)))
                   :surface (or (:semantic_usage_events/surface row) (:surface row))
                   :operation (or (:semantic_usage_events/operation row) (:operation row))
                   :status (or (:semantic_usage_events/status row) (:status row))
                   :trace_id (or (:semantic_usage_events/trace_id row) (:trace_id row))
                   :request_id (or (:semantic_usage_events/request_id row) (:request_id row))
                   :session_id (or (:semantic_usage_events/session_id row) (:session_id row))
                   :task_id (or (:semantic_usage_events/task_id row) (:task_id row))
                   :actor_id (or (:semantic_usage_events/actor_id row) (:actor_id row))
                   :tenant_id (or (:semantic_usage_events/tenant_id row) (:tenant_id row))
                   :root_path_hash (or (:semantic_usage_events/root_path_hash row) (:root_path_hash row))
                   :latency_ms (or (:semantic_usage_events/latency_ms row) (:latency_ms row))
                   :file_count (or (:semantic_usage_events/file_count row) (:file_count row))
                   :unit_count (or (:semantic_usage_events/unit_count row) (:unit_count row))
                   :selected_units_count (or (:semantic_usage_events/selected_units_count row) (:selected_units_count row))
                   :selected_files_count (or (:semantic_usage_events/selected_files_count row) (:selected_files_count row))
                   :cache_hit (or (:semantic_usage_events/cache_hit row) (:cache_hit row))
                   :confidence_level (or (:semantic_usage_events/confidence_level row) (:confidence_level row))
                   :autonomy_posture (or (:semantic_usage_events/autonomy_posture row) (:autonomy_posture row))
                   :result_status (or (:semantic_usage_events/result_status row) (:result_status row))
                   :raw_fetch_level (or (:semantic_usage_events/raw_fetch_level row) (:raw_fetch_level row))
                   :payload (parse-json (or (:semantic_usage_events/payload row) (:payload row)))}))))))

(defn- postgres-feedback [sink opts]
  (let [ds (:datasource sink)
        {:keys [surface operation tenant_id since root_path_hash]} (normalize-filter-opts opts)
        sql (str "select occurred_at, surface, operation, trace_id, request_id, session_id,
                         task_id, actor_id, tenant_id, root_path_hash, feedback_outcome,
                         feedback_reason, followup_action, confidence_level,
                         retrieval_issue_codes, ground_truth_unit_ids, ground_truth_paths, payload
                  from semantic_usage_feedback
                  where 1=1"
                 (when surface " and surface = ?")
                 (when operation " and operation = ?")
                 (when tenant_id " and tenant_id = ?")
                 (when root_path_hash " and root_path_hash = ?")
                 (when since " and occurred_at >= cast(? as timestamptz)")
                 " order by occurred_at asc")
        params (cond-> [sql]
                 surface (conj surface)
                 operation (conj operation)
                 tenant_id (conj tenant_id)
                 root_path_hash (conj root_path_hash)
                 since (conj since))]
    (init-usage-metrics! sink)
    (->> (jdbc/execute! ds params)
         (mapv (fn [row]
                 (normalize-feedback
                  {:occurred_at (str (or (:semantic_usage_feedback/occurred_at row)
                                         (:occurred_at row)))
                   :surface (or (:semantic_usage_feedback/surface row) (:surface row))
                   :operation (or (:semantic_usage_feedback/operation row) (:operation row))
                   :trace_id (or (:semantic_usage_feedback/trace_id row) (:trace_id row))
                   :request_id (or (:semantic_usage_feedback/request_id row) (:request_id row))
                   :session_id (or (:semantic_usage_feedback/session_id row) (:session_id row))
                   :task_id (or (:semantic_usage_feedback/task_id row) (:task_id row))
                   :actor_id (or (:semantic_usage_feedback/actor_id row) (:actor_id row))
                   :tenant_id (or (:semantic_usage_feedback/tenant_id row) (:tenant_id row))
                   :root_path_hash (or (:semantic_usage_feedback/root_path_hash row) (:root_path_hash row))
                   :feedback_outcome (or (:semantic_usage_feedback/feedback_outcome row) (:feedback_outcome row))
                   :feedback_reason (or (:semantic_usage_feedback/feedback_reason row) (:feedback_reason row))
                   :followup_action (or (:semantic_usage_feedback/followup_action row) (:followup_action row))
                   :confidence_level (or (:semantic_usage_feedback/confidence_level row) (:confidence_level row))
                   :retrieval_issue_codes (parse-json (or (:semantic_usage_feedback/retrieval_issue_codes row)
                                                          (:retrieval_issue_codes row)))
                   :ground_truth_unit_ids (parse-json (or (:semantic_usage_feedback/ground_truth_unit_ids row)
                                                          (:ground_truth_unit_ids row)))
                   :ground_truth_paths (parse-json (or (:semantic_usage_feedback/ground_truth_paths row)
                                                       (:ground_truth_paths row)))
                   :payload (parse-json (or (:semantic_usage_feedback/payload row) (:payload row)))}))))))

(defn- sink-events [sink opts]
  (let [opts* (normalize-filter-opts opts)]
    (cond
    (instance? InMemoryUsageMetrics sink)
    (->> (emitted-events sink)
         (filter #(event-matches? % opts*))
         vec)

    (instance? PostgresUsageMetrics sink)
    (postgres-events sink opts*)

    :else [])))

(defn- sink-feedback [sink opts]
  (let [opts* (normalize-filter-opts opts)]
    (cond
    (instance? InMemoryUsageMetrics sink)
    (->> (emitted-feedback sink)
         (filter #(feedback-matches? % opts*))
         vec)

    (instance? PostgresUsageMetrics sink)
    (postgres-feedback sink opts*)

    :else [])))

(defn- rate [numerator denominator]
  (if (pos? denominator)
    (/ (double numerator) (double denominator))
    0.0))

(defn- percentile [sorted-values p]
  (when (seq sorted-values)
    (let [n (count sorted-values)
          idx (-> (* p (dec n))
                  Math/ceil
                  int)]
      (nth sorted-values idx))))

(defn- latency-summary [events]
  (let [latencies (->> events
                       (keep :latency_ms)
                       (map long)
                       sort
                       vec)
        total (count latencies)]
    {:count total
     :mean_ms (if (pos? total)
                (/ (reduce + 0 latencies) (double total))
                0.0)
     :p95_ms (or (percentile latencies 0.95) 0)
     :max_ms (or (last latencies) 0)}))

(defn- policy-version-distribution [resolve-events]
  (->> resolve-events
       (keep (fn [event]
               (let [payload (:payload event)
                     policy-id (:policy_id payload)
                     policy-version (:policy_version payload)]
                 (when (and (seq (str policy-id))
                            (seq (str policy-version)))
                   (str policy-id "@" policy-version)))))
       frequencies
       (into (sorted-map))))

(def ^:private calibration-feedback-score
  {"helpful" 1.0
   "partially_helpful" 0.5
   "not_helpful" 0.0
   "abandoned" 0.0})

(def ^:private confidence-level-score
  {"high" 0.9
   "medium" 0.65
   "low" 0.35})

(def ^:private confidence-level-rank
  {"low" 0
   "medium" 1
   "high" 2})

(defn- predicted-confidence [event]
  (or (get-in event [:payload :outcome_summary :confidence_score])
      (get confidence-level-score (:confidence_level event) 0.0)))

(defn- observed-feedback-score [feedback-records]
  (let [scores (->> feedback-records
                    (keep #(get calibration-feedback-score (:feedback_outcome %)))
                    vec)]
    (if (seq scores)
      (/ (reduce + 0.0 scores) (double (count scores)))
      nil)))

(defn- calibration-rows [events feedback]
  (let [feedback-by-key (reduce (fn [acc record]
                                  (if-let [k (correlation-key record)]
                                    (update acc k (fnil conj []) record)
                                    acc))
                                {}
                                feedback)]
    (->> events
         (keep (fn [event]
                 (let [records (get feedback-by-key (correlation-key event) [])
                       observed (observed-feedback-score records)]
                   (when (some? observed)
                     {:confidence_level (:confidence_level event)
                      :predicted (double (predicted-confidence event))
                      :observed observed
                      :feedback_outcomes (->> records (map :feedback_outcome) distinct vec)
                      :retrieval_issue_codes (->> records (mapcat :retrieval_issue_codes) distinct vec)}))))
         vec)))

(defn- calibration-summary [rows]
  (let [rows* (vec rows)
        total (count rows*)
        overall-mae (if (seq rows*)
                      (/ (reduce + 0.0 (map (fn [{:keys [predicted observed]}]
                                              (Math/abs (- predicted observed)))
                                            rows*))
                         (double total))
                      0.0)
        by-level (->> rows*
                      (group-by :confidence_level)
                      (reduce-kv
                       (fn [acc level group-rows]
                         (let [group* (vec group-rows)
                               count* (count group*)
                               predicted-mean (/ (reduce + 0.0 (map :predicted group*)) (double count*))
                               observed-mean (/ (reduce + 0.0 (map :observed group*)) (double count*))
                               mae (/ (reduce + 0.0
                                               (map (fn [{:keys [predicted observed]}]
                                                      (Math/abs (- predicted observed)))
                                                    group*))
                                      (double count*))]
                           (assoc acc level
                                  {:count count*
                                   :mean_predicted_confidence predicted-mean
                                   :observed_feedback_score observed-mean
                                   :mean_absolute_error mae})))
                       {})
                      (into (sorted-map)))]
    {:total_correlated_queries total
     :mean_absolute_error overall-mae
     :by_confidence_level by-level}))

(defn slo-report
  ([sink]
   (slo-report sink {}))
  ([sink opts]
   (let [events (sink-events sink opts)
         index-events (->> events
                           (filter #(contains? #{"create_index" "update_index"} (:operation %)))
                           vec)
         retrieval-events (->> events
                               (filter #(= "resolve_context" (:operation %)))
                               vec)
         cache-events (->> events
                           (filter #(and (= "create_index" (:operation %))
                                         (boolean? (:cache_hit %))))
                           vec)
         degraded-count (count (filter #(or (= "degraded" (:result_status %))
                                            (= "error" (:status %)))
                                       retrieval-events))
         fallback-count (count (filter #(pos? (long (or (get-in % [:payload :fallback_units]) 0)))
                                       retrieval-events))]
     {:scope {:surface (:surface opts)
              :operation (:operation opts)
              :tenant_id (:tenant_id opts)
              :since (:since opts)}
      :totals {:events (count events)
               :index_events (count index-events)
               :retrieval_events (count retrieval-events)}
      :index_latency_ms (latency-summary index-events)
      :retrieval_latency_ms (latency-summary retrieval-events)
      :cache_hit_ratio (rate (count (filter :cache_hit cache-events)) (count cache-events))
      :degraded_rate (rate degraded-count (count retrieval-events))
      :fallback_rate (rate fallback-count (count retrieval-events))
      :policy_version_distribution (policy-version-distribution retrieval-events)})))

(defn- correlation-key [record]
  (cond
    (and (seq (:trace_id record)) (seq (:request_id record)))
    [:trace-request (:trace_id record) (:request_id record)]

    (and (seq (:session_id record)) (seq (:task_id record)))
    [:session-task (:session_id record) (:task_id record)]

    :else nil))

(def ^:private difficult-feedback-outcomes #{"not_helpful" "abandoned"})
(def ^:private difficult-issue-codes
  #{"missing_authority" "wrong_scope" "too_broad" "too_shallow" "confidence_miscalibrated"})

(defn- difficult-case? [event feedback-records]
  (let [feedbacks (vec feedback-records)
        issue-codes (set (mapcat :retrieval_issue_codes feedbacks))
        fallback-units (long (or (get-in event [:payload :fallback_units]) 0))]
    (or (some #(contains? difficult-feedback-outcomes (:feedback_outcome %)) feedbacks)
        (some difficult-issue-codes issue-codes)
        (= "low" (:confidence_level event))
        (= "degraded" (:result_status event))
        (pos? fallback-units))))

(defn- harvest-expected [feedback-records]
  (let [feedbacks (vec feedback-records)
        unit-ids (->> feedbacks (mapcat :ground_truth_unit_ids) distinct vec)
        paths (->> feedbacks (mapcat :ground_truth_paths) distinct vec)]
    (cond-> {}
      (seq unit-ids) (assoc :top_authority_unit_ids unit-ids)
      (seq paths) (assoc :required_paths paths))))

(defn- harvest-query-entry [event feedback-records]
  (let [payload (:payload event)
        query (:query payload)
        expected (harvest-expected feedback-records)
        protected? (difficult-case? event feedback-records)]
    (when (map? query)
      {:query_id (or (:request_id event) (:event_id event))
       :protected_case protected?
       :query query
       :expected expected
       :harvest {:surface (:surface event)
                 :event_id (:event_id event)
                 :occurred_at (:occurred_at event)
                 :trace_id (:trace_id event)
                 :request_id (:request_id event)
                 :session_id (:session_id event)
                 :task_id (:task_id event)
                 :actor_id (:actor_id event)
                 :tenant_id (:tenant_id event)
                 :feedback_outcomes (->> feedback-records (map :feedback_outcome) distinct vec)
                 :retrieval_issue_codes (->> feedback-records (mapcat :retrieval_issue_codes) distinct vec)
                 :selected_unit_ids (:selected_unit_ids payload)
                 :selected_paths (:selected_paths payload)
                 :top_authority_unit_ids (:top_authority_unit_ids payload)
                 :outcome_summary (:outcome_summary payload)}})))

(defn harvest-replay-dataset
  ([sink]
   (harvest-replay-dataset sink {}))
  ([sink opts]
   (let [event-opts (assoc opts :operation "resolve_context")
         events (->> (sink-events sink event-opts)
                     (filter #(= "success" (:status %)))
                     vec)
         feedback (sink-feedback sink event-opts)
         feedback-by-key (reduce (fn [acc record]
                                   (if-let [k (correlation-key record)]
                                     (update acc k (fnil conj []) record)
                                     acc))
                                 {}
                                 feedback)
         queries (->> events
                      (keep (fn [event]
                              (let [feedback-records (get feedback-by-key (correlation-key event) [])]
                                (harvest-query-entry event feedback-records))))
                      vec)]
     {:schema_version "1.0"
      :generated_at (now-iso)
      :source_summary {:surface (:surface opts)
                       :tenant_id (:tenant_id opts)
                       :since (:since opts)
                       :total_events (count events)
                       :total_feedback (count feedback)
                       :harvested_queries (count queries)
                       :protected_cases (count (filter :protected_case queries))}
      :queries queries})))

(defn calibration-report
  ([sink]
   (calibration-report sink {}))
  ([sink opts]
   (let [event-opts (assoc opts :operation "resolve_context")
         events (->> (sink-events sink event-opts)
                     (filter #(= "success" (:status %)))
                     vec)
         feedback (sink-feedback sink event-opts)
         rows (calibration-rows events feedback)]
     {:scope {:surface (:surface opts)
              :tenant_id (:tenant_id opts)
              :since (:since opts)}
      :totals {:events (count events)
               :feedback_records (count feedback)
               :correlated_queries (count rows)}
      :calibration (calibration-summary rows)})))

(defn- feedback-summary [feedback-records]
  {:feedback_outcomes (->> feedback-records (map :feedback_outcome) distinct vec)
   :confidence_levels (->> feedback-records (keep :confidence_level) distinct vec)
   :retrieval_issue_codes (->> feedback-records (mapcat :retrieval_issue_codes) distinct vec)
   :ground_truth_unit_ids (->> feedback-records (mapcat :ground_truth_unit_ids) distinct vec)
   :ground_truth_paths (->> feedback-records (mapcat :ground_truth_paths) distinct vec)})

(defn- weekly-review-entry [event feedback-records]
  (let [payload (:payload event)
        query (:query payload)
        feedback* (vec feedback-records)]
    (when (map? query)
      {:query_id (or (:request_id event) (:event_id event))
       :protected_case (difficult-case? event feedback*)
       :trace {:trace_id (:trace_id event)
               :request_id (:request_id event)
               :session_id (:session_id event)
               :task_id (:task_id event)
               :actor_id (:actor_id event)
               :tenant_id (:tenant_id event)}
       :query query
       :selected_context {:selected_unit_ids (:selected_unit_ids payload)
                          :selected_paths (:selected_paths payload)
                          :top_authority_unit_ids (:top_authority_unit_ids payload)}
       :feedback (feedback-summary feedback*)
       :outcome_summary (:outcome_summary payload)})))

(defn weekly-review-report
  ([sink]
   (weekly-review-report sink {}))
  ([sink opts]
   (let [event-opts (assoc opts :operation "resolve_context")
         events (->> (sink-events sink event-opts)
                     (filter #(= "success" (:status %)))
                     vec)
         feedback (sink-feedback sink event-opts)
         feedback-by-key (reduce (fn [acc record]
                                   (if-let [k (correlation-key record)]
                                     (update acc k (fnil conj []) record)
                                     acc))
                                 {}
                                 feedback)
         entries (->> events
                      (keep (fn [event]
                              (weekly-review-entry event
                                                   (get feedback-by-key (correlation-key event) []))))
                      vec)
         correlated (count (filter #(seq (get-in % [:feedback :feedback_outcomes])) entries))
         protected (count (filter :protected_case entries))
         outcome-counts (->> entries
                             (mapcat #(get-in % [:feedback :feedback_outcomes]))
                             frequencies
                             (into (sorted-map)))
         calibration (calibration-summary (calibration-rows events feedback))]
     {:schema_version "1.0"
      :generated_at (now-iso)
      :scope {:surface (:surface opts)
              :tenant_id (:tenant_id opts)
              :since (:since opts)}
      :summary {:total_queries (count entries)
                :correlated_queries correlated
                :protected_cases protected
                :feedback_outcome_counts outcome-counts}
      :calibration calibration
      :entries entries})))

(defn- strongest-confidence-level [levels]
  (->> levels
       (filter #(contains? confidence-level-rank %))
       (sort-by confidence-level-rank >)
       first))

(defn- protected-expected [entry]
  (let [ground-truth-unit-ids (vec (get-in entry [:feedback :ground_truth_unit_ids]))
        ground-truth-paths (vec (get-in entry [:feedback :ground_truth_paths]))
        query-paths (vec (get-in entry [:query :targets :paths]))
        required-paths (vec (distinct (concat ground-truth-paths query-paths)))
        min-confidence (or (strongest-confidence-level (get-in entry [:feedback :confidence_levels]))
                           "medium")]
    (cond-> {:min_confidence_level min-confidence}
      (seq ground-truth-unit-ids) (assoc :top_authority_unit_ids ground-truth-unit-ids)
      (seq required-paths) (assoc :required_paths required-paths))))

(defn review-report->protected-replay-dataset [review-report]
  (let [entries (->> (:entries review-report)
                     (filter :protected_case)
                     (filter #(map? (:query %)))
                     vec)
        queries (mapv (fn [entry]
                        {:query_id (:query_id entry)
                         :protected_case true
                         :query (:query entry)
                         :expected (protected-expected entry)
                         :source_review {:trace (:trace entry)
                                         :feedback (:feedback entry)
                                         :outcome_summary (:outcome_summary entry)}})
                      entries)]
    {:schema_version "1.0"
     :generated_at (now-iso)
     :source_summary {:weekly_review_entries (count (:entries review-report))
                      :protected_review_entries (count entries)
                      :dataset_queries (count queries)}
     :queries queries}))
