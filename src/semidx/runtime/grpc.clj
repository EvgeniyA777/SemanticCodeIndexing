(ns semidx.runtime.grpc
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [semidx.runtime.authz :as authz]
            [semidx.runtime.errors :as errors]
            [semidx.runtime.grpc-proto :as grpc-proto]
            [semidx.runtime.project-context :as project-context]
            [semidx.runtime.retrieval-policy :as rp]
            [semidx.core :as sci])
  (:import [io.grpc MethodDescriptor MethodDescriptor$Marshaller MethodDescriptor$MethodType
                     Context Contexts Metadata Metadata$Key
                     ServerCall ServerCall$Listener ServerCallHandler ServerInterceptor ServerInterceptors
                     ServerServiceDefinition Status]
           [io.grpc.stub ServerCalls ServerCalls$UnaryMethod StreamObserver]
           [io.grpc.netty.shaded.io.grpc.netty NettyServerBuilder]))

(def ^:private service-name "semidx.RuntimeService")

(defn- parse-bool [s]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str (or s "")))))

(defn- parse-args [args]
  (loop [m {:host "127.0.0.1" :port 8789} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--host" (recur (assoc m :host (or v "127.0.0.1")) rest)
          "--port" (recur (assoc m :port (or (some-> v parse-long) 8789)) rest)
          "--api-key" (recur (assoc m :api_key (or v "")) rest)
          "--authz-policy-file" (recur (assoc m :authz_policy_file (or v "")) rest)
          "--policy-registry-file" (recur (assoc m :policy_registry_file (or v "")) rest)
          "--language-policy-file" (recur (assoc m :language_policy_file (or v "")) rest)
          "--require-tenant" (recur (assoc m :require_tenant true) (cons v rest))
          (recur m rest))))))

(defn- load-language-policy [path]
  (when (seq path)
    (-> path slurp edn/read-string)))

(defn- unary-method [method-name request-type response-type]
  (-> (MethodDescriptor/newBuilder)
      (.setType MethodDescriptor$MethodType/UNARY)
      (.setFullMethodName (MethodDescriptor/generateFullMethodName service-name method-name))
      (.setRequestMarshaller ^MethodDescriptor$Marshaller (grpc-proto/marshaller request-type))
      (.setResponseMarshaller ^MethodDescriptor$Marshaller (grpc-proto/marshaller response-type))
      (.build)))

(def health-method (unary-method "Health" :health-request :health-response))
(def create-index-method (unary-method "CreateIndex" :create-index-request :create-index-response))
(def resolve-context-method (unary-method "ResolveContext" :resolve-context-request :resolve-context-response))
(def expand-context-method (unary-method "ExpandContext" :expand-context-request :expand-context-response))
(def fetch-context-detail-method (unary-method "FetchContextDetail" :fetch-context-detail-request :fetch-context-detail-response))
(def literal-file-slice-method (unary-method "LiteralFileSlice" :literal-file-slice-request :literal-file-slice-response))

(def ^:private api-key-header
  (Metadata$Key/of "x-api-key" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private tenant-id-header
  (Metadata$Key/of "x-tenant-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private trace-id-header
  (Metadata$Key/of "x-trace-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private request-id-header
  (Metadata$Key/of "x-request-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private session-id-header
  (Metadata$Key/of "x-session-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private task-id-header
  (Metadata$Key/of "x-task-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private actor-id-header
  (Metadata$Key/of "x-actor-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private api-key-context (Context/key "sci-api-key"))
(def ^:private tenant-id-context (Context/key "sci-tenant-id"))
(def ^:private trace-id-context (Context/key "sci-trace-id"))
(def ^:private request-id-context (Context/key "sci-request-id"))
(def ^:private session-id-context (Context/key "sci-session-id"))
(def ^:private task-id-context (Context/key "sci-task-id"))
(def ^:private actor-id-context (Context/key "sci-actor-id"))

(defn- metadata-context-interceptor []
  (reify ServerInterceptor
    (^ServerCall$Listener interceptCall [_ ^ServerCall call ^Metadata headers ^ServerCallHandler next]
      (let [api-key (.get headers api-key-header)
            tenant-id (.get headers tenant-id-header)
            trace-id (.get headers trace-id-header)
            request-id (.get headers request-id-header)
            session-id (.get headers session-id-header)
            task-id (.get headers task-id-header)
            actor-id (.get headers actor-id-header)
            ctx (-> (Context/current)
                    (.withValue api-key-context api-key)
                    (.withValue tenant-id-context tenant-id)
                    (.withValue trace-id-context trace-id)
                    (.withValue request-id-context request-id)
                    (.withValue session-id-context session-id)
                    (.withValue task-id-context task-id)
                    (.withValue actor-id-context actor-id))]
        (Contexts/interceptCall ctx call headers next)))))

(defn- normalize-number [x]
  (if (number? x)
    (let [d (double x)]
      (if (== d (Math/floor d))
        (long d)
        x))
    x))

(defn- normalize-numeric-shapes [v]
  (cond
    (map? v) (->> v
                  (map (fn [[k val]] [k (normalize-numeric-shapes val)]))
                  (into {}))
    (vector? v) (mapv normalize-numeric-shapes v)
    (sequential? v) (vec (map normalize-numeric-shapes v))
    :else (normalize-number v)))

(defn- reply! [^StreamObserver response-observer payload]
  (.onNext response-observer payload)
  (.onCompleted response-observer))

(def ^:private response-trace-id-header
  (Metadata$Key/of "x-sci-trace-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private response-request-id-header
  (Metadata$Key/of "x-sci-request-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private response-session-id-header
  (Metadata$Key/of "x-sci-session-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private response-task-id-header
  (Metadata$Key/of "x-sci-task-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private response-actor-id-header
  (Metadata$Key/of "x-sci-actor-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private response-tenant-id-header
  (Metadata$Key/of "x-sci-tenant-id" Metadata/ASCII_STRING_MARSHALLER))

(defn- current-request-correlation []
  (cond-> {:tenant_id (.get tenant-id-context (Context/current))
           :trace_id (.get trace-id-context (Context/current))
           :request_id (.get request-id-context (Context/current))
           :session_id (.get session-id-context (Context/current))
           :task_id (.get task-id-context (Context/current))
           :actor_id (.get actor-id-context (Context/current))}
    true (update-vals #(when (seq (str %)) %))
    true (#(into {} (remove (comp nil? val) %)))))

(defn- attach-correlation-trailers [^Metadata trailers]
  (let [{:keys [trace_id request_id session_id task_id actor_id tenant_id]} (current-request-correlation)]
    (when (seq trace_id)
      (.put trailers response-trace-id-header trace_id))
    (when (seq request_id)
      (.put trailers response-request-id-header request_id))
    (when (seq session_id)
      (.put trailers response-session-id-header session_id))
    (when (seq task_id)
      (.put trailers response-task-id-header task_id))
    (when (seq actor_id)
      (.put trailers response-actor-id-header actor_id))
    (when (seq tenant_id)
      (.put trailers response-tenant-id-header tenant_id))
    trailers))

(defn- fail! [^StreamObserver response-observer e]
  (let [status (errors/grpc-status e)
        description (errors/grpc-description e)
        trailers (attach-correlation-trailers (errors/grpc-trailers e))]
    (.onError response-observer (.asRuntimeException (.withDescription status description)
                                                     trailers))))

(defn- current-api-key []
  (.get api-key-context (Context/current)))

(defn- current-tenant-id []
  (.get tenant-id-context (Context/current)))

(defn- auth-violation [{:keys [api_key require_tenant]}]
  (let [provided-api-key (current-api-key)
        tenant-id (current-tenant-id)]
    (cond
      (and (seq api_key) (not= api_key provided-api-key))
      {:status Status/UNAUTHENTICATED
       :message "missing or invalid x-api-key"}

      (and require_tenant (str/blank? tenant-id))
      {:status Status/INVALID_ARGUMENT
       :message "x-tenant-id is required"}

      :else nil)))

(defn- authz-code->status [code]
  (errors/grpc-status {:type code}))

(defn- authz-violation [auth-config operation payload]
  (let [{:keys [allowed? code message]}
        (authz/evaluate (:authz_check auth-config)
                        {:operation operation
                         :tenant_id (current-tenant-id)
                         :root_path (or (:root_path payload) ".")
                         :paths (:paths payload)})]
    (when-not allowed?
      {:status (authz-code->status code)
       :type code
       :message (or message "request denied by authz policy")})))

(defn- unary-handler [request->payload response->message f auth-config operation]
  (ServerCalls/asyncUnaryCall
   (reify ServerCalls$UnaryMethod
     (invoke [_ request response-observer]
       (try
         (if-let [{:keys [status message]} (auth-violation auth-config)]
           (fail! response-observer {:type (if (= status Status/UNAUTHENTICATED) :unauthorized :invalid_request)
                                     :message message})
           (let [payload (-> request request->payload normalize-numeric-shapes)]
             (if-let [{:keys [type message]} (authz-violation auth-config operation payload)]
               (fail! response-observer {:type (or type :forbidden)
                                         :message message})
               (reply! response-observer (response->message (f payload))))))
         (catch clojure.lang.ExceptionInfo e
           (fail! response-observer e))
         (catch Exception e
           (fail! response-observer e)))))))

(defn- handle-health [_]
  {:status "ok"
   :service "semidx-runtime-grpc"})

(declare build-project-index project-context-summary)

(defn- handle-create-index [policy-registry usage-metrics selection-cache project-registry server-language-policy payload]
  (let [scope (project-context/project-scope (or (:root_path payload) ".")
                                             (current-tenant-id))
        entry (project-context/refresh-project-index! project-registry
                                                      scope
                                                      #(build-project-index policy-registry
                                                                            usage-metrics
                                                                            selection-cache
                                                                            server-language-policy
                                                                            payload
                                                                            (current-request-correlation)
                                                                            (:root_path scope)
                                                                            false))
        index (:index entry)]
    {:snapshot_id (:snapshot_id index)
     :indexed_at (:indexed_at index)
     :index_lifecycle (:index_lifecycle index)
     :file_count (count (:files index))
     :unit_count (count (:units index))
     :repo_map (sci/repo-map index)
     :project_context (project-context-summary entry)}))

(defn- query-trace-correlation [query]
  (let [trace (get query :trace {})]
    (cond-> (select-keys trace [:trace_id :request_id :session_id :task_id])
      (or (:actor_id trace) (:agent_id trace))
      (assoc :actor_id (or (:actor_id trace) (:agent_id trace))))))

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

(defn- build-project-index [policy-registry usage-metrics selection-cache server-language-policy payload correlation canonical-root-path suppress-usage?]
  (let [effective-language-policy (project-context/merge-language-policy server-language-policy
                                                                         (:language_policy payload))]
    (sci/create-index {:root_path canonical-root-path
                       :paths (:paths payload)
                       :parser_opts (:parser_opts payload)
                       :usage_metrics usage-metrics
                       :usage_context (merge {:surface "grpc"} correlation)
                       :selection_cache selection-cache
                       :suppress_usage_metrics suppress-usage?
                       :policy_registry policy-registry
                       :language_policy effective-language-policy})))

(defn- handle-resolve-context [policy-registry usage-metrics selection-cache project-registry server-language-policy payload]
  (let [query (:query payload)
        retrieval-policy (:retrieval_policy payload)
        correlation (merge (current-request-correlation)
                           (query-trace-correlation query))]
    (when-not (map? query)
      (throw (ex-info "query must be an object"
                      {:type :invalid_request
                       :message "query must be an object"})))
    (when (and (some? retrieval-policy) (not (map? retrieval-policy)))
      (throw (ex-info "retrieval_policy must be an object"
                      {:type :invalid_request
                       :message "retrieval_policy must be an object"})))
    (let [root-path (or (:root_path payload) ".")
          scope (project-context/project-scope root-path (current-tenant-id))
          entry (project-context/ensure-project-index! project-registry
                                                       scope
                                                       {:paths (:paths payload)
                                                        :query query}
                                                       #(build-project-index policy-registry
                                                                             usage-metrics
                                                                             selection-cache
                                                                             server-language-policy
                                                                             payload
                                                                             correlation
                                                                             (:root_path scope)
                                                                             true))
          index (:index entry)]
      (assoc (sci/resolve-context index query {:retrieval_policy retrieval-policy
                                               :policy_registry policy-registry})
             :project_context (project-context-summary entry)))))

(defn- handle-expand-context [policy-registry usage-metrics selection-cache project-registry server-language-policy payload]
  (let [scope (project-context/project-scope (or (:root_path payload) ".")
                                             (current-tenant-id))
        entry (project-context/ensure-project-index! project-registry
                                                     scope
                                                     {:paths (:paths payload)}
                                                     #(build-project-index policy-registry
                                                                           usage-metrics
                                                                           selection-cache
                                                                           server-language-policy
                                                                           payload
                                                                           (merge {:surface "grpc"} (current-request-correlation))
                                                                           (:root_path scope)
                                                                           true))
        index (:index entry)]
    (assoc (sci/expand-context index (select-keys payload [:selection_id :snapshot_id :unit_ids :include_impact_hints]))
           :project_context (project-context-summary entry))))

(defn- handle-fetch-context-detail [policy-registry usage-metrics selection-cache project-registry server-language-policy payload]
  (let [scope (project-context/project-scope (or (:root_path payload) ".")
                                             (current-tenant-id))
        entry (project-context/ensure-project-index! project-registry
                                                     scope
                                                     {:paths (:paths payload)}
                                                     #(build-project-index policy-registry
                                                                           usage-metrics
                                                                           selection-cache
                                                                           server-language-policy
                                                                           payload
                                                                           (merge {:surface "grpc"} (current-request-correlation))
                                                                           (:root_path scope)
                                                                           true))
        index (:index entry)]
    (assoc (sci/fetch-context-detail index (select-keys payload [:selection_id :snapshot_id :unit_ids :detail_level]))
           :project_context (project-context-summary entry))))

(defn- handle-literal-file-slice [policy-registry usage-metrics selection-cache project-registry server-language-policy payload]
  (let [scope (project-context/project-scope (or (:root_path payload) ".")
                                             (current-tenant-id))
        entry (project-context/ensure-project-index! project-registry
                                                     scope
                                                     {:paths (:paths payload)}
                                                     #(build-project-index policy-registry
                                                                           usage-metrics
                                                                           selection-cache
                                                                           server-language-policy
                                                                           payload
                                                                           (merge {:surface "grpc"} (current-request-correlation))
                                                                           (:root_path scope)
                                                                           true))
        index (:index entry)]
    (assoc (sci/literal-file-slice index (select-keys payload [:selection_id :snapshot_id :path :start_line :end_line]))
           :project_context (project-context-summary entry))))

(defn start-server [{:keys [host port api_key require_tenant authz_check policy_registry usage_metrics selection_cache
                            project_registry language_policy]}]
  (let [auth-config {:api_key api_key
                     :require_tenant require_tenant
                     :authz_check authz_check}
        selection-cache (or selection_cache (atom {:max_entries 128}))
        project-registry (or project_registry (project-context/project-registry))
        service (-> (ServerServiceDefinition/builder service-name)
                    (.addMethod health-method (unary-handler (constantly {})
                                                             grpc-proto/health-response
                                                             handle-health
                                                             nil
                                                             nil))
                    (.addMethod create-index-method (unary-handler grpc-proto/create-index-request->map
                                                                   grpc-proto/create-index-response
                                                                   (partial handle-create-index policy_registry usage_metrics selection-cache project-registry language_policy)
                                                                   auth-config
                                                                   :create_index))
                    (.addMethod resolve-context-method (unary-handler grpc-proto/resolve-context-request->map
                                                                      grpc-proto/resolve-context-response
                                                                      (partial handle-resolve-context policy_registry usage_metrics selection-cache project-registry language_policy)
                                                                      auth-config
                                                                      :resolve_context))
                    (.addMethod expand-context-method (unary-handler grpc-proto/expand-context-request->map
                                                                     grpc-proto/expand-context-response
                                                                     (partial handle-expand-context policy_registry usage_metrics selection-cache project-registry language_policy)
                                                                     auth-config
                                                                     :expand_context))
                    (.addMethod fetch-context-detail-method (unary-handler grpc-proto/fetch-context-detail-request->map
                                                                           grpc-proto/fetch-context-detail-response
                                                                           (partial handle-fetch-context-detail policy_registry usage_metrics selection-cache project-registry language_policy)
                                                                           auth-config
                                                                           :fetch_context_detail))
                    (.addMethod literal-file-slice-method (unary-handler grpc-proto/literal-file-slice-request->map
                                                                         grpc-proto/literal-file-slice-response
                                                                         (partial handle-literal-file-slice policy_registry usage_metrics selection-cache project-registry language_policy)
                                                                         auth-config
                                                                         :literal_file_slice))
                    (.build))
        intercepted-service (ServerInterceptors/intercept service (into-array ServerInterceptor [(metadata-context-interceptor)]))
        server (-> (NettyServerBuilder/forAddress (java.net.InetSocketAddress. ^String host (int port)))
                   (.addService intercepted-service)
                   (.build)
                   (.start))]
    {:server server
     :port (.getPort server)
     :host host}))

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
        {:keys [server port]} (start-server {:host host
                                             :port port
                                             :api_key api-key*
                                             :require_tenant require-tenant*
                                             :authz_check authz-check*
                                             :policy_registry policy-registry*
                                             :language_policy language-policy*
                                             :usage_metrics usage-metrics*})]
    (println (str "runtime_grpc_server_started host=" host " port=" port))
    (flush)
    (.awaitTermination server)))
