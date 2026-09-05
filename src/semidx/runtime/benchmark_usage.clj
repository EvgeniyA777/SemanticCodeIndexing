(ns semidx.runtime.benchmark-usage
  "Provider-independent usage normalization for the retrieval value benchmark
   (plans/020 Stage 2, preregistration reports/023 sections 2, 4).

   Raw provider usage is the source of truth and is always preserved. Canonical
   usage fields are derived through a versioned adapter, and cost is derived
   through an immutable, versioned price schedule. A run recorded today can
   therefore be repriced later from `raw_usage` without re-running the suite."
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]))

(def schema-version
  "Schema version of one response/usage matrix row."
  "benchmark_usage_matrix_v1")

(def adapter-version
  "Version of the raw -> canonical usage mappings in this namespace."
  "v1")

(def cache-protocol-id
  "Preregistered cache protocol (reports/023 section 2)."
  "implicit_cache_observed_v1")

(def price-schedule
  "Immutable evaluated-model price schedule (reports/023 section 4).

   Rates are USD per `:token_unit` tokens. `:max_input_tokens` bounds the
   context tier of a row (nil = any context size). `:output_classes_uniform?`
   records whether visible and reasoning output share one rate, which is the
   only condition under which `output_unclassified` can be priced."
  {:price_schedule_id "2026-08-03-eligible-v1"
   :currency "USD"
   :token_unit 1000000
   :captured_at "2026-08-03T01:02:00Z"
   :eligible_until "2026-10-16"
   :source "https://ai.google.dev/gemini-api/docs/pricing"
   :rows [{:provider "google"
           :api_surface "generate-content"
           :model_revision "gemini-2.5-pro"
           :service_tier "on-demand"
           :context_tier "<=200k"
           :max_input_tokens 200000
           :input_uncached_rate 1.25
           :input_cache_read_rate 0.125
           :output_rate 10.0
           :output_classes_uniform? true}
          {:provider "google"
           :api_surface "generate-content"
           :model_revision "gemini-2.5-pro"
           :service_tier "on-demand"
           :context_tier ">200k"
           :max_input_tokens nil
           :input_uncached_rate 2.5
           :input_cache_read_rate 0.25
           :output_rate 15.0
           :output_classes_uniform? true}
          {:provider "google"
           :api_surface "generate-content"
           :model_revision "gemini-2.5-flash"
           :service_tier "on-demand"
           :context_tier "any"
           :max_input_tokens nil
           :input_uncached_rate 0.3
           :input_cache_read_rate 0.03
           :output_rate 2.5
           :output_classes_uniform? true}]
   ;; Recorded for reference only. An attempt on one of these revisions is
   ;; excluded from the primary cost verdict (reports/023 section 4.2).
   :historical_rows [{:provider "anthropic"
                      :api_surface "messages"
                      :model_revision "claude-3-5-sonnet-20240620"}
                     {:provider "openai"
                      :api_surface "chat"
                      :model_revision "gpt-4o-2024-05-13"}
                     {:provider "openai"
                      :api_surface "responses"
                      :model_revision "gpt-4o-2024-05-13"}]})

(def price-schedule-id (:price_schedule_id price-schedule))

(defn price-schedule-eligible?
  "True when `on-date` (an ISO yyyy-MM-dd string) is before the schedule's
   eligible-until date. No pilot or scoring run may start on or after it."
  ([on-date] (price-schedule-eligible? price-schedule on-date))
  ([schedule on-date]
   (let [limit (LocalDate/parse (:eligible_until schedule))
         day (LocalDate/parse (str on-date))]
     (.isBefore day limit))))

(defn- long-or-zero [value]
  (if (number? value) (long value) 0))

(defn- non-negative [value]
  (max 0 (long-or-zero value)))

(defn- unresolved [reason]
  {:pricing_status "unresolved"
   :pricing_status_reason reason})

(defn- canonical-base [m]
  (merge {:input_uncached 0
          :input_cache_read 0
          :input_cache_write_5m 0
          :input_cache_write_1h 0
          :output_visible 0
          :output_reasoning 0
          :output_unclassified 0
          :pricing_status "resolved"
          :pricing_status_reason nil}
         m))

(defmulti adapt-usage
  "Map one raw provider `usage` payload to canonical usage classes.
   Dispatches on `adapter-id`. Returns canonical class counts plus an initial
   `:pricing_status`; cost is applied later by `price-usage`."
  (fn [adapter-id _raw-usage] adapter-id))

(defmethod adapt-usage :default [adapter-id _raw]
  (canonical-base (unresolved (str "unknown_adapter:" adapter-id))))

(defmethod adapt-usage "gemini-generate-content" [_ raw]
  (let [prompt (long-or-zero (:promptTokenCount raw))
        cached (long-or-zero (:cachedContentTokenCount raw))
        visible (long-or-zero (:candidatesTokenCount raw))
        reasoning (long-or-zero (:thoughtsTokenCount raw))]
    (canonical-base
     (merge {:input_uncached (non-negative (- prompt cached))
             :input_cache_read cached
             :output_visible visible
             :output_reasoning reasoning}
            (cond
              (nil? (:promptTokenCount raw))
              (unresolved "missing_prompt_token_count")

              (nil? (:candidatesTokenCount raw))
              (unresolved "missing_candidates_token_count")

              (< prompt cached)
              (unresolved "cached_tokens_exceed_prompt_tokens")

              :else nil)))))

(defmethod adapt-usage "anthropic-messages" [_ raw]
  (let [creation (:cache_creation raw)
        write-5m (long-or-zero (or (:ephemeral_5m_input_tokens creation)
                                   (when (nil? creation) (:cache_creation_input_tokens raw))))
        write-1h (long-or-zero (:ephemeral_1h_input_tokens creation))
        output-total (long-or-zero (:output_tokens raw))
        reasoning (:reasoning_output_tokens raw)]
    (canonical-base
     (merge {:input_uncached (long-or-zero (:input_tokens raw))
             :input_cache_read (long-or-zero (:cache_read_input_tokens raw))
             :input_cache_write_5m write-5m
             :input_cache_write_1h write-1h}
            (if (some? reasoning)
              {:output_visible (non-negative (- output-total (long-or-zero reasoning)))
               :output_reasoning (long-or-zero reasoning)}
              ;; A combined output total is not silently relabelled as visible
              ;; output; it stays unclassified and is priced only when every
              ;; output class shares one rate.
              {:output_unclassified output-total})
            (when (nil? (:input_tokens raw))
              (unresolved "missing_input_tokens"))))))

(defn- openai-canonical [raw prompt-key completion-key cached-path reasoning-path]
  (let [prompt (long-or-zero (get raw prompt-key))
        cached (long-or-zero (get-in raw cached-path))
        completion (long-or-zero (get raw completion-key))
        reasoning (get-in raw reasoning-path)]
    (canonical-base
     (merge {:input_uncached (non-negative (- prompt cached))
             :input_cache_read cached}
            (if (some? reasoning)
              {:output_visible (non-negative (- completion (long-or-zero reasoning)))
               :output_reasoning (long-or-zero reasoning)}
              {:output_unclassified completion})
            (cond
              (nil? (get raw prompt-key)) (unresolved "missing_prompt_tokens")
              (< prompt cached) (unresolved "cached_tokens_exceed_prompt_tokens")
              :else nil)))))

(defmethod adapt-usage "openai-chat" [_ raw]
  (openai-canonical raw :prompt_tokens :completion_tokens
                    [:prompt_tokens_details :cached_tokens]
                    [:completion_tokens_details :reasoning_tokens]))

(defmethod adapt-usage "openai-responses" [_ raw]
  (openai-canonical raw :input_tokens :output_tokens
                    [:input_tokens_details :cached_tokens]
                    [:output_tokens_details :reasoning_tokens]))

(defn historical-only-model?
  "True when the evaluated model is recorded in the historical-only table and is
   therefore excluded from the primary cost verdict."
  ([model] (historical-only-model? price-schedule model))
  ([schedule {:keys [evaluated_provider evaluated_api_surface evaluated_model_revision]}]
   (boolean
    (some (fn [row]
            (and (= (:provider row) evaluated_provider)
                 (= (:api_surface row) evaluated_api_surface)
                 (= (:model_revision row) evaluated_model_revision)))
          (:historical_rows schedule)))))

(defn find-price-row
  "Return the price row for an evaluated model at a given input size, or nil.
   Context tiers are ordered by `:max_input_tokens`; the narrowest row whose
   bound covers `input-total` wins, and an unbounded row covers any size."
  ([model input-total] (find-price-row price-schedule model input-total))
  ([schedule {:keys [evaluated_provider evaluated_api_surface
                     evaluated_model_revision evaluated_service_tier]}
    input-total]
   (->> (:rows schedule)
        (filterv (fn [row]
                   (and (= (:provider row) evaluated_provider)
                        (= (:api_surface row) evaluated_api_surface)
                        (= (:model_revision row) evaluated_model_revision)
                        (= (:service_tier row) evaluated_service_tier))))
        (sort-by (fn [row] (or (:max_input_tokens row) Long/MAX_VALUE)))
        (filterv (fn [row]
                   (or (nil? (:max_input_tokens row))
                       (<= (long input-total) (long (:max_input_tokens row))))))
        first)))

(defn- rate-cost [tokens rate token-unit]
  (/ (* (double tokens) (double rate)) (double token-unit)))

(defn price-usage
  "Attach totals and cost to a canonical usage record.

   `model` carries the evaluated_* identity of the attempt. `tool-charges-usd`
   is a non-token provider charge kept separate from token cost. Cost is only
   produced when the adapter resolved every billing-relevant class, the cache
   protocol was respected, and an eligible price row exists."
  ([canonical model] (price-usage price-schedule canonical model 0.0))
  ([schedule canonical model tool-charges-usd]
   (let [input-total (+ (:input_uncached canonical)
                        (:input_cache_read canonical)
                        (:input_cache_write_5m canonical)
                        (:input_cache_write_1h canonical))
         output-total (+ (:output_visible canonical)
                         (:output_reasoning canonical)
                         (:output_unclassified canonical))
         tool-charges (double (or tool-charges-usd 0.0))
         row (find-price-row schedule model input-total)
         cache-write-total (+ (:input_cache_write_5m canonical)
                              (:input_cache_write_1h canonical))
         status (cond
                  (= "unresolved" (:pricing_status canonical))
                  canonical

                  (pos? cache-write-total)
                  (merge canonical (unresolved "cache_write_forbidden_by_cache_protocol"))

                  (historical-only-model? schedule model)
                  (merge canonical {:pricing_status "historical_only"
                                    :pricing_status_reason "model_revision_not_eligible_for_v1_verdict"})

                  (nil? row)
                  (merge canonical (unresolved "no_price_row_for_evaluated_model"))

                  (and (pos? (:output_unclassified canonical))
                       (not (:output_classes_uniform? row)))
                  (merge canonical (unresolved "unclassified_output_without_uniform_output_rate"))

                  :else canonical)
         priced? (= "resolved" (:pricing_status status))
         token-cost (when priced?
                      (+ (rate-cost (:input_uncached status)
                                    (:input_uncached_rate row)
                                    (:token_unit schedule))
                         (rate-cost (:input_cache_read status)
                                    (:input_cache_read_rate row)
                                    (:token_unit schedule))
                         (rate-cost output-total
                                    (:output_rate row)
                                    (:token_unit schedule))))]
     (assoc status
            :input_total input-total
            :output_total output-total
            :grand_total (+ input-total output-total)
            :token_cost_usd token-cost
            :tool_charges_usd tool-charges
            :cost_usd (when priced? (+ token-cost tool-charges))
            :context_tier (:context_tier row)))))

(defn normalize-turn
  "Normalize one raw provider response into a canonical, priced usage record."
  [{:keys [adapter_id raw_usage tool_charges_usd] :as turn} model]
  (price-usage price-schedule
               (adapt-usage adapter_id (or raw_usage {}))
               (merge model (select-keys turn [:evaluated_provider
                                               :evaluated_api_surface
                                               :evaluated_model_revision
                                               :evaluated_service_tier]))
               (or tool_charges_usd 0.0)))

(defn matrix-row
  "Build one tidy/long response/usage matrix row.

   One row per (benchmark_run_id x task_attempt_id x turn_index x
   evaluated_model_revision). `raw_usage` is always retained so a wrong mapping
   or price schedule can be corrected by re-derivation."
  [{:keys [run attempt turn]}]
  (let [model (select-keys attempt [:evaluated_provider :evaluated_api_surface
                                    :evaluated_model :evaluated_model_revision
                                    :evaluated_service_tier])
        usage-norm (normalize-turn turn model)]
    (merge
     (select-keys run [:benchmark_run_id :repo_key :repo_revision
                       :task_prompt_policy_id :arm_policy_bundle_id
                       :execution_budget_policy_id :cache_protocol_id])
     (select-keys attempt [:task_id :task_attempt_id :arm :seed :arm_policy_id
                           :sequence_index :agent_id :agent_build_id
                           :evaluated_provider :evaluated_api_surface
                           :evaluated_model :evaluated_model_revision
                           :evaluated_service_tier])
     {:turn_index (:turn_index turn)
      :usage_norm usage-norm
      :raw_usage (:raw_usage turn)
      :response_meta (:response_meta turn)
      :adapter_id (:adapter_id turn)
      :adapter_version adapter-version
      :price_schedule_id (:price_schedule_id price-schedule)
      :schema_version schema-version})))

(defn- sum-field [rows field]
  (reduce + 0 (map (fn [row] (long-or-zero (get-in row [:usage_norm field]))) rows)))

(defn- sum-usd [rows field]
  (reduce + 0.0 (map (fn [row] (double (or (get-in row [:usage_norm field]) 0.0))) rows)))

(defn- worst-pricing-status [rows]
  (let [statuses (set (map (fn [row] (get-in row [:usage_norm :pricing_status])) rows))]
    (cond
      (empty? statuses) "unresolved"
      (contains? statuses "unresolved") "unresolved"
      (contains? statuses "historical_only") "historical_only"
      :else "resolved")))

(defn aggregate-attempt-usage
  "Sum the response/usage matrix rows of one task attempt.

   This is the attempt-first aggregation step required before any roll-up by
   (benchmark_run_id, task_id, arm). Cost is only reported when every turn of
   the attempt priced cleanly; otherwise the attempt is excluded from the cost
   verdict and the reason is preserved."
  [rows]
  (let [rows (vec rows)
        status (worst-pricing-status rows)
        priced? (= "resolved" status)
        reasons (->> rows
                     (keep (fn [row] (get-in row [:usage_norm :pricing_status_reason])))
                     distinct
                     vec)]
    {:turn_count (count rows)
     :input_uncached (sum-field rows :input_uncached)
     :input_cache_read (sum-field rows :input_cache_read)
     :input_cache_write_5m (sum-field rows :input_cache_write_5m)
     :input_cache_write_1h (sum-field rows :input_cache_write_1h)
     :output_visible (sum-field rows :output_visible)
     :output_reasoning (sum-field rows :output_reasoning)
     :output_unclassified (sum-field rows :output_unclassified)
     :input_total (sum-field rows :input_total)
     :output_total (sum-field rows :output_total)
     :grand_total (sum-field rows :grand_total)
     :token_cost_usd (when priced? (sum-usd rows :token_cost_usd))
     :tool_charges_usd (sum-usd rows :tool_charges_usd)
     :cost_usd (when priced? (sum-usd rows :cost_usd))
     :pricing_status status
     :pricing_status_reasons reasons
     :cost_verdict_eligible priced?
     :adapter_version adapter-version
     :price_schedule_id (:price_schedule_id price-schedule)
     :schema_version schema-version}))

(defn adapter-ids
  "Registered raw -> canonical usage adapters."
  []
  (->> (methods adapt-usage)
       keys
       (remove #(= :default %))
       (map str)
       sort
       vec))

(defn describe-price-schedule
  "Compact, printable description of the locked price schedule."
  []
  (str (:price_schedule_id price-schedule)
       " (" (:currency price-schedule)
       " per " (:token_unit price-schedule) " tokens, eligible until "
       (:eligible_until price-schedule) "): "
       (str/join ", " (map (fn [row] (str (:model_revision row) " " (:context_tier row)))
                           (:rows price-schedule)))))
