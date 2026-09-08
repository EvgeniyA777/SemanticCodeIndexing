(ns semidx.integration.clojure-onboarding-test
  "plans/023: every language lane carries the same onboarding artifacts. This is
  Clojure's end-to-end regression test — the lane had none before, which is why
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
                               :trace {:trace_id "aaaaaaa1-1111-4111-8111-aaaaaaaaaaaa"
                                       :request_id "clojure-onboarding-test-001"
                                       :actor_id "test_runner"}}))

(defn- selected-symbols [result]
  (set (map :symbol (get-in result [:context_packet :relevant_units]))))

(deftest clojure-adapter-onboarding-regression-test
  (let [root (temp-root "sci-clojure-onboarding-test")
        _ (write-file! root "src/my/app/order.clj"
                       "(ns my.app.order)\n\n(defn normalize [value]\n  (str value))\n\n(defn process-order [order]\n  (normalize (:id order)))\n")
        index (sci/create-index {:root_path root})
        result (resolve-target index "my.app.order/process-order" "src/my/app/order.clj")]

    (testing "the lane indexes its own files as itself"
      (is (= "clojure" (get-in index [:files "src/my/app/order.clj" :language])))
      (is (= "full" (get-in index [:files "src/my/app/order.clj" :parser_mode])))
      (is (= "my.app.order" (get-in index [:files "src/my/app/order.clj" :module]))))

    (testing "both definitions are units, namespace-qualified"
      (let [symbols (set (map :symbol (vals (:units index))))]
        (is (contains? symbols "my.app.order/process-order"))
        (is (contains? symbols "my.app.order/normalize"))))

    (testing "and retrieval returns the requested one"
      (is (contains? (selected-symbols result) "my.app.order/process-order")))))
