(ns semantic-code-indexing.contracts.schemas)

(def schema-version [:re "^1\\.0$"])
(def uuid-str [:re "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"])
(def timestamp [:re "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"])
(def code [:re "^[a-z0-9_]+$"])
(def code-key
  [:fn
   {:error/message "should be a keyword matching ^[a-z0-9_]+$"}
   (fn [k] (and (keyword? k) (re-matches #"^[a-z0-9_]+$" (name k))))])
(def bounded-string [:string {:min 1 :max 240}])
(def bounded-long-string [:string {:min 1 :max 2000}])
(def string-array [:vector {:max 25} bounded-string])
(def code-array [:vector {:max 12} code])

(def coded-item
  [:map {:closed true}
   [:code code]
   [:summary bounded-string]])

(def coded-item-array [:vector {:max 12} coded-item])

(def confidence-level [:enum "high" "medium" "low"])
(def autonomy-posture [:enum "read_safe" "plan_safe" "draft_patch_safe" "autonomy_blocked"])
(def usage-surface [:enum "library" "mcp" "http" "grpc"])
(def usage-operation
  [:enum
   "server_start"
   "create_index"
   "update_index"
   "repo_map"
   "resolve_context"
   "impact_analysis"
   "skeletons"
   "cache_eviction"])
(def usage-status [:enum "success" "error"])
(def feedback-outcome [:enum "helpful" "partially_helpful" "not_helpful" "abandoned"])
(def followup-action [:enum "planned" "drafted" "patched" "discarded"])
(def retrieval-issue-code
  [:enum
   "resolved_target_correct"
   "missing_authority"
   "wrong_scope"
   "too_broad"
   "too_shallow"
   "latency_too_high"
   "confidence_miscalibrated"])
(def stage-name
  [:enum
   "query_validation"
   "candidate_generation"
   "ranking"
   "context_packet_assembly"
   "raw_code_fetch"
   "result_finalization"])
(def stage-outcome [:enum "completed" "degraded" "failed" "skipped"])
(def event-status [:enum "started" "completed" "degraded" "failed" "skipped"])
(def raw-fetch-level [:enum "none" "target_span" "enclosing_unit" "local_neighborhood" "whole_file"])
(def rank-band [:enum "top_authority" "useful_support" "exploratory" "below_threshold_noise"])
(def root-path-hash [:re "^[0-9a-f]{64}$"])
(def payload-value [:or bounded-string boolean? int? float?])
(def bounded-payload-map [:map-of {:max 16} code-key payload-value])
(def unit-kind
  [:enum
   "namespace"
   "module"
   "class"
   "type"
   "function"
   "method"
   "protocol"
   "interface"
   "section"
   "block"
   "test"])

(def span
  [:map {:closed true}
   [:path bounded-string]
   [:start_line pos-int?]
   [:end_line pos-int?]])

(def trace-ref
  [:map {:closed true}
   [:trace_id uuid-str]
   [:request_id bounded-string]
   [:task_id {:optional true} bounded-string]
   [:session_id {:optional true} bounded-string]
   [:actor_id {:optional true} bounded-string]])

(def metric-map [:map-of {:max 12} code-key [:or int? float?]])

(def confidence
  [:map {:closed true}
   [:schema_version schema-version]
   [:level confidence-level]
   [:score {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:reasons coded-item-array]
   [:warnings coded-item-array]
   [:missing_evidence coded-item-array]])

(def retrieval-policy-summary
  [:map {:closed true}
   [:policy_id bounded-string]
   [:version bounded-string]])

(def capability-summary
  [:map {:closed true}
   [:index_languages string-array]
   [:selected_languages string-array]
   [:parser_modes string-array]
   [:coverage_level [:enum "unknown" "full" "mixed" "fallback_only"]]
   [:fallback_unit_count nat-int?]
   [:selected_unit_count nat-int?]
   [:strong_languages string-array]
   [:index_snapshot_id bounded-string]])

(def guardrail-assessment
  [:map {:closed true}
   [:schema_version schema-version]
   [:autonomy_posture autonomy-posture]
   [:blocking_reasons coded-item-array]
   [:required_next_steps coded-item-array]
   [:allowed_action_scope
    [:map {:closed true}
     [:mode [:enum "analysis_only" "plan_only" "draft_patch_on_selected_unit_only"]]
     [:allow_multi_file_edit boolean?]
     [:allow_apply_without_human_review boolean?]
     [:max_raw_code_level {:optional true} raw-fetch-level]]]
   [:risk_flags coded-item-array]])

(def retrieval-intent
  [:map {:closed true}
   [:purpose [:enum "code_understanding" "change_impact" "edit_preparation" "test_targeting" "review_support" "bug_investigation"]]
   [:details {:optional true} bounded-long-string]])

(def retrieval-targets-base
  [:map {:closed true}
   [:paths {:optional true} [:vector {:min 1 :max 20} bounded-string]]
   [:symbols {:optional true} [:vector {:min 1 :max 20} bounded-string]]
   [:modules {:optional true} [:vector {:min 1 :max 20} bounded-string]]
   [:tests {:optional true} [:vector {:min 1 :max 20} bounded-string]]
   [:changed_spans {:optional true} [:vector {:min 1 :max 20} span]]
   [:diff_summary {:optional true} bounded-long-string]])

(def retrieval-targets
  [:and
   retrieval-targets-base
   [:fn
    {:error/message "targets must contain at least one target key"}
    (fn [m] (some #(contains? m %) [:paths :symbols :modules :tests :changed_spans :diff_summary]))]])

(def retrieval-query
  [:map {:closed true}
   [:schema_version schema-version]
   [:intent retrieval-intent]
   [:targets retrieval-targets]
   [:constraints
    [:map {:closed true}
     [:token_budget {:optional true} pos-int?]
     [:snapshot_id {:optional true} bounded-string]
     [:language_allowlist {:optional true} [:vector {:min 1 :max 12} bounded-string]]
     [:allowed_path_prefixes {:optional true} [:vector {:min 1 :max 20} bounded-string]]
     [:max_raw_code_level {:optional true} raw-fetch-level]
     [:freshness {:optional true} [:enum "current_snapshot" "allow_stale_if_flagged"]]]]
   [:hints
    [:map {:closed true}
     [:preferred_paths {:optional true} string-array]
     [:preferred_modules {:optional true} string-array]
     [:suspected_symbols {:optional true} string-array]
     [:focus_on_tests {:optional true} boolean?]
     [:prefer_definitions_over_callers {:optional true} boolean?]
     [:prefer_breadth_over_depth {:optional true} boolean?]]]
   [:options
    [:map {:closed true}
     [:include_tests {:optional true} boolean?]
     [:include_impact_hints {:optional true} boolean?]
     [:allow_raw_code_escalation {:optional true} boolean?]
     [:favor_compact_packet {:optional true} boolean?]
     [:favor_higher_recall {:optional true} boolean?]]]
   [:trace trace-ref]])

(def relevant-unit
  [:map {:closed true}
   [:unit_id bounded-string]
   [:kind unit-kind]
   [:symbol {:optional true} bounded-string]
   [:path bounded-string]
   [:span span]
   [:rank_band rank-band]])

(def skeleton
  [:map {:closed true}
   [:unit_id bounded-string]
   [:signature bounded-long-string]
   [:summary bounded-string]
   [:docstring_excerpt {:optional true} bounded-long-string]])

(def context-packet
  [:map {:closed true}
   [:schema_version schema-version]
   [:retrieval_policy {:optional true} retrieval-policy-summary]
   [:capabilities {:optional true} capability-summary]
   [:query
    [:map {:closed true}
     [:intent bounded-string]
     [:targets_summary string-array]
     [:constraints_summary string-array]
     [:hints_summary string-array]]]
   [:repo_map
    [:map {:closed true}
     [:focus_paths string-array]
     [:focus_modules string-array]
     [:summary bounded-long-string]]]
   [:relevant_units [:vector {:min 1 :max 20} relevant-unit]]
   [:skeletons [:vector {:min 1 :max 20} skeleton]]
   [:impact_hints
    [:map {:closed true}
     [:callers string-array]
     [:dependents string-array]
     [:related_tests string-array]
     [:risky_neighbors string-array]]]
   [:evidence
    [:map {:closed true}
     [:selection_reasons coded-item-array]
     [:hint_effects coded-item-array]]]
   [:budget
    [:map {:closed true}
     [:requested_tokens pos-int?]
     [:estimated_tokens nat-int?]
     [:truncation_flags string-array]]]
   [:confidence confidence]])

(def diagnostics-stage
  [:map {:closed true}
   [:name stage-name]
   [:status stage-outcome]
   [:summary bounded-string]
   [:counters metric-map]
   [:warnings coded-item-array]
   [:degradation_flags coded-item-array]
   [:duration_ms nat-int?]])

(def diagnostics-trace
  [:map {:closed true}
   [:schema_version schema-version]
   [:retrieval_policy {:optional true} retrieval-policy-summary]
   [:capabilities {:optional true} capability-summary]
   [:trace
    [:map {:closed true}
     [:trace_id uuid-str]
     [:request_id bounded-string]
     [:timestamp_start timestamp]
     [:timestamp_end timestamp]
     [:host_metadata {:optional true} [:map-of {:max 8} code-key [:or string? number? boolean?]]]]]
   [:query
    [:map {:closed true}
     [:intent bounded-string]
     [:targets_summary string-array]
     [:constraints_summary string-array]
     [:hints_summary string-array]
     [:options_summary string-array]
     [:validation_status [:enum "accepted" "accepted_with_normalization" "rejected"]]]]
   [:stages [:vector {:min 1 :max 10} diagnostics-stage]]
   [:result
    [:map {:closed true}
     [:selected_units_count nat-int?]
     [:selected_files_count nat-int?]
     [:raw_fetch_level_reached raw-fetch-level]
     [:packet_size_estimate nat-int?]
     [:top_authority_targets string-array]
     [:result_status [:enum "completed" "degraded" "failed"]]]]
   [:warnings coded-item-array]
   [:degradations coded-item-array]
   [:confidence confidence]
   [:guardrails guardrail-assessment]
   [:performance
    [:map {:closed true}
     [:total_duration_ms nat-int?]
     [:cache_summary metric-map]
     [:parser_summary metric-map]
     [:fetch_summary metric-map]
     [:budget_summary metric-map]]]])

(def retrieval-stage-event
  [:map {:closed true}
   [:schema_version schema-version]
   [:event_name [:re "^(query_validation|candidate_generation|ranking|context_packet_assembly|raw_code_fetch|result_finalization)\\.(started|completed|degraded|failed|skipped)$"]]
   [:timestamp timestamp]
   [:trace_id uuid-str]
   [:request_id bounded-string]
   [:stage stage-name]
   [:status event-status]
   [:summary bounded-string]
   [:counters metric-map]
   [:task_id {:optional true} bounded-string]
   [:session_id {:optional true} bounded-string]
   [:snapshot_id {:optional true} bounded-string]
   [:query_intent {:optional true} bounded-string]
   [:warning_codes {:optional true} code-array]
   [:degradation_codes {:optional true} code-array]
   [:duration_ms {:optional true} nat-int?]
   [:budget_summary {:optional true} metric-map]
   [:redaction_level {:optional true} [:enum "default_safe" "strict" "debug_expanded"]]])

(def override-record
  [:map {:closed true}
   [:schema_version schema-version]
   [:override_id uuid-str]
   [:trace_id uuid-str]
   [:original_autonomy_posture autonomy-posture]
   [:original_blocking_reasons coded-item-array]
   [:requested_action
    [:map {:closed true}
     [:action_class [:enum "read" "plan" "draft_patch" "apply_patch"]]
     [:scope bounded-string]
     [:multi_file {:optional true} boolean?]
     [:apply_requested {:optional true} boolean?]]]
   [:override_reason coded-item]
   [:actor_id bounded-string]
   [:human_review_status [:enum "required_pending" "approved" "rejected" "waived_by_policy"]]
   [:timestamp timestamp]])

(def human-review-record
  [:map {:closed true}
   [:schema_version schema-version]
   [:review_id uuid-str]
   [:trace_id uuid-str]
   [:override_id uuid-str]
   [:review_status [:enum "required_pending" "approved" "rejected" "waived_by_policy"]]
   [:reviewer_id bounded-string]
   [:timestamp timestamp]
   [:rationale coded-item]])

(def usage-event
  [:map {:closed true}
   [:schema_version schema-version]
   [:event_id uuid-str]
   [:occurred_at timestamp]
   [:surface usage-surface]
   [:operation usage-operation]
   [:status usage-status]
   [:trace_id {:optional true} uuid-str]
   [:request_id {:optional true} bounded-string]
   [:session_id {:optional true} bounded-string]
   [:task_id {:optional true} bounded-string]
   [:actor_id {:optional true} bounded-string]
   [:tenant_id {:optional true} bounded-string]
   [:root_path_hash {:optional true} root-path-hash]
   [:latency_ms {:optional true} nat-int?]
   [:file_count {:optional true} nat-int?]
   [:unit_count {:optional true} nat-int?]
   [:selected_units_count {:optional true} nat-int?]
   [:selected_files_count {:optional true} nat-int?]
   [:cache_hit {:optional true} boolean?]
   [:confidence_level {:optional true} confidence-level]
   [:autonomy_posture {:optional true} autonomy-posture]
   [:result_status {:optional true} [:enum "completed" "degraded" "failed"]]
   [:raw_fetch_level {:optional true} raw-fetch-level]
   [:payload bounded-payload-map]])

(def usage-feedback
  [:map {:closed true}
   [:schema_version schema-version]
   [:feedback_id uuid-str]
   [:occurred_at timestamp]
   [:surface usage-surface]
   [:operation usage-operation]
   [:feedback_outcome feedback-outcome]
   [:trace_id {:optional true} uuid-str]
   [:request_id {:optional true} bounded-string]
   [:session_id {:optional true} bounded-string]
   [:task_id {:optional true} bounded-string]
   [:actor_id {:optional true} bounded-string]
   [:tenant_id {:optional true} bounded-string]
   [:root_path_hash {:optional true} root-path-hash]
   [:feedback_reason {:optional true} bounded-string]
   [:followup_action {:optional true} followup-action]
   [:confidence_level {:optional true} confidence-level]
   [:retrieval_issue_codes {:optional true} [:vector {:max 12} retrieval-issue-code]]
   [:ground_truth_unit_ids {:optional true} string-array]
   [:ground_truth_paths {:optional true} string-array]
   [:payload bounded-payload-map]])

(def example-catalog-entry
  [:map {:closed true}
   [:example_id bounded-string]
   [:contract_family bounded-string]
   [:path bounded-string]
   [:schema bounded-string]
   [:adr_refs [:vector {:min 1 :max 10} bounded-string]]
   [:purpose bounded-long-string]
   [:expected_interpretation bounded-long-string]])

(def example-catalog
  [:map {:closed true}
   [:schema_version schema-version]
   [:examples [:vector {:min 1} example-catalog-entry]]])

(def fixture-corpus-entry
  [:map {:closed true}
   [:fixture_id bounded-string]
   [:category bounded-string]
   [:path bounded-string]
   [:purpose bounded-long-string]])

(def fixture-corpus
  [:map {:closed true}
   [:schema_version schema-version]
   [:suite bounded-string]
   [:fixtures [:vector {:min 1} fixture-corpus-entry]]])

(def retrieval-fixture
  [:map {:closed true}
   [:fixture_id bounded-string]
   [:category bounded-string]
   [:purpose bounded-long-string]
   [:adr_refs [:vector {:min 1 :max 10} bounded-string]]
   [:snapshot_ref bounded-string]
   [:input [:map {:closed true} [:query retrieval-query]]]
   ;; expected is intentionally open so behavior bands can evolve
   [:expected :map]])

(def contracts
  {:example/catalog example-catalog
   :example/query retrieval-query
   :example/context-packet context-packet
   :example/diagnostics-trace diagnostics-trace
   :example/stage-event retrieval-stage-event
   :example/usage-event usage-event
   :example/usage-feedback usage-feedback
   :example/confidence confidence
   :example/guardrail-assessment guardrail-assessment
   :example/override-record override-record
   :example/human-review-record human-review-record
   :fixture/corpus fixture-corpus
   :fixture/retrieval retrieval-fixture})
