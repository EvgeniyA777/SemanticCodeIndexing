(ns semidx.runtime.retrieval-policy-test
  "Confidence ceilings, and what the evidence behind a selection does to them
  (plans/018 Stage 6.2).

  `capability-summary` takes the index only to look up a language for a unit
  that does not carry one, so these units carry theirs and the index is empty."
  (:require [clojure.test :refer [deftest testing is]]
            [semidx.runtime.retrieval-policy :as policy]))

(defn- unit
  ([language parser-mode] (unit language parser-mode nil))
  ([language parser-mode authority]
   (cond-> {:language language
            :path (str "src/a." (if (= "java" language) "java" "ts"))
            :parser_mode parser-mode}
     authority (assoc :authority authority))))

(defn- ceiling [units]
  (:confidence_ceiling (policy/capability-summary {} units)))

(deftest a-static-language-strength-still-applies-without-evidence-test
  (testing "units from before the provider tiers carry no authority, and nothing
            about their ceiling changes"
    (is (= "low" (ceiling [(unit "typescript" "full") (unit "typescript" "full")])))
    (is (= "medium" (ceiling [(unit "java" "full")])))
    (is (= "high" (ceiling [(unit "clojure" "full")])))
    (is (= "low" (ceiling [(unit "java" "fallback")]))
        "a fallback-only selection is low whatever the lane claims")))

(deftest exact-evidence-lifts-a-language-past-its-static-strength-test
  (testing "TypeScript is rated low because a regular expression is guessing, not
            because a SCIP index is: once every selected unit is exact, the static
            claim is the stale one"
    (is (= "high" (ceiling [(unit "typescript" "full" "exact")
                            (unit "typescript" "full" "exact")]))))

  (testing "structural evidence is not exact evidence and does not lift anything"
    (is (= "low" (ceiling [(unit "typescript" "full" "structural")]))))

  (testing "a partly exact selection is as good as its weakest member, which is the
            rule already applied across languages"
    (is (= "low" (ceiling [(unit "typescript" "full" "exact")
                           (unit "typescript" "full" "heuristic")])))
    (is (= "medium" (ceiling [(unit "java" "full" "exact")
                              (unit "java" "full" "heuristic")])))))

(deftest a-fallback-unit-still-caps-the-whole-selection-test
  (testing "mixed coverage caps at medium and a fallback-only language at low,
            even when some other unit is exact"
    (let [summary (policy/capability-summary {} [(unit "typescript" "full" "exact")
                                                 (unit "typescript" "fallback" "heuristic")])]
      (is (= "mixed" (:coverage_level summary)))
      (is (= 1 (:fallback_unit_count summary)))
      (is (= "low" (:confidence_ceiling summary))))))
