(ns semidx.mcp-server-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.mcp.server :as mcp-server])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.time Duration Instant]))

(def ^:private response-timeout-ms 30000)
(def ^:private process-shutdown-timeout-seconds 2)

(defn- log-step! [& xs]
  (binding [*out* *err*]
    (apply println "[mcp-test]" xs)
    (flush)))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n"))

(def sample-query
  {:api_version "1.0"
   :schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate the authority implementation for process-order."}
   :targets {:symbols ["my.app.order/process-order"]
             :paths ["src/my/app/order.clj"]}
   :constraints {:token_budget 1200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:focus_on_tests true
           :prefer_definitions_over_callers true}
   :options {:include_tests true
             :include_impact_hints true
             :allow_raw_code_escalation false}
   :trace {:trace_id "55555555-5555-4555-8555-555555555555"
           :request_id "mcp-server-test-001"
           :actor_id "test_runner"}})

(def sample-shorthand-query
  {:intent "Find the main orchestration flow and key entrypoints."})

(def sample-intent-shorthand
  "Find the main orchestration flow and key entrypoints.")

(defn- headers-complete? [^bytes bytes]
  (let [n (alength bytes)]
    (and (>= n 4)
         (= 13 (aget bytes (- n 4)))
         (= 10 (aget bytes (- n 3)))
         (= 13 (aget bytes (- n 2)))
         (= 10 (aget bytes (- n 1))))))

(defn- read-header-block [^InputStream input-stream]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read input-stream)]
        (cond
          (= -1 b)
          (if (zero? (.size buffer))
            nil
            (throw (ex-info "unexpected EOF while reading MCP headers" {})))

          :else
          (do
            (.write buffer b)
            (let [bytes (.toByteArray buffer)]
              (if (headers-complete? bytes)
                (String. bytes 0 (- (alength bytes) 4) "UTF-8")
                (recur)))))))))

(defn- parse-headers [header-text]
  (reduce (fn [acc line]
            (let [[k v] (str/split line #":" 2)]
              (assoc acc (str/lower-case (str/trim k))
                     (str/trim v))))
          {}
          (remove str/blank? (str/split-lines header-text))))

(defn- read-body-bytes [^InputStream input-stream length]
  (let [buffer (byte-array length)]
    (loop [offset 0]
      (if (= offset length)
        buffer
        (let [read-count (.read input-stream buffer offset (- length offset))]
          (when (= -1 read-count)
            (throw (ex-info "unexpected EOF while reading MCP body" {})))
          (recur (+ offset read-count)))))))

(defn- read-message! [^InputStream input-stream]
  (when-let [header-text (read-header-block input-stream)]
    (let [content-length (some-> (get (parse-headers header-text) "content-length") parse-long)
          body (String. ^bytes (read-body-bytes input-stream (int content-length)) "UTF-8")]
      (json/read-str body :key-fn keyword))))

(defn- read-line-message! [^InputStream input-stream]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read input-stream)]
        (cond
          (= -1 b)
          (if (zero? (.size buffer))
            nil
            (json/read-str (str/trim (String. (.toByteArray buffer) "UTF-8")) :key-fn keyword))

          (= 10 b)
          (json/read-str (str/trim (String. (.toByteArray buffer) "UTF-8")) :key-fn keyword)

          :else
          (do
            (.write buffer b)
            (recur)))))))

(defn- recent-stderr [handle]
  (some->> (:stderr-lines handle)
           deref
           (take-last 20)
           seq
           (str/join "\n")))

(defn- ex-with-stderr [message handle details]
  (ex-info message
           (cond-> details
             (recent-stderr handle) (assoc :stderr_tail (recent-stderr handle)))))

(defn- send-message! [handle payload]
  (let [body-bytes (.getBytes (json/write-str payload :escape-slash false) "UTF-8")
        header-bytes (.getBytes (str "Content-Length: " (count body-bytes) "\r\n\r\n") "UTF-8")
        out (.getOutputStream ^Process (:proc handle))]
    (log-step! "send" (pr-str (select-keys payload [:id :method])))
    (.write out header-bytes)
    (.write out body-bytes)
    (.flush out)))

(defn- send-line-message! [handle payload]
  (let [line-bytes (.getBytes (str (json/write-str payload :escape-slash false) "\n") "UTF-8")
        out (.getOutputStream ^Process (:proc handle))]
    (log-step! "send-line" (pr-str (select-keys payload [:id :method])))
    (.write out line-bytes)
    (.flush out)))

(defn- wait-for-response [handle expected-id timeout-ms]
  (let [deadline (.plusMillis (Instant/now) timeout-ms)
        input-stream (.getInputStream ^Process (:proc handle))]
    (log-step! "wait" (str "id=" expected-id) (str "timeout_ms=" timeout-ms))
    (loop []
      (let [remaining (Duration/between (Instant/now) deadline)
            millis (.toMillis remaining)]
        (when (neg? millis)
          (throw (ex-with-stderr (str "timed out waiting for response id=" expected-id)
                                 handle
                                 {:expected_id expected-id
                                  :timeout_ms timeout-ms})))
        (let [message (deref (future (read-message! input-stream)) millis ::timeout)]
          (when (= ::timeout message)
            (throw (ex-with-stderr (str "timed out waiting for response id=" expected-id)
                                   handle
                                   {:expected_id expected-id
                                    :timeout_ms timeout-ms})))
          (log-step! "recv" (pr-str (select-keys message [:id :method])))
          (if (= expected-id (:id message))
            message
            (recur)))))))

(defn- wait-for-line-response [handle expected-id timeout-ms]
  (let [deadline (.plusMillis (Instant/now) timeout-ms)
        input-stream (.getInputStream ^Process (:proc handle))]
    (log-step! "wait-line" (str "id=" expected-id) (str "timeout_ms=" timeout-ms))
    (loop []
      (let [remaining (Duration/between (Instant/now) deadline)
            millis (.toMillis remaining)]
        (when (neg? millis)
          (throw (ex-with-stderr (str "timed out waiting for line response id=" expected-id)
                                 handle
                                 {:expected_id expected-id
                                  :timeout_ms timeout-ms})))
        (let [message (deref (future (read-line-message! input-stream)) millis ::timeout)]
          (when (= ::timeout message)
            (throw (ex-with-stderr (str "timed out waiting for line response id=" expected-id)
                                   handle
                                   {:expected_id expected-id
                                    :timeout_ms timeout-ms})))
          (log-step! "recv-line" (pr-str (select-keys message [:id :method])))
          (if (= expected-id (:id message))
            message
            (recur)))))))

(defn- start-stderr-capture! [^Process proc]
  (let [lines (atom [])]
    {:stderr-lines lines
     :stderr-future
     (future
       (with-open [rdr (io/reader (.getErrorStream proc))]
         (doseq [line (line-seq rdr)]
           (swap! lines #(->> (conj % line) (take-last 200) vec))
           (log-step! "server-stderr" line))))}))

(defn- start-mcp-process! [opts]
  (let [{:keys [directory legacy-allowed-root-env]
         :as opts
         :or {directory "."}} opts
        builder (ProcessBuilder. ["clojure" "-M:mcp"])
        env (.environment builder)]
    (.directory builder (io/file directory))
    (if-let [user-dir (:user-dir opts)]
      (.put env "JAVA_TOOL_OPTIONS" (str "-Duser.dir=" user-dir))
      (.remove env "JAVA_TOOL_OPTIONS"))
    (if legacy-allowed-root-env
      (.put env "SEMIDX_MCP_ALLOWED_ROOTS" legacy-allowed-root-env)
      (.remove env "SEMIDX_MCP_ALLOWED_ROOTS"))
    (if-let [policy-registry-file (:policy-registry-file opts)]
      (.put env "SEMIDX_MCP_POLICY_REGISTRY_FILE" policy-registry-file)
      (.remove env "SEMIDX_MCP_POLICY_REGISTRY_FILE"))
    (.put env "SEMIDX_MCP_MAX_INDEXES" "4")
    (log-step! "start-process"
               (pr-str {:directory (.getPath (io/file directory))
                        :legacy-allowed-root-env legacy-allowed-root-env
                        :policy-registry-file (:policy-registry-file opts)
                        :user-dir (:user-dir opts)
                        :max-indexes "4"}))
    (let [proc (.start builder)
          stderr-capture (start-stderr-capture! proc)]
      (assoc stderr-capture :proc proc))))

(defn- destroy-process! [handle]
  (when-let [^Process proc (:proc handle)]
    (log-step! "destroy-process")
    (.destroy proc)
    (when-not (.waitFor proc process-shutdown-timeout-seconds java.util.concurrent.TimeUnit/SECONDS)
      (log-step! "destroy-process" "forcing")
      (.destroyForcibly proc)
      (.waitFor proc process-shutdown-timeout-seconds java.util.concurrent.TimeUnit/SECONDS))
    (when-let [stderr-future (:stderr-future handle)]
      (deref stderr-future 1000 nil))))

(defn- initialize!
  ([handle]
   (initialize! handle "2024-11-05"))
  ([handle protocol-version]
   (log-step! "initialize" "begin")
   (send-message! handle {:jsonrpc "2.0"
                          :id 1
                          :method "initialize"
                          :params {:protocolVersion protocol-version
                                   :capabilities {}
                                   :clientInfo {:name "semidx-test"
                                                :version "1.0"}}})
   (let [response (wait-for-response handle 1 response-timeout-ms)]
     (send-message! handle {:jsonrpc "2.0"
                            :method "notifications/initialized"
                            :params {}})
     (log-step! "initialize" "done")
     response)))

(defn- call-tool! [handle request-id tool-name arguments]
  (log-step! "tool-call" tool-name (str "request_id=" request-id))
  (send-message! handle {:jsonrpc "2.0"
                         :id request-id
                         :method "tools/call"
                         :params {:name tool-name
                                  :arguments arguments}})
  (wait-for-response handle request-id response-timeout-ms))

(deftest runtime-mcp-server-conformance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        external-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-external-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        _ (create-sample-repo! external-root)
        handle (start-mcp-process! {:directory "."})]
    (try
      (testing "initialize and tools/list"
        (let [init-response (initialize! handle)]
          (is (= "2024-11-05" (get-in init-response [:result :protocolVersion])))
          (is (some #(str/includes? % ":max_indexes 4")
                    @(:stderr-lines handle))))
        (send-message! handle {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
          (let [tools-response (wait-for-response handle 2 response-timeout-ms)
              tools (get-in tools-response [:result :tools])
              resolve-tool (some #(when (= "resolve_context" (:name %)) %) tools)
              tool-names (->> tools
                              (map :name)
                              set)]
          (is (= #{"create_index" "repo_map" "resolve_context" "expand_context" "fetch_context_detail" "literal_file_slice" "impact_analysis" "skeletons" "health"}
                 tool-names))
          (is (str/includes? (some->> tools
                                      (filter #(= "create_index" (:name %)))
                                      first
                                      :description)
                             "ALWAYS call this first"))
          (is (str/includes? (some->> tools
                                      (filter #(= "repo_map" (:name %)))
                                      first
                                      :description)
                             "immediately after create_index"))
          (is (str/includes? (some->> tools
                                      (filter #(= "resolve_context" (:name %)))
                                      first
                                      :description)
                             "INSTEAD OF grep"))
          (is (= "string" (get-in resolve-tool [:inputSchema :properties :intent :type])))
          (is (= ["index_id"] (get-in resolve-tool [:inputSchema :required])))
          (is (= "object" (get-in resolve-tool [:inputSchema :properties :query :type])))
          (is (= ["intent"] (get-in resolve-tool [:inputSchema :properties :query :required])))
          (is (= ["purpose"] (get-in resolve-tool [:inputSchema :properties :query :properties :intent :required])))
          (is (= "object" (get-in resolve-tool [:inputSchema :properties :query :properties :targets :type])))))

      (testing "health omits internal root restrictions"
        (let [health-response (call-tool! handle 101 "health" {})
              health-data (get-in health-response [:result :structuredContent])]
          (is (= "ok" (:status health-data)))
          (is (string? (:session_id health-data)))
          (is (integer? (:uptime_ms health-data)))
          (is (contains? health-data :index_count))
          (is (not (contains? health-data :allowed_roots)))))

      (let [create-response (call-tool! handle 3 "create_index" {:root_path tmp-root})
            create-data (get-in create-response [:result :structuredContent])
            index-id (:index_id create-data)]
        (testing "create_index returns a reusable handle"
          (is (false? (:cache_hit create-data)))
          (is (string? index-id))
          (is (string? (:snapshot_id create-data)))
          (is (= "initial_build" (get-in create-data [:index_lifecycle :rebuild_reason])))
          (is (pos-int? (:file_count create-data)))
          (is (pos-int? (:unit_count create-data)))
          (is (= "repo_map" (:recommended_next_step create-data)))
          (is (= ["create_index" "repo_map" "resolve_context" "expand_context" "fetch_context_detail"]
                 (:recommended_flow create-data)))
          (is (string? (:usage_hint create-data))))

        (testing "create_index auto-relativizes absolute paths"
          (let [abs-path (str tmp-root "/src/my/app/order.clj")
                abs-response (call-tool! handle 40 "create_index" {:root_path tmp-root
                                                                    :paths [abs-path]})
                abs-data (get-in abs-response [:result :structuredContent])]
            (is (string? (:index_id abs-data)))
            (is (not (true? (:isError (:result abs-response)))))))

        (testing "cache hits reuse the same index_id"
          (let [cached-response (call-tool! handle 4 "create_index" {:root_path tmp-root})
                cached-data (get-in cached-response [:result :structuredContent])]
            (is (true? (:cache_hit cached-data)))
            (is (= index-id (:index_id cached-data)))))

        (testing "repo_map resolves by index_id"
          (let [repo-map-response (call-tool! handle 5 "repo_map" {:index_id index-id})
                repo-map-data (get-in repo-map-response [:result :structuredContent])]
            (is (= index-id (:index_id repo-map-data)))
            (is (seq (:files repo-map-data)))
            (is (map? (:index_lifecycle repo-map-data)))
            (is (string? (:summary repo-map-data)))
            (is (= "resolve_context" (:recommended_next_step repo-map-data)))))

        (testing "resolve_context returns compact selection"
          (let [resolve-response (call-tool! handle 6 "resolve_context" {:index_id index-id
                                                                         :query sample-query})
                resolve-data (get-in resolve-response [:result :structuredContent])]
            (is (= index-id (:index_id resolve-data)))
            (is (string? (:selection_id resolve-data)))
            (is (string? (:snapshot_id resolve-data)))
            (is (= "completed" (:result_status resolve-data)))
            (is (vector? (:focus resolve-data)))
            (is (= "expand_context" (:recommended_next_step resolve-data)))
            (is (= "selection_artifact"
                   (get-in resolve-data [:compact_continuation :continuation_mode])))
            (is (= (:selection_id resolve-data)
                   (get-in resolve-data [:compact_continuation :selection_id])))
            (is (= (:snapshot_id resolve-data)
                   (get-in resolve-data [:compact_continuation :snapshot_id])))
            (is (= "expand_context"
                   (get-in resolve-data [:compact_continuation :next_tool])))
            (is (= ["expand_context" "fetch_context_detail"]
                   (get-in resolve-data [:next_step :available_actions])))
            (is (some #(= "my.app.order/process-order" (:symbol %))
                      (:focus resolve-data)))))

        (testing "resolve_context normalizes narrow MCP shorthand"
          (let [resolve-response (call-tool! handle 66 "resolve_context" {:index_id index-id
                                                                          :query sample-shorthand-query})
                resolve-data (get-in resolve-response [:result :structuredContent])]
            (is (= index-id (:index_id resolve-data)))
            (is (true? (:query_normalized resolve-data)))
            (is (= "mcp_shorthand" (:query_ingress_mode resolve-data)))
            (is (= "code_understanding"
                   (get-in resolve-data [:normalized_query_summary :purpose])))
            (is (= ["paths"]
                   (get-in resolve-data [:normalized_query_summary :target_keys])))
            (is (string? (:selection_id resolve-data)))))

        (testing "invalid shorthand returns a repair-oriented error"
          (let [resolve-response (call-tool! handle 67 "resolve_context" {:index_id index-id
                                                                          :query {}})
                result (:result resolve-response)]
            (is (true? (:isError result)))
            (is (= "invalid_query"
                   (get-in result [:structuredContent :details :code])))
            (is (= "retry_resolve_context_with_structured_query"
                   (get-in result [:structuredContent :details :details :recommended_next_step])))
            (is (= #{"schema_version" "intent" "targets" "constraints" "hints" "options" "trace"}
                   (set (get-in result [:structuredContent :details :details :missing_sections]))))
            (is (= "code_understanding"
                   (get-in result [:structuredContent :details :details :minimal_query_skeleton :intent :purpose])))))

        (testing "invalid shorthand with malformed fields returns invalid_field_paths"
          (let [resolve-response (call-tool! handle 68 "resolve_context" {:index_id index-id
                                                                          :query {:intent 42
                                                                                  :targets "not-a-map"}})
                result (:result resolve-response)]
            (is (true? (:isError result)))
            (is (seq (get-in result [:structuredContent :details :details :invalid_field_paths])))
            (is (some #(= "intent" (:path %))
                      (get-in result [:structuredContent :details :details :invalid_field_paths])))
            (is (some #(= "targets" (:path %))
                      (get-in result [:structuredContent :details :details :invalid_field_paths])))))

        (testing "resolve_context accepts top-level intent string shorthand"
          (let [resolve-response (call-tool! handle 75 "resolve_context" {:index_id index-id
                                                                          :intent sample-intent-shorthand})
                resolve-data (get-in resolve-response [:result :structuredContent])]
            (is (= index-id (:index_id resolve-data)))
            (is (true? (:query_normalized resolve-data)))
            (is (= "intent_shorthand" (:query_ingress_mode resolve-data)))
            (is (string? (:selection_id resolve-data)))))

        (testing "resolve_context rejects missing both intent and query"
          (let [resolve-response (call-tool! handle 76 "resolve_context" {:index_id index-id})
                result (:result resolve-response)]
            (is (true? (:isError result)))
            (is (str/includes? (get-in result [:structuredContent :message])
                               "either intent (string) or query (object) is required"))))

        (testing "resolve_context prefers query over intent when both provided"
          (let [resolve-response (call-tool! handle 77 "resolve_context" {:index_id index-id
                                                                          :intent "ignored"
                                                                          :query sample-query})
                resolve-data (get-in resolve-response [:result :structuredContent])]
            (is (= "canonical" (:query_ingress_mode resolve-data)))))

        (testing "fetch_context_detail rejects invalid detail_level"
          (let [resolve-response (call-tool! handle 69 "resolve_context" {:index_id index-id
                                                                          :query sample-query})
                resolve-data (get-in resolve-response [:result :structuredContent])
                detail-response (call-tool! handle 70 "fetch_context_detail" {:index_id index-id
                                                                              :selection_id (:selection_id resolve-data)
                                                                              :snapshot_id (:snapshot_id resolve-data)
                                                                              :detail_level "full"})
                result (:result detail-response)]
            (is (true? (:isError result)))
            (is (= "invalid_request"
                   (get-in result [:structuredContent :details :code])))
            (is (str/includes? (get-in result [:structuredContent :message])
                               "detail_level"))))

        (testing "expand_context and fetch_context_detail return staged artifacts"
          (let [resolve-response (call-tool! handle 61 "resolve_context" {:index_id index-id
                                                                          :query sample-query})
                resolve-data (get-in resolve-response [:result :structuredContent])
                selection-id (:selection_id resolve-data)
                snapshot-id (:snapshot_id resolve-data)
                expand-response (call-tool! handle 62 "expand_context" {:index_id index-id
                                                                        :selection_id selection-id
                                                                        :snapshot_id snapshot-id})
                expand-data (get-in expand-response [:result :structuredContent])
                detail-response (call-tool! handle 63 "fetch_context_detail" {:index_id index-id
                                                                              :selection_id selection-id
                                                                              :snapshot_id snapshot-id})
                detail-data (get-in detail-response [:result :structuredContent])]
            (is (= index-id (:index_id expand-data)))
            (is (seq (:skeletons expand-data)))
            (is (map? (:impact_hints expand-data)))
            (is (= "fetch_context_detail" (:recommended_next_step expand-data)))
            (is (= "fetch_context_detail"
                   (get-in expand-data [:compact_continuation :next_tool])))
            (is (= selection-id
                   (get-in expand-data [:compact_continuation :selection_id])))
            (is (= snapshot-id
                   (get-in expand-data [:compact_continuation :snapshot_id])))
            (is (= index-id (:index_id detail-data)))
            (is (map? (:context_packet detail-data)))
            (is (map? (:guardrail_assessment detail-data)))
            (is (vector? (:stage_events detail-data)))
            (is (= "resolve_context" (:recommended_next_step detail-data)))
            (is (= "resolve_context"
                   (get-in detail-data [:compact_continuation :next_tool])))
            (is (= selection-id
                   (get-in detail-data [:compact_continuation :selection_id])))
            (is (= snapshot-id
                   (get-in detail-data [:compact_continuation :snapshot_id])))
            (is (some #(= "my.app.order/process-order" (:symbol %))
                      (get-in detail-data [:context_packet :relevant_units])))))

        (testing "literal_file_slice returns exact edit context"
          (let [literal-response (call-tool! handle 64 "literal_file_slice" {:index_id index-id
                                                                             :snapshot_id (:snapshot_id create-data)
                                                                             :path "src/my/app/order.clj"
                                                                             :start_line 3
                                                                             :end_line 4})
                literal-data (get-in literal-response [:result :structuredContent])]
            (is (= index-id (:index_id literal-data)))
            (is (= "literal_slice" (:projection_profile literal-data)))
            (is (= {:start_line 3 :end_line 4} (:returned_range literal-data)))
            (is (str/includes? (:content literal-data) "process-order"))
            (is (= "resolve_context" (:recommended_next_step literal-data)))))

        (testing "impact_analysis wraps impact_hints"
          (let [impact-response (call-tool! handle 7 "impact_analysis" {:index_id index-id
                                                                        :query sample-query})
                impact-data (get-in impact-response [:result :structuredContent])]
            (is (= index-id (:index_id impact-data)))
            (is (map? (:impact_hints impact-data)))
            (is (contains? (:impact_hints impact-data) :callers))))

        (testing "skeletons returns selected unit skeletons"
          (let [skeletons-response (call-tool! handle 8 "skeletons" {:index_id index-id
                                                                     :paths ["src/my/app/order.clj"]})
                skeletons-data (get-in skeletons-response [:result :structuredContent])]
            (is (= index-id (:index_id skeletons-data)))
            (is (seq (:skeletons skeletons-data)))))

        (testing "force_rebuild returns a new handle"
          (let [rebuild-response (call-tool! handle 9 "create_index" {:root_path tmp-root
                                                                      :force_rebuild true})
                rebuild-data (get-in rebuild-response [:result :structuredContent])]
            (is (false? (:cache_hit rebuild-data)))
            (is (not= index-id (:index_id rebuild-data)))))

        (testing "unknown index_id is returned as a tool error"
          (let [repo-map-response (call-tool! handle 10 "repo_map" {:index_id "missing-index"})
                result (:result repo-map-response)]
            (is (true? (:isError result)))
            (is (= "index_not_found"
                   (get-in result [:structuredContent :details :code])))
            (is (= "not_found"
                   (get-in result [:structuredContent :details :category])))))

        (testing "create_index accepts another root without MCP allowlist checks"
          (let [external-response (call-tool! handle 11 "create_index" {:root_path external-root})
                external-data (get-in external-response [:result :structuredContent])]
            (is (false? (:cache_hit external-data)))
            (is (string? (:index_id external-data)))
            (is (= (.getCanonicalPath (io/file external-root))
                   (:root_path external-data)))))

        (testing "invalid selectors are surfaced as tool errors"
          (let [bad-selector-response (call-tool! handle 12 "skeletons" {:index_id index-id})
                result (:result bad-selector-response)]
            (is (true? (:isError result)))
            (is (= "invalid_request"
                   (get-in result [:structuredContent :details :code])))
            (is (= "client"
                   (get-in result [:structuredContent :details :category]))))))

      (finally
        (destroy-process! handle)))))

(deftest runtime-mcp-policy-registry-selection-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-policy-registry-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        registry-path (str (io/file tmp-root "policy-registry.edn"))
        _ (create-sample-repo! tmp-root)
        active-policy {:policy_id "heuristic_v1_mcp_active"
                       :version "2026-03-11"
                       :state "active"
                       :policy {:policy_id "heuristic_v1_mcp_active"
                                :version "2026-03-11"
                                :thresholds {:top_authority_min 500}}}
        shadow-policy {:policy_id "heuristic_v1_mcp_shadow"
                       :version "2026-03-12"
                       :state "shadow"
                       :policy {:policy_id "heuristic_v1_mcp_shadow"
                                :version "2026-03-12"
                                :thresholds {:top_authority_min 500}}}
        _ (spit registry-path (pr-str {:schema_version "1.0"
                                       :policies [active-policy shadow-policy]}))
        handle (start-mcp-process! {:directory "."
                                    :legacy-allowed-root-env tmp-root
                                    :policy-registry-file registry-path})]
    (try
      (initialize! handle)
      (let [create-response (call-tool! handle 31 "create_index" {:root_path tmp-root})
            create-data (get-in create-response [:result :structuredContent])
            index-id (:index_id create-data)]
        (testing "active registry policy is used when resolve_context has no override"
          (let [resolve-response (call-tool! handle 32 "resolve_context" {:index_id index-id
                                                                          :query sample-query})
                resolve-data (get-in resolve-response [:result :structuredContent])
                detail-response (call-tool! handle 34 "fetch_context_detail" {:index_id index-id
                                                                              :selection_id (:selection_id resolve-data)
                                                                              :snapshot_id (:snapshot_id resolve-data)})
                detail-data (get-in detail-response [:result :structuredContent])]
            (is (= "heuristic_v1_mcp_active"
                   (get-in detail-data [:diagnostics_trace :retrieval_policy :policy_id])))
            (is (not= "top_authority"
                      (get-in detail-data [:context_packet :relevant_units 0 :rank_band])))))

        (testing "selector-based override resolves from registry"
          (let [resolve-response (call-tool! handle 33 "resolve_context" {:index_id index-id
                                                                          :query sample-query
                                                                          :retrieval_policy {:policy_id "heuristic_v1_mcp_shadow"
                                                                                             :version "2026-03-12"}})
                resolve-data (get-in resolve-response [:result :structuredContent])
                detail-response (call-tool! handle 35 "fetch_context_detail" {:index_id index-id
                                                                              :selection_id (:selection_id resolve-data)
                                                                              :snapshot_id (:snapshot_id resolve-data)})
                detail-data (get-in detail-response [:result :structuredContent])]
            (is (= "heuristic_v1_mcp_shadow"
                   (get-in detail-data [:diagnostics_trace :retrieval_policy :policy_id])))
            (is (not= "top_authority"
                      (get-in detail-data [:context_packet :relevant_units 0 :rank_band]))))))
      (finally
        (destroy-process! handle)))))

(deftest runtime-mcp-server-line-transport-compatibility-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-line-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        handle (start-mcp-process! {:directory "."})]
    (try
      (send-line-message! handle {:jsonrpc "2.0"
                                  :id 1
                                  :method "initialize"
                                  :params {:protocolVersion "2024-11-05"
                                           :capabilities {}
                                           :clientInfo {:name "line-client"
                                                        :version "1.0"}}})
      (let [init-response (wait-for-line-response handle 1 response-timeout-ms)]
        (is (= "2024-11-05" (get-in init-response [:result :protocolVersion]))))
      (send-line-message! handle {:jsonrpc "2.0"
                                  :method "notifications/initialized"
                                  :params {}})
      (send-line-message! handle {:jsonrpc "2.0"
                                  :id 2
                                  :method "tools/list"
                                  :params {}})
      (let [tools-response (wait-for-line-response handle 2 response-timeout-ms)
            tool-names (->> (get-in tools-response [:result :tools])
                            (map :name)
                            set)]
        (is (= #{"create_index" "repo_map" "resolve_context" "expand_context" "fetch_context_detail" "literal_file_slice" "impact_analysis" "skeletons" "health"}
               tool-names)))
      (finally
        (destroy-process! handle)))))

(deftest runtime-mcp-no-language-guidance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-no-lang" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "README.md" "# none")
        handle (start-mcp-process! {:directory "."})]
    (try
      (initialize! handle)
      (let [create-response (call-tool! handle 71 "create_index" {:root_path tmp-root})
            result (:result create-response)]
        (is (true? (:isError result)))
        (is (= "no_supported_languages_found"
               (get-in result [:structuredContent :details :code])))
        (is (= "awaiting_language_selection"
               (get-in result [:structuredContent :details :details :activation_state]))))
      (finally
        (destroy-process! handle)))))

(deftest runtime-mcp-language-refresh-required-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-refresh" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "app/main.py" "def run(value):\n    return value\n")
        handle (start-mcp-process! {:directory "."})]
    (try
      (initialize! handle)
      (let [create-response (call-tool! handle 81 "create_index" {:root_path tmp-root})
            create-data (get-in create-response [:result :structuredContent])
            _ts (write-file! tmp-root "src/example/main.ts"
                             "export function runTs(value: string): string {\n  return value;\n}\n")
            resolve-response (call-tool! handle 82 "resolve_context" {:index_id (:index_id create-data)
                                                                      :query {:api_version "1.0"
                                                                              :schema_version "1.0"
                                                                              :intent {:purpose "code_understanding"
                                                                                       :details "Locate TS function."}
                                                                              :targets {:paths ["src/example/main.ts"]}
                                                                              :constraints {:token_budget 400
                                                                                            :max_raw_code_level "signature_only"
                                                                                            :freshness "current_snapshot"}
                                                                              :hints {}
                                                                              :options {}
                                                                              :trace {:request_id "mcp-refresh-001"}}})
            result (:result resolve-response)]
        (is (true? (:isError result)))
        (is (= "language_refresh_required"
               (get-in result [:structuredContent :details :code]))))
      (finally
        (destroy-process! handle)))))

(deftest runtime-mcp-server-ignores-legacy-allowed-roots-env-test
  (let [target-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-unrestricted-root" (make-array java.nio.file.attribute.FileAttribute 0)))
        legacy-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-legacy-env-root" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! target-root)
        handle (start-mcp-process! {:directory "."
                                    :legacy-allowed-root-env legacy-root})]
    (try
      (initialize! handle)
      (let [create-response (call-tool! handle 901 "create_index" {:root_path target-root})
            create-data (get-in create-response [:result :structuredContent])]
        (testing "create_index succeeds even when a stale SEMIDX_MCP_ALLOWED_ROOTS env var is present"
          (is (string? (:index_id create-data)))
          (is (= (.getCanonicalPath (io/file target-root))
                 (:root_path create-data)))))
      (finally
        (destroy-process! handle)))))

(deftest initialize-preserves-client-protocol-version-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-version-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        handle (start-mcp-process! {:directory "."})]
    (try
      (testing "legacy Codex protocol version is preserved"
        (is (= "2024-11-05"
               (get-in (initialize! handle "2024-11-05") [:result :protocolVersion]))))
      (finally
        (destroy-process! handle))))
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-version-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        handle (start-mcp-process! {:directory "."})]
    (try
      (testing "arbitrary newer protocol version is echoed back"
        (is (= "2026-02-18"
               (get-in (initialize! handle "2026-02-18") [:result :protocolVersion]))))
      (finally
        (destroy-process! handle)))))
