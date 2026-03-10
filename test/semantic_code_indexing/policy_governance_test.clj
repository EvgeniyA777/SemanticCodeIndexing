(ns semantic-code-indexing.policy-governance-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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
