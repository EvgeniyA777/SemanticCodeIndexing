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
               "package com.acme;\n\nimport java.util.Objects;\n\npublic class CheckoutService {\n  public String processOrder(String id) {\n    return normalize(id, true);\n  }\n\n  private String normalize(String id) {\n    return normalize(id, false);\n  }\n\n  private String normalize(String id, boolean strict) {\n    String base = Objects.requireNonNull(id).trim();\n    return strict ? base : base.toLowerCase();\n  }\n}\n")
  (write-file! root "lib/my_app/order.ex"
               "defmodule MyApp.Order do\n  alias MyApp.{Validator, Payments.Adapter}\n  alias Adapter.Client, as: BillingClient\n  alias Validator, as: V\n\n  def process_order(ctx, order) do\n    V.validate(order)\n    Adapter.charge(order)\n    BillingClient.charge(order)\n    {:ok, order}\n  end\n\n  defp to_status(order) do\n    Map.get(order, :status, :new)\n  end\n\n  defdelegate charge(order), to: MyApp.Validator\nend\n")
  (write-file! root "lib/my_app/validator.ex"
               "defmodule MyApp.Validator do\n  def validate(order) do\n    {:ok, order}\n  end\n\n  def charge(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "lib/my_app/payments/adapter.ex"
               "defmodule MyApp.Payments.Adapter do\n  def charge(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "lib/my_app/payments/adapter_client.ex"
               "defmodule MyApp.Payments.Adapter.Client do\n  def charge(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "app/orders.py"
               "from app.validators import validate_order\n\nclass OrderService:\n    def process_order(self, order):\n        return validate_order(order)\n\n\ndef validate_local(order):\n    return bool(order)\n")
  (write-file! root "src/example/normalize.ts"
               "export function normalizeOrder(orderId: string): string {\n  return (orderId || \"\").trim().toLowerCase();\n}\n")
  (write-file! root "src/example/main.ts"
               "import { normalizeOrder } from \"./normalize\";\n\nexport function processMain(orderId: string): string {\n  return normalizeOrder(orderId);\n}\n\nexport class MainService {\n  processMain(orderId: string): string {\n    return processMain(orderId);\n  }\n}\n")
  (write-file! root "test/example/main_test.ts"
               "import { processMain } from \"../../src/example/main\";\n\nexport function testProcessMain(): string {\n  return processMain(\"A-1\");\n}\n"))

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

(def sample-query-typescript
  {:schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate TypeScript processMain function."}
   :targets {:symbols ["src.example.main/processMain"]
             :paths ["src/example/main.ts"]}
   :constraints {:token_budget 1200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:prefer_definitions_over_callers true}
   :options {:include_tests true
             :include_impact_hints true
             :allow_raw_code_escalation false
             :favor_compact_packet true
             :favor_higher_recall false}
   :trace {:trace_id "44444444-4444-4444-8444-444444444444"
           :request_id "runtime-test-ts-001"
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
      (is (some #(= "typescript" (:language %)) (vals (:files index))))
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
      (is (= "high" (get-in packet [:confidence :level]))))
    (testing "retrieval emits policy and capability metadata"
      (is (= "heuristic_v1" (get-in packet [:retrieval_policy :policy_id])))
      (is (= "heuristic_v1" (get-in diagnostics [:retrieval_policy :policy_id])))
      (is (= "full" (get-in packet [:capabilities :coverage_level])))
      (is (some #{"clojure"} (get-in packet [:capabilities :selected_languages])))
      (is (string? (get-in packet [:capabilities :index_snapshot_id]))))))

(deftest retrieval-policy-can-change-ranking-band-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-policy-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        baseline (sci/resolve-context index sample-query)
        strict-policy {:policy_id "heuristic_v1_strict_top"
                       :version "2026-03-10"
                       :thresholds {:top_authority_min 500}}
        strict-result (sci/resolve-context index sample-query {:retrieval_policy strict-policy})]
    (is (= "top_authority" (get-in baseline [:context_packet :relevant_units 0 :rank_band])))
    (is (not= "top_authority" (get-in strict-result [:context_packet :relevant_units 0 :rank_band])))
    (is (= "heuristic_v1_strict_top" (get-in strict-result [:diagnostics_trace :retrieval_policy :policy_id])))))

(deftest elixir-and-python-targeted-retrieval-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-lang-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        ex-result (sci/resolve-context index sample-query-elixir)
        py-result (sci/resolve-context index sample-query-python)
        ts-result (sci/resolve-context index sample-query-typescript)]
    (testing "elixir symbol can be localized"
      (is (some #(= "MyApp.Order/process_order" (:symbol %))
                (get-in ex-result [:context_packet :relevant_units]))))
    (testing "python symbol can be localized"
      (is (some #(= "app.orders.OrderService/process_order" (:symbol %))
                (get-in py-result [:context_packet :relevant_units]))))
    (testing "typescript symbol can be localized"
      (is (some #(= "src.example.main/processMain" (:symbol %))
                (get-in ts-result [:context_packet :relevant_units]))))))

(deftest elixir-alias-aware-call-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-alias-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-units (sci/query-units storage tmp-root {:module "MyApp.Validator" :limit 20})
        validate-unit-id (some->> validator-units (filter #(= "MyApp.Validator/validate" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "MyApp.Order/process_order" (:symbol %)) callers))))

(deftest elixir-brace-and-nested-alias-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-nested-alias-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        adapter-units (sci/query-units storage tmp-root {:module "MyApp.Payments.Adapter" :limit 20})
        client-units (sci/query-units storage tmp-root {:module "MyApp.Payments.Adapter.Client" :limit 20})
        adapter-charge-id (some->> adapter-units (filter #(= "MyApp.Payments.Adapter/charge" (:symbol %))) first :unit_id)
        client-charge-id (some->> client-units (filter #(= "MyApp.Payments.Adapter.Client/charge" (:symbol %))) first :unit_id)
        adapter-callers (sci/query-callers storage tmp-root adapter-charge-id {:limit 20})
        client-callers (sci/query-callers storage tmp-root client-charge-id {:limit 20})]
    (is adapter-charge-id)
    (is client-charge-id)
    (is (some #(= "MyApp.Order/process_order" (:symbol %)) adapter-callers))
    (is (some #(= "MyApp.Order/process_order" (:symbol %)) client-callers))))

(deftest java-overload-unit-identity-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-overload-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        java-units (->> (:unit_order index)
                        (map #(get (:units index) %))
                        (filter #(= "src/com/acme/CheckoutService.java" (:path %)))
                        (filter #(= "com.acme.CheckoutService#normalize" (:symbol %)))
                        vec)
        unit-ids (mapv :unit_id java-units)]
    (is (= 2 (count java-units)))
    (is (= 2 (count (distinct unit-ids))))
    (is (every? #(re-find #"\$arity[0-9]+" %) unit-ids))))

(deftest typescript-call-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-call-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        normalize-units (sci/query-units storage tmp-root {:module "src.example.normalize" :limit 20})
        normalize-id (some->> normalize-units
                              (filter #(= "src.example.normalize/normalizeOrder" (:symbol %)))
                              first
                              :unit_id)
        callers (sci/query-callers storage tmp-root normalize-id {:limit 20})]
    (is normalize-id)
    (is (some #(= "src.example.main/processMain" (:symbol %)) callers))))

(deftest in-memory-storage-roundtrip-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-storage-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index-a (sci/create-index {:root_path tmp-root :storage storage})
        ;; load_latest should return previously persisted snapshot
        index-b (sci/create-index {:root_path tmp-root :storage storage :load_latest true})]
    (is (= (:snapshot_id index-a) (:snapshot_id index-b)))
    (is (= (count (:units index-a)) (count (:units index-b))))))

(deftest storage-query-api-regression-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-storage-query-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        process-unit-id (some->> order-units (filter #(= "my.app.order/process-order" (:symbol %))) first :unit_id)
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})
        callees (sci/query-callees storage tmp-root process-unit-id {:limit 20})]
    (is (seq order-units))
    (is process-unit-id)
    (is validate-unit-id)
    (is (some #(= "my.app.order/process-order" (:symbol %)) callers))
    (is (some #(= "my.app.order/validate-order" (:symbol %)) callees))))

(deftest tree-sitter-parser-path-test
  (let [clj-grammar (System/getenv "SCI_TREE_SITTER_CLOJURE_GRAMMAR_PATH")
        java-grammar (System/getenv "SCI_TREE_SITTER_JAVA_GRAMMAR_PATH")
        ts-grammar (System/getenv "SCI_TREE_SITTER_TYPESCRIPT_GRAMMAR_PATH")]
    (if (and (seq clj-grammar) (seq java-grammar) (seq ts-grammar))
      (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-tree-sitter-test" (make-array java.nio.file.attribute.FileAttribute 0)))
            _ (create-sample-repo! tmp-root)
            index (sci/create-index {:root_path tmp-root
                                     :parser_opts {:clojure_engine :tree-sitter
                                                   :java_engine :tree-sitter
                                                   :typescript_engine :tree-sitter
                                                   :tree_sitter_enabled true
                                                   :tree_sitter_grammars {:clojure clj-grammar
                                                                          :java java-grammar
                                                                          :typescript ts-grammar}}})
            clj-diags (get-in index [:files "src/my/app/order.clj" :diagnostics])
            java-diags (get-in index [:files "src/com/acme/CheckoutService.java" :diagnostics])
            ts-diags (get-in index [:files "src/example/main.ts" :diagnostics])]
        (is (some #(= "tree_sitter_active" (:code %)) clj-diags))
        (is (some #(= "tree_sitter_active" (:code %)) java-diags))
        (is (some #(= "tree_sitter_active" (:code %)) ts-diags)))
      (is true "Tree-sitter grammar paths are not configured for Clojure/Java/TypeScript; skipping tree-sitter parser test."))))

(deftest postgres-storage-roundtrip-test
  (if-let [jdbc-url (System/getenv "SCI_TEST_POSTGRES_URL")]
    (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-pg-storage-test" (make-array java.nio.file.attribute.FileAttribute 0)))
          _ (create-sample-repo! tmp-root)
          storage (sci/postgres-storage {:jdbc-url jdbc-url})
          index-a (sci/create-index {:root_path tmp-root :storage storage})
          index-b (sci/create-index {:root_path tmp-root :storage storage :load_latest true})
          order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
          process-unit-id (some->> order-units (filter #(= "my.app.order/process-order" (:symbol %))) first :unit_id)
          validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
          callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})
          callees (sci/query-callees storage tmp-root process-unit-id {:limit 20})]
      (is (= (:snapshot_id index-a) (:snapshot_id index-b)))
      (is (= (count (:units index-a)) (count (:units index-b))))
      (is (seq order-units))
      (is (some #(= "my.app.order/process-order" (:symbol %)) callers))
      (is (some #(= "my.app.order/validate-order" (:symbol %)) callees)))
    (is true "SCI_TEST_POSTGRES_URL is not set; skipping postgres storage smoke test.")))
