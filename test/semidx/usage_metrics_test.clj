(ns semidx.usage-metrics-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]
            [semidx.mcp.server :as mcp-server]
            [semidx.runtime.usage-metrics :as usage]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- create-sample-repo! [root]
  (write-file! root "src/my/app/order.clj"
               "(ns my.app.order)\n\n(defn process-order [ctx order]\n  (validate-order order))\n\n(defn validate-order [order]\n  (if (:id order)\n    order\n    (throw (ex-info \"invalid\" {}))))\n")
  (write-file! root "test/my/app/order_test.clj"
               "(ns my.app.order-test\n  (:require [clojure.test :refer [deftest is]]\n            [my.app.order :as order]))\n\n(deftest process-order-test\n  (is (map? (order/validate-order {:id 1}))))\n"))

(def sample-query
  {:api_version "1.0"
   :schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate process-order authority and close tests."}
   :targets {:symbols ["my.app.order/process-order"]
             :paths ["src/my/app/order.clj"]}
   :constraints {:token_budget 1200
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:focus_on_tests true
           :prefer_definitions_over_callers true}
   :options {:include_tests true
             :include_impact_hints true
             :allow_raw_code_escalation false}
   :trace {:trace_id "77777777-7777-4777-8777-777777777777"
           :request_id "usage-metrics-test-001"
           :actor_id "test_runner"}})

(def sample-shorthand-query
  {:intent "Find the main orchestration flow for process-order."
   :targets {:paths ["src/my/app/order.clj"]}})

(deftest library-usage-metrics-flow-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-lib" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink
                                 :usage_context {:session_id "session-001"
                                                 :task_id "task-001"
                                                 :actor_id "library-agent"}})
        _repo-map (sci/repo-map index)
        result (sci/resolve-context index sample-query)
        _expand (sci/expand-context index {:selection_id (:selection_id result)
                                           :snapshot_id (:snapshot_id result)
                                           :include_impact_hints true})
        _detail (sci/fetch-context-detail index {:selection_id (:selection_id result)
                                                 :snapshot_id (:snapshot_id result)})
        _literal (sci/literal-file-slice index {:snapshot_id (:snapshot_id index)
                                                :path "src/my/app/order.clj"
                                                :start_line 3
                                                :end_line 4})
        _impact (sci/impact-analysis index sample-query)
        _skeletons (sci/skeletons index {:paths ["src/my/app/order.clj"]})
        _feedback (sci/record-feedback! index {:trace_id (get-in sample-query [:trace :trace_id])
                                               :request_id (get-in sample-query [:trace :request_id])
                                               :feedback_outcome "helpful"
                                               :followup_action "planned"
                                               :confidence_level "high"
                                               :retrieval_issue_codes ["resolved_target_correct"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        events (usage/emitted-events sink)
        feedback (usage/emitted-feedback sink)
        create-event (first events)
        resolve-event (last (filter #(= "resolve_context" (:operation %)) events))
        expand-event (last (filter #(= "expand_context" (:operation %)) events))
        detail-event (last (filter #(= "fetch_context_detail" (:operation %)) events))
        literal-event (last (filter #(= "literal_file_slice" (:operation %)) events))]
    (testing "library events inherit sink and context from index"
      (is (>= (count events) 4))
      (is (= "library" (:surface create-event)))
      (is (= "session-001" (:session_id create-event)))
      (is (= "task-001" (:task_id create-event)))
      (is (= "create_index" (:operation create-event)))
      (is (= "success" (:status create-event))))
    (testing "resolve_context emits usefulness-oriented summaries"
      (is (= "resolve_context" (:operation resolve-event)))
      (is (= "success" (:status resolve-event)))
      (is (= "usage-metrics-test-001" (:request_id resolve-event)))
      (is (= (:confidence_level result) (:confidence_level resolve-event)))
      (is (pos-int? (:selected_units_count resolve-event)))
      (is (string? (:root_path_hash resolve-event)))
      (is (= "heuristic_v1" (get-in resolve-event [:payload :policy_id])))
      (is (= "2026-03-10" (get-in resolve-event [:payload :policy_version])))
      (is (= "selection" (get-in resolve-event [:payload :stage_name]))))
    (testing "expand/detail stages emit stage-aware token and budget payloads"
      (is (= "expand_context" (:operation expand-event)))
      (is (= "expand" (get-in expand-event [:payload :stage_name])))
      (is (<= (get-in expand-event [:payload :returned_tokens])
              (get-in expand-event [:payload :reserved_tokens])))
      (is (= "fetch_context_detail" (:operation detail-event)))
      (is (= "detail" (get-in detail-event [:payload :stage_name])))
      (is (<= (get-in detail-event [:payload :returned_tokens])
              (get-in detail-event [:payload :reserved_tokens])))
      (is (= "literal_file_slice" (:operation literal-event)))
      (is (= "src/my/app/order.clj" (get-in literal-event [:payload :path])))
      (is (= {:start_line 3 :end_line 4} (get-in literal-event [:payload :returned_range]))))
    (testing "explicit host feedback is recorded separately"
      (is (= 1 (count feedback)))
      (is (= "helpful" (:feedback_outcome (first feedback))))
      (is (= "session-001" (:session_id (first feedback))))
      (is (= ["resolved_target_correct"] (:retrieval_issue_codes (first feedback))))
      (is (= ["src/my/app/order.clj"] (:ground_truth_paths (first feedback)))))))

(deftest suppressed-library-metrics-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-suppress" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)]
    (sci/create-index {:root_path tmp-root
                       :usage_metrics sink
                       :suppress_usage_metrics true})
    (is (empty? (usage/emitted-events sink)))))

(deftest mcp-tool-usage-metrics-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-mcp" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        allowed-root (.getCanonicalPath (io/file tmp-root))
        sink (sci/in-memory-usage-metrics)
        state (atom {:allowed-roots [allowed-root]
                     :max-indexes 4
                     :session_id "mcp-session-001"
                     :usage_metrics sink
                     :selection_cache (atom {})
                     :indexes-by-id {}
                     :cache-key->index-id {}
                     :client-info {:name "codex-test-client"}})
        create-response (#'mcp-server/handle-tools-call state {:name "create_index"
                                                               :arguments {:root_path tmp-root}})
        cached-response (#'mcp-server/handle-tools-call state {:name "create_index"
                                                               :arguments {:root_path tmp-root}})
        index-id (get-in create-response [:structuredContent :index_id])
        _resolve-response (#'mcp-server/handle-tools-call state {:name "resolve_context"
                                                                 :arguments {:index_id index-id
                                                                             :query sample-query}})
        _literal-response (#'mcp-server/handle-tools-call state {:name "literal_file_slice"
                                                                 :arguments {:index_id index-id
                                                                             :snapshot_id (get-in create-response [:structuredContent :snapshot_id])
                                                                             :path "src/my/app/order.clj"
                                                                             :start_line 3
                                                                             :end_line 4}})
        create-events (filter #(= "create_index" (:operation %)) (usage/emitted-events sink))
        resolve-event (last (filter #(= "resolve_context" (:operation %)) (usage/emitted-events sink)))
        literal-event (last (filter #(= "literal_file_slice" (:operation %)) (usage/emitted-events sink)))]
    (testing "mcp create_index records cache miss then hit"
      (is (= 2 (count create-events)))
      (is (false? (:cache_hit (first create-events))))
      (is (true? (:cache_hit (second create-events))))
      (is (= "mcp" (:surface (first create-events))))
      (is (= "mcp-session-001" (:session_id (first create-events)))))
    (testing "mcp resolve_context records query correlation fields"
      (is (= "resolve_context" (:operation resolve-event)))
      (is (= "usage-metrics-test-001" (:request_id resolve-event)))
      (is (= "success" (:status resolve-event)))
      (is (pos-int? (:selected_units_count resolve-event)))
      (is (= "2026-03-10" (get-in resolve-event [:payload :policy_version]))))
    (testing "mcp literal_file_slice records exact-read payloads"
      (is (= "literal_file_slice" (:operation literal-event)))
      (is (= "src/my/app/order.clj" (get-in literal-event [:payload :path])))
      (is (= {:start_line 3 :end_line 4} (get-in literal-event [:payload :returned_range]))))
    (testing "tool responses are still successful"
      (is (not (true? (get-in create-response [:structuredContent :isError]))))
      (is (not (true? (get-in cached-response [:structuredContent :isError])))))))

(deftest compact-mcp-query-memory-summarizes-normalized-resolve-context-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-mcp-memory" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        allowed-root (.getCanonicalPath (io/file tmp-root))
        sink (sci/in-memory-usage-metrics)
        state (atom {:allowed-roots [allowed-root]
                     :max-indexes 4
                     :session_id "mcp-session-memory-001"
                     :usage_metrics sink
                     :selection_cache (atom {})
                     :indexes-by-id {}
                     :cache-key->index-id {}
                     :client-info {:name "codex-test-client"}})
        create-response (#'mcp-server/handle-tools-call state {:name "create_index"
                                                               :arguments {:root_path tmp-root}})
        index-id (get-in create-response [:structuredContent :index_id])
        _resolve-response (#'mcp-server/handle-tools-call state {:name "resolve_context"
                                                                 :arguments {:index_id index-id
                                                                             :query sample-shorthand-query}})
        memory (sci/compact-mcp-query-memory sink)
        entry (first (:entries memory))]
    (testing "report scope and counts stay compact and MCP-specific"
      (is (= "mcp" (get-in memory [:scope :surface])))
      (is (= 1 (get-in memory [:summary :entries])))
      (is (= {"mcp_shorthand" 1}
             (get-in memory [:summary :ingress_mode_counts]))))
    (testing "entries retain normalized summary and continuation artifact"
      (is (= "mcp_shorthand" (:query_ingress_mode entry)))
      (is (true? (:query_normalized entry)))
      (is (= "code_understanding"
             (get-in entry [:normalized_query_summary :purpose])))
      (is (= ["paths"]
             (get-in entry [:normalized_query_summary :target_keys])))
      (is (= "expand_context"
             (get-in entry [:continuation :recommended_next_step])))
      (is (string? (get-in entry [:continuation :selection_id])))
      (is (string? (get-in entry [:continuation :snapshot_id]))))))

(deftest slo-report-aggregates-operational-metrics-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-slo" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        allowed-root (.getCanonicalPath (io/file tmp-root))
        sink (sci/in-memory-usage-metrics)
        _index (sci/create-index {:root_path tmp-root
                                  :usage_metrics sink
                                  :usage_context {:session_id "session-slo"}})
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink
                                 :usage_context {:session_id "session-slo"}})
        selection (sci/resolve-context index sample-query)
        _expand (sci/expand-context index {:selection_id (:selection_id selection)
                                           :snapshot_id (:snapshot_id selection)
                                           :include_impact_hints true})
        _detail (sci/fetch-context-detail index {:selection_id (:selection_id selection)
                                                 :snapshot_id (:snapshot_id selection)})
        state (atom {:allowed-roots [allowed-root]
                     :max-indexes 4
                     :session_id "mcp-slo-session"
                     :usage_metrics sink
                     :selection_cache (atom {})
                     :indexes-by-id {}
                     :cache-key->index-id {}
                     :client-info {:name "codex-test-client"}})
        _mcp-create (#'mcp-server/handle-tools-call state {:name "create_index"
                                                           :arguments {:root_path tmp-root}})
        _mcp-create-hit (#'mcp-server/handle-tools-call state {:name "create_index"
                                                               :arguments {:root_path tmp-root}})
        report (sci/slo-report sink)
        retrieval-only (sci/slo-report sink {:operation "resolve_context"})]
    (testing "report exposes the requested SLO-facing metrics"
      (is (contains? report :index_latency_ms))
      (is (contains? report :retrieval_latency_ms))
      (is (contains? report :cache_hit_ratio))
      (is (contains? report :degraded_rate))
      (is (contains? report :fallback_rate))
      (is (contains? report :stage_latency_ms))
      (is (contains? report :stage_token_footprint))
      (is (contains? report :stage_budget_outcomes))
      (is (contains? (:stage_latency_ms report) "selection"))
      (is (contains? (:stage_latency_ms report) "expand"))
      (is (contains? (:stage_latency_ms report) "detail"))
      (is (= {"heuristic_v1@2026-03-10" 1}
             (:policy_version_distribution retrieval-only))))
    (testing "cache-hit ratio observes create_index cache hits"
      (is (<= 0.0 (:cache_hit_ratio report) 1.0))
      (is (= 0.5 (:cache_hit_ratio report))))
    (testing "latency summaries include counts"
      (is (pos? (get-in report [:index_latency_ms :count])))
      (is (pos? (get-in retrieval-only [:retrieval_latency_ms :count])))
      (is (pos? (get-in report [:stage_token_footprint "detail" :returned_tokens :count]))))))

(deftest harvest-replay-dataset-builds-query-expected-shape-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-harvest" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        query (assoc-in sample-query [:trace :session_id] "harvest-session-001")
        query (assoc-in query [:trace :task_id] "harvest-task-001")
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "helpful"
                                               :followup_action "planned"
                                               :confidence_level "high"
                                               :retrieval_issue_codes ["resolved_target_correct"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        dataset (sci/harvest-replay-dataset sink)
        harvested (first (:queries dataset))]
    (testing "dataset preserves query and expected replay contract"
      (is (= "1.0" (:schema_version dataset)))
      (is (= 1 (get-in dataset [:source_summary :harvested_queries])))
      (is (= query (:query harvested)))
      (is (= ["src/my/app/order.clj::my.app.order/process-order"]
             (get-in harvested [:expected :top_authority_unit_ids])))
      (is (= ["src/my/app/order.clj"]
             (get-in harvested [:expected :required_paths])))
      (is (false? (:protected_case harvested))))
    (testing "harvest metadata links query, selection, and feedback outcome"
      (is (= ["helpful"] (get-in harvested [:harvest :feedback_outcomes])))
      (is (= "harvest-session-001" (get-in harvested [:harvest :session_id])))
      (is (= "high" (get-in harvested [:harvest :outcome_summary :confidence_level]))))))

(deftest harvest-replay-dataset-promotes-difficult-cases-to-protected-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-harvest-protected" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink})
        _result (sci/resolve-context index sample-query)
        _feedback (sci/record-feedback! index {:trace_id (get-in sample-query [:trace :trace_id])
                                               :request_id (get-in sample-query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "low"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        dataset (sci/harvest-replay-dataset sink)
        harvested (first (:queries dataset))]
    (testing "difficult outcomes become protected replay cases"
      (is (true? (:protected_case harvested)))
      (is (= ["missing_authority"] (get-in harvested [:harvest :retrieval_issue_codes])))
      (is (= ["src/my/app/order.clj"] (get-in harvested [:expected :required_paths]))))
    (testing "source summary counts protected cases"
      (is (= 1 (get-in dataset [:source_summary :protected_cases]))))))

(deftest calibration-report-correlates-confidence-with-feedback-outcomes-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-calibration" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        high-query (assoc sample-query
                          :trace {:trace_id "88888888-8888-4888-8888-888888888888"
                                  :request_id "usage-metrics-calibration-high"
                                  :actor_id "test_runner"})
        low-query (assoc sample-query
                         :trace {:trace_id "99999999-9999-4999-8999-999999999999"
                                 :request_id "usage-metrics-calibration-low"
                                 :actor_id "test_runner"})
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink})
        _high (sci/resolve-context index high-query)
        _low (sci/resolve-context index low-query)
        _feedback-high (sci/record-feedback! index {:trace_id (get-in high-query [:trace :trace_id])
                                                    :request_id (get-in high-query [:trace :request_id])
                                                    :feedback_outcome "helpful"
                                                    :followup_action "planned"
                                                    :confidence_level "high"})
        _feedback-low (sci/record-feedback! index {:trace_id (get-in low-query [:trace :trace_id])
                                                   :request_id (get-in low-query [:trace :request_id])
                                                   :feedback_outcome "not_helpful"
                                                   :followup_action "discarded"
                                                   :confidence_level "low"})
        events (usage/emitted-events sink)
        _patched-events (swap! (:state sink)
                               update :events
                               (fn [rows]
                                 (mapv (fn [row]
                                         (cond
                                           (= "usage-metrics-calibration-high" (:request_id row))
                                           (assoc row
                                                  :confidence_level "high"
                                                  :payload (assoc (:payload row)
                                                                  :outcome_summary {:confidence_level "high"
                                                                                    :confidence_score 0.9}))

                                           (= "usage-metrics-calibration-low" (:request_id row))
                                           (assoc row
                                                  :confidence_level "low"
                                                  :payload (assoc (:payload row)
                                                                  :outcome_summary {:confidence_level "low"
                                                                                    :confidence_score 0.2}))

                                           :else row))
                                       rows)))
        report (sci/calibration-report sink)]
    (testing "report summarizes correlated queries by confidence band"
      (is (= 2 (get-in report [:totals :correlated_queries])))
      (is (= 1 (get-in report [:calibration :by_confidence_level "high" :count])))
      (is (= 1 (get-in report [:calibration :by_confidence_level "low" :count]))))
    (testing "observed helpfulness tracks predicted confidence directionally"
      (is (= 1.0 (get-in report [:calibration :by_confidence_level "high" :observed_feedback_score])))
      (is (= 0.0 (get-in report [:calibration :by_confidence_level "low" :observed_feedback_score])))
      (is (> (get-in report [:calibration :by_confidence_level "high" :mean_predicted_confidence])
             (get-in report [:calibration :by_confidence_level "low" :mean_predicted_confidence]))))
    (testing "overall calibration includes MAE"
      (is (number? (get-in report [:calibration :mean_absolute_error])))
      (is (<= 0.0 (get-in report [:calibration :mean_absolute_error]) 1.0)))))

(deftest weekly-review-report-links-query-context-feedback-and-outcome-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-usage-metrics-weekly-review" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        query (assoc sample-query
                     :trace {:trace_id "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                             :request_id "usage-metrics-weekly-review-001"
                             :session_id "weekly-session-001"
                             :task_id "weekly-task-001"
                             :actor_id "weekly-reviewer"})
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "low"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        report (sci/weekly-review-report sink)
        entry (first (:entries report))]
    (testing "summary counts linked review cases"
      (is (= 1 (get-in report [:summary :total_queries])))
      (is (= 1 (get-in report [:summary :correlated_queries])))
      (is (= 1 (get-in report [:summary :protected_cases])))
      (is (= {"not_helpful" 1} (get-in report [:summary :feedback_outcome_counts]))))
    (testing "entry links query to selected context, feedback, and outcome"
      (is (= query (:query entry)))
      (is (= "usage-metrics-weekly-review-001" (:query_id entry)))
      (is (= "weekly-session-001" (get-in entry [:trace :session_id])))
      (is (seq (get-in entry [:selected_context :selected_unit_ids])))
      (is (= ["not_helpful"] (get-in entry [:feedback :feedback_outcomes])))
      (is (= ["missing_authority"] (get-in entry [:feedback :retrieval_issue_codes])))
      (is (= ["src/my/app/order.clj"] (get-in entry [:feedback :ground_truth_paths])))
      (is (contains? (get-in entry [:outcome_summary]) :confidence_level))
      (is (true? (:protected_case entry))))
    (testing "report carries calibration summary too"
      (is (contains? report :calibration))
      (is (= 1 (get-in report [:calibration :total_correlated_queries]))))))

(deftest review-report->protected-replay-dataset-builds-governance-ready-shape-test
  (let [tmp-root (str (java.nio.file.Files/createTempDirectory "sci-protected-replay-builder" (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (create-sample-repo! tmp-root)
        sink (sci/in-memory-usage-metrics)
        query (assoc sample-query
                     :trace {:trace_id "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                             :request_id "protected-replay-builder-001"
                             :session_id "protected-session-001"
                             :task_id "protected-task-001"
                             :actor_id "protected-builder"})
        index (sci/create-index {:root_path tmp-root
                                 :usage_metrics sink})
        _result (sci/resolve-context index query)
        _feedback (sci/record-feedback! index {:trace_id (get-in query [:trace :trace_id])
                                               :request_id (get-in query [:trace :request_id])
                                               :feedback_outcome "not_helpful"
                                               :followup_action "discarded"
                                               :confidence_level "medium"
                                               :retrieval_issue_codes ["missing_authority"]
                                               :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                                               :ground_truth_paths ["src/my/app/order.clj"]})
        weekly-review (sci/weekly-review-report sink)
        dataset (sci/review-report->protected-replay-dataset weekly-review)
        harvested (first (:queries dataset))]
    (testing "only protected review cases become protected replay queries"
      (is (= "1.0" (:schema_version dataset)))
      (is (= 1 (get-in dataset [:source_summary :protected_review_entries])))
      (is (= 1 (count (:queries dataset))))
      (is (true? (:protected_case harvested))))
    (testing "expected block is governance-compatible"
      (is (= query (:query harvested)))
      (is (= "medium" (get-in harvested [:expected :min_confidence_level])))
      (is (= ["src/my/app/order.clj::my.app.order/process-order"]
             (get-in harvested [:expected :top_authority_unit_ids])))
      (is (= ["src/my/app/order.clj"]
             (get-in harvested [:expected :required_paths]))))
    (testing "source review context is preserved for auditability"
      (is (= "not_helpful" (first (get-in harvested [:source_review :feedback :feedback_outcomes]))))
      (is (= "protected-replay-builder-001"
             (get-in harvested [:source_review :trace :request_id]))))))
