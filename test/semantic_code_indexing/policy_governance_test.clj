(ns semantic-code-indexing.policy-governance-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semantic-code-indexing.core :as sci]
            [semantic-code-indexing.runtime.evaluation :as evaluation]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]))

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
   :trace {:trace_id "77777777-7777-4777-8777-777777777777"
           :request_id "policy-governance-test-001"}})

(defn- sample-dataset []
  {:queries [{:query_id "q1"
              :query sample-query
              :expected {:top_authority_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                         :required_paths ["src/my/app/order.clj"]
                         :min_confidence_level "medium"}}]})

(defn- protected-sample-dataset []
  {:queries [{:query_id "q-protected"
              :protected_case true
              :query sample-query
              :expected {:top_authority_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                         :required_paths ["src/my/app/order.clj"]
                         :min_confidence_level "medium"}}
             {:query_id "q-unprotected"
              :query sample-query
              :expected {:required_paths ["src/my/app/order.clj"]}}]})

(deftest score-policy-emits-fixed-scorecard-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-score" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        result (evaluation/score-policy {:root_path tmp-root
                                         :dataset (sample-dataset)
                                         :retrieval_policy (rp/default-retrieval-policy)})]
    (testing "scorecard contains governed fixed metrics"
      (is (= "heuristic_v1" (get-in result [:policy_summary :policy_id])))
      (is (= 1.0 (get-in result [:scorecard :top_authority_hit_rate])))
      (is (= 1.0 (get-in result [:scorecard :required_path_hit_rate])))
      (is (= 1.0 (get-in result [:scorecard :minimum_confidence_pass_rate])))
      (is (= 0.0 (get-in result [:scorecard :degraded_rate])))
      (is (= {"high" 1} (get-in result [:scorecard :confidence_ceiling_distribution])))
      (is (contains? (get-in result [:scorecard :confidence_calibration]) :mean_absolute_error))
      (is (= "high" (get-in result [:results 0 :confidence_ceiling]))))))

(deftest compare-policies-detects-protected-regression-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-compare" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        strict-candidate (assoc-in (rp/default-retrieval-policy) [:thresholds :top_authority_min] 99999)
        comparison (evaluation/compare-policies {:root_path tmp-root
                                                 :dataset (sample-dataset)
                                                 :baseline_policy (rp/default-retrieval-policy)
                                                 :candidate_policy (assoc strict-candidate
                                                                          :policy_id "heuristic_v1_shadow"
                                                                          :version "2026-03-11")})
        decision (evaluation/promotion-gate-decision comparison)]
    (testing "protected metrics expose regression when candidate is stricter and worse"
      (is (true? (get-in comparison [:protected_metrics :top_authority_hit_rate :regressed?])))
      (is (false? (:eligible? decision)))
      (is (some (fn [{:keys [metric passed?]}]
                  (and (= :top_authority_hit_rate metric) (false? passed?)))
                (:checks decision))))))

(deftest score-policy-emits-protected-summary-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-protected-score" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        result (evaluation/score-policy {:root_path tmp-root
                                         :dataset (protected-sample-dataset)
                                         :retrieval_policy (rp/default-retrieval-policy)})]
    (testing "protected summary is emitted separately from overall scorecard"
      (is (= 1 (get-in result [:protected_summary :total_queries])))
      (is (= 1.0 (get-in result [:protected_summary :scorecard :top_authority_hit_rate])))
      (is (empty? (get-in result [:protected_summary :failed_query_ids]))))))

(deftest protected-cases-block-promotion-on-newly-failed-queries-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-protected-promote" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        baseline-policy (rp/default-retrieval-policy)
        bad-candidate (-> baseline-policy
                          (assoc :policy_id "heuristic_v1_protected_bad")
                          (assoc :version "2026-03-13")
                          (assoc-in [:thresholds :top_authority_min] 99999))
        comparison (evaluation/compare-policies {:root_path tmp-root
                                                 :dataset (protected-sample-dataset)
                                                 :baseline_policy baseline-policy
                                                 :candidate_policy bad-candidate})
        decision (evaluation/promotion-gate-decision comparison)]
    (testing "comparison tracks newly failed protected queries"
      (is (= ["q-protected"]
             (get-in comparison [:protected_case_summary :newly_failed_query_ids]))))
    (testing "promotion gate rejects newly failed protected cases"
      (is (false? (:eligible? decision)))
      (is (some (fn [{:keys [metric passed? newly_failed_query_ids]}]
                  (and (= :protected_query_regressions metric)
                       (false? passed?)
                       (= ["q-protected"] newly_failed_query_ids)))
                (:checks decision))))))

(deftest promote-policy-updates-registry-only-when-gates-pass-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-promote" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        baseline-policy (rp/default-retrieval-policy)
        good-candidate (assoc baseline-policy :policy_id "heuristic_v1_candidate" :version "2026-03-11")
        bad-candidate (-> baseline-policy
                          (assoc :policy_id "heuristic_v1_bad")
                          (assoc :version "2026-03-12")
                          (assoc-in [:thresholds :top_authority_min] 99999))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry baseline-policy {:state "active"})
                             (rp/registry-entry good-candidate {:state "shadow"})
                             (rp/registry-entry bad-candidate {:state "shadow"})]}
        good-comparison (evaluation/compare-policies {:root_path tmp-root
                                                      :dataset (sample-dataset)
                                                      :baseline_policy baseline-policy
                                                      :candidate_policy good-candidate})
        bad-comparison (evaluation/compare-policies {:root_path tmp-root
                                                     :dataset (sample-dataset)
                                                     :baseline_policy baseline-policy
                                                     :candidate_policy bad-candidate})
        good-promotion (evaluation/promote-policy {:registry registry
                                                   :candidate_policy_id "heuristic_v1_candidate"
                                                   :candidate_version "2026-03-11"
                                                   :comparison good-comparison})
        bad-promotion (evaluation/promote-policy {:registry registry
                                                  :candidate_policy_id "heuristic_v1_bad"
                                                  :candidate_version "2026-03-12"
                                                  :comparison bad-comparison})]
    (testing "passing candidate becomes active and retires previous active policy"
      (is (:promoted? good-promotion))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry good-promotion)
                                                "heuristic_v1_candidate"
                                                "2026-03-11"))))
      (is (= "retired"
             (:state (rp/resolve-registry-entry (:registry good-promotion)
                                                "heuristic_v1"
                                                "2026-03-10")))))
    (testing "failing candidate remains non-active and registry is unchanged for that candidate"
      (is (false? (:promoted? bad-promotion)))
      (is (= "shadow"
             (:state (rp/resolve-registry-entry (:registry bad-promotion)
                                                "heuristic_v1_bad"
                                                "2026-03-12"))))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry bad-promotion)
                                                "heuristic_v1"
                                                "2026-03-10")))))))

(deftest promote-policy-rejects-governance-blocked-candidates-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-promote-blocked" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        baseline-policy (rp/default-retrieval-policy)
        blocked-candidate (assoc baseline-policy :policy_id "heuristic_v1_blocked" :version "2026-03-16")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry baseline-policy {:state "active"})
                             (rp/registry-entry blocked-candidate {:state "shadow"
                                                                   :governance {:promotion_mode "blocked"
                                                                                :approval_tier "critical"}})]}
        comparison (evaluation/compare-policies {:root_path tmp-root
                                                 :dataset (sample-dataset)
                                                 :baseline_policy baseline-policy
                                                 :candidate_policy blocked-candidate})
        promotion (evaluation/promote-policy {:registry registry
                                              :candidate_policy_id "heuristic_v1_blocked"
                                              :candidate_version "2026-03-16"
                                              :comparison comparison
                                              :manual_approval true})]
    (is (false? (:promoted? promotion)))
    (is (false? (get-in promotion [:decision :eligible?])))
    (is (= "promotion_blocked" (get-in promotion [:decision :governance_reason])))
    (is (= "blocked" (get-in promotion [:decision :promotion_mode])))
    (is (true? (get-in promotion [:decision :manual_approval_supplied])))
    (is (= "shadow"
           (:state (rp/resolve-registry-entry (:registry promotion)
                                              "heuristic_v1_blocked"
                                              "2026-03-16"))))))

(deftest promote-policy-requires-explicit-manual-approval-for-restricted-tier-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-promote-manual" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        baseline-policy (rp/default-retrieval-policy)
        manual-candidate (assoc baseline-policy :policy_id "heuristic_v1_manual" :version "2026-03-17")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry baseline-policy {:state "active"})
                             (rp/registry-entry manual-candidate {:state "shadow"
                                                                  :governance {:promotion_mode "manual_approval_required"
                                                                               :approval_tier "restricted"}})]}
        comparison (evaluation/compare-policies {:root_path tmp-root
                                                 :dataset (sample-dataset)
                                                 :baseline_policy baseline-policy
                                                 :candidate_policy manual-candidate})
        without-approval (evaluation/promote-policy {:registry registry
                                                     :candidate_policy_id "heuristic_v1_manual"
                                                     :candidate_version "2026-03-17"
                                                     :comparison comparison})
        with-approval (evaluation/promote-policy {:registry registry
                                                  :candidate_policy_id "heuristic_v1_manual"
                                                  :candidate_version "2026-03-17"
                                                  :comparison comparison
                                                  :manual_approval true})]
    (testing "manual-only candidate stays shadow without explicit approval"
      (is (false? (:promoted? without-approval)))
      (is (false? (get-in without-approval [:decision :eligible?])))
      (is (= "manual_approval_required" (get-in without-approval [:decision :governance_reason])))
      (is (= "restricted" (get-in without-approval [:decision :approval_tier]))))
    (testing "manual approval unlocks direct promotion when replay gates pass"
      (is (:promoted? with-approval))
      (is (true? (get-in with-approval [:decision :eligible?])))
      (is (= "manual_approval_granted" (get-in with-approval [:decision :governance_reason])))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry with-approval)
                                                "heuristic_v1_manual"
                                                "2026-03-17")))))))

(deftest manual-approval-does-not-override-failed-replay-gates-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-promote-manual-failed-replay" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        baseline-policy (rp/default-retrieval-policy)
        manual-candidate (-> baseline-policy
                             (assoc :policy_id "heuristic_v1_manual_fail")
                             (assoc :version "2026-03-18")
                             (assoc-in [:thresholds :top_authority_min] 99999))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry baseline-policy {:state "active"})
                             (rp/registry-entry manual-candidate {:state "shadow"
                                                                  :governance {:promotion_mode "manual_approval_required"
                                                                               :approval_tier "restricted"}})]}
        comparison (evaluation/compare-policies {:root_path tmp-root
                                                 :dataset (protected-sample-dataset)
                                                 :baseline_policy baseline-policy
                                                 :candidate_policy manual-candidate})
        promotion (evaluation/promote-policy {:registry registry
                                              :candidate_policy_id "heuristic_v1_manual_fail"
                                              :candidate_version "2026-03-18"
                                              :comparison comparison
                                              :manual_approval true})]
    (is (false? (:promoted? promotion)))
    (is (false? (get-in promotion [:decision :eligible?])))
    (is (= "replay_gates_failed" (get-in promotion [:decision :governance_reason])))
    (is (false? (get-in promotion [:decision :replay_eligible])))))

(deftest shadow-review-reports-shadow-candidates-against-active-policy-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-shadow-review" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        baseline-policy (rp/default-retrieval-policy)
        good-shadow (assoc baseline-policy :policy_id "heuristic_v1_shadow_good" :version "2026-03-14")
        bad-shadow (-> baseline-policy
                       (assoc :policy_id "heuristic_v1_shadow_bad")
                       (assoc :version "2026-03-15")
                       (assoc-in [:thresholds :top_authority_min] 99999))
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry baseline-policy {:state "active"})
                             (rp/registry-entry good-shadow {:state "shadow"})
                             (rp/registry-entry bad-shadow {:state "shadow"})]}
        report (evaluation/shadow-review-report {:root_path tmp-root
                                                 :dataset (protected-sample-dataset)
                                                 :registry registry})
        reviewed-registry (evaluation/apply-shadow-review registry report)]
    (testing "shadow review summarizes readiness against the active policy"
      (is (= "heuristic_v1" (get-in report [:active_policy :policy_summary :policy_id])))
      (is (= 2 (get-in report [:summary :shadow_candidates])))
      (is (= 1 (get-in report [:summary :ready_for_promotion])))
      (is (= 1 (get-in report [:summary :blocked]))))
    (testing "shadow review marks good shadow as promotable and bad shadow as blocked"
      (is (boolean
           (some (fn [candidate]
                   (and (= "heuristic_v1_shadow_good" (get-in candidate [:policy_summary :policy_id]))
                        (:eligible_for_promotion candidate)))
                 (:shadow_candidates report))))
      (is (boolean
           (some (fn [candidate]
                   (and (= "heuristic_v1_shadow_bad" (get-in candidate [:policy_summary :policy_id]))
                        (not (:eligible_for_promotion candidate))
                        (seq (:failed_checks candidate))))
                 (:shadow_candidates report)))))
    (testing "applying shadow review persists review metadata onto registry entries"
      (is (string? (get-in (rp/resolve-registry-entry reviewed-registry "heuristic_v1_shadow_good" "2026-03-14")
                           [:shadow_review :reviewed_at])))
      (is (true? (get-in (rp/resolve-registry-entry reviewed-registry "heuristic_v1_shadow_good" "2026-03-14")
                         [:shadow_review :eligible_for_promotion])))
      (is (false? (get-in (rp/resolve-registry-entry reviewed-registry "heuristic_v1_shadow_bad" "2026-03-15")
                          [:shadow_review :eligible_for_promotion]))))))

(deftest policy-review-pipeline-builds-weekly-review-dataset-and-shadow-review-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-review-pipeline" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
                             :request_id "policy-review-pipeline-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-policy (assoc active-policy :policy_id "heuristic_v1_shadow_pipeline" :version "2026-03-16")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        bundle (evaluation/policy-review-pipeline {:root_path tmp-root
                                                   :usage_metrics metrics
                                                   :registry registry})]
    (testing "pipeline emits weekly review and protected replay dataset"
      (is (= 1 (get-in bundle [:weekly_review_report :summary :total_queries])))
      (is (= 1 (count (get-in bundle [:protected_replay_dataset :queries]))))
      (is (true? (get-in bundle [:protected_replay_dataset :queries 0 :protected_case]))))
    (testing "pipeline runs shadow review on the generated protected dataset"
      (is (= 1 (get-in bundle [:shadow_review_report :summary :shadow_candidates])))
      (is (= "heuristic_v1" (get-in bundle [:shadow_review_report :active_policy :policy_summary :policy_id])))
      (is (= "heuristic_v1_shadow_pipeline"
             (get-in bundle [:shadow_review_report :shadow_candidates 0 :policy_summary :policy_id]))))
    (testing "registry is returned even without write-back"
      (is (= 2 (count (:policies (:registry bundle)))))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry bundle) "heuristic_v1" "2026-03-10")))))))

(deftest policy-review-pipeline-can-write-shadow-review-back-to-registry-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-policy-review-pipeline-write" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
                             :request_id "policy-review-pipeline-write-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-policy (assoc active-policy :policy_id "heuristic_v1_shadow_write" :version "2026-03-17")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        bundle (evaluation/policy-review-pipeline {:root_path tmp-root
                                                   :usage_metrics metrics
                                                   :registry registry
                                                   :write_registry true})]
    (testing "write-back persists shadow review metadata onto shadow entries"
      (is (string? (get-in (rp/resolve-registry-entry (:registry bundle)
                                                      "heuristic_v1_shadow_write"
                                                      "2026-03-17")
                           [:shadow_review :reviewed_at])))
      (is (contains? (get-in (rp/resolve-registry-entry (:registry bundle)
                                                        "heuristic_v1_shadow_write"
                                                        "2026-03-17")
                             [:shadow_review])
                     :eligible_for_promotion)))))

(deftest scheduled-policy-review-writes-artifacts-and-prunes-retention-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-scheduled-policy-review" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        active-policy (rp/default-retrieval-policy)
        shadow-policy (assoc active-policy :policy_id "heuristic_v1_shadow_scheduled" :version "2026-03-18")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        query-1 (assoc sample-query
                       :trace {:trace_id "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
                               :request_id "scheduled-policy-review-001"})
        _result-1 (sci/resolve-context index query-1)
        _feedback-1 (sci/record-feedback! index {:trace_id (get-in query-1 [:trace :trace_id])
                                                 :request_id (get-in query-1 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        first-run (evaluation/scheduled-policy-review {:root_path tmp-root
                                                       :usage_metrics metrics
                                                       :registry registry
                                                       :artifacts_dir artifacts-dir
                                                       :retention_runs 1
                                                       :lookback_days 7})
        _ (Thread/sleep 5)
        query-2 (assoc sample-query
                       :trace {:trace_id "ffffffff-ffff-4fff-8fff-ffffffffffff"
                               :request_id "scheduled-policy-review-002"})
        _result-2 (sci/resolve-context index query-2)
        _feedback-2 (sci/record-feedback! index {:trace_id (get-in query-2 [:trace :trace_id])
                                                 :request_id (get-in query-2 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        second-run (evaluation/scheduled-policy-review {:root_path tmp-root
                                                        :usage_metrics metrics
                                                        :registry registry
                                                        :artifacts_dir artifacts-dir
                                                        :retention_runs 1
                                                        :lookback_days 7})
        files (->> (.listFiles (io/file artifacts-dir))
                   (filter #(.isFile %))
                   (map #(.getName %))
                   sort
                   vec)]
    (testing "scheduled run writes bundle, manifest, and retained standalone artifacts"
      (is (some #(= "policy-review-manifest.json" %) files))
      (is (= 1 (count (filter #(and (str/starts-with? % "policy-review-")
                                    (not= "policy-review-manifest.json" %))
                              files))))
      (is (= 1 (count (filter #(str/starts-with? % "weekly-review-") files))))
      (is (= 1 (count (filter #(str/starts-with? % "protected-replay-dataset-") files))))
      (is (= 1 (count (filter #(str/starts-with? % "shadow-review-") files)))))
    (testing "scheduled run returns direct pointers to retained component artifacts"
      (is (str/ends-with? (get-in second-run [:scheduled_run :artifact_path]) ".json"))
      (is (str/ends-with? (get-in second-run [:scheduled_run :weekly_review_path]) ".json"))
      (is (str/ends-with? (get-in second-run [:scheduled_run :protected_replay_dataset_path]) ".json"))
      (is (str/ends-with? (get-in second-run [:scheduled_run :shadow_review_path]) ".json"))
      (is (str/ends-with? (get-in second-run [:scheduled_run :review_index_path]) ".json"))
      (is (.exists (io/file (get-in second-run [:scheduled_run :weekly_review_path]))))
      (is (.exists (io/file (get-in second-run [:scheduled_run :protected_replay_dataset_path]))))
      (is (.exists (io/file (get-in second-run [:scheduled_run :shadow_review_path]))))
      (is (.exists (io/file (get-in second-run [:scheduled_run :review_index_path]))))
      (is (= (get-in second-run [:scheduled_run :weekly_review_path])
             (get-in second-run [:manifest :latest_weekly_review_path])))
      (is (= (get-in second-run [:scheduled_run :protected_replay_dataset_path])
             (get-in second-run [:manifest :latest_protected_replay_dataset_path])))
      (is (= (get-in second-run [:scheduled_run :shadow_review_path])
             (get-in second-run [:manifest :latest_shadow_review_path])))
      (is (= (get-in second-run [:scheduled_run :review_index_path])
             (get-in second-run [:manifest :review_index_path]))))
    (testing "review index retains one current run summary with direct artifact pointers"
      (is (= 1 (count (get-in second-run [:review_index :runs]))))
      (is (= (get-in second-run [:scheduled_run :artifact_path])
             (get-in second-run [:review_index :runs 0 :artifact_paths :policy_review_bundle])))
      (is (= (get-in second-run [:scheduled_run :protected_replay_dataset_path])
             (get-in second-run [:review_index :runs 0 :artifact_paths :protected_replay_dataset]))))
    (testing "second run uses previous manifest timestamp as implicit since"
      (is (= (get-in first-run [:scheduled_run :generated_at])
             (get-in second-run [:scheduled_run :since]))))
    (testing "retention pruning deletes the older artifact generation across every retained stream"
      (is (= 1 (count (get-in second-run [:scheduled_run :deleted_artifacts]))))
      (is (= 1 (count (get-in second-run [:scheduled_run :weekly_review_deleted_artifacts]))))
      (is (= 1 (count (get-in second-run [:scheduled_run :protected_replay_dataset_deleted_artifacts]))))
      (is (= 1 (count (get-in second-run [:scheduled_run :shadow_review_deleted_artifacts])))))))

(deftest scheduled-governance-cycle-can-auto-promote-single-eligible-shadow-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-scheduled-governance-cycle" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "12121212-1212-4212-8212-121212121212"
                             :request_id "scheduled-governance-cycle-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-policy (assoc active-policy :policy_id "heuristic_v1_shadow_auto" :version "2026-03-19")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                      :usage_metrics metrics
                                                      :registry registry
                                                      :artifacts_dir artifacts-dir
                                                      :retention_runs 2
                                                      :auto_promote true})]
    (testing "single eligible candidate is auto-promoted"
      (is (true? (get-in cycle [:promotion :promoted?])))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1_shadow_auto"
                                                "2026-03-19"))))
      (is (= "retired"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1"
                                                "2026-03-10")))))
    (testing "governance cycle writes its own retained artifact stream"
      (is (str/ends-with? (get-in cycle [:scheduled_run :artifact_path]) ".json"))
      (is (.exists (io/file (get-in cycle [:scheduled_run :manifest_path]))))
      (is (some #(= "governance-cycle-manifest.json" %)
                (map #(.getName %)
                     (seq (.listFiles (io/file artifacts-dir)))))))))

(deftest scheduled-governance-cycle-can-select-best-candidate-among-multiple-eligible-shadows-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-scheduled-governance-best" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "34343434-3434-4434-8434-343434343434"
                             :request_id "scheduled-governance-best-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-a (assoc active-policy :policy_id "heuristic_v1_shadow_a" :version "2026-03-20")
        shadow-b (assoc active-policy :policy_id "heuristic_v1_shadow_b" :version "2026-03-21")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-a {:state "shadow"})
                             (rp/registry-entry shadow-b {:state "shadow"})]}
        cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                      :usage_metrics metrics
                                                      :registry registry
                                                      :artifacts_dir artifacts-dir
                                                      :retention_runs 2
                                                      :auto_promote true
                                                      :select_best_candidate true})]
    (testing "ranking is emitted and deterministic across eligible shadows"
      (is (= ["heuristic_v1_shadow_a" "heuristic_v1_shadow_b"]
             (mapv #(get-in % [:policy_summary :policy_id]) (:candidate_ranking cycle)))))
    (testing "best-ranked candidate is auto-promoted when selection is enabled"
      (is (= "best_eligible_candidate" (get-in cycle [:promotion :selection_mode])))
      (is (= "heuristic_v1_shadow_a"
             (get-in cycle [:promotion :selected_candidate :policy_id])))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1_shadow_a"
                                                "2026-03-20"))))
      (is (= "shadow"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1_shadow_b"
                                                "2026-03-21")))))))

(deftest governance-history-report-summarizes-retained-governance-runs-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-governance-history" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query-1 (assoc sample-query
                       :trace {:trace_id "56565656-5656-4656-8656-565656565656"
                               :request_id "governance-history-001"})
        _result-1 (sci/resolve-context index query-1)
        _feedback-1 (sci/record-feedback! index {:trace_id (get-in query-1 [:trace :trace_id])
                                                 :request_id (get-in query-1 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-a (assoc active-policy :policy_id "heuristic_v1_shadow_hist_a" :version "2026-03-22")
        shadow-b (assoc active-policy :policy_id "heuristic_v1_shadow_hist_b" :version "2026-03-23")
        registry-multi {:schema_version "1.0"
                        :policies [(rp/registry-entry active-policy {:state "active"})
                                   (rp/registry-entry shadow-a {:state "shadow"})
                                   (rp/registry-entry shadow-b {:state "shadow"})]}
        _cycle-1 (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                         :usage_metrics metrics
                                                         :registry registry-multi
                                                         :artifacts_dir artifacts-dir
                                                         :retention_runs 4
                                                         :auto_promote true})
        _ (Thread/sleep 5)
        query-2 (assoc sample-query
                       :trace {:trace_id "78787878-7878-4787-8787-787878787878"
                               :request_id "governance-history-002"})
        _result-2 (sci/resolve-context index query-2)
        _feedback-2 (sci/record-feedback! index {:trace_id (get-in query-2 [:trace :trace_id])
                                                 :request_id (get-in query-2 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        registry-single {:schema_version "1.0"
                         :policies [(rp/registry-entry active-policy {:state "active"})
                                    (rp/registry-entry shadow-a {:state "shadow"})]}
        _cycle-2 (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                         :usage_metrics metrics
                                                         :registry registry-single
                                                         :artifacts_dir artifacts-dir
                                                         :retention_runs 4
                                                         :auto_promote true})
        report (evaluation/governance-history-report {:artifacts_dir artifacts-dir})]
    (testing "history report aggregates promoted and skipped runs"
      (is (= 2 (get-in report [:summary :total_runs])))
      (is (= 1 (get-in report [:summary :promoted_runs])))
      (is (= 1 (get-in report [:summary :skipped_runs]))))
    (testing "history report carries skipped reason and selected policy counts"
      (is (= 1 (get-in report [:summary :promotion_reason_counts "multiple_eligible_candidates"])))
      (is (= 1 (get-in report [:summary :selected_policy_counts "heuristic_v1_shadow_hist_a"]))))
    (testing "history report preserves recent run entries"
      (is (true? (:index_used report)))
      (is (= 2 (count (:runs report))))
      (is (some :artifact_path (:runs report)))
      (is (every? :review_run_ref (:runs report)))
      (is (string? (get-in report [:summary :latest_run_at]))))))

(deftest scheduled-governance-cycle-can-require-candidate-streak-before-promotion-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-governance-streak" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        active-policy (rp/default-retrieval-policy)
        shadow-policy (assoc active-policy :policy_id "heuristic_v1_shadow_streak" :version "2026-03-24")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        query-1 (assoc sample-query
                       :trace {:trace_id "90909090-9090-4090-8090-909090909090"
                               :request_id "governance-streak-001"})
        _result-1 (sci/resolve-context index query-1)
        _feedback-1 (sci/record-feedback! index {:trace_id (get-in query-1 [:trace :trace_id])
                                                 :request_id (get-in query-1 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        first-cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                            :usage_metrics metrics
                                                            :registry registry
                                                            :artifacts_dir artifacts-dir
                                                            :retention_runs 4
                                                            :auto_promote true
                                                            :required_candidate_streak_runs 2})
        _ (Thread/sleep 5)
        query-2 (assoc sample-query
                       :trace {:trace_id "91919191-9191-4191-8191-919191919191"
                               :request_id "governance-streak-002"})
        _result-2 (sci/resolve-context index query-2)
        _feedback-2 (sci/record-feedback! index {:trace_id (get-in query-2 [:trace :trace_id])
                                                 :request_id (get-in query-2 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        second-cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                             :usage_metrics metrics
                                                             :registry registry
                                                             :artifacts_dir artifacts-dir
                                                             :retention_runs 4
                                                             :auto_promote true
                                                             :required_candidate_streak_runs 2})]
    (testing "first run is gated by insufficient streak"
      (is (= "insufficient_candidate_streak" (get-in first-cycle [:promotion :reason])))
      (is (= 1 (get-in first-cycle [:promotion :candidate_streak_runs]))))
    (testing "second consecutive run promotes the same candidate"
      (is (true? (get-in second-cycle [:promotion :promoted?])))
      (is (= 2 (get-in second-cycle [:promotion :candidate_streak_runs]))))))

(deftest scheduled-governance-cycle-can-apply-promotion-cooldown-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-governance-cooldown" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        active-policy (rp/default-retrieval-policy)
        shadow-policy (assoc active-policy :policy_id "heuristic_v1_shadow_cooldown" :version "2026-03-25")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-policy {:state "shadow"})]}
        query-1 (assoc sample-query
                       :trace {:trace_id "92929292-9292-4292-8292-929292929292"
                               :request_id "governance-cooldown-001"})
        _result-1 (sci/resolve-context index query-1)
        _feedback-1 (sci/record-feedback! index {:trace_id (get-in query-1 [:trace :trace_id])
                                                 :request_id (get-in query-1 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        first-cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                            :usage_metrics metrics
                                                            :registry registry
                                                            :artifacts_dir artifacts-dir
                                                            :retention_runs 4
                                                            :auto_promote true
                                                            :promotion_cooldown_runs 1})
        _ (Thread/sleep 5)
        query-2 (assoc sample-query
                       :trace {:trace_id "93939393-9393-4393-8393-939393939393"
                               :request_id "governance-cooldown-002"})
        _result-2 (sci/resolve-context index query-2)
        _feedback-2 (sci/record-feedback! index {:trace_id (get-in query-2 [:trace :trace_id])
                                                 :request_id (get-in query-2 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        second-cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                             :usage_metrics metrics
                                                             :registry registry
                                                             :artifacts_dir artifacts-dir
                                                             :retention_runs 4
                                                             :auto_promote true
                                                             :promotion_cooldown_runs 1})]
    (testing "first run promotes normally"
      (is (true? (get-in first-cycle [:promotion :promoted?]))))
    (testing "next run is held by promotion cooldown"
      (is (= "promotion_cooldown_active" (get-in second-cycle [:promotion :reason])))
      (is (true? (get-in second-cycle [:promotion :skipped]))))))

(deftest scheduled-governance-cycle-can-use-history-aware-selection-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-governance-history-aware" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        active-policy (rp/default-retrieval-policy)
        shadow-a (assoc active-policy :policy_id "heuristic_v1_shadow_hist_select_a" :version "2026-03-26")
        shadow-b (assoc active-policy :policy_id "heuristic_v1_shadow_hist_select_b" :version "2026-03-27")
        query-1 (assoc sample-query
                       :trace {:trace_id "94949494-9494-4494-8494-949494949494"
                               :request_id "governance-history-aware-001"})
        _result-1 (sci/resolve-context index query-1)
        _feedback-1 (sci/record-feedback! index {:trace_id (get-in query-1 [:trace :trace_id])
                                                 :request_id (get-in query-1 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        prior-registry {:schema_version "1.0"
                        :policies [(rp/registry-entry active-policy {:state "active"})
                                   (rp/registry-entry shadow-b {:state "shadow"})]}
        _prior-cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                             :usage_metrics metrics
                                                             :registry prior-registry
                                                             :artifacts_dir artifacts-dir
                                                             :retention_runs 6
                                                             :auto_promote true})
        _ (Thread/sleep 5)
        query-2 (assoc sample-query
                       :trace {:trace_id "95959595-9595-4595-8595-959595959595"
                               :request_id "governance-history-aware-002"})
        _result-2 (sci/resolve-context index query-2)
        _feedback-2 (sci/record-feedback! index {:trace_id (get-in query-2 [:trace :trace_id])
                                                 :request_id (get-in query-2 [:trace :request_id])
                                                 :feedback_outcome "not_helpful"
                                                 :followup_action "discarded"
                                                 :confidence_level "medium"
                                                 :retrieval_issue_codes ["missing_authority"]
                                                 :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                                 :ground_truth_paths ["src/my/app/order.clj"]})
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-a {:state "shadow"})
                             (rp/registry-entry shadow-b {:state "shadow"})]}
        cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                      :usage_metrics metrics
                                                      :registry registry
                                                      :artifacts_dir artifacts-dir
                                                      :retention_runs 6
                                                      :auto_promote true
                                                      :select_best_candidate true
                                                      :history_aware_selection true})]
    (testing "history-aware selection reorders candidates using prior promotion history"
      (is (= ["heuristic_v1_shadow_hist_select_b" "heuristic_v1_shadow_hist_select_a"]
             (mapv #(get-in % [:policy_summary :policy_id]) (:candidate_ranking cycle))))
      (is (= 1 (get-in cycle [:candidate_ranking 0 :history_stats :promoted_runs]))))
    (testing "history-preferred candidate is promoted"
      (is (= "heuristic_v1_shadow_hist_select_b"
             (get-in cycle [:promotion :selected_candidate :policy_id])))
      (is (= "active"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1_shadow_hist_select_b"
                                                "2026-03-27")))))))

(deftest registry-defaults-governance-metadata-for-existing-entries-test
  (let [registry {:schema_version "1.0"
                  :policies [(rp/registry-entry (rp/default-retrieval-policy) {:state "active"})]}
        entry (rp/active-registry-entry registry)]
    (is (= "auto_promotable" (:promotion_mode (rp/effective-governance entry))))
    (is (= "standard" (:approval_tier (rp/effective-governance entry))))))

(deftest shadow-review-emits-governance-tier-metadata-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-shadow-review-governance" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        active-policy (rp/default-retrieval-policy)
        manual-shadow (assoc active-policy :policy_id "heuristic_v1_shadow_manual" :version "2026-03-28")
        blocked-shadow (assoc active-policy :policy_id "heuristic_v1_shadow_blocked" :version "2026-03-29")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry manual-shadow {:state "shadow"
                                                               :governance {:promotion_mode "manual_approval_required"
                                                                            :approval_tier "restricted"}})
                             (rp/registry-entry blocked-shadow {:state "shadow"
                                                                :governance {:promotion_mode "blocked"
                                                                             :approval_tier "critical"}})]}
        report (evaluation/shadow-review-report {:root_path tmp-root
                                                 :dataset (protected-sample-dataset)
                                                 :registry registry})
        manual-candidate (some #(when (= "heuristic_v1_shadow_manual" (get-in % [:policy_summary :policy_id])) %) (:shadow_candidates report))
        blocked-candidate (some #(when (= "heuristic_v1_shadow_blocked" (get-in % [:policy_summary :policy_id])) %) (:shadow_candidates report))]
    (testing "replay eligibility and auto-promotion eligibility are separated"
      (is (= 2 (get-in report [:summary :ready_for_promotion])))
      (is (= 0 (get-in report [:summary :ready_for_auto_promotion])))
      (is (= 1 (get-in report [:summary :manual_review_required])))
      (is (= 1 (get-in report [:summary :governance_blocked]))))
    (testing "manual tier candidate is review-eligible but not auto-promotable"
      (is (true? (:eligible_for_promotion manual-candidate)))
      (is (false? (:eligible_for_auto_promotion manual-candidate)))
      (is (true? (:manual_review_required manual-candidate)))
      (is (= "restricted" (:approval_tier manual-candidate))))
    (testing "blocked candidate is withheld by governance"
      (is (true? (:eligible_for_promotion blocked-candidate)))
      (is (false? (:eligible_for_auto_promotion blocked-candidate)))
      (is (true? (:blocked_by_governance blocked-candidate)))
      (is (= "critical" (:approval_tier blocked-candidate))))))

(deftest scheduled-governance-cycle-skips-manual-approval-candidates-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-governance-manual-tier" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "96969696-9696-4696-8696-969696969696"
                             :request_id "governance-manual-tier-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        manual-shadow (assoc active-policy :policy_id "heuristic_v1_shadow_manual_cycle" :version "2026-03-30")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry manual-shadow {:state "shadow"
                                                               :governance {:promotion_mode "manual_approval_required"
                                                                            :approval_tier "restricted"}})]}
        cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                      :usage_metrics metrics
                                                      :registry registry
                                                      :artifacts_dir artifacts-dir
                                                      :retention_runs 2
                                                      :auto_promote true})]
    (testing "manual-review candidates do not auto-promote"
      (is (= "no_auto_promotable_candidates" (get-in cycle [:promotion :reason])))
      (is (= "heuristic_v1_shadow_manual_cycle"
             (get-in cycle [:promotion :manual_review_candidates 0 :policy_id])))
      (is (= "shadow"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1_shadow_manual_cycle"
                                                "2026-03-30")))))))

(deftest scheduled-governance-cycle-skips-governance-blocked-candidates-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-governance-blocked-tier" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "97979797-9797-4797-8797-979797979797"
                             :request_id "governance-blocked-tier-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        blocked-shadow (assoc active-policy :policy_id "heuristic_v1_shadow_blocked_cycle" :version "2026-03-31")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry blocked-shadow {:state "shadow"
                                                                :governance {:promotion_mode "blocked"
                                                                             :approval_tier "critical"}})]}
        cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                      :usage_metrics metrics
                                                      :registry registry
                                                      :artifacts_dir artifacts-dir
                                                      :retention_runs 2
                                                      :auto_promote true})]
    (testing "blocked candidates are excluded from auto-promotion"
      (is (= "all_candidates_blocked_by_governance" (get-in cycle [:promotion :reason])))
      (is (= "heuristic_v1_shadow_blocked_cycle"
             (get-in cycle [:promotion :governance_blocked_candidates 0 :policy_id])))
      (is (= "shadow"
             (:state (rp/resolve-registry-entry (:registry cycle)
                                                "heuristic_v1_shadow_blocked_cycle"
                                                "2026-03-31")))))))

(deftest phase5-review-queue-emits-feedback-gap-and-manual-review-items-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-phase5-review-queue" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        active-policy (rp/default-retrieval-policy)
        manual-shadow (assoc active-policy :policy_id "heuristic_v1_shadow_queue_manual" :version "2026-04-01")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry manual-shadow {:state "shadow"
                                                               :governance {:promotion_mode "manual_approval_required"
                                                                            :approval_tier "restricted"}})]}
        _no-feedback-run (evaluation/scheduled-policy-review {:root_path tmp-root
                                                              :usage_metrics metrics
                                                              :registry registry
                                                              :artifacts_dir artifacts-dir
                                                              :retention_runs 4
                                                              :lookback_days 7})
        _ (Thread/sleep 5)
        query (assoc sample-query
                     :trace {:trace_id "98989898-9898-4898-8898-989898989898"
                             :request_id "phase5-review-queue-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        _manual-review-run (evaluation/scheduled-policy-review {:root_path tmp-root
                                                                :usage_metrics metrics
                                                                :registry registry
                                                                :artifacts_dir artifacts-dir
                                                                :retention_runs 4
                                                                :lookback_days 7})
        queue (evaluation/phase5-review-queue {:artifacts_dir artifacts-dir})]
    (testing "queue retains both scope-level feedback gaps and candidate-level manual review work"
      (is (= 2 (get-in queue [:summary :total_items])))
      (is (= 1 (get-in queue [:summary :reason_counts "no_protected_queries"])))
      (is (= 1 (get-in queue [:summary :reason_counts "manual_approval_required"])))
      (is (= #{"no_protected_queries" "manual_approval_required"}
             (set (map :reason_code (:items queue))))))
    (testing "manual review items keep the candidate identity and direct artifact pointers"
      (let [manual-item (some #(when (= "manual_approval_required" (:reason_code %)) %) (:items queue))]
        (is (= "heuristic_v1_shadow_queue_manual" (get-in manual-item [:policy_summary :policy_id])))
        (is (str/ends-with? (get-in manual-item [:artifact_paths :shadow_review]) ".json"))))
    (testing "feedback-gap items keep the expected operator action"
      (let [gap-item (some #(when (= "no_protected_queries" (:reason_code %)) %) (:items queue))]
        (is (= "collect_more_feedback" (:required_action gap-item)))))))

(deftest phase5-status-report-aggregates-review-governance-and-queue-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-phase5-status-report" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "99999999-9999-4999-8999-999999999999"
                             :request_id "phase5-status-report-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-a (assoc active-policy :policy_id "heuristic_v1_shadow_status_a" :version "2026-04-02")
        shadow-b (assoc active-policy :policy_id "heuristic_v1_shadow_status_b" :version "2026-04-03")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-a {:state "shadow"})
                             (rp/registry-entry shadow-b {:state "shadow"})]}
        _cycle (evaluation/scheduled-governance-cycle {:root_path tmp-root
                                                       :usage_metrics metrics
                                                       :registry registry
                                                       :artifacts_dir artifacts-dir
                                                       :retention_runs 4
                                                       :auto_promote true})
        report (evaluation/phase5-status-report {:artifacts_dir artifacts-dir})]
    (testing "status report joins retained review runs, governance runs, and pending queue items"
      (is (= 1 (get-in report [:summary :review_runs])))
      (is (= 1 (get-in report [:summary :governance_runs])))
      (is (= 1 (get-in report [:summary :pending_queue_items])))
      (is (= 1 (get-in report [:summary :protected_queries_total])))
      (is (= 1 (get-in report [:summary :pending_reason_counts "multiple_eligible_candidates"]))))
    (testing "latest governance run keeps source review artifact references"
      (is (= 1 (count (:governance_runs report))))
      (is (str/ends-with? (get-in report [:governance_runs 0 :artifact_paths :governance_cycle]) ".json"))
      (is (str/ends-with? (get-in report [:governance_runs 0 :review_run_ref :protected_replay_dataset_path]) ".json")))
    (testing "pending queue exposes the operator action for the governance skip"
      (is (= "choose_best_candidate_from_ranking"
             (get-in report [:pending_queue 0 :required_action]))))))

(deftest scheduled-phase5-cycle-retains-orchestration-artifact-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-scheduled-phase5-cycle" (make-array java.nio.file.attribute.FileAttribute 0)))
        artifacts-dir (str (io/file tmp-root ".tmp" "policy-review"))
        _ (create-sample-repo! tmp-root)
        metrics (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics metrics})
        query (assoc sample-query
                     :trace {:trace_id "10101010-1010-4010-8010-101010101010"
                             :request_id "scheduled-phase5-cycle-001"})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        active-policy (rp/default-retrieval-policy)
        shadow-a (assoc active-policy :policy_id "heuristic_v1_shadow_phase5_a" :version "2026-04-04")
        shadow-b (assoc active-policy :policy_id "heuristic_v1_shadow_phase5_b" :version "2026-04-05")
        registry {:schema_version "1.0"
                  :policies [(rp/registry-entry active-policy {:state "active"})
                             (rp/registry-entry shadow-a {:state "shadow"})
                             (rp/registry-entry shadow-b {:state "shadow"})]}
        cycle (evaluation/scheduled-phase5-cycle {:root_path tmp-root
                                                  :usage_metrics metrics
                                                  :registry registry
                                                  :artifacts_dir artifacts-dir
                                                  :retention_runs 4
                                                  :auto_promote true})]
    (testing "top-level phase5 cycle writes its own retained artifact and index"
      (is (str/ends-with? (get-in cycle [:scheduled_run :artifact_path]) ".json"))
      (is (.exists (io/file (get-in cycle [:scheduled_run :artifact_path]))))
      (is (.exists (io/file (get-in cycle [:scheduled_run :manifest_path]))))
      (is (.exists (io/file (get-in cycle [:scheduled_run :phase5_index_path]))))
      (is (= 1 (count (get-in cycle [:phase5_index :runs]))))
      (is (= (get-in cycle [:scheduled_run :artifact_path])
             (get-in cycle [:phase5_index :runs 0 :artifact_paths :phase5_cycle]))))
    (testing "phase5 cycle manifest preserves direct pointers to underlying retained review and governance artifacts"
      (is (= (get-in cycle [:scheduled_run :artifact_path])
             (get-in cycle [:manifest :latest_artifact_path])))
      (is (= (get-in cycle [:governance_run_ref :artifact_path])
             (get-in cycle [:manifest :latest_governance_cycle_artifact_path])))
      (is (= (get-in cycle [:review_run_ref :artifact_path])
             (get-in cycle [:manifest :latest_policy_review_artifact_path])))
      (is (= (get-in cycle [:scheduled_run :phase5_index_path])
             (get-in cycle [:manifest :phase5_index_path]))))
    (testing "phase5 cycle snapshots current pending operator work for the same orchestration run"
      (is (= 1 (count (:current_queue_items cycle))))
      (is (= "multiple_eligible_candidates"
             (get-in cycle [:current_queue_items 0 :reason_code])))
      (is (= "choose_best_candidate_from_ranking"
             (get-in cycle [:current_queue_items 0 :required_action]))))
    (testing "phase5 cycle bundles the aggregate status view for the retained run set"
      (is (= 1 (get-in cycle [:status_report :summary :review_runs])))
      (is (= 1 (get-in cycle [:status_report :summary :governance_runs])))
      (is (= 1 (get-in cycle [:status_report :summary :pending_queue_items]))))))
