(ns semidx.runtime.provider-authority
  "Stage 6.1 of the Semantic Provider Authority Migration (plans/018, ADR-046):
  the provider plan as the default extraction path for Java and TypeScript.

  Every earlier stage produced facts beside the snapshot. This one merges them
  into it, and the merge is deliberately asymmetric, because the two vocabularies
  do not carry the same things:

  - a parsed unit carries what only a parser has — module, imports, calls,
    signature, docstring, the spans relations are built from;
  - a fact carries identity and evidence — which tier saw this symbol, at what
    authority, anchored to which content digest.

  So the parse stays the source of unit shape, and the arbitrated facts decide
  what that shape is worth: a unit whose fact carries exact evidence is marked
  exact, and a fact with no parsed counterpart becomes a unit, which is where a
  semantic provider adds what the lexical tier never saw. Nothing is dropped
  because a provider disagreed; that decision (owner, 2026-09-06) is recorded in
  the plan as annotate-not-block.

  What this namespace does not do: change any language other than Java and
  TypeScript, decide confidence, relabel degraded parses — that is Stage 6.2 —
  or run the project tier per file. The project tier runs once per build in
  `build-context`, exactly as it does in shadow mode."
  (:require [clojure.string :as str]
            [semidx.runtime.adapters :as adapters]
            [semidx.runtime.fact-arbitration :as fact-arbitration]
            [semidx.runtime.provider-batch :as provider-batch]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.provider-selection :as provider-selection]
            [semidx.runtime.providers :as providers]))

(def authority-languages
  "The languages whose default path the provider plan owns. Every other language
  keeps its single-parser path untouched, which is the scope boundary Stage 6
  committed to."
  #{"java" "typescript"})

(def authority-policy-version
  "Version of the rules that turn provider evidence into a snapshot: which tiers
  are admitted, how a fact upgrades a unit, and which authority earns which
  `parser_mode`.

  Bump it whenever those rules change in a way that would make two snapshots of
  the same files disagree. It travels in the workspace fingerprint, so a bump
  invalidates prior snapshots rather than letting one built under the old rules
  be reused under the new ones."
  "1")

(defn authority-model
  "The identity of the authority model a build runs under, or nil when it runs
  no provider pipeline at all (plans/018 Stage 6.3).

  Nil for `:off` on purpose: that mode produces exactly the pre-Stage-6 snapshot,
  so its fingerprint must stay exactly the pre-Stage-6 fingerprint and every
  snapshot taken before this stage must stay reusable. `:shadow` and `:authority`
  each get their own model — shadow adds a provider summary to the snapshot and
  authority changes the units themselves, and neither should be served from a
  snapshot built under the other.

  Provider versions come from the catalog rather than from a plan, because the
  question this answers is whether two builds *could* have produced the same
  snapshot, and a provider that was absent on one machine and present on another
  is exactly the case a plan would hide."
  [mode]
  (when (contains? #{:shadow :authority} mode)
    {:mode (name mode)
     :policy_version authority-policy-version
     :catalog_version providers/catalog-version
     :languages (vec (sort authority-languages))
     :provider_versions (into (sorted-map)
                              (map (juxt :provider_id :provider_version))
                              (concat providers/descriptors
                                      providers/project-descriptors))}))

(defn- authority-paths [paths]
  (filterv #(contains? authority-languages (adapters/language-by-path %)) paths))

(defn- languages-for [paths]
  (vec (distinct (keep adapters/language-by-path (authority-paths paths)))))

(defn build-context
  "Run the project tier once for this build and return what per-file work needs.

  Project providers index a repository in one run; running them per file would
  reindex the project once per document. This mirrors what shadow mode already
  does, and returns nil when no path in the build belongs to an authority
  language, so a build with no Java or TypeScript pays nothing.

  The project run is timed here because it is the only part of an authority
  build whose cost is separable: per-file provider work is interleaved with
  parsing and cannot be honestly attributed by a wall clock around the whole
  build."
  [root-path paths parser-opts]
  (let [languages (languages-for paths)]
    (when (seq languages)
      (let [provider-opts {:root_path root-path}
            statuses (provider-batch/project-statuses languages provider-opts)
            plan (provider-selection/project-plan {:root_path root-path
                                                   :languages languages
                                                   :mode "default"
                                                   :statuses statuses})
            started (System/nanoTime)
            execution (provider-batch/execute-project-plan plan provider-opts)
            elapsed-ms (Math/round (/ (double (- (System/nanoTime) started)) 1e6))]
        {:languages languages
         :statuses statuses
         :project_plan plan
         :project_execution execution
         :project_elapsed_ms elapsed-ms
         :coverage (provider-batch/batch-coverage execution)
         :parser_opts parser-opts}))))

(defn- parsed-tier
  "Which catalog tier the parse in hand actually came from.

  Read off the result rather than off the request: `parse-file` falls back to the
  lexical parser when tree-sitter is unavailable or fails, and a tier read from
  the request would then label lexical units structural — the same laundering
  `providers/refuse-silent-fallback!` exists to prevent."
  [language parsed]
  (let [structural? (and (nil? (providers/tree-sitter-fallback-diagnostic parsed))
                         (some #(= "tree_sitter_active" (str (:code %)))
                               (:diagnostics parsed)))]
    (case language
      "java" (if structural? "java-tree-sitter" "java-regex")
      "typescript" (if structural? "typescript-tree-sitter" "typescript-regex")
      nil)))

(defn- evidence-context [language tier root-path path]
  (let [descriptor (providers/descriptor tier)]
    {:provider_id tier
     :provider_version (:provider_version descriptor)
     :authority (get-in descriptor [:operation_capabilities :definitions])
     :language language
     :source_identity (providers/source-identity {:root_path root-path :path path})}))

(defn- unit-key-id [evidence-ctx unit]
  (fact-arbitration/canonical-fact-key-id (:key (providers/unit->fact evidence-ctx unit))))

(defn- evidence-providers [fact]
  (vec (distinct (map :provider_id (:evidence fact)))))

(defn- unit-fact?
  "Whether an arbitrated fact describes a unit. Reads `:core_key`, which is what
  arbitration produces; the pre-arbitration `:key` does not survive the merge."
  [fact]
  (= "unit" (get-in fact [:core_key :fact_kind])))

(defn- unit-from-fact
  "A snapshot unit for a fact no parsed unit matched.

  This is the whole point of the switch: a symbol the semantic tier resolved and
  the lexical tier missed becomes a real unit instead of a number in a shadow
  report.

  An arbitrated fact carries `:core_key` and evidence but no `:value` —
  arbitration merges evidence and drops the values, which is why the conflict
  check compares them before they go. The value comes from `values-by-key`,
  built from the pre-arbitration batches the same call already returns. The unit
  carries no calls and no imports of its own, because a fact does not know them,
  so it takes the file's imports and leaves relations to the parse."
  [language file-imports values-by-key fact]
  (let [key* (:core_key fact)
        key-id (:canonical_fact_key_id fact)
        value (get values-by-key key-id)
        location (some :evidence_location (:evidence fact))
        symbol* (:symbol key*)
        path (:path key*)
        start (or (:start_line location) 1)]
    {:unit_id (or (:native_unit_id value) (str path "::" symbol*))
     :kind (or (:kind value) "function")
     :symbol symbol*
     :path path
     :language language
     :module (:owner key*)
     :start_line start
     :end_line (or (:end_line location) start)
     ;; Never empty: the context packet contract requires at least one
     ;; character, and a provider-supplied unit often has no value to recover a
     ;; signature from — a SCIP class symbol the lexical tier never produced is
     ;; the common case. The symbol is the truthful minimum; inventing source
     ;; text the provider never gave us would be worse than saying the name.
     :signature (let [signature (:signature value)]
                  (if (and signature (seq (str signature)))
                    signature
                    symbol*))
     :summary (str "provider unit " symbol*)
     :docstring_excerpt nil
     :imports (vec file-imports)
     :calls []
     :method_arity (:arity key*)
     :parser_mode "full"
     :authority (:authority fact)
     :evidence_providers (evidence-providers fact)
     :canonical_fact_key_id key-id
     ;; Marks a unit no parser produced. The build summary counts these, and a
     ;; reader looking at a unit with no calls and no docstring deserves to know
     ;; it came from a provider rather than from a parse that lost them.
     :provider_supplied true}))

(defn- conflicted-key-ids [diagnostics]
  (into #{}
        (keep (fn [d]
                (when (= "equal_authority_value_conflict" (str (name (or (:code d) ""))))
                  (:canonical_fact_key_id d))))
        diagnostics))

(defn- values-by-key
  "`canonical_fact_key_id -> value`, recovered from the pre-arbitration batches.

  Arbitration keeps every evidence and drops every `:value`, so a unit built
  from an arbitrated fact alone would have no kind and no signature. The batches
  that produced those facts are returned by the same call and still carry them."
  [raw-batches]
  (reduce (fn [acc fact]
            (let [id (fact-arbitration/canonical-fact-key-id (:key fact))]
              (if (or (contains? acc id) (nil? (:value fact)))
                acc
                (assoc acc id (:value fact)))))
          {}
          (mapcat :facts raw-batches)))

(def strong-authorities
  "Authorities that justify the `full` parser mode: a semantic provider resolved
  the symbol, or a structural parser saw its syntax. Heuristic evidence — a
  regular expression over source text — does not, which is the whole content of
  ADR-046's tiering and of the owner's Stage 6 decision to label degradation
  unconditionally."
  #{"exact" "structural"})

(defn- degradation-reasons
  "Why no strong tier reached this file, in the planner's own words.

  A degradation that does not say what was missing is a complaint, not a
  diagnostic: the reader needs to know whether a toolchain is absent, a status
  was never observed, or a provider was denied."
  [plan]
  (->> (vals (:operations plan))
       (mapcat :excluded)
       (map (fn [entry]
              (str (:provider_id entry) ": " (:reason entry))))
       distinct
       sort
       vec))

(defn- announce-degradation
  "Stage 6.2, corrected by bugs/005. Say that a file's evidence is heuristic,
  without claiming its parser failed.

  The first version of this wrote `parser_mode \"fallback\"` onto every
  heuristic unit. That field already had an owner: it means the parser could not
  extract structure, and the whole system reads it that way —
  `retrieval-policy/coverage-level` counts fallback units, the ceiling collapses
  to `low`, and `retrieval/impact-seed-degradations` then treats the selection as
  degraded, so impact analysis returns a stub and the state-invariant packet is
  never assembled. A successful regex parse that produced methods, fields, calls
  and relations is not a failed parse, and labelling it as one switched off
  working features for every Java workspace without a semantic toolchain.

  The evidence tier lives on `:authority`, which every merged unit carries, and
  the fact that no strong tier reached this file is stated once, on the file, as
  a diagnostic. Both are additive: nothing that read `parser_mode` before reads
  anything different now."
  [parsed plan]
  (let [units (:units parsed)
        heuristic-only? (and (seq units)
                             (every? #(not (contains? strong-authorities (:authority %))) units))
        reasons (when heuristic-only? (degradation-reasons plan))]
    (cond-> parsed
      heuristic-only?
      (update :diagnostics conj
              {:code "provider_authority_degraded"
               :summary (str "no exact or structural evidence reached this file, so every"
                             " unit rests on heuristic evidence"
                             (when (seq reasons)
                               (str "; excluded: " (str/join ", " reasons))))}))))

(defn merge-facts
  "Merge arbitrated facts into one parsed file.

  Returns the parsed map with units upgraded and extended, relabelled by the
  evidence they actually carry, and with the arbitration diagnostics carried
  over so a conflict is visible in the snapshot rather than only in a shadow
  report."
  [parsed {:keys [facts diagnostics raw_batches plan]} language evidence-ctx]
  (let [unit-facts (filterv unit-fact? facts)
        by-key (into {} (map (juxt :canonical_fact_key_id identity)) unit-facts)
        values (values-by-key raw_batches)
        conflicts (conflicted-key-ids diagnostics)
        matched (volatile! #{})
        units (mapv (fn [unit]
                      (let [key-id (unit-key-id evidence-ctx unit)
                            fact (get by-key key-id)]
                        (if-not fact
                          unit
                          (do (vswap! matched conj key-id)
                              (cond-> (assoc unit
                                             :authority (:authority fact)
                                             :evidence_providers (evidence-providers fact)
                                             :canonical_fact_key_id key-id)
                                (contains? conflicts key-id)
                                (assoc :evidence_conflict true))))))
                    (:units parsed))
        added (->> unit-facts
                   (remove #(contains? @matched (:canonical_fact_key_id %)))
                   (mapv #(unit-from-fact language (:imports parsed) values %)))
        carried (mapv (fn [d]
                        {:code (str (name (or (:code d) "provider_diagnostic")))
                         :summary (or (:message d) (:summary d) "")})
                      diagnostics)]
    (-> (cond-> (assoc parsed :units (into units added))
          (seq carried) (update :diagnostics into carried)
          (seq added) (update :diagnostics conj
                              {:code "provider_authority_units_added"
                               :summary (str (count added)
                                             " unit(s) supplied by a semantic provider that the"
                                             " file parser did not produce")}))
        (announce-degradation plan))))

(defn parse-file
  "Default extraction for one file, with the provider plan authoritative for
  Java and TypeScript.

  Falls straight through to `adapters/parse-file` for every other language, and
  for every language when `ctx` is nil — which is what an `:off` or `:shadow`
  build passes, so the default path is bit-for-bit what it was."
  [root-path path parser-opts ctx]
  (let [parsed (adapters/parse-file root-path path parser-opts)
        language (:language parsed)]
    (if-not (and ctx (contains? authority-languages language))
      parsed
      (let [tier (parsed-tier language parsed)
            evidence-ctx (evidence-context language tier root-path path)
            legacy-facts (mapv #(providers/unit->fact evidence-ctx %) (:units parsed))
            ;; The tier that produced `parsed` answers from those units instead
            ;; of parsing the file a second time; the other file tier answers
            ;; nothing, because running it would mean parsing the same file with
            ;; the engine this build did not choose.
            runner (provider-batch/batch-run-provider
                    (:project_execution ctx)
                    (fn [provider-id {:keys [operation]}]
                      (if (and (= provider-id tier)
                               (= "definitions" (name (or operation ""))))
                        {:facts legacy-facts :diagnostics [] :parser_mode (:parser_mode parsed)}
                        {:facts [] :diagnostics [] :parser_mode nil})))
            arbitrated (provider-execution/facts-for-file
                        {:root_path root-path
                         :path path
                         :mode "default"
                         :parser_opts parser-opts
                         :batch_coverage (:coverage ctx)
                         :observed_statuses (:statuses ctx)
                         :run-provider runner})]
        (merge-facts parsed arbitrated language evidence-ctx)))))

(defn build-summary
  "The provider summary for an authority build (plans/018 Stage 6.4).

  Shadow mode already reported what the pipeline would have done; an authority
  build reported nothing at all, so switching the pipeline on cost the operator
  the observation. This produces the same key from what the build actually
  produced rather than by running the pipeline a second time — which is what
  reusing the shadow path here would have meant, at double the cost.

  It is not the shadow summary with a different `:mode`, and two fields make the
  difference explicit:

  - `:comparison` is absent. Shadow compares two tiers, neither of which is the
    snapshot. Here one of them *is* the snapshot, so the same key would name a
    different thing.
  - `:units_supplied` counts the units no parser produced, which only an
    authority build can have.

  **No timing.** This summary rides in the snapshot, and a snapshot must be
  deterministic for the same content, provider versions and configuration
  (ADR-046). A latency field would make two identical builds differ and would
  surface in `snapshot-diff` as a change where nothing changed. The project
  tier's duration is measured in `build-context` and stays available to a caller
  that wants it; the whole build's latency is already on the create_index usage
  event."
  [ctx files-data]
  (let [units (vec (:units files-data))
        authority-units (filterv #(contains? authority-languages (:language %)) units)
        files (vals (:files files-data))
        authority-files (filterv #(contains? authority-languages (:language %)) files)
        ;; Read off the diagnostic, not off `parser_mode`. bugs/005 took that
        ;; field back, so counting `fallback` units here would report zero for
        ;; every build and quietly retire the metric.
        degraded (filterv (fn [file]
                            (some #(= "provider_authority_degraded" (str (:code %)))
                                  (:diagnostics file)))
                          authority-files)
        execution (:project_execution ctx)]
    {:mode "authority"
     :languages (vec (:languages ctx))
     :files_observed (count authority-files)
     :files_degraded (count degraded)
     :units_observed (count authority-units)
     :units_supplied (count (filterv :provider_supplied authority-units))
     :units_conflicted (count (filterv :evidence_conflict authority-units))
     :authorities (into (sorted-map) (frequencies (keep :authority authority-units)))
     :providers (into (sorted-map)
                      (map (fn [[provider-id state]]
                             [provider-id (-> state
                                              (update :fresh count)
                                              (update :stale count)
                                              (update :invalid count)
                                              (update :uncovered count))]))
                      (provider-batch/document-states execution (mapv :path authority-files)))
     :diagnostic_codes (->> authority-files
                            (mapcat :diagnostics)
                            (map (comp str :code))
                            frequencies
                            (into (sorted-map)))}))
