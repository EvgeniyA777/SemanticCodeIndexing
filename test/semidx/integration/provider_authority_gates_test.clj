(ns semidx.integration.provider-authority-gates-test
  "plans/018 Stage 6.5: the gates that decide whether the authority switch may be
  made the default.

  The repository's standing gates all run the default path, which is the right
  thing for them to do and says nothing about the mode this stage is preparing to
  turn on. These compare the two directly on the protected corpus: what the
  switch must never do is lose something the default path found, and what it must
  keep doing is answer a retrieval at all."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest testing is]]
            [semidx.core :as sci]
            [semidx.runtime.retrieval-policy :as policy]))

(def ^:private java-corpus "fixtures/provider-authority/corpus/java")

(defn- build [mode]
  (sci/create-index (cond-> {:root_path java-corpus}
                      mode (assoc :parser_opts {:provider_pipeline mode}))))

(deftest the-switch-loses-nothing-the-default-path-found-test
  (let [off (build nil)
        authority (build "authority")
        off-ids (set (keys (:units off)))
        authority-ids (set (keys (:units authority)))]

    (testing "every unit the default path produced survives the switch"
      (is (empty? (set/difference off-ids authority-ids))
          (str "units lost by the authority path: "
               (pr-str (set/difference off-ids authority-ids)))))

    (testing "the file set is untouched: this stage changes what units are worth,
              not which files are indexed"
      (is (= (set (keys (:files off))) (set (keys (:files authority))))))

    (testing "and every unit it kept carries an authority, since the merge assigns
              one to everything it touches"
      (is (every? :authority (vals (:units authority)))))))

(deftest retrieval-still-answers-under-the-switch-test
  (let [authority (build "authority")
        result (sci/resolve-context authority
                                    {:api_version "1.0"
                                     :schema_version "1.0"
                                     :intent {:purpose "code_understanding"
                                              :details "Locate the order service handle method."}
                                     :targets {:paths ["src/example/OrderService.java"]}
                                     :constraints {:token_budget 1800
                                                   :freshness "current_snapshot"}
                                     :hints {}
                                     :options {:include_tests true}
                                     :trace {:trace_id "22222222-2222-4222-8222-222222222222"
                                             :request_id "req-stage-6-5-gate"}}
                                    {:suppress_usage_metrics true})]
    (is (= "completed" (:result_status result)))
    (is (seq (:focus result)) "a selection that returns nothing is not a passing gate")
    (is (string? (:selection_id result)))))

(deftest the-confidence-ceiling-follows-the-evidence-not-the-lane-test
  (let [authority (build "authority")
        units (vec (vals (:units authority)))
        summary (policy/capability-summary authority units)
        authorities (set (keep :authority units))]
    (testing "whatever the toolchain on this machine produced, the ceiling agrees
              with the evidence rather than with the language's static strength"
      (cond
        (= #{"exact"} authorities)
        (is (= "high" (:confidence_ceiling summary))
            "a wholly exact selection rises above java's static medium")

        (contains? authorities "heuristic")
        (is (contains? #{"low" "medium"} (:confidence_ceiling summary))
            "a selection with heuristic units is capped at or below the lane")

        :else
        (is (some? (:confidence_ceiling summary)))))

    (testing "and the coverage level names what the labels say"
      (is (contains? #{"full" "mixed" "fallback_only"} (:coverage_level summary))))))
