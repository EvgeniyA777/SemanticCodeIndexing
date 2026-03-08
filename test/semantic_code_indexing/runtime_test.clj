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
               "package com.acme;\n\nimport java.util.Objects;\n\npublic class CheckoutService {\n  public String processOrder(String id) {\n    return normalize(id);\n  }\n\n  private String normalize(String id) {\n    return Objects.requireNonNull(id).trim();\n  }\n}\n")
  (write-file! root "lib/my_app/order.ex"
               "defmodule MyApp.Order do\n  alias MyApp.Validator\n\n  def process_order(ctx, order) do\n    Validator.validate(order)\n    {:ok, order}\n  end\n\n  defp to_status(order) do\n    Map.get(order, :status, :new)\n  end\nend\n")
  (write-file! root "app/orders.py"
               "from app.validators import validate_order\n\nclass OrderService:\n    def process_order(self, order):\n        return validate_order(order)\n\n\ndef validate_local(order):\n    return bool(order)\n"))

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

(def sample-query-elixir
  {:schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate Elixir order process function."}
   :targets {:symbols ["MyApp.Order/process_order"]
             :paths ["lib/my_app/order.ex"]}
   :constraints {:token_budget 1200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:prefer_definitions_over_callers true}
   :options {:include_tests false
             :include_impact_hints true
             :allow_raw_code_escalation false
             :favor_compact_packet true
             :favor_higher_recall false}
   :trace {:trace_id "22222222-2222-4222-8222-222222222222"
           :request_id "runtime-test-ex-001"
           :actor_id "test_runner"}})

(def sample-query-python
  {:schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate Python process_order function."}
   :targets {:symbols ["app.orders.OrderService/process_order"]
             :paths ["app/orders.py"]}
   :constraints {:token_budget 1200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:prefer_definitions_over_callers true}
   :options {:include_tests false
             :include_impact_hints true
             :allow_raw_code_escalation false
             :favor_compact_packet true
             :favor_higher_recall false}
   :trace {:trace_id "33333333-3333-4333-8333-333333333333"
           :request_id "runtime-test-py-001"
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
      (is (some #(= "elixir" (:language %)) (vals (:files index))))
      (is (some #(= "python" (:language %)) (vals (:files index))))
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

(deftest elixir-and-python-targeted-retrieval-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-lang-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        ex-result (sci/resolve-context index sample-query-elixir)
        py-result (sci/resolve-context index sample-query-python)]
    (testing "elixir symbol can be localized"
      (is (some #(= "MyApp.Order/process_order" (:symbol %))
                (get-in ex-result [:context_packet :relevant_units]))))
    (testing "python symbol can be localized"
      (is (some #(= "app.orders.OrderService/process_order" (:symbol %))
                (get-in py-result [:context_packet :relevant_units]))))))

(deftest in-memory-storage-roundtrip-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-storage-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index-a (sci/create-index {:root_path tmp-root :storage storage})
        ;; load_latest should return previously persisted snapshot
        index-b (sci/create-index {:root_path tmp-root :storage storage :load_latest true})]
    (is (= (:snapshot_id index-a) (:snapshot_id index-b)))
    (is (= (count (:units index-a)) (count (:units index-b))))))

(deftest postgres-storage-roundtrip-test
  (if-let [jdbc-url (System/getenv "SCI_TEST_POSTGRES_URL")]
    (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-pg-storage-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          _ (create-sample-repo! tmp-root)
          storage (sci/postgres-storage {:jdbc-url jdbc-url})
          index-a (sci/create-index {:root_path tmp-root :storage storage})
          index-b (sci/create-index {:root_path tmp-root :storage storage :load_latest true})]
      (is (= (:snapshot_id index-a) (:snapshot_id index-b)))
      (is (= (count (:units index-a)) (count (:units index-b)))))
    (is true "SCI_TEST_POSTGRES_URL is not set; skipping postgres storage smoke test.")))
