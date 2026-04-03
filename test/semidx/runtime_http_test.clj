(ns semidx.runtime-http-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]
            [semidx.runtime.authz :as runtime-authz]
            [semidx.runtime.project-context :as project-context]
            [semidx.runtime.retrieval-policy :as rp]
            [semidx.runtime.http :as runtime-http]
            [semidx.runtime.usage-metrics :as usage])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-http-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n"))

(defn- write-authz-policy! [path policy]
  (spit path (pr-str policy)))

(defn- http-request
  ([^HttpClient client method url body]
   (http-request client method url body {}))
  ([^HttpClient client method url body headers]
  (let [publisher (if (some? body)
                    (HttpRequest$BodyPublishers/ofString body)
                    (HttpRequest$BodyPublishers/noBody))
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
                    (#(reduce (fn [builder [k v]] (.header builder (str k) (str v))) % headers))
                    (.method method publisher)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        text-body (.body response)]
    {:status (.statusCode response)
     :headers (.map (.headers response))
     :body text-body
     :json (when (seq text-body) (json/read-str text-body :key-fn keyword))})))

(defn- post-json
  ([^HttpClient client url payload]
   (post-json client url payload {}))
  ([^HttpClient client url payload headers]
   (http-request client "POST" url (json/write-str payload) headers)))

(defn- wait-health! [^HttpClient client base-url]
  (loop [attempt 0]
    (let [resp (http-request client "GET" (str base-url "/health") nil)]
      (if (or (= 200 (:status resp)) (>= attempt 20))
        resp
        (do (Thread/sleep 50)
            (recur (inc attempt)))))))

(deftest runtime-http-edge-conformance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        server (runtime-http/start-server {:host "127.0.0.1" :port 0})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            health (wait-health! client base-url)]
        (testing "health endpoint"
          (is (= 200 (:status health)))
          (is (= "ok" (get-in health [:json :status]))))

        (testing "index create endpoint"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root})]
            (is (= 200 (:status resp)))
            (is (string? (get-in resp [:json :snapshot_id])))
            (is (= "initial_build" (get-in resp [:json :index_lifecycle :rebuild_reason])))
            (is (pos-int? (get-in resp [:json :file_count])))
            (is (pos-int? (get-in resp [:json :unit_count])))))

        (testing "resolve-context endpoint"
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
                       :trace {:trace_id "01111111-1111-4111-8111-111111111111"
                               :request_id "runtime-http-test-001"
                               :actor_id "test_runner"}}
                resp (post-json client
                                (str base-url "/v1/retrieval/resolve-context")
                                {:root_path tmp-root
                                 :query query})]
            (is (= 200 (:status resp)))
            (is (= "1.0" (get-in resp [:json :api_version])))
            (is (string? (get-in resp [:json :selection_id])))
            (is (string? (get-in resp [:json :snapshot_id])))
            (is (= "completed" (get-in resp [:json :result_status])))
            (is (= "selection" (get-in resp [:json :projection_profile])))
            (is (= "api_shape" (get-in resp [:json :recommended_projection_profile])))
            (is (vector? (get-in resp [:json :focus])))
            (is (some #(= "my.app.order/process-order" (:symbol %))
                      (get-in resp [:json :focus])))
            (is (= ["expand_context" "fetch_context_detail"]
                   (get-in resp [:json :next_step :available_actions])))))

        (testing "expand-context and fetch-context-detail endpoints"
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
                       :trace {:trace_id "02111111-1111-4111-8111-111111111111"
                               :request_id "runtime-http-test-002"
                               :actor_id "test_runner"}}
                resolve-resp (post-json client
                                        (str base-url "/v1/retrieval/resolve-context")
                                        {:root_path tmp-root
                                         :query query})
                selection-id (get-in resolve-resp [:json :selection_id])
                snapshot-id (get-in resolve-resp [:json :snapshot_id])
                expand-resp (post-json client
                                       (str base-url "/v1/retrieval/expand-context")
                                       {:root_path tmp-root
                                        :selection_id selection-id
                                        :snapshot_id snapshot-id})
                detail-resp (post-json client
                                       (str base-url "/v1/retrieval/fetch-context-detail")
                                       {:root_path tmp-root
                                        :selection_id selection-id
                                        :snapshot_id snapshot-id})
                literal-resp (post-json client
                                        (str base-url "/v1/retrieval/literal-file-slice")
                                        {:root_path tmp-root
                                         :selection_id selection-id
                                         :snapshot_id snapshot-id
                                         :path "src/my/app/order.clj"
                                         :start_line 3
                                         :end_line 4})]
            (is (= 200 (:status expand-resp)))
            (is (seq (get-in expand-resp [:json :skeletons])))
            (is (map? (get-in expand-resp [:json :impact_hints])))
            (is (= "api_shape" (get-in expand-resp [:json :projection_profile])))
            (is (= "detail" (get-in expand-resp [:json :recommended_projection_profile])))
            (is (= 200 (:status detail-resp)))
            (is (map? (get-in detail-resp [:json :context_packet])))
            (is (map? (get-in detail-resp [:json :diagnostics_trace])))
            (is (map? (get-in detail-resp [:json :guardrail_assessment])))
            (is (vector? (get-in detail-resp [:json :stage_events])))
            (is (= "detail" (get-in detail-resp [:json :projection_profile])))
            (is (some #(= "my.app.order/process-order" (:symbol %))
                      (get-in detail-resp [:json :context_packet :relevant_units])))
            (is (= 200 (:status literal-resp)))
            (is (= "literal_slice" (get-in literal-resp [:json :projection_profile])))
            (is (= {:start_line 3 :end_line 4} (get-in literal-resp [:json :returned_range])))
            (is (str/includes? (get-in literal-resp [:json :content]) "process-order"))))

        (testing "snapshot-diff endpoint"
          (let [baseline-resp (post-json client
                                         (str base-url "/v1/index/create")
                                         {:root_path tmp-root})
                baseline-snapshot-id (get-in baseline-resp [:json :snapshot_id])
                _ (write-file! tmp-root
                               "src/my/app/order.clj"
                               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n\n(defn audit-order [order]\n  (:id order))\n")
                rebuilt-resp (post-json client
                                        (str base-url "/v1/index/create")
                                        {:root_path tmp-root})
                diff-resp (post-json client
                                     (str base-url "/v1/retrieval/snapshot-diff")
                                     {:root_path tmp-root
                                      :baseline_snapshot_id baseline-snapshot-id})]
            (is (= 200 (:status baseline-resp)))
            (is (= 200 (:status rebuilt-resp)))
            (is (= 200 (:status diff-resp)))
            (is (= baseline-snapshot-id
                   (get-in diff-resp [:json :baseline_snapshot_id])))
            (is (= (get-in rebuilt-resp [:json :snapshot_id])
                   (get-in diff-resp [:json :current_snapshot_id])))
            (is (= "diff" (get-in diff-resp [:json :projection_profile])))
            (is (= 1 (get-in diff-resp [:json :summary :change_counts :added])))
            (is (= 1 (get-in diff-resp [:json :summary :total_changes])))
            (is (= "added" (get-in diff-resp [:json :changes 0 :change_type])))))

        (testing "method and payload validation"
          (let [method-resp (http-request client "GET" (str base-url "/v1/index/create") nil)
                invalid-resp (post-json client
                                        (str base-url "/v1/retrieval/resolve-context")
                                        {:root_path tmp-root
                                         :query "not-an-object"})]
            (is (= 405 (:status method-resp)))
            (is (= "method_not_allowed" (get-in method-resp [:json :error_code])))
            (is (= "client" (get-in method-resp [:json :error_category])))
            (is (= 400 (:status invalid-resp)))
            (is (= "invalid_request" (get-in invalid-resp [:json :error_code])))
            (is (= "client" (get-in invalid-resp [:json :error_category]))))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-authz-boundary-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-auth-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        server (runtime-http/start-server {:host "127.0.0.1"
                                           :port 0
                                           :api_key "secret-token"
                                           :require_tenant true})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)]
        (testing "missing api key -> 401"
          (let [resp (post-json client (str base-url "/v1/index/create") {:root_path tmp-root})]
            (is (= 401 (:status resp)))
            (is (= "unauthorized" (get-in resp [:json :error_code])))))
        (testing "api key without tenant -> 400"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root}
                                {"x-api-key" "secret-token"})]
            (is (= 400 (:status resp)))
            (is (= "invalid_request" (get-in resp [:json :error_code])))))
        (testing "api key + tenant -> 200"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root}
                                {"x-api-key" "secret-token"
                                 "x-tenant-id" "tenant-001"})]
            (is (= 200 (:status resp)))
            (is (string? (get-in resp [:json :snapshot_id]))))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-language-activation-guidance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-no-lang" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "README.md" "# none")
        server (runtime-http/start-server {:host "127.0.0.1" :port 0})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
            resp (post-json client
                            (str base-url "/v1/index/create")
                            {:root_path tmp-root})]
        (is (= 400 (:status resp)))
        (is (= "no_supported_languages_found" (get-in resp [:json :error_code])))
        (is (= "awaiting_language_selection" (get-in resp [:json :details :activation_state])))
        (is (= ["clojure" "java" "elixir" "python" "typescript" "lua"]
               (get-in resp [:json :details :supported_languages]))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-language-activation-in-progress-retry-header-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-lock" (make-array java.nio.file.attribute.FileAttribute 0)))
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
        server (runtime-http/start-server {:host "127.0.0.1" :port 0 :project_registry project-registry})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
            resp (post-json client
                            (str base-url "/v1/index/create")
                            {:root_path tmp-root})]
        (is (= 409 (:status resp)))
        (is (= "language_activation_in_progress" (get-in resp [:json :error_code])))
        (is (= ["2"] (get (:headers resp) "Retry-After")))
        (is (= "retry_same_request" (get-in resp [:json :details :recommended_action]))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-language-refresh-required-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-refresh" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "app/main.py" "def run(value):\n    return value\n")
        server (runtime-http/start-server {:host "127.0.0.1" :port 0})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
            create-resp (post-json client
                                   (str base-url "/v1/index/create")
                                   {:root_path tmp-root})
            _ts (write-file! tmp-root "src/example/main.ts"
                             "export function runTs(value: string): string {\n  return value;\n}\n")
            resolve-resp (post-json client
                                    (str base-url "/v1/retrieval/resolve-context")
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
                                             :trace {:request_id "runtime-http-refresh-001"}}})]
        (is (= 200 (:status create-resp)))
        (is (= 409 (:status resolve-resp)))
        (is (= "language_refresh_required" (get-in resolve-resp [:json :error_code])))
        (is (= ["typescript"] (get-in resolve-resp [:json :details :inactive_languages]))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-authz-policy-contract-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-policy-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        policy-path (str (io/file tmp-root "authz-policy.edn"))
        _ (write-authz-policy! policy-path
                               {:tenants {"tenant-001" {:allowed_roots [tmp-root]
                                                        :allowed_path_prefixes ["src/my/app"]}}})
        authz-check (runtime-authz/load-policy-authorizer policy-path)
        server (runtime-http/start-server {:host "127.0.0.1"
                                           :port 0
                                           :api_key "secret-token"
                                           :require_tenant true
                                           :authz_check authz-check})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
            headers {"x-api-key" "secret-token"
                     "x-tenant-id" "tenant-001"}]
        (testing "tenant with path restrictions must send explicit paths"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root}
                                headers)]
            (is (= 403 (:status resp)))
            (is (= "forbidden" (get-in resp [:json :error_code])))))

        (testing "allowed path prefix passes"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root
                                 :paths ["src/my/app/order.clj"]}
                                headers)]
            (is (= 200 (:status resp)))
            (is (string? (get-in resp [:json :snapshot_id])))))

        (testing "disallowed path prefix denied"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root
                                 :paths ["test/my/app/order_test.clj"]}
                                headers)]
            (is (= 403 (:status resp)))
            (is (= "forbidden" (get-in resp [:json :error_code])))))

        (testing "unknown tenant denied"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root
                                 :paths ["src/my/app/order.clj"]}
                                {"x-api-key" "secret-token"
                                 "x-tenant-id" "tenant-999"})]
            (is (= 403 (:status resp)))
            (is (= "forbidden" (get-in resp [:json :error_code]))))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-policy-registry-selection-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-policy-registry-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        active-policy (-> (rp/default-retrieval-policy)
                          (assoc :policy_id "heuristic_v1_http_active")
                          (assoc :version "2026-03-11")
                          (assoc-in [:thresholds :top_authority_min] 500))
        shadow-policy (-> (rp/default-retrieval-policy)
                          (assoc :policy_id "heuristic_v1_http_shadow")
                          (assoc :version "2026-03-12")
                          (assoc-in [:thresholds :top_authority_min] 500))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        server (runtime-http/start-server {:host "127.0.0.1"
                                           :port 0
                                           :policy_registry registry})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
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
                   :trace {:trace_id "03111111-1111-4111-8111-111111111111"
                           :request_id "runtime-http-policy-registry-test-001"
                           :actor_id "test_runner"}}]
        (testing "active registry policy is used when no override is passed"
          (let [resolve-resp (post-json client
                                        (str base-url "/v1/retrieval/resolve-context")
                                        {:root_path tmp-root
                                         :query query})
                detail-resp (post-json client
                                       (str base-url "/v1/retrieval/fetch-context-detail")
                                       {:root_path tmp-root
                                        :selection_id (get-in resolve-resp [:json :selection_id])
                                        :snapshot_id (get-in resolve-resp [:json :snapshot_id])})]
            (is (= 200 (:status resolve-resp)))
            (is (= "heuristic_v1_http_active"
                   (get-in detail-resp [:json :diagnostics_trace :retrieval_policy :policy_id])))
            (is (not= "top_authority"
                      (get-in detail-resp [:json :context_packet :relevant_units 0 :rank_band])))))

        (testing "selector-based override resolves from registry"
          (let [resolve-resp (post-json client
                                        (str base-url "/v1/retrieval/resolve-context")
                                        {:root_path tmp-root
                                         :query query
                                         :retrieval_policy {:policy_id "heuristic_v1_http_shadow"
                                                            :version "2026-03-12"}})
                detail-resp (post-json client
                                       (str base-url "/v1/retrieval/fetch-context-detail")
                                       {:root_path tmp-root
                                        :selection_id (get-in resolve-resp [:json :selection_id])
                                        :snapshot_id (get-in resolve-resp [:json :snapshot_id])})]
            (is (= 200 (:status resolve-resp)))
            (is (= "heuristic_v1_http_shadow"
                   (get-in detail-resp [:json :diagnostics_trace :retrieval_policy :policy_id])))
            (is (not= "top_authority"
                      (get-in detail-resp [:json :context_packet :relevant_units 0 :rank_band]))))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-staged-selection-error-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-selection-errors" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        selection-cache (atom {:max_entries 1})
        server (runtime-http/start-server {:host "127.0.0.1"
                                           :port 0
                                           :selection_cache selection-cache})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
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
                   :trace {:trace_id "07111111-1111-4111-8111-111111111111"
                           :request_id "runtime-http-selection-errors-001"
                           :actor_id "test_runner"}}
            selection-a (post-json client
                                   (str base-url "/v1/retrieval/resolve-context")
                                   {:root_path tmp-root
                                    :query query})
            mismatch-resp (post-json client
                                     (str base-url "/v1/retrieval/fetch-context-detail")
                                     {:root_path tmp-root
                                      :selection_id (get-in selection-a [:json :selection_id])
                                      :snapshot_id "wrong-snapshot"})
            _selection-b (post-json client
                                    (str base-url "/v1/retrieval/resolve-context")
                                    {:root_path tmp-root
                                     :query query})
            evicted-resp (post-json client
                                    (str base-url "/v1/retrieval/fetch-context-detail")
                                    {:root_path tmp-root
                                     :selection_id (get-in selection-a [:json :selection_id])
                                     :snapshot_id (get-in selection-a [:json :snapshot_id])})]
        (is (= 409 (:status mismatch-resp)))
        (is (= "snapshot_mismatch" (get-in mismatch-resp [:json :error_code])))
        (is (= 410 (:status evicted-resp)))
        (is (= "selection_evicted" (get-in evicted-resp [:json :error_code]))))
      (finally
        (.stop server 0)))))

(deftest runtime-http-tenant-trace-correlation-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-correlation-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        server (runtime-http/start-server {:host "127.0.0.1"
                                           :port 0
                                           :api_key "secret-token"
                                           :require_tenant true
                                           :usage_metrics sink})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
            create-resp (post-json client
                                   (str base-url "/v1/index/create")
                                   {:root_path tmp-root}
                                   {"x-api-key" "secret-token"
                                    "x-tenant-id" "tenant-001"
                                    "x-trace-id" "04111111-1111-4111-8111-111111111111"
                                    "x-request-id" "runtime-http-create-trace-001"
                                    "x-session-id" "http-session-001"
                                    "x-task-id" "http-task-001"
                                    "x-actor-id" "http-edge-tester"})
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
                   :trace {:trace_id "05111111-1111-4111-8111-111111111111"
                           :request_id "runtime-http-resolve-trace-001"
                           :session_id "http-session-002"
                           :task_id "http-task-002"
                           :actor_id "http-query-runner"}}
            resolve-resp (post-json client
                                    (str base-url "/v1/retrieval/resolve-context")
                                    {:root_path tmp-root
                                     :query query}
                                    {"x-api-key" "secret-token"
                                     "x-tenant-id" "tenant-001"
                                     "x-trace-id" "04111111-1111-4111-8111-111111111111"
                                     "x-request-id" "runtime-http-header-fallback-001"
                                     "x-session-id" "http-session-header"
                                     "x-task-id" "http-task-header"
                                     "x-actor-id" "http-header-actor"})
            events (usage/emitted-events sink)
            create-event (first (filter #(= "create_index" (:operation %)) events))
            resolve-event (first (filter #(= "resolve_context" (:operation %)) events))]
        (testing "response headers echo correlation markers"
          (is (= "runtime-http-create-trace-001" (first (get-in create-resp [:headers "x-sci-request-id"]))))
          (is (= "tenant-001" (first (get-in resolve-resp [:headers "x-sci-tenant-id"]))))
          (is (= "runtime-http-resolve-trace-001" (first (get-in resolve-resp [:headers "x-sci-request-id"])))))
        (testing "usage events retain tenant and trace consistency"
          (is (= "http" (:surface create-event)))
          (is (= "tenant-001" (:tenant_id create-event)))
          (is (= "04111111-1111-4111-8111-111111111111" (:trace_id create-event)))
          (is (= "http-session-001" (:session_id create-event)))
          (is (= "http-task-001" (:task_id create-event)))
          (is (= "http-edge-tester" (:actor_id create-event)))
          (is (= "http" (:surface resolve-event)))
          (is (= "tenant-001" (:tenant_id resolve-event)))
          (is (= "05111111-1111-4111-8111-111111111111" (:trace_id resolve-event)))
          (is (= "runtime-http-resolve-trace-001" (:request_id resolve-event)))
          (is (= "http-session-002" (:session_id resolve-event)))
          (is (= "http-task-002" (:task_id resolve-event)))
          (is (= "http-query-runner" (:actor_id resolve-event)))))
      (finally
        (.stop server 0)))))
(deftest runtime-http-unsupported-api-version-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-http-unsupported-api-version" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-http-sample-repo! tmp-root)
        server (runtime-http/start-server {:host "127.0.0.1" :port 0})]
    (try
      (let [port (-> server .getAddress .getPort)
            base-url (str "http://127.0.0.1:" port)
            client (HttpClient/newHttpClient)
            _health (wait-health! client base-url)
            resp (post-json client
                            (str base-url "/v1/retrieval/resolve-context")
                            {:root_path tmp-root
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
                                     :trace {:trace_id "06111111-1111-4111-8111-111111111111"
                                             :request_id "runtime-http-unsupported-api-version-001"
                                             :actor_id "test_runner"}}})]
        (is (= 400 (:status resp)))
        (is (= "unsupported_api_version" (get-in resp [:json :error_code])))
        (is (= "2.0" (get-in resp [:json :details :provided_api_version])))
        (is (= ["1.0"] (get-in resp [:json :details :supported_api_versions]))))
      (finally
        (.stop server 0)))))
