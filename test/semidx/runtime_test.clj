(ns semidx.runtime-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [semidx.contracts.schemas :as contracts]
            [semidx.core :as sci]
            [semidx.runtime.adapters :as adapters]
            [semidx.runtime.languages.clojure :as clj-language]
            [semidx.runtime.languages.elixir :as ex-language]
            [semidx.runtime.languages.java :as java-language]
            [semidx.runtime.languages.lua :as lua-language]
            [semidx.runtime.languages.python :as py-language]
            [semidx.runtime.languages.typescript :as ts-language]
            [semidx.runtime.retrieval-policy :as rp]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order\n  (:require [clojure.string :as str]))\n\n(defn process-order [ctx order]\n  (validate-order order)\n  (str/join \"-\" [\"ok\" (:id order)]))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n")
  (write-file! root "test/my/app/order_support.clj"
               "(ns my.app.order-support\n  (:require [my.app.order :as order]))\n\n(defn run-order-helper []\n  (order/process-order {} {:id 1}))\n")
  (write-file! root "test/my/app/order_support_test.clj"
               "(ns my.app.order-support-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order-support :as support]))\n\n(deftest support-wraps-order-processing\n  (is (string? (support/run-order-helper))))\n")
  (write-file! root "src/my/app/alt_order.clj"
               "(ns my.app.alt-order)\n\n(defn validate-order [order]\n  (assoc order :alt true))\n")
  (write-file! root "src/my/app/alias_workflow.clj"
               "(ns my.app.alias-workflow\n  (:require [my.app.order :as primary]\n            [my.app.alt-order :as alternate]))\n\n(defn prepare-primary [order]\n  (primary/validate-order order))\n\n(defn prepare-alternate [order]\n  (alternate/validate-order order))\n")
  (write-file! root "src/my/app/lexical_shadow.clj"
               "(ns my.app.lexical-shadow\n  (:require [my.app.order :as order]))\n\n(defn run-qualified-global [payload]\n  (order/validate-order payload))\n\n(defn run-param-shadow [validate-order payload]\n  (validate-order payload))\n\n(defn run-let-shadow [payload]\n  (let [validate-order (fn [value] value)]\n    (validate-order payload)))\n\n(defn run-destructured-shadow [helpers payload]\n  (let [{:keys [validate-order]} helpers]\n    (validate-order payload)))\n\n(defn run-when-let-shadow [maybe-validator payload]\n  (when-let [validate-order maybe-validator]\n    (validate-order payload)))\n\n(defn run-doseq-shadow [payload]\n  (doseq [validate-order [(fn [value] value)]]\n    (validate-order payload)))\n\n(defn run-as-thread-shadow [payload]\n  (as-> payload validate-order\n    (str validate-order)))\n")
  (write-file! root "src/my/app/macros.clj"
               "(ns my.app.macros\n  (:require [my.app.order :as order]\n            [my.app.alt-order :as alt-order]))\n\n(comment\n  (defn hidden-helper [order]\n    (:id order)))\n\n(defn macro-helper [order]\n  order)\n\n(defn emit-top-level-validation [order]\n  (list 'order/validate-order order))\n\n(defn helper-side-effect [order]\n  (emit-top-level-validation order)\n  order)\n\n(defmacro with-order [order & body]\n  `(let [current-order# ~order]\n     ~@body))\n\n(defmacro with-validated-order [order & body]\n  (macro-helper order)\n  `(let [validated-order# (order/validate-order ~order)]\n     ~@body))\n\n(defmacro with-prepared-order [order & body]\n  `(with-validated-order ~order\n     ~@body))\n\n(defmacro with-listed-validation [order & body]\n  (list 'do\n        (list 'order/validate-order order)\n        (cons 'do body)))\n\n(defmacro with-listed-prepared [order & body]\n  (list 'with-listed-validation order\n        (cons 'do body)))\n\n(defmacro with-composed-validation [order & body]\n  (concat (list 'do)\n          (list (list 'order/validate-order order))\n          body))\n\n(defmacro with-threaded-validation [order & body]\n  `(let [validated-order# (-> ~order\n                              order/validate-order)]\n     ~@body))\n\n(defmacro with-threaded-ambiguous-validation [order mode & body]\n  (if (= mode :primary)\n    `(let [validated-order# (-> ~order\n                                order/validate-order)]\n       ~@body)\n    `(let [validated-order# (-> ~order\n                                alt-order/validate-order)]\n       ~@body)))\n\n(defmacro with-top-level-helper-validation [order & body]\n  (apply list 'do\n         (concat [(emit-top-level-validation order)]\n                 body)))\n\n(defmacro with-branching-validation [order mode & body]\n  (if (= mode :apply)\n    (apply list 'do\n           (concat (list (list 'order/validate-order order))\n                   body))\n    (concat (list 'do)\n            (into []\n                  (concat [(list 'order/validate-order order)]\n                          body)))))\n\n(defmacro with-ambiguous-branch-validation [order mode & body]\n  (if (= mode :primary)\n    (apply list 'do\n           (concat (list (list 'order/validate-order order))\n                   body))\n    (apply list 'do\n           (concat (list (list 'alt-order/validate-order order))\n                   body))))\n\n(defmacro with-letfn-validation [order & body]\n  (letfn [(emit-validation []\n            (list 'order/validate-order order))]\n    (apply list 'do\n           (concat [(emit-validation)]\n                   body))))\n\n(defmacro with-side-effect-helper [order & body]\n  (helper-side-effect order)\n  `(do ~@body))\n\n(defn visible-helper [order]\n  (with-order order\n    (:id order)))\n")
  (write-file! root "src/my/app/workflow.clj"
               "(ns my.app.workflow\n  (:require [my.app.macros :refer [with-validated-order with-prepared-order with-listed-prepared with-composed-validation with-threaded-validation with-threaded-ambiguous-validation with-top-level-helper-validation with-branching-validation with-ambiguous-branch-validation with-letfn-validation with-side-effect-helper]]))\n\n(defn prepare-order [order]\n  (with-validated-order order\n    {:prepared true}))\n\n(defn prepare-prevalidated-order [order]\n  (with-prepared-order order\n    {:prepared true :mode :nested}))\n\n(defn prepare-listed-order [order]\n  (with-listed-prepared order\n    {:prepared true :mode :list-generated}))\n\n(defn prepare-composed-order [order]\n  (with-composed-validation order\n    {:prepared true :mode :concat-generated}))\n\n(defn prepare-threaded-order [order]\n  (with-threaded-validation order\n    {:prepared true :mode :thread-generated}))\n\n(defn prepare-threaded-ambiguous-order [order]\n  (with-threaded-ambiguous-validation order :primary\n    {:prepared true :mode :thread-ambiguous}))\n\n(defn prepare-top-level-helper-order [order]\n  (with-top-level-helper-validation order\n    {:prepared true :mode :top-level-helper-generated}))\n\n(defn prepare-branching-order [order]\n  (with-branching-validation order :apply\n    {:prepared true :mode :branch-generated}))\n\n(defn prepare-ambiguous-order [order]\n  (with-ambiguous-branch-validation order :primary\n    {:prepared true :mode :branch-ambiguous}))\n\n(defn prepare-letfn-order [order]\n  (with-letfn-validation order\n    {:prepared true :mode :letfn-generated}))\n\n(defn prepare-side-effect-order [order]\n  (with-side-effect-helper order\n    {:prepared true :mode :helper-side-effect}))\n")
  (write-file! root "src/my/app/shipping.clj"
               "(ns my.app.shipping)\n\n(defmulti route-order (fn [mode payload] mode))\n\n(defn pickup-stop [payload]\n  (:pickup payload))\n\n(defn delivery-stop [payload]\n  (:delivery payload))\n\n(defmethod route-order :pickup [_ payload]\n  (pickup-stop payload))\n\n(defmethod route-order :delivery [_ payload]\n  (delivery-stop payload))\n\n(defn plan-route [mode payload]\n  (route-order mode payload))\n\n(defn plan-pickup [payload]\n  (route-order :pickup payload))\n\n(defn plan-delivery [payload]\n  (route-order :delivery payload))\n")
  (write-file! root "src/my/app/protocols.clj"
               "(ns my.app.protocols)\n\n(defprotocol OrderFormatter\n  (format-order [this payload])\n  (format-summary [this]))\n\n(defn render-order [formatter payload]\n  (format-order formatter payload))\n")
  (write-file! root "src/com/acme/CheckoutService.java"
               "package com.acme;\n\nimport java.util.Objects;\nimport static com.acme.IdNormalizer.normalizeImported;\n\npublic class CheckoutService {\n  public CheckoutService() {\n    this(\"default\");\n  }\n\n  public CheckoutService(String id) {\n    normalize(id, true);\n  }\n\n  public String processOrder(String id) {\n    return normalize(id, true);\n  }\n\n  public String processImported(String id) {\n    return normalizeImported(id);\n  }\n\n  public String processImportedStrict(String id) {\n    return normalizeImported(id, true);\n  }\n\n  private String normalize(String id) {\n    return normalize(id, false);\n  }\n\n  private String normalize(String id, boolean strict) {\n    String base = Objects.requireNonNull(id).trim();\n    return strict ? base : base.toLowerCase();\n  }\n}\n")
  (write-file! root "src/com/acme/CheckoutFactory.java"
               "package com.acme;\n\npublic class CheckoutFactory {\n  public CheckoutService buildDefault() {\n    return new CheckoutService();\n  }\n\n  public CheckoutService buildConfigured(String id) {\n    return new CheckoutService(id);\n  }\n}\n")
  (write-file! root "src/com/acme/IdNormalizer.java"
               "package com.acme;\n\npublic class IdNormalizer {\n  public static String normalizeImported(String id) {\n    return id.trim();\n  }\n\n  public static String normalizeImported(String id, boolean strict) {\n    String base = id.trim();\n    return strict ? base : base.toLowerCase();\n  }\n}\n")
  (write-file! root "src/com/acme/AuditService.java"
               "package com.acme;\n\nimport static com.acme.IdNormalizer.normalizeImported;\n\npublic class AuditService {\n  public String processLocalCollision(String id) {\n    return normalizeImported(id);\n  }\n\n  public String processThisCollision(String id) {\n    return this.normalizeImported(id);\n  }\n\n  public String processImportedCollision(String id) {\n    return IdNormalizer.normalizeImported(id);\n  }\n\n  private String normalizeImported(String id) {\n    return \"[\" + id.trim() + \"]\";\n  }\n}\n")
  (write-file! root "src/com/acme/BaseNormalizer.java"
               "package com.acme;\n\npublic class BaseNormalizer {\n  protected String normalizeBase(String id) {\n    return id.trim().toLowerCase();\n  }\n}\n")
  (write-file! root "src/com/acme/InheritedNormalizer.java"
               "package com.acme;\n\npublic class InheritedNormalizer extends BaseNormalizer {\n  public String processInherited(String id) {\n    return normalizeBase(id);\n  }\n\n  public String processInheritedLambda(String id) {\n    java.util.function.Function<String, String> fn = value -> normalizeBase(value);\n    return fn.apply(id);\n  }\n}\n")
  (write-file! root "src/com/acme/OverridingNormalizer.java"
               "package com.acme;\n\npublic class OverridingNormalizer extends BaseNormalizer {\n  @Override\n  protected String normalizeBase(String id) {\n    return \"[\" + id.trim() + \"]\";\n  }\n\n  public String processSuper(String id) {\n    return super.normalizeBase(id);\n  }\n\n  public String processLocal(String id) {\n    return normalizeBase(id);\n  }\n\n  public String processSuperMethodReference(String id) {\n    java.util.function.Function<String, String> fn = super::normalizeBase;\n    return fn.apply(id);\n  }\n}\n")
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
  (write-file! root "lib/my_app/local_formatter.ex"
               "defmodule MyApp.LocalFormatter do\n  use MyApp.Formatter\n\n  def normalize(order) do\n    {:local, order}\n  end\n\n  def process_local(order) do\n    normalize(order)\n  end\nend\n")
  (write-file! root "lib/my_app/module_self_formatter.ex"
               "defmodule MyApp.ModuleSelfFormatter do\n  use MyApp.Formatter\n\n  def normalize(order) do\n    {:module_self, order}\n  end\n\n  def process_module_self(order) do\n    __MODULE__.normalize(order)\n  end\nend\n")
  (write-file! root "lib/my_app/local_overloaded_formatter.ex"
               "defmodule MyApp.LocalOverloadedFormatter do\n  use MyApp.Formatter\n\n  def normalize(order, mode) do\n    {order, mode}\n  end\n\n  def process_imported_one(order) do\n    normalize(order)\n  end\n\n  def process_local_two(order) do\n    normalize(order, :strict)\n  end\nend\n")
  (write-file! root "lib/my_app/order_case.ex"
               "defmodule MyApp.OrderCase do\n  defmacro __using__(_opts) do\n    quote do\n      import MyApp.Validator\n    end\n  end\nend\n")
  (write-file! root "lib/my_app/implicit_use_client.ex"
               "defmodule MyApp.ImplicitUseClient do\n  use MyApp.OrderCase\n\n  def process_implicit(order) do\n    validate(order)\n  end\nend\n")
  (write-file! root "lib/my_app/overloads.ex"
               "defmodule MyApp.Overloads do\n  def normalize(order) do\n    normalize(order, :default)\n  end\n\n  def normalize(order, mode) do\n    {order, mode}\n  end\n\n  def call_one(order) do\n    normalize(order)\n  end\n\n  def call_two(order) do\n    normalize(order, :strict)\n  end\nend\n")
  (write-file! root "lib/my_app/pipeline_formatter.ex"
               "defmodule MyApp.PipelineFormatter do\n  use MyApp.Formatter\n\n  def normalize(order) do\n    {:local, order}\n  end\n\n  def process_pipeline(order) do\n    order\n    |> normalize()\n  end\n\n  def process_with(order) do\n    with normalized <- normalize(order) do\n      normalized\n    end\n  end\n\n  def process_capture(order) do\n    formatter = &normalize/1\n    formatter.(order)\n  end\nend\n")
  (write-file! root "lib/my_app/nested_client.ex"
               "defmodule MyApp.NestedClient do\n  def process_nested(order) do\n    __MODULE__.Nested.normalize(order)\n  end\nend\n")
  (write-file! root "lib/my_app/nested_client/nested.ex"
               "defmodule MyApp.NestedClient.Nested do\n  def normalize(order) do\n    {:nested, order}\n  end\nend\n")
  (write-file! root "test/my_app/order_test.exs"
               "defmodule MyApp.OrderTest do\n  use ExUnit.Case\n  alias MyApp.Order\n\n  test \"process order stays linked to source module\" do\n    assert {:ok, %{id: 1}} = {:ok, Order.process_order(%{}, %{id: 1})}\n  end\nend\n")
  (write-file! root "app/orders.py"
               "from app.validators import validate_order\nimport app.validators as validators\n\nclass OrderService:\n    def process_order(self, order):\n        return validate_order(order)\n\n    def process_alias(self, order):\n        return validators.validate_order(order)\n\n    def process_local(self, order):\n        return self.validate_local(order)\n\n    def validate_local(self, order):\n        return bool(order)\n\n\ndef validate_local(order):\n    return bool(order)\n")
  (write-file! root "app/collision_orders.py"
               "from app.validators import validate_order\nimport app.validators as validators\n\nclass CollisionService:\n    def validate_order(self, order):\n        return {\"local\": bool(order)}\n\n    def process_method(self, order):\n        return self.validate_order(order)\n\n    def process_class_name(self, order):\n        return CollisionService.validate_order(self, order)\n\n    def process_module_alias(self, order):\n        return validators.validate_order(order)\n\n\ndef validate_order(order):\n    return {\"top_level\": bool(order)}\n\n\ndef process_top_level(order):\n    return validate_order(order)\n")
  (write-file! root "app/validators.py"
               "def validate_order(order):\n    return bool(order and order.get(\"id\"))\n")
  (write-file! root "app/nested/relative_orders.py"
               "from ..validators import validate_order\n\nclass NestedRelativeOrderService:\n    def process_nested_relative(self, order):\n        return validate_order(order)\n")
  (write-file! root "app/nested/relative_collision_orders.py"
               "from ..validators import validate_order\n\n\ndef validate_order(order):\n    return {\"local\": bool(order)}\n\n\ndef process_local_relative(order):\n    return validate_order(order)\n")
  (write-file! root "app/decorated_orders.py"
               "from app.validators import validate_order\n\n\ndef trace_call(fn):\n    return fn\n\n\nclass DecoratedService:\n    @classmethod\n    def build(cls, order):\n        return {\"built\": order}\n\n    @staticmethod\n    def normalize(order):\n        return bool(order)\n\n    @property\n    def status(self):\n        return \"ready\"\n\n    @classmethod\n    def process_class(cls, order):\n        return cls.build(order)\n\n    @staticmethod\n    def process_static(order):\n        return DecoratedService.normalize(order)\n\n    @trace_call\n    def process_nested(self, order):\n        def validate_order(order):\n            return {\"local\": bool(order)}\n\n        return validate_order(order)\n\n    @trace_call\n    def process_nested_class(self, order):\n        class LocalFormatter:\n            @staticmethod\n            def normalize(order):\n                return {\"local\": order}\n\n        return LocalFormatter.normalize(order)\n\n    def process_status(self):\n        return self.status\n")
  (write-file! root "app/orders_test.py"
               "from app.orders import OrderService\n\n\ndef test_process_order():\n    service = OrderService()\n    assert service.process_order({\"id\": 1})\n")
  (write-file! root "src/example/normalize.ts"
               "export function normalizeOrder(orderId: string): string {\n  return (orderId || \"\").trim().toLowerCase();\n}\n")
  (write-file! root "src/example/default_normalize.ts"
               "export default function normalizeDefault(orderId: string): string {\n  return (orderId || \"\").trim().toUpperCase();\n}\n")
  (write-file! root "src/example/helpers.ts"
               "export function normalizeHelper(orderId: string): string {\n  return (orderId || \"\").trim();\n}\n")
  (write-file! root "src/example/main.ts"
               "import { normalizeOrder } from \"./normalize\";\n\nexport function processMain(orderId: string): string {\n  return normalizeOrder(orderId);\n}\n\nexport class MainService {\n  processMain(orderId: string): string {\n    return processMain(orderId);\n  }\n}\n")
  (write-file! root "src/example/import_modes.ts"
               "import normalizeDefault from \"./default_normalize\";\nimport { normalizeHelper as namedNormalize } from \"./helpers\";\nimport * as helperNs from \"./helpers\";\n\nexport function processImports(orderId: string): string {\n  namedNormalize(orderId);\n  return normalizeDefault(orderId);\n}\n\nexport const normalizeExpr = function(orderId: string): string {\n  return namedNormalize(orderId);\n};\n\nexport class ImportService {\n  processThis(orderId: string): string {\n    return this.normalizeLocal(orderId);\n  }\n\n  processNamespace(orderId: string): string {\n    return helperNs.normalizeHelper(orderId);\n  }\n\n  static processStaticCall(orderId: string): string {\n    return ImportService.normalizeStatic(orderId);\n  }\n\n  normalizeLocal(orderId: string): string {\n    return namedNormalize(orderId);\n  }\n\n  static normalizeStatic(orderId: string): string {\n    return normalizeDefault(orderId);\n  }\n}\n")
  (write-file! root "src/example/collision_modes.ts"
               "import * as helperNs from \"./helpers\";\n\nexport class CollisionService {\n  normalizeHelper(orderId: string): string {\n    return `local:${orderId}`;\n  }\n\n  processThis(orderId: string): string {\n    return this.normalizeHelper(orderId);\n  }\n\n  processNamespace(orderId: string): string {\n    return helperNs.normalizeHelper(orderId);\n  }\n}\n")
  (write-file! root "src/example/object_modes.ts"
               "export const formatters = {\n  normalizeObject(orderId: string): string {\n    return (orderId || \"\").trim();\n  }\n};\n\nexport function processObject(orderId: string): string {\n  return formatters.normalizeObject(orderId);\n}\n")
  (write-file! root "src/example/field_methods.ts"
               "export class FieldService {\n  normalizeField = (orderId: string): string => {\n    return (orderId || \"\").trim();\n  };\n\n  processField(orderId: string): string {\n    return this.normalizeField(orderId);\n  }\n}\n")
  (write-file! root "src/example/default_alias.ts"
               "function normalizeAlias(orderId: string): string {\n  return (orderId || \"\").trim();\n}\n\nexport default normalizeAlias;\n")
  (write-file! root "src/example/default_alias_consumer.ts"
               "import normalizeAlias from \"./default_alias\";\n\nexport function processDefaultAlias(orderId: string): string {\n  return normalizeAlias(orderId);\n}\n")
  (write-file! root "src/example/barrel.ts"
               "export { normalizeOrder as exportedNormalize } from \"./normalize\";\n")
  (write-file! root "src/example/re_export_consumer.ts"
               "import { exportedNormalize } from \"./barrel\";\n\nexport function processReExport(orderId: string): string {\n  return exportedNormalize(orderId);\n}\n")
  (write-file! root "test/example/main_test.ts"
               "import { processMain } from \"../../src/example/main\";\n\nexport function testProcessMain(): string {\n  return processMain(\"A-1\");\n}\n"))

(def sample-query
  {:api_version "1.0"
   :schema_version "1.0"
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
             :allow_raw_code_escalation false}
   :trace {:trace_id "11111111-1111-4111-8111-111111111111"
           :request_id "runtime-test-001"
           :actor_id "test_runner"}})

(def sample-query-elixir
  {:api_version "1.0"
   :schema_version "1.0"
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
             :allow_raw_code_escalation false}
   :trace {:trace_id "22222222-2222-4222-8222-222222222222"
           :request_id "runtime-test-ex-001"
           :actor_id "test_runner"}})

(def sample-query-python
  {:api_version "1.0"
   :schema_version "1.0"
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
             :allow_raw_code_escalation false}
   :trace {:trace_id "33333333-3333-4333-8333-333333333333"
           :request_id "runtime-test-py-001"
           :actor_id "test_runner"}})

(def sample-query-typescript
  {:api_version "1.0"
   :schema_version "1.0"
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
             :allow_raw_code_escalation false}
   :trace {:trace_id "44444444-4444-4444-8444-444444444444"
           :request_id "runtime-test-ts-001"
           :actor_id "test_runner"}})

(deftest end-to-end-resolve-context-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context-detail index sample-query)
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
        result (sci/resolve-context-detail index sample-query)
        related-tests (get-in result [:context_packet :impact_hints :related_tests])]
    (is (some #{"test/my/app/order_test.clj"} related-tests))))

(deftest clojure-related-tests-link-via-indirect-test-helper-namespace-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-clj-indirect-related-tests" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context-detail index sample-query)
        related-tests (get-in result [:context_packet :impact_hints :related_tests])]
    (is (some #{"test/my/app/order_support_test.clj"} related-tests))))

(deftest retrieval-policy-can-change-ranking-band-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-policy-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        baseline (sci/resolve-context-detail index sample-query)
        strict-policy {:policy_id "heuristic_v1_strict_top"
                       :version "2026-03-10"
                       :thresholds {:top_authority_min 500}}
        strict-result (sci/resolve-context-detail index sample-query {:retrieval_policy strict-policy})]
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
        result (sci/resolve-context-detail index sample-query)]
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
        result (sci/resolve-context-detail index
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
        ex-result (sci/resolve-context-detail index sample-query-elixir)
        py-result (sci/resolve-context-detail index sample-query-python)
        ts-result (sci/resolve-context-detail index sample-query-typescript)]
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
        result (sci/resolve-context-detail index sample-query-elixir)
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

(deftest elixir-local-function-beats-use-expanded-import-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-local-precedence-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        local-units (sci/query-units storage tmp-root {:module "MyApp.LocalFormatter" :limit 20})
        formatter-units (sci/query-units storage tmp-root {:module "MyApp.Formatter" :limit 20})
        local-normalize-id (some->> local-units
                                    (filter #(= "MyApp.LocalFormatter/normalize" (:symbol %)))
                                    first
                                    :unit_id)
        shared-normalize-id (some->> formatter-units
                                     (filter #(= "MyApp.Formatter/normalize" (:symbol %)))
                                     first
                                     :unit_id)
        local-callers (sci/query-callers storage tmp-root local-normalize-id {:limit 20})
        shared-callers (sci/query-callers storage tmp-root shared-normalize-id {:limit 20})]
    (is local-normalize-id)
    (is shared-normalize-id)
    (is (some #(= "MyApp.LocalFormatter/process_local" (:symbol %)) local-callers))
    (is (not-any? #(= "MyApp.LocalFormatter/process_local" (:symbol %)) shared-callers))))

(deftest elixir-module-self-qualified-call-beats-imported-collision-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-module-self-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        local-units (sci/query-units storage tmp-root {:module "MyApp.ModuleSelfFormatter" :limit 20})
        formatter-units (sci/query-units storage tmp-root {:module "MyApp.Formatter" :limit 20})
        local-normalize-id (some->> local-units
                                    (filter #(= "MyApp.ModuleSelfFormatter/normalize" (:symbol %)))
                                    first
                                    :unit_id)
        shared-normalize-id (some->> formatter-units
                                     (filter #(= "MyApp.Formatter/normalize" (:symbol %)))
                                     first
                                     :unit_id)
        local-callers (sci/query-callers storage tmp-root local-normalize-id {:limit 20})
        shared-callers (sci/query-callers storage tmp-root shared-normalize-id {:limit 20})]
    (is local-normalize-id)
    (is shared-normalize-id)
    (is (some #(= "MyApp.ModuleSelfFormatter/process_module_self" (:symbol %)) local-callers))
    (is (not-any? #(= "MyApp.ModuleSelfFormatter/process_module_self" (:symbol %)) shared-callers))))

(deftest elixir-local-shadowing-is-arity-aware-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-local-arity-shadowing-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        overloaded-units (sci/query-units storage tmp-root {:module "MyApp.LocalOverloadedFormatter" :limit 20})
        formatter-units (sci/query-units storage tmp-root {:module "MyApp.Formatter" :limit 20})
        local-normalize-2-id (some->> overloaded-units
                                       (filter #(and (= "MyApp.LocalOverloadedFormatter/normalize" (:symbol %))
                                                     (= 2 (:method_arity %))))
                                       first
                                       :unit_id)
        formatter-normalize-1-id (some->> formatter-units
                                          (filter #(= "MyApp.Formatter/normalize" (:symbol %)))
                                          first
                                          :unit_id)
        local-callers (sci/query-callers storage tmp-root local-normalize-2-id {:limit 20})
        formatter-callers (sci/query-callers storage tmp-root formatter-normalize-1-id {:limit 20})]
    (is local-normalize-2-id)
    (is formatter-normalize-1-id)
    (is (some #(= "MyApp.LocalOverloadedFormatter/process_local_two" (:symbol %)) local-callers))
    (is (not-any? #(= "MyApp.LocalOverloadedFormatter/process_imported_one" (:symbol %)) local-callers))
    (is (some #(= "MyApp.LocalOverloadedFormatter/process_imported_one" (:symbol %)) formatter-callers))))

(deftest elixir-implicit-use-imports-propagate-from-using-macro-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-implicit-use-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-units (sci/query-units storage tmp-root {:module "MyApp.Validator" :limit 20})
        validate-unit-id (some->> validator-units
                                  (filter #(= "MyApp.Validator/validate" (:symbol %)))
                                  first
                                  :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "MyApp.ImplicitUseClient/process_implicit" (:symbol %)) callers))))

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

(deftest elixir-pipeline-with-and-capture-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-pipeline-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        local-units (sci/query-units storage tmp-root {:module "MyApp.PipelineFormatter" :limit 20})
        formatter-units (sci/query-units storage tmp-root {:module "MyApp.Formatter" :limit 20})
        local-normalize-id (some->> local-units
                                    (filter #(= "MyApp.PipelineFormatter/normalize" (:symbol %)))
                                    first
                                    :unit_id)
        imported-normalize-id (some->> formatter-units
                                       (filter #(= "MyApp.Formatter/normalize" (:symbol %)))
                                       first
                                       :unit_id)
        local-callers (sci/query-callers storage tmp-root local-normalize-id {:limit 20})
        imported-callers (sci/query-callers storage tmp-root imported-normalize-id {:limit 20})]
    (is local-normalize-id)
    (is imported-normalize-id)
    (is (some #(= "MyApp.PipelineFormatter/process_pipeline" (:symbol %)) local-callers))
    (is (some #(= "MyApp.PipelineFormatter/process_with" (:symbol %)) local-callers))
    (is (some #(= "MyApp.PipelineFormatter/process_capture" (:symbol %)) local-callers))
    (is (not-any? #(= "MyApp.PipelineFormatter/process_capture" (:symbol %)) imported-callers))))

(deftest elixir-module-self-qualified-nested-module-call-links-to-nested-target-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-nested-module-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        nested-units (sci/query-units storage tmp-root {:module "MyApp.NestedClient.Nested" :limit 20})
        nested-normalize-id (some->> nested-units
                                     (filter #(= "MyApp.NestedClient.Nested/normalize" (:symbol %)))
                                     first
                                     :unit_id)
        callers (sci/query-callers storage tmp-root nested-normalize-id {:limit 20})]
    (is nested-normalize-id)
    (is (some #(= "MyApp.NestedClient/process_nested" (:symbol %)) callers))))

(deftest elixir-tree-sitter-falls-back-with-diagnostics-when-grammar-is-missing-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-elixir-tree-sitter-fallback" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        index (sci/create-index {:root_path tmp-root
                                 :storage storage
                                 :parser_opts {:elixir_engine :tree-sitter}})
        order-file (get-in index [:files "lib/my_app/order.ex"])
        validator-units (sci/query-units storage tmp-root {:module "MyApp.Validator" :limit 20})
        validate-unit-id (some->> validator-units
                                  (filter #(= "MyApp.Validator/validate" (:symbol %)))
                                  first
                                  :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})
        diag-codes (set (map :code (:diagnostics order-file)))]
    (is (= "elixir" (:language order-file)))
    (is (= "full" (:parser_mode order-file)))
    (is (or (contains? diag-codes "tree_sitter_missing_grammar")
            (contains? diag-codes "tree_sitter_unavailable")))
    (is validate-unit-id)
    (is (some #(= "MyApp.Order/process_order" (:symbol %)) callers))))

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

(deftest java-constructor-unit-identity-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-constructor-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        java-units (->> (:unit_order index)
                        (map #(get (:units index) %))
                        (filter #(= "src/com/acme/CheckoutService.java" (:path %)))
                        (filter #(= "com.acme.CheckoutService#CheckoutService" (:symbol %)))
                        vec)
        unit-ids (mapv :unit_id java-units)]
    (is (= 2 (count java-units)))
    (is (= #{"constructor"} (set (map :kind java-units))))
    (is (= 2 (count (distinct unit-ids))))
    (is (= #{0 1} (set (map :method_arity java-units))))))

(deftest java-constructor-caller-resolution-prefers-matching-arity-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-constructor-callers-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        checkout-units (sci/query-units storage tmp-root {:module "com.acme.CheckoutService" :limit 20})
        constructor-0 (some #(when (and (= "com.acme.CheckoutService#CheckoutService" (:symbol %))
                                        (= 0 (:method_arity %)))
                               %)
                            checkout-units)
        constructor-1 (some #(when (and (= "com.acme.CheckoutService#CheckoutService" (:symbol %))
                                        (= 1 (:method_arity %)))
                               %)
                            checkout-units)
        callers-0 (sci/query-callers storage tmp-root (:unit_id constructor-0) {:limit 20})
        callers-1 (sci/query-callers storage tmp-root (:unit_id constructor-1) {:limit 20})]
    (is constructor-0)
    (is constructor-1)
    (is (some #(= "com.acme.CheckoutFactory#buildDefault" (:symbol %)) callers-0))
    (is (not-any? #(= "com.acme.CheckoutFactory#buildConfigured" (:symbol %)) callers-0))
    (is (some #(= "com.acme.CheckoutFactory#buildConfigured" (:symbol %)) callers-1))))

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

(deftest java-local-method-beats-static-import-on-same-name-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-collision-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        audit-units (sci/query-units storage tmp-root {:module "com.acme.AuditService" :limit 20})
        normalizer-units (sci/query-units storage tmp-root {:module "com.acme.IdNormalizer" :limit 20})
        local-normalize-id (some->> audit-units
                                    (filter #(= "com.acme.AuditService#normalizeImported" (:symbol %)))
                                    first
                                    :unit_id)
        imported-normalize-id (some->> normalizer-units
                                       (filter #(and (= "com.acme.IdNormalizer#normalizeImported" (:symbol %))
                                                     (= 1 (:method_arity %))))
                                       first
                                       :unit_id)
        local-callers (sci/query-callers storage tmp-root local-normalize-id {:limit 20})
        imported-callers (sci/query-callers storage tmp-root imported-normalize-id {:limit 20})]
    (is local-normalize-id)
    (is imported-normalize-id)
    (is (some #(= "com.acme.AuditService#processLocalCollision" (:symbol %)) local-callers))
    (is (some #(= "com.acme.AuditService#processThisCollision" (:symbol %)) local-callers))
    (is (not-any? #(= "com.acme.AuditService#processImportedCollision" (:symbol %)) local-callers))
    (is (some #(= "com.acme.AuditService#processImportedCollision" (:symbol %)) imported-callers))
    (is (not-any? #(= "com.acme.AuditService#processLocalCollision" (:symbol %)) imported-callers))))

(deftest java-super-call-and-method-reference-prefer-parent-implementation-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-super-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        base-units (sci/query-units storage tmp-root {:module "com.acme.BaseNormalizer" :limit 20})
        overriding-units (sci/query-units storage tmp-root {:module "com.acme.OverridingNormalizer" :limit 20})
        base-normalize-id (some->> base-units
                                   (filter #(= "com.acme.BaseNormalizer#normalizeBase" (:symbol %)))
                                   first
                                   :unit_id)
        overriding-normalize-id (some->> overriding-units
                                         (filter #(= "com.acme.OverridingNormalizer#normalizeBase" (:symbol %)))
                                         first
                                         :unit_id)
        base-callers (sci/query-callers storage tmp-root base-normalize-id {:limit 20})
        overriding-callers (sci/query-callers storage tmp-root overriding-normalize-id {:limit 20})]
    (is base-normalize-id)
    (is overriding-normalize-id)
    (is (some #(= "com.acme.OverridingNormalizer#processSuper" (:symbol %)) base-callers))
    (is (some #(= "com.acme.OverridingNormalizer#processSuperMethodReference" (:symbol %)) base-callers))
    (is (not-any? #(= "com.acme.OverridingNormalizer#processLocal" (:symbol %)) base-callers))
    (is (some #(= "com.acme.OverridingNormalizer#processLocal" (:symbol %)) overriding-callers))
    (is (not-any? #(= "com.acme.OverridingNormalizer#processSuper" (:symbol %)) overriding-callers))))

(deftest java-inherited-unqualified-call-and-lambda-link-to-parent-method-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-inherited-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        base-units (sci/query-units storage tmp-root {:module "com.acme.BaseNormalizer" :limit 20})
        base-normalize-id (some->> base-units
                                   (filter #(= "com.acme.BaseNormalizer#normalizeBase" (:symbol %)))
                                   first
                                   :unit_id)
        base-callers (sci/query-callers storage tmp-root base-normalize-id {:limit 20})]
    (is base-normalize-id)
    (is (some #(= "com.acme.InheritedNormalizer#processInherited" (:symbol %)) base-callers))
    (is (some #(= "com.acme.InheritedNormalizer#processInheritedLambda" (:symbol %)) base-callers))))

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

(deftest python-local-function-and-method-beat-imported-symbols-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-collision-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        collision-units (sci/query-units storage tmp-root {:module "app.collision_orders" :limit 20})
        validator-units (sci/query-units storage tmp-root {:module "app.validators" :limit 20})
        local-top-level-id (some->> collision-units
                                    (filter #(= "app.collision_orders/validate_order" (:symbol %)))
                                    first
                                    :unit_id)
        local-method-id (some->> collision-units
                                 (filter #(= "app.collision_orders.CollisionService/validate_order" (:symbol %)))
                                 first
                                 :unit_id)
        imported-validator-id (some->> validator-units
                                       (filter #(= "app.validators/validate_order" (:symbol %)))
                                       first
                                       :unit_id)
        top-level-callers (sci/query-callers storage tmp-root local-top-level-id {:limit 20})
        method-callers (sci/query-callers storage tmp-root local-method-id {:limit 20})
        imported-callers (sci/query-callers storage tmp-root imported-validator-id {:limit 20})]
    (is local-top-level-id)
    (is local-method-id)
    (is imported-validator-id)
    (is (some #(= "app.collision_orders/process_top_level" (:symbol %)) top-level-callers))
    (is (some #(= "app.collision_orders.CollisionService/process_method" (:symbol %)) method-callers))
    (is (some #(= "app.collision_orders.CollisionService/process_class_name" (:symbol %)) method-callers))
    (is (some #(= "app.collision_orders.CollisionService/process_module_alias" (:symbol %)) imported-callers))
    (is (not-any? #(= "app.collision_orders/process_top_level" (:symbol %)) imported-callers))))

(deftest python-related-tests-link-via-test-module-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-related-tests" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context-detail index sample-query-python)
        related-tests (get-in result [:context_packet :impact_hints :related_tests])]
    (is (some #{"app/orders_test.py"} related-tests))))

(deftest python-relative-import-normalization-links-parent-package-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-relative-imports" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-units (sci/query-units storage tmp-root {:module "app.validators" :limit 20})
        validate-order-id (some->> validator-units
                                   (filter #(= "app.validators/validate_order" (:symbol %)))
                                   first
                                   :unit_id)
        validate-callers (sci/query-callers storage tmp-root validate-order-id {:limit 20})]
    (is validate-order-id)
    (is (some #(= "app.nested.relative_orders.NestedRelativeOrderService/process_nested_relative" (:symbol %))
              validate-callers))))

(deftest python-local-function-still-beats-relative-imported-symbol-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-relative-collision" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        collision-units (sci/query-units storage tmp-root {:module "app.nested.relative_collision_orders" :limit 20})
        validator-units (sci/query-units storage tmp-root {:module "app.validators" :limit 20})
        local-validate-id (some->> collision-units
                                   (filter #(= "app.nested.relative_collision_orders/validate_order" (:symbol %)))
                                   first
                                   :unit_id)
        imported-validate-id (some->> validator-units
                                      (filter #(= "app.validators/validate_order" (:symbol %)))
                                      first
                                      :unit_id)
        local-callers (sci/query-callers storage tmp-root local-validate-id {:limit 20})
        imported-callers (sci/query-callers storage tmp-root imported-validate-id {:limit 20})]
    (is local-validate-id)
    (is imported-validate-id)
    (is (some #(= "app.nested.relative_collision_orders/process_local_relative" (:symbol %))
              local-callers))
    (is (not-any? #(= "app.nested.relative_collision_orders/process_local_relative" (:symbol %))
                  imported-callers))))

(deftest python-classmethod-and-staticmethod-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-decorator-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        decorated-units (sci/query-units storage tmp-root {:module "app.decorated_orders" :limit 20})
        build-id (some->> decorated-units
                          (filter #(= "app.decorated_orders.DecoratedService/build" (:symbol %)))
                          first
                          :unit_id)
        normalize-id (some->> decorated-units
                              (filter #(= "app.decorated_orders.DecoratedService/normalize" (:symbol %)))
                              first
                              :unit_id)
        build-callers (sci/query-callers storage tmp-root build-id {:limit 20})
        normalize-callers (sci/query-callers storage tmp-root normalize-id {:limit 20})]
    (is build-id)
    (is normalize-id)
    (is (some #(= "app.decorated_orders.DecoratedService/process_class" (:symbol %)) build-callers))
    (is (some #(= "app.decorated_orders.DecoratedService/process_static" (:symbol %)) normalize-callers))))

(deftest python-nested-local-scope-and-property-access-stay-conservative-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-nested-scope" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        decorated-units (sci/query-units storage tmp-root {:module "app.decorated_orders" :limit 20})
        validator-units (sci/query-units storage tmp-root {:module "app.validators" :limit 20})
        imported-validate-id (some->> validator-units
                                      (filter #(= "app.validators/validate_order" (:symbol %)))
                                      first
                                      :unit_id)
        normalize-id (some->> decorated-units
                              (filter #(= "app.decorated_orders.DecoratedService/normalize" (:symbol %)))
                              first
                              :unit_id)
        status-id (some->> decorated-units
                           (filter #(= "app.decorated_orders.DecoratedService/status" (:symbol %)))
                           first
                           :unit_id)
        validate-callers (sci/query-callers storage tmp-root imported-validate-id {:limit 20})
        normalize-callers (sci/query-callers storage tmp-root normalize-id {:limit 20})
        status-callers (sci/query-callers storage tmp-root status-id {:limit 20})]
    (is imported-validate-id)
    (is normalize-id)
    (is status-id)
    (is (not-any? #(= "app.decorated_orders.DecoratedService/process_nested" (:symbol %))
                  validate-callers))
    (is (not-any? #(= "app.decorated_orders.DecoratedService/process_nested_class" (:symbol %))
                  normalize-callers))
    (is (not-any? #(= "app.decorated_orders.DecoratedService/process_status" (:symbol %))
                  status-callers))))

(deftest python-deeply-nested-local-def-does-not-shadow-outer-import-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-python-deep-nested-scope" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "app/validators.py"
                       "def validate_order(order):\n    return bool(order)\n")
        _ (write-file! tmp-root "app/deep_nested.py"
                       "from app.validators import validate_order\n\nclass DeepNestedService:\n    def process(self, order):\n        def helper(payload):\n            def validate_order(value):\n                return {\"local\": value}\n\n            return validate_order(payload)\n\n        helper(order)\n        return validate_order(order)\n")
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        validator-id (some->> (sci/query-units storage tmp-root {:module "app.validators" :limit 20})
                              (filter #(= "app.validators/validate_order" (:symbol %)))
                              first
                              :unit_id)
        callers (sci/query-callers storage tmp-root validator-id {:limit 20})]
    (is validator-id)
    (is (some #(= "app.deep_nested.DeepNestedService/process" (:symbol %)) callers))))

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

(deftest typescript-import-modes-and-function-expression-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-import-modes" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        helper-units (sci/query-units storage tmp-root {:module "src.example.helpers" :limit 20})
        default-units (sci/query-units storage tmp-root {:module "src.example.default_normalize" :limit 20})
        import-mode-units (sci/query-units storage tmp-root {:module "src.example.import_modes" :limit 20})
        helper-id (some->> helper-units
                           (filter #(= "src.example.helpers/normalizeHelper" (:symbol %)))
                           first
                           :unit_id)
        default-id (some->> default-units
                            (filter #(= "src.example.default_normalize/normalizeDefault" (:symbol %)))
                            first
                            :unit_id)
        expr-id (some->> import-mode-units
                         (filter #(= "src.example.import_modes/normalizeExpr" (:symbol %)))
                         first
                         :unit_id)
        helper-callers (sci/query-callers storage tmp-root helper-id {:limit 20})
        default-callers (sci/query-callers storage tmp-root default-id {:limit 20})]
    (is helper-id)
    (is default-id)
    (is expr-id)
    (is (some #(= "src.example.import_modes/processImports" (:symbol %)) helper-callers))
    (is (some #(= "src.example.import_modes.ImportService#processNamespace" (:symbol %)) helper-callers))
    (is (some #(= "src.example.import_modes/normalizeExpr" (:symbol %)) helper-callers))
    (is (some #(= "src.example.import_modes/processImports" (:symbol %)) default-callers))
    (is (some #(= "src.example.import_modes.ImportService#normalizeStatic" (:symbol %)) default-callers))))

(deftest typescript-this-and-class-qualified-calls-stay-local-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-local-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        import-mode-units (sci/query-units storage tmp-root {:module "src.example.import_modes.ImportService" :limit 20})
        collision-units (sci/query-units storage tmp-root {:module "src.example.collision_modes.CollisionService" :limit 20})
        helper-units (sci/query-units storage tmp-root {:module "src.example.helpers" :limit 20})
        local-method-id (some->> import-mode-units
                                 (filter #(= "src.example.import_modes.ImportService#normalizeLocal" (:symbol %)))
                                 first
                                 :unit_id)
        static-method-id (some->> import-mode-units
                                  (filter #(= "src.example.import_modes.ImportService#normalizeStatic" (:symbol %)))
                                  first
                                  :unit_id)
        collision-local-id (some->> collision-units
                                    (filter #(= "src.example.collision_modes.CollisionService#normalizeHelper" (:symbol %)))
                                    first
                                    :unit_id)
        helper-id (some->> helper-units
                           (filter #(= "src.example.helpers/normalizeHelper" (:symbol %)))
                           first
                           :unit_id)
        local-callers (sci/query-callers storage tmp-root local-method-id {:limit 20})
        static-callers (sci/query-callers storage tmp-root static-method-id {:limit 20})
        collision-local-callers (sci/query-callers storage tmp-root collision-local-id {:limit 20})
        helper-callers (sci/query-callers storage tmp-root helper-id {:limit 20})]
    (is local-method-id)
    (is static-method-id)
    (is collision-local-id)
    (is helper-id)
    (is (some #(= "src.example.import_modes.ImportService#processThis" (:symbol %)) local-callers))
    (is (some #(= "src.example.import_modes.ImportService#processStaticCall" (:symbol %)) static-callers))
    (is (some #(= "src.example.collision_modes.CollisionService#processThis" (:symbol %)) collision-local-callers))
    (is (some #(= "src.example.collision_modes.CollisionService#processNamespace" (:symbol %)) helper-callers))
    (is (not-any? #(= "src.example.collision_modes.CollisionService#processThis" (:symbol %)) helper-callers))))

(deftest typescript-object-method-and-class-field-arrow-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-object-methods" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        object-units (sci/query-units storage tmp-root {:module "src.example.object_modes.formatters" :limit 20})
        field-units (sci/query-units storage tmp-root {:module "src.example.field_methods.FieldService" :limit 20})
        object-id (some->> object-units
                           (filter #(= "src.example.object_modes.formatters#normalizeObject" (:symbol %)))
                           first
                           :unit_id)
        field-id (some->> field-units
                          (filter #(= "src.example.field_methods.FieldService#normalizeField" (:symbol %)))
                          first
                          :unit_id)
        object-callers (sci/query-callers storage tmp-root object-id {:limit 20})
        field-callers (sci/query-callers storage tmp-root field-id {:limit 20})]
    (is object-id)
    (is field-id)
    (is (some #(= "src.example.object_modes/processObject" (:symbol %)) object-callers))
    (is (some #(= "src.example.field_methods.FieldService#processField" (:symbol %)) field-callers))))

(deftest typescript-default-export-alias-and-re-export-chain-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-default-alias" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        alias-units (sci/query-units storage tmp-root {:module "src.example.default_alias" :limit 20})
        normalize-units (sci/query-units storage tmp-root {:module "src.example.normalize" :limit 20})
        barrel-units (sci/query-units storage tmp-root {:module "src.example.barrel" :limit 20})
        default-alias-id (some->> alias-units
                                  (filter #(= "src.example.default_alias/normalizeAlias" (:symbol %)))
                                  first
                                  :unit_id)
        normalize-id (some->> normalize-units
                              (filter #(= "src.example.normalize/normalizeOrder" (:symbol %)))
                              first
                              :unit_id)
        barrel-id (some->> barrel-units
                           (filter #(= "src.example.barrel/exportedNormalize" (:symbol %)))
                           first
                           :unit_id)
        default-callers (sci/query-callers storage tmp-root default-alias-id {:limit 20})
        normalize-callers (sci/query-callers storage tmp-root normalize-id {:limit 20})
        barrel-callers (sci/query-callers storage tmp-root barrel-id {:limit 20})]
    (is default-alias-id)
    (is normalize-id)
    (is barrel-id)
    (is (some #(= "src.example.default_alias_consumer/processDefaultAlias" (:symbol %)) default-callers))
    (is (some #(= "src.example.barrel/exportedNormalize" (:symbol %)) normalize-callers))
    (is (some #(= "src.example.re_export_consumer/processReExport" (:symbol %)) barrel-callers))))

(deftest typescript-duplicate-re-export-lines-keep-distinct-start-lines-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-duplicate-barrel" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "src/example/normalize.ts"
                       "export function normalizeOrder(orderId: string): string {\n  return orderId.trim();\n}\n")
        _ (write-file! tmp-root "src/example/duplicate_barrel.ts"
                       "export { normalizeOrder as exportedNormalize } from \"./normalize\";\nexport { normalizeOrder as exportedNormalize } from \"./normalize\";\n")
        lines (-> (io/file tmp-root "src/example/duplicate_barrel.ts") slurp str/split-lines vec)
        parsed (ts-language/parse-file tmp-root "src/example/duplicate_barrel.ts" lines {})]
    (is (= [1 2]
           (->> (:units parsed)
                (filter #(= "src.example.duplicate_barrel/exportedNormalize" (:symbol %)))
                (map :start_line)
                sort
                vec)))))

(deftest typescript-advanced-surfaces-still-keep-low-capability-ceiling-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-typescript-capability" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/resolve-context-detail
                index
                {:api_version "1.0"
                 :schema_version "1.0"
                 :intent {:purpose "code_understanding"
                          :details "Locate TypeScript default-alias consumer."}
                 :targets {:symbols ["src.example.default_alias_consumer/processDefaultAlias"]
                           :paths ["src/example/default_alias_consumer.ts"]}
                 :constraints {:token_budget 1200
                               :max_raw_code_level "enclosing_unit"
                               :freshness "current_snapshot"}
                 :hints {:prefer_definitions_over_callers true}
                 :options {:include_tests false
                           :include_impact_hints true
                           :allow_raw_code_escalation false}
                 :trace {:trace_id "66666666-6666-4666-8666-666666666666"
                         :request_id "runtime-test-ts-capability-001"
                         :actor_id "test_runner"}})]
    (is (= "low" (get-in result [:context_packet :capabilities :selected_language_strengths "typescript"])))
    (is (= "low" (get-in result [:context_packet :capabilities :confidence_ceiling])))
    (is (= "low" (get-in result [:context_packet :confidence :level])))))

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
          result (sci/resolve-context-detail pinned-stale sample-query)]
      (is (true? (get-in result [:context_packet :capabilities :index_stale])))
      (is (true? (get-in result [:context_packet :capabilities :snapshot_pinned])))
      (is (= "storage_pinned" (get-in result [:context_packet :capabilities :index_provenance_source])))
      (is (= "autonomy_blocked" (get-in result [:guardrail_assessment :autonomy_posture])))
      (is (some #(= "stale_index" (:code %))
                (get-in result [:guardrail_assessment :blocking_reasons]))))))

(deftest staged-selection-stays-snapshot-bound-and-detail-fetch-is-idempotent-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-staged-snapshot-bound" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        selection-cache (atom {:max_entries 8})
        index-a (sci/create-index {:root_path tmp-root
                                   :selection_cache selection-cache})
        selection (sci/resolve-context index-a sample-query)
        _ (write-file! tmp-root
                       "src/my/app/order.clj"
                       "(ns my.app.order)\n\n(defn process-order [ctx order]\n  :mutated)\n\n(defn validate-order [order]\n  :mutated)\n")
        index-b (sci/create-index {:root_path tmp-root
                                   :selection_cache selection-cache})
        detail-1 (sci/fetch-context-detail index-b {:selection_id (:selection_id selection)
                                                    :snapshot_id (:snapshot_id selection)
                                                    :detail_level "enclosing_unit"})
        detail-2 (sci/fetch-context-detail index-b {:selection_id (:selection_id selection)
                                                    :snapshot_id (:snapshot_id selection)
                                                    :detail_level "enclosing_unit"})
        raw-content (str/join "\n" (map :content (:raw_context detail-1)))]
    (is (str/includes? raw-content "str/join"))
    (is (not (str/includes? raw-content ":mutated")))
    (is (= detail-1 detail-2))))

(deftest literal-file-slice-reads-current-snapshot-range-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-literal-slice-current" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        result (sci/literal-file-slice index {:snapshot_id (:snapshot_id index)
                                              :path "src/my/app/order.clj"
                                              :start_line 4
                                              :end_line 6})]
    (is (= "1.0" (:api_version result)))
    (is (= "literal_slice" (:projection_profile result)))
    (is (= {:start_line 4 :end_line 6} (:requested_range result)))
    (is (= {:start_line 4 :end_line 6} (:returned_range result)))
    (is (= 3 (:line_count result)))
    (is (false? (:truncated result)))
    (is (str/includes? (:content result) "process-order"))
    (is (str/includes? (:content result) "str/join"))))

(deftest literal-file-slice-stays-selection-bound-across-live-file-changes-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-literal-slice-selection-bound" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        selection-cache (atom {:max_entries 8})
        index-a (sci/create-index {:root_path tmp-root
                                   :selection_cache selection-cache})
        selection (sci/resolve-context index-a sample-query)
        _ (write-file! tmp-root
                       "src/my/app/order.clj"
                       "(ns my.app.order)\n\n(defn process-order [ctx order]\n  :mutated)\n\n(defn validate-order [order]\n  :mutated)\n")
        index-b (sci/create-index {:root_path tmp-root
                                   :selection_cache selection-cache})
        literal-old (sci/literal-file-slice index-b {:selection_id (:selection_id selection)
                                                     :snapshot_id (:snapshot_id selection)
                                                     :path "src/my/app/order.clj"
                                                     :start_line 4
                                                     :end_line 6})
        literal-current (sci/literal-file-slice index-b {:snapshot_id (:snapshot_id index-b)
                                                         :path "src/my/app/order.clj"
                                                         :start_line 3
                                                         :end_line 6})]
    (is (= (:selection_id selection) (:selection_id literal-old)))
    (is (str/includes? (:content literal-old) "str/join"))
    (is (not (str/includes? (:content literal-old) ":mutated")))
    (is (str/includes? (:content literal-current) ":mutated"))))

(deftest literal-file-slice-clamps-large-line-spans-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-literal-slice-truncation" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        big-content (str "(ns my.app.big-file)\n\n(defn giant []\n"
                         (apply str (for [idx (range 450)]
                                      (str "  ;; line-" idx "\n")))
                         "  :ok)\n")
        _ (write-file! tmp-root "src/my/app/big_file.clj" big-content)
        index (sci/create-index {:root_path tmp-root})
        result (sci/literal-file-slice index {:snapshot_id (:snapshot_id index)
                                              :path "src/my/app/big_file.clj"
                                              :start_line 2
                                              :end_line 450})]
    (is (true? (:truncated result)))
    (is (= "line_cap_exceeded" (:truncation_reason result)))
    (is (= 400 (:line_count result)))
    (is (= {:start_line 2 :end_line 401} (:returned_range result)))))

(deftest selection-cache-eviction-surfaces-explicit-error-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-selection-eviction" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        selection-cache (atom {:max_entries 1})
        index (sci/create-index {:root_path tmp-root
                                 :selection_cache selection-cache})
        selection-a (sci/resolve-context index sample-query)
        selection-b (sci/resolve-context index sample-query-python)]
    (is (string? (:selection_id selection-b)))
    (try
      (sci/fetch-context-detail index {:selection_id (:selection_id selection-a)
                                       :snapshot_id (:snapshot_id selection-a)})
      (is false "expected selection eviction")
      (catch clojure.lang.ExceptionInfo e
        (is (= :selection_evicted (:type (ex-data e))))
        (is (= "selection_evicted" (:error_code (ex-data e))))
        (is (= "not_found" (:error_category (ex-data e))))))))

(deftest snapshot-mismatch-surfaces-explicit-error-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-selection-snapshot-mismatch" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        selection-cache (atom {:max_entries 8})
        index (sci/create-index {:root_path tmp-root
                                 :selection_cache selection-cache})
        selection (sci/resolve-context index sample-query)]
    (try
      (sci/fetch-context-detail index {:selection_id (:selection_id selection)
                                       :snapshot_id "wrong-snapshot"})
      (is false "expected snapshot mismatch")
      (catch clojure.lang.ExceptionInfo e
        (is (= :snapshot_mismatch (:type (ex-data e))))
        (is (= "snapshot_mismatch" (:error_code (ex-data e))))
        (is (= "conflict" (:error_category (ex-data e))))))))

(deftest unsupported-api-version-surfaces-explicit-error-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-unsupported-api-version" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})]
    (try
      (sci/resolve-context index (assoc sample-query :api_version "2.0"))
      (is false "expected unsupported api_version")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unsupported_api_version (:type (ex-data e))))
        (is (= "unsupported_api_version" (:error_code (ex-data e))))
        (is (= "client" (:error_category (ex-data e))))
        (is (= "2.0" (get-in (ex-data e) [:details :provided_api_version])))
        (is (= ["1.0"] (get-in (ex-data e) [:details :supported_api_versions])))))))

(deftest staged-detail-fetch-enforces-reserved-budget-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-detail-budget-enforcement" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        low-budget-query (assoc-in sample-query [:constraints :token_budget] 240)
        selection (sci/resolve-context index low-budget-query)
        expansion (sci/expand-context index {:selection_id (:selection_id selection)
                                             :snapshot_id (:snapshot_id selection)
                                             :include_impact_hints true})
        detail (sci/fetch-context-detail index {:selection_id (:selection_id selection)
                                                :snapshot_id (:snapshot_id selection)
                                                :detail_level "enclosing_unit"})]
    (testing "expand stage never exceeds its reserved budget"
      (is (<= (get-in expansion [:budget_summary :returned_tokens])
              (get-in expansion [:budget_summary :reserved_tokens]))))
    (testing "detail stage is shaped down to the reserved budget"
      (is (= 0 (get-in detail [:context_packet :budget :reserved_tokens])))
      (is (empty? (:raw_context detail)))
      (is (empty? (get-in detail [:context_packet :relevant_units])))
      (is (= "budget_exhausted" (get-in detail [:context_packet :budget :stage_result_status])))
      (is (some #{"detail_budget_exhausted"}
                (get-in detail [:context_packet :budget :truncation_flags])))
      (is (<= (get-in detail [:context_packet :budget :returned_tokens])
              (get-in detail [:context_packet :budget :reserved_tokens])))
      (is (= "degraded" (get-in detail [:diagnostics_trace :result :result_status]))))))

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

(deftest clojure-regex-fallback-local-lexical-bindings-beat-global-vars-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-lexical-shadow-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root
                                  :storage storage
                                  :parser_opts {:clojure_engine :regex
                                                :tree_sitter_enabled false}})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 30})]
    (is validate-unit-id)
    (is (some #(= "my.app.lexical-shadow/run-qualified-global" (:symbol %)) callers))
    (is (not-any? #(= "my.app.lexical-shadow/run-param-shadow" (:symbol %)) callers))
    (is (not-any? #(= "my.app.lexical-shadow/run-let-shadow" (:symbol %)) callers))
    (is (not-any? #(= "my.app.lexical-shadow/run-destructured-shadow" (:symbol %)) callers))
    (is (not-any? #(= "my.app.lexical-shadow/run-when-let-shadow" (:symbol %)) callers))
    (is (not-any? #(= "my.app.lexical-shadow/run-doseq-shadow" (:symbol %)) callers))))

(deftest clojure-regex-fallback-as-thread-local-binding-does-not-leak-global-call-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-as-thread-shadow-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root
                                  :storage storage
                                  :parser_opts {:clojure_engine :regex
                                                :tree_sitter_enabled false}})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 30})]
    (is validate-unit-id)
    (is (not-any? #(= "my.app.lexical-shadow/run-as-thread-shadow" (:symbol %)) callers))))

(deftest clojure-alias-heavy-same-name-var-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-alias-same-name-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        primary-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        alternate-units (sci/query-units storage tmp-root {:module "my.app.alt-order" :limit 20})
        primary-validate-id (some->> primary-units
                                     (filter #(= "my.app.order/validate-order" (:symbol %)))
                                     first
                                     :unit_id)
        alternate-validate-id (some->> alternate-units
                                       (filter #(= "my.app.alt-order/validate-order" (:symbol %)))
                                       first
                                       :unit_id)
        primary-callers (sci/query-callers storage tmp-root primary-validate-id {:limit 20})
        alternate-callers (sci/query-callers storage tmp-root alternate-validate-id {:limit 20})]
    (is primary-validate-id)
    (is alternate-validate-id)
    (is (some #(= "my.app.alias-workflow/prepare-primary" (:symbol %)) primary-callers))
    (is (not-any? #(= "my.app.alias-workflow/prepare-alternate" (:symbol %)) primary-callers))
    (is (some #(= "my.app.alias-workflow/prepare-alternate" (:symbol %)) alternate-callers))
    (is (not-any? #(= "my.app.alias-workflow/prepare-primary" (:symbol %)) alternate-callers))))

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
             "my.app.macros/emit-top-level-validation"
             "my.app.macros/helper-side-effect"
             "my.app.macros/with-letfn-validation"
             "my.app.macros/with-threaded-validation"
             "my.app.macros/with-threaded-ambiguous-validation"
             "my.app.macros/with-top-level-helper-validation"
             "my.app.macros/with-branching-validation"
             "my.app.macros/with-ambiguous-branch-validation"
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

(deftest clojure-threaded-macro-generated-ownership-adds-caller-edge-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-threaded-macro-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.workflow/prepare-threaded-order" (:symbol %)) callers))))

(deftest clojure-ambiguous-threaded-macro-generated-ownership-stays-conservative-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-threaded-ambiguous-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        primary-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        alternate-units (sci/query-units storage tmp-root {:module "my.app.alt-order" :limit 20})
        primary-validate-id (some->> primary-units
                                     (filter #(= "my.app.order/validate-order" (:symbol %)))
                                     first
                                     :unit_id)
        alternate-validate-id (some->> alternate-units
                                       (filter #(= "my.app.alt-order/validate-order" (:symbol %)))
                                       first
                                       :unit_id)
        primary-callers (sci/query-callers storage tmp-root primary-validate-id {:limit 20})
        alternate-callers (sci/query-callers storage tmp-root alternate-validate-id {:limit 20})]
    (is primary-validate-id)
    (is alternate-validate-id)
    (is (not-any? #(= "my.app.workflow/prepare-threaded-ambiguous-order" (:symbol %)) primary-callers))
    (is (not-any? #(= "my.app.workflow/prepare-threaded-ambiguous-order" (:symbol %)) alternate-callers))))

(deftest clojure-top-level-helper-generated-ownership-adds-caller-edge-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-top-level-helper-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (some #(= "my.app.workflow/prepare-top-level-helper-order" (:symbol %)) callers))))

(deftest clojure-ambiguous-branch-generated-ownership-stays-conservative-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-ambiguous-branch-ownership" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        primary-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        alternate-units (sci/query-units storage tmp-root {:module "my.app.alt-order" :limit 20})
        primary-validate-id (some->> primary-units
                                     (filter #(= "my.app.order/validate-order" (:symbol %)))
                                     first
                                     :unit_id)
        alternate-validate-id (some->> alternate-units
                                       (filter #(= "my.app.alt-order/validate-order" (:symbol %)))
                                       first
                                       :unit_id)
        primary-callers (sci/query-callers storage tmp-root primary-validate-id {:limit 20})
        alternate-callers (sci/query-callers storage tmp-root alternate-validate-id {:limit 20})]
    (is primary-validate-id)
    (is alternate-validate-id)
    (is (not-any? #(= "my.app.workflow/prepare-ambiguous-order" (:symbol %)) primary-callers))
    (is (not-any? #(= "my.app.workflow/prepare-ambiguous-order" (:symbol %)) alternate-callers))))

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

(deftest clojure-top-level-helper-side-effects-do-not-leak-ownership-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-top-level-helper-side-effects" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        order-units (sci/query-units storage tmp-root {:module "my.app.order" :limit 20})
        validate-unit-id (some->> order-units (filter #(= "my.app.order/validate-order" (:symbol %))) first :unit_id)
        callers (sci/query-callers storage tmp-root validate-unit-id {:limit 20})]
    (is validate-unit-id)
    (is (not-any? #(= "my.app.workflow/prepare-side-effect-order" (:symbol %)) callers))))

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
               :api_version "1.0"
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
                         :allow_raw_code_escalation false}
               :trace {:trace_id "55555555-5555-4555-8555-555555555555"
                       :request_id "runtime-test-clj-dispatch-001"
                       :actor_id "test_runner"}}
        result (sci/resolve-context-detail index query)]
    (is pickup-method)
    (is (= (:unit_id pickup-method)
           (get-in result [:context_packet :relevant_units 0 :unit_id])))
    (is (= "high" (get-in result [:context_packet :confidence :level])))))

(deftest clojure-literal-defmulti-dispatch-links-callers-to-specific-defmethod-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-literal-defmulti-dispatch" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        shipping-units (sci/query-units storage tmp-root {:module "my.app.shipping" :limit 20})
        pickup-method-id (some->> shipping-units
                                  (filter #(and (= "my.app.shipping/route-order" (:symbol %))
                                                (= ":pickup" (:dispatch_value %))))
                                  first
                                  :unit_id)
        delivery-method-id (some->> shipping-units
                                    (filter #(and (= "my.app.shipping/route-order" (:symbol %))
                                                  (= ":delivery" (:dispatch_value %))))
                                    first
                                    :unit_id)
        pickup-callers (sci/query-callers storage tmp-root pickup-method-id {:limit 20})
        delivery-callers (sci/query-callers storage tmp-root delivery-method-id {:limit 20})]
    (is pickup-method-id)
    (is delivery-method-id)
    (is (some #(= "my.app.shipping/plan-pickup" (:symbol %)) pickup-callers))
    (is (not-any? #(= "my.app.shipping/plan-delivery" (:symbol %)) pickup-callers))
    (is (some #(= "my.app.shipping/plan-delivery" (:symbol %)) delivery-callers))
    (is (not-any? #(= "my.app.shipping/plan-pickup" (:symbol %)) delivery-callers))))

(deftest clojure-defprotocol-method-units-and-caller-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-clj-defprotocol-units" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        protocol-units (sci/query-units storage tmp-root {:module "my.app.protocols" :limit 20})
        format-order-id (some->> protocol-units
                                 (filter #(= "my.app.protocols/format-order" (:symbol %)))
                                 first
                                 :unit_id)
        callers (sci/query-callers storage tmp-root format-order-id {:limit 20})]
    (is (some #(= "my.app.protocols/format-order" (:symbol %)) protocol-units))
    (is (some #(= "my.app.protocols/format-summary" (:symbol %)) protocol-units))
    (is format-order-id)
    (is (some #(= "my.app.protocols/render-order" (:symbol %)) callers))))

(deftest tree-sitter-parser-path-test
  (let [clj-grammar (System/getenv "SEMIDX_TREE_SITTER_CLOJURE_GRAMMAR_PATH")
        java-grammar (System/getenv "SEMIDX_TREE_SITTER_JAVA_GRAMMAR_PATH")
        elixir-grammar (System/getenv "SEMIDX_TREE_SITTER_ELIXIR_GRAMMAR_PATH")
        ts-grammar (System/getenv "SEMIDX_TREE_SITTER_TYPESCRIPT_GRAMMAR_PATH")]
    (if (and (seq clj-grammar) (seq java-grammar) (seq elixir-grammar) (seq ts-grammar))
      (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-tree-sitter-test" (make-array java.nio.file.attribute.FileAttribute 0)))
            _ (create-sample-repo! tmp-root)
            index (sci/create-index {:root_path tmp-root
                                     :parser_opts {:clojure_engine :tree-sitter
                                                   :elixir_engine :tree-sitter
                                                   :java_engine :tree-sitter
                                                   :typescript_engine :tree-sitter
                                                   :tree_sitter_enabled true
                                                   :tree_sitter_grammars {:clojure clj-grammar
                                                                          :elixir elixir-grammar
                                                                          :java java-grammar
                                                                          :typescript ts-grammar}}})
            clj-diags (get-in index [:files "src/my/app/order.clj" :diagnostics])
            ex-diags (get-in index [:files "lib/my_app/order.ex" :diagnostics])
            java-diags (get-in index [:files "src/com/acme/CheckoutService.java" :diagnostics])
            ts-diags (get-in index [:files "src/example/main.ts" :diagnostics])]
        (is (some #(= "tree_sitter_active" (:code %)) clj-diags))
        (is (some #(= "tree_sitter_active" (:code %)) ex-diags))
        (is (some #(= "tree_sitter_active" (:code %)) java-diags))
        (is (some #(= "tree_sitter_active" (:code %)) ts-diags)))
      (is true "Tree-sitter grammar paths are not configured for Clojure/Elixir/Java/TypeScript; skipping tree-sitter parser test."))))

(deftest tree-sitter-elixir-parity-test
  (let [elixir-grammar (System/getenv "SEMIDX_TREE_SITTER_ELIXIR_GRAMMAR_PATH")]
    (if (seq elixir-grammar)
      (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-tree-sitter-elixir-parity" (make-array java.nio.file.attribute.FileAttribute 0)))
            _ (create-sample-repo! tmp-root)
            storage (sci/in-memory-storage)
            _index (sci/create-index {:root_path tmp-root
                                      :storage storage
                                      :parser_opts {:elixir_engine :tree-sitter
                                                    :tree_sitter_enabled true
                                                    :tree_sitter_grammars {:elixir elixir-grammar}}})
            local-units (sci/query-units storage tmp-root {:module "MyApp.PipelineFormatter" :limit 20})
            formatter-units (sci/query-units storage tmp-root {:module "MyApp.Formatter" :limit 20})
            nested-units (sci/query-units storage tmp-root {:module "MyApp.NestedClient.Nested" :limit 20})
            local-normalize-id (some->> local-units
                                        (filter #(= "MyApp.PipelineFormatter/normalize" (:symbol %)))
                                        first
                                        :unit_id)
            imported-normalize-id (some->> formatter-units
                                           (filter #(= "MyApp.Formatter/normalize" (:symbol %)))
                                           first
                                           :unit_id)
            nested-normalize-id (some->> nested-units
                                         (filter #(= "MyApp.NestedClient.Nested/normalize" (:symbol %)))
                                         first
                                         :unit_id)
            local-callers (sci/query-callers storage tmp-root local-normalize-id {:limit 20})
            imported-callers (sci/query-callers storage tmp-root imported-normalize-id {:limit 20})
            nested-callers (sci/query-callers storage tmp-root nested-normalize-id {:limit 20})]
        (is local-normalize-id)
        (is imported-normalize-id)
        (is nested-normalize-id)
        (is (some #(= "MyApp.PipelineFormatter/process_pipeline" (:symbol %)) local-callers))
        (is (some #(= "MyApp.PipelineFormatter/process_with" (:symbol %)) local-callers))
        (is (some #(= "MyApp.PipelineFormatter/process_capture" (:symbol %)) local-callers))
        (is (not-any? #(= "MyApp.PipelineFormatter/process_capture" (:symbol %)) imported-callers))
        (is (some #(= "MyApp.NestedClient/process_nested" (:symbol %)) nested-callers)))
      (is true "SEMIDX_TREE_SITTER_ELIXIR_GRAMMAR_PATH is not set; skipping Elixir tree-sitter parity test."))))

(deftest tree-sitter-typescript-advanced-surface-parity-test
  (let [ts-grammar (System/getenv "SEMIDX_TREE_SITTER_TYPESCRIPT_GRAMMAR_PATH")]
    (if (seq ts-grammar)
      (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-tree-sitter-typescript-parity" (make-array java.nio.file.attribute.FileAttribute 0)))
            _ (create-sample-repo! tmp-root)
            storage (sci/in-memory-storage)
            _index (sci/create-index {:root_path tmp-root
                                      :storage storage
                                      :parser_opts {:typescript_engine :tree-sitter
                                                    :tree_sitter_enabled true
                                                    :tree_sitter_grammars {:typescript ts-grammar}}})
            object-units (sci/query-units storage tmp-root {:module "src.example.object_modes.formatters" :limit 20})
            field-units (sci/query-units storage tmp-root {:module "src.example.field_methods.FieldService" :limit 20})
            barrel-units (sci/query-units storage tmp-root {:module "src.example.barrel" :limit 20})
            default-alias-id (some->> (sci/query-units storage tmp-root {:module "src.example.default_alias" :limit 20})
                                      (filter #(= "src.example.default_alias/normalizeAlias" (:symbol %)))
                                      first
                                      :unit_id)
            alias-callers (sci/query-callers storage tmp-root default-alias-id {:limit 20})]
        (is (some #(= "src.example.object_modes.formatters#normalizeObject" (:symbol %)) object-units))
        (is (some #(= "src.example.field_methods.FieldService#normalizeField" (:symbol %)) field-units))
        (is (some #(= "src.example.barrel/exportedNormalize" (:symbol %)) barrel-units))
        (is (some #(= "src.example.default_alias_consumer/processDefaultAlias" (:symbol %)) alias-callers)))
      (is true "SEMIDX_TREE_SITTER_TYPESCRIPT_GRAMMAR_PATH is not set; skipping TypeScript tree-sitter parity test."))))

(deftest parsed-files-carry-semantic-pipeline-metadata-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-semantic-pipeline-meta" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        index (sci/create-index {:root_path tmp-root})
        py-file (some (fn [[_ file]] (when (= "python" (:language file)) file)) (:files index))
        ts-file (get-in index [:files "src/example/main.ts"])
        py-unit (some->> (:unit_order index)
                         (map #(get (:units index) %))
                         (filter #(= "python" (get-in index [:files (:path %) :language])))
                         first)
        ts-unit (some->> (:unit_order index)
                         (map #(get (:units index) %))
                         (filter #(= "src/example/main.ts" (:path %)))
                         first)]
    (is (= "v1" (get-in py-file [:semantic_pipeline :version])))
    (is (= "python" (get-in py-file [:semantic_pipeline :language])))
    (is (= "v1" (:semantic_pipeline py-unit)))
    (is (= "v1" (:semantic_pipeline ts-unit)))
    (is (= "typescript" (get-in ts-file [:semantic_pipeline :language])))))

(deftest java-multi-level-superclass-resolution-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-java-multi-super" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "src/com/acme/BaseNormalizer.java"
                       "package com.acme;\n\npublic class BaseNormalizer {\n  protected String normalize(String id) {\n    return id.trim();\n  }\n}\n")
        _ (write-file! tmp-root "src/com/acme/MidNormalizer.java"
                       "package com.acme;\n\npublic class MidNormalizer extends BaseNormalizer {\n}\n")
        _ (write-file! tmp-root "src/com/acme/DeepNormalizer.java"
                       "package com.acme;\n\npublic class DeepNormalizer extends MidNormalizer {\n  public String processSuper(String id) {\n    return super.normalize(id);\n  }\n\n  public String processInherited(String id) {\n    return normalize(id);\n  }\n}\n")
        storage (sci/in-memory-storage)
        _index (sci/create-index {:root_path tmp-root :storage storage})
        base-id (some->> (sci/query-units storage tmp-root {:module "com.acme.BaseNormalizer" :limit 20})
                         (filter #(= "com.acme.BaseNormalizer#normalize" (:symbol %)))
                         first
                         :unit_id)
        callers (sci/query-callers storage tmp-root base-id {:limit 20})]
    (is base-id)
    (is (some #(= "com.acme.DeepNormalizer#processSuper" (:symbol %)) callers))
    (is (some #(= "com.acme.DeepNormalizer#processInherited" (:symbol %)) callers))))

(deftest language-entry-modules-smoke-test
  (let [clj-parsed (clj-language/parse-file "." "src/example/core.clj"
                                            ["(ns example.core)"
                                             "(defn run [] 1)"]
                                            {:clojure_engine :regex})
        java-parsed (java-language/parse-file "." "src/example/Main.java"
                                              ["package example;"
                                               "public class Main {"
                                               "  public String run() {"
                                               "    return normalize();"
                                               "  }"
                                               "  private String normalize() {"
                                               "    return \"ok\";"
                                               "  }"
                                               "}"]
                                              {:java_engine :regex})
        ex-parsed (ex-language/parse-file "." "lib/example.ex"
                                          ["defmodule Example do"
                                           "  def run(value) do"
                                           "    normalize(value)"
                                           "  end"
                                           "end"]
                                          {})
        lua-parsed (lua-language/parse-file "." "app/example.lua"
                                            ["local helpers = require(\"app.helpers\")"
                                             ""
                                             "local M = {}"
                                             ""
                                             "function M.run(value)"
                                             "  return helpers.normalize(value)"
                                             "end"
                                             ""
                                             "return M"]
                                            {})
        py-parsed (py-language/parse-file "." "app/example.py"
                                          ["def run(value):"
                                           "    return normalize(value)"
                                           ""
                                           "def normalize(value):"
                                           "    return value"]
                                          {})]
    (is (= "clojure" (:language clj-parsed)))
    (is (= "java" (:language java-parsed)))
    (is (= "elixir" (:language ex-parsed)))
    (is (= "lua" (:language lua-parsed)))
    (is (= "python" (:language py-parsed)))))

(deftest lua-parser-module-table-and-method-linking-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-lua-parser-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "app/helpers.lua"
                       "local M = {}\n\nfunction M.normalize(value)\n  return value\nend\n\nreturn M\n")
        _ (write-file! tmp-root "app/main.lua"
                       "local helpers = require(\"app.helpers\")\n\nlocal M = {}\n\nfunction M:normalize_local(value)\n  return value\nend\n\nfunction M.call_local(self, value)\n  return self:normalize_local(value)\nend\n\nfunction M.run(value)\n  return helpers.normalize(value)\nend\n\nreturn M\n")
        storage (sci/in-memory-storage)
        index (sci/create-index {:root_path tmp-root :storage storage})
        main-units (sci/query-units storage tmp-root {:module "app.main" :limit 20})
        helper-id (some->> (sci/query-units storage tmp-root {:module "app.helpers" :limit 20})
                           (filter #(= "app.helpers/normalize" (:symbol %)))
                           first
                           :unit_id)
        method-id (some->> main-units
                           (filter #(= "app.main#normalize_local" (:symbol %)))
                           first
                           :unit_id)
        helper-callers (sci/query-callers storage tmp-root helper-id {:limit 20})
        method-callers (sci/query-callers storage tmp-root method-id {:limit 20})]
    (is (= "lua" (get-in index [:files "app/main.lua" :language])))
    (is (= ["app.helpers"] (get-in index [:files "app/main.lua" :imports])))
    (is (some #(= "app.main/run" (:symbol %)) main-units))
    (is (some #(= "app.main/call_local" (:symbol %)) main-units))
    (is (some #(= "app.main#normalize_local" (:symbol %)) main-units))
    (is (some #(= "app.main/run" (:symbol %)) helper-callers))
    (is (some #(= "app.main/call_local" (:symbol %)) method-callers))))

(deftest current-repo-create-index-does-not-crash-test
  (let [root-path (-> (io/file ".") .getCanonicalPath)
        index (sci/create-index {:root_path root-path})]
    (is (string? (:snapshot_id index)))
    (is (seq (:files index)))
    (is (seq (:units index)))))

(deftest evaluation-file-parses-without-cast-crash-test
  (let [root-path (-> (io/file ".") .getCanonicalPath)
        rel-path "src/semidx/runtime/evaluation.clj"
        parsed (adapters/parse-file root-path rel-path {})]
    (is (map? parsed))
    (is (= "clojure" (:language parsed)))
    (is (vector? (:units parsed)))
    (is (vector? (:diagnostics parsed)))
    (is (every? map? (:diagnostics parsed)))))

(deftest parse-file-fallback-finalizes-parse-exceptions-test
  (let [root-path (-> (io/file ".") .getCanonicalPath)
        rel-path "src/semidx/runtime/evaluation.clj"
        parsed (with-redefs [adapters/parse-clojure-file (fn [& _]
                                                           (throw (ex-info "boom" {})))]
                 (adapters/parse-file root-path rel-path {}))]
    (is (map? parsed))
    (is (= "clojure" (:language parsed)))
    (is (= "fallback" (:parser_mode parsed)))
    (is (vector? (:units parsed)))
    (is (vector? (:diagnostics parsed)))
    (is (some #(and (= "parser_fallback" (:code %))
                    (= "parse_exception" (:summary %)))
              (:diagnostics parsed)))))

(deftest create-index-no-supported-languages-guidance-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-no-lang" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "README.md" "# empty")
        ex (try
             (sci/create-index {:root_path tmp-root})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is ex)
    (is (= :no_supported_languages_found (:type (ex-data ex))))
    (is (= "awaiting_language_selection" (get-in (ex-data ex) [:details :activation_state])))
    (is (= ["clojure" "java" "elixir" "python" "typescript" "lua"]
           (get-in (ex-data ex) [:details :supported_languages])))
    (is (string? (get-in (ex-data ex) [:details :selection_hint])))))

(deftest create-index-manual-core-language-selection-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-runtime-manual-language" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! tmp-root "README.md" "# bootstrap")
        index (sci/create-index {:root_path tmp-root
                                 :language_policy {:allow_languages ["python"]}})]
    (is (= ["python"] (:active_languages index)))
    (is (= [] (:detected_languages index)))
    (is (= "manual_language_selection" (get-in index [:index_lifecycle :rebuild_reason])))
    (is (true? (:manual_language_selection index)))
    (is (= "ready" (:activation_state index)))
    (is (empty? (:files index)))))

(deftest postgres-storage-roundtrip-test
  (if-let [jdbc-url (System/getenv "SEMIDX_TEST_POSTGRES_URL")]
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
    (is true "SEMIDX_TEST_POSTGRES_URL is not set; skipping postgres storage smoke test.")))
