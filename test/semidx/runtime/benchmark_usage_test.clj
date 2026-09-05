(ns semidx.runtime.benchmark-usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.benchmark-usage :as bu]))

(def gemini-flash
  {:evaluated_provider "google"
   :evaluated_api_surface "generate-content"
   :evaluated_model "gemini-2.5-flash"
   :evaluated_model_revision "gemini-2.5-flash"
   :evaluated_service_tier "on-demand"})

(def gemini-pro
  (assoc gemini-flash
         :evaluated_model "gemini-2.5-pro"
         :evaluated_model_revision "gemini-2.5-pro"))

(defn- close-to? [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1e-9))

(deftest gemini-adapter-splits-usage-classes-test
  (let [canonical (bu/adapt-usage "gemini-generate-content"
                                  {:promptTokenCount 1000
                                   :cachedContentTokenCount 200
                                   :candidatesTokenCount 150
                                   :thoughtsTokenCount 50})]
    (is (= 800 (:input_uncached canonical)))
    (is (= 200 (:input_cache_read canonical)))
    (is (= 150 (:output_visible canonical)))
    (is (= 50 (:output_reasoning canonical)))
    (is (= 0 (:output_unclassified canonical)))
    (is (= "resolved" (:pricing_status canonical)))))

(deftest gemini-adapter-reports-unresolved-classes-test
  (testing "a missing prompt count cannot be guessed"
    (is (= "unresolved"
           (:pricing_status (bu/adapt-usage "gemini-generate-content"
                                            {:candidatesTokenCount 10})))))
  (testing "cached tokens larger than the prompt total are incoherent"
    (is (= "cached_tokens_exceed_prompt_tokens"
           (:pricing_status_reason
            (bu/adapt-usage "gemini-generate-content"
                            {:promptTokenCount 10
                             :cachedContentTokenCount 20
                             :candidatesTokenCount 5}))))))

(deftest unknown-adapter-is-unresolved-test
  (let [canonical (bu/adapt-usage "mystery-provider" {:tokens 10})]
    (is (= "unresolved" (:pricing_status canonical)))
    (is (= "unknown_adapter:mystery-provider" (:pricing_status_reason canonical)))))

(deftest anthropic-combined-output-stays-unclassified-test
  (let [canonical (bu/adapt-usage "anthropic-messages"
                                  {:input_tokens 100
                                   :cache_read_input_tokens 40
                                   :output_tokens 60})]
    (is (= 100 (:input_uncached canonical)))
    (is (= 40 (:input_cache_read canonical)))
    (is (= 60 (:output_unclassified canonical))
        "a combined output total must not be relabelled as visible output")
    (is (= 0 (:output_visible canonical)))))

(deftest openai-adapters-separate-reasoning-test
  (let [chat (bu/adapt-usage "openai-chat"
                             {:prompt_tokens 500
                              :prompt_tokens_details {:cached_tokens 100}
                              :completion_tokens 200
                              :completion_tokens_details {:reasoning_tokens 80}})
        responses (bu/adapt-usage "openai-responses"
                                  {:input_tokens 500
                                   :input_tokens_details {:cached_tokens 100}
                                   :output_tokens 200
                                   :output_tokens_details {:reasoning_tokens 80}})]
    (is (= 400 (:input_uncached chat)))
    (is (= 100 (:input_cache_read chat)))
    (is (= 120 (:output_visible chat)))
    (is (= 80 (:output_reasoning chat)))
    (is (= (dissoc chat :pricing_status_reason)
           (dissoc responses :pricing_status_reason)))))

(deftest cost-uses-cache-read-rate-test
  (let [priced (bu/price-usage bu/price-schedule
                               (bu/adapt-usage "gemini-generate-content"
                                               {:promptTokenCount 1000
                                                :cachedContentTokenCount 200
                                                :candidatesTokenCount 150
                                                :thoughtsTokenCount 50})
                               gemini-flash
                               0.0)]
    (is (= "resolved" (:pricing_status priced)))
    (is (= 1000 (:input_total priced)))
    (is (= 200 (:output_total priced)))
    (is (= 1200 (:grand_total priced)))
    ;; 800 * 0.30/1M + 200 * 0.03/1M + 200 * 2.50/1M
    (is (close-to? 0.000746 (:token_cost_usd priced)))
    (is (close-to? 0.000746 (:cost_usd priced)))))

(deftest tool-charges-stay-separate-from-token-cost-test
  (let [priced (bu/price-usage bu/price-schedule
                               (bu/adapt-usage "gemini-generate-content"
                                               {:promptTokenCount 100
                                                :candidatesTokenCount 100})
                               gemini-flash
                               0.25)]
    (is (close-to? 0.25 (:tool_charges_usd priced)))
    (is (close-to? (+ 0.25 (:token_cost_usd priced)) (:cost_usd priced)))))

(deftest context-tier-selects-the-price-row-test
  (let [small (bu/price-usage bu/price-schedule
                              (bu/adapt-usage "gemini-generate-content"
                                              {:promptTokenCount 1000
                                               :candidatesTokenCount 10})
                              gemini-pro 0.0)
        large (bu/price-usage bu/price-schedule
                              (bu/adapt-usage "gemini-generate-content"
                                              {:promptTokenCount 300000
                                               :candidatesTokenCount 10})
                              gemini-pro 0.0)]
    (is (= "<=200k" (:context_tier small)))
    (is (= ">200k" (:context_tier large)))))

(deftest cache-write-is-forbidden-by-the-cache-protocol-test
  (let [priced (bu/price-usage bu/price-schedule
                               (bu/adapt-usage "anthropic-messages"
                                               {:input_tokens 100
                                                :cache_creation {:ephemeral_5m_input_tokens 50}
                                                :output_tokens 10
                                                :reasoning_output_tokens 0})
                               gemini-flash 0.0)]
    (is (= "unresolved" (:pricing_status priced)))
    (is (= "cache_write_forbidden_by_cache_protocol" (:pricing_status_reason priced)))
    (is (nil? (:cost_usd priced)))))

(deftest historical-model-is-excluded-from-the-cost-verdict-test
  (let [priced (bu/price-usage bu/price-schedule
                               (bu/adapt-usage "anthropic-messages"
                                               {:input_tokens 100 :output_tokens 10})
                               {:evaluated_provider "anthropic"
                                :evaluated_api_surface "messages"
                                :evaluated_model_revision "claude-3-5-sonnet-20240620"
                                :evaluated_service_tier "on-demand"}
                               0.0)]
    (is (= "historical_only" (:pricing_status priced)))
    (is (nil? (:cost_usd priced)))))

(deftest unpriced-model-is-unresolved-test
  (let [priced (bu/price-usage bu/price-schedule
                               (bu/adapt-usage "gemini-generate-content"
                                               {:promptTokenCount 10 :candidatesTokenCount 1})
                               (assoc gemini-flash :evaluated_model_revision "gemini-9.9-turbo")
                               0.0)]
    (is (= "unresolved" (:pricing_status priced)))
    (is (= "no_price_row_for_evaluated_model" (:pricing_status_reason priced)))))

(deftest price-schedule-eligibility-window-test
  (is (bu/price-schedule-eligible? "2026-08-27"))
  (is (not (bu/price-schedule-eligible? "2026-10-16")))
  (is (not (bu/price-schedule-eligible? "2026-12-01"))))

(def sample-run
  {:benchmark_run_id "run-1"
   :repo_key "semidx"
   :repo_revision "abc123"
   :task_prompt_policy_id "agent_default_v1"
   :arm_policy_bundle_id "harness_v1"
   :execution_budget_policy_id "budget_v1"
   :cache_protocol_id "implicit_cache_observed_v1"})

(def sample-attempt
  (merge gemini-flash
         {:task_id "task-1"
          :task_attempt_id "attempt-1"
          :arm "A"
          :arm_policy_id "arm_a_semidx_staged_v1"
          :sequence_index 0
          :seed 7
          :agent_id "agent-1"
          :agent_build_id "build-1"}))

(defn- turn [index raw]
  {:turn_index index
   :adapter_id "gemini-generate-content"
   :raw_usage raw
   :response_meta {:stop_reason "stop" :tool_call_count 1 :output_chars 120}})

(deftest matrix-row-retains-raw-usage-and-versions-test
  (let [row (bu/matrix-row {:run sample-run
                            :attempt sample-attempt
                            :turn (turn 0 {:promptTokenCount 100
                                           :candidatesTokenCount 20})})]
    (is (= "run-1" (:benchmark_run_id row)))
    (is (= "attempt-1" (:task_attempt_id row)))
    (is (= 0 (:turn_index row)))
    (is (= {:promptTokenCount 100 :candidatesTokenCount 20} (:raw_usage row))
        "raw usage is the source of truth and must survive normalization")
    (is (= bu/adapter-version (:adapter_version row)))
    (is (= bu/price-schedule-id (:price_schedule_id row)))
    (is (= bu/schema-version (:schema_version row)))
    (is (= "implicit_cache_observed_v1" (:cache_protocol_id row)))))

(deftest attempt-aggregation-sums-turns-test
  (let [rows [(bu/matrix-row {:run sample-run :attempt sample-attempt
                              :turn (turn 0 {:promptTokenCount 1000
                                             :cachedContentTokenCount 200
                                             :candidatesTokenCount 150
                                             :thoughtsTokenCount 50})})
              (bu/matrix-row {:run sample-run :attempt sample-attempt
                              :turn (turn 1 {:promptTokenCount 500
                                             :candidatesTokenCount 100})})]
        totals (bu/aggregate-attempt-usage rows)]
    (is (= 2 (:turn_count totals)))
    (is (= 1300 (:input_uncached totals)))
    (is (= 200 (:input_cache_read totals)))
    (is (= 1500 (:input_total totals)))
    (is (= 300 (:output_total totals)))
    (is (= "resolved" (:pricing_status totals)))
    (is (true? (:cost_verdict_eligible totals)))
    (is (close-to? (+ (get-in rows [0 :usage_norm :cost_usd])
                      (get-in rows [1 :usage_norm :cost_usd]))
                   (:cost_usd totals)))))

(deftest attempt-aggregation-excludes-cost-when-a-turn-is-unresolved-test
  (let [rows [(bu/matrix-row {:run sample-run :attempt sample-attempt
                              :turn (turn 0 {:promptTokenCount 100
                                             :candidatesTokenCount 20})})
              (bu/matrix-row {:run sample-run :attempt sample-attempt
                              :turn {:turn_index 1
                                     :adapter_id "mystery-provider"
                                     :raw_usage {:tokens 10}}})]
        totals (bu/aggregate-attempt-usage rows)]
    (is (= "unresolved" (:pricing_status totals)))
    (is (false? (:cost_verdict_eligible totals)))
    (is (nil? (:cost_usd totals)))
    (is (some #{"unknown_adapter:mystery-provider"} (:pricing_status_reasons totals)))))

(deftest registered-adapters-cover-the-preregistered-surfaces-test
  (is (= ["anthropic-messages" "gemini-generate-content" "openai-chat" "openai-responses"]
         (bu/adapter-ids))))
