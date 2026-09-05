(ns semidx.contracts.schemas)

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
   "expand_context"
   "fetch_context_detail"
   "impact_analysis"
   "traverse_relations"
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

(def provider
  [:map {:closed true}
   [:provider_id bounded-string]
   [:provider_version bounded-string]
   [:classification bounded-string]])

(def language-capability
  [:map {:closed true}
   [:language bounded-string]
   [:extensions string-array]
   [:provider provider]
   [:strength confidence-level]
   [:confidence_ceiling confidence-level]])

(def server-info
  [:map {:closed true}
   [:name bounded-string]
   [:version bounded-string]])

(def language-policy-option
  [:enum "clojure" "java" "elixir" "python" "typescript" "javascript" "lua" "zig" "html" "css"])

(def capabilities
  [:map {:closed true}
   [:capability_version bounded-string]
   [:server server-info]
   [:languages [:vector {:min 1} language-capability]]
   [:language_policy_options [:vector language-policy-option]]])

(def capability-summary
  [:map {:closed true}
   [:index_languages string-array]
   [:selected_languages string-array]
   [:parser_modes string-array]
   [:coverage_level [:enum "unknown" "full" "mixed" "fallback_only"]]
   [:fallback_unit_count nat-int?]
   [:selected_unit_count nat-int?]
   [:strong_languages string-array]
   [:selected_language_strengths {:optional true} [:map-of {:max 12} bounded-string confidence-level]]
   [:confidence_ceiling {:optional true} confidence-level]
   [:index_age_seconds {:optional true} nat-int?]
   [:index_stale {:optional true} boolean?]
   [:snapshot_pinned {:optional true} boolean?]
   [:index_provenance_source {:optional true} bounded-string]
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
   [:api_version {:optional true} bounded-string]
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
     [:allow_raw_code_escalation {:optional true} boolean?]]]
   [:trace trace-ref]])

(def compact-focus-unit
  [:map {:closed true}
   [:unit_id bounded-string]
   [:symbol {:optional true} bounded-string]
   [:path bounded-string]
   [:span span]
   [:rank_band rank-band]
   [:why_selected string-array]])

(def next-step
  [:map {:closed true}
   [:recommended_action bounded-string]
   [:available_actions string-array]
   [:reason bounded-string]
   [:target_unit_ids string-array]])

(def selection-budget-summary
  [:map {:closed true}
   [:requested_tokens pos-int?]
   [:estimated_tokens nat-int?]
   [:within_budget boolean?]
   [:remaining_tokens nat-int?]
   [:reserved_budget
    [:map {:closed true}
     [:selection_tokens nat-int?]
     [:expansion_tokens nat-int?]
     [:detail_tokens nat-int?]]]])

(def selection-result
  [:map {:closed true}
   [:api_version bounded-string]
   [:selection_id bounded-string]
   [:snapshot_id bounded-string]
   [:result_status [:enum "completed" "insufficient_evidence" "budget_exhausted_at_selection"]]
   [:confidence_level confidence-level]
   [:budget_summary selection-budget-summary]
   [:focus [:vector {:max 5} compact-focus-unit]]
   [:next_step next-step]])

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

(def relation-support
  "Bounded, reason-coded relation-backed impact support. Additive to the legacy
  caller/callee/dependent hints: `downstream`/`upstream` are distinct unit
  display strings reached only through resolved dataflow relations, and
  `reasons` carries the codes explaining the projection."
  [:map {:closed true}
   [:downstream string-array]
   [:upstream string-array]
   [:reasons coded-item-array]])

(def state-invariant-unit-ref
  "Bounded unit reference inside a state-invariant packet. Carries only display
  facts the index already has; `path` and `symbol` are optional because some
  referenced units expose only an identifier."
  [:map {:closed true}
   [:unit_id bounded-string]
   [:path {:optional true} bounded-string]
   [:symbol {:optional true} bounded-string]])

(def state-invariants
  "Additive, versioned plans/016 Slice-1 state-invariant packet embedded in the
  impact_analysis hint map for state/lifecycle/persistence queries. Every list is
  bounded to the take-12 discipline; field-level write and preservation facts are
  deliberately absent, and the guardrail directs the agent to read whole files."
  [:map {:closed true}
   [:packet_version [:re "^[0-9]+\\.[0-9]+$"]]
   [:triggered_by code-array]
   [:entity_candidates [:vector {:max 12} state-invariant-unit-ref]]
   [:entity_fields {:optional true}
    [:vector {:max 12}
     [:map {:closed true}
      [:entity bounded-string]
      [:path {:optional true} bounded-string]
      [:fields
       [:vector {:max 24}
        [:map {:closed true}
         [:name bounded-string]
         [:nullable {:optional true} boolean?]
         [:annotations {:optional true} string-array]
         [:state_bearing {:optional true} boolean?]]]]]]]
   [:state_writers [:vector {:max 12} state-invariant-unit-ref]]
   [:field_writes {:optional true}
    [:vector {:max 12}
     [:map {:closed true}
      [:unit_id bounded-string]
      [:symbol {:optional true} bounded-string]
      [:writes [:vector {:max 24} bounded-string]]]]]
   [:assertion_tests [:vector {:max 12} bounded-string]]
   [:fixture_helpers [:vector {:max 12} state-invariant-unit-ref]]
   [:guardrail
    [:map {:closed true}
     [:code code]
     [:recommendation bounded-long-string]]]])

(def expansion-result
  [:map {:closed true}
   [:api_version bounded-string]
   [:selection_id bounded-string]
   [:snapshot_id bounded-string]
   [:result_status [:enum "completed" "truncated" "budget_exhausted"]]
   [:budget_summary
    [:map {:closed true}
     [:reserved_tokens nat-int?]
     [:estimated_tokens nat-int?]
     [:returned_tokens nat-int?]
     [:within_budget boolean?]
     [:truncation_flags string-array]]]
   [:skeletons [:vector {:max 20} skeleton]]
   [:impact_hints {:optional true}
    [:map {:closed true}
     [:callers string-array]
     [:dependents string-array]
     [:related_tests string-array]
     [:risky_neighbors string-array]
     [:relation_support {:optional true} relation-support]]]
   [:state_invariants {:optional true} state-invariants]])

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
   [:relevant_units [:vector {:max 20} relevant-unit]]
   [:skeletons [:vector {:max 20} skeleton]]
   [:impact_hints
    [:map {:closed true}
     [:callers string-array]
     [:dependents string-array]
     [:related_tests string-array]
     [:risky_neighbors string-array]
     [:relation_support {:optional true} relation-support]]]
   [:state_invariants {:optional true} state-invariants]
   [:evidence
    [:map {:closed true}
     [:selection_reasons coded-item-array]
     [:hint_effects coded-item-array]]]
   [:budget
    [:map {:closed true}
     [:requested_tokens pos-int?]
     [:reserved_tokens {:optional true} nat-int?]
     [:estimated_tokens nat-int?]
     [:returned_tokens {:optional true} nat-int?]
     [:suggested_token_budget {:optional true} pos-int?]
     [:stage_result_status {:optional true} bounded-string]
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

(def relation-traversal-node
  [:map {:closed true}
   [:unit_id bounded-string]
   [:depth nat-int?]])

(def relation-traversal-edge
  [:map {:closed true}
   [:relation_id bounded-string]
   [:from bounded-string]
   [:to bounded-string]
   [:relation_type bounded-string]
   [:resolution_status [:enum "resolved" "ambiguous" "unresolved"]]
   [:depth pos-int?]])

(def relation-traversal-query
  "Public bounded relation-traversal request (ADR-040). Reuses the Stage 3
  traversal kernel semantics; it is not a general-purpose graph-query language."
  [:map {:closed true}
   [:api_version {:optional true} bounded-string]
   [:schema_version schema-version]
   [:start_nodes [:vector {:min 1 :max 200} bounded-string]]
   [:direction [:enum "downstream" "upstream"]]
   [:relation_types {:optional true} [:vector {:min 1 :max 12} bounded-string]]
   [:resolved_only {:optional true} boolean?]
   [:budgets {:optional true}
    [:map {:closed true}
     [:max_depth {:optional true} nat-int?]
     [:max_nodes {:optional true} pos-int?]
     [:max_discovery_paths {:optional true} nat-int?]]]
   [:snapshot_id {:optional true} bounded-string]
   [:trace trace-ref]])

(def relation-traversal-result
  "Compact, snapshot-bound relation-traversal result (ADR-040). Ordering is
  deterministic and mirrors the Stage 3 traversal kernel output."
  [:map {:closed true}
   [:api_version {:optional true} bounded-string]
   [:schema_version schema-version]
   [:snapshot_id bounded-string]
   [:direction [:enum "downstream" "upstream"]]
   [:start_nodes [:vector {:max 200} bounded-string]]
   [:relation_types [:vector {:max 12} bounded-string]]
   [:budgets
    [:map {:closed true}
     [:max_depth nat-int?]
     [:max_nodes pos-int?]
     [:max_discovery_paths nat-int?]
     [:resolved_only boolean?]]]
   [:nodes [:vector {:max 200} relation-traversal-node]]
   [:edges [:vector {:max 1000} relation-traversal-edge]]
   [:discovery_paths [:vector {:max 50} [:vector {:max 4} bounded-string]]]
   [:truncated
    [:map {:closed true}
     [:max_depth boolean?]
     [:max_nodes boolean?]
     [:max_discovery_paths boolean?]]]
   [:selection_id {:optional true} bounded-string]])

(def policy-lifecycle-request
  [:or
   [:map {:closed true}
    [:api_version {:optional true} bounded-string]
    [:schema_version schema-version]
    [:policy_id bounded-string]
    [:version bounded-string]
    [:decision_id bounded-string]
    [:approval_id {:optional true} bounded-string]
    [:trace trace-ref]]
   [:map {:closed true}
    [:api_version {:optional true} bounded-string]
    [:schema_version schema-version]
    [:policy_id bounded-string]
    [:version bounded-string]
    [:trace trace-ref]]])

(def policy-promote-request
  [:map {:closed true}
   [:api_version {:optional true} bounded-string]
   [:schema_version schema-version]
   [:policy_id bounded-string]
   [:version bounded-string]
   [:decision_id bounded-string]
   [:approval_id {:optional true} bounded-string]
   [:trace trace-ref]])

(def policy-retire-request
  [:map {:closed true}
   [:api_version {:optional true} bounded-string]
   [:schema_version schema-version]
   [:policy_id bounded-string]
   [:version bounded-string]
   [:trace trace-ref]])

(def policy-registry-entry
  [:map
   [:policy_id bounded-string]
   [:version bounded-string]
   [:state [:enum "draft" "shadow" "active" "retired"]]
   [:policy map?]
   [:governance map?]
   [:shadow_review {:optional true} map?]
   [:approvals {:optional true} [:vector map?]]])

(def policy-registry-response
  [:map {:closed true}
   [:schema_version schema-version]
   [:policies [:vector {:max 1000} policy-registry-entry]]])

(def policy-lifecycle-response
  [:or
   [:map {:closed true}
    [:promoted [:= true]]
    [:decision_id bounded-string]]
   [:map {:closed true}
    [:retired [:= true]]]])

(def contracts
  {:example/catalog example-catalog
   :example/capabilities capabilities
   :example/query retrieval-query
   :example/selection-result selection-result
   :example/expansion-result expansion-result
   :example/context-packet context-packet
   :example/diagnostics-trace diagnostics-trace
   :example/stage-event retrieval-stage-event
   :example/usage-event usage-event
   :example/usage-feedback usage-feedback
   :example/confidence confidence
   :example/guardrail-assessment guardrail-assessment
   :example/override-record override-record
   :example/human-review-record human-review-record
   :example/relation-traversal-query relation-traversal-query
   :example/relation-traversal-result relation-traversal-result
   :example/state-invariants state-invariants
   :example/policy-lifecycle-request policy-lifecycle-request
   :example/policy-promote-request policy-promote-request
   :example/policy-retire-request policy-retire-request
   :example/policy-registry-response policy-registry-response
   :example/policy-lifecycle-response policy-lifecycle-response
   :fixture/corpus fixture-corpus
   :fixture/retrieval retrieval-fixture})
