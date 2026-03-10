(ns semantic-code-indexing.evaluation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semantic-code-indexing.runtime.evaluation :as evaluation]))

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
