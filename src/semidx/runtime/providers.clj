(ns semidx.runtime.providers
  "Stage 2 of the Semantic Provider Authority Migration (plans/018, ADR-046).

  Data-first provider catalog: versioned descriptors, runtime status probes, and
  the role functions that turn one provider's parse of one file into
  `FactEvidence`-bearing facts for `semidx.runtime.fact-arbitration`.

  The catalog knows provider identity, selectors, and static capability claims.
  It does not know provider precedence (that is the planning policy), repository
  traversal, retrieval ranking, storage, or transport shapes.

  Descriptors are plain serializable data; executable roles live in a separate
  map keyed by `provider_id`, so the catalog can be inspected, diffed, and
  persisted without carrying functions.

  Stage 2 wraps providers already in the repository: the tree-sitter and regex
  engines behind the existing language parsers. Their capability claims are
  bounded by ADR-046 — regex is heuristic and can never be exact, tree-sitter is
  structural — and nothing here changes default extraction."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.languages.java :as java-language]
            [semidx.runtime.languages.shared :as shared]
            [semidx.runtime.languages.typescript :as ts-language]
            [semidx.runtime.workspace-state :as workspace-state])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def catalog-version "1")

(def locally-probed-engines
  "Engines this catalog can observe by itself.

  Everything else — a project batch indexer, a language server — is probed by
  the boundary that owns its lifecycle, and its status reaches planning through
  `provider-selection/provider-plan`'s `:observed_statuses`. Keeping the set
  explicit is what stops an unprobed provider from defaulting to `ready`."
  #{:tree-sitter :regex})

(def descriptors
  "Versioned file-scoped provider descriptors.

  `operation_capabilities` are claims bounded by runtime status and freshness,
  not unconditional confidence grants.

  Stage 2 claims `definitions` only. These adapters emit unit facts from the
  existing parsers, so claiming `document_symbols` or `call_hierarchy` would
  make every run report a permanent gap for an operation nothing produces. Those
  claims belong to the providers that actually implement them.

  Stage 4.5 made `:scope` explicit. Every descriptor here is `:file`: one run
  parses one file, and `run-provider` executes it. Project-scoped providers live
  in `project-descriptors` and are executed by `semidx.runtime.provider-batch`."
  [{:provider_id "java-tree-sitter"
    :provider_version "1"
    :languages ["java"]
    :classification "structural"
    :engine :tree-sitter
    :scope :file
    :selectors {:extensions [".java"]}
    :operation_capabilities {:definitions "structural"}}
   {:provider_id "java-regex"
    :provider_version "1"
    :languages ["java"]
    :classification "lexical"
    :engine :regex
    :scope :file
    :selectors {:extensions [".java"]}
    :operation_capabilities {:definitions "heuristic"}}
   {:provider_id "typescript-tree-sitter"
    :provider_version "1"
    :languages ["typescript"]
    :classification "structural"
    :engine :tree-sitter
    :scope :file
    :selectors {:extensions [".ts" ".tsx"]}
    :operation_capabilities {:definitions "structural"}}
   {:provider_id "typescript-regex"
    :provider_version "1"
    :languages ["typescript"]
    :classification "lexical"
    :engine :regex
    :scope :file
    :selectors {:extensions [".ts" ".tsx"]}
    :operation_capabilities {:definitions "heuristic"}}
   ;; Stage 5a. File-scoped like the parsers above — a language server answers
   ;; about one document — but neither probed nor executed by this catalog:
   ;; `semidx.runtime.provider-overlay` owns the session lifecycle and supplies
   ;; the facts through the injected `run-provider` role. `:live_overlay` marks
   ;; the tier whose evidence may describe buffer content that differs from the
   ;; file on disk.
   {:provider_id "typescript-lsp"
    :provider_version "1"
    :languages ["typescript"]
    :classification "semantic"
    :engine :lsp
    :scope :file
    :provider_family :lsp
    :live_overlay true
    :selectors {:extensions [".ts" ".tsx"]}
    :operation_capabilities {:definitions "exact"
                             :references "exact"}}
   ;; Stage 5b. `definitions` only: jdtls runs an invisible project without a
   ;; build file, so `textDocument/references` comes back empty even for a
   ;; method the corpus calls twice. Claiming it would report a permanent gap on
   ;; every Java file.
   {:provider_id "java-lsp"
    :provider_version "1"
    :languages ["java"]
    :classification "semantic"
    :engine :lsp
    :scope :file
    :provider_family :lsp
    :live_overlay true
    :selectors {:extensions [".java"]}
    :operation_capabilities {:definitions "exact"}}])

(def project-descriptors
  "Versioned project-scoped provider descriptors (Stage 4.5).

  SCIP indexes a project in one run and then yields facts for the documents that
  run covered, so these providers are not per-file parsers. The descriptor data
  lives here because the catalog is the single source of truth for every
  provider claim; the executable roles do not, because
  `semidx.runtime.providers.scip-typescript` and
  `semidx.runtime.providers.scip-java` load generated protobuf classes through
  `semidx.runtime.scip`, and this namespace is on the per-file planning path
  that must keep loading without them. `semidx.runtime.provider-batch` owns the
  roles and is the only namespace that requires both adapters.

  `:project_manifests` (Stage 6c) names the files whose presence decides whether
  a workspace is a project of this provider's kind at all. It is a claim about
  the provider, so it belongs to the catalog rather than to the cache that reads
  it: `semidx.runtime.provider-negative-cache` uses it as the eligibility
  evidence that lets a generic index failure be remembered instead of retried on
  every build.

  Both providers remain default-off: nothing plans them unless a caller supplies
  an observed status and batch coverage."
  [{:provider_id "scip-typescript"
    :provider_version "1"
    :languages ["typescript"]
    :classification "semantic"
    :engine :scip
    :scope :project
    :selectors {:extensions [".ts" ".tsx"]}
    :project_manifests ["package.json" "tsconfig.json" "jsconfig.json"]
    :operation_capabilities {:definitions "exact"
                             :references "exact"}}
   {:provider_id "scip-java"
    :provider_version "1"
    :languages ["java"]
    :classification "semantic"
    :engine :scip
    :scope :project
    :selectors {:extensions [".java"]}
    :project_manifests ["pom.xml" "build.gradle" "build.gradle.kts"
                        "settings.gradle" "settings.gradle.kts"]
    :operation_capabilities {:definitions "exact"
                             :references "exact"}}])

(def descriptors-by-id
  "Every descriptor the catalog knows, file-scoped and project-scoped alike.

  Lookup by id must find a project provider — evidence, plans, and batches all
  carry its `provider_version` — while eligibility by path stays scope-aware in
  `descriptors-for` and `descriptors-for-project`."
  (into {} (map (juxt :provider_id identity)) (concat descriptors project-descriptors)))

(defn descriptor [provider-id]
  (get descriptors-by-id provider-id))

(defn selects-path?
  "True when a descriptor's selectors accept `path`."
  [descriptor path]
  (boolean (some (fn [extension] (str/ends-with? (str path) extension))
                 (get-in descriptor [:selectors :extensions]))))

(defn descriptors-for
  "File-scoped descriptors eligible for one path and operation, in catalog order.

  It reads `descriptors`, never `project-descriptors`: a project provider cannot
  be planned from a path alone, only from batch coverage a run actually produced
  (`semidx.runtime.provider-selection/provider-plan`, key `:batch_coverage`)."
  ([path] (descriptors-for path nil))
  ([path operation]
   (->> descriptors
        (filter #(selects-path? % path))
        (filter (fn [d]
                  (or (nil? operation)
                      (contains? (:operation_capabilities d) operation))))
        vec)))

(defn descriptors-for-project
  "Project-scoped descriptors, optionally narrowed to `languages`.

  `languages` is a collection of language names; nil or empty means every
  project provider in the catalog."
  ([] (descriptors-for-project nil))
  ([languages]
   (let [wanted (set (map str languages))]
     (->> project-descriptors
          (filter (fn [d]
                    (or (empty? wanted)
                        (some wanted (:languages d)))))
          vec))))

;; ---------------------------------------------------------------------------
;; Runtime status
;; ---------------------------------------------------------------------------

(defn- now-iso [] (str (Instant/now)))

(defn locally-probed?
  "True when this catalog can observe the provider's runtime status itself."
  [descriptor]
  (boolean (and (= :file (:scope descriptor))
                (contains? locally-probed-engines (:engine descriptor)))))

(defn provider-status
  "Observe whether a locally probed provider can run right now.

  A tree-sitter provider needs both the CLI and a grammar for its language; the
  reason codes name which one is missing, so a degradation is explicit rather
  than an empty result.

  Anything this catalog cannot test is refused rather than answered, and the
  refusal is by construction: the probe reports `ready` only for the engines in
  `locally-probed-engines`. A project batch indexer or a language server
  reaching here would otherwise be declared ready without its toolchain having
  been looked at once — a false status the planner would then admit. Those
  statuses come from the boundary that owns the lifecycle
  (`provider-batch/project-statuses`, `provider-overlay/overlay-statuses`) and
  enter planning as `:observed_statuses`."
  ([provider-id] (provider-status provider-id {}))
  ([provider-id parser-opts]
   (let [descriptor (descriptor provider-id)
         language (first (:languages descriptor))
         base {:provider_id provider-id :observed_at (now-iso)}]
     (cond
       (nil? descriptor)
       (assoc base :state "unavailable" :reason_codes ["unknown_provider"])

       (not= :file (:scope descriptor))
       (assoc base :state "unavailable" :reason_codes ["provider_scope_not_file"])

       (not (contains? locally-probed-engines (:engine descriptor)))
       (assoc base :state "unavailable" :reason_codes ["provider_engine_not_probed_here"])

       (not= :tree-sitter (:engine descriptor))
       (assoc base :state "ready" :reason_codes [])

       :else
       (let [cli? (shared/tree-sitter-available? parser-opts)
             grammar (shared/parser-grammar-path parser-opts (keyword language))
             reasons (cond-> []
                       (not cli?) (conj "tree_sitter_cli_missing")
                       (str/blank? (str grammar)) (conj "tree_sitter_grammar_missing"))]
         (assoc base
                :state (if (seq reasons) "unavailable" "ready")
                :reason_codes reasons))))))

(defn statuses
  "Status for every locally probed descriptor eligible for `path`, keyed by
  provider id.

  Providers this catalog cannot test are absent rather than present-and-
  unavailable. Their status belongs to the boundary that owns their lifecycle,
  and a plan that has not been given one must treat the provider as unobserved:
  present-but-unavailable would look like a probe result nobody performed."
  ([path] (statuses path {}))
  ([path parser-opts]
   (into {} (map (fn [d] [(:provider_id d) (provider-status (:provider_id d) parser-opts)]))
         (filter locally-probed? (descriptors-for path)))))

;; ---------------------------------------------------------------------------
;; Source identity
;; ---------------------------------------------------------------------------

(def file-digest-basis
  "Byte-level digest of the file on disk. Same basis as
  `semidx.runtime.workspace-state/sha256-file`, so provider evidence and
  workspace freshness are comparable."
  "file_bytes_sha256")

(def lines-digest-basis
  "Digest of the newline-joined lines a provider was handed. Not comparable to
  a byte digest: joining normalizes line endings and drops a trailing newline."
  "joined_lines_sha256")

(defn lines-digest
  "SHA-256 of the newline-joined lines. Used only when no file is available."
  [lines]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str/join "\n" lines) "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" %) bytes)))))

(defn file-digest
  "SHA-256 of the file's bytes, or nil when it cannot be read."
  [file]
  (let [file (io/file file)]
    (when (.isFile file)
      (str "sha256:" (workspace-state/sha256-file file)))))

(defn source-identity
  "Source identity for evidence produced from `path`, with its digest basis
  named.

  ADR-046 requires evidence to be tied to the content it describes. The file's
  bytes are the basis wherever the file can be read, matching how workspace
  freshness is computed; the joined-lines digest is a fallback for callers that
  only have lines. The basis is recorded because the two are not interchangeable
  and must never be compared to each other."
  [{:keys [root_path path lines]}]
  (or (when (and root_path path)
        (when-let [digest (file-digest (io/file (str root_path) (str path)))]
          {:content_digest digest :digest_basis file-digest-basis}))
      {:content_digest (lines-digest (or lines []))
       :digest_basis lines-digest-basis}))

;; ---------------------------------------------------------------------------
;; Role functions: parsed units -> facts
;; ---------------------------------------------------------------------------

(defn- overload-identity
  "Variant C overload identity for a parsed unit.

  Only the exact tier may commit a typed signature. The regex and tree-sitter
  tiers commit arity alone, and a unit whose language exposes no arity (such as
  a TypeScript function) carries no overload identity at all — which is exactly
  what the Stage 0 identity fixtures specify."
  [unit]
  (when-let [arity (:method_arity unit)]
    {:arity arity
     :signature_precision "arity_only"
     :signature_key nil}))

(defn unit->fact
  "The `FactEvidence`-bearing fact one parsed unit carries for a provider tier.

  Public because it is the single owner of the unit-to-fact mapping, and Stage 6
  needs the same mapping in the other direction: to decide whether an arbitrated
  fact and a parsed unit are the same thing, the default path computes the key a
  unit would have had and compares it against the arbitrated key. Two spellings
  of that mapping would be two identities."
  [{:keys [provider_id provider_version authority language source_identity]} unit]
  {:key {:fact_kind "unit"
         :language language
         :path (:path unit)
         :owner (:module unit)
         :symbol (:symbol unit)
         :overload_identity (overload-identity unit)}
   :evidence [{:provider_id provider_id
               :provider_version provider_version
               :authority authority
               :operation "definitions"
               ;; The digest is of the content just parsed, so this evidence is
               ;; tied to it. Freshness is not authority: these providers stay
               ;; structural and heuristic.
               :freshness "exact"
               :source_identity source_identity
               :evidence_location {:path (:path unit)
                                   :start_line (:start_line unit)
                                   :end_line (:end_line unit)}
               :native_symbol (:symbol unit)}]
   :value {:kind (:kind unit)
           :signature (:signature unit)
           :native_unit_id (:unit_id unit)}})

(defn- parse-with-engine
  [descriptor {:keys [root_path path lines parser_opts]}]
  (let [language (first (:languages descriptor))
        engine (:engine descriptor)
        opts (case language
               "java" (assoc parser_opts
                             :java_engine engine
                             :tree_sitter_enabled (= :tree-sitter engine))
               "typescript" (assoc parser_opts
                                   :typescript_engine engine
                                   :tree_sitter_enabled (= :tree-sitter engine))
               parser_opts)]
    (case language
      "java" (java-language/parse-file root_path path lines opts)
      "typescript" (ts-language/parse-file root_path path lines opts)
      (throw (ex-info "No Stage 2 provider runner for language"
                      {:error_code :unsupported_provider_language
                       :language language
                       :provider_id (:provider_id descriptor)})))))

(def tree-sitter-success-codes
  "The `tree_sitter_*` diagnostics that report a working structural parse rather
  than a degradation.

  `tree_sitter_probe` says the CLI was found; `tree_sitter_active` says the CST
  extraction actually produced the units. Everything else under the prefix —
  `tree_sitter_unavailable`, `tree_sitter_missing_grammar`,
  `tree_sitter_parse_failed`, `tree_sitter_no_units` — is a degradation, and an
  unknown future code is treated as one."
  #{"tree_sitter_probe" "tree_sitter_active"})

(defn tree-sitter-fallback-diagnostic
  "The diagnostic showing a tree-sitter parse silently degraded to the lexical
  parser, if there is one.

  Any `tree_sitter_*` diagnostic that is not one of `tree-sitter-success-codes`
  means the structural parse did not happen: unknown future codes fail closed
  rather than passing as structural."
  [parsed]
  (first (filter (fn [d]
                   (let [code (str (:code d))]
                     (and (str/starts-with? code "tree_sitter_")
                          (not (contains? tree-sitter-success-codes code)))))
                 (:diagnostics parsed))))

(defn- refuse-silent-fallback!
  "A tree-sitter provider must not emit lexical facts under a structural label.

  `parse-file` falls back to regex when tree-sitter is unavailable or fails, and
  the fallback result is indistinguishable from a regex parse apart from a
  diagnostic. Inheriting the descriptor's structural authority there would
  launder heuristic evidence, so the run fails instead; the regex provider is
  admitted separately and contributes the same facts as heuristic."
  [descriptor parsed]
  (when (= :tree-sitter (:engine descriptor))
    (when-let [fallback (tree-sitter-fallback-diagnostic parsed)]
      (throw (ex-info "Tree-sitter provider fell back to the lexical parser"
                      {:error_code :tree_sitter_fallback_refused
                       :provider_id (:provider_id descriptor)
                       :diagnostic fallback})))))

(defn run-provider
  "Execute one file-scoped provider against one file and return its facts and
  diagnostics.

  Returns `{:facts [...] :diagnostics [...] :parser_mode ...}`. It does not
  decide authority beyond the descriptor's static claim, does not merge, and
  does not touch the default extraction path.

  A provider this catalog cannot execute is refused rather than run.
  `parse-with-engine` dispatches on language, not engine, so a SCIP or LSP
  descriptor arriving here would quietly parse the file with the language lane's
  own parser and return those units under an `exact` claim. Those providers are
  executed by `semidx.runtime.provider-batch` and
  `semidx.runtime.provider-overlay`, and their facts reach per-file execution
  through the injected `run-provider` role."
  [provider-id {:keys [path lines] :as request}]
  (let [descriptor (or (descriptor provider-id)
                       (throw (ex-info "Unknown provider"
                                       {:error_code :unknown_provider
                                        :provider_id provider-id})))
        _ (when (not= :file (:scope descriptor))
            (throw (ex-info "run-provider executes file-scoped providers only"
                            {:error_code :provider_scope_not_file
                             :provider_id provider-id
                             :scope (:scope descriptor)})))
        _ (when-not (contains? locally-probed-engines (:engine descriptor))
            (throw (ex-info "run-provider executes locally probed engines only"
                            {:error_code :provider_engine_not_executable_here
                             :provider_id provider-id
                             :engine (:engine descriptor)})))
        language (first (:languages descriptor))
        authority (get-in descriptor [:operation_capabilities :definitions])
        parsed (parse-with-engine descriptor request)
        _ (refuse-silent-fallback! descriptor parsed)
        identity* (or (:source_identity request)
                      (source-identity (select-keys request [:root_path :path :lines])))
        context {:provider_id provider-id
                 :provider_version (:provider_version descriptor)
                 :authority authority
                 :language language
                 :source_identity identity*}]
    {:facts (mapv (partial unit->fact context)
                  (map #(assoc % :path (or (:path %) path)) (:units parsed)))
     :diagnostics (vec (:diagnostics parsed))
     :parser_mode (:parser_mode parsed)}))
