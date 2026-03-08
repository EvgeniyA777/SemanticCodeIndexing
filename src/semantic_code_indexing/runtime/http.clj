(ns semantic-code-indexing.runtime.http
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.runtime.authz :as authz]
            [semantic-code-indexing.core :as sci])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- parse-bool [s]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str (or s "")))))

(defn- parse-args [args]
  (loop [m {:host "127.0.0.1" :port 8787} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--host" (recur (assoc m :host (or v "127.0.0.1")) rest)
          "--port" (recur (assoc m :port (or (some-> v parse-long) 8787)) rest)
          "--api-key" (recur (assoc m :api_key (or v "")) rest)
          "--authz-policy-file" (recur (assoc m :authz_policy_file (or v "")) rest)
          "--require-tenant" (recur (assoc m :require_tenant true) (cons v rest))
          (recur m rest))))))

(defn- request-method [^HttpExchange exchange]
  (.toUpperCase (.getRequestMethod exchange)))

(defn- read-json-body [^HttpExchange exchange]
  (with-open [rdr (io/reader (.getRequestBody exchange))]
    (json/read rdr :key-fn keyword)))

(defn- write-json! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/write-str payload :escape-slash false) "UTF-8")]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json; charset=utf-8"))
    (.sendResponseHeaders exchange (long status) (long (count bytes)))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- with-handler [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (f exchange)
        (catch Exception e
          (write-json! exchange 500 {:error "internal_error"
                                     :message (.getMessage e)}))))))


(defn- post-request? [^HttpExchange exchange]
  (= "POST" (request-method exchange)))

(defn- request-header [^HttpExchange exchange header-name]
  (some-> exchange .getRequestHeaders (.getFirst header-name)))

(defn- authorize-request [^HttpExchange exchange {:keys [api_key require_tenant]}]
  (let [provided-api-key (request-header exchange "x-api-key")
        tenant-id (request-header exchange "x-tenant-id")]
    (cond
      (and (seq api_key) (not= api_key provided-api-key))
      {:ok? false
       :status 401
       :body {:error "unauthorized"
              :message "missing or invalid x-api-key"}}

      (and require_tenant (str/blank? tenant-id))
      {:ok? false
       :status 400
       :body {:error "invalid_request"
              :message "x-tenant-id is required"}}

      :else
      {:ok? true :tenant_id tenant-id})))

(defn- enforce-authorized! [^HttpExchange exchange auth-config]
  (let [{:keys [ok? status body] :as auth} (authorize-request exchange auth-config)]
    (if ok?
      auth
      (do (write-json! exchange status body)
          nil))))

(defn- authz-denial->http [{:keys [code message]}]
  (case code
    :invalid_request {:status 400
                      :body {:error "invalid_request"
                             :message (or message "invalid request")}}
    :internal_error {:status 500
                     :body {:error "internal_error"
                            :message (or message "authz policy evaluation failed")}}
    {:status 403
     :body {:error "forbidden"
            :message (or message "request denied by authz policy")}}))

(defn- enforce-authz! [^HttpExchange exchange auth-config request]
  (let [{:keys [allowed?] :as decision} (authz/evaluate (:authz_check auth-config) request)]
    (if allowed?
      true
      (let [{:keys [status body]} (authz-denial->http decision)]
        (write-json! exchange status body)
        false))))

(defn- handle-health [^HttpExchange exchange]
  (if (= "GET" (request-method exchange))
    (write-json! exchange 200 {:status "ok" :service "semantic-code-indexing-runtime-http"})
    (write-json! exchange 405 {:error "method_not_allowed"
                               :allowed ["GET"]})))

(defn- handle-create-index [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :allowed ["POST"]})
    (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
      (let [payload (read-json-body exchange)
            root-path (or (:root_path payload) ".")
            paths (:paths payload)]
        (when (enforce-authz! exchange auth-config {:operation :create_index
                                                    :tenant_id tenant_id
                                                    :root_path root-path
                                                    :paths paths})
          (let [index (sci/create-index {:root_path root-path
                                         :paths paths
                                         :parser_opts (:parser_opts payload)})]
            (write-json! exchange 200 {:snapshot_id (:snapshot_id index)
                                       :indexed_at (:indexed_at index)
                                       :file_count (count (:files index))
                                       :unit_count (count (:units index))
                                       :repo_map (sci/repo-map index)})))))))

(defn- handle-resolve-context [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :allowed ["POST"]})
    (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
      (let [payload (read-json-body exchange)
            root-path (or (:root_path payload) ".")
            paths (:paths payload)
            query (:query payload)]
        (if-not (map? query)
          (write-json! exchange 400 {:error "invalid_request"
                                     :message "query must be an object"})
          (when (enforce-authz! exchange auth-config {:operation :resolve_context
                                                      :tenant_id tenant_id
                                                      :root_path root-path
                                                      :paths paths})
            (let [index (sci/create-index {:root_path root-path
                                           :paths paths
                                           :parser_opts (:parser_opts payload)})
                  result (sci/resolve-context index query)]
              (write-json! exchange 200 result))))))))

(defn start-server [{:keys [host port api_key require_tenant authz_check]}]
  (let [server (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)]
    (.createContext server "/health" (with-handler handle-health))
    (.createContext server "/v1/index/create" (with-handler (partial handle-create-index {:api_key api_key
                                                                                           :require_tenant require_tenant
                                                                                           :authz_check authz_check})))
    (.createContext server "/v1/retrieval/resolve-context" (with-handler (partial handle-resolve-context {:api_key api_key
                                                                                                            :require_tenant require_tenant
                                                                                                            :authz_check authz_check})))
    (.setExecutor server nil)
    (.start server)
    server))

(defn -main [& args]
  (let [{:keys [host port api_key require_tenant authz_policy_file]} (parse-args args)
        api-key* (or (not-empty api_key) (System/getenv "SCI_RUNTIME_API_KEY"))
        require-tenant* (or require_tenant (parse-bool (System/getenv "SCI_RUNTIME_REQUIRE_TENANT")))
        authz-policy-file* (or (not-empty authz_policy_file) (System/getenv "SCI_RUNTIME_AUTHZ_POLICY_FILE"))
        authz-check* (when (seq authz-policy-file*)
                       (authz/load-policy-authorizer authz-policy-file*))
        _server (start-server {:host host
                               :port port
                               :api_key api-key*
                               :require_tenant require-tenant*
                               :authz_check authz-check*})]
    (println (str "runtime_http_server_started host=" host " port=" port))
    (flush)
    @(promise)))
