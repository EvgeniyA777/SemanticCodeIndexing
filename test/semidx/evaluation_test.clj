(ns semidx.evaluation-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.evaluation :as evaluation]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n"))

(def sample-query
  {:schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate process-order authority."}
   :targets {:symbols ["my.app.order/process-order"]
             :paths ["src/my/app/order.clj"]}
   :constraints {:token_budget 1200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:prefer_definitions_over_callers true}
   :options {:allow_raw_code_escalation false}
   :trace {:trace_id "99999999-9999-4999-8999-999999999999"
           :request_id "evaluation-test-001"}})

(deftest feedback-summary-test
  (let [summary (evaluation/evaluate-feedback-records
                 [{:feedback_outcome "helpful"
                   :confidence_level "high"
                   :retrieval_issue_codes ["resolved_target_correct"]}
                  {:feedback_outcome "not_helpful"
                   :confidence_level "low"
                   :retrieval_issue_codes ["missing_authority" "too_shallow"]}])]
    (is (= 2 (:total_feedback summary)))
    (is (= {"helpful" 1, "not_helpful" 1} (:outcome_counts summary)))
    (is (= 1 (get-in summary [:issue_counts "missing_authority"])))
    (is (> (:mean_feedback_score summary) 0.0))))

(deftest replay-query-dataset-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-eval-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        result (evaluation/replay-query-dataset
                {:root_path tmp-root
                 :dataset {:queries [{:query_id "q1"
                                      :query sample-query
                                      :expected {:top_authority_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :required_paths ["src/my/app/order.clj"]
                                                 :min_confidence_level "medium"}}]}})]
    (testing "offline replay reports a passing query"
      (is (= 1 (:total_queries result)))
      (is (= 1 (:passed_queries result)))
      (is (zero? (:failed_queries result)))
      (is (= "heuristic_v1" (get-in result [:results 0 :retrieval_policy :policy_id]))))))

(deftest semantic-quality-report-from-dataset-test
  (let [impl-baseline (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-impl-base" (make-array java.nio.file.attribute.FileAttribute 0)))
        impl-current (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-impl-current" (make-array java.nio.file.attribute.FileAttribute 0)))
        rename-baseline (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-rename-base" (make-array java.nio.file.attribute.FileAttribute 0)))
        rename-current (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-rename-current" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (write-file! impl-baseline
                       "src/my/app/order.clj"
                       "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  order)\n")
        _ (write-file! impl-current
                       "src/my/app/order.clj"
                       "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (let [validated (validate-order order)]\n    validated))\n\n(defn validate-order [order]\n  order)\n")
        _ (write-file! rename-baseline
                       "src/my/app/order.clj"
                       "(ns my.app.order)\n\n(defn process-order [ctx order]\n  order)\n")
        _ (write-file! rename-current
                       "src/my/app/checkout.clj"
                       "(ns my.app.checkout)\n\n(defn process-order [ctx order]\n  order)\n")
        report (evaluation/semantic-quality-report-from-dataset
                {:thresholds {:identity_stability_rate 1.0
                              :move_rename_recovery_rate 1.0
                              :implementation_vs_meaning_accuracy 1.0
                              :expected_change_match_rate 1.0
                              :unmatched_rate 0.0}
                 :cases [{:case_id "implementation-change"
                          :baseline_root impl-baseline
                          :current_root impl-current
                          :expected_changes [{:change_type "implementation_changed"
                                              :path "src/my/app/order.clj"
                                              :symbol "my.app.order/process-order"}]}
                         {:case_id "rename-recovery"
                          :baseline_root rename-baseline
                          :current_root rename-current
                          :expected_changes [{:change_type "moved_or_renamed"
                                              :baseline_path "src/my/app/order.clj"
                                              :path "src/my/app/checkout.clj"}]}]})]
    (testing "semantic quality report aggregates fixture cases"
      (is (= 2 (get-in report [:summary :cases])))
      (is (= 2 (get-in report [:summary :expected_changes])))
      (is (= 2 (get-in report [:summary :exact_matches])))
      (is (= 1.0 (get-in report [:summary :metrics :expected_change_match_rate])))
      (is (= 1.0 (get-in report [:summary :metrics :identity_stability_rate])))
      (is (= 1.0 (get-in report [:summary :metrics :move_rename_recovery_rate])))
      (is (= 1.0 (get-in report [:summary :metrics :implementation_vs_meaning_accuracy])))
      (is (= 0.0 (get-in report [:summary :metrics :unmatched_rate]))))
    (testing "quality gate passes when thresholds are met"
      (is (true? (get-in report [:gate_decision :eligible?])))
      (is (every? :passed? (get-in report [:gate_decision :checks]))))))

(deftest semantic-quality-advisory-fixture-dataset-test
  (let [dataset (json/read-str (slurp "fixtures/semantic-quality/report-dataset.json") :key-fn keyword)
        report (evaluation/semantic-quality-report-from-dataset dataset)]
    (testing "fixture corpus is deterministic and intentionally advisory-failing"
      (is (= 6 (get-in report [:summary :cases])))
      (is (false? (get-in report [:gate_decision :eligible?])))
      (is (pos? (get-in report [:summary :classification_mismatches])))
      (is (some #(= :move_rename_recovery_rate (:metric %))
                (get-in report [:gate_decision :checks]))))
    (testing "review samples surface the intentional mismatch"
      (is (seq (get-in report [:cases 5 :review_samples :classification_mismatches]))))))

(deftest semantic-quality-advisory-runner-script-test
  (let [tmp-dir (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-runner" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str tmp-dir "/semantic-quality-report.json")
        summary-path (str tmp-dir "/semantic-quality-report-summary.md")
        {:keys [exit out err]} (sh/sh "bash"
                                      "scripts/run-semantic-quality-report.sh"
                                      "fixtures/semantic-quality/report-dataset.json"
                                      out-path
                                      summary-path
                                      :dir (.getCanonicalPath (io/file ".")))
        report (json/read-str (slurp out-path) :key-fn keyword)
        summary (slurp summary-path)]
    (testing "runner succeeds in report-only mode"
      (is (zero? exit))
      (is (false? (get-in report [:gate_decision :eligible?])))
      (is (str/includes? out "semantic_quality_gate=advisory_failure"))
      (is (str/includes? out (str "semantic_quality_report=" out-path)))
      (is (str/includes? out (str "semantic_quality_summary=" summary-path)))
      (is (not (str/includes? out "semantic_quality_report_advisory_only=true")))
      (is (str/blank? err)))
    (testing "runner writes a concise markdown summary"
      (is (str/includes? summary "Semantic Quality Report"))
      (is (str/includes? summary "gate_eligible"))
      (is (str/includes? summary "expected_change_match_rate")))))

(deftest semantic-quality-advisory-runner-fails-when-report-generation-fails-test
  (let [tmp-dir (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-runner-failure" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str tmp-dir "/semantic-quality-report.json")
        summary-path (str tmp-dir "/semantic-quality-report-summary.md")
        _ (spit out-path "{\"stale\":true}")
        _ (spit summary-path "stale summary\n")
        {:keys [exit out err]} (sh/sh "bash"
                                      "scripts/run-semantic-quality-report.sh"
                                      "fixtures/semantic-quality/missing-dataset.json"
                                      out-path
                                      summary-path
                                      :dir (.getCanonicalPath (io/file ".")))]
    (testing "runner removes stale artifacts and exits nonzero on execution failure"
      (is (not (zero? exit)))
      (is (str/includes? err "semantic_quality_runner_failed"))
      (is (not (.exists (io/file out-path))))
      (is (not (.exists (io/file summary-path))))
      (is (not (str/includes? out "semantic_quality_report_advisory_only=true"))))))

(deftest semantic-quality-advisory-runner-overwrites-final-artifacts-atomically-test
  (let [tmp-dir (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-runner-atomic" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str tmp-dir "/semantic-quality-report.json")
        summary-path (str tmp-dir "/semantic-quality-report-summary.md")
        _ (spit out-path "{\"stale\":true}")
        _ (spit summary-path "stale summary\n")
        {:keys [exit]} (sh/sh "bash"
                              "scripts/run-semantic-quality-report.sh"
                              "fixtures/semantic-quality/report-dataset.json"
                              out-path
                              summary-path
                              :dir (.getCanonicalPath (io/file ".")))
        report (json/read-str (slurp out-path) :key-fn keyword)
        summary (slurp summary-path)]
    (testing "runner replaces stale final artifacts with fresh validated output"
      (is (zero? exit))
      (is (contains? report :gate_decision))
      (is (contains? report :summary))
      (is (not (contains? report :stale)))
      (is (str/includes? summary "Semantic Quality Report"))
      (is (not (str/includes? summary "stale summary"))))))

(deftest semantic-quality-advisory-runner-explicit-paths-ignore-tmpdir-test
  (let [tmp-dir (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-runner-explicit" (make-array java.nio.file.attribute.FileAttribute 0)))
        custom-tmp (str (java.nio.file.Files/createTempDirectory "sci-semantic-quality-runner-custom-tmp" (make-array java.nio.file.attribute.FileAttribute 0)))
        out-path (str tmp-dir "/semantic-quality-report.json")
        summary-path (str tmp-dir "/semantic-quality-report-summary.md")
        {:keys [exit]} (sh/sh "bash"
                              "scripts/run-semantic-quality-report.sh"
                              "fixtures/semantic-quality/report-dataset.json"
                              out-path
                              summary-path
                              :dir (.getCanonicalPath (io/file "."))
                              :env (assoc (into {} (System/getenv))
                                          "TMPDIR" custom-tmp))]
    (testing "explicit output paths win even when TMPDIR is set"
      (is (zero? exit))
      (is (.exists (io/file out-path)))
      (is (.exists (io/file summary-path)))
      (is (not (.exists (io/file custom-tmp "semantic-quality-report.json"))))
      (is (not (.exists (io/file custom-tmp "semantic-quality-report-summary.md")))))))
