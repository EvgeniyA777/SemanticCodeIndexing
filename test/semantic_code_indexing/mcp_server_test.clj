(ns semantic-code-indexing.mcp-server-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semantic-code-indexing.mcp.server :as mcp-server])
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
  {:schema_version "1.0"
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
             :allow_raw_code_escalation false
             :favor_compact_packet true
             :favor_higher_recall false}
   :trace {:trace_id "55555555-5555-4555-8555-555555555555"
           :request_id "mcp-server-test-001"
           :actor_id "test_runner"}})

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

(defn- start-mcp-process! [opts-or-root]
  (let [{:keys [directory allowed-root]
         :as opts
         :or {directory "."}}
        (if (map? opts-or-root)
          opts-or-root
          {:directory "."
           :allowed-root opts-or-root})
        builder (ProcessBuilder. ["clojure" "-M:mcp"])
        env (.environment builder)]
    (.directory builder (io/file directory))
    (if-let [user-dir (:user-dir opts)]
      (.put env "JAVA_TOOL_OPTIONS" (str "-Duser.dir=" user-dir))
      (.remove env "JAVA_TOOL_OPTIONS"))
    (if allowed-root
      (.put env "SCI_MCP_ALLOWED_ROOTS" allowed-root)
      (.remove env "SCI_MCP_ALLOWED_ROOTS"))
    (.put env "SCI_MCP_MAX_INDEXES" "4")
    (log-step! "start-process"
               (pr-str {:directory (.getPath (io/file directory))
                        :allowed-root allowed-root
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
                                   :clientInfo {:name "semantic-code-indexing-test"
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
        forbidden-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-forbidden-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        handle (start-mcp-process! tmp-root)]
    (try
      (testing "initialize and tools/list"
        (let [init-response (initialize! handle)]
          (is (= "2024-11-05" (get-in init-response [:result :protocolVersion])))
          (is (some #(str/includes? % ":max_indexes 4")
                    @(:stderr-lines handle))))
        (send-message! handle {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
        (let [tools-response (wait-for-response handle 2 response-timeout-ms)
              tool-names (->> (get-in tools-response [:result :tools])
                              (map :name)
                              set)]
          (is (= #{"create_index" "repo_map" "resolve_context" "impact_analysis" "skeletons"}
                 tool-names))))

      (let [create-response (call-tool! handle 3 "create_index" {:root_path tmp-root})
            create-data (get-in create-response [:result :structuredContent])
            index-id (:index_id create-data)]
        (testing "create_index returns a reusable handle"
          (is (false? (:cache_hit create-data)))
          (is (string? index-id))
          (is (string? (:snapshot_id create-data)))
          (is (pos-int? (:file_count create-data)))
          (is (pos-int? (:unit_count create-data))))

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
            (is (string? (:summary repo-map-data)))))

        (testing "resolve_context returns the expected contract keys"
          (let [resolve-response (call-tool! handle 6 "resolve_context" {:index_id index-id
                                                                         :query sample-query})
                resolve-data (get-in resolve-response [:result :structuredContent])]
            (is (= index-id (:index_id resolve-data)))
            (is (map? (:context_packet resolve-data)))
            (is (map? (:guardrail_assessment resolve-data)))
            (is (vector? (:stage_events resolve-data)))
            (is (some #(= "my.app.order/process-order" (:symbol %))
                      (get-in resolve-data [:context_packet :relevant_units])))))

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
                   (get-in result [:structuredContent :details :code])))))

        (testing "root allowlist is enforced"
          (let [forbidden-response (call-tool! handle 11 "create_index" {:root_path forbidden-root})
                result (:result forbidden-response)]
            (is (true? (:isError result)))
            (is (= "forbidden_root"
                   (get-in result [:structuredContent :details :code])))))

        (testing "invalid selectors are surfaced as tool errors"
          (let [bad-selector-response (call-tool! handle 12 "skeletons" {:index_id index-id})
                result (:result bad-selector-response)]
            (is (true? (:isError result)))
            (is (= "invalid_request"
                   (get-in result [:structuredContent :details :code]))))))
      (finally
        (destroy-process! handle)))))

(deftest runtime-mcp-server-line-transport-compatibility-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-line-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        handle (start-mcp-process! tmp-root)]
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
        (is (= #{"create_index" "repo_map" "resolve_context" "impact_analysis" "skeletons"}
               tool-names)))
      (finally
        (destroy-process! handle)))))

(deftest resolve-allowed-roots-defaults-to-cwd-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-cwd-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        canonical-tmp-root (.getCanonicalPath (io/file tmp-root))
        original-user-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" tmp-root)
      (testing "blank allowlist config falls back to the current working directory"
        (is (= [canonical-tmp-root]
               (#'mcp-server/resolve-allowed-roots ""))))
      (finally
        (System/setProperty "user.dir" original-user-dir)))))

(deftest initialize-preserves-client-protocol-version-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-version-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        handle (start-mcp-process! tmp-root)]
    (try
      (testing "legacy Codex protocol version is preserved"
        (is (= "2024-11-05"
               (get-in (initialize! handle "2024-11-05") [:result :protocolVersion]))))
      (finally
        (destroy-process! handle))))
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-server-version-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        handle (start-mcp-process! tmp-root)]
    (try
      (testing "arbitrary newer protocol version is echoed back"
        (is (= "2026-02-18"
               (get-in (initialize! handle "2026-02-18") [:result :protocolVersion]))))
      (finally
        (destroy-process! handle)))))
