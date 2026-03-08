(ns semantic-code-indexing.runtime-http-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semantic-code-indexing.runtime.authz :as runtime-authz]
            [semantic-code-indexing.runtime.http :as runtime-http])
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
            (is (pos-int? (get-in resp [:json :file_count])))
            (is (pos-int? (get-in resp [:json :unit_count])))))

        (testing "resolve-context endpoint"
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
                       :trace {:trace_id "01111111-1111-4111-8111-111111111111"
                               :request_id "runtime-http-test-001"
                               :actor_id "test_runner"}}
                resp (post-json client
                                (str base-url "/v1/retrieval/resolve-context")
                                {:root_path tmp-root
                                 :query query})]
            (is (= 200 (:status resp)))
            (is (map? (get-in resp [:json :context_packet])))
            (is (map? (get-in resp [:json :diagnostics_trace])))
            (is (map? (get-in resp [:json :guardrail_assessment])))
            (is (vector? (get-in resp [:json :stage_events])))
            (is (some #(= "my.app.order/process-order" (:symbol %))
                      (get-in resp [:json :context_packet :relevant_units])))))

        (testing "method and payload validation"
          (let [method-resp (http-request client "GET" (str base-url "/v1/index/create") nil)
                invalid-resp (post-json client
                                        (str base-url "/v1/retrieval/resolve-context")
                                        {:root_path tmp-root
                                         :query "not-an-object"})]
            (is (= 405 (:status method-resp)))
            (is (= 400 (:status invalid-resp))))))
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
            (is (= 401 (:status resp)))))
        (testing "api key without tenant -> 400"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root}
                                {"x-api-key" "secret-token"})]
            (is (= 400 (:status resp)))))
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
            (is (= 403 (:status resp)))))

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
            (is (= 403 (:status resp)))))

        (testing "unknown tenant denied"
          (let [resp (post-json client
                                (str base-url "/v1/index/create")
                                {:root_path tmp-root
                                 :paths ["src/my/app/order.clj"]}
                                {"x-api-key" "secret-token"
                                 "x-tenant-id" "tenant-999"})]
            (is (= 403 (:status resp))))))
      (finally
        (.stop server 0)))))
