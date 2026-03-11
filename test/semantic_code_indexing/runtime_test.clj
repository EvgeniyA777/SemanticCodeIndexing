(ns semantic-code-indexing.runtime-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [semantic-code-indexing.contracts.schemas :as contracts]
            [semantic-code-indexing.core :as sci]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order\n  (:require [clojure.string :as str]))\n\n(defn process-order [ctx order]\n  (validate-order order)\n  (str/join \"-\" [\"ok\" (:id order)]))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n")
  (write-file! root "src/my/app/macros.clj"
               "(ns my.app.macros\n  (:require [my.app.order :as order]))\n\n(comment\n  (defn hidden-helper [order]\n    (:id order)))\n\n(defn macro-helper [order]\n  order)\n\n(defmacro with-order [order & body]\n  `(let [current-order# ~order]\n     ~@body))\n\n(defmacro with-validated-order [order & body]\n  (macro-helper order)\n  `(let [validated-order# (order/validate-order ~order)]\n     ~@body))\n\n(defmacro with-prepared-order [order & body]\n  `(with-validated-order ~order\n     ~@body))\n\n(defmacro with-listed-validation [order & body]\n  (list 'do\n        (list 'order/validate-order order)\n        (cons 'do body)))\n\n(defmacro with-listed-prepared [order & body]\n  (list 'with-listed-validation order\n        (cons 'do body)))\n\n(defmacro with-composed-validation [order & body]\n  (concat (list 'do)\n          (list (list 'order/validate-order order))\n          body))\n\n(defmacro with-branching-validation [order mode & body]\n  (if (= mode :apply)\n    (apply list 'do\n           (concat (list (list 'order/validate-order order))\n                   body))\n    (concat (list 'do)\n            (into []\n                  (concat [(list 'order/validate-order order)]\n                          body)))))\n\n(defmacro with-letfn-validation [order & body]\n  (letfn [(emit-validation []\n            (list 'order/validate-order order))]\n    (apply list 'do\n           (concat [(emit-validation)]\n                   body))))\n\n(defmacro with-side-effect-helper [order & body]\n  (letfn [(emit-validation []\n            (list 'order/validate-order order))]\n    (emit-validation)\n    `(do ~@body)))\n\n(defn visible-helper [order]\n  (with-order order\n    (:id order)))\n")
  (write-file! root "src/my/app/workflow.clj"
               "(ns my.app.workflow\n  (:require [my.app.macros :refer [with-validated-order with-prepared-order with-listed-prepared with-composed-validation with-branching-validation with-letfn-validation with-side-effect-helper]]))\n\n(defn prepare-order [order]\n  (with-validated-order order\n    {:prepared true}))\n\n(defn prepare-prevalidated-order [order]\n  (with-prepared-order order\n    {:prepared true :mode :nested}))\n\n(defn prepare-listed-order [order]\n  (with-listed-prepared order\n    {:prepared true :mode :list-generated}))\n\n(defn prepare-composed-order [order]\n  (with-composed-validation order\n    {:prepared true :mode :concat-generated}))\n\n(defn prepare-branching-order [order]\n  (with-branching-validation order :apply\n    {:prepared true :mode :branch-generated}))\n\n(defn prepare-letfn-order [order]\n  (with-letfn-validation order\n    {:prepared true :mode :letfn-generated}))\n\n(defn prepare-side-effect-order [order]\n  (with-side-effect-helper order\n    {:prepared true :mode :helper-side-effect}))\n")
  (write-file! root "src/my/app/shipping.clj"
               "(ns my.app.shipping)\n\n(defmulti route-order (fn [mode payload] mode))\n\n(defn pickup-stop [payload]\n  (:pickup payload))\n\n(defn delivery-stop [payload]\n  (:delivery payload))\n\n(defmethod route-order :pickup [_ payload]\n  (pickup-stop payload))\n\n(defmethod route-order :delivery [_ payload]\n  (delivery-stop payload))\n\n(defn plan-route [mode payload]\n  (route-order mode payload))\n")
  (write-file! root "src/com/acme/CheckoutService.java"
               "package com.acme;\n\nimport java.util.Objects;\nimport static com.acme.IdNormalizer.normalizeImported;\n\npublic class CheckoutService {\n  public String processOrder(String id) {\n    return normalize(id, true);\n  }\n\n  public String processImported(String id) {\n    return normalizeImported(id);\n  }\n\n  public String processImportedStrict(String id) {\n    return normalizeImported(id, true);\n  }\n\n  private String normalize(String id) {\n    return normalize(id, false);\n  }\n\n  private String normalize(String id, boolean strict) {\n    String base = Objects.requireNonNull(id).trim();\n    return strict ? base : base.toLowerCase();\n  }\n}\n")
  (write-file! root "src/com/acme/IdNormalizer.java"
               "package com.acme;\n\npublic class IdNormalizer {\n  public static String normalizeImported(String id) {\n    return id.trim();\n  }\n\n  public static String normalizeImported(String id, boolean strict) {\n    String base = id.trim();\n    return strict ? base : base.toLowerCase();\n  }\n}\n")
  (write-file! root "lib/my_app/order.ex"
               "defmodule MyApp.Order do\n  import MyApp.Validator\n  alias MyApp.{Validator, Payments.Adapter}\n  alias Adapter.Client, as: BillingClient\n  alias Validator, as: V\n\n  def process_order(ctx, order) do\n    validate(order)\n    Adapter.charge(order)\n    BillingClient.charge(order)\n    {:ok, order}\n  end\n\n  def process_with_alias(ctx, order) do\n    V.validate(order)\n    {:ok, order}\n  end\n\n  defp to_status(order) do\n    Map.get(order, :status, :new)\n  end\n\n  defdelegate charge(order), to: MyApp.Validator\nend\n")
  (write-file! root "lib/my_app/validator.ex"
               "defmodule MyApp.Validator do\n  def validate(order) do\n    {:ok, order}\n  end\n\n  def charge(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "lib/my_app/payments/adapter.ex"
               "defmodule MyApp.Payments.Adapter do\n  def charge(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "lib/my_app/payments/adapter_client.ex"
               "defmodule MyApp.Payments.Adapter.Client do\n  def charge(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "lib/my_app/formatter.ex"
               "defmodule MyApp.Formatter do\n  defmacro __using__(_opts) do\n    quote do\n      import MyApp.Formatter\n    end\n  end\n\n  def normalize(order) do\n    {:ok, order}\n  end\nend\n")
  (write-file! root "lib/my_app/use_client.ex"
               "defmodule MyApp.UseClient do\n  use MyApp.Formatter\n\n  def process_used(order) do\n    normalize(order)\n  end\nend\n")
  (write-file! root "lib/my_app/overloads.ex"
               "defmodule MyApp.Overloads do\n  def normalize(order) do\n    normalize(order, :default)\n  end\n\n  def normalize(order, mode) do\n    {order, mode}\n  end\n\n  def call_one(order) do\n    normalize(order)\n  end\n\n  def call_two(order) do\n    normalize(order, :strict)\n  end\nend\n")
  (write-file! root "test/my_app/order_test.exs"
               "defmodule MyApp.OrderTest do\n  use ExUnit.Case\n  alias MyApp.Order\n\n  test \"process order stays linked to source module\" do\n    assert {:ok, %{id: 1}} = {:ok, Order.process_order(%{}, %{id: 1})}\n  end\nend\n")
  (write-file! root "app/orders.py"
               "from app.validators import validate_order\nimport app.validators as validators\n\nclass OrderService:\n    def process_order(self, order):\n        return validate_order(order)\n\n    def process_alias(self, order):\n        return validators.validate_order(order)\n\n    def process_local(self, order):\n        return self.validate_local(order)\n\n    def validate_local(self, order):\n        return bool(order)\n\n\ndef validate_local(order):\n    return bool(order)\n")
  (write-file! root "app/validators.py"
               "def validate_order(order):\n    return bool(order and order.get(\"id\"))\n")
  (write-file! root "app/orders_test.py"
               "from app.orders import OrderService\n\n\ndef test_process_order():\n    service = OrderService()\n    assert service.process_order({\"id\": 1})\n")
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
      (is (= "high" (get-in packet [:capabilities :selected_language_strengths "clojure"])))
      (is (= "high" (get-in packet [:capabilities :confidence_ceiling])))
      (is (string? (get-in packet [:capabilities :index_snapshot_id]))))))

(deftest clojure-related-tests-link-via-imported-test-namespace-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-clj-related-tests" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context index sample-query)
        related-tests (get-in result [:context_packet :impact_hints :related_tests])]
    (is (some #{"test/my/app/order_test.clj"} related-tests))))

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

(deftest retrieval-policy-can-resolve-active-registry-policy-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-policy-registry-active-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        strict-active (-> (rp/default-retrieval-policy)
                          (assoc :policy_id "heuristic_v1_active_strict")
                          (assoc :version "2026-03-11")
                          (assoc-in [:thresholds :top_authority_min] 500))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry strict-active {:state "active"})]}
        index (sci/create-index {:root_path tmp-root
                                 :policy_registry registry})
        result (sci/resolve-context index sample-query)]
    (is (not= "top_authority" (get-in result [:context_packet :relevant_units 0 :rank_band])))
    (is (= "heuristic_v1_active_strict"
           (get-in result [:diagnostics_trace :retrieval_policy :policy_id])))))

(deftest retrieval-policy-can-resolve-summary-from-registry-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-policy-registry-summary-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        strict-shadow (-> (rp/default-retrieval-policy)
                          (assoc :policy_id "heuristic_v1_shadow_strict")
                          (assoc :version "2026-03-12")
                          (assoc-in [:thresholds :top_authority_min] 500))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry (rp/default-retrieval-policy) {:state "active"})
                             (rp/registry-entry strict-shadow {:state "shadow"})]}
        index (sci/create-index {:root_path tmp-root
                                 :policy_registry registry})
        result (sci/resolve-context index
                                    sample-query
                                    {:retrieval_policy {:policy_id "heuristic_v1_shadow_strict"
                                                        :version "2026-03-12"}})]
    (is (not= "top_authority" (get-in result [:context_packet :relevant_units 0 :rank_band])))
    (is (= "heuristic_v1_shadow_strict"
           (get-in result [:diagnostics_trace :retrieval_policy :policy_id])))))

(deftest elixir-and-python-targeted-retrieval-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-lang-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        ex-result (sci/resolve-context index sample-query-elixir)
        py-result (sci/resolve-context index sample-query-python)
        ts-result (sci/resolve-context index sample-query-typescript)]
    (testing "elixir symbol can be localized"
      (is (some #(= "MyApp.Order/process_order" (:symbol %))
                (get-in ex-result [:context_packet :relevant_units])))
      (is (= "medium" (get-in ex-result [:context_packet :capabilities :confidence_ceiling])))
      (is (= "medium" (get-in ex-result [:context_packet :confidence :level]))))
    (testing "python symbol can be localized"
      (is (some #(= "app.orders.OrderService/process_order" (:symbol %))
                (get-in py-result [:context_packet :relevant_units])))
      (is (= "medium" (get-in py-result [:context_packet :capabilities :confidence_ceiling])))
      (is (= "medium" (get-in py-result [:context_packet :confidence :level]))))
    (testing "typescript symbol can be localized"
      (is (some #(= "src.example.main/processMain" (:symbol %))
                (get-in ts-result [:context_packet :relevant_units])))
      (is (= "low" (get-in ts-result [:context_packet :capabilities :confidence_ceiling])))
      (is (= "low" (get-in ts-result [:context_packet :confidence :level])))
      (is (some #(= "capability_ceiling" (:code %))
                (get-in ts-result [:context_packet :confidence :warnings]))))))

(deftest elixir-alias-aware-call-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-alias-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-units (sci/query-units storage tmp-root {:module "MyApp.Validator" :limit 20})
        validate-unit-id (some->> validator-units (filter #(= "MyApp.Validator/validate" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "MyApp.Order/process_order" (:symbol %)) callers))
    (is (some #(= "MyApp.Order/process_with_alias" (:symbol %)) callers))))

(deftest elixir-related-tests-link-via-exunit-module-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-related-tests" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context index sample-query-elixir)
        related-tests (get-in result [:context_packet :impact_hints :related_tests])]
    (is (some #{"test/my_app/order_test.exs"} related-tests))))

(deftest elixir-defdelegate-links-to-target-module-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-defdelegate-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-units (sci/query-units storage tmp-root {:module "MyApp.Validator" :limit 20})
        charge-unit-id (some->> validator-units (filter #(= "MyApp.Validator/charge" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root charge-unit-id {:limit 20})]
    (is charge-unit-id)
    (is (some #(= "MyApp.Order/charge" (:symbol %)) callers))))

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

(deftest elixir-use-directive-expands-unqualified-calls-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-use-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        formatter-units (sci/query-units storage tmp-root {:module "MyApp.Formatter" :limit 20})
        normalize-id (some->> formatter-units
                              (filter #(= "MyApp.Formatter/normalize" (:symbol %)))
                              first
                              :unit_id)
        callers (sci/query-callers storage tmp-root normalize-id {:limit 20})]
    (is normalize-id)
    (is (some #(= "MyApp.UseClient/process_used" (:symbol %)) callers))))

(deftest elixir-arity-aware-caller-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-arity-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        overload-units (sci/query-units storage tmp-root {:module "MyApp.Overloads" :limit 20})
        normalize-1 (some #(when (and (= "MyApp.Overloads/normalize" (:symbol %))
                                      (= 1 (:method_arity %)))
                             %)
                          overload-units)
        normalize-2 (some #(when (and (= "MyApp.Overloads/normalize" (:symbol %))
                                      (= 2 (:method_arity %)))
                             %)
                          overload-units)
        callers-1 (sci/query-callers storage tmp-root (:unit_id normalize-1) {:limit 20})
        callers-2 (sci/query-callers storage tmp-root (:unit_id normalize-2) {:limit 20})]
    (is normalize-1)
    (is normalize-2)
    (is (some #(= "MyApp.Overloads/call_one" (:symbol %)) callers-1))
    (is (not-any? #(= "MyApp.Overloads/call_two" (:symbol %)) callers-1))
    (is (some #(= "MyApp.Overloads/call_two" (:symbol %)) callers-2))
    (is (some #(= "MyApp.Overloads/normalize" (:symbol %)) callers-2))))

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

(deftest java-overload-caller-resolution-prefers-matching-arity-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-overload-callers-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        checkout-units (sci/query-units storage tmp-root {:module "com.acme.CheckoutService" :limit 20})
        normalize-1 (some #(when (and (= "com.acme.CheckoutService#normalize" (:symbol %))
                                      (= 1 (:method_arity %)))
                             %)
                          checkout-units)
        normalize-2 (some #(when (and (= "com.acme.CheckoutService#normalize" (:symbol %))
                                      (= 2 (:method_arity %)))
                             %)
                          checkout-units)
        callers-1 (sci/query-callers storage tmp-root (:unit_id normalize-1) {:limit 20})
        callers-2 (sci/query-callers storage tmp-root (:unit_id normalize-2) {:limit 20})
        process-order (some #(when (= "com.acme.CheckoutService#processOrder" (:symbol %)) %) checkout-units)
        normalize-arity1 (some #(when (and (= "com.acme.CheckoutService#normalize" (:symbol %))
                                           (= 1 (:method_arity %)))
                                  %)
                               checkout-units)]
    (is normalize-1)
    (is normalize-2)
    (is (not-any? #(= "com.acme.CheckoutService#processOrder" (:symbol %)) callers-1))
    (is (some #(= "com.acme.CheckoutService#processOrder" (:symbol %)) callers-2))
    (is (some #(= "com.acme.CheckoutService#normalize" (:symbol %)) callers-2))
    (is process-order)
    (is normalize-arity1)))

(deftest java-static-import-resolution-prefers-imported-class-and-arity-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-static-import-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        normalizer-units (sci/query-units storage tmp-root {:module "com.acme.IdNormalizer" :limit 20})
        imported-1 (some #(when (and (= "com.acme.IdNormalizer#normalizeImported" (:symbol %))
                                     (= 1 (:method_arity %)))
                            %)
                         normalizer-units)
        imported-2 (some #(when (and (= "com.acme.IdNormalizer#normalizeImported" (:symbol %))
                                     (= 2 (:method_arity %)))
                            %)
                         normalizer-units)
        callers-1 (sci/query-callers storage tmp-root (:unit_id imported-1) {:limit 20})
        callers-2 (sci/query-callers storage tmp-root (:unit_id imported-2) {:limit 20})]
    (is imported-1)
    (is imported-2)
    (is (some #(= "com.acme.CheckoutService#processImported" (:symbol %)) callers-1))
    (is (not-any? #(= "com.acme.CheckoutService#processImportedStrict" (:symbol %)) callers-1))
    (is (some #(= "com.acme.CheckoutService#processImportedStrict" (:symbol %)) callers-2))))

(deftest python-import-and-self-call-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-callers-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-units (sci/query-units storage tmp-root {:module "app.validators" :limit 20})
        validate-order-id (some->> validator-units (filter #(= "app.validators/validate_order" (:symbol %))) first :unit_id)
        order-units (sci/query-units storage tmp-root {:module "app.orders" :limit 20})
        validate-local-id (some->> order-units (filter #(= "app.orders.OrderService/validate_local" (:symbol %))) first :unit_id)
        validate-callers (sci/query-callers storage tmp-root validate-order-id {:limit 20})
        local-callers (sci/query-callers storage tmp-root validate-local-id {:limit 20})]
    (is validate-order-id)
    (is validate-local-id)
    (is (some #(= "app.orders.OrderService/process_order" (:symbol %)) validate-callers))
    (is (some #(= "app.orders.OrderService/process_alias" (:symbol %)) validate-callers))
    (is (some #(= "app.orders.OrderService/process_local" (:symbol %)) local-callers))))

(deftest python-related-tests-link-via-test-module-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-related-tests" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context index sample-query-python)
        related-tests (get-in result [:context_packet :impact_hints :related_tests])]
    (is (some #{"app/orders_test.py"} related-tests))))

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

(deftest index-lifecycle-reuse-staleness-and-pinning-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-storage-lifecycle-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index-a (sci/create-index {:root_path tmp-root :storage storage})]
    (Thread/sleep 1100)
    (let [reused (sci/create-index {:root_path tmp-root
                                    :storage storage
                                    :load_latest true
                                    :max_snapshot_age_seconds 3600})
          rebuilt (sci/create-index {:root_path tmp-root
                                     :storage storage
                                     :load_latest true
                                     :max_snapshot_age_seconds 0})
          pinned (sci/create-index {:root_path tmp-root
                                    :storage storage
                                    :pinned_snapshot_id (:snapshot_id index-a)
                                    :max_snapshot_age_seconds 0})]
      (testing "fresh latest snapshot can be reused with lifecycle metadata"
        (is (= (:snapshot_id index-a) (:snapshot_id reused)))
        (is (true? (get-in reused [:index_lifecycle :reused_snapshot])))
        (is (false? (get-in reused [:index_lifecycle :stale])))
        (is (= "storage_latest" (get-in reused [:index_lifecycle :provenance :source]))))
      (testing "stale latest snapshot triggers rebuild with provenance"
        (is (not= (:snapshot_id index-a) (:snapshot_id rebuilt)))
        (is (= "snapshot_stale" (get-in rebuilt [:index_lifecycle :rebuild_reason])))
        (is (= (:snapshot_id index-a)
               (get-in rebuilt [:index_lifecycle :provenance :parent_snapshot_id]))))
      (testing "pinned snapshot reuses exact snapshot even when stale"
        (is (= (:snapshot_id index-a) (:snapshot_id pinned)))
        (is (true? (get-in pinned [:index_lifecycle :snapshot_pinned])))
        (is (true? (get-in pinned [:index_lifecycle :stale])))
        (is (= "storage_pinned" (get-in pinned [:index_lifecycle :provenance :source])))
        (is (= (:index_lifecycle pinned)
               (:index_lifecycle (sci/repo-map pinned))))))))

(deftest stale-index-freshness-guardrail-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-stale-guardrail-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        initial (sci/create-index {:root_path tmp-root :storage storage})]
    (Thread/sleep 1100)
    (let [pinned-stale (sci/create-index {:root_path tmp-root
                                          :storage storage
                                          :pinned_snapshot_id (:snapshot_id initial)
                                          :max_snapshot_age_seconds 0})
          result (sci/resolve-context pinned-stale sample-query)]
      (is (true? (get-in result [:context_packet :capabilities :index_stale])))
      (is (true? (get-in result [:context_packet :capabilities :snapshot_pinned])))
      (is (= "storage_pinned" (get-in result [:context_packet :capabilities :index_provenance_source])))
      (is (= "autonomy_blocked" (get-in result [:guardrail_assessment :autonomy_posture])))
      (is (some #(= "stale_index" (:code %))
                (get-in result [:guardrail_assessment :blocking_reasons]))))))

(deftest library-surface-emits-normalized-error-taxonomy-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-library-error-taxonomy-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (sci/create-index {:root_path tmp-root
                         :pinned_snapshot_id "missing-snapshot"})
      (is false "expected ex-info")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid_request (:type (ex-data e))))
        (is (= "invalid_request" (:error_code (ex-data e))))
        (is (= "client" (:error_category (ex-data e))))))))

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

(deftest clojure-regex-fallback-alias-aware-call-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-regex-callers-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index (sci/create-index {:root_path tmp-root
                                 :storage storage
                                 :parser_opts {:clojure_engine :regex
                                               :tree_sitter_enabled false}})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is (= "fallback" (get-in index [:files "test/my/app/order_test.clj" :parser_mode])))
    (is validate-unit-id)
    (is (some #(= "my.app.order/process-order" (:symbol %)) callers))
    (is (some #(= "my.app.order-test/process-order-test" (:symbol %)) callers))))

(deftest clojure-regex-parser-ignores-nested-comment-defs-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-regex-macro-boundaries" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root
                                  :storage storage
                                  :parser_opts {:clojure_engine :regex
                                                :tree_sitter_enabled false}})
        macro-units (sci/query-units storage tmp-root {:module "my.app.macros" :limit 20})
        symbols (set (map :symbol macro-units))
        with-order-unit (some->> macro-units (filter #(= "my.app.macros/with-order" (:symbol %))) first)
        visible-helper-unit (some->> macro-units (filter #(= "my.app.macros/visible-helper" (:symbol %))) first)]
    (is (= #{"my.app.macros/macro-helper"
             "my.app.macros/with-letfn-validation"
             "my.app.macros/with-branching-validation"
             "my.app.macros/with-composed-validation"
             "my.app.macros/with-order"
             "my.app.macros/with-listed-prepared"
             "my.app.macros/with-listed-validation"
             "my.app.macros/with-side-effect-helper"
             "my.app.macros/with-validated-order"
             "my.app.macros/with-prepared-order"
             "my.app.macros/visible-helper"} symbols))
    (is (nil? (some #(= "my.app.macros/hidden-helper" (:symbol %)) macro-units)))
    (is with-order-unit)
    (is visible-helper-unit)
    (is (< (:end_line with-order-unit) (:start_line visible-helper-unit)))))

(deftest clojure-macro-generated-ownership-adds-caller-edge-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-macro-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.order/process-order" (:symbol %)) callers))
    (is (some #(= "my.app.workflow/prepare-order" (:symbol %)) callers))))

(deftest clojure-macro-generated-ownership-recurses-across-nested-macros-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-macro-recursive-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.workflow/prepare-prevalidated-order" (:symbol %)) callers))))

(deftest clojure-list-built-macro-generated-ownership-recurses-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-list-macro-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.workflow/prepare-listed-order" (:symbol %)) callers))))

(deftest clojure-composed-macro-generated-ownership-supports-concat-and-into-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-composed-macro-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.workflow/prepare-composed-order" (:symbol %)) callers))
    (is (some #(= "my.app.workflow/prepare-branching-order" (:symbol %)) callers))))

(deftest clojure-letfn-helper-generated-ownership-adds-caller-edge-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-letfn-macro-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.workflow/prepare-letfn-order" (:symbol %)) callers))))

(deftest clojure-letfn-helper-side-effects-do-not-leak-ownership-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-letfn-helper-side-effect" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (not-any? #(= "my.app.workflow/prepare-side-effect-order" (:symbol %)) callers))))

(deftest clojure-macro-implementation-details-do-not-leak-ownership-edges-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-macro-filtered-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        macro-units (sci/query-units storage tmp-root {:module "my.app.macros" :limit 20})
        helper-unit-id (some->> macro-units (filter #(= "my.app.macros/macro-helper" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root helper-unit-id {:limit 20})]
    (is helper-unit-id)
    (is (not-any? #(= "my.app.workflow/prepare-order" (:symbol %)) callers))
    (is (not-any? #(= "my.app.workflow/prepare-prevalidated-order" (:symbol %)) callers))))

(deftest clojure-defmethod-identity-and-caller-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-defmethod-identity" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        shipping-units (sci/query-units storage tmp-root {:module "my.app.shipping" :limit 20})
        route-methods (->> shipping-units
                           (filter #(= "my.app.shipping/route-order" (:symbol %)))
                           (filter #(= "method" (:kind %)))
                           vec)
        pickup-method (some #(when (= ":pickup" (:dispatch_value %)) %) route-methods)
        delivery-method (some #(when (= ":delivery" (:dispatch_value %)) %) route-methods)
        pickup-stop-id (some->> shipping-units (filter #(= "my.app.shipping/pickup-stop" (:symbol %))) first :unit_id)
        delivery-stop-id (some->> shipping-units (filter #(= "my.app.shipping/delivery-stop" (:symbol %))) first :unit_id)
        pickup-callers (sci/query-callers storage tmp-root pickup-stop-id {:limit 20})
        delivery-callers (sci/query-callers storage tmp-root delivery-stop-id {:limit 20})]
    (is (= 2 (count route-methods)))
    (is (= 2 (count (distinct (map :unit_id route-methods)))))
    (is pickup-method)
    (is delivery-method)
    (is (not= (:unit_id pickup-method) (:unit_id delivery-method)))
    (is (some #(= (:unit_id pickup-method) (:unit_id %)) pickup-callers))
    (is (some #(= (:unit_id delivery-method) (:unit_id %)) delivery-callers))
    (is (not-any? #(= (:unit_id delivery-method) (:unit_id %)) pickup-callers))
    (is (not-any? #(= (:unit_id pickup-method) (:unit_id %)) delivery-callers))))

(deftest clojure-multimethod-dispatch-query-ranks-correct-defmethod-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-dispatch-ranking" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index (sci/create-index {:root_path tmp-root :storage storage})
        shipping-units (sci/query-units storage tmp-root {:module "my.app.shipping" :limit 20})
        pickup-method (some #(when (= ":pickup" (:dispatch_value %)) %) shipping-units)
        query {:schema_version "1.0"
               :intent {:purpose "code_understanding"
                        :details "Locate the pickup route-order implementation."}
               :targets {:symbols ["my.app.shipping/route-order"]
                         :paths ["src/my/app/shipping.clj"]}
               :constraints {:token_budget 1200
                             :max_raw_code_level "enclosing_unit"
                             :freshness "current_snapshot"}
               :hints {:prefer_definitions_over_callers true}
               :options {:include_tests false
                         :include_impact_hints true
                         :allow_raw_code_escalation false
                         :favor_compact_packet true
                         :favor_higher_recall false}
               :trace {:trace_id "55555555-5555-4555-8555-555555555555"
                       :request_id "runtime-test-clj-dispatch-001"
                       :actor_id "test_runner"}}
        result (sci/resolve-context index query)]
    (is pickup-method)
    (is (= (:unit_id pickup-method)
           (get-in result [:context_packet :relevant_units 0 :unit_id])))
    (is (= "high" (get-in result [:context_packet :confidence :level])))))

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
