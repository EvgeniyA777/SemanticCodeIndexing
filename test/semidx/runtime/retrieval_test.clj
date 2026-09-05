(ns semidx.runtime.retrieval-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.retrieval]
            [semidx.runtime.retrieval-policy :as rp]))

(def ^:private impact-analysis #'semidx.runtime.retrieval/impact-analysis)
(def ^:private perform-raw-fetch #'semidx.runtime.retrieval/perform-raw-fetch)
(def ^:private suggested-token-budget #'semidx.runtime.retrieval/suggested-token-budget)

(defn- sample-lines [n]
  (vec (for [i (range 1 (inc n))]
         (str "line-" i " padded content to make each line meaningfully long for byte accounting"))))

(defn- sample-selection [path line-count]
  {:file_snapshots {path (sample-lines line-count)}})

(defn- sample-unit [path start end]
  {:unit_id (str path "::unit") :path path :start_line start :end_line end})

(defn- escalation-query [level]
  {:options {:allow_raw_code_escalation true}
   :constraints {:max_raw_code_level level}})

(defn- selection-for-impact [confidence capabilities]
  {:selection_id "selection-1"
   :snapshot_id "snapshot-1"
   :query {:intent {:purpose "change_impact"
                    :details "Find impact for ControlledRuntime"}
           :targets {:symbols ["ControlledRuntime"]}}
   :policy (rp/resolve-policy nil nil)
   :bound_index {}
   :focus [{:unit_id "src/adapter.zig::wrong/OllamaAdapter"
            :symbol "wrong/OllamaAdapter"
            :path "src/adapter.zig"
            :module "wrong"}]
   :confidence confidence
   :capabilities capabilities})

(deftest impact-analysis-degrades-unsafe-seed-test
  (let [query {:intent {:purpose "change_impact"
                        :details "Find impact for ControlledRuntime"}
               :targets {:symbols ["ControlledRuntime"]}}
        selection (selection-for-impact
                   {:schema_version "1.0"
                    :level "low"
                    :score 0.2
                    :reasons []
                    :warnings [{:code "target_ambiguous"
                                :summary "ambiguous"}]
                    :missing_evidence [{:code "exact_target_resolution_missing"
                                        :summary "missing"}]}
                   {:confidence_ceiling "low"
                    :coverage_level "full"
                    :index_stale false})
        build-called? (atom false)]
    (with-redefs [semidx.runtime.retrieval/resolve-context
                  (fn [_index _query _opts]
                    {:selection_id "selection-1" :snapshot_id "snapshot-1"})
                  semidx.runtime.retrieval/ensure-selection!
                  (fn [_index _selection-id _snapshot-id] selection)
                  semidx.runtime.retrieval/build-impact-hints
                  (fn [& _]
                    (reset! build-called? true)
                    {:callers ["should-not-return"]})]
      (let [result (impact-analysis {} query {})]
        (is (= "degraded" (:result_status result)))
        (is (= [] (:callers result)))
        (is (= [] (:dependents result)))
        (is (false? @build-called?))
        (is (= #{"impact_seed_confidence_low"
                 "impact_seed_ambiguous"
                 "impact_seed_exact_target_missing"
                 "impact_seed_capability_limited"}
               (set (map :code (:degradations result)))))
        (is (= "autonomy_blocked"
               (get-in result [:guardrails :autonomy_posture])))))))

(deftest impact-analysis-keeps-trusted-seed-test
  (let [query {:intent {:purpose "change_impact"
                        :details "Find impact for normalize-mcp-query"}
               :targets {:symbols ["semidx.mcp.core/normalize-mcp-query"]}}
        selection (selection-for-impact
                   {:schema_version "1.0"
                    :level "high"
                    :score 0.9
                    :reasons [{:code "exact_target_resolved"
                               :summary "resolved"}]
                    :warnings []
                    :missing_evidence []}
                   {:confidence_ceiling "high"
                    :coverage_level "full"
                    :index_stale false})]
    (with-redefs [semidx.runtime.retrieval/resolve-context
                  (fn [_index _query _opts]
                    {:selection_id "selection-1" :snapshot_id "snapshot-1"})
                  semidx.runtime.retrieval/ensure-selection!
                  (fn [_index _selection-id _snapshot-id] selection)
                  semidx.runtime.retrieval/build-impact-hints
                  (fn [_index _selected]
                    {:callers ["caller"]
                     :dependents []
                     :related_tests []
                     :risky_neighbors []})
                  semidx.runtime.state-invariants/assemble
                  (fn [& _] nil)]
      (let [result (impact-analysis {} query {})]
        (is (= ["caller"] (:callers result)))
        (is (nil? (:result_status result)))
        (is (nil? (:degradations result)))))))

(deftest oversized-unit-returns-partial-snippet-instead-of-empty-test
  (let [selection (sample-selection "big.py" 300)
        selected [(sample-unit "big.py" 1 300)]
        result (perform-raw-fetch nil selection selected (escalation-query "enclosing_unit") 250)
        snippet (first (:raw_context result))]
    (testing "a positive budget always yields at least one non-empty snippet"
      (is (pos? (:snippets result)))
      (is (seq (:content snippet))))
    (testing "the snippet is a front slice of the requested span within the byte cap"
      (is (= 1 (:start_line snippet)))
      (is (< (:end_line snippet) 300))
      (is (<= (:bytes result) (* 4 (max 200 250)))))
    (testing "truncation is reported with the budget-limited warning"
      (is (true? (:truncated? result)))
      (is (some #(= "raw_fetch_budget_limited" (:code %)) (:warnings result))))
    (testing "full requirement for the requested level is measured"
      (is (pos? (:required_tokens result)))
      (is (> (:required_tokens result) 250)))))

(deftest raw-fetch-level-degrades-before-truncating-test
  (let [selection (sample-selection "big.py" 400)
        ;; narrow target span inside a large file: whole_file cannot fit the
        ;; budget but the target span itself can
        selected [(sample-unit "big.py" 10 12)]
        result (perform-raw-fetch nil selection selected (escalation-query "whole_file") 300)]
    (testing "level walks down the ladder until the payload fits"
      (is (= "whole_file" (:requested_level result)))
      (is (not= "whole_file" (:level result)))
      (is (some #(= "raw_fetch_level_degraded" (:code %)) (:degradations result))))
    (testing "the degraded fetch still returns a complete snippet"
      (is (pos? (:snippets result)))
      (is (false? (:truncated? result))))))

(deftest zero-budget-skips-raw-fetch-but-reports-requirement-test
  (let [selection (sample-selection "big.py" 100)
        selected [(sample-unit "big.py" 1 100)]
        result (perform-raw-fetch nil selection selected (escalation-query "enclosing_unit") 0)]
    (is (= "skipped" (:status result)))
    (is (empty? (:raw_context result)))
    (is (some #(= "raw_fetch_budget_exhausted" (:code %)) (:warnings result)))
    (testing "requirement is still measured so a budget suggestion can be emitted"
      (is (pos? (:required_tokens result))))))

(deftest escalation-disabled-stays-skipped-test
  (let [selection (sample-selection "big.py" 100)
        selected [(sample-unit "big.py" 1 100)]
        result (perform-raw-fetch nil selection selected {:options {:allow_raw_code_escalation false}} 500)]
    (is (= "skipped" (:status result)))
    (is (= "none" (:level result)))
    (is (zero? (:required_tokens result)))))

(deftest suggested-token-budget-covers-detail-stage-needs-test
  (testing "suggestion reserves room for the 10/20/70 stage split"
    (let [suggestion (suggested-token-budget 100 false 50 4000)]
      ;; detail stage receives roughly 70% of the top-level budget
      (is (>= (* 0.7 suggestion) (+ 100 50 4000)))))
  (testing "structure truncation raises the floor via the 35% structure share"
    (let [suggestion (suggested-token-budget 700 true 0 0)]
      (is (>= (* 0.7 suggestion) (/ 700 0.35)))))
  (testing "suggestion grows with raw requirement"
    (is (> (suggested-token-budget 100 false 0 8000)
           (suggested-token-budget 100 false 0 2000)))))
