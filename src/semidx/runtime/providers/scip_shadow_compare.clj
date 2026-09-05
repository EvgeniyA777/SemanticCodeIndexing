(ns semidx.runtime.providers.scip-shadow-compare
  "Stage 3 of the Semantic Provider Authority Migration (plans/018, ADR-046):
  shadow comparison of the TypeScript SCIP provider against the Stage 2
  tree-sitter / regex shadow facts, plus latency and fact-set size measurement
  for a SCIP run.

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

  On a host without a tree-sitter grammar the legacy tier is regex only; the
  comparison is identical in shape."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [semidx.runtime.fact-arbitration :as fact-arbitration]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.providers.scip-typescript :as scip-typescript]))

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
                  #(scip-typescript/shadow-facts-for-project
                    (merge (select-keys opts [:scip_typescript_cli_path
                                              :scip_toolchain_dir
                                              :expected_document_digests])
                           {:root_path root_path})))
        scip (:value scip-run)]
    (-> (compare-scip-run scip opts)
        (assoc :cli (:cli scip)
               :latency {:scip_run_ms (:elapsed_ms scip-run)}))))
