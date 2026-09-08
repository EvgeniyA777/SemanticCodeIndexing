(ns semidx.integration.elixir-onboarding-test
  "plans/023: every language lane carries the same onboarding artifacts. This is
  Elixir's end-to-end regression test — the lane had none before, which is why
  the onboarding checklist failed for it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- temp-root [label]
  (str (java.nio.file.Files/createTempDirectory
        label (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- resolve-target [index symbol* path]
  (sci/resolve-context-detail index
                              {:api_version "1.0"
                               :schema_version "1.0"
                               :intent {:purpose "code_understanding"
                                        :details (str "Locate " symbol* ".")}
                               :targets {:symbols [symbol*] :paths [path]}
                               :constraints {:token_budget 1000
                                             :max_raw_code_level "enclosing_unit"
                                             :freshness "current_snapshot"}
                               :hints {:prefer_definitions_over_callers true}
                               :options {:include_tests false
                                         :include_impact_hints true
                                         :allow_raw_code_escalation false}
                               :trace {:trace_id "ddddddd4-4444-4444-8444-dddddddddddd"
                                       :request_id "elixir-onboarding-test-001"
                                       :actor_id "test_runner"}}))

(defn- selected-symbols [result]
  (set (map :symbol (get-in result [:context_packet :relevant_units]))))

(deftest elixir-adapter-onboarding-regression-test
  (let [root (temp-root "sci-elixir-onboarding-test")
        _ (write-file! root "lib/my_app/order.ex"
                       "defmodule MyApp.Order do\n  def normalize(value) do\n    value\n  end\n\n  def process_order(order) do\n    normalize(order)\n  end\nend\n")
        index (sci/create-index {:root_path root})
        result (resolve-target index "MyApp.Order/process_order" "lib/my_app/order.ex")]

    (testing "the lane indexes its own files as itself, through the lazily
              resolved adapter branch"
      (is (= "elixir" (get-in index [:files "lib/my_app/order.ex" :language])))
      (is (= "full" (get-in index [:files "lib/my_app/order.ex" :parser_mode])))
      (is (= "MyApp.Order" (get-in index [:files "lib/my_app/order.ex" :module]))))

    (testing "definitions are module-qualified and carry their arity in the id,
              since Elixir names are only unique with it"
      (let [symbols (set (map :symbol (vals (:units index))))]
        (is (contains? symbols "MyApp.Order/process_order"))
        (is (contains? symbols "MyApp.Order/normalize")))
      (is (some #(re-find #"\$arity1" (str (:unit_id %))) (vals (:units index)))))

    (testing "and retrieval returns the requested function"
      (is (contains? (selected-symbols result) "MyApp.Order/process_order")))))
