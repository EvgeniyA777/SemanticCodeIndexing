(ns semidx.mcp-http-server-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.mcp.http-server :as mcp-http])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net HttpURLConnection URL]
           [java.nio.charset StandardCharsets]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  order)\n"))

(def ^:private sample-shorthand-query
  {:intent "Find the main orchestration flow and key entrypoints."})

(defn- request!
  ([method url]
   (request! method url nil nil))
  ([method url payload headers]
   (let [^HttpURLConnection conn (.openConnection (URL. url))]
     (.setRequestMethod conn method)
     (.setRequestProperty conn "Accept" "application/json")
     (doseq [[header-name header-value] headers]
       (.setRequestProperty conn (str header-name) (str header-value)))
     (when payload
       (.setDoOutput conn true)
       (.setRequestProperty conn "Content-Type" "application/json")
     (with-open [w (OutputStreamWriter. (.getOutputStream conn) StandardCharsets/UTF_8)]
         (.write w (json/write-str payload :escape-slash false))))
     (let [status (.getResponseCode conn)
           stream (try
                    (.getInputStream conn)
                    (catch Exception _
                      (.getErrorStream conn)))
           body (when stream
                  (with-open [rdr (io/reader stream)]
                    (slurp rdr)))
           payload* (when (seq body)
                      (json/read-str body :key-fn keyword))]
       {:status status
        :headers {"Mcp-Session-Id" (.getHeaderField conn "Mcp-Session-Id")}
        :body payload*}))))

(defn- open-sse! [url]
  (let [^HttpURLConnection conn (.openConnection (URL. url))]
    (.setRequestMethod conn "GET")
    (.setRequestProperty conn "Accept" "text/event-stream")
    (.connect conn)
    {:conn conn
     :reader (BufferedReader. (InputStreamReader. (.getInputStream conn) StandardCharsets/UTF_8))
     :session-id (.getHeaderField conn "Mcp-Session-Id")}))

(defn- read-sse-event! [{:keys [^BufferedReader reader]} timeout-ms]
  (deref
   (future
     (loop [event-name nil
            data nil]
       (let [line (.readLine reader)]
         (cond
           (nil? line) nil
           (= "" line) {:event event-name :payload (some-> data (json/read-str :key-fn keyword))}
           (.startsWith line "event: ") (recur (subs line 7) data)
           (.startsWith line "data: ") (recur event-name (subs line 6))
           :else (recur event-name data)))))
   timeout-ms
   ::timeout))

(defn- close-sse! [{:keys [^HttpURLConnection conn ^BufferedReader reader]}]
  (when reader
    (.close reader))
  (when conn
    (.disconnect conn)))

(deftest mcp-streamable-http-session-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-http-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        server (mcp-http/start-http-server! {:host "127.0.0.1"
                                             :port 0
                                             :transport-mode "dual"})
        base-url (str "http://127.0.0.1:" (:port server))]
    (try
      (let [init-response (request! "POST"
                                    (str base-url "/mcp")
                                    {:jsonrpc "2.0"
                                     :id 1
                                     :method "initialize"
                                     :params {:protocolVersion "2024-11-05"
                                              :capabilities {}
                                              :clientInfo {:name "http-test"
                                                           :version "1.0"}}}
                                    nil)
            session-id (get-in init-response [:headers "Mcp-Session-Id"])]
        (testing "initialize creates an HTTP MCP session"
          (is (= 200 (:status init-response)))
          (is (string? session-id))
          (is (= "semidx-mcp"
                 (get-in init-response [:body :result :serverInfo :name]))))
        (testing "tools/list works over the same session"
          (let [list-response (request! "POST"
                                        (str base-url "/mcp")
                                        {:jsonrpc "2.0"
                                        :id 2
                                         :method "tools/list"}
                                        {"Mcp-Session-Id" session-id})]
            (is (= 200 (:status list-response)))
            (is (seq (get-in list-response [:body :result :tools])))
            (is (= "create_index"
                   (get-in list-response [:body :result :tools 0 :name])))
            (is (some #(= "literal_file_slice" (:name %))
                      (get-in list-response [:body :result :tools])))
            (is (re-find #"ALWAYS call this first"
                         (get-in list-response [:body :result :tools 0 :description])))))
        (testing "tools/call create_index works over streamable HTTP"
          (let [create-response (request! "POST"
                                          (str base-url "/mcp")
                                          {:jsonrpc "2.0"
                                           :id 3
                                           :method "tools/call"
                                           :params {:name "create_index"
                                                    :arguments {:root_path tmp-root}}}
                                          {"Mcp-Session-Id" session-id})]
            (is (= 200 (:status create-response)))
            (is (string? (get-in create-response [:body :result :structuredContent :snapshot_id])))
            (is (= "repo_map"
                   (get-in create-response [:body :result :structuredContent :recommended_next_step])))))
        (testing "resolve_context shorthand works over streamable HTTP"
          (let [create-response (request! "POST"
                                          (str base-url "/mcp")
                                          {:jsonrpc "2.0"
                                           :id 31
                                           :method "tools/call"
                                           :params {:name "create_index"
                                                    :arguments {:root_path tmp-root
                                                                :force_rebuild true}}}
                                          {"Mcp-Session-Id" session-id})
                index-id (get-in create-response [:body :result :structuredContent :index_id])
                resolve-response (request! "POST"
                                           (str base-url "/mcp")
                                           {:jsonrpc "2.0"
                                            :id 32
                                            :method "tools/call"
                                            :params {:name "resolve_context"
                                                     :arguments {:index_id index-id
                                                                 :query sample-shorthand-query}}}
                                           {"Mcp-Session-Id" session-id})]
            (is (= 200 (:status resolve-response)))
            (is (true? (get-in resolve-response [:body :result :structuredContent :query_normalized])))
            (is (= "mcp_shorthand"
                   (get-in resolve-response [:body :result :structuredContent :query_ingress_mode])))
            (is (= "selection_artifact"
                   (get-in resolve-response [:body :result :structuredContent :compact_continuation :continuation_mode])))
            (is (= "expand_context"
                   (get-in resolve-response [:body :result :structuredContent :compact_continuation :next_tool])))
            (is (= ["paths"]
                   (get-in resolve-response [:body :result :structuredContent :normalized_query_summary :target_keys])))))
        (testing "intent string shorthand works over streamable HTTP"
          (let [create-response (request! "POST"
                                          (str base-url "/mcp")
                                          {:jsonrpc "2.0"
                                           :id 51
                                           :method "tools/call"
                                           :params {:name "create_index"
                                                    :arguments {:root_path tmp-root
                                                                :force_rebuild true}}}
                                          {"Mcp-Session-Id" session-id})
                index-id (get-in create-response [:body :result :structuredContent :index_id])
                resolve-response (request! "POST"
                                           (str base-url "/mcp")
                                           {:jsonrpc "2.0"
                                            :id 52
                                            :method "tools/call"
                                            :params {:name "resolve_context"
                                                     :arguments {:index_id index-id
                                                                 :intent "Find the main orchestration flow."}}}
                                           {"Mcp-Session-Id" session-id})]
            (is (= 200 (:status resolve-response)))
            (is (true? (get-in resolve-response [:body :result :structuredContent :query_normalized])))
            (is (= "intent_shorthand"
                   (get-in resolve-response [:body :result :structuredContent :query_ingress_mode])))
            (is (string? (get-in resolve-response [:body :result :structuredContent :selection_id])))))
        (testing "invalid shorthand returns repair-oriented error over streamable HTTP"
          (let [create-response (request! "POST"
                                          (str base-url "/mcp")
                                          {:jsonrpc "2.0"
                                           :id 41
                                           :method "tools/call"
                                           :params {:name "create_index"
                                                    :arguments {:root_path tmp-root
                                                                :force_rebuild true}}}
                                          {"Mcp-Session-Id" session-id})
                index-id (get-in create-response [:body :result :structuredContent :index_id])
                resolve-response (request! "POST"
                                           (str base-url "/mcp")
                                           {:jsonrpc "2.0"
                                            :id 42
                                            :method "tools/call"
                                            :params {:name "resolve_context"
                                                     :arguments {:index_id index-id
                                                                 :query {}}}}
                                           {"Mcp-Session-Id" session-id})]
            (is (= 200 (:status resolve-response)))
            (is (true? (get-in resolve-response [:body :result :isError])))
            (is (= "invalid_query"
                   (get-in resolve-response [:body :result :structuredContent :details :code])))
            (is (= "retry_resolve_context_with_structured_query"
                   (get-in resolve-response [:body :result :structuredContent :details :details :recommended_next_step])))))
        (testing "missing session is rejected for non-initialize calls"
          (let [missing-session (request! "POST"
                                          (str base-url "/mcp")
                                          {:jsonrpc "2.0"
                                           :id 4
                                           :method "tools/list"}
                                          nil)]
            (is (= 400 (:status missing-session)))
            (is (= "missing MCP session"
                   (get-in missing-session [:body :error :message]))))))
      (finally
        (mcp-http/stop-http-server! server)))))

(deftest mcp-sse-roundtrip-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-mcp-sse-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        server (mcp-http/start-http-server! {:host "127.0.0.1"
                                             :port 0
                                             :transport-mode "dual"})
        base-url (str "http://127.0.0.1:" (:port server))
        sse (open-sse! (str base-url "/mcp/sse"))]
    (try
      (let [endpoint-event (read-sse-event! sse 5000)
            session-id (or (:session-id sse)
                           (get-in endpoint-event [:payload :session_id]))]
        (testing "SSE connect yields endpoint metadata"
          (is (= "endpoint" (:event endpoint-event)))
          (is (= session-id (get-in endpoint-event [:payload :session_id]))))
        (let [initialize-response (request! "POST"
                                            (str base-url "/mcp/messages?session_id=" session-id)
                                            {:jsonrpc "2.0"
                                             :id 1
                                             :method "initialize"
                                             :params {:protocolVersion "2024-11-05"
                                                      :capabilities {}
                                                      :clientInfo {:name "sse-test"
                                                                   :version "1.0"}}}
                                            nil)
              initialize-event (read-sse-event! sse 5000)]
          (testing "POST /mcp/messages accepts initialize and emits JSON-RPC over SSE"
            (is (= 202 (:status initialize-response)))
            (is (= "message" (:event initialize-event)))
            (is (= 1 (get-in initialize-event [:payload :id])))
            (is (= "semidx-mcp"
                   (get-in initialize-event [:payload :result :serverInfo :name])))))
        (let [list-response (request! "POST"
                                      (str base-url "/mcp/messages?session_id=" session-id)
                                      {:jsonrpc "2.0"
                                       :id 2
                                       :method "tools/list"}
                                      nil)
              list-event (read-sse-event! sse 5000)]
          (testing "tools/list round-trips over SSE"
            (is (= 202 (:status list-response)))
            (is (= "message" (:event list-event)))
            (is (seq (get-in list-event [:payload :result :tools])))
            (is (some #(= "literal_file_slice" (:name %))
                      (get-in list-event [:payload :result :tools])))
            (is (re-find #"INSTEAD OF manual directory crawling"
                         (get-in list-event [:payload :result :tools 1 :description]))))))
      (finally
        (close-sse! sse)
        (mcp-http/stop-http-server! server)))))
