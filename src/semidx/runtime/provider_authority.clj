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
  (:require [semidx.runtime.adapters :as adapters]
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

(defn- authority-paths [paths]
  (filterv #(contains? authority-languages (adapters/language-by-path %)) paths))

(defn- languages-for [paths]
  (vec (distinct (keep adapters/language-by-path (authority-paths paths)))))

(defn build-context
  "Run the project tier once for this build and return what per-file work needs.

  Project providers index a repository in one run; running them per file would
  reindex the project once per document. This mirrors what shadow mode already
  does, and returns nil when no path in the build belongs to an authority
  language, so a build with no Java or TypeScript pays nothing."
  [root-path paths parser-opts]
  (let [languages (languages-for paths)]
    (when (seq languages)
      (let [provider-opts {:root_path root-path}
            statuses (provider-batch/project-statuses languages provider-opts)
            plan (provider-selection/project-plan {:root_path root-path
                                                   :languages languages
                                                   :mode "default"
                                                   :statuses statuses})
            execution (provider-batch/execute-project-plan plan provider-opts)]
        {:languages languages
         :statuses statuses
         :project_plan plan
         :project_execution execution
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
     :signature (or (:signature value) "")
     :summary (str "provider unit " symbol*)
     :docstring_excerpt nil
     :imports (vec file-imports)
     :calls []
     :method_arity (:arity key*)
     :parser_mode "full"
     :authority (:authority fact)
     :evidence_providers (evidence-providers fact)
     :canonical_fact_key_id key-id}))

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

(defn merge-facts
  "Merge arbitrated facts into one parsed file.

  Returns the parsed map with units upgraded and extended, and with the
  arbitration diagnostics carried over so a conflict is visible in the snapshot
  rather than only in a shadow report."
  [parsed {:keys [facts diagnostics raw_batches]} language evidence-ctx]
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
    (cond-> (assoc parsed :units (into units added))
      (seq carried) (update :diagnostics into carried)
      (seq added) (update :diagnostics conj
                          {:code "provider_authority_units_added"
                           :summary (str (count added)
                                         " unit(s) supplied by a semantic provider that the"
                                         " file parser did not produce")}))))

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
            arbitrated (provider-execution/shadow-facts-for-file
                        {:root_path root-path
                         :path path
                         :mode "default"
                         :parser_opts parser-opts
                         :batch_coverage (:coverage ctx)
                         :observed_statuses (:statuses ctx)
                         :run-provider runner})]
        (merge-facts parsed arbitrated language evidence-ctx)))))
