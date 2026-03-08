(ns semantic-code-indexing.runtime.retrieval
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [semantic-code-indexing.contracts.schemas :as contracts]
            [semantic-code-indexing.runtime.index :as idx]))

(defn- now-iso []
  (-> (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)
      (.withNano 0)
      (.format java.time.format.DateTimeFormatter/ISO_INSTANT)))

(defn- coded [code summary]
  {:code code :summary summary})

(defn- summarize-query [query]
  {:intent (get-in query [:intent :purpose] "unknown")
   :targets_summary (vec (concat
                          (map #(str "symbol: " %) (get-in query [:targets :symbols] []))
                          (map #(str "path: " %) (get-in query [:targets :paths] []))
                          (map #(str "module: " %) (get-in query [:targets :modules] []))
                          (map #(str "test: " %) (get-in query [:targets :tests] []))))
   :constraints_summary (->> (get query :constraints)
                             (map (fn [[k v]] (str (name k) " " v)))
                             vec)
   :hints_summary (->> (get query :hints)
                       (keep (fn [[k v]] (when (or (true? v)
                                                   (and (coll? v) (seq v)))
                                      (str (name k)))))
                       vec)})

(defn- validate-query! [query]
  (when-let [explain (m/explain (:example/query contracts/contracts) query)]
    (throw (ex-info "invalid retrieval query"
                    {:type :invalid_query
                     :errors (me/humanize explain)}))))

(defn- add-score [score-map uid points reason]
  (-> score-map
      (update-in [uid :score] (fnil + 0) points)
      (update-in [uid :reasons] (fnil conj []) reason)))

(defn- overlap-span? [u span]
  (and (= (:path u) (:path span))
       (<= (:start_line u) (:end_line span))
       (<= (:start_line span) (:end_line u))))

(defn- lexical-tokens [query]
  (->> [(get-in query [:intent :details])
        (get-in query [:targets :diff_summary])]
       (remove nil?)
       (str/join " ")
       (re-seq #"[A-Za-z][A-Za-z0-9_\-]+")
       (map str/lower-case)
       distinct
       (take 20)
       vec))

(defn- lexical-match? [u tokens]
  (let [hay (str/lower-case (str (:signature u) " " (:summary u) " " (:symbol u)))]
    (some #(str/includes? hay %) tokens)))

(defn- module-prefix-match? [u module]
  (let [m (:module u)
        module-str (str module)]
    (and m
         (or (= m module-str)
             (str/starts-with? m module-str)
             (str/includes? m module-str)))))

(defn- collect-candidates [index query]
  (let [units (idx/all-units index)
        target-symbols (get-in query [:targets :symbols] [])
        target-paths (set (get-in query [:targets :paths] []))
        target-modules (get-in query [:targets :modules] [])
        target-tests (set (get-in query [:targets :tests] []))
        changed-spans (get-in query [:targets :changed_spans] [])
        tokens (lexical-tokens query)
        hints (:hints query)
        preferred-paths (set (:preferred_paths hints))
        preferred-modules (:preferred_modules hints)
        score-map (reduce
                   (fn [acc u]
                     (let [uid (:unit_id u)
                           by-symbol (some #(= (:symbol u) %) target-symbols)
                           by-path (contains? target-paths (:path u))
                           by-module (some #(module-prefix-match? u %) target-modules)
                           by-test (contains? target-tests (:path u))
                           by-span (some #(overlap-span? u %) changed-spans)
                           by-pref-path (contains? preferred-paths (:path u))
                           by-pref-module (some #(module-prefix-match? u %) preferred-modules)
                           by-lexical (lexical-match? u tokens)
                           acc1 (cond-> acc
                                  by-symbol (add-score uid 140 (coded "exact_target_resolved" "Target symbol resolved to unit."))
                                  by-path (add-score uid 100 (coded "target_path_match" "Unit path directly targeted by query."))
                                  by-module (add-score uid 85 (coded "target_module_match" "Unit module targeted by query."))
                                  by-test (add-score uid 60 (coded "target_test_match" "Unit appears in explicitly requested tests."))
                                  by-span (add-score uid 95 (coded "diff_overlap_direct" "Changed span overlaps this unit."))
                                  by-pref-path (add-score uid 15 (coded "hint_preferred_path" "Preferred path hint boosted this unit."))
                                  by-pref-module (add-score uid 10 (coded "hint_preferred_module" "Preferred module hint boosted this unit."))
                                  by-lexical (add-score uid 8 (coded "lexical_overlap" "Lexical overlap with query detail.")))]
                       (if (contains? acc1 uid) acc1 (assoc acc1 uid {:score 0 :reasons []}))))
                   {}
                   units)
        scored (->> units
                    (map (fn [u]
                           (let [{:keys [score reasons]} (get score-map (:unit_id u) {:score 0 :reasons []})]
                             (assoc u :score score :selection_reasons reasons))))
                    (filter #(pos? (:score %)))
                    (sort-by (juxt (comp - :score) :path :start_line))
                    vec)
        fallback (if (seq scored)
                   scored
                   (->> units
                        (sort-by (juxt :path :start_line))
                        (take 10)
                        (map #(assoc % :score 1 :selection_reasons [(coded "fallback_candidate" "No strong match; selected from repository map.")]))
                        vec))]
    {:scored fallback
     :tokens tokens}))

(defn- rank-band [score]
  (cond
    (>= score 120) "top_authority"
    (>= score 80) "useful_support"
    (>= score 30) "exploratory"
    :else "below_threshold_noise"))

(defn- with-rank-band [units]
  (mapv #(assoc % :rank_band (rank-band (:score %))) units))

(defn- estimate-tokens [selected]
  (->> selected
       (map (fn [u]
              (+ (count (or (:signature u) ""))
                 (count (or (:summary u) ""))
                 (count (or (:symbol u) "")))) )
       (reduce + 0)
       (#(int (Math/ceil (/ (double %) 4.0))))))

(defn- top-reasons [selected]
  (->> selected
       (mapcat :selection_reasons)
       distinct
       (take 10)
       vec))

(defn- build-impact-hints [index selected]
  (let [selected-ids (set (map :unit_id selected))
        callers (->> selected
                     (mapcat (fn [u]
                               (map #(idx/unit-by-id index %)
                                    (get (:callers_index index) (:unit_id u) #{}))))
                     (remove nil?)
                     (map #(str (:path %) "::" (:symbol %)))
                     distinct
                     (take 12)
                     vec)
        selected-modules (->> selected (map :module) (remove nil?) distinct vec)
        dependents (->> selected-modules
                        (mapcat #(get (:module_dependents index) % #{}))
                        distinct
                        (take 12)
                        vec)
        related-tests (->> (idx/all-units index)
                           (filter #(or (= "test" (:kind %))
                                        (str/includes? (:path %) "/test/")))
                           (filter (fn [u]
                                     (or (contains? selected-ids (:unit_id u))
                                         (some #(= (:module u) %) selected-modules)
                                         (contains? (set callers) (str (:path u) "::" (:symbol u))))))
                           (map :path)
                           distinct
                           (take 12)
                           vec)
        risky-neighbors (->> selected
                             (mapcat (fn [u]
                                       (->> (idx/units-for-path index (:path u))
                                            (remove #(= (:unit_id %) (:unit_id u))))))
                             (map #(str (:path %) "::" (:symbol %)))
                             distinct
                             (take 12)
                             vec)]
    {:callers callers
     :dependents dependents
     :related_tests related-tests
     :risky_neighbors risky-neighbors}))

(defn- build-confidence [selected query]
  (let [top (first selected)
        second-best (second selected)
        exact-target? (and (seq (get-in query [:targets :symbols]))
                           (some #(contains? (set (get-in query [:targets :symbols])) (:symbol %)) selected))
        parser-fallback? (some #(= "fallback" (:parser_mode %)) selected)
        ambiguous? (and top second-best (<= (Math/abs (- (:score top) (:score second-best))) 10))
        level (cond
                (and exact-target? (not parser-fallback?) (not ambiguous?)) "high"
                (or exact-target? (seq (get-in query [:targets :changed_spans])) (seq (get-in query [:targets :paths]))) "medium"
                :else "low")
        adjusted-level (if parser-fallback? "low" level)
        reasons (cond-> []
                  exact-target? (conj (coded "exact_target_resolved" "Target symbol resolved to authority unit."))
                  (and top (>= (:score top) 80)) (conj (coded "graph_proximity_strong" "High structural score for selected unit."))
                  (seq (get-in query [:targets :changed_spans])) (conj (coded "diff_overlap_direct" "Changed span overlap contributed to retrieval.")))
        warnings (cond-> []
                   parser-fallback? (conj (coded "parser_fallback" "Fallback parser used for at least one selected unit."))
                   ambiguous? (conj (coded "target_ambiguous" "Top ranked units are close in score; authority target is ambiguous.")))
        missing (cond-> []
                  (not exact-target?) (conj (coded "exact_target_resolution_missing" "No exact symbol target resolved from query."))
                  (empty? reasons) (conj (coded "structural_evidence_weak" "No strong structural evidence was found.")))
        numeric (case adjusted-level
                  "high" 0.90
                  "medium" 0.62
                  0.30)]
    {:schema_version "1.0"
     :level adjusted-level
     :score numeric
     :reasons (vec (take 10 reasons))
     :warnings (vec (take 10 warnings))
     :missing_evidence (vec (take 10 missing))}))

(defn- build-guardrails [confidence impact]
  (let [level (:level confidence)
        broad-impact? (> (count (:risky_neighbors impact)) 6)
        posture (case level
                  "high" "draft_patch_safe"
                  "medium" "plan_safe"
                  "autonomy_blocked")
        blocked? (= posture "autonomy_blocked")]
    {:schema_version "1.0"
     :autonomy_posture posture
     :blocking_reasons (cond-> []
                         blocked? (conj (coded "confidence_low" "Confidence level is low for autonomous drafting."))
                         broad-impact? (conj (coded "impact_broad" "Impact surface appears broad and needs review.")))
     :required_next_steps (case posture
                            "draft_patch_safe" [(coded "run_targeted_tests" "Run nearest tests before any apply path.")]
                            "plan_safe" [(coded "fetch_more_context" "Fetch additional context before drafting changes.")]
                            [(coded "human_review_required" "Human review is required before proceeding.")])
     :allowed_action_scope {:mode (case posture
                                    "draft_patch_safe" "draft_patch_on_selected_unit_only"
                                    "plan_safe" "plan_only"
                                    "analysis_only")
                            :allow_multi_file_edit false
                            :allow_apply_without_human_review false
                            :max_raw_code_level "enclosing_unit"}
     :risk_flags (cond-> []
                   broad-impact? (conj (coded "impact_broad" "Riskiest neighbors exceed safe localized threshold."))
                   blocked? (conj (coded "review_gate" "Host override + review required for risky action.")))}))

(defn- build-stage [name status summary counters warnings degradations duration-ms]
  {:name name
   :status status
   :summary summary
   :counters counters
   :warnings warnings
   :degradation_flags degradations
   :duration_ms duration-ms})

(defn- build-stage-events [trace-id request-id query-intent stages budget]
  (->> stages
       (map (fn [stage]
              {:schema_version "1.0"
               :event_name (str (:name stage) "." (:status stage))
               :timestamp (now-iso)
               :trace_id trace-id
               :request_id request-id
               :stage (:name stage)
               :status (:status stage)
               :summary (:summary stage)
               :counters (:counters stage)
               :query_intent query-intent
               :warning_codes (mapv :code (:warnings stage))
               :degradation_codes (mapv :code (:degradation_flags stage))
               :duration_ms (:duration_ms stage)
               :budget_summary budget
               :redaction_level "default_safe"}))
       vec))

(defn- compact-unit [u]
  {:unit_id (:unit_id u)
   :kind (:kind u)
   :symbol (:symbol u)
   :path (:path u)
   :span {:path (:path u) :start_line (:start_line u) :end_line (:end_line u)}
   :rank_band (:rank_band u)})

(defn- compact-skeleton [u]
  (cond-> {:unit_id (:unit_id u)
           :signature (:signature u)
           :summary (:summary u)}
    (some? (:docstring_excerpt u))
    (assoc :docstring_excerpt (:docstring_excerpt u))))

(defn resolve-context [index query]
  (validate-query! query)
  (let [trace-id (get-in query [:trace :trace_id] (str (java.util.UUID/randomUUID)))
        request-id (get-in query [:trace :request_id] (str "req-" (subs trace-id 0 8)))
        summary (summarize-query query)
        stage-query (build-stage "query_validation" "completed" "Structured query accepted." {:target_count (count (:targets_summary summary)) :constraint_count (count (:constraints_summary summary))} [] [] 2)
        {:keys [scored]} (collect-candidates index query)
        stage-candidates (build-stage "candidate_generation" "completed" "Generated retrieval candidates from structural signals." {:candidate_units (count scored) :candidate_files (count (distinct (map :path scored)))} [] [] 7)
        ranked (->> scored with-rank-band (sort-by (juxt (comp - :score) :path :start_line)) vec)
        selected (vec (take 20 ranked))
        stage-ranking (build-stage "ranking" "completed" "Ranked candidates using structural-first signals." {:ranked_units (count ranked) :top_authority_units (count (filter #(= "top_authority" (:rank_band %)) selected))} [] [] 4)
        requested (get-in query [:constraints :token_budget] 1800)
        estimated (estimate-tokens selected)
        truncation (cond-> [] (> estimated requested) (conj "budget_restricted"))
        budget {:requested_tokens requested :estimated_tokens estimated :truncation_flags truncation}
        impact (build-impact-hints index selected)
        confidence (build-confidence selected query)
        guardrails (build-guardrails confidence impact)
        focus-paths (->> selected (map :path) distinct (take 20) vec)
        focus-modules (->> selected (map :module) (remove nil?) distinct (take 20) vec)
        context-packet {:schema_version "1.0"
                        :query summary
                        :repo_map {:focus_paths focus-paths
                                   :focus_modules focus-modules
                                   :summary (str "Selected " (count selected) " units from " (count focus-paths) " files.")}
                        :relevant_units (mapv compact-unit selected)
                        :skeletons (mapv compact-skeleton selected)
                        :impact_hints impact
                        :evidence {:selection_reasons (top-reasons selected)
                                   :hint_effects (cond-> []
                                                   (seq (:hints_summary summary))
                                                   (conj (coded "hints_applied" "Soft hints were applied during candidate ranking.")))}
                        :budget budget
                        :confidence confidence}
        packet-status (if (= "low" (:level confidence)) "degraded" "completed")
        packet-warns (if (= "low" (:level confidence)) [(coded "confidence_low" "Context packet confidence is low.")] [])
        stage-packet (build-stage "context_packet_assembly" packet-status "Assembled bounded context packet." {:selected_units (count selected) :selected_files (count focus-paths)} packet-warns [] 5)
        stage-fetch (build-stage "raw_code_fetch" "skipped" "Late raw-code fetch not required in default pipeline." {:raw_fetch_requests 0} [] [] 0)
        stage-final (build-stage "result_finalization" "completed" "Confidence, guardrails, and diagnostics emitted." {:warning_count (count (:warnings confidence)) :degradation_count (if (= "low" (:level confidence)) 1 0)} [] [] 2)
        stages [stage-query stage-candidates stage-ranking stage-packet stage-fetch stage-final]
        diagnostics {:schema_version "1.0"
                     :trace {:trace_id trace-id
                             :request_id request-id
                             :timestamp_start (now-iso)
                             :timestamp_end (now-iso)
                             :host_metadata {:host "library_runtime"
                                             :interactive true}}
                     :query (assoc summary :options_summary (->> (:options query) (keep (fn [[k v]] (when (true? v) (name k)))) vec)
                                    :validation_status "accepted")
                     :stages stages
                     :result {:selected_units_count (count selected)
                              :selected_files_count (count focus-paths)
                              :raw_fetch_level_reached "none"
                              :packet_size_estimate estimated
                              :top_authority_targets (->> selected (filter #(= "top_authority" (:rank_band %))) (map :unit_id) (take 10) vec)
                              :result_status (if (= "low" (:level confidence)) "degraded" "completed")}
                     :warnings (:warnings confidence)
                     :degradations (if (= "low" (:level confidence)) [(coded "confidence_low" "Confidence degraded due to weak or ambiguous evidence.")] [])
                     :confidence confidence
                     :guardrails guardrails
                     :performance {:total_duration_ms 20
                                   :cache_summary {:cache_hits 0 :cache_misses 1}
                                   :parser_summary {:fallback_units (count (filter #(= "fallback" (:parser_mode %)) selected))
                                                    :selected_units (count selected)}
                                   :fetch_summary {:raw_fetch_requests 0 :raw_fetch_bytes 0}
                                   :budget_summary {:requested_tokens requested :estimated_tokens estimated}}}
        events (build-stage-events trace-id request-id (get-in query [:intent :purpose] "unknown") stages {:requested_tokens requested :estimated_tokens estimated})]
    (when-let [explain (m/explain (:example/context-packet contracts/contracts) context-packet)]
      (throw (ex-info "invalid context packet generated" {:type :internal_contract_error :errors (me/humanize explain)})))
    (when-let [explain (m/explain (:example/diagnostics-trace contracts/contracts) diagnostics)]
      (throw (ex-info "invalid diagnostics trace generated" {:type :internal_contract_error :errors (me/humanize explain)})))
    {:context_packet context-packet
     :guardrail_assessment guardrails
     :diagnostics_trace diagnostics
     :stage_events events}))

(defn impact-analysis [index query]
  (-> (resolve-context index query)
      :context_packet
      :impact_hints))

(defn skeletons [index {:keys [unit_ids paths]}]
  (let [units (cond
                (seq unit_ids) (idx/units-by-ids index unit_ids)
                (seq paths) (->> paths (mapcat #(idx/units-for-path index %)) distinct vec)
                :else (->> (idx/all-units index) (take 20) vec))]
    (mapv compact-skeleton units)))
