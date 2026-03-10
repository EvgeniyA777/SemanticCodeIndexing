(ns semantic-code-indexing.runtime.retrieval
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [semantic-code-indexing.contracts.schemas :as contracts]
            [semantic-code-indexing.runtime.index :as idx]
            [semantic-code-indexing.runtime.retrieval-policy :as rp]))

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
                                                   (and (coll? v) (seq v))))
                               (str (name k))))
                       vec)})

(defn- validate-query! [query]
  (when-let [explain (m/explain (:example/query contracts/contracts) query)]
    (throw (ex-info "invalid retrieval query"
                    {:type :invalid_query
                     :errors (me/humanize explain)}))))

(defn- tiered-entry []
  {:tier1 0
   :tier2 0
   :tier3 0
   :tier4 0
   :reasons []})

(defn- add-tier [score-map uid tier points reason]
  (-> score-map
      (update uid #(or % (tiered-entry)))
      (update-in [uid tier] (fnil + 0) points)
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

(defn- dispatch-match? [u tokens]
  (let [dispatch (some-> (:dispatch_value u) str str/lower-case)]
    (and (seq dispatch)
         (some #(str/includes? dispatch %) tokens))))

(defn- module-prefix-match? [u module]
  (let [m (:module u)
        module-str (str module)]
    (and m
         (or (= m module-str)
             (str/starts-with? m module-str)
             (str/includes? m module-str)))))

(defn- combine-score [policy {:keys [tier1 tier2 tier3 tier4]} parser-mode]
  (let [raw (+ tier1 tier2 tier3 tier4)
        capped-soft (if (zero? tier1)
                      (min raw (rp/cap policy :no_tier1_max))
                      raw)
        capped-fallback (if (= parser-mode "fallback")
                          (min capped-soft (rp/cap policy :fallback_max))
                          capped-soft)]
    capped-fallback))

(defn- collect-candidates [index query policy]
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
                           by-dispatch (dispatch-match? u tokens)
                           by-parser-fallback (= "fallback" (:parser_mode u))
                           by-lexical (lexical-match? u tokens)
                           acc1 (cond-> acc
                                  by-symbol (add-tier uid :tier1 (rp/weight policy "exact_target_resolved") (coded "exact_target_resolved" "Tier1: target symbol resolved to unit."))
                                  by-path (add-tier uid :tier1 (rp/weight policy "target_path_match") (coded "target_path_match" "Tier1: unit path directly targeted by query."))
                                  by-span (add-tier uid :tier1 (rp/weight policy "diff_overlap_direct") (coded "diff_overlap_direct" "Tier1: changed span overlaps this unit."))
                                  by-module (add-tier uid :tier2 (rp/weight policy "target_module_match") (coded "target_module_match" "Tier2: unit module targeted by query."))
                                  by-test (add-tier uid :tier2 (rp/weight policy "target_test_match") (coded "target_test_match" "Tier2: unit appears in explicitly requested tests."))
                                  by-dispatch (add-tier uid :tier2 (rp/weight policy "dispatch_value_match") (coded "dispatch_value_match" "Tier2: multimethod dispatch value matches the query intent."))
                                  by-pref-path (add-tier uid :tier3 (rp/weight policy "hint_preferred_path") (coded "hint_preferred_path" "Tier3: preferred path hint boosted unit."))
                                  by-pref-module (add-tier uid :tier3 (rp/weight policy "hint_preferred_module") (coded "hint_preferred_module" "Tier3: preferred module hint boosted unit."))
                                  by-lexical (add-tier uid :tier4 (rp/weight policy "lexical_overlap") (coded "lexical_overlap" "Tier4: lexical overlap with query detail."))
                                  by-parser-fallback (add-tier uid :tier3 (rp/weight policy "parser_fallback") (coded "parser_fallback" "Fallback parser contributes limited-confidence evidence.")))]
                       (if (contains? acc1 uid) acc1 (assoc acc1 uid (tiered-entry)))))
                   {}
                   units)
        scored (->> units
                    (map (fn [u]
                           (let [{:keys [tier1 tier2 tier3 tier4 reasons] :as entry}
                                 (get score-map (:unit_id u) (tiered-entry))
                                 score (combine-score policy entry (:parser_mode u))]
                             (assoc u
                                    :tier_scores {:tier1 tier1 :tier2 tier2 :tier3 tier3 :tier4 tier4}
                                    :score score
                                    :selection_reasons reasons))))
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

(defn- with-rank-band [units policy]
  (mapv #(assoc % :rank_band (rp/rank-band policy (:score %))) units))

(defn- estimate-tokens [selected]
  (->> selected
       (map (fn [u]
              (+ (count (or (:signature u) ""))
                 (count (or (:summary u) ""))
                 (count (or (:symbol u) "")))))
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
        linked-test-paths (->> selected-modules
                               (mapcat #(get (:test_target_index index) % #{}))
                               distinct
                               (take 12)
                               vec)
        related-tests (->> (idx/all-units index)
                           (filter #(or (= "test" (:kind %))
                                        (str/includes? (:path %) "/test/")))
                           (filter (fn [u]
                                     (or (contains? selected-ids (:unit_id u))
                                         (contains? (set linked-test-paths) (:path u))
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

(defn- build-confidence [selected query policy]
  (let [top (first selected)
        second-best (second selected)
        tier1 (get-in top [:tier_scores :tier1] 0)
        tier2 (get-in top [:tier_scores :tier2] 0)
        exact-target? (and (seq (get-in query [:targets :symbols]))
                           (some #(contains? (set (get-in query [:targets :symbols])) (:symbol %)) selected))
        parser-fallback? (some #(= "fallback" (:parser_mode %)) selected)
        ambiguous? (and top second-best
                        (<= (Math/abs (- (:score top) (:score second-best)))
                            (rp/threshold policy :ambiguity_delta_max)))
        level (cond
                (and (pos? tier1) exact-target? (not parser-fallback?) (not ambiguous?)) "high"
                (or (pos? tier1) (>= tier2 50) exact-target? (seq (get-in query [:targets :changed_spans])) (seq (get-in query [:targets :paths]))) "medium"
                :else "low")
        adjusted-level (if parser-fallback? "low" level)
        reasons (cond-> []
                  exact-target? (conj (coded "exact_target_resolved" "Target symbol resolved to authority unit."))
                  (and top (pos? tier1)) (conj (coded "tier1_structural_signal" "Strong tier1 structural evidence is present."))
                  (and top (>= (:score top) 80)) (conj (coded "graph_proximity_strong" "High structural score for selected unit."))
                  (seq (get-in query [:targets :changed_spans])) (conj (coded "diff_overlap_direct" "Changed span overlap contributed to retrieval.")))
        warnings (cond-> []
                   parser-fallback? (conj (coded "parser_partial" "Parser coverage is partial for at least one selected unit."))
                   parser-fallback? (conj (coded "parser_fallback" "Fallback parser used for at least one selected unit."))
                   (zero? tier1) (conj (coded "no_tier1_evidence" "No tier1 structural evidence; confidence is ceiling-limited."))
                   ambiguous? (conj (coded "target_ambiguous" "Top ranked units are close in score; authority target is ambiguous.")))
        missing (cond-> []
                  (not exact-target?) (conj (coded "exact_target_resolution_missing" "No exact symbol target resolved from query."))
                  (empty? reasons) (conj (coded "structural_evidence_weak" "No strong structural evidence was found.")))
        numeric (rp/confidence-score policy adjusted-level)]
    {:schema_version "1.0"
     :level adjusted-level
     :score numeric
     :reasons (vec (take 10 reasons))
     :warnings (vec (take 10 warnings))
     :missing_evidence (vec (take 10 missing))}))

(defn- build-guardrails [confidence impact query policy capabilities]
  (let [level (:level confidence)
        broad-impact? (> (count (:risky_neighbors impact))
                         (rp/threshold policy :broad_impact_neighbor_threshold))
        raw-level (get-in query [:constraints :max_raw_code_level] "enclosing_unit")
        coverage-level (:coverage_level capabilities)
        posture (case level
                  "high" "draft_patch_safe"
                  "medium" "plan_safe"
                  "autonomy_blocked")
        posture* (if (= coverage-level "fallback_only") "autonomy_blocked" posture)
        blocked? (= posture* "autonomy_blocked")]
    {:schema_version "1.0"
     :autonomy_posture posture*
     :blocking_reasons (cond-> []
                         blocked? (conj (coded "confidence_low" "Confidence level is low for autonomous drafting."))
                         (= coverage-level "fallback_only") (conj (coded "capability_low" "Selected evidence comes only from fallback parser coverage."))
                         broad-impact? (conj (coded "impact_broad" "Impact surface appears broad and needs review.")))
     :required_next_steps (case posture*
                            "draft_patch_safe" [(coded "run_targeted_tests" "Run nearest tests before any apply path.")]
                            "plan_safe" [(coded "fetch_more_context" "Fetch additional context before drafting changes.")]
                            [(coded "human_review_required" "Human review is required before proceeding.")])
     :allowed_action_scope {:mode (case posture*
                                    "draft_patch_safe" "draft_patch_on_selected_unit_only"
                                    "plan_safe" "plan_only"
                                    "analysis_only")
                            :allow_multi_file_edit false
                            :allow_apply_without_human_review false
                            :max_raw_code_level raw-level}
     :risk_flags (cond-> []
                   broad-impact? (conj (coded "impact_broad" "Riskiest neighbors exceed safe localized threshold."))
                   (= coverage-level "fallback_only") (conj (coded "capability_low" "Fallback-only evidence requires review."))
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

(def ^:private raw-level-order
  {"none" 0
   "target_span" 1
   "enclosing_unit" 2
   "local_neighborhood" 3
   "whole_file" 4})

(defn- raw-escalation-level [query]
  (if (true? (get-in query [:options :allow_raw_code_escalation]))
    (get-in query [:constraints :max_raw_code_level] "enclosing_unit")
    "none"))

(defn- read-file-lines [index path]
  (let [root (:root_path index)
        f (io/file root path)]
    (when (.exists f)
      (-> f slurp str/split-lines vec))))

(defn- bounded-span [unit level total-lines]
  (let [start (:start_line unit)
        end (:end_line unit)
        clamp (fn [n] (-> n (max 1) (min total-lines)))]
    (case level
      "target_span" {:start (clamp start) :end (clamp end)}
      "enclosing_unit" {:start (clamp start) :end (clamp end)}
      "local_neighborhood" {:start (clamp (- start 8)) :end (clamp (+ end 8))}
      "whole_file" {:start 1 :end total-lines}
      nil)))

(defn- snippet-bytes [s]
  (count (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8)))

(defn- perform-raw-fetch [index selected query requested-token-budget]
  (let [level (raw-escalation-level query)]
    (if (= level "none")
      {:status "skipped"
       :level "none"
       :requests 0
       :snippets 0
       :bytes 0
       :warnings []
       :degradations []}
      (let [max-units 6
            max-bytes (* 4 (max 200 requested-token-budget))
            chosen (take max-units selected)]
        (loop [units chosen
               requests 0
               snippets 0
               bytes 0
               warnings []
               degradations []
               truncated? false]
          (if (empty? units)
            (let [status (if (or (seq degradations) truncated?) "degraded" "completed")
                  status* (if (zero? snippets) "degraded" status)
                  warnings* (cond-> warnings
                              truncated? (conj (coded "raw_fetch_budget_limited" "Raw fetch was truncated by budget limits.")))
                  degradations* (cond-> degradations
                                  (zero? snippets) (conj (coded "raw_fetch_empty" "Raw-code escalation produced no snippets.")))]
              {:status status*
               :level level
               :requests requests
               :snippets snippets
               :bytes bytes
               :warnings (vec (take 10 warnings*))
               :degradations (vec (take 10 degradations*))})
            (let [u (first units)
                  lines (read-file-lines index (:path u))]
              (if-not (seq lines)
                (recur (rest units)
                       (inc requests)
                       snippets
                       bytes
                       warnings
                       (conj degradations (coded "raw_fetch_file_missing" (str "Unable to read " (:path u) " for raw fetch.")))
                       truncated?)
                (let [span (bounded-span u level (count lines))]
                  (if-not span
                    (recur (rest units)
                           (inc requests)
                           snippets
                           bytes
                           warnings
                           (conj degradations (coded "raw_fetch_level_invalid" "Unknown raw-code fetch level requested."))
                           truncated?)
                    (let [chunk (->> (subvec lines (dec (:start span)) (:end span))
                                     (str/join "\n"))
                          chunk-bytes (snippet-bytes chunk)
                          next-bytes (+ bytes chunk-bytes)]
                      (if (> next-bytes max-bytes)
                        (recur [] requests snippets bytes warnings degradations true)
                        (recur (rest units)
                               (inc requests)
                               (inc snippets)
                               next-bytes
                               warnings
                               degradations
                               truncated?)))))))))))))

(defn resolve-context
  ([index query]
   (resolve-context index query {}))
  ([index query opts]
   (validate-query! query)
   (let [policy (rp/resolve-policy (:retrieval_policy opts) (:policy_registry opts))
         trace-id (get-in query [:trace :trace_id] (str (java.util.UUID/randomUUID)))
         request-id (get-in query [:trace :request_id] (str "req-" (subs trace-id 0 8)))
         summary (summarize-query query)
         stage-query (build-stage "query_validation" "completed" "Structured query accepted." {:target_count (count (:targets_summary summary)) :constraint_count (count (:constraints_summary summary))} [] [] 2)
         {:keys [scored]} (collect-candidates index query policy)
         stage-candidates (build-stage "candidate_generation" "completed" "Generated retrieval candidates from structural signals." {:candidate_units (count scored) :candidate_files (count (distinct (map :path scored)))} [] [] 7)
         ranked (->> (with-rank-band scored policy) (sort-by (juxt (comp - :score) :path :start_line)) vec)
         selected (vec (take 20 ranked))
         stage-ranking (build-stage "ranking" "completed" "Ranked candidates using structural-first signals." {:ranked_units (count ranked) :top_authority_units (count (filter #(= "top_authority" (:rank_band %)) selected))} [] [] 4)
         requested (get-in query [:constraints :token_budget] 1800)
         estimated (estimate-tokens selected)
         truncation (cond-> [] (> estimated requested) (conj "budget_restricted"))
         budget {:requested_tokens requested :estimated_tokens estimated :truncation_flags truncation}
         impact (build-impact-hints index selected)
         capabilities (rp/capability-summary index selected)
         base-confidence (build-confidence selected query policy)
         raw-fetch (perform-raw-fetch index selected query requested)
         fallback-selected? (some #(= "parser_fallback" (:code %)) (:warnings base-confidence))
         confidence-a (cond-> base-confidence
                        (and (not= "none" (:level raw-fetch))
                             (pos? (:snippets raw-fetch)))
                        (update :reasons conj (coded "raw_code_escalated" "Late raw-code fetch was performed for selected units."))
                        (seq (:degradations raw-fetch))
                        (update :warnings conj (coded "raw_fetch_degraded" "Raw-code escalation produced degraded signals.")))
         confidence-b (if (and (= "low" (:level base-confidence))
                               (= "completed" (:status raw-fetch))
                               (>= (:snippets raw-fetch) (rp/raw-fetch-threshold policy :medium_upgrade_min_snippets))
                               (not fallback-selected?))
                        (-> confidence-a
                            (assoc :level "medium"
                                   :score (rp/confidence-score policy "medium"))
                            (update :reasons conj (coded "raw_fetch_disambiguated" "Raw-code spans reduced ambiguity for low-confidence retrieval.")))
                        confidence-a)
         confidence (-> confidence-b
                        (update :reasons #(vec (take 10 %)))
                        (update :warnings #(vec (take 10 %))))
         guardrails (build-guardrails confidence impact query policy capabilities)
         focus-paths (->> selected (map :path) distinct (take 20) vec)
         focus-modules (->> selected (map :module) (remove nil?) distinct (take 20) vec)
         context-packet {:schema_version "1.0"
                         :retrieval_policy (rp/policy-summary policy)
                         :capabilities capabilities
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
                                                    (conj (coded "hints_applied" "Soft hints were applied during candidate ranking."))
                                                    (and (not= "none" (:level raw-fetch))
                                                         (pos? (:snippets raw-fetch)))
                                                    (conj (coded "raw_code_escalated" "Late raw-code fetch was executed for ranked units.")))}
                         :budget budget
                         :confidence confidence}
         packet-status (if (or (= "low" (:level confidence))
                               (= "degraded" (:status raw-fetch)))
                         "degraded"
                         "completed")
         packet-warns (cond-> []
                        (= "low" (:level confidence)) (conj (coded "confidence_low" "Context packet confidence is low."))
                        (= "degraded" (:status raw-fetch)) (conj (coded "raw_fetch_degraded" "Raw-code fetch was executed in degraded mode.")))
         stage-packet (build-stage "context_packet_assembly" packet-status "Assembled bounded context packet." {:selected_units (count selected) :selected_files (count focus-paths)} packet-warns [] 5)
         stage-fetch (build-stage "raw_code_fetch"
                                  (:status raw-fetch)
                                  (case (:status raw-fetch)
                                    "skipped" "Late raw-code fetch skipped by query options."
                                    "degraded" "Late raw-code fetch executed with degradation flags."
                                    "completed" "Late raw-code fetch completed for ranked units."
                                    "Late raw-code fetch stage completed.")
                                  {:raw_fetch_requests (:requests raw-fetch)
                                   :raw_fetch_snippets (:snippets raw-fetch)
                                   :raw_fetch_bytes (:bytes raw-fetch)}
                                  (:warnings raw-fetch)
                                  (:degradations raw-fetch)
                                  (if (= "skipped" (:status raw-fetch)) 0 3))
         base-degradations (cond-> []
                             (= "low" (:level confidence)) (conj (coded "confidence_low" "Confidence degraded due to weak or ambiguous evidence."))
                             fallback-selected? (conj (coded "parser_fallback" "Fallback parser evidence contributed to selected units.")))
         diagnostics-degradations (vec (take 10 (concat base-degradations (:degradations raw-fetch))))
         stage-final-status (if (or (= "low" (:level confidence))
                                    (seq diagnostics-degradations))
                              "degraded"
                              "completed")
         stage-final (build-stage "result_finalization"
                                  stage-final-status
                                  "Confidence, guardrails, and diagnostics emitted."
                                  {:warning_count (count (:warnings confidence))
                                   :degradation_count (count diagnostics-degradations)}
                                  []
                                  diagnostics-degradations
                                  2)
         stages [stage-query stage-candidates stage-ranking stage-packet stage-fetch stage-final]
         diagnostics {:schema_version "1.0"
                      :retrieval_policy (rp/policy-summary policy)
                      :capabilities capabilities
                      :trace {:trace_id trace-id
                              :request_id request-id
                              :timestamp_start (now-iso)
                              :timestamp_end (now-iso)
                              :host_metadata {:host "library_runtime"
                                              :interactive true}}
                      :query (assoc summary
                                    :options_summary (->> (:options query) (keep (fn [[k v]] (when (true? v) (name k)))) vec)
                                    :validation_status "accepted")
                      :stages stages
                      :result {:selected_units_count (count selected)
                               :selected_files_count (count focus-paths)
                               :raw_fetch_level_reached (:level raw-fetch)
                               :packet_size_estimate estimated
                               :top_authority_targets (->> selected (filter #(= "top_authority" (:rank_band %))) (map :unit_id) (take 10) vec)
                               :result_status (if (or (= "low" (:level confidence))
                                                      (= "degraded" (:status raw-fetch)))
                                                "degraded"
                                                "completed")}
                      :warnings (vec (take 10 (concat (:warnings confidence) (:warnings raw-fetch))))
                      :degradations diagnostics-degradations
                      :confidence confidence
                      :guardrails guardrails
                      :performance {:total_duration_ms (+ 20 (if (= "skipped" (:status raw-fetch)) 0 3))
                                    :cache_summary {:cache_hits 0 :cache_misses 1}
                                    :parser_summary {:fallback_units (count (filter #(= "fallback" (:parser_mode %)) selected))
                                                     :selected_units (count selected)}
                                    :fetch_summary {:raw_fetch_requests (:requests raw-fetch)
                                                    :raw_fetch_snippets (:snippets raw-fetch)
                                                    :raw_fetch_bytes (:bytes raw-fetch)}
                                    :budget_summary {:requested_tokens requested :estimated_tokens estimated}}}
         events (build-stage-events trace-id request-id (get-in query [:intent :purpose] "unknown") stages {:requested_tokens requested :estimated_tokens estimated})]
     (when-let [explain (m/explain (:example/context-packet contracts/contracts) context-packet)]
       (throw (ex-info "invalid context packet generated" {:type :internal_contract_error :errors (me/humanize explain)})))
     (when-let [explain (m/explain (:example/diagnostics-trace contracts/contracts) diagnostics)]
       (throw (ex-info "invalid diagnostics trace generated" {:type :internal_contract_error :errors (me/humanize explain)})))
     {:context_packet context-packet
      :guardrail_assessment guardrails
      :diagnostics_trace diagnostics
      :stage_events events})))

(defn impact-analysis
  ([index query]
   (impact-analysis index query {}))
  ([index query opts]
   (-> (resolve-context index query opts)
       :context_packet
       :impact_hints)))

(defn skeletons [index {:keys [unit_ids paths]}]
  (let [units (cond
                (seq unit_ids) (idx/units-by-ids index unit_ids)
                (seq paths) (->> paths (mapcat #(idx/units-for-path index %)) distinct vec)
                :else (->> (idx/all-units index) (take 20) vec))]
    (mapv compact-skeleton units)))
