(ns semidx.runtime.providers.scip-adapter
  "Shared boundary for the SCIP provider adapters (plans/018 Stages 3 and 4,
  ADR-046).

  Everything a SCIP adapter does that is not language-specific lives here:
  toolchain path resolution helpers, the per-document stale gate that anchors
  exact authority, `FactBatch` assembly, the arity-only overload guard, and the
  `ready` / `unavailable` / `failed` result shapes.

  The plan's Stage 4 exit criterion is that no TypeScript-specific rule enters
  this boundary. What stays in a language adapter is only: how its toolchain is
  resolved and invoked, and which `semidx.runtime.providers.scip-normalize`
  bridge it uses.

  Nothing here writes to a snapshot or changes default extraction."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.fact-arbitration :as fact-arbitration]
            [semidx.runtime.providers.scip-normalize :as scip-normalize]
            [semidx.runtime.workspace-state :as workspace-state]))

;; ---------------------------------------------------------------------------
;; Path helpers
;; ---------------------------------------------------------------------------

(defn present-path
  "Trim a candidate to a non-blank string, or nil."
  [value]
  (let [s (some-> value str str/trim)]
    (when-not (str/blank? s) s)))

(defn executable-file?
  "True when `path` names a file that can be executed."
  [path]
  (let [f (io/file (str path))]
    (and (.isFile f) (.canExecute f))))

(defn directory?
  "True when `path` names an existing directory."
  [path]
  (.isDirectory (io/file (str path))))

;; ---------------------------------------------------------------------------
;; Per-document stale gate
;; ---------------------------------------------------------------------------

(def ^:private windows-drive-re #"^[A-Za-z]:[\\/]")

(defn document-path-problem
  "Why a SCIP `Document.relative_path` must not be resolved against the project
  root, or nil when it is safe to resolve.

  A SCIP artifact is an external input: it may be malformed, produced against a
  different root, or supplied by a caller. Resolving its paths blindly let a
  document claim coverage of a file outside the project and still be anchored as
  fresh exact evidence, and an absolute path threw out of the whole run instead
  of degrading."
  [relative-path]
  (let [p (str relative-path)]
    (cond
      (str/blank? p) :blank_document_path
      (str/starts-with? p "/") :absolute_document_path
      (str/starts-with? p "\\") :absolute_document_path
      (re-find windows-drive-re p) :absolute_document_path
      (some #{".."} (str/split p #"[/\\]")) :parent_traversal_in_document_path)))

(defn- containment-problem
  "Nil when `relative-path` canonically resolves inside `project-root`, otherwise
  the reason it does not.

  Defence in depth behind `document-path-problem`: a symlink can leave the root
  without any `..` segment. A path that cannot be canonicalized at all is treated
  as outside, and the filesystem's own message is returned rather than printed —
  these adapters run behind the MCP stdio transport, where anything written to
  stdout corrupts the protocol stream."
  [project-root relative-path]
  (try
    (let [root (.getCanonicalFile (io/file (str project-root)))
          target (.getCanonicalFile (io/file root (str relative-path)))]
      (when-not (str/starts-with? (str target) (str root java.io.File/separator))
        {:reason :document_escapes_project_root}))
    (catch Exception e
      {:reason :document_path_unresolvable :detail (.getMessage e)})))

(defn workspace-digest
  "sha256 of the workspace file for `relative-path` under `project-root`, or nil
  when the file does not exist. Same basis as
  `semidx.runtime.workspace-state/sha256-file`, so a digest here is comparable to
  workspace freshness.

  Callers must screen the path with `document-path-problem` first. This does not
  guard against I/O failures itself; `document-freshness` owns that, so the
  reason reaches a diagnostic instead of being swallowed here."
  [project-root relative-path]
  (let [f (io/file (str project-root) (str relative-path))]
    (when (.isFile f)
      (str "sha256:" (workspace-state/sha256-file f)))))

(defn document-freshness
  "Classify one SCIP document against the workspace. Returns
  `{:state :fresh :content_digest ...}`, `{:state :missing}`, or
  `{:state :mismatch :expected ... :actual ...}`.

  `expected-digests` is an optional map of `relative-path` -> expected
  `sha256:...` digest (for example captured from a workspace-state snapshot taken
  before indexing); when it carries no entry for a document, a present file is
  fresh and its own digest is the anchor.

  ADR-046 requires evidence to be tied to the content it describes, so a
  document that cannot be anchored contributes nothing at exact authority. The
  gate is document-level: range-level invalidation is a later refinement
  (reports/024 finding F2).

  A path that must not be resolved at all — blank, absolute, escaping the
  project root, or unreadable — is `:invalid` and is reported rather than
  digested. Every failure returns a reason for the caller's diagnostic; nothing
  is printed, because these adapters run behind the MCP stdio transport."
  [project-root relative-path expected-digests]
  (if-let [problem (or (when-let [reason (document-path-problem relative-path)]
                         {:reason reason})
                       (containment-problem project-root relative-path))]
    (merge {:state :invalid} problem)
    (let [digest (try
                   {:value (workspace-digest project-root relative-path)}
                   (catch Exception e
                     {:error (.getMessage e)}))]
      (if (:error digest)
        {:state :invalid
         :reason :document_path_unreadable
         :detail (:error digest)}
        (let [actual (:value digest)
              expected (get expected-digests relative-path)]
          (cond
            (nil? actual) {:state :missing}
            (and expected (not= expected actual)) {:state :mismatch
                                                   :expected expected
                                                   :actual actual}
            :else {:state :fresh :content_digest actual}))))))

(defn stale-diagnostic [relative-path freshness]
  (case (:state freshness)
    :missing {:code :scip_document_source_missing
              :document relative-path
              :message (str "SCIP covered " relative-path
                            " but its workspace file is missing; dropped from exact facts")}
    :mismatch {:code :scip_document_stale
               :document relative-path
               :expected (:expected freshness)
               :actual (:actual freshness)
               :message (str "SCIP artifact for " relative-path
                             " does not match the workspace file; dropped from exact facts")}
    :invalid (cond-> {:code :scip_document_path_invalid
                      :document relative-path
                      :reason (:reason freshness)
                      :message (str "SCIP document path " (pr-str relative-path)
                                    " is not a safe project-relative path ("
                                    (name (:reason freshness))
                                    "); dropped without being read")}
               (:detail freshness) (assoc :detail (:detail freshness)))))

;; ---------------------------------------------------------------------------
;; Arity-only overload guard (plans/018 Stage 4 exit criterion, finding S1a)
;; ---------------------------------------------------------------------------

(defn- definition-fact? [fact]
  (some #(= "definitions" (:operation %)) (:evidence fact)))

(defn- arity-only-fact? [fact]
  (let [overload (get-in fact [:key :overload_identity])]
    (and overload
         (= "arity_only" (:signature_precision overload))
         (nil? (:signature_key overload)))))

(defn- native-symbols-of [facts]
  (->> facts (mapcat :evidence) (keep :native_symbol) distinct sort vec))

(defn withhold-ambiguous-arity-only-overloads
  "Withhold the exact contribution for same-arity overloads that carry no typed
  signature to tell them apart.

  `fact-arbitration/arbitrate-facts` splits a core-key group only when it holds
  two or more distinct typed `signature_key`s. A provider whose whole tier is
  `arity_only` — scip-java, which disambiguates overloads with a source-order
  ordinal instead of parameter types — never supplies one, so two genuinely
  distinct same-arity overloads would merge into a single canonical fact at
  exact authority with no diagnostic. That false exact identity was reproduced
  on 2026-09-05; see reports/024 finding S2.

  A group is ambiguous when two or more of its definition facts are arity-only
  and were spelled with different provider-native symbols. Every fact in such a
  group — references included, since a reference cannot be attributed either — is
  withheld, so the lower tiers supply those units instead. Non-ambiguous groups
  pass through untouched.

  Returns `{:facts <kept> :withheld <dropped> :diagnostics [...]}`."
  [facts]
  (let [grouped (group-by #(fact-arbitration/canonical-fact-key-id (:key %)) facts)
        ambiguous (into {}
                        (keep (fn [[key-id group]]
                                (let [definitions (filter definition-fact? group)
                                      natives (native-symbols-of definitions)]
                                  (when (and (>= (count definitions) 2)
                                             (>= (count natives) 2)
                                             (every? arity-only-fact? definitions))
                                    [key-id natives]))))
                        grouped)]
    (if (empty? ambiguous)
      {:facts (vec facts) :withheld [] :diagnostics []}
      {:facts (vec (remove #(contains? ambiguous
                                       (fact-arbitration/canonical-fact-key-id (:key %)))
                           facts))
       :withheld (vec (filter #(contains? ambiguous
                                          (fact-arbitration/canonical-fact-key-id (:key %)))
                              facts))
       :diagnostics (vec (for [[key-id natives] (sort-by key ambiguous)
                               :let [example (first (get grouped key-id))]]
                           {:code :same_arity_arity_only_overload_ambiguous
                            :canonical_fact_key_id key-id
                            :symbol (get-in example [:key :symbol])
                            :path (get-in example [:key :path])
                            :arity (get-in example [:key :overload_identity :arity])
                            :native_symbols natives
                            :message (str "Refusing to claim one exact identity for "
                                          (count natives)
                                          " same-arity overloads of "
                                          (get-in example [:key :symbol])
                                          " that carry no typed signature; the exact"
                                          " contribution is withheld and the lower"
                                          " tiers supply these units.")}))})))

;; ---------------------------------------------------------------------------
;; FactBatch assembly
;; ---------------------------------------------------------------------------

(defn facts->batches
  "Group normalized facts into one `FactBatch` per operation. Each fact from
  `scip-normalize` carries exactly one evidence record with one operation."
  [facts {:keys [provider-id provider-version covered-paths complete?]}]
  (->> facts
       (group-by #(-> % :evidence first :operation))
       (sort-by key)
       (mapv (fn [[operation op-facts]]
               {:provider_id provider-id
                :provider_version provider-version
                :operation operation
                :freshness "exact"
                :source_identity {}
                :coverage {:paths (vec covered-paths)
                           :fact_kinds ["unit"]
                           :complete complete?}
                :diagnostics []
                :facts (vec op-facts)}))))

(defn unmapped-summary
  "One diagnostic summarising every SCIP symbol deliberately not turned into a
  fact, or nil when there were none."
  [unmapped]
  (when (seq unmapped)
    {:code :scip_symbols_unmapped
     :count (count unmapped)
     :by_reason (into (sorted-map) (frequencies (map :reason unmapped)))
     :message (str (count unmapped)
                   " SCIP symbol occurrences were deliberately not turned into facts")}))

;; ---------------------------------------------------------------------------
;; Result shapes
;; ---------------------------------------------------------------------------

(defn unavailable-result
  "The provider could not run at all. Not an error: the caller degrades to
  tree-sitter/regex."
  [{:keys [provider-id provider-version reason-codes message]}]
  {:provider_id provider-id
   :provider_version provider-version
   :result "unavailable"
   :reason_codes (vec reason-codes)
   :facts []
   :raw_facts []
   :batches []
   :errors []
   :diagnostics [{:code :scip_provider_unavailable :message message}]
   :coverage {:covered_paths [] :stale_documents [] :complete false}
   :unmapped []})

(defn failed-result
  "The toolchain resolved but the run errored. No facts, and the reason is
  visible rather than silently empty."
  [{:keys [provider-id provider-version diagnostic]}]
  {:provider_id provider-id
   :provider_version provider-version
   :result "failed"
   :facts []
   :raw_facts []
   :batches []
   :errors []
   :diagnostics [diagnostic]
   :coverage {:covered_paths [] :stale_documents [] :complete false}
   :unmapped []})

;; ---------------------------------------------------------------------------
;; Index -> arbitrated facts
;; ---------------------------------------------------------------------------

(defn facts-from-index
  "Turn an already-read SCIP index into arbitrated shadow facts.

  Language-neutral: `:language` selects the `scip-normalize` bridge, and
  `:guard-overloads?` turns on the arity-only overload guard for a tier that
  cannot supply typed signatures.

  `opts`:
  - `:language` (required) — bridge selector;
  - `:project-root` (required) — used to digest the workspace files SCIP covered;
  - `:provider-id` / `:provider-version` — evidence provenance;
  - `:expected-document-digests` — optional stale-gate expectations;
  - `:guard-overloads?` — apply `withhold-ambiguous-arity-only-overloads`."
  [scip-index {:keys [language project-root provider-id provider-version
                      expected-document-digests guard-overloads?]}]
  (when-not project-root
    (throw (ex-info "facts-from-index requires :project-root to anchor exact facts"
                    {:error_code :missing_project_root})))
  (let [documents (:documents scip-index)
        graded (mapv (fn [doc]
                       (assoc doc :freshness
                              (document-freshness project-root (:relative-path doc)
                                                  expected-document-digests)))
                     documents)
        fresh (filterv #(= :fresh (:state (:freshness %))) graded)
        invalid (filterv #(= :invalid (:state (:freshness %))) graded)
        stale (filterv #(contains? #{:missing :mismatch} (:state (:freshness %))) graded)
        digest-by-path (into {} (map (juxt :relative-path
                                           (comp :content_digest :freshness))
                                     fresh))
        normalized (scip-normalize/normalize-index
                    (assoc scip-index :documents fresh)
                    {:language language
                     :provider-id provider-id
                     :provider-version provider-version
                     :source-identity (fn [relative-path]
                                        {:content_digest (get digest-by-path relative-path)})})
        guarded (if guard-overloads?
                  (withhold-ambiguous-arity-only-overloads (:facts normalized))
                  {:facts (:facts normalized) :withheld [] :diagnostics []})
        covered-paths (mapv :relative-path fresh)
        complete? (and (empty? stale) (empty? invalid) (empty? (:withheld guarded)))
        batches (facts->batches (:facts guarded)
                                {:provider-id provider-id
                                 :provider-version provider-version
                                 :covered-paths covered-paths
                                 :complete? complete?})
        arbitrated (fact-arbitration/arbitrate-batches batches)
        diagnostics (vec (concat (map #(stale-diagnostic (:relative-path %) (:freshness %))
                                      (concat stale invalid))
                                 (:diagnostics guarded)
                                 (when-let [s (unmapped-summary (:unmapped normalized))]
                                   [s])))]
    {:provider_id provider-id
     :provider_version provider-version
     :result "ready"
     :facts (:facts arbitrated)
     ;; Pre-arbitration facts, one evidence record each, kept so a shadow
     ;; comparison can co-arbitrate this tier with another in one pass.
     :raw_facts (vec (:facts guarded))
     :batches (:batches arbitrated)
     :errors (:errors arbitrated)
     :diagnostics diagnostics
     :coverage {:covered_paths covered-paths
                :stale_documents (mapv :relative-path stale)
                :invalid_documents (mapv :relative-path invalid)
                :withheld_fact_count (count (:withheld guarded))
                :complete complete?}
     :unmapped (:unmapped normalized)}))
