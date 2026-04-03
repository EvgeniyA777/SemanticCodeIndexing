(ns semidx.mcp.http-server
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.mcp.core :as core]
            [semidx.mcp.session-registry :as sessions])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def ^:private default-host "127.0.0.1")
(def ^:private default-port 8791)

(defn- parse-args [args]
  (loop [m {:host default-host
            :port default-port
            :transport_mode "dual"} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--host" (recur (assoc m :host (or v default-host)) rest)
          "--port" (recur (assoc m :port (or (some-> v parse-long) default-port)) rest)
          "--max-indexes" (recur (assoc m :max_indexes (or (some-> v parse-long)
                                                           core/default-max-indexes)) rest)
          "--policy-registry-file" (recur (assoc m :policy_registry_file v) rest)
          "--transport-mode" (recur (assoc m :transport_mode (or v "dual")) rest)
          (recur m rest))))))

(defn- request-method [^HttpExchange exchange]
  (.toUpperCase (.getRequestMethod exchange)))

(defn- request-uri [^HttpExchange exchange]
  (.getRequestURI exchange))

(defn- request-header [^HttpExchange exchange header-name]
  (some-> exchange .getRequestHeaders (.getFirst header-name)))

(defn- write-json!
  ([^HttpExchange exchange status payload]
   (write-json! exchange status payload nil))
  ([^HttpExchange exchange status payload headers]
   (let [bytes (.getBytes (json/write-str payload :escape-slash false) "UTF-8")]
     (doto (.getResponseHeaders exchange)
       (.set "Content-Type" "application/json; charset=utf-8")
       (.set "Cache-Control" "no-store")
       (#(do
           (doseq [[header-name header-value] headers]
             (when (some? header-value)
               (.set % (str header-name) (str header-value))))
           %)))
     (.sendResponseHeaders exchange (long status) (long (count bytes)))
     (with-open [out (.getResponseBody exchange)]
       (.write out bytes)))))

(defn- read-json-body [^HttpExchange exchange]
  (with-open [rdr (io/reader (.getRequestBody exchange))]
    (json/read rdr :key-fn keyword)))

(defn- decode-component [s]
  (URLDecoder/decode (str (or s "")) (.name StandardCharsets/UTF_8)))

(defn- query-params [^HttpExchange exchange]
  (let [raw (.getRawQuery (request-uri exchange))]
    (if (str/blank? raw)
      {}
      (reduce (fn [acc pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (assoc acc (keyword (decode-component k))
                         (decode-component v))))
              {}
              (str/split raw #"&")))))

(defn- transport-enabled? [transport-mode transport]
  (case transport-mode
    "dual" true
    "streamable" (= transport :streamable)
    "sse" (= transport :sse)
    false))

(defn- jsonrpc-error-body
  ([id code message]
   (jsonrpc-error-body id code message nil))
  ([id code message data]
   (cond-> {:jsonrpc "2.0"
            :id id
            :error {:code code
                    :message message}}
     (some? data) (assoc-in [:error :data] data))))

(defn- endpoint-disabled! [^HttpExchange exchange transport]
  (write-json! exchange 404 (jsonrpc-error-body nil -32601 (str (name transport) " transport is disabled"))))

(defn- missing-session! [^HttpExchange exchange id]
  (write-json! exchange 400
               (jsonrpc-error-body id -32010 "missing MCP session"
                                   {:code "missing_session"
                                    :recommended_action "initialize_session"})))

(defn- unknown-session! [^HttpExchange exchange id]
  (write-json! exchange 404
               (jsonrpc-error-body id -32011 "unknown or expired MCP session"
                                   {:code "unknown_session"
                                    :recommended_action "reinitialize_session"})))

(defn- invalid-jsonrpc! [^HttpExchange exchange id message]
  (write-json! exchange 400 (jsonrpc-error-body id -32600 message)))

(defn- create-session! [{:keys [session-registry max-indexes policy-registry]}]
  (sessions/create-session! session-registry {:max-indexes max-indexes
                                              :policy-registry policy-registry}))

(defn- ensure-session! [server-state session-id]
  (when session-id
    (sessions/session-entry (:session-registry server-state) session-id)))

(defn- write-sse-event! [writer event payload]
  (.write writer (str "event: " event "\n"))
  (.write writer (str "data: " (core/format-json payload) "\n\n"))
  (.flush writer))

(defn- handle-health [^HttpExchange exchange]
  (if (= "GET" (request-method exchange))
    (write-json! exchange 200 {:status "ok"
                               :service "semidx-mcp-http"})
    (write-json! exchange 405 {:error "method_not_allowed"
                               :allowed ["GET"]})))

(defn- handle-streamable-http [server-state ^HttpExchange exchange]
  (if-not (transport-enabled? (:transport-mode server-state) :streamable)
    (endpoint-disabled! exchange :streamable)
    (if-not (= "POST" (request-method exchange))
      (write-json! exchange 405 {:error "method_not_allowed"
                                 :allowed ["POST"]})
      (let [message (read-json-body exchange)
            id (:id message)
            method (:method message)
            session-id (request-header exchange "Mcp-Session-Id")]
        (cond
          (not= "2.0" (:jsonrpc message))
          (invalid-jsonrpc! exchange id "jsonrpc must be 2.0")

          (= "initialize" method)
          (let [entry (or (ensure-session! server-state session-id)
                          (create-session! server-state))
                response (core/handle-jsonrpc-message! (:state entry) message)]
            (write-json! exchange 200 response {"Mcp-Session-Id" (:session_id entry)}))

          (str/blank? session-id)
          (missing-session! exchange id)

          :else
          (if-let [entry (ensure-session! server-state session-id)]
            (if-let [response (core/handle-jsonrpc-message! (:state entry) message)]
              (write-json! exchange 200 response {"Mcp-Session-Id" session-id})
              (write-json! exchange 202 {:ok true} {"Mcp-Session-Id" session-id}))
            (unknown-session! exchange id)))))))

(defn- handle-sse-connect [server-state ^HttpExchange exchange]
  (if-not (transport-enabled? (:transport-mode server-state) :sse)
    (endpoint-disabled! exchange :sse)
    (if-not (= "GET" (request-method exchange))
      (write-json! exchange 405 {:error "method_not_allowed"
                                 :allowed ["GET"]})
      (let [requested-session-id (:session_id (query-params exchange))
            entry (or (ensure-session! server-state requested-session-id)
                      (when (str/blank? requested-session-id)
                        (create-session! server-state)))]
        (if-not entry
          (unknown-session! exchange nil)
          (let [close-stream! #(.close exchange)
                connection (sessions/attach-sse! (:session-registry server-state)
                                                 (:session_id entry)
                                                 close-stream!)]
            (doto (.getResponseHeaders exchange)
              (.set "Content-Type" "text/event-stream; charset=utf-8")
              (.set "Cache-Control" "no-cache")
              (.set "Connection" "keep-alive")
              (.set "Mcp-Session-Id" (:session_id entry)))
            (.sendResponseHeaders exchange 200 0)
            (with-open [writer (io/writer (.getResponseBody exchange))]
              (write-sse-event! writer
                                "endpoint"
                                {:session_id (:session_id entry)
                                 :endpoint (str "/mcp/messages?session_id=" (:session_id entry))})
              (try
                (loop []
                  (let [event (sessions/poll-sse-event! (:queue connection)
                                                        sessions/default-sse-poll-timeout-ms)]
                    (cond
                      (= sessions/close-sentinel event) nil
                      (nil? event) (recur)
                      :else
                      (do
                        (write-sse-event! writer (:event event) (:payload event))
                        (recur)))))
                (finally
                  (sessions/detach-sse! (:session-registry server-state)
                                        (:session_id entry)
                                        (:connection_id connection)))))))))))

(defn- handle-sse-message [server-state ^HttpExchange exchange]
  (if-not (transport-enabled? (:transport-mode server-state) :sse)
    (endpoint-disabled! exchange :sse)
    (if-not (= "POST" (request-method exchange))
      (write-json! exchange 405 {:error "method_not_allowed"
                                 :allowed ["POST"]})
      (let [message (read-json-body exchange)
            id (:id message)
            session-id (:session_id (query-params exchange))]
        (cond
          (str/blank? session-id)
          (missing-session! exchange id)

          (not= "2.0" (:jsonrpc message))
          (invalid-jsonrpc! exchange id "jsonrpc must be 2.0")

          :else
          (if-let [entry (ensure-session! server-state session-id)]
            (if-not (sessions/sse-connected? (:session-registry server-state) session-id)
              (write-json! exchange 409
                           (jsonrpc-error-body id -32012 "SSE session is not connected"
                                               {:code "sse_session_not_connected"
                                                :recommended_action "open_sse_stream"}))
              (do
                (when-let [response (core/handle-jsonrpc-message! (:state entry) message)]
                  (sessions/enqueue-sse-event! (:session-registry server-state)
                                               session-id
                                               "message"
                                               response))
                (write-json! exchange 202 {:accepted true
                                           :session_id session-id})))
            (unknown-session! exchange id)))))))

(defn- with-handler [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (f exchange)
        (catch Exception e
          (core/log! "mcp_http_error" (.getMessage e))
          (write-json! exchange 500 (jsonrpc-error-body nil -32603 (.getMessage e))))))))

(defn start-http-server!
  [{:keys [host port max-indexes policy-registry transport-mode session-registry]
    :or {host default-host
         port default-port
         max-indexes core/default-max-indexes
         transport-mode "dual"}}]
  (let [server (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
        executor (Executors/newCachedThreadPool)
        server-state {:max-indexes max-indexes
                      :policy-registry policy-registry
                      :transport-mode transport-mode
                      :session-registry (or session-registry (sessions/new-registry))}]
    (.setExecutor server executor)
    (.createContext server "/health" (with-handler handle-health))
    (.createContext server "/mcp" (with-handler #(handle-streamable-http server-state %)))
    (.createContext server "/mcp/sse" (with-handler #(handle-sse-connect server-state %)))
    (.createContext server "/mcp/messages" (with-handler #(handle-sse-message server-state %)))
    (.start server)
    {:server server
     :executor executor
     :state server-state
     :host host
     :port (.getPort (.getAddress server))}))

(defn stop-http-server! [{:keys [^HttpServer server executor]}]
  (when server
    (.stop server 0))
  (when executor
    (.shutdownNow executor)))

(defn -main [& args]
  (let [{:keys [host port max_indexes policy_registry_file transport_mode]} (parse-args args)
        policy-registry (core/load-policy-registry (or policy_registry_file
                                                       (System/getenv "SEMIDX_MCP_POLICY_REGISTRY_FILE")))
        max-indexes (or max_indexes
                        (some-> (System/getenv "SEMIDX_MCP_MAX_INDEXES") parse-long)
                        core/default-max-indexes)
        transport-mode (or transport_mode "dual")]
    (core/log! "semidx_mcp_http_started" {:host host
                                                          :port port
                                                          :transport_mode transport-mode
                                                          :max_indexes max-indexes})
    (start-http-server! {:host host
                         :port port
                         :max-indexes max-indexes
                         :policy-registry policy-registry
                         :transport-mode transport-mode})
    @(promise)))
