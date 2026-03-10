(ns semantic-code-indexing.mcp.server
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.core :as sci]
            [semantic-code-indexing.runtime.usage-metrics :as usage])
  (:import [java.io ByteArrayOutputStream InputStream OutputStream PushbackInputStream]
           [java.util UUID]))

(def ^:private default-protocol-version "2024-11-05")
(def ^:private server-name "semantic-code-indexing-mcp")
(def ^:private server-version "0.1.0")
(def ^:private default-max-indexes 8)
(def ^:private default-parser-opts
  {:clojure_engine :clj-kondo
   :tree_sitter_enabled false})

(defn- now-ms []
  (System/currentTimeMillis))

(defn- tool-usage-context [state]
  (let [state* @state
        client-info (:client-info state*)]
    (cond-> {:surface "mcp"
             :session_id (:session_id state*)}
      (:name client-info) (assoc :actor_id (:name client-info))
      (:tenant_id state*) (assoc :tenant_id (:tenant_id state*)))))

(defn- record-mcp-event! [state event]
  (when-let [sink (:usage_metrics @state)]
    (usage/safe-record-event! sink (merge (tool-usage-context state) event))))

(defn- usage-fields-for-query [query]
  {:trace_id (get-in query [:trace :trace_id])
   :request_id (get-in query [:trace :request_id])
   :actor_id (or (get-in query [:trace :actor_id])
                 (get-in query [:trace :agent_id]))})

(defn- with-usage-event [payload event-fields]
  (with-meta payload (merge (meta payload) {:usage_event_fields event-fields})))

(defn- log! [& xs]
  (binding [*out* *err*]
    (apply println xs)
    (flush)))

(defn- invalid-request
  ([message]
   (invalid-request message nil))
  ([message details]
   (throw (ex-info message {:type :invalid_request
                            :message message
                            :details details}))))

(defn- forbidden-root [root-path allowed-roots]
  (throw (ex-info "root_path is outside SCI_MCP_ALLOWED_ROOTS"
                  {:type :forbidden_root
                   :message "root_path is outside SCI_MCP_ALLOWED_ROOTS"
                   :details {:root_path root-path
                             :allowed_roots allowed-roots}})))

(defn- index-not-found [index-id]
  (throw (ex-info "index_id not found"
                  {:type :index_not_found
                   :message "index_id not found"
                   :details {:index_id index-id}})))

(defn- parse-allowed-roots [value]
  (let [separator (System/getProperty "path.separator")]
    (->> (str/split (or value "") (re-pattern (java.util.regex.Pattern/quote separator)))
         (remove str/blank?)
         vec)))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- current-working-directory []
  (canonical-path (System/getProperty "user.dir" ".")))

(defn- default-allowed-roots-warning [cwd]
  (str
   "SCI_MCP_ALLOWED_ROOTS is not set; defaulting the MCP allowlist to the current working directory.\n"
   "If you want a different allowlist, set SCI_MCP_ALLOWED_ROOTS or pass --allowed-roots explicitly.\n"
   "\n"
   "Current working directory: " cwd "\n"
   "Current default:\n"
   "   SCI_MCP_ALLOWED_ROOTS=" cwd " clojure -M:mcp\n"
   "Explicit custom root:\n"
   "   SCI_MCP_ALLOWED_ROOTS=<repo-root> clojure -M:mcp\n"
   "CLI override:\n"
   "   clojure -M:mcp --allowed-roots " cwd "\n"
   "\n"
   "The server does not prompt interactively because stdin/stdout are reserved for the MCP protocol."))

(defn- resolve-allowed-roots [allowed-roots-arg]
  (let [configured (->> (parse-allowed-roots (or allowed-roots-arg
                                                 (System/getenv "SCI_MCP_ALLOWED_ROOTS")))
                        (map canonical-path)
                        distinct
                        vec)]
    (if (seq configured)
      configured
      (let [cwd (current-working-directory)]
        (log! (default-allowed-roots-warning cwd))
        [cwd]))))

(defn- parse-args [args]
  (loop [m {} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--allowed-roots" (recur (assoc m :allowed_roots v) rest)
          "--max-indexes" (recur (assoc m :max_indexes (or (some-> v parse-long)
                                                           default-max-indexes))
                                 rest)
          (recur m rest))))))

(defn- normalize-rel-path [path]
  (let [normalized (-> (str path)
                       (str/replace "\\" "/")
                       (str/replace #"^\./" ""))]
    (when (str/blank? normalized)
      (invalid-request "paths entries must be non-empty strings"))
    (when (str/starts-with? normalized "/")
      (invalid-request "paths entries must be relative paths"))
    (when (re-find #"(^|/)\.\.(/|$)" normalized)
      (invalid-request "paths entries must not contain '..' traversal segments"))
    normalized))

(defn- ensure-string [value field-name]
  (when-not (string? value)
    (invalid-request (str field-name " must be a string")))
  value)

(defn- ensure-map-or-nil [value field-name]
  (when (and (some? value) (not (map? value)))
    (invalid-request (str field-name " must be an object")))
  value)

(defn- ensure-boolean-or-nil [value field-name]
  (when (and (some? value) (not (instance? Boolean value)))
    (invalid-request (str field-name " must be a boolean")))
  value)

(defn- ensure-int-or-nil [value field-name]
  (when (and (some? value) (not (integer? value)))
    (invalid-request (str field-name " must be an integer")))
  value)

(defn- ensure-string-coll [value field-name]
  (when-not (sequential? value)
    (invalid-request (str field-name " must be an array of strings")))
  (mapv (fn [entry]
          (ensure-string entry field-name))
        value))

(defn- normalize-paths [paths]
  (when (some? paths)
    (->> (ensure-string-coll paths "paths")
         (map normalize-rel-path)
         distinct
         vec)))

(defn- normalize-unit-ids [unit-ids]
  (when (some? unit-ids)
    (->> (ensure-string-coll unit-ids "unit_ids")
         distinct
         vec)))

(defn- normalize-parser-opts [parser-opts]
  (let [opts (ensure-map-or-nil parser-opts "parser_opts")]
    (if (nil? opts) default-parser-opts opts)))

(defn- sort-data [value]
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

(defn- cache-key [root-path paths parser-opts]
  (pr-str (sort-data {:root_path root-path
                      :paths paths
                      :parser_opts parser-opts})))

(defn- path-within-root? [root-path candidate]
  (let [root (.toPath (io/file root-path))
        path (.toPath (io/file candidate))]
    (or (= root path)
        (.startsWith path root))))

(defn- validate-root-path! [state root-path]
  (let [provided (ensure-string root-path "root_path")
        canonical (canonical-path provided)
        file (io/file canonical)]
    (when-not (.exists file)
      (invalid-request "root_path must exist"))
    (when-not (.isDirectory file)
      (invalid-request "root_path must be a directory"))
    (when-not (some #(path-within-root? % canonical) (:allowed-roots @state))
      (forbidden-root canonical (:allowed-roots @state)))
    canonical))

(defn- index-summary [entry cache-hit?]
  (let [index (:index entry)]
    {:index_id (:index_id entry)
     :snapshot_id (:snapshot_id index)
     :indexed_at (:indexed_at index)
     :root_path (:root_path entry)
     :file_count (count (:files index))
     :unit_count (count (:units index))
     :cache_hit cache-hit?}))

(defn- touch-index! [state index-id]
  (let [ts (now-ms)]
    (swap! state update-in [:indexes-by-id index-id]
           (fn [entry]
             (when entry
               (assoc entry :last_accessed_at ts))))
    (or (get-in @state [:indexes-by-id index-id])
        (index-not-found index-id))))

(defn- remove-evicted-cache-keys [cache-index evicted-ids]
  (reduce-kv (fn [acc k v]
               (if (contains? evicted-ids v)
                 (dissoc acc k)
                 acc))
             cache-index
             cache-index))

(defn- evict-excess! [state]
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

(defn- store-index! [state cache-key-value root-path paths parser-opts index]
  (let [ts (now-ms)
        index-id (str (UUID/randomUUID))
        entry {:index_id index-id
               :cache_key cache-key-value
               :root_path root-path
               :paths paths
               :parser_opts parser-opts
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

(defn- find-cached-entry [state cache-key-value]
  (when-let [index-id (get-in @state [:cache-key->index-id cache-key-value])]
    (when-let [entry (get-in @state [:indexes-by-id index-id])]
      (touch-index! state (:index_id entry)))))

(defn- resolve-entry! [state args]
  (let [index-id (ensure-string (:index_id args) "index_id")]
    (touch-index! state index-id)))

(defn- tool-create-index [state args]
  (when-not (map? args)
    (invalid-request "create_index arguments must be an object"))
  (let [root-path (validate-root-path! state (or (:root_path args) "."))
        paths (normalize-paths (:paths args))
        parser-opts (normalize-parser-opts (:parser_opts args))
        force-rebuild (boolean (ensure-boolean-or-nil (:force_rebuild args) "force_rebuild"))
        cache-key-value (cache-key root-path paths parser-opts)]
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
                                     :usage_metrics (:usage_metrics @state)
                                     :usage_context (tool-usage-context state)
                                     :suppress_usage_metrics true})
            entry (store-index! state cache-key-value root-path paths parser-opts index)]
        (with-usage-event
          (index-summary entry false)
          {:root_path_hash (usage/hash-root-path root-path)
           :file_count (count (get-in entry [:index :files]))
           :unit_count (count (get-in entry [:index :units]))
           :cache_hit false
           :payload {:force_rebuild force-rebuild
                     :paths_count (count paths)
                     :snapshot_id (get-in entry [:index :snapshot_id])}})))))

(defn- tool-repo-map [state args]
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
      (assoc result :index_id (:index_id entry))
      {:root_path_hash (usage/hash-root-path (:root_path entry))
       :payload {:index_id (:index_id entry)
                 :snapshot_id (:snapshot_id result)
                 :file_count (count (:files result))
                 :module_count (count (:modules result))}})))

(defn- tool-resolve-context [state args]
  (when-not (map? args)
    (invalid-request "resolve_context arguments must be an object"))
  (let [entry (resolve-entry! state args)
        query (ensure-map-or-nil (:query args) "query")]
    (when-not query
      (invalid-request "query is required"))
    (let [result (assoc (sci/resolve-context (:index entry) query {:suppress_usage_metrics true})
                        :index_id (:index_id entry))]
      (with-usage-event
        result
        (merge
         (usage-fields-for-query query)
         {:root_path_hash (usage/hash-root-path (:root_path entry))
          :selected_units_count (count (get-in result [:context_packet :relevant_units]))
          :selected_files_count (get-in result [:diagnostics_trace :result :selected_files_count])
          :confidence_level (get-in result [:context_packet :confidence :level])
          :autonomy_posture (get-in result [:guardrail_assessment :autonomy_posture])
          :result_status (get-in result [:diagnostics_trace :result :result_status])
          :raw_fetch_level (get-in result [:diagnostics_trace :result :raw_fetch_level_reached])
          :payload {:index_id (:index_id entry)
                    :warning_count (count (get-in result [:diagnostics_trace :warnings]))
                    :degradation_count (count (get-in result [:diagnostics_trace :degradations]))
                    :estimated_tokens (get-in result [:context_packet :budget :estimated_tokens])}})))))

(defn- tool-impact-analysis [state args]
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

(defn- tool-skeletons [state args]
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

(def ^:private tool-definitions
  [{:name "create_index"
    :description "Index a repository root or reuse a cached index. Call this first before repo navigation, code retrieval, impact analysis, or skeleton fetches."
    :inputSchema {:type "object"
                  :properties {"root_path" {:type "string"}
                               "paths" {:type "array" :items {:type "string"}}
                               "parser_opts" {:type "object"}
                               "force_rebuild" {:type "boolean"}}
                  :required ["root_path"]
                  :additionalProperties false}}
   {:name "repo_map"
    :description "Return a compact repository map for high-level codebase navigation, highlighting important files and modules without loading full source files."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "max_files" {:type "integer"}
                               "max_modules" {:type "integer"}}
                  :required ["index_id"]
                  :additionalProperties false}}
   {:name "resolve_context"
    :description "Find the most relevant files, symbols, and code context for a coding task or question, and return diagnostics and guardrails for downstream agent use."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "query" {:type "object"}}
                  :required ["index_id" "query"]
                  :additionalProperties false}}
   {:name "impact_analysis"
    :description "Estimate which files, symbols, or semantic units are likely affected by a proposed change, bug fix, refactor, or target query."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "query" {:type "object"}}
                  :required ["index_id" "query"]
                  :additionalProperties false}}
   {:name "skeletons"
    :description "Return lightweight code skeletons for selected files or semantic units so an agent can inspect structure and signatures without loading full file contents."
    :inputSchema {:type "object"
                  :properties {"index_id" {:type "string"}
                               "paths" {:type "array" :items {:type "string"}}
                               "unit_ids" {:type "array" :items {:type "string"}}}
                  :required ["index_id"]
                  :additionalProperties false}}])

(def ^:private tool-handlers
  {"create_index" tool-create-index
   "repo_map" tool-repo-map
   "resolve_context" tool-resolve-context
   "impact_analysis" tool-impact-analysis
   "skeletons" tool-skeletons})

(defn- format-json [payload]
  (json/write-str payload :escape-slash false))

(defn- tool-success [payload]
  {:content [{:type "text"
              :text (format-json payload)}]
   :structuredContent payload})

(defn- tool-error [message details]
  {:content [{:type "text"
              :text message}]
   :structuredContent (cond-> {:message message}
                        (some? details) (assoc :details details))
   :isError true})

(defn- exception->tool-result [e]
  (let [data (ex-data e)
        error-code (case (:type data)
                     :invalid_request "invalid_request"
                     :forbidden_root "forbidden_root"
                     :index_not_found "index_not_found"
                     "internal_error")
        message (or (:message data) (.getMessage e) "internal error")
        details (cond-> {:code error-code}
                  (:details data) (assoc :details (:details data)))]
    (tool-error message details)))

(defn- headers-complete? [^bytes bytes]
  (let [n (alength bytes)]
    (or (and (>= n 4)
             (= 13 (aget bytes (- n 4)))
             (= 10 (aget bytes (- n 3)))
             (= 13 (aget bytes (- n 2)))
             (= 10 (aget bytes (- n 1))))
        (and (>= n 2)
             (= 10 (aget bytes (- n 2)))
             (= 10 (aget bytes (- n 1)))))))

(defn- header-terminator-length [^bytes bytes]
  (let [n (alength bytes)]
    (cond
      (and (>= n 4)
           (= 13 (aget bytes (- n 4)))
           (= 10 (aget bytes (- n 3)))
           (= 13 (aget bytes (- n 2)))
           (= 10 (aget bytes (- n 1))))
      4

      (and (>= n 2)
           (= 10 (aget bytes (- n 2)))
           (= 10 (aget bytes (- n 1))))
      2

      :else
      nil)))

(defn- read-header-block [^InputStream input-stream]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read input-stream)]
        (cond
          (= -1 b)
          (if (zero? (.size buffer))
            nil
            (throw (ex-info "unexpected EOF while reading MCP headers" {:type :protocol_error})))

          :else
          (do
            (.write buffer b)
            (let [bytes (.toByteArray buffer)]
              (if (headers-complete? bytes)
                (String. bytes 0 (- (alength bytes) (header-terminator-length bytes)) "UTF-8")
                (recur)))))))))

(defn- read-json-line-text [^PushbackInputStream input-stream first-byte]
  (let [buffer (ByteArrayOutputStream.)]
    (.write buffer first-byte)
    (loop []
      (let [b (.read input-stream)]
        (cond
          (= -1 b)
          (str/trim (String. (.toByteArray buffer) "UTF-8"))

          (= 10 b)
          (str/trim (String. (.toByteArray buffer) "UTF-8"))

          :else
          (do
            (.write buffer b)
            (recur)))))))

(defn- read-next-byte [^PushbackInputStream input-stream]
  (loop []
    (let [b (.read input-stream)]
      (cond
        (= -1 b) nil
        (contains? #{9 10 13 32} b) (recur)
        :else b))))

(defn- parse-headers [header-text]
  (reduce (fn [acc line]
            (let [[k v] (str/split line #":" 2)]
              (assoc acc (str/lower-case (str/trim k)) (str/trim v))))
          {}
          (remove str/blank? (str/split-lines header-text))))

(defn- read-body-bytes [^InputStream input-stream length]
  (let [buffer (byte-array length)]
    (loop [offset 0]
      (if (= offset length)
        buffer
        (let [read-count (.read input-stream buffer offset (- length offset))]
          (when (= -1 read-count)
            (throw (ex-info "unexpected EOF while reading MCP body" {:type :protocol_error})))
          (recur (+ offset read-count)))))))

(defn- read-framed-message! [^InputStream input-stream]
  (when-let [header-text (read-header-block input-stream)]
    (let [headers (parse-headers header-text)
          content-length (some-> (get headers "content-length") parse-long)]
      (when-not content-length
        (throw (ex-info "missing Content-Length header" {:type :protocol_error})))
      (let [body-bytes (read-body-bytes input-stream (int content-length))
            body-text (String. ^bytes body-bytes "UTF-8")]
        (json/read-str body-text :key-fn keyword)))))

(defn- read-message! [^PushbackInputStream input-stream]
  (when-let [first-byte (read-next-byte input-stream)]
    (if (= (int \{) first-byte)
      {:transport-format :line
       :message (json/read-str (read-json-line-text input-stream first-byte) :key-fn keyword)}
      (do
        (.unread input-stream first-byte)
        {:transport-format :headers
         :message (read-framed-message! input-stream)}))))

(defn- send-message! [^OutputStream output-stream transport-format payload]
  (case transport-format
    :line
    (let [line-bytes (.getBytes (str (format-json payload) "\n") "UTF-8")]
      (.write output-stream line-bytes)
      (.flush output-stream))

    (let [body-bytes (.getBytes (format-json payload) "UTF-8")
          header-bytes (.getBytes (str "Content-Length: " (count body-bytes) "\r\n\r\n") "UTF-8")]
      (.write output-stream header-bytes)
      (.write output-stream body-bytes)
      (.flush output-stream))))

(defn- jsonrpc-success [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn- jsonrpc-error [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code
           :message message}})

(defn- negotiate-protocol-version [message]
  (let [requested (get-in message [:params :protocolVersion])]
    (if (and (string? requested)
             (not (str/blank? requested)))
      requested
      default-protocol-version)))

(defn- handle-tools-call [state params]
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
                   :payload {:error_class (.getName (class e))
                             :error_message (.getMessage e)}}))
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
                     :details {:name tool-name}})))))

(defn- handle-message! [state ^OutputStream output-stream transport-format message]
  (let [id (:id message)
        method (:method message)]
    (when-not (= "2.0" (:jsonrpc message))
      (when (contains? message :id)
        (send-message! output-stream transport-format (jsonrpc-error id -32600 "jsonrpc must be 2.0")))
      nil)
    (when (= "2.0" (:jsonrpc message))
      (case method
        "initialize"
        (do
          (swap! state assoc :client-info (get-in message [:params :clientInfo]))
          (send-message! output-stream transport-format
                         (jsonrpc-success id {:protocolVersion (negotiate-protocol-version message)
                                              :capabilities {:tools {}}
                                              :serverInfo {:name server-name
                                                           :version server-version}})))

        "notifications/initialized"
        (swap! state assoc :initialized? true)

        "ping"
        (when (contains? message :id)
          (send-message! output-stream transport-format (jsonrpc-success id {})))

        "tools/list"
        (send-message! output-stream transport-format
                       (jsonrpc-success id {:tools tool-definitions}))

        "tools/call"
        (send-message! output-stream transport-format
                       (jsonrpc-success id (handle-tools-call state (:params message))))

        (when (contains? message :id)
          (send-message! output-stream transport-format (jsonrpc-error id -32601 (str "method not found: " method))))))))

(defn start-server-loop! [{:keys [allowed-roots max-indexes usage_metrics]
                           :or {max-indexes default-max-indexes}}]
  (let [state (atom {:initialized? false
                     :transport-format nil
                     :allowed-roots allowed-roots
                     :max-indexes max-indexes
                     :session_id (str (UUID/randomUUID))
                     :usage_metrics usage_metrics
                     :indexes-by-id {}
                     :cache-key->index-id {}})
        input-stream (PushbackInputStream. System/in 8)
        output-stream System/out]
    (loop []
      (let [outcome (try
                      (if-let [{:keys [transport-format message]} (read-message! input-stream)]
                        (do
                          (swap! state assoc :transport-format transport-format)
                          (handle-message! state output-stream transport-format message)
                          :continue)
                        :eof)
                      (catch Exception e
                        (if (= :protocol_error (:type (ex-data e)))
                          (do
                            (log! "mcp_protocol_error" (.getMessage e))
                            (send-message! output-stream
                                           (or (:transport-format @state) :headers)
                                           (jsonrpc-error nil -32700 (.getMessage e)))
                            :continue)
                          (do
                            (log! "mcp_internal_error" (.getMessage e))
                            (throw e)))))]
        (when (= :continue outcome)
          (recur))))))

(defn -main [& args]
  (let [{:keys [allowed_roots max_indexes]} (parse-args args)
        allowed-roots (resolve-allowed-roots allowed_roots)
        max-indexes (or max_indexes
                        (some-> (System/getenv "SCI_MCP_MAX_INDEXES") parse-long)
                        default-max-indexes)
        usage-metrics (when-let [jdbc-url (System/getenv "SCI_USAGE_METRICS_JDBC_URL")]
                        (sci/postgres-usage-metrics {:jdbc-url jdbc-url
                                                     :user (System/getenv "SCI_USAGE_METRICS_DB_USER")
                                                     :password (System/getenv "SCI_USAGE_METRICS_DB_PASSWORD")}))]
    (log! "semantic_code_indexing_mcp_started" {:allowed_roots allowed-roots
                                                :max_indexes max-indexes})
    (when usage-metrics
      (usage/safe-record-event! usage-metrics {:surface "mcp"
                                               :operation "server_start"
                                               :status "success"
                                               :payload {:allowed_root_count (count allowed-roots)
                                                         :max_indexes max-indexes}}))
    (start-server-loop! {:allowed-roots allowed-roots
                         :max-indexes max-indexes
                         :usage_metrics usage-metrics})))
