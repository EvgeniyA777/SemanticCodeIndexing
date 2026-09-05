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

(def descriptors
  "Versioned provider descriptors available in Stage 2.

  `operation_capabilities` are claims bounded by runtime status and freshness,
  not unconditional confidence grants.

  Stage 2 claims `definitions` only. These adapters emit unit facts from the
  existing parsers, so claiming `document_symbols` or `call_hierarchy` would
  make every run report a permanent gap for an operation nothing produces. Those
  claims belong to the providers that actually implement them."
  [{:provider_id "java-tree-sitter"
    :provider_version "1"
    :languages ["java"]
    :classification "structural"
    :engine :tree-sitter
    :selectors {:extensions [".java"]}
    :operation_capabilities {:definitions "structural"}}
   {:provider_id "java-regex"
    :provider_version "1"
    :languages ["java"]
    :classification "lexical"
    :engine :regex
    :selectors {:extensions [".java"]}
    :operation_capabilities {:definitions "heuristic"}}
   {:provider_id "typescript-tree-sitter"
    :provider_version "1"
    :languages ["typescript"]
    :classification "structural"
    :engine :tree-sitter
    :selectors {:extensions [".ts" ".tsx"]}
    :operation_capabilities {:definitions "structural"}}
   {:provider_id "typescript-regex"
    :provider_version "1"
    :languages ["typescript"]
    :classification "lexical"
    :engine :regex
    :selectors {:extensions [".ts" ".tsx"]}
    :operation_capabilities {:definitions "heuristic"}}])

(def descriptors-by-id
  (into {} (map (juxt :provider_id identity)) descriptors))

(defn descriptor [provider-id]
  (get descriptors-by-id provider-id))

(defn selects-path?
  "True when a descriptor's selectors accept `path`."
  [descriptor path]
  (boolean (some (fn [extension] (str/ends-with? (str path) extension))
                 (get-in descriptor [:selectors :extensions]))))

(defn descriptors-for
  "Descriptors eligible for one path and operation, in catalog order."
  ([path] (descriptors-for path nil))
  ([path operation]
   (->> descriptors
        (filter #(selects-path? % path))
        (filter (fn [d]
                  (or (nil? operation)
                      (contains? (:operation_capabilities d) operation))))
        vec)))

;; ---------------------------------------------------------------------------
;; Runtime status
;; ---------------------------------------------------------------------------

(defn- now-iso [] (str (Instant/now)))

(defn provider-status
  "Observe whether a provider can run right now.

  A tree-sitter provider needs both the CLI and a grammar for its language; the
  reason codes name which one is missing, so a degradation is explicit rather
  than an empty result."
  ([provider-id] (provider-status provider-id {}))
  ([provider-id parser-opts]
   (let [descriptor (descriptor provider-id)
         language (first (:languages descriptor))
         base {:provider_id provider-id :observed_at (now-iso)}]
     (cond
       (nil? descriptor)
       (assoc base :state "unavailable" :reason_codes ["unknown_provider"])

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
  "Status for every descriptor eligible for `path`, keyed by provider id."
  ([path] (statuses path {}))
  ([path parser-opts]
   (into {} (map (fn [d] [(:provider_id d) (provider-status (:provider_id d) parser-opts)]))
         (descriptors-for path))))

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

(defn- unit->fact
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

(defn tree-sitter-fallback-diagnostic
  "The diagnostic showing a tree-sitter parse silently degraded to the lexical
  parser, if there is one.

  Any `tree_sitter_*` diagnostic other than the positive CLI probe means the
  structural parse did not happen: unknown future codes fail closed rather than
  passing as structural."
  [parsed]
  (first (filter (fn [d]
                   (let [code (str (:code d))]
                     (and (str/starts-with? code "tree_sitter_")
                          (not= "tree_sitter_probe" code))))
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
  "Execute one provider against one file and return its facts and diagnostics.

  Returns `{:facts [...] :diagnostics [...] :parser_mode ...}`. It does not
  decide authority beyond the descriptor's static claim, does not merge, and
  does not touch the default extraction path."
  [provider-id {:keys [path lines] :as request}]
  (let [descriptor (or (descriptor provider-id)
                       (throw (ex-info "Unknown provider"
                                       {:error_code :unknown_provider
                                        :provider_id provider-id})))
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
