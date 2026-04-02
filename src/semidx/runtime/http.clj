(ns semidx.runtime.http
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.authz :as authz]
            [semidx.runtime.errors :as errors]
            [semidx.runtime.project-context :as project-context]
            [semidx.runtime.retrieval-policy :as rp]
            [semidx.core :as sci])
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
          "--policy-registry-file" (recur (assoc m :policy_registry_file (or v "")) rest)
          "--language-policy-file" (recur (assoc m :language_policy_file (or v "")) rest)
          "--require-tenant" (recur (assoc m :require_tenant true) (cons v rest))
          (recur m rest))))))

(defn- load-language-policy [path]
  (when (seq path)
    (-> path slurp edn/read-string)))

(defn- request-method [^HttpExchange exchange]
  (.toUpperCase (.getRequestMethod exchange)))

(declare request-header)

(defn- read-json-body [^HttpExchange exchange]
  (with-open [rdr (io/reader (.getRequestBody exchange))]
    (json/read rdr :key-fn keyword)))

(defn- write-json!
  ([^HttpExchange exchange status payload]
   (write-json! exchange status payload nil))
  ([^HttpExchange exchange status payload response-headers]
  (let [bytes (.getBytes (json/write-str payload :escape-slash false) "UTF-8")]
    (doto (.getResponseHeaders exchange)
      (#(do
          (doseq [[header-name header-value] response-headers]
            (when (seq (str header-value))
              (.set % (str header-name) (str header-value))))
          %))
      (.set "Content-Type" "application/json; charset=utf-8"))
    (.sendResponseHeaders exchange (long status) (long (count bytes)))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes)))))

(def ^:private api-version-header {"x-sci-api-version" "1.0"})

(def ^:private correlation-attribute "sci-correlation")

(def ^:private request-correlation-headers
  {:trace_id "x-trace-id"
   :request_id "x-request-id"
   :session_id "x-session-id"
   :task_id "x-task-id"
   :actor_id "x-actor-id"})

(def ^:private response-correlation-headers
  {:trace_id "x-sci-trace-id"
   :request_id "x-sci-request-id"
   :session_id "x-sci-session-id"
   :task_id "x-sci-task-id"
   :actor_id "x-sci-actor-id"
   :tenant_id "x-sci-tenant-id"})

(defn- request-correlation [^HttpExchange exchange]
  (reduce-kv (fn [acc field header-name]
               (if-let [value (some-> (request-header exchange header-name) not-empty)]
                 (assoc acc field value)
                 acc))
             {}
             request-correlation-headers))

(defn- merge-query-correlation [correlation query]
  (merge correlation
         (let [trace (get query :trace {})]
           (cond-> (select-keys trace [:trace_id :request_id :session_id :task_id])
             (or (:actor_id trace) (:agent_id trace))
             (assoc :actor_id (or (:actor_id trace) (:agent_id trace)))))))

(defn- remember-correlation! [^HttpExchange exchange correlation]
  (.setAttribute exchange correlation-attribute correlation)
  correlation)

(defn- response-correlation [^HttpExchange exchange]
  (or (.getAttribute exchange correlation-attribute) {}))

(defn- response-correlation-header-map [^HttpExchange exchange]
  (reduce-kv (fn [acc field header-name]
               (if-let [value (get (response-correlation exchange) field)]
                 (assoc acc header-name value)
                 acc))
             {}
             response-correlation-headers))

(defn- error-response-headers [^HttpExchange exchange error]
  (merge (response-correlation-header-map exchange)
         (errors/http-error-headers error)))

(defn- with-handler [f]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (f exchange)
        (catch Exception e
          (let [{:keys [status body]} (errors/http-error-body e)]
            (write-json! exchange status body (error-response-headers exchange e))))))))

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
       :error (errors/http-error-body {:type :unauthorized
                                       :message "missing or invalid x-api-key"})}

      (and require_tenant (str/blank? tenant-id))
      {:ok? false
       :error (errors/http-error-body {:type :invalid_request
                                       :message "x-tenant-id is required"})}

      :else
      {:ok? true :tenant_id tenant-id})))

(defn- enforce-authorized! [^HttpExchange exchange auth-config]
  (let [{:keys [ok? error] :as auth} (authorize-request exchange auth-config)]
    (if ok?
      auth
      (do (write-json! exchange (:status error) (:body error) (response-correlation-header-map exchange))
          nil))))

(defn- authz-denial->http [{:keys [code message]}]
  (errors/http-error-body {:type code
                           :message (or message "request denied by authz policy")}))

(defn- project-context-summary [entry]
  (select-keys entry [:root_path
                      :tenant_id
                      :snapshot_id
                      :detected_languages
                      :active_languages
                      :supported_languages
                      :language_fingerprint
                      :activation_state
                      :activation_started_at
                      :retry_after_seconds
                      :selection_hint
                      :manual_language_selection]))

(defn- build-project-index [auth-config canonical-root-path paths parser-opts tenant-id correlation language-policy suppress-usage?]
  (let [effective-language-policy (project-context/merge-language-policy (:language_policy auth-config)
                                                                         language-policy)]
    (sci/create-index {:root_path canonical-root-path
                       :paths paths
                       :parser_opts parser-opts
                       :usage_metrics (:usage_metrics auth-config)
                       :usage_context (merge {:surface "http"
                                              :tenant_id tenant-id}
                                             correlation)
                       :selection_cache (:selection_cache auth-config)
                       :suppress_usage_metrics suppress-usage?
                       :policy_registry (:policy_registry auth-config)
                       :language_policy effective-language-policy})))

(defn- refresh-project-entry! [auth-config root-path paths parser-opts tenant-id correlation language-policy]
  (let [scope (project-context/project-scope root-path tenant-id)]
    (project-context/refresh-project-index! (:project_registry auth-config)
                                          scope
                                          #(build-project-index auth-config
                                                                (:root_path scope)
                                                                paths
                                                                parser-opts
                                                                tenant-id
                                                                correlation
                                                                language-policy
                                                                false))))

(defn- ensure-project-entry! [auth-config root-path paths parser-opts tenant-id correlation language-policy request]
  (let [scope (project-context/project-scope root-path tenant-id)]
    (project-context/ensure-project-index! (:project_registry auth-config)
                                         scope
                                         request
                                         #(build-project-index auth-config
                                                               (:root_path scope)
                                                               paths
                                                               parser-opts
                                                               tenant-id
                                                               correlation
                                                               language-policy
                                                               true))))

(defn- enforce-authz! [^HttpExchange exchange auth-config request]
  (let [{:keys [allowed?] :as decision} (authz/evaluate (:authz_check auth-config) request)]
    (if allowed?
      true
      (let [{:keys [status body]} (authz-denial->http decision)]
        (write-json! exchange status body (response-correlation-header-map exchange))
        false))))

(defn- handle-health [^HttpExchange exchange]
  (if (= "GET" (request-method exchange))
    (write-json! exchange 200 {:status "ok" :service "semidx-runtime-http"})
    (write-json! exchange 405 {:error "method_not_allowed"
                               :error_code "method_not_allowed"
                               :error_category "client"
                               :allowed ["GET"]})))

(defn- handle-create-index [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :error_code "method_not_allowed"
                               :error_category "client"
                               :allowed ["POST"]})
    (do
      (remember-correlation! exchange (request-correlation exchange))
      (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
        (let [payload (read-json-body exchange)
              root-path (or (:root_path payload) ".")
              paths (:paths payload)
              language-policy (:language_policy payload)
              correlation (remember-correlation! exchange
                                                 (assoc (request-correlation exchange)
                                                        :tenant_id tenant_id))]
          (when (enforce-authz! exchange auth-config {:operation :create_index
                                                      :tenant_id tenant_id
                                                      :root_path root-path
                                                      :paths paths})
            (let [entry (refresh-project-entry! auth-config
                                                root-path
                                                paths
                                                (:parser_opts payload)
                                                tenant_id
                                                correlation
                                                language-policy)
                  index (:index entry)]
              (write-json! exchange 200 {:snapshot_id (:snapshot_id index)
                                         :indexed_at (:indexed_at index)
                                         :index_lifecycle (:index_lifecycle index)
                                         :file_count (count (:files index))
                                         :unit_count (count (:units index))
                                         :repo_map (sci/repo-map index)
                                         :project_context (project-context-summary entry)}
                           (response-correlation-header-map exchange)))))))))

(defn- handle-resolve-context [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :error_code "method_not_allowed"
                               :error_category "client"
                               :allowed ["POST"]})
    (do
      (remember-correlation! exchange (request-correlation exchange))
      (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
        (let [payload (read-json-body exchange)
              root-path (or (:root_path payload) ".")
              paths (:paths payload)
              query (:query payload)
              retrieval-policy (:retrieval_policy payload)
              language-policy (:language_policy payload)
              correlation (remember-correlation! exchange
                                                 (assoc (merge-query-correlation (request-correlation exchange) query)
                                                        :tenant_id tenant_id))]
          (if-not (map? query)
            (let [{:keys [status body]} (errors/http-error-body {:type :invalid_request
                                                                 :message "query must be an object"})]
              (write-json! exchange status body (response-correlation-header-map exchange)))
            (if (and (some? retrieval-policy) (not (map? retrieval-policy)))
              (let [{:keys [status body]} (errors/http-error-body {:type :invalid_request
                                                                   :message "retrieval_policy must be an object"})]
                (write-json! exchange status body (response-correlation-header-map exchange)))
              (when (enforce-authz! exchange auth-config {:operation :resolve_context
                                                          :tenant_id tenant_id
                                                          :root_path root-path
                                                          :paths paths})
                (let [entry (ensure-project-entry! auth-config
                                                   root-path
                                                   paths
                                                   (:parser_opts payload)
                                                   tenant_id
                                                   correlation
                                                   language-policy
                                                   {:paths paths
                                                    :query query})
                      index (:index entry)
                      result (sci/resolve-context index
                                                  query
                                                  {:retrieval_policy retrieval-policy
                                                   :policy_registry (:policy_registry auth-config)})]
                  (write-json! exchange 200
                               (assoc result :project_context (project-context-summary entry))
                               (merge api-version-header
                                      (response-correlation-header-map exchange))))))))))))

(defn- handle-expand-context [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :error_code "method_not_allowed"
                               :error_category "client"
                               :allowed ["POST"]})
    (do
      (remember-correlation! exchange (request-correlation exchange))
      (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
        (let [payload (read-json-body exchange)
              root-path (or (:root_path payload) ".")
              paths (:paths payload)
              selector (select-keys payload [:selection_id :snapshot_id :unit_ids :include_impact_hints])
              language-policy (:language_policy payload)
              correlation (remember-correlation! exchange
                                                 (assoc (request-correlation exchange) :tenant_id tenant_id))]
          (when (enforce-authz! exchange auth-config
                                {:operation :expand_context
                                 :tenant_id tenant_id
                                 :root_path root-path
                                 :paths paths})
            (let [entry (ensure-project-entry! auth-config
                                               root-path
                                               paths
                                               (:parser_opts payload)
                                               tenant_id
                                               correlation
                                               language-policy
                                               {:paths paths})
                  index (:index entry)
                  result (sci/expand-context index selector)]
              (write-json! exchange 200
                           (assoc result :project_context (project-context-summary entry))
                           (merge api-version-header
                                  (response-correlation-header-map exchange))))))))))

(defn- handle-fetch-context-detail [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :error_code "method_not_allowed"
                               :error_category "client"
                               :allowed ["POST"]})
    (do
      (remember-correlation! exchange (request-correlation exchange))
      (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
        (let [payload (read-json-body exchange)
              root-path (or (:root_path payload) ".")
              paths (:paths payload)
              selector (select-keys payload [:selection_id :snapshot_id :unit_ids :detail_level])
              language-policy (:language_policy payload)
              correlation (remember-correlation! exchange
                                                 (assoc (request-correlation exchange) :tenant_id tenant_id))]
          (when (enforce-authz! exchange auth-config
                                {:operation :fetch_context_detail
                                 :tenant_id tenant_id
                                 :root_path root-path
                                 :paths paths})
            (let [entry (ensure-project-entry! auth-config
                                               root-path
                                               paths
                                               (:parser_opts payload)
                                               tenant_id
                                               correlation
                                               language-policy
                                               {:paths paths})
                  index (:index entry)
                  result (sci/fetch-context-detail index selector)]
              (write-json! exchange 200
                           (assoc result :project_context (project-context-summary entry))
                           (merge api-version-header
                                  (response-correlation-header-map exchange))))))))))

(defn- handle-literal-file-slice [auth-config ^HttpExchange exchange]
  (if-not (post-request? exchange)
    (write-json! exchange 405 {:error "method_not_allowed"
                               :error_code "method_not_allowed"
                               :error_category "client"
                               :allowed ["POST"]})
    (do
      (remember-correlation! exchange (request-correlation exchange))
      (when-let [{:keys [tenant_id]} (enforce-authorized! exchange auth-config)]
        (let [payload (read-json-body exchange)
              root-path (or (:root_path payload) ".")
              paths (:paths payload)
              selector (select-keys payload [:selection_id :snapshot_id :path :start_line :end_line])
              language-policy (:language_policy payload)
              correlation (remember-correlation! exchange
                                                 (assoc (request-correlation exchange) :tenant_id tenant_id))]
          (when (enforce-authz! exchange auth-config
                                {:operation :literal_file_slice
                                 :tenant_id tenant_id
                                 :root_path root-path
                                 :paths paths})
            (let [entry (ensure-project-entry! auth-config
                                               root-path
                                               paths
                                               (:parser_opts payload)
                                               tenant_id
                                               correlation
                                               language-policy
                                               {:paths paths})
                  index (:index entry)
                  result (sci/literal-file-slice index selector)]
              (write-json! exchange 200
                           (assoc result :project_context (project-context-summary entry))
                           (merge api-version-header
                                  (response-correlation-header-map exchange))))))))))

(defn start-server [{:keys [host port api_key require_tenant authz_check policy_registry usage_metrics selection_cache
                            project_registry language_policy]}]
  (let [server (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
        selection-cache (or selection_cache (atom {:max_entries 128}))
        project-registry (or project_registry (project-context/project-registry))
        auth-config {:api_key api_key
                     :require_tenant require_tenant
                     :authz_check authz_check
                     :policy_registry policy_registry
                     :usage_metrics usage_metrics
                     :selection_cache selection-cache
                     :project_registry project-registry
                     :language_policy language_policy}]
    (.createContext server "/health" (with-handler handle-health))
    (.createContext server "/v1/index/create" (with-handler (partial handle-create-index auth-config)))
    (.createContext server "/v1/retrieval/resolve-context" (with-handler (partial handle-resolve-context auth-config)))
    (.createContext server "/v1/retrieval/expand-context" (with-handler (partial handle-expand-context auth-config)))
    (.createContext server "/v1/retrieval/fetch-context-detail" (with-handler (partial handle-fetch-context-detail auth-config)))
    (.createContext server "/v1/retrieval/literal-file-slice" (with-handler (partial handle-literal-file-slice auth-config)))
    (.setExecutor server nil)
    (.start server)
    server))

(defn -main [& args]
  (let [{:keys [host port api_key require_tenant authz_policy_file policy_registry_file language_policy_file]} (parse-args args)
        api-key* (or (not-empty api_key) (System/getenv "SEMIDX_RUNTIME_API_KEY"))
        require-tenant* (or require_tenant (parse-bool (System/getenv "SEMIDX_RUNTIME_REQUIRE_TENANT")))
        authz-policy-file* (or (not-empty authz_policy_file) (System/getenv "SEMIDX_RUNTIME_AUTHZ_POLICY_FILE"))
        policy-registry-file* (or (not-empty policy_registry_file) (System/getenv "SEMIDX_RUNTIME_POLICY_REGISTRY_FILE"))
        language-policy-file* (or (not-empty language_policy_file) (System/getenv "SEMIDX_RUNTIME_LANGUAGE_POLICY_FILE"))
        usage-metrics* (when-let [jdbc-url (System/getenv "SEMIDX_USAGE_METRICS_JDBC_URL")]
                         (sci/postgres-usage-metrics {:jdbc-url jdbc-url
                                                      :user (System/getenv "SEMIDX_USAGE_METRICS_DB_USER")
                                                      :password (System/getenv "SEMIDX_USAGE_METRICS_DB_PASSWORD")}))
        authz-check* (when (seq authz-policy-file*)
                       (authz/load-policy-authorizer authz-policy-file*))
        policy-registry* (when (seq policy-registry-file*)
                           (rp/load-registry policy-registry-file*))
        language-policy* (load-language-policy language-policy-file*)
        _server (start-server {:host host
                               :port port
                               :api_key api-key*
                               :require_tenant require-tenant*
                               :authz_check authz-check*
                               :policy_registry policy-registry*
                               :language_policy language-policy*
                               :usage_metrics usage-metrics*})]
    (println (str "runtime_http_server_started host=" host " port=" port))
    (flush)
    @(promise)))
