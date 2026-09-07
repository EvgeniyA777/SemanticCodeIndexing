(ns semidx.runtime.providers.scip-shadow-compare
  "Shadow comparison of the SCIP exact tier against the Stage 2 tree-sitter /
  regex tier, plus latency and fact-set size measurement.

  Read-only. Runs both shadow producers over a corpus, never writes a snapshot,
  and changes no default extraction. Its purpose is to record — before the
  Stage 6 authority switch — that:

  - a symbol both tiers find lands on the *same* `canonical_fact_key_id`, so
    SCIP exact evidence merges onto the existing unit identity instead of
    minting a duplicate (Stage 3 exit criterion);
  - what SCIP adds (exact-only symbols) and what it does not model
    (legacy-only symbols) is explicit and expected, not a silent regression;
  - the per-fact evidence expansion and SCIP run latency are measured
    (plans/018 [Medium] snapshot-size and latency risks).

  Two entry points. `shadow-report` is the Stage 3 TypeScript harness: it drives
  the TypeScript adapter directly. `project-shadow-report` is the Stage 4.5
  form: it runs the project seam in `semidx.runtime.provider-batch` for every
  admitted project provider, then splits the two tiers back out of the per-file
  runs that merged them. The second is language-neutral and is the shape the
  Stage 6 admission evidence is recorded in.

  On a host without a tree-sitter grammar the legacy tier is regex only; the
  comparison is identical in shape."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [semidx.runtime.fact-arbitration :as fact-arbitration]
            [semidx.runtime.provider-batch :as provider-batch]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.providers :as providers]))

(defn measure
  "Run `thunk` and return `{:value <result> :elapsed_ms <double>}`."
  [thunk]
  (let [start (System/nanoTime)
        value (thunk)]
    {:value value
     :elapsed_ms (/ (double (- (System/nanoTime) start)) 1e6)}))

(defn discover-ts-paths
  "Root-relative `.ts` / `.tsx` paths under `root`, sorted. A convenience for
  callers; the report takes an explicit `:ts_paths` list."
  [root]
  (let [prefix (str (.getPath (io/file (str root))) "/")]
    (->> (file-seq (io/file (str root)))
         (filter #(.isFile ^java.io.File %))
         (map #(.getPath ^java.io.File %))
         (keep (fn [p]
                 (when (and (str/starts-with? p prefix)
                            (re-find #"\.tsx?$" p))
                   (subs p (count prefix)))))
         sort
         vec)))

(defn- fact-symbol [fact] (get-in fact [:core_key :symbol]))

(defn- facts-by-key [arbitrated-facts]
  (into {} (map (juxt :canonical_fact_key_id identity)) arbitrated-facts))

(defn compare-fact-sets
  "Diff an exact (SCIP) arbitrated fact set against a legacy (tree-sitter/regex)
  one, keyed on `canonical_fact_key_id`.

  - `:agreed` — symbols both tiers produced under the same key;
  - `:exact_only` / `:legacy_only` — symbols only one tier produced;
  - `:authority_upgrade` — agreed symbols where SCIP raises the authority the
    legacy tier assigned."
  [exact-facts legacy-facts]
  (let [ex (facts-by-key exact-facts)
        lg (facts-by-key legacy-facts)
        ex-ids (set (keys ex))
        lg-ids (set (keys lg))
        shared (set/intersection ex-ids lg-ids)]
    {:agreed (->> shared (map #(fact-symbol (ex %))) sort vec)
     :exact_only (->> (set/difference ex-ids lg-ids) (map #(fact-symbol (ex %))) sort vec)
     :legacy_only (->> (set/difference lg-ids ex-ids) (map #(fact-symbol (lg %))) sort vec)
     :authority_upgrade (->> shared
                             (keep (fn [k]
                                     (let [e (:authority (ex k))
                                           l (:authority (lg k))]
                                       (when (not= e l)
                                         {:symbol (fact-symbol (ex k))
                                          :legacy l
                                          :exact e}))))
                             (sort-by :symbol)
                             vec)}))

(defn co-arbitrate
  "Feed the raw (pre-arbitration) SCIP and legacy facts through one
  `arbitrate-facts` pass. A symbol both tiers found must collapse to one
  canonical fact that retains both providers' evidence — the direct proof that
  SCIP does not create a duplicate semantic identity."
  [exact-raw-facts legacy-raw-facts]
  (let [{:keys [facts diagnostics]}
        (fact-arbitration/arbitrate-facts (concat exact-raw-facts legacy-raw-facts))
        provider-ids (fn [f] (set (map :provider_id (:evidence f))))]
    {:canonical_fact_count (count facts)
     :diagnostic_count (count diagnostics)
     :multi_provider_symbols (->> facts
                                  (filter #(< 1 (count (provider-ids %))))
                                  (map (fn [f]
                                         {:symbol (fact-symbol f)
                                          :authority (:authority f)
                                          :providers (vec (sort (provider-ids f)))}))
                                  (sort-by :symbol)
                                  vec)}))

(defn- fact-set-size [facts]
  {:fact_count (count facts)
   :evidence_count (reduce + 0 (map (comp count :evidence) facts))
   :serialized_bytes (count (pr-str facts))})

(defn compare-scip-run
  "Compare an already-computed SCIP result against the Stage 2 seam.

  `scip` is a `scip-typescript/shadow-facts-for-project` or `facts-from-index`
  result (it must carry `:result`, `:facts`, `:raw_facts`, `:coverage`,
  `:unmapped`). The Stage 2 seam is run here over `:ts_paths` under
  `:root_path`. When `scip` is not `ready` the comparison is skipped."
  [scip {:keys [root_path ts_paths parser_opts]}]
  (if (not= "ready" (:result scip))
    {:scip_result (:result scip)
     :scip_reason_codes (:reason_codes scip)
     :scip_diagnostics (:diagnostics scip)
     :comparison :skipped_scip_not_ready}
    (let [legacy-runs (mapv (fn [p]
                              (provider-execution/shadow-facts-for-file
                               {:root_path root_path :path p :parser_opts parser_opts}))
                            ts_paths)
          legacy-facts (vec (mapcat :facts legacy-runs))
          legacy-raw (vec (mapcat #(mapcat :facts (:raw_batches %)) legacy-runs))
          scip-facts (:facts scip)
          scip-raw (:raw_facts scip)]
      {:scip_result "ready"
       :corpus {:root root_path :ts_paths (vec ts_paths)}
       :comparison (compare-fact-sets scip-facts legacy-facts)
       :co_arbitration (co-arbitrate scip-raw legacy-raw)
       :scip_coverage (:coverage scip)
       :scip_unmapped_by_reason (->> (:unmapped scip)
                                     (map :reason)
                                     frequencies
                                     (into (sorted-map)))
       :size {:scip (fact-set-size scip-facts)
              :legacy (fact-set-size legacy-facts)}})))

(defn shadow-report
  "Run the SCIP provider and the Stage 2 seam over `:ts_paths` under
  `:root_path`, then compare and measure.

  CLI-resolution keys (`:scip_typescript_cli_path`, `:scip_toolchain_dir`,
  `:expected_document_digests`) are forwarded to
  `scip-typescript/shadow-facts-for-project`. When SCIP is not `ready` the
  report carries the reason and skips the comparison."
  [{:keys [root_path] :as opts}]
  (let [scip-run (measure
                  #((requiring-resolve 'semidx.runtime.providers.scip-typescript/shadow-facts-for-project)
                    (merge (select-keys opts [:scip_typescript_cli_path
                                              :scip_toolchain_dir
                                              :expected_document_digests])
                           {:root_path root_path})))
        scip (:value scip-run)]
    (-> (compare-scip-run scip opts)
        (assoc :cli (:cli scip)
               :latency {:scip_run_ms (:elapsed_ms scip-run)}))))

;; ---------------------------------------------------------------------------
;; Stage 4.5: project-level comparison over the provider-batch seam
;; ---------------------------------------------------------------------------

(defn discover-paths
  "Root-relative paths under `root` that some project provider selects, sorted.

  Language-neutral by construction: eligibility comes from the catalog's
  selectors, so a third project provider needs no change here."
  ([root] (discover-paths root nil))
  ([root languages]
   (let [descriptors (providers/descriptors-for-project languages)
         prefix (str (.getPath (io/file (str root))) "/")]
     (->> (file-seq (io/file (str root)))
          (filter #(.isFile ^java.io.File %))
          (map #(.getPath ^java.io.File %))
          (keep (fn [p]
                  (when (str/starts-with? p prefix)
                    (let [relative (subs p (count prefix))]
                      (when (some #(providers/selects-path? % relative) descriptors)
                        relative)))))
          sort
          vec))))

(defn- batches-by-scope
  "Raw provider batches from a project run, split by the scope of the provider
  that produced them: `:project` is the exact tier, `:file` the structural and
  heuristic ones. Scope comes from the catalog, so the split needs no provider
  id list to maintain."
  [files]
  (->> (mapcat :raw_batches files)
       (group-by (fn [batch]
                   (or (:scope (providers/descriptor (:provider_id batch))) :file)))))

(defn project-report
  "Standard project-level shadow comparison for a
  `semidx.runtime.provider-batch/shadow-facts-for-project` result.

  Stage 4.5 turns what Stage 3 ran as a one-off harness into ordinary
  diagnostic output. The two tiers are separated back out of the per-file runs
  that merged them, diffed on `canonical_fact_key_id`, and co-arbitrated in one
  pass. Document states and coverage travel with the diff, because a comparison
  is only readable next to how much of the project the exact tier actually
  covered: a small `:exact_only` list means something different when half the
  documents are stale."
  [batch-result]
  (let [by-scope (batches-by-scope (:files batch-result))
        exact-batches (vec (get by-scope :project))
        legacy-batches (vec (get by-scope :file))
        exact-facts (:facts (fact-arbitration/arbitrate-batches exact-batches))
        legacy-facts (:facts (fact-arbitration/arbitrate-batches legacy-batches))
        exact-raw (vec (mapcat :facts exact-batches))
        legacy-raw (vec (mapcat :facts legacy-batches))]
    {:root_path (:root_path batch-result)
     :languages (:languages batch-result)
     :providers (into (sorted-map)
                      (map (fn [[provider-id result]]
                             [provider-id (select-keys result [:result :reason_codes :coverage])]))
                      (get-in batch-result [:project_execution :results]))
     :batch_coverage (:batch_coverage batch-result)
     :documents (:documents batch-result)
     :comparison (compare-fact-sets exact-facts legacy-facts)
     :co_arbitration (co-arbitrate exact-raw legacy-raw)
     :size {:exact (fact-set-size exact-facts)
            :legacy (fact-set-size legacy-facts)}
     :diagnostics_by_code (->> (:diagnostics batch-result)
                               (map #(str (:code %)))
                               frequencies
                               (into (sorted-map)))}))

(defn project-shadow-report
  "Run the Stage 4.5 project seam and report the comparison plus its latency.

  Options are `provider-batch/shadow-facts-for-project` options. `:paths`
  defaults to every path a project provider selects under `:root_path`. When no
  project provider is admitted the report still renders: the comparison is
  simply the legacy tier against an empty exact tier, which is what a workspace
  without a toolchain should look like."
  [{:keys [root_path paths languages] :as opts}]
  (let [paths (vec (or (seq paths) (discover-paths root_path languages)))
        run (measure #(provider-batch/shadow-facts-for-project (assoc opts :paths paths)))]
    (assoc (project-report (:value run))
           :latency {:project_run_ms (:elapsed_ms run)})))
