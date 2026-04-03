(ns semidx.runtime.retrieval
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [semidx.contracts.schemas :as contracts]
            [semidx.runtime.index :as idx]
            [semidx.runtime.projections :as projections]
            [semidx.runtime.retrieval-policy :as rp]))

(defn- now-iso []
  (-> (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)
      (.withNano 0)
      (.format java.time.format.DateTimeFormatter/ISO_INSTANT)))

(defn- now-ms []
  (System/currentTimeMillis))

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
                       (keep (fn [[k v]]
                               (when (or (true? v)
                                         (and (coll? v) (seq v)))
                                 (str (name k)))))
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

(defn- include-tests? [query]
  (or (true? (get-in query [:options :include_tests]))
      (true? (get-in query [:hints :focus_on_tests]))))

(defn- allowed-path? [path allowed-prefixes]
  (or (empty? allowed-prefixes)
      (some #(str/starts-with? (str path) (str %)) allowed-prefixes)))

(defn- allowed-language? [index unit language-allowlist]
  (or (empty? language-allowlist)
      (contains? (set (map str/lower-case language-allowlist))
                 (some-> (get-in index [:files (:path unit) :language]) str str/lower-case))))

(defn- query-visible-units [index query]
  (let [allowed-prefixes (get-in query [:constraints :allowed_path_prefixes] [])
        language-allowlist (get-in query [:constraints :language_allowlist] [])
        include-tests?* (include-tests? query)
        explicitly-targeted-tests (set (get-in query [:targets :tests] []))]
    (->> (idx/all-units index)
         (filter #(allowed-path? (:path %) allowed-prefixes))
         (filter #(allowed-language? index % language-allowlist))
         (filter (fn [u]
                   (or include-tests?*
                       (not (or (= "test" (:kind u))
                                (str/includes? (:path u) "/test/")))
                       (contains? explicitly-targeted-tests (:path u)))))
         vec)))

(def ^:private graph-traversal-codes
  #{"graph_callee_neighbor"
    "graph_caller_neighbor"})

(defn- add-scored-reason [score-map uid tier points code summary]
  (let [already-present? (some #(= code (:code %))
                               (get-in score-map [uid :reasons]))]
    (if (and (pos? (long (or points 0)))
             (not already-present?))
      (add-tier score-map uid tier points (coded code summary))
      score-map)))

(defn- structural-seed-reasons [u query tokens]
  (let [target-symbols (get-in query [:targets :symbols] [])
        target-paths (set (get-in query [:targets :paths] []))
        target-modules (get-in query [:targets :modules] [])
        target-tests (set (get-in query [:targets :tests] []))
        changed-spans (get-in query [:targets :changed_spans] [])
        by-symbol (some #(= (:symbol u) %) target-symbols)
        by-path (contains? target-paths (:path u))
        by-module (some #(module-prefix-match? u %) target-modules)
        by-test (contains? target-tests (:path u))
        by-span (some #(overlap-span? u %) changed-spans)
        by-dispatch (dispatch-match? u tokens)]
    (cond-> []
      by-symbol (conj [:tier1 "exact_target_resolved" "Tier1: target symbol resolved to unit."])
      by-path (conj [:tier1 "target_path_match" "Tier1: unit path directly targeted by query."])
      by-span (conj [:tier1 "diff_overlap_direct" "Tier1: changed span overlaps this unit."])
      by-module (conj [:tier2 "target_module_match" "Tier2: unit module targeted by query."])
      by-test (conj [:tier2 "target_test_match" "Tier2: unit appears in explicitly requested tests."])
      by-dispatch (conj [:tier2 "dispatch_value_match" "Tier2: multimethod dispatch value matches the query intent."]))))

(defn- lexical-seed-units [units tokens]
  (->> units
       (filter #(or (lexical-match? % tokens)
                    (dispatch-match? % tokens)))
       (sort-by (juxt :path :start_line))
       (take 6)
       vec))

(defn- related-test-unit-ids [index module]
  (if (seq module)
    (->> (get (:test_target_index index) module #{})
         (mapcat #(map :unit_id (idx/units-for-path index %)))
         distinct
         vec)
    []))

(defn- graph-neighbor-groups [index unit query]
  (let [unit-id (:unit_id unit)
        path-neighbor-ids (->> (idx/units-for-path index (:path unit))
                               (map :unit_id)
                               (remove #(= unit-id %))
                               distinct
                               vec)
        module-neighbor-ids (if-let [module (:module unit)]
                              (->> (idx/units-for-module index module)
                                   (map :unit_id)
                                   (remove #(= unit-id %))
                                   distinct
                                   vec)
                              [])
        related-test-ids (if (include-tests? query)
                           (->> (related-test-unit-ids index (:module unit))
                                (remove #(= unit-id %))
                                distinct
                                vec)
                           [])]
    [["graph_callee_neighbor" "Tier2: graph expansion followed outbound call edges from a seeded unit." (vec (get (:callees_index index) unit-id #{}))]
     ["graph_caller_neighbor" "Tier2: graph expansion followed inbound caller edges from a seeded unit." (vec (get (:callers_index index) unit-id #{}))]
     ["graph_module_neighbor" "Tier3: graph expansion pulled structurally adjacent units from the same module." module-neighbor-ids]
     ["graph_path_neighbor" "Tier3: graph expansion pulled neighboring units from the same file." path-neighbor-ids]
     ["graph_related_test_neighbor" "Tier3: graph expansion linked related tests for the seeded unit's module." related-test-ids]]))

(defn- graph-neighbor-points [policy code depth prefer-definitions-over-callers?]
  (let [base (rp/weight policy code)
        caller-penalty (if (and prefer-definitions-over-callers?
                                (= code "graph_caller_neighbor"))
                         8
                         0)
        decayed (- base caller-penalty (* depth 10))]
    (max 0 decayed)))

(defn- apply-global-boosts [units query policy score-map]
  (let [hints (:hints query)
        preferred-paths (set (:preferred_paths hints))
        preferred-modules (:preferred_modules hints)]
    (reduce
     (fn [acc u]
       (let [uid (:unit_id u)
             by-pref-path (contains? preferred-paths (:path u))
             by-pref-module (some #(module-prefix-match? u %) preferred-modules)
             by-parser-fallback (= "fallback" (:parser_mode u))]
         (cond-> acc
           by-pref-path (add-scored-reason uid :tier3 (rp/weight policy "hint_preferred_path") "hint_preferred_path" "Tier3: preferred path hint boosted unit.")
           by-pref-module (add-scored-reason uid :tier3 (rp/weight policy "hint_preferred_module") "hint_preferred_module" "Tier3: preferred module hint boosted unit.")
           by-parser-fallback (add-scored-reason uid :tier3 (rp/weight policy "parser_fallback") "parser_fallback" "Fallback parser contributes limited-confidence evidence."))))
     score-map
     units)))

(defn- expand-graph-score-map [index units query policy score-map seed-ids]
  (let [visible-by-id (into {} (map (juxt :unit_id identity) units))
        visible-id-set (set (keys visible-by-id))
        max-depth (if (true? (get-in query [:hints :prefer_breadth_over_depth])) 3 2)
        prefer-definitions-over-callers? (true? (get-in query [:hints :prefer_definitions_over_callers]))]
    (loop [frontier (vec (distinct seed-ids))
           visited (set seed-ids)
           depth 0
           acc score-map]
      (if (or (empty? frontier) (>= depth max-depth))
        acc
        (let [[acc* next-frontier visited*]
              (reduce
               (fn [[score-map next-frontier visited] unit-id]
                 (if-let [unit (get visible-by-id unit-id)]
                   (reduce
                    (fn [[score-map next-frontier visited] [code summary neighbor-ids]]
                      (reduce
                       (fn [[score-map next-frontier visited] neighbor-id]
                         (if (or (not (contains? visible-id-set neighbor-id))
                                 (= neighbor-id unit-id))
                           [score-map next-frontier visited]
                           (let [tier (if (contains? graph-traversal-codes code) :tier2 :tier3)
                                 points (graph-neighbor-points policy code depth prefer-definitions-over-callers?)
                                 score-map* (add-scored-reason score-map neighbor-id tier points code summary)
                                 traversable? (contains? graph-traversal-codes code)
                                 next? (and traversable?
                                            (< (inc depth) max-depth)
                                            (not (contains? visited neighbor-id)))]
                             [score-map*
                              (cond-> next-frontier next? (conj neighbor-id))
                              (cond-> visited next? (conj neighbor-id))])))
                       [score-map next-frontier visited]
                       neighbor-ids))
                    [score-map next-frontier visited]
                    (graph-neighbor-groups index unit query))
                   [score-map next-frontier visited]))
               [acc [] visited]
               frontier)]
          (recur (vec (distinct next-frontier)) visited* (inc depth) acc*))))))

(defn- collect-candidates [index query policy]
  (let [units (query-visible-units index query)
        tokens (lexical-tokens query)
        seed-pairs (->> units
                        (map (fn [u]
                               [u (structural-seed-reasons u query tokens)]))
                        vec)
        structural-seed-ids (->> seed-pairs
                                 (keep (fn [[u reasons]]
                                         (when (seq reasons)
                                           (:unit_id u))))
                                 distinct
                                 vec)
        seeded-score-map (reduce (fn [acc [u reasons]]
                                   (reduce (fn [score-map [tier code summary]]
                                             (add-scored-reason score-map
                                                                (:unit_id u)
                                                                tier
                                                                (rp/weight policy code)
                                                                code
                                                                summary))
                                           acc
                                           reasons))
                                 {}
                                 seed-pairs)
        lexical-seeds (if (seq structural-seed-ids)
                        []
                        (lexical-seed-units units tokens))
        lexical-seed-ids (mapv :unit_id lexical-seeds)
        lexical-score-map (reduce (fn [acc u]
                                    (add-scored-reason acc
                                                       (:unit_id u)
                                                       :tier4
                                                       (rp/weight policy "lexical_overlap")
                                                       "lexical_overlap"
                                                       "Tier4: lexical overlap seeded retrieval because structural seeds were absent."))
                                  seeded-score-map
                                  lexical-seeds)
        seed-ids (vec (distinct (concat structural-seed-ids lexical-seed-ids)))
        graph-score-map (if (seq seed-ids)
                          (expand-graph-score-map index units query policy lexical-score-map seed-ids)
                          lexical-score-map)
        score-map (apply-global-boosts units query policy graph-score-map)
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
                    vec)]
    {:scored scored
     :tokens tokens
     :seed_ids seed-ids}))

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

(defn- estimate-skeleton-tokens [u]
  (->> [(or (:signature u) "")
        (or (:summary u) "")
        (or (:docstring_excerpt u) "")]
       (map count)
       (reduce + 0)
       (#(int (Math/ceil (/ (double %) 4.0))))))

(defn- estimate-impact-hints-tokens [impact]
  (if (map? impact)
    (->> impact
         vals
         (mapcat identity)
         (map #(count (str %)))
         (reduce + 0)
         (+ (* 12 (count (keys impact))))
         (#(int (Math/ceil (/ (double %) 4.0)))))
    0))

(defn- estimate-raw-context-tokens [raw-context]
  (->> raw-context
       (map (fn [{:keys [content]}]
              (count (str content))))
       (reduce + 0)
       (#(int (Math/ceil (/ (double %) 4.0))))))

(defn- fit-items-to-budget [items estimate-fn budget]
  (let [budget* (max 0 (int (or budget 0)))]
    (loop [remaining items
           chosen []
           used 0
           truncated? false]
      (if (empty? remaining)
        {:items chosen
         :used_tokens used
         :truncated? truncated?}
        (let [item (first remaining)
              item-tokens (max 0 (int (estimate-fn item)))
              next-used (+ used item-tokens)]
          (if (<= next-used budget*)
            (recur (rest remaining)
                   (conj chosen item)
                   next-used
                   truncated?)
            {:items chosen
             :used_tokens used
             :truncated? true}))))))

(defn- stage-result-status [source-items kept-items truncation-flags]
  (cond
    (and (seq source-items) (empty? kept-items)) "budget_exhausted"
    (seq truncation-flags) "truncated"
    :else "completed"))

(defn- detail-structure-budget [detail-budget raw-level]
  (let [budget* (max 0 (int (or detail-budget 0)))]
    (cond
      (zero? budget*) 0
      (= "none" raw-level) budget*
      (< budget* 160) budget*
      :else (min budget*
                 (max 120 (int (Math/floor (* 0.35 budget*))))))))

(defn- top-reasons [selected]
  (->> selected
       (mapcat :selection_reasons)
       distinct
       (take 10)
       vec))

(defn- capability-units [selected]
  (let [focused (->> selected
                     (filter #(contains? #{"top_authority" "useful_support"} (:rank_band %)))
                     vec)]
    (if (seq focused)
      focused
      selected)))

(defn- file-modules-for-paths [index paths]
  (->> paths
       (keep #(get-in index [:files % :module]))
       (remove nil?)
       distinct
       vec))

(defn- build-impact-hints [index selected]
  (let [selected-ids (set (map :unit_id selected))
        caller-units (->> selected
                          (mapcat (fn [u]
                                    (map #(idx/unit-by-id index %)
                                         (get (:callers_index index) (:unit_id u) #{}))))
                          (remove nil?)
                          distinct
                          vec)
        callers (->> caller-units
                     (map #(str (:path %) "::" (:symbol %)))
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
        indirect-test-paths (->> (concat (file-modules-for-paths index linked-test-paths)
                                         (->> caller-units
                                              (filter #(or (= "test" (:kind %))
                                                           (str/includes? (:path %) "/test/")))
                                              (map :module)
                                              (remove nil?)))
                                 distinct
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
                                         (contains? (set indirect-test-paths) (:path u))
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

(defn- empty-impact-hints []
  {:callers []
   :dependents []
   :related_tests []
   :risky_neighbors []})

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

(defn- apply-capability-ceiling [confidence capabilities policy]
  (let [current-level (:level confidence "low")
        ceiling (get capabilities :confidence_ceiling "low")
        capped-level (rp/min-confidence-level current-level ceiling)
        capped? (not= capped-level current-level)]
    (cond-> (assoc confidence
                   :level capped-level
                   :score (rp/confidence-score policy capped-level))
      capped?
      (-> (update :warnings conj (coded "capability_ceiling" "Language-semantic capability ceiling lowered retrieval confidence."))
          (update :missing_evidence conj (coded "language_strength_limited" "Selected language support does not justify a higher confidence ceiling."))))))

(defn- build-guardrails [confidence impact query policy capabilities]
  (let [level (:level confidence)
        broad-impact? (> (count (:risky_neighbors impact))
                         (rp/threshold policy :broad_impact_neighbor_threshold))
        raw-level (get-in query [:constraints :max_raw_code_level] "enclosing_unit")
        coverage-level (:coverage_level capabilities)
        capability-ceiling (:confidence_ceiling capabilities)
        capability-limited? (not= capability-ceiling "high")
        freshness (get-in query [:constraints :freshness] "current_snapshot")
        stale-index? (true? (:index_stale capabilities))
        posture (case level
                  "high" "draft_patch_safe"
                  "medium" "plan_safe"
                  "autonomy_blocked")
        posture* (cond
                   stale-index? "autonomy_blocked"
                   (= coverage-level "fallback_only") "autonomy_blocked"
                   :else posture)
        blocked? (= posture* "autonomy_blocked")]
    {:schema_version "1.0"
     :autonomy_posture posture*
     :blocking_reasons (cond-> []
                         (and blocked? (= level "low")) (conj (coded "confidence_low" "Confidence level is low for autonomous drafting."))
                         (and stale-index? (= freshness "current_snapshot")) (conj (coded "stale_index" "Selected index snapshot is stale for current-snapshot freshness requirements."))
                         (= coverage-level "fallback_only") (conj (coded "capability_low" "Selected evidence comes only from fallback parser coverage."))
                         (and blocked? capability-limited?) (conj (coded "capability_ceiling" "Language-semantic capability ceiling blocks autonomous drafting."))
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
                   stale-index? (conj (coded "stale_index" "Selected snapshot is stale and should be reviewed or rebuilt."))
                   broad-impact? (conj (coded "impact_broad" "Riskiest neighbors exceed safe localized threshold."))
                   (= coverage-level "fallback_only") (conj (coded "capability_low" "Fallback-only evidence requires review."))
                   capability-limited? (conj (coded "capability_ceiling" "Language-semantic capability ceiling limits downstream autonomy."))
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

(defn- selection-file-lines [selection path]
  (or (get-in selection [:file_snapshots path])
      []))

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

(defn- perform-raw-fetch [_index selection selected query requested-token-budget]
  (let [level (raw-escalation-level query)]
    (if (= level "none")
      {:status "skipped"
       :level "none"
       :requests 0
       :snippets 0
       :raw_context []
       :bytes 0
       :warnings []
       :degradations []}
      (if (<= (long (or requested-token-budget 0)) 0)
        {:status "skipped"
         :level level
         :requests 0
         :snippets 0
         :raw_context []
         :bytes 0
         :warnings [(coded "raw_fetch_budget_exhausted" "No budget remained for late raw-code fetch.")]
         :degradations []}
        (let [max-units 6
              max-bytes (* 4 (max 200 requested-token-budget))
              chosen (take max-units selected)]
          (loop [units chosen
                 requests 0
                 snippets 0
                 raw-context []
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
                 :raw_context raw-context
                 :bytes bytes
                 :warnings (vec (take 10 warnings*))
                 :degradations (vec (take 10 degradations*))})
              (let [u (first units)
                    lines (selection-file-lines selection (:path u))]
                (if-not (seq lines)
                  (recur (rest units)
                         (inc requests)
                         snippets
                         raw-context
                         bytes
                         warnings
                         (conj degradations (coded "raw_fetch_file_missing" (str "Unable to read " (:path u) " for raw fetch.")))
                         truncated?)
                  (let [span (bounded-span u level (count lines))]
                    (if-not span
                      (recur (rest units)
                             (inc requests)
                             snippets
                             raw-context
                             bytes
                             warnings
                             (conj degradations (coded "raw_fetch_level_invalid" "Unknown raw-code fetch level requested."))
                             truncated?)
                      (let [chunk (->> (subvec lines (dec (:start span)) (:end span))
                                       (str/join "\n"))
                            chunk-bytes (snippet-bytes chunk)
                            next-bytes (+ bytes chunk-bytes)]
                        (if (> next-bytes max-bytes)
                          (recur [] requests snippets raw-context bytes warnings degradations true)
                          (recur (rest units)
                                 (inc requests)
                                 (inc snippets)
                                 (conj raw-context
                                       {:unit_id (:unit_id u)
                                        :path (:path u)
                                        :start_line (:start span)
                                        :end_line (:end span)
                                        :content chunk})
                                 next-bytes
                                 warnings
                                 degradations
                                 truncated?))))))))))))))

(def ^:private default-api-version "1.0")
(def ^:private default-selection-cache-max-entries 128)
(def ^:private default-evicted-selection-memory 512)

(defn- query-api-version [query]
  (or (:api_version query) default-api-version))

(defn- ensure-supported-api-version! [query]
  (let [version (query-api-version query)]
    (when-not (= default-api-version (str version))
      (throw (ex-info "unsupported api_version"
                      {:type :unsupported_api_version
                       :message "unsupported api_version"
                       :details {:provided_api_version (str version)
                                 :supported_api_versions [default-api-version]}})))))

(defn- enforce-query-constraints! [index query]
  (when-let [requested-snapshot (get-in query [:constraints :snapshot_id])]
    (when (not= (str requested-snapshot) (str (:snapshot_id index)))
      (throw (ex-info "requested snapshot_id is not available on the current index"
                      {:type :invalid_request
                       :message "requested snapshot_id is not available on the current index"})))))

(defn- selection-cache [index]
  (:selection_cache (meta index)))

(defn- normalized-selection-cache-state [state]
  (let [entries (cond
                  (map? (:entries state)) (:entries state)
                  (map? state) (dissoc state :entries :order :evicted :evicted_order :max_entries :max_evicted)
                  :else {})
        order-seen (set (or (:order state) []))
        order (vec (concat (filter #(contains? entries %) (or (:order state) []))
                           (remove order-seen (keys entries))))
        evicted (or (:evicted state) {})
        evicted-order-seen (set (or (:evicted_order state) []))
        evicted-order (vec (concat (filter #(contains? evicted %) (or (:evicted_order state) []))
                                   (remove evicted-order-seen (keys evicted))))]
    {:entries entries
     :order order
     :evicted evicted
     :evicted_order evicted-order
     :max_entries (max 1 (int (or (:max_entries state) default-selection-cache-max-entries)))
     :max_evicted (max 1 (int (or (:max_evicted state) default-evicted-selection-memory)))}))

(defn- snapshot-bound-index [index]
  (with-meta index
    (apply dissoc (meta index) [:selection_cache :usage_metrics :usage_context])))

(defn- snapshot-file-lines [index paths]
  (reduce (fn [acc path]
            (assoc acc path (or (read-file-lines index path) [])))
          {}
          paths))

(defn- put-selection! [index selection]
  (when-let [cache (selection-cache index)]
    (swap! cache
           (fn [state]
             (let [{:keys [entries order evicted evicted_order max_entries max_evicted]}
                   (normalized-selection-cache-state state)
                   selection-id (:selection_id selection)
                   prior-order (vec (remove #(= selection-id %) order))
                   next-entries (assoc entries selection-id selection)
                   next-order (conj prior-order selection-id)
                   overflow (max 0 (- (count next-order) max_entries))
                   evicted-ids (vec (take overflow next-order))
                   retained-order (vec (drop overflow next-order))
                   retained-entries (apply dissoc next-entries evicted-ids)
                   evicted-at (now-ms)
                   evicted-meta (reduce (fn [acc sid]
                                          (let [entry (get next-entries sid)]
                                            (assoc acc sid {:selection_id sid
                                                            :snapshot_id (:snapshot_id entry)
                                                            :evicted_at evicted-at})))
                                        evicted
                                        evicted-ids)
                   next-evicted-order (vec (concat evicted_order evicted-ids))
                   overflow-evicted (max 0 (- (count next-evicted-order) max_evicted))
                   trimmed-evicted-ids (vec (take overflow-evicted next-evicted-order))
                   retained-evicted-order (vec (drop overflow-evicted next-evicted-order))
                   retained-evicted-meta (apply dissoc evicted-meta trimmed-evicted-ids)]
               {:entries retained-entries
                :order retained-order
                :evicted retained-evicted-meta
                :evicted_order retained-evicted-order
                :max_entries max_entries
                :max_evicted max_evicted}))))
  selection)

(defn- get-selection [index selection-id]
  (when-let [cache (selection-cache index)]
    (get-in (normalized-selection-cache-state @cache) [:entries selection-id])))

(defn- selection-evicted [index selection-id]
  (when-let [cache (selection-cache index)]
    (get-in (normalized-selection-cache-state @cache) [:evicted selection-id])))

(defn- detail-cache-key [{:keys [unit_ids detail_level]}]
  {:unit_ids (->> unit_ids (remove nil?) distinct sort vec)
   :detail_level (or detail_level "enclosing_unit")})

(defn- cached-detail-result [index selection-id cache-key]
  (when-let [cache (selection-cache index)]
    (get-in (normalized-selection-cache-state @cache)
            [:entries selection-id :detail_cache cache-key])))

(defn- cache-detail-result! [index selection-id cache-key result]
  (when-let [cache (selection-cache index)]
    (swap! cache
           (fn [state]
             (let [{:keys [entries order evicted evicted_order max_entries max_evicted]}
                   (normalized-selection-cache-state state)]
               {:entries (if (contains? entries selection-id)
                           (assoc-in entries [selection-id :detail_cache cache-key] result)
                           entries)
                :order order
                :evicted evicted
                :evicted_order evicted_order
                :max_entries max_entries
                :max_evicted max_evicted}))))
  result)

(defn- ensure-selection! [index selection-id snapshot-id]
  (let [selection (get-selection index selection-id)]
    (cond
      (some? (selection-evicted index selection-id))
      (throw (ex-info "selection_id was evicted"
                      {:type :selection_evicted
                       :message "selection_id was evicted"
                       :details {:selection_id selection-id
                                 :snapshot_id snapshot-id}}))

      (nil? selection)
      (throw (ex-info "selection_id not found"
                      {:type :selection_not_found
                       :message "selection_id not found"
                       :details {:selection_id selection-id
                                 :snapshot_id snapshot-id}}))

      (not= (str snapshot-id) (str (:snapshot_id selection)))
      (throw (ex-info "snapshot_id does not match selection"
                      {:type :snapshot_mismatch
                       :message "snapshot_id does not match selection"
                       :details {:selection_id selection-id
                                 :expected_snapshot_id (:snapshot_id selection)
                                 :provided_snapshot_id snapshot-id}}))

      :else
      selection)))

(defn- stage-budgets [requested]
  (let [selection-budget (max 80 (int (Math/floor (* requested 0.10))))
        expansion-budget (max 160 (int (Math/floor (* requested 0.20))))
        detail-budget (max 0 (- requested selection-budget expansion-budget))]
    {:selection_tokens selection-budget
     :expansion_tokens expansion-budget
     :detail_tokens detail-budget}))

(defn- compact-item-estimate [u]
  (int (Math/ceil (/ (double (+ (count (or (:symbol u) ""))
                                (count (or (:path u) ""))
                                (count (or (:unit_id u) ""))
                                24))
                     4.0))))

(defn- fit-focus [selected selection-budget]
  (loop [remaining selected
         chosen []
         used 0]
    (if (or (empty? remaining) (>= (count chosen) 5))
      {:focus chosen :estimated_tokens used}
      (let [u (first remaining)
            next-used (+ used (compact-item-estimate u))]
        (if (and (seq chosen) (> next-used selection-budget))
          {:focus chosen :estimated_tokens used}
          (if (> next-used selection-budget)
            {:focus [] :estimated_tokens next-used}
            (recur (rest remaining) (conj chosen u) next-used)))))))

(defn- compact-focus-unit [u]
  {:unit_id (:unit_id u)
   :symbol (:symbol u)
   :path (:path u)
   :span {:path (:path u)
          :start_line (:start_line u)
          :end_line (:end_line u)}
   :rank_band (:rank_band u)
   :why_selected (->> (:selection_reasons u)
                      (map :code)
                      distinct
                      (take 2)
                      vec)})

(defn- next-step [status focus confidence]
  (let [target-unit-ids (mapv :unit_id focus)]
    (case status
      "insufficient_evidence"
      {:recommended_action "expand_query_scope"
       :available_actions []
       :reason "No structurally relevant units were found."
       :target_unit_ids []}

      "budget_exhausted_at_selection"
      {:recommended_action "raise_token_budget"
       :available_actions []
       :reason "Selection payload could not fit into the reserved selection budget."
       :target_unit_ids []}

      {:recommended_action (if (= "low" (:level confidence)) "fetch_context_detail" "expand_context")
       :available_actions ["expand_context" "fetch_context_detail"]
       :reason (if (= "low" (:level confidence))
                 "Low confidence suggests additional detail fetch."
                 "Compact selection identified likely relevant units.")
       :target_unit_ids target-unit-ids})))

(defn- build-selection-result [index query policy selected]
  (let [requested (get-in query [:constraints :token_budget] 1800)
        reserved (stage-budgets requested)
        capabilities (rp/capability-summary index (capability-units selected))
        confidence (-> (build-confidence selected query policy)
                       (apply-capability-ceiling capabilities policy)
                       (update :reasons #(vec (take 10 %)))
                       (update :warnings #(vec (take 10 %)))
                       (update :missing_evidence #(vec (take 10 %))))
        {:keys [focus estimated_tokens]} (fit-focus selected (:selection_tokens reserved))
        status (cond
                 (empty? selected) "insufficient_evidence"
                 (empty? focus) "budget_exhausted_at_selection"
                 :else "completed")
        selection-id (str (java.util.UUID/randomUUID))
        selection {:api_version default-api-version
                   :selection_id selection-id
                   :snapshot_id (:snapshot_id index)
                   :query query
                   :policy policy
                   :created_at_ms (now-ms)
                   :bound_index (snapshot-bound-index index)
                   :file_snapshots (snapshot-file-lines index (->> selected (map :path) distinct vec))
                   :selected selected
                   :focus focus
                   :confidence confidence
                   :capabilities capabilities
                   :budget {:requested_tokens requested
                            :estimated_tokens estimated_tokens
                            :within_budget (<= estimated_tokens (:selection_tokens reserved))
                            :remaining_tokens (max 0 (- requested estimated_tokens))
                            :reserved_budget reserved}
                   :result_status status}]
    (put-selection! index selection)
    (with-meta
      (projections/with-projection
       {:api_version default-api-version
        :selection_id selection-id
        :snapshot_id (:snapshot_id index)
        :result_status status
        :confidence_level (:level confidence)
        :budget_summary (:budget selection)
        :focus (mapv compact-focus-unit focus)
        :next_step (next-step status focus confidence)}
       :selection
       :api-shape)
      {:retrieval_policy (rp/policy-summary policy)
       :capabilities capabilities
       :confidence confidence})))

(defn- build-detail-response [index selection selector]
  (let [query (:query selection)
        policy (:policy selection)
        trace-id (get-in query [:trace :trace_id] (str (java.util.UUID/randomUUID)))
        request-id (get-in query [:trace :request_id] (str "req-" (subs trace-id 0 8)))
        summary (summarize-query query)
        selected-source (if-let [unit-ids (seq (:unit_ids selector))]
                          (->> (:selected selection)
                               (filter #(contains? (set unit-ids) (:unit_id %)))
                               vec)
                          (:selected selection))
        requested (get-in query [:constraints :token_budget] 1800)
        detail-budget (get-in selection [:budget :reserved_budget :detail_tokens] requested)
        raw-detail-level (or (:detail_level selector)
                             (get-in query [:constraints :max_raw_code_level])
                             "enclosing_unit")
        detail-level (if (contains? raw-level-order raw-detail-level)
                       raw-detail-level
                       "enclosing_unit")
        query* (-> query
                   (assoc-in [:options :allow_raw_code_escalation] true)
                   (assoc-in [:constraints :max_raw_code_level] detail-level))
        structure-budget (detail-structure-budget detail-budget (raw-escalation-level query*))
        selected-fit (fit-items-to-budget selected-source compact-item-estimate structure-budget)
        selected (vec (:items selected-fit))
        selected-structure-tokens (:used_tokens selected-fit)
        raw-budget (max 0 (- detail-budget selected-structure-tokens))
        stage-query (build-stage "query_validation" "completed" "Structured query accepted." {:target_count (count (:targets_summary summary)) :constraint_count (count (:constraints_summary summary))} [] [] 2)
        stage-candidates (build-stage "candidate_generation" "completed" "Generated retrieval candidates from structural signals." {:candidate_units (count (:selected selection)) :candidate_files (count (distinct (map :path (:selected selection))))} [] [] 7)
        stage-ranking (build-stage "ranking" "completed" "Ranked candidates using structural-first signals." {:ranked_units (count (:selected selection)) :top_authority_units (count (filter #(= "top_authority" (:rank_band %)) selected-source))} [] [] 4)
        impact-full (build-impact-hints index selected)
        impact-tokens (estimate-impact-hints-tokens impact-full)
        include-impact? (and (seq selected)
                             (<= impact-tokens raw-budget))
        impact (if include-impact? impact-full (empty-impact-hints))
        raw-budget* (max 0 (- raw-budget (if include-impact? impact-tokens 0)))
        structure-estimated (estimate-tokens selected-source)
        truncation (cond-> []
                     (:truncated? selected-fit) (conj "selected_units_truncated")
                     (and (seq selected) (not include-impact?)) (conj "impact_hints_omitted")
                     (zero? detail-budget) (conj "detail_budget_exhausted"))
        capabilities (rp/capability-summary index (capability-units selected))
        base-confidence (build-confidence selected query* policy)
        raw-fetch (perform-raw-fetch index selection selected query* raw-budget*)
        raw-fetch-tokens (estimate-raw-context-tokens (:raw_context raw-fetch))
        budget (cond-> {:requested_tokens requested
                        :reserved_tokens detail-budget
                        :estimated_tokens (+ structure-estimated
                                             (if include-impact? impact-tokens 0)
                                             raw-fetch-tokens)
                        :returned_tokens (+ selected-structure-tokens
                                            (if include-impact? impact-tokens 0)
                                            raw-fetch-tokens)
                        :truncation_flags (cond-> truncation
                                            (and (pos? (count selected))
                                                 (= "skipped" (:status raw-fetch))
                                                 (pos? (count (:warnings raw-fetch))))
                                            (into (map :code (:warnings raw-fetch))))}
                 true (assoc :stage_result_status
                             (stage-result-status selected-source selected truncation)))
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
                       (apply-capability-ceiling capabilities policy)
                       (update :reasons #(vec (take 10 %)))
                       (update :warnings #(vec (take 10 %)))
                       (update :missing_evidence #(vec (take 10 %))))
        guardrails (build-guardrails confidence impact query* policy capabilities)
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
                              (= "degraded" (:status raw-fetch))
                              (seq truncation))
                        "degraded"
                        "completed")
        packet-warns (cond-> []
                       (= "low" (:level confidence)) (conj (coded "confidence_low" "Context packet confidence is low."))
                       (= "degraded" (:status raw-fetch)) (conj (coded "raw_fetch_degraded" "Raw-code fetch was executed in degraded mode."))
                       (:truncated? selected-fit) (conj (coded "detail_budget_limited" "Detail packet selected units were truncated to fit the reserved stage budget."))
                       (and (seq selected) (not include-impact?)) (conj (coded "impact_hints_omitted" "Impact hints were omitted to keep detail payload within the reserved stage budget.")))
        stage-packet (build-stage "context_packet_assembly"
                                  packet-status
                                  "Assembled bounded context packet."
                                  {:selected_units (count selected)
                                   :selected_files (count focus-paths)
                                   :reserved_tokens detail-budget
                                   :returned_tokens (+ selected-structure-tokens
                                                       (if include-impact? impact-tokens 0))}
                                  packet-warns
                                  []
                                  5)
        stage-fetch (build-stage "raw_code_fetch"
                                 (:status raw-fetch)
                                 (case (:status raw-fetch)
                                   "skipped" "Late raw-code fetch skipped by query options."
                                   "degraded" "Late raw-code fetch executed with degradation flags."
                                   "completed" "Late raw-code fetch completed for ranked units."
                                   "Late raw-code fetch stage completed.")
                                 {:raw_fetch_requests (:requests raw-fetch)
                                  :raw_fetch_snippets (:snippets raw-fetch)
                                  :raw_fetch_bytes (:bytes raw-fetch)
                                  :reserved_tokens raw-budget*
                                  :returned_tokens raw-fetch-tokens}
                                 (:warnings raw-fetch)
                                 (:degradations raw-fetch)
                                 (if (= "skipped" (:status raw-fetch)) 0 3))
        base-degradations (cond-> []
                            (= "low" (:level confidence)) (conj (coded "confidence_low" "Confidence degraded due to weak or ambiguous evidence."))
                            fallback-selected? (conj (coded "parser_fallback" "Fallback parser evidence contributed to selected units.")))
        diagnostics-degradations (vec (take 10 (concat base-degradations (:degradations raw-fetch))))
        stage-final-status (if (or (= "low" (:level confidence))
                                   (seq diagnostics-degradations)
                                   (seq truncation))
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
                                   :options_summary (->> (:options query*) (keep (fn [[k v]] (when (true? v) (name k)))) vec)
                                   :validation_status "accepted")
                     :stages stages
                     :result {:selected_units_count (count selected)
                              :selected_files_count (count focus-paths)
                              :raw_fetch_level_reached (:level raw-fetch)
                              :packet_size_estimate (:estimated_tokens budget)
                              :top_authority_targets (->> selected (filter #(= "top_authority" (:rank_band %))) (map :unit_id) (take 10) vec)
                              :result_status (if (or (= "low" (:level confidence))
                                                     (= "degraded" (:status raw-fetch))
                                                     (seq truncation))
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
                                   :budget_summary {:requested_tokens requested
                                                    :reserved_tokens detail-budget
                                                    :structure_budget_tokens structure-budget
                                                    :raw_fetch_budget_tokens raw-budget*
                                                    :estimated_tokens (:estimated_tokens budget)
                                                    :returned_tokens (:returned_tokens budget)
                                                    :raw_fetch_returned_tokens raw-fetch-tokens}}}
        events (build-stage-events trace-id request-id (get-in query [:intent :purpose] "unknown") stages {:requested_tokens requested
                                                                                                           :reserved_tokens detail-budget
                                                                                                           :estimated_tokens (:estimated_tokens budget)
                                                                                                           :returned_tokens (:returned_tokens budget)})]
    (when-let [explain (m/explain (:example/context-packet contracts/contracts) context-packet)]
      (throw (ex-info "invalid context packet generated" {:type :internal_contract_error :errors (me/humanize explain)})))
    (when-let [explain (m/explain (:example/diagnostics-trace contracts/contracts) diagnostics)]
      (throw (ex-info "invalid diagnostics trace generated" {:type :internal_contract_error :errors (me/humanize explain)})))
    (projections/with-projection
     {:api_version default-api-version
      :selection_id (:selection_id selection)
      :snapshot_id (:snapshot_id selection)
      :raw_context (:raw_context raw-fetch)
      :context_packet context-packet
      :guardrail_assessment guardrails
      :diagnostics_trace diagnostics
      :stage_events events}
     :detail)))

(defn resolve-context
  ([index query]
   (resolve-context index query {}))
  ([index query opts]
   (validate-query! query)
   (ensure-supported-api-version! query)
   (enforce-query-constraints! index query)
   (let [policy (rp/resolve-policy (:retrieval_policy opts) (:policy_registry opts))
         {:keys [scored]} (collect-candidates index query policy)
         ranked (->> (with-rank-band scored policy)
                     (sort-by (juxt (comp - :score) :path :start_line))
                     vec)
         selected (vec (take 20 ranked))]
     (build-selection-result index query policy selected))))

(defn expand-context
  ([index selector]
   (expand-context index selector {}))
  ([index {:keys [selection_id snapshot_id unit_ids include_impact_hints] :as _selector} _opts]
   (let [selection (ensure-selection! index selection_id snapshot_id)
         bound-index (:bound_index selection)
         selected-source (if (seq unit_ids)
                           (->> (:selected selection)
                                (filter #(contains? (set unit_ids) (:unit_id %)))
                                vec)
                           (:focus selection))
         impact? (if (some? include_impact_hints)
                   (boolean include_impact_hints)
                   true)
         expansion-budget (get-in selection [:budget :reserved_budget :expansion_tokens] 0)
         skeleton-fit (fit-items-to-budget selected-source estimate-skeleton-tokens expansion-budget)
         selected (vec (:items skeleton-fit))
         impact-full (when impact? (build-impact-hints bound-index selected))
         impact-tokens (estimate-impact-hints-tokens impact-full)
         remaining-budget (max 0 (- expansion-budget (:used_tokens skeleton-fit)))
         include-impact? (and impact? (<= impact-tokens remaining-budget))
         impact (when include-impact? impact-full)
         truncation-flags (cond-> []
                            (:truncated? skeleton-fit) (conj "skeletons_truncated")
                            (and impact? (seq impact-full) (not include-impact?)) (conj "impact_hints_omitted")
                            (and (seq selected-source) (zero? expansion-budget)) (conj "expansion_budget_exhausted"))
         estimated (+ (reduce + 0 (map estimate-skeleton-tokens selected-source))
                      (if impact? impact-tokens 0))
         returned (+ (:used_tokens skeleton-fit)
                     (if include-impact? impact-tokens 0))
         result-status (stage-result-status selected-source selected truncation-flags)]
     (projections/with-projection
      {:api_version default-api-version
       :selection_id selection_id
       :snapshot_id snapshot_id
       :result_status result-status
       :budget_summary {:reserved_tokens expansion-budget
                        :estimated_tokens estimated
                        :returned_tokens returned
                        :within_budget (<= estimated expansion-budget)
                        :truncation_flags truncation-flags}
       :skeletons (mapv compact-skeleton selected)
       :impact_hints impact}
      :api-shape
      :detail))))

(defn fetch-context-detail
  ([index selector]
   (fetch-context-detail index selector {}))
  ([index {:keys [selection_id snapshot_id detail_level unit_ids] :as selector} _opts]
   (let [selection (ensure-selection! index selection_id snapshot_id)
         bound-index (:bound_index selection)
         cache-key (detail-cache-key {:unit_ids unit_ids
                                      :detail_level (or detail_level
                                                        (get-in selection [:query :constraints :max_raw_code_level])
                                                        "enclosing_unit")})]
     (or (cached-detail-result index selection_id cache-key)
         (->> (build-detail-response bound-index selection selector)
              (cache-detail-result! index selection_id cache-key))))))

(defn impact-analysis
  ([index query]
   (impact-analysis index query {}))
  ([index query opts]
   (let [selection (resolve-context index query opts)]
     (:impact_hints (expand-context index {:selection_id (:selection_id selection)
                                           :snapshot_id (:snapshot_id selection)
                                           :include_impact_hints true})))))

(defn skeletons [index {:keys [unit_ids paths]}]
  (let [units (cond
                (seq unit_ids) (idx/units-by-ids index unit_ids)
                (seq paths) (->> paths (mapcat #(idx/units-for-path index %)) distinct vec)
                :else (->> (idx/all-units index) (take 20) vec))]
    (with-meta (mapv compact-skeleton units)
      (projections/projection-meta :api-shape :detail))))
