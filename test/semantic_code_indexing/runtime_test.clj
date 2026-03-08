(ns semantic-code-indexing.runtime-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [semantic-code-indexing.contracts.schemas :as contracts]
            [semantic-code-indexing.core :as sci]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order\n  (:require [clojure.string :as str]))\n\n(defn process-order [ctx order]\n  (validate-order order)\n  (str/join \"-\" [\"ok\" (:id order)]))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n")
  (write-file! root "src/com/acme/CheckoutService.java"
               "package com.acme;\n\nimport java.util.Objects;\n\npublic class CheckoutService {\n  public String processOrder(String id) {\n    return normalize(id);\n  }\n\n  private String normalize(String id) {\n    return Objects.requireNonNull(id).trim();\n  }\n}\n"))

(def sample-query
  {:schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate process-order authority and close tests."}
   :targets {:symbols ["my.app.order/process-order"]
             :paths ["src/my/app/order.clj"]}
   :constraints {:token_budget 1500
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:focus_on_tests true
           :prefer_definitions_over_callers true}
   :options {:include_tests true
             :include_impact_hints true
             :allow_raw_code_escalation false
             :favor_compact_packet true
             :favor_higher_recall false}
   :trace {:trace_id "11111111-1111-4111-8111-111111111111"
           :request_id "runtime-test-001"
           :actor_id "test_runner"}})

(deftest end-to-end-resolve-context-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context index sample-query)
        packet (:context_packet result)
        diagnostics (:diagnostics_trace result)
        guardrails (:guardrail_assessment result)]
    (testing "index has both languages"
      (is (seq (:files index)))
      (is (some #(= "clojure" (:language %)) (vals (:files index))))
      (is (some #(= "java" (:language %)) (vals (:files index))))
      (is (= "full" (get-in index [:files "src/my/app/order.clj" :parser_mode]))))
    (testing "context packet validates against contract"
      (is (nil? (m/explain (:example/context-packet contracts/contracts) packet))))
    (testing "diagnostics validates against contract"
      (is (nil? (m/explain (:example/diagnostics-trace contracts/contracts) diagnostics))))
    (testing "guardrails validates against contract"
      (is (nil? (m/explain (:example/guardrail-assessment contracts/contracts) guardrails))))
    (testing "retrieval actually localizes target"
      (is (seq (:relevant_units packet)))
      (is (some #(= "my.app.order/process-order" (:symbol %)) (:relevant_units packet)))
      (is (= "high" (get-in packet [:confidence :level]))))))

(deftest in-memory-storage-roundtrip-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-storage-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index-a (sci/create-index {:root_path tmp-root :storage storage})
        ;; load_latest should return previously persisted snapshot
        index-b (sci/create-index {:root_path tmp-root :storage storage :load_latest true})]
    (is (= (:snapshot_id index-a) (:snapshot_id index-b)))
    (is (= (count (:units index-a)) (count (:units index-b))))))
