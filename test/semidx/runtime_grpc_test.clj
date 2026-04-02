(ns semidx.runtime-grpc-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]
            [semidx.runtime.authz :as runtime-authz]
            [semidx.runtime.grpc-proto :as grpc-proto]
            [semidx.runtime.project-context :as project-context]
            [semidx.runtime.retrieval-policy :as rp]
            [semidx.runtime.grpc :as runtime-grpc]
            [semidx.runtime.usage-metrics :as usage])
  (:import [io.grpc CallOptions ClientInterceptor ClientInterceptors ManagedChannelBuilder Metadata Metadata$Key Status StatusRuntimeException]
           [io.grpc.stub ClientCalls MetadataUtils]))

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

(defn- unary-call
  ([channel method request response->map]
   (unary-call channel method request response->map {}))
  ([channel method request response->map headers]
  (let [channel* (with-headers channel headers)
        response (ClientCalls/blockingUnaryCall channel* method CallOptions/DEFAULT request)]
    (response->map response))))

(deftest runtime-grpc-edge-conformance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1" :port 0})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (testing "health rpc"
        (let [resp (unary-call channel
                               runtime-grpc/health-method
                               (grpc-proto/health-request)
                               grpc-proto/health-response->map)]
          (is (= "ok" (:status resp)))))

      (testing "create-index rpc"
        (let [resp (unary-call channel
                               runtime-grpc/create-index-method
                               (grpc-proto/create-index-request {:root_path tmp-root})
                               grpc-proto/create-index-response->map)]
          (is (string? (:snapshot_id resp)))
          (is (= "initial_build" (get-in resp [:index_lifecycle :rebuild_reason])))
          (is (pos? (long (:file_count resp))))
          (is (pos? (long (:unit_count resp))))))

      (testing "resolve-context rpc"
        (let [query {:api_version "1.0"
                     :schema_version "1.0"
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
                               :allow_raw_code_escalation false}
                     :trace {:trace_id "02222222-2222-4222-8222-222222222222"
                             :request_id "runtime-grpc-test-001"
                             :actor_id "test_runner"}}
              resp (unary-call channel
                               runtime-grpc/resolve-context-method
                               (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                    :query query})
                               grpc-proto/resolve-context-response->map)]
          (is (= "1.0" (:api_version resp)))
          (is (string? (:selection_id resp)))
          (is (string? (:snapshot_id resp)))
          (is (= "completed" (:result_status resp)))
          (is (vector? (:focus resp)))
          (is (some #(= "my.app.order/process-order" (:symbol %))
                    (:focus resp)))))

      (testing "expand-context and fetch-context-detail rpc"
        (let [query {:api_version "1.0"
                     :schema_version "1.0"
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
                               :allow_raw_code_escalation false}
                     :trace {:trace_id "02322222-2222-4222-8222-222222222222"
                             :request_id "runtime-grpc-test-002"
                             :actor_id "test_runner"}}
              selection (unary-call channel
                                    runtime-grpc/resolve-context-method
                                    (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                         :query query})
                                    grpc-proto/resolve-context-response->map)
              expansion (unary-call channel
                                    runtime-grpc/expand-context-method
                                    (grpc-proto/expand-context-request {:root_path tmp-root
                                                                        :selection_id (:selection_id selection)
                                                                        :snapshot_id (:snapshot_id selection)})
                                    grpc-proto/expand-context-response->map)
              detail (unary-call channel
                                 runtime-grpc/fetch-context-detail-method
                                 (grpc-proto/fetch-context-detail-request {:root_path tmp-root
                                                                           :selection_id (:selection_id selection)
                                                                           :snapshot_id (:snapshot_id selection)})
                                 grpc-proto/fetch-context-detail-response->map)
              literal (unary-call channel
                                  runtime-grpc/literal-file-slice-method
                                  (grpc-proto/literal-file-slice-request {:root_path tmp-root
                                                                          :selection_id (:selection_id selection)
                                                                          :snapshot_id (:snapshot_id selection)
                                                                          :path "src/my/app/order.clj"
                                                                          :start_line 3
                                                                          :end_line 4})
                                  grpc-proto/literal-file-slice-response->map)]
          (is (seq (:skeletons expansion)))
          (is (map? (:impact_hints expansion)))
          (is (map? (:context_packet detail)))
          (is (map? (:diagnostics_trace detail)))
          (is (map? (:guardrail_assessment detail)))
          (is (vector? (:stage_events detail)))
          (is (some #(= "my.app.order/process-order" (:symbol %))
                    (get-in detail [:context_packet :relevant_units])))
          (is (= "literal_slice" (:projection_profile literal)))
          (is (= {:start_line 3 :end_line 4} (:returned_range literal)))
          (is (string? (:content literal)))
          (is (.contains ^String (:content literal) "process-order"))))

      (testing "invalid payload returns INVALID_ARGUMENT"
        (try
          (unary-call channel
                      runtime-grpc/resolve-context-method
                      (grpc-proto/resolve-context-request {:root_path tmp-root
                                                           :query "not-an-object"})
                      grpc-proto/resolve-context-response->map)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/INVALID_ARGUMENT)
                   (.getCode (.getStatus e))))
            (is (= "invalid_request"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))))
            (is (= "client"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-error-category" Metadata/ASCII_STRING_MARSHALLER)))))))

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
          (unary-call channel
                      runtime-grpc/create-index-method
                      (grpc-proto/create-index-request {:root_path tmp-root})
                      grpc-proto/create-index-response->map)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/UNAUTHENTICATED)
                   (.getCode (.getStatus e))))
            (is (= "unauthorized"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER)))))))

      (testing "api key without tenant -> INVALID_ARGUMENT"
        (try
          (unary-call channel
                      runtime-grpc/create-index-method
                      (grpc-proto/create-index-request {:root_path tmp-root})
                      grpc-proto/create-index-response->map
                      {"x-api-key" "secret-token"})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/INVALID_ARGUMENT)
                   (.getCode (.getStatus e))))
            (is (= "invalid_request"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER)))))))

      (testing "api key + tenant -> success"
        (let [resp (unary-call channel
                               runtime-grpc/create-index-method
                               (grpc-proto/create-index-request {:root_path tmp-root})
                               grpc-proto/create-index-response->map
                               {"x-api-key" "secret-token"
                                "x-tenant-id" "tenant-001"})]
          (is (string? (:snapshot_id resp)))
          (is (pos? (long (:file_count resp))))))

      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-language-activation-guidance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-no-lang" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "README.md" "# none")
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1" :port 0})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (try
        (unary-call channel
                    runtime-grpc/create-index-method
                    (grpc-proto/create-index-request {:root_path tmp-root})
                    grpc-proto/create-index-response->map)
        (is false "expected StatusRuntimeException")
        (catch StatusRuntimeException e
          (is (= (.getCode Status/INVALID_ARGUMENT)
                 (.getCode (.getStatus e))))
          (is (= "no_supported_languages_found"
                 (.get (.getTrailers e)
                       (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))))))
      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-language-activation-in-progress-retry-trailers-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-lock" (make-array java.nio.file.attribute.FileAttribute 0)))
        project-registry (project-context/project-registry)
        scope (project-context/project-scope tmp-root nil)
        _ (swap! project-registry assoc
                 (:registry_key scope)
                 {:root_path (:root_path scope)
                  :activation_state "activation_in_progress"
                  :activation_started_at (str (java.time.Instant/now))
                  :retry_after_seconds 2
                  :active_languages ["python"]
                  :detected_languages ["python"]})
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1" :port 0
                                                          :project_registry project-registry})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (try
        (unary-call channel
                    runtime-grpc/create-index-method
                    (grpc-proto/create-index-request {:root_path tmp-root})
                    grpc-proto/create-index-response->map)
        (is false "expected StatusRuntimeException")
        (catch StatusRuntimeException e
          (is (= (.getCode Status/FAILED_PRECONDITION)
                 (.getCode (.getStatus e))))
          (is (= "language_activation_in_progress"
                 (.get (.getTrailers e)
                       (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))))
          (is (= "2"
                 (.get (.getTrailers e)
                       (Metadata$Key/of "x-sci-retry-after-seconds" Metadata/ASCII_STRING_MARSHALLER))))
          (is (= "retry_same_request"
                 (.get (.getTrailers e)
                       (Metadata$Key/of "x-sci-recommended-action" Metadata/ASCII_STRING_MARSHALLER))))))
      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-language-refresh-required-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-refresh" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "app/main.py" "def run(value):\n    return value\n")
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1" :port 0})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (let [create-resp (unary-call channel
                                    runtime-grpc/create-index-method
                                    (grpc-proto/create-index-request {:root_path tmp-root})
                                    grpc-proto/create-index-response->map)]
        (is (string? (:snapshot_id create-resp)))
        (write-file! tmp-root "src/example/main.ts"
                     "export function runTs(value: string): string {\n  return value;\n}\n")
        (try
          (unary-call channel
                      runtime-grpc/resolve-context-method
                      (grpc-proto/resolve-context-request
                       {:root_path tmp-root
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
                                :trace {:request_id "runtime-grpc-refresh-001"}}})
                      grpc-proto/resolve-context-response->map)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/FAILED_PRECONDITION)
                   (.getCode (.getStatus e))))
            (is (= "language_refresh_required"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER)))))))
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
          (unary-call channel
                      runtime-grpc/create-index-method
                      (grpc-proto/create-index-request {:root_path tmp-root})
                      grpc-proto/create-index-response->map
                      headers)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/PERMISSION_DENIED)
                   (.getCode (.getStatus e)))))))

      (testing "allowed path prefix passes"
        (let [resp (unary-call channel
                               runtime-grpc/create-index-method
                               (grpc-proto/create-index-request
                                {:root_path tmp-root
                                 :paths ["src/my/app/order.clj"]})
                               grpc-proto/create-index-response->map
                               headers)]
          (is (string? (:snapshot_id resp)))
          (is (pos? (long (:file_count resp))))))

      (testing "disallowed path prefix denied"
        (try
          (unary-call channel
                      runtime-grpc/create-index-method
                      (grpc-proto/create-index-request
                       {:root_path tmp-root
                        :paths ["test/my/app/order_test.clj"]})
                      grpc-proto/create-index-response->map
                      headers)
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/PERMISSION_DENIED)
                   (.getCode (.getStatus e)))))))

      (testing "unknown tenant denied"
        (try
          (unary-call channel
                      runtime-grpc/create-index-method
                      (grpc-proto/create-index-request
                       {:root_path tmp-root
                        :paths ["src/my/app/order.clj"]})
                      grpc-proto/create-index-response->map
                      {"x-api-key" "secret-token"
                       "x-tenant-id" "tenant-999"})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/PERMISSION_DENIED)
                   (.getCode (.getStatus e)))))))

      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-policy-registry-selection-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-policy-registry-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        active-policy (-> (rp/default-retrieval-policy)
                          (assoc :policy_id "heuristic_v1_grpc_active")
                          (assoc :version "2026-03-11")
                          (assoc-in [:thresholds :top_authority_min] 500))
        shadow-policy (-> (rp/default-retrieval-policy)
                          (assoc :policy_id "heuristic_v1_grpc_shadow")
                          (assoc :version "2026-03-12")
                          (assoc-in [:thresholds :top_authority_min] 500))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1"
                                                          :port 0
                                                          :policy_registry registry})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))
        query {:api_version "1.0"
               :schema_version "1.0"
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
                         :allow_raw_code_escalation false}
               :trace {:trace_id "03222222-2222-4222-8222-222222222222"
                       :request_id "runtime-grpc-policy-registry-test-001"
                       :actor_id "test_runner"}}]
    (try
      (testing "active registry policy is used when no override is passed"
        (let [selection (unary-call channel
                                    runtime-grpc/resolve-context-method
                                    (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                         :query query})
                                    grpc-proto/resolve-context-response->map)
              resp (unary-call channel
                               runtime-grpc/fetch-context-detail-method
                               (grpc-proto/fetch-context-detail-request {:root_path tmp-root
                                                                         :selection_id (:selection_id selection)
                                                                         :snapshot_id (:snapshot_id selection)})
                               grpc-proto/fetch-context-detail-response->map)]
          (is (= "heuristic_v1_grpc_active"
                 (get-in resp [:diagnostics_trace :retrieval_policy :policy_id])))
          (is (not= "top_authority"
                    (get-in resp [:context_packet :relevant_units 0 :rank_band])))))

      (testing "selector-based override resolves from registry"
        (let [selection (unary-call channel
                                    runtime-grpc/resolve-context-method
                                    (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                         :query query
                                                                         :retrieval_policy {:policy_id "heuristic_v1_grpc_shadow"
                                                                                            :version "2026-03-12"}})
                                    grpc-proto/resolve-context-response->map)
              resp (unary-call channel
                               runtime-grpc/fetch-context-detail-method
                               (grpc-proto/fetch-context-detail-request {:root_path tmp-root
                                                                         :selection_id (:selection_id selection)
                                                                         :snapshot_id (:snapshot_id selection)})
                               grpc-proto/fetch-context-detail-response->map)]
          (is (= "heuristic_v1_grpc_shadow"
                 (get-in resp [:diagnostics_trace :retrieval_policy :policy_id])))
          (is (not= "top_authority"
                    (get-in resp [:context_packet :relevant_units 0 :rank_band])))))

      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-staged-selection-error-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-selection-errors" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        selection-cache (atom {:max_entries 1})
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1"
                                                          :port 0
                                                          :selection_cache selection-cache})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))
        query {:api_version "1.0"
               :schema_version "1.0"
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
                         :allow_raw_code_escalation false}
               :trace {:trace_id "07222222-2222-4222-8222-222222222222"
                       :request_id "runtime-grpc-selection-errors-001"
                       :actor_id "test_runner"}}]
    (try
      (let [selection-a (unary-call channel
                                    runtime-grpc/resolve-context-method
                                    (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                         :query query})
                                    grpc-proto/resolve-context-response->map)]
        (try
          (unary-call channel
                      runtime-grpc/fetch-context-detail-method
                      (grpc-proto/fetch-context-detail-request {:root_path tmp-root
                                                                :selection_id (:selection_id selection-a)
                                                                :snapshot_id "wrong-snapshot"})
                      grpc-proto/fetch-context-detail-response->map)
          (is false "expected snapshot mismatch")
          (catch StatusRuntimeException e
            (is (= (.getCode Status/FAILED_PRECONDITION)
                   (.getCode (.getStatus e))))
            (is (= "snapshot_mismatch"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))))))
        (let [_selection-b (unary-call channel
                                       runtime-grpc/resolve-context-method
                                       (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                            :query query})
                                       grpc-proto/resolve-context-response->map)]
          (try
            (unary-call channel
                        runtime-grpc/fetch-context-detail-method
                        (grpc-proto/fetch-context-detail-request {:root_path tmp-root
                                                                  :selection_id (:selection_id selection-a)
                                                                  :snapshot_id (:snapshot_id selection-a)})
                        grpc-proto/fetch-context-detail-response->map)
            (is false "expected selection eviction")
            (catch StatusRuntimeException e
              (is (= (.getCode Status/FAILED_PRECONDITION)
                     (.getCode (.getStatus e))))
              (is (= "selection_evicted"
                     (.get (.getTrailers e)
                           (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))))))))
      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))

(deftest runtime-grpc-tenant-trace-correlation-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-correlation-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1"
                                                          :port 0
                                                          :api_key "secret-token"
                                                          :require_tenant true
                                                          :usage_metrics sink})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))
        create-headers {"x-api-key" "secret-token"
                        "x-tenant-id" "tenant-001"
                        "x-trace-id" "04222222-2222-4222-8222-222222222222"
                        "x-request-id" "runtime-grpc-create-trace-001"
                        "x-session-id" "grpc-session-001"
                        "x-task-id" "grpc-task-001"
                        "x-actor-id" "grpc-edge-tester"}
        query {:api_version "1.0"
               :schema_version "1.0"
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
                         :allow_raw_code_escalation false}
               :trace {:trace_id "05222222-2222-4222-8222-222222222222"
                       :request_id "runtime-grpc-resolve-trace-001"
                       :session_id "grpc-session-002"
                       :task_id "grpc-task-002"
                       :actor_id "grpc-query-runner"}}]
    (try
      (let [_create-resp (unary-call channel
                                     runtime-grpc/create-index-method
                                     (grpc-proto/create-index-request {:root_path tmp-root})
                                     grpc-proto/create-index-response->map
                                     create-headers)
            _resolve-resp (unary-call channel
                                      runtime-grpc/resolve-context-method
                                      (grpc-proto/resolve-context-request {:root_path tmp-root
                                                                           :query query})
                                      grpc-proto/resolve-context-response->map
                                      {"x-api-key" "secret-token"
                                       "x-tenant-id" "tenant-001"
                                       "x-trace-id" "04222222-2222-4222-8222-222222222222"
                                       "x-request-id" "runtime-grpc-header-fallback-001"
                                       "x-session-id" "grpc-session-header"
                                       "x-task-id" "grpc-task-header"
                                       "x-actor-id" "grpc-header-actor"})
            events (usage/emitted-events sink)
            create-event (first (filter #(= "create_index" (:operation %)) events))
            resolve-event (first (filter #(= "resolve_context" (:operation %)) events))]
        (testing "usage events retain tenant and trace consistency"
          (is (= "grpc" (:surface create-event)))
          (is (= "tenant-001" (:tenant_id create-event)))
          (is (= "04222222-2222-4222-8222-222222222222" (:trace_id create-event)))
          (is (= "runtime-grpc-create-trace-001" (:request_id create-event)))
          (is (= "grpc-session-001" (:session_id create-event)))
          (is (= "grpc-task-001" (:task_id create-event)))
          (is (= "grpc-edge-tester" (:actor_id create-event)))
          (is (= "grpc" (:surface resolve-event)))
          (is (= "tenant-001" (:tenant_id resolve-event)))
          (is (= "05222222-2222-4222-8222-222222222222" (:trace_id resolve-event)))
          (is (= "runtime-grpc-resolve-trace-001" (:request_id resolve-event)))
          (is (= "grpc-session-002" (:session_id resolve-event)))
          (is (= "grpc-task-002" (:task_id resolve-event)))
          (is (= "grpc-query-runner" (:actor_id resolve-event)))))
      (testing "error trailers retain correlation markers"
        (try
          (unary-call channel
                      runtime-grpc/resolve-context-method
                      (grpc-proto/resolve-context-request {:root_path tmp-root
                                                           :query "not-an-object"})
                      grpc-proto/resolve-context-response->map
                      {"x-api-key" "secret-token"
                       "x-tenant-id" "tenant-001"
                       "x-trace-id" "06222222-2222-4222-8222-222222222222"
                       "x-request-id" "runtime-grpc-error-trace-001"
                       "x-session-id" "grpc-session-error"
                       "x-task-id" "grpc-task-error"
                       "x-actor-id" "grpc-error-runner"})
          (is false "expected StatusRuntimeException")
          (catch StatusRuntimeException e
            (is (= "06222222-2222-4222-8222-222222222222"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-trace-id" Metadata/ASCII_STRING_MARSHALLER))))
            (is (= "runtime-grpc-error-trace-001"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-request-id" Metadata/ASCII_STRING_MARSHALLER))))
            (is (= "tenant-001"
                   (.get (.getTrailers e)
                         (Metadata$Key/of "x-sci-tenant-id" Metadata/ASCII_STRING_MARSHALLER)))))))
      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))
(deftest runtime-grpc-unsupported-api-version-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-grpc-unsupported-api-version" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-grpc-sample-repo! tmp-root)
        {:keys [server port]} (runtime-grpc/start-server {:host "127.0.0.1" :port 0})
        channel (-> (ManagedChannelBuilder/forAddress "127.0.0.1" (int port))
                    (.usePlaintext)
                    (.build))]
    (try
      (try
        (unary-call channel
                    runtime-grpc/resolve-context-method
                    (grpc-proto/resolve-context-request {:root_path tmp-root
                                                         :query {:api_version "2.0"
                                                                 :schema_version "1.0"
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
                                                                           :allow_raw_code_escalation false}
                                                                 :trace {:trace_id "06222222-2222-4222-8222-222222222222"
                                                                         :request_id "runtime-grpc-unsupported-api-version-001"
                                                                         :actor_id "test_runner"}}})
                    grpc-proto/resolve-context-response->map)
        (is false "expected unsupported api_version")
        (catch StatusRuntimeException e
          (is (= (.getCode Status/INVALID_ARGUMENT)
                 (.getCode (.getStatus e))))
          (is (= "unsupported_api_version"
                 (.get (.getTrailers e)
                       (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))))))
      (finally
        (.shutdownNow channel)
        (.shutdownNow server)))))
