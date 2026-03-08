(ns semantic-code-indexing.runtime.grpc
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [semantic-code-indexing.runtime.authz :as authz]
            [semantic-code-indexing.core :as sci])
  (:import [io.grpc MethodDescriptor MethodDescriptor$Marshaller MethodDescriptor$MethodType
                     Context Contexts Metadata Metadata$Key
                     ServerCall ServerCall$Listener ServerCallHandler ServerInterceptor ServerInterceptors
                     ServerServiceDefinition Status]
           [io.grpc.protobuf ProtoUtils]
           [io.grpc.stub ServerCalls ServerCalls$UnaryMethod StreamObserver]
           [io.grpc.netty.shaded.io.grpc.netty NettyServerBuilder]
           [com.google.protobuf Struct]
           [com.google.protobuf.util JsonFormat]))

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
          "--require-tenant" (recur (assoc m :require_tenant true) (cons v rest))
          (recur m rest))))))

(def ^:private struct-marshaller
  (ProtoUtils/marshaller (Struct/getDefaultInstance)))

(defn- unary-method [method-name]
  (-> (MethodDescriptor/newBuilder)
      (.setType MethodDescriptor$MethodType/UNARY)
      (.setFullMethodName (MethodDescriptor/generateFullMethodName service-name method-name))
      (.setRequestMarshaller struct-marshaller)
      (.setResponseMarshaller struct-marshaller)
      (.build)))

(def health-method (unary-method "Health"))
(def create-index-method (unary-method "CreateIndex"))
(def resolve-context-method (unary-method "ResolveContext"))

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

(defn- struct->map [^Struct value]
  (if (nil? value)
    {}
    (let [s (JsonFormat/printer)
          json-str (.print s value)]
      (json/read-str json-str :key-fn keyword))))

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

(defn- map->struct [m]
  (let [b (Struct/newBuilder)
        parser (JsonFormat/parser)
        payload (json/write-str (or m {}) :escape-slash false)]
    (.merge parser payload b)
    (.build b)))

(defn- reply! [^StreamObserver response-observer payload]
  (.onNext response-observer (map->struct payload))
  (.onCompleted response-observer))

(defn- fail! [^StreamObserver response-observer ^Status status message]
  (.onError response-observer (.asRuntimeException (.withDescription status message))))

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
  (case code
    :invalid_request Status/INVALID_ARGUMENT
    :internal_error Status/INTERNAL
    Status/PERMISSION_DENIED))

(defn- authz-violation [auth-config operation payload]
  (let [{:keys [allowed? code message]}
        (authz/evaluate (:authz_check auth-config)
                        {:operation operation
                         :tenant_id (current-tenant-id)
                         :root_path (or (:root_path payload) ".")
                         :paths (:paths payload)})]
    (when-not allowed?
      {:status (authz-code->status code)
       :message (or message "request denied by authz policy")})))

(defn- unary-handler [f auth-config operation]
  (ServerCalls/asyncUnaryCall
   (reify ServerCalls$UnaryMethod
     (invoke [_ request response-observer]
       (try
         (if-let [{:keys [status message]} (auth-violation auth-config)]
           (fail! response-observer status message)
           (let [payload (-> request struct->map normalize-numeric-shapes)]
             (if-let [{:keys [status message]} (authz-violation auth-config operation payload)]
               (fail! response-observer status message)
               (reply! response-observer (f payload)))))
         (catch clojure.lang.ExceptionInfo e
           (let [m (ex-data e)]
             (if (= :invalid_request (:type m))
               (fail! response-observer Status/INVALID_ARGUMENT (or (:message m) (.getMessage e)))
               (fail! response-observer Status/INTERNAL (.getMessage e)))))
         (catch Exception e
           (fail! response-observer Status/INTERNAL (.getMessage e))))))))

(defn- handle-health [_]
  {:status "ok"
   :service "semantic-code-indexing-runtime-grpc"})

(defn- handle-create-index [payload]
  (let [index (sci/create-index {:root_path (or (:root_path payload) ".")
                                 :paths (:paths payload)
                                 :parser_opts (:parser_opts payload)})]
    {:snapshot_id (:snapshot_id index)
     :indexed_at (:indexed_at index)
     :file_count (count (:files index))
     :unit_count (count (:units index))
     :repo_map (sci/repo-map index)}))

(defn- handle-resolve-context [payload]
  (let [query (:query payload)]
    (when-not (map? query)
      (throw (ex-info "query must be an object"
                      {:type :invalid_request
                       :message "query must be an object"})))
    (let [index (sci/create-index {:root_path (or (:root_path payload) ".")
                                   :paths (:paths payload)
                                   :parser_opts (:parser_opts payload)})]
      (sci/resolve-context index query))))

(defn start-server [{:keys [host port api_key require_tenant authz_check]}]
  (let [auth-config {:api_key api_key
                     :require_tenant require_tenant
                     :authz_check authz_check}
        service (-> (ServerServiceDefinition/builder service-name)
                    (.addMethod health-method (unary-handler handle-health nil nil))
                    (.addMethod create-index-method (unary-handler handle-create-index auth-config :create_index))
                    (.addMethod resolve-context-method (unary-handler handle-resolve-context auth-config :resolve_context))
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
  (let [{:keys [host port api_key require_tenant authz_policy_file]} (parse-args args)
        api-key* (or (not-empty api_key) (System/getenv "SCI_RUNTIME_API_KEY"))
        require-tenant* (or require_tenant (parse-bool (System/getenv "SCI_RUNTIME_REQUIRE_TENANT")))
        authz-policy-file* (or (not-empty authz_policy_file) (System/getenv "SCI_RUNTIME_AUTHZ_POLICY_FILE"))
        authz-check* (when (seq authz-policy-file*)
                       (authz/load-policy-authorizer authz-policy-file*))
        {:keys [server port]} (start-server {:host host
                                             :port port
                                             :api_key api-key*
                                             :require_tenant require-tenant*
                                             :authz_check authz-check*})]
    (println (str "runtime_grpc_server_started host=" host " port=" port))
    (flush)
    (.awaitTermination server)))
