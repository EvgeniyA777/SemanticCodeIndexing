(ns semantic-code-indexing.runtime-grpc-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semantic-code-indexing.runtime.authz :as runtime-authz]
            [semantic-code-indexing.runtime.grpc :as runtime-grpc])
  (:import [io.grpc CallOptions ClientInterceptor ClientInterceptors ManagedChannelBuilder Metadata Metadata$Key Status StatusRuntimeException]
           [io.grpc.stub ClientCalls MetadataUtils]
           [com.google.protobuf Struct]
           [com.google.protobuf.util JsonFormat]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-grpc-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n"))

(defn- write-authz-policy! [path policy]
  (spit path (pr-str policy)))

(defn- with-headers [channel headers]
  (if (empty? headers)
    channel
    (let [metadata (Metadata.)]
      (doseq [[k v] headers]
        (.put metadata (Metadata$Key/of (str k) Metadata/ASCII_STRING_MARSHALLER) (str v)))
      (ClientInterceptors/intercept channel
                                    (into-array ClientInterceptor
                                                [(MetadataUtils/newAttachHeadersInterceptor metadata)])))))

(defn- map->struct [m]
  (let [b (Struct/newBuilder)
        parser (JsonFormat/parser)
        payload (json/write-str (or m {}) :escape-slash false)]
    (.merge parser payload b)
    (.build b)))

(defn- struct->map [^Struct value]
  (let [printer (JsonFormat/printer)
        json-str (.print printer value)]
    (json/read-str json-str :key-fn keyword)))

(defn- unary-call
  ([channel method payload]
   (unary-call channel method payload {}))
  ([channel method payload headers]
  (let [request (map->struct payload)
        channel* (with-headers channel headers)
        response (ClientCalls/blockingUnaryCall channel* method CallOptions/DEFAULT request)]
    (struct->map response))))

(deftest runtime-grpc-edge-conformance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1" :port 0})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (testing "health rpc"
        (let [resp (unary-call channel runtime-grpc/health-method {})]
          (is (= "ok" (:status resp)))))

      (testing "create-index rpc"
        (let [resp (unary-call channel runtime-grpc/create-index-method {:root_path tmp-root})]
          (is (string? (:snapshot_id resp)))
          (is (pos? (long (:file_count resp))))
          (is (pos? (long (:unit_count resp))))))

      (testing "resolve-context rpc"
        (let [query {:schema_version "1.0"
                     :intent {:purpose "code_understanding"
                              :details "Locate authority implementation for process-order."}
                     :targets {:symbols ["my.app.order/process-order"]
                               :paths ["src/my/app/order.clj"]}
                     :constraints {:token_budget 1200
                                   :max_raw_code_level "enclosing_unit"
                                   :freshness "current_snapshot"}
                     :hints {:prefer_definitions_over_callers true}
                     :options {:include_tests true
                               :include_impact_hints true
                               :allow_raw_code_escalation false
                               :favor_compact_packet true
                               :favor_higher_recall false}
                     :trace {:trace_id "02222222-2222-4222-8222-222222222222"
                             :request_id "runtime-grpc-test-001"
                             :actor_id "test_runner"}}
              resp (unary-call channel runtime-grpc/resolve-context-method {:root_path tmp-root
                                                                            :query query})]
          (is (map? (:context_packet resp)))
          (is (map? (:diagnostics_trace resp)))
          (is (map? (:guardrail_assessment resp)))
          (is (vector? (:stage_events resp)))
          (is (some #(= "my.app.order/process-order" (:symbol %))
                    (get-in resp [:context_packet :relevant_units])))))

      (testing "invalid payload returns INVALID_ARGUMENT"
        (try
          (unary-call channel runtime-grpc/resolve-context-method {:root_path tmp-root
                                                                   :query "not-an-object"})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/INVALID_ARGUMENT)
                   (.getCode (.getStatus e)))))))

      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-authz-boundary-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-auth-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1"
                                                          :port 0
                                                          :api_key "secret-token"
                                                          :require_tenant true})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (testing "missing api key -> UNAUTHENTICATED"
        (try
          (unary-call channel runtime-grpc/create-index-method {:root_path tmp-root})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/UNAUTHENTICATED)
                   (.getCode (.getStatus e)))))))

      (testing "api key without tenant -> INVALID_ARGUMENT"
        (try
          (unary-call channel runtime-grpc/create-index-method {:root_path tmp-root}
                      {"x-api-key" "secret-token"})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/INVALID_ARGUMENT)
                   (.getCode (.getStatus e)))))))

      (testing "api key + tenant -> success"
        (let [resp (unary-call channel runtime-grpc/create-index-method {:root_path tmp-root}
                               {"x-api-key" "secret-token"
                                "x-tenant-id" "tenant-001"})]
          (is (string? (:snapshot_id resp)))
          (is (pos? (long (:file_count resp))))))

      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-authz-policy-contract-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-policy-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        policy-path (str (io/file tmp-root "authz-policy.edn"))
        _ (write-authz-policy! policy-path
                               {:tenants {"tenant-001" {:allowed_roots [tmp-root]
                                                        :allowed_path_prefixes ["src/my/app"]}}})
        authz-check (runtime-authz/load-policy-authorizer policy-path)
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1"
                                                          :port 0
                                                          :api_key "secret-token"
                                                          :require_tenant true
                                                          :authz_check authz-check})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))
        headers {"x-api-key" "secret-token"
                 "x-tenant-id" "tenant-001"}]
    (try
      (testing "tenant with path restrictions must send explicit paths"
        (try
          (unary-call channel runtime-grpc/create-index-method {:root_path tmp-root} headers)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/PERMISSION_DENIED)
                   (.getCode (.getStatus e)))))))

      (testing "allowed path prefix passes"
        (let [resp (unary-call channel runtime-grpc/create-index-method
                               {:root_path tmp-root
                                :paths ["src/my/app/order.clj"]}
                               headers)]
          (is (string? (:snapshot_id resp)))
          (is (pos? (long (:file_count resp))))))

      (testing "disallowed path prefix denied"
        (try
          (unary-call channel runtime-grpc/create-index-method
                      {:root_path tmp-root
                       :paths ["test/my/app/order_test.clj"]}
                      headers)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/PERMISSION_DENIED)
                   (.getCode (.getStatus e)))))))

      (testing "unknown tenant denied"
        (try
          (unary-call channel runtime-grpc/create-index-method
                      {:root_path tmp-root
                       :paths ["src/my/app/order.clj"]}
                      {"x-api-key" "secret-token"
                       "x-tenant-id" "tenant-999"})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/PERMISSION_DENIED)
                   (.getCode (.getStatus e)))))))

      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))
