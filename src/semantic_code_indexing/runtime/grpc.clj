(ns semantic-code-indexing.runtime.grpc
  (:gen-class)
  (:require [clojure.string :as str]
            [semantic-code-indexing.runtime.authz :as authz]
            [semantic-code-indexing.runtime.errors :as errors]
            [semantic-code-indexing.runtime.grpc-proto :as grpc-proto]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]
            [semantic-code-indexing.core :as sci])
  (:import [io.grpc MethodDescriptor MethodDescriptor$Marshaller MethodDescriptor$MethodType
                     Context Contexts Metadata Metadata$Key
                     ServerCall ServerCall$Listener ServerCallHandler ServerInterceptor ServerInterceptors
                     ServerServiceDefinition Status]
           [io.grpc.stub ServerCalls ServerCalls$UnaryMethod StreamObserver]
           [io.grpc.netty.shaded.io.grpc.netty NettyServerBuilder]))

(def ^:private service-name "semantic_code_indexing.RuntimeService")

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
          "--require-tenant" (recur (assoc m :require_tenant true) (cons v rest))
          (recur m rest))))))

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

(def ^:private api-key-header
  (Metadata$Key/of "x-api-key" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private tenant-id-header
  (Metadata$Key/of "x-tenant-id" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private api-key-context (Context/key "sci-api-key"))
(def ^:private tenant-id-context (Context/key "sci-tenant-id"))

(defn- metadata-context-interceptor []
  (reify ServerInterceptor
    (^ServerCall$Listener interceptCall [_ ^ServerCall call ^Metadata headers ^ServerCallHandler next]
      (let [api-key (.get headers api-key-header)
            tenant-id (.get headers tenant-id-header)
            ctx (-> (Context/current)
                    (.withValue api-key-context api-key)
                    (.withValue tenant-id-context tenant-id))]
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

(defn- fail! [^StreamObserver response-observer e]
  (let [status (errors/grpc-status e)
        description (errors/grpc-description e)
        trailers (errors/grpc-trailers e)]
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
   :service "semantic-code-indexing-runtime-grpc"})

(defn- handle-create-index [policy-registry payload]
  (let [index (sci/create-index {:root_path (or (:root_path payload) ".")
                                 :paths (:paths payload)
                                 :parser_opts (:parser_opts payload)
                                 :policy_registry policy-registry})]
    {:snapshot_id (:snapshot_id index)
     :indexed_at (:indexed_at index)
     :index_lifecycle (:index_lifecycle index)
     :file_count (count (:files index))
     :unit_count (count (:units index))
     :repo_map (sci/repo-map index)}))

(defn- handle-resolve-context [policy-registry payload]
  (let [query (:query payload)
        retrieval-policy (:retrieval_policy payload)]
    (when-not (map? query)
      (throw (ex-info "query must be an object"
                      {:type :invalid_request
                       :message "query must be an object"})))
    (when (and (some? retrieval-policy) (not (map? retrieval-policy)))
      (throw (ex-info "retrieval_policy must be an object"
                      {:type :invalid_request
                       :message "retrieval_policy must be an object"})))
    (let [index (sci/create-index {:root_path (or (:root_path payload) ".")
                                   :paths (:paths payload)
                                   :parser_opts (:parser_opts payload)
                                   :policy_registry policy-registry})]
      (sci/resolve-context index query {:retrieval_policy retrieval-policy
                                        :policy_registry policy-registry}))))

(defn start-server [{:keys [host port api_key require_tenant authz_check policy_registry]}]
  (let [auth-config {:api_key api_key
                     :require_tenant require_tenant
                     :authz_check authz_check}
        service (-> (ServerServiceDefinition/builder service-name)
                    (.addMethod health-method (unary-handler (constantly {})
                                                             grpc-proto/health-response
                                                             handle-health
                                                             nil
                                                             nil))
                    (.addMethod create-index-method (unary-handler grpc-proto/create-index-request->map
                                                                   grpc-proto/create-index-response
                                                                   (partial handle-create-index policy_registry)
                                                                   auth-config
                                                                   :create_index))
                    (.addMethod resolve-context-method (unary-handler grpc-proto/resolve-context-request->map
                                                                      grpc-proto/resolve-context-response
                                                                      (partial handle-resolve-context policy_registry)
                                                                      auth-config
                                                                      :resolve_context))
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
  (let [{:keys [host port api_key require_tenant authz_policy_file policy_registry_file]} (parse-args args)
        api-key* (or (not-empty api_key) (System/getenv "SCI_RUNTIME_API_KEY"))
        require-tenant* (or require_tenant (parse-bool (System/getenv "SCI_RUNTIME_REQUIRE_TENANT")))
        authz-policy-file* (or (not-empty authz_policy_file) (System/getenv "SCI_RUNTIME_AUTHZ_POLICY_FILE"))
        policy-registry-file* (or (not-empty policy_registry_file) (System/getenv "SCI_RUNTIME_POLICY_REGISTRY_FILE"))
        authz-check* (when (seq authz-policy-file*)
                       (authz/load-policy-authorizer authz-policy-file*))
        policy-registry* (when (seq policy-registry-file*)
                           (rp/load-registry policy-registry-file*))
        {:keys [server port]} (start-server {:host host
                                             :port port
                                             :api_key api-key*
                                             :require_tenant require-tenant*
                                             :authz_check authz-check*
                                             :policy_registry policy-registry*})]
    (println (str "runtime_grpc_server_started host=" host " port=" port))
    (flush)
    (.awaitTermination server)))
