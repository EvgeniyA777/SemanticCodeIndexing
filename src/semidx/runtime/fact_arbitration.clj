(ns semidx.runtime.fact-arbitration
  "Stage 1 of the Semantic Provider Authority Migration (plans/018, ADR-046).

  Additive, pure kernel for provider-neutral fact identity and deterministic
  same-key arbitration. It does not change default extraction, storage, or any
  transport; adapters (later stages) produce the `FactEvidence` values this
  namespace normalizes and merges.

  CanonicalFactKey uses the Variant C precision-aware overload identity decided
  in reports/024: the heuristic tier commits arity only
  (`signature_precision=arity_only`, `signature_key=nil`) and never guesses
  parameter types; the exact tier adds `signature_precision=typed` with a
  fully-qualified, type-only `signature_key`. Stable identity anchors on the
  core key (owner, symbol, arity, dispatch identity); the typed signature is a
  refinement used to split genuinely distinct same-arity overloads. Same-arity
  disambiguation is finding F1a; the rule implemented here is documented at
  `arbitrate-facts`.

  Identity mirrors ADR-039 / `semidx.runtime.relations`: only stable,
  provider-neutral fields feed the key; provider ids, provider-native symbols,
  runtime status, freshness, content digests, locations, and evidence quality
  are evidence only and are excluded from the key."
  (:require [clojure.string :as str]))

(def fact-schema-version "1")

;; Authority ladder (ADR-046), strongest first. Rank is used for merge
;; precedence; a lower rank number is stronger authority.
(def authority-order ["exact" "structural" "heuristic" "fallback"])
(def authority-rank (into {} (map-indexed (fn [i a] [a i]) authority-order)))

(def freshness-values #{"exact" "stale" "unknown"})

;; Documented vocabulary constants for the fact contract. Marked ^:export
;; (intentional public API) to match the convention in semidx.runtime.relations;
;; they are the valid values later provider adapters and contract mirrors read.
(def ^:export fact-kinds #{"unit" "relation"})
(def ^:export signature-precisions #{"arity_only" "typed"})

(defn- blank->nil [value]
  (let [s (some-> value str str/trim)]
    (when-not (str/blank? s) s)))

;; Non-cryptographic content-addressing hash for fact identity, matching the
;; discipline in `semidx.runtime.relations`. Baked into `canonical-fact-key-id`;
;; changing it is a breaking identity change that must bump
;; `fact-schema-version`, not a drop-in edit.
(defn- sha1 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-1")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- canonical-value [value]
  (cond
    (map? value)
    (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
          (map (fn [[k v]] [k (canonical-value v)]) value))

    (sequential? value)
    (mapv canonical-value value)

    (set? value)
    (->> value (map canonical-value) (sort-by pr-str) vec)

    :else value))

;; --- CanonicalFactKey (provider-neutral) ---

(defn normalize-overload-identity
  "Normalize a unit's overload identity to the Variant C shape. `nil` means the
  fact is not overload-bearing (e.g. a function without overloads). Returns a
  map {:arity :signature_precision :signature_key :ordinal} where signature_key
  is present only when precision is \"typed\"."
  [overload]
  (when (some? overload)
    (let [arity (:arity overload)
          precision (or (blank->nil (:signature_precision overload))
                        (if (blank->nil (:signature_key overload)) "typed" "arity_only"))
          typed? (= precision "typed")]
      {:arity (when (number? arity) (long arity))
       :signature_precision precision
       :signature_key (when typed? (blank->nil (:signature_key overload)))
       :ordinal (when (number? (:ordinal overload)) (long (:ordinal overload)))})))

(defn- unit-core-key-fields
  "Stable, provider-neutral identity fields for a unit fact. Excludes the typed
  signature (Variant C: identity anchors on the arity core key; the typed
  signature is a refinement resolved by `arbitrate-facts`)."
  [key*]
  (let [oi (normalize-overload-identity (:overload_identity key*))]
    {:fact_schema_version fact-schema-version
     :fact_kind "unit"
     :language (blank->nil (:language key*))
     :path (blank->nil (:path key*))
     :owner (blank->nil (:owner key*))
     :symbol (blank->nil (:symbol key*))
     :dispatch_identity (:dispatch_identity key*)
     :arity (:arity oi)
     :ordinal (:ordinal oi)}))

(defn- relation-core-key-fields
  "Stable identity fields for a relation fact, aligned with ADR-039 /
  `semidx.runtime.relations/relation-id-input`."
  [key*]
  {:fact_schema_version fact-schema-version
   :fact_kind "relation"
   :relation_type (blank->nil (:relation_type key*))
   :source_unit_key (:source_unit_key key*)
   :target_key (:target_key key*)
   :flow_identity (:flow_identity key*)})

(defn core-key-fields
  "Provider-neutral identity fields used to group facts for arbitration. Same
  core key => candidate for merge into one canonical fact."
  [key*]
  (case (blank->nil (:fact_kind key*))
    "unit" (unit-core-key-fields key*)
    "relation" (relation-core-key-fields key*)
    (throw (ex-info "Unknown or missing fact_kind"
                    {:error_code :invalid_fact_kind :fact_kind (:fact_kind key*)}))))

(defn canonical-fact-key-id
  "Deterministic identity string for the core key. Stable regardless of map
  entry order or provider spelling."
  [key*]
  (str "fact:" (sha1 (pr-str (canonical-value (core-key-fields key*))))))

;; --- FactEvidence ---

(defn normalize-fact-evidence
  "Normalize one FactEvidence record (ADR-046). Provider-native identifiers are
  retained for diagnostics but never enter the canonical key. Returns a map;
  invalid records are surfaced by `fact-evidence-errors`, not dropped here.

  `:native_details` is the one open slot for provider-native detail a language
  adapter wants to keep visible — scip-java's `+N` overload disambiguator and its
  signature documentation, for instance. It is deliberately a single opaque map
  rather than named fields, so a language cannot leak its own vocabulary into
  this kernel, and it is dropped when empty so evidence that has none keeps its
  previous shape."
  [ev]
  (cond-> {:provider_id (blank->nil (:provider_id ev))
           :provider_version (blank->nil (:provider_version ev))
           :authority (blank->nil (:authority ev))
           :operation (blank->nil (:operation ev))
           :freshness (or (blank->nil (:freshness ev)) "unknown")
           :source_identity (or (:source_identity ev) {})
           :evidence_location (:evidence_location ev)
           :native_symbol (blank->nil (:native_symbol ev))}
    (seq (:native_details ev)) (assoc :native_details (:native_details ev))))

(def source-identity-anchors
  "Fields that can tie evidence to the content it describes (ADR-046).

  A per-document content digest, an LSP document version, or a revision-bound
  artifact. Provider health, and a provider's own claim that it is fresh, are
  not anchors."
  [:content_digest :document_version :revision])

(defn anchored-source-identity?
  "True when source identity carries at least one anchor tying it to content."
  [source-identity]
  (boolean
   (some (fn [field]
           (let [value (get source-identity field)]
             (cond
               (string? value) (some? (blank->nil value))
               (number? value) true
               :else false)))
         source-identity-anchors)))

(defn fact-evidence-errors
  "Structured validation errors for a normalized FactEvidence record. Empty
  vector means valid."
  [ev]
  (cond-> []
    (not (blank->nil (:provider_id ev)))
    (conj {:code :missing-provider-id :field :provider_id
           :message "FactEvidence is missing provider_id."})

    (not (contains? authority-rank (:authority ev)))
    (conj {:code :invalid-authority :field :authority
           :message (str "Unknown authority: " (pr-str (:authority ev)) ".")})

    (not (contains? freshness-values (:freshness ev)))
    (conj {:code :invalid-freshness :field :freshness
           :message (str "Unknown freshness: " (pr-str (:freshness ev)) ".")})

    ;; ADR-046: regex/heuristic evidence must never claim exact authority.
    (and (= "exact" (:authority ev))
         (contains? #{"stale" "unknown"} (:freshness ev)))
    (conj {:code :exact-without-fresh-identity :field :freshness
           :message "Exact authority requires fresh source identity (freshness must be exact)."})

    ;; ADR-046: "a provider whose source identity cannot be tied to the current
    ;; content is stale and is excluded from exact authority". Declaring
    ;; freshness is the provider's claim; the anchor is what makes it checkable,
    ;; so an unanchored fact cannot hold exact authority however fresh it says
    ;; it is.
    (and (= "exact" (:authority ev))
         (not (anchored-source-identity? (:source_identity ev))))
    (conj {:code :exact-without-source-identity :field :source_identity
           :message (str "Exact authority requires source identity tied to the current content: "
                         "content_digest, document_version, or revision.")})))

;; --- Arbitration ---

(defn- strongest-authority [evidences]
  (->> evidences
       (map :authority)
       (sort-by #(get authority-rank % Long/MAX_VALUE))
       first))

(defn- dedupe-evidence [evidences]
  (->> evidences
       (sort-by pr-str)
       distinct
       vec))

(defn- typed-signature-keys [facts]
  (->> facts
       (keep (fn [f] (get-in f [:key :overload_identity :signature_key])))
       (map blank->nil)
       (remove nil?)
       distinct
       vec))

(defn- merge-one-canonical-fact
  "Merge a set of same-identity facts into one canonical fact. `signature-key`
  is the resolved typed signature (or nil for an arity-only canonical fact).
  `fact-identity` is the stable identity string: it equals the core key id in
  the common case and only diverges (by signature) when genuine same-arity
  overloads are split. This preserves the Variant C invariant that attaching a
  typed signature later must not change a common-case fact's identity (so a
  regex-only unit keeps its id when SCIP/LSP evidence arrives)."
  [key-id fact-identity core-fields signature-key facts]
  (let [evidences (dedupe-evidence (mapcat :evidence facts))
        authority (strongest-authority evidences)]
    {:canonical_fact_key_id key-id
     :fact_identity fact-identity
     :core_key core-fields
     :signature_precision (if signature-key "typed" "arity_only")
     :signature_key signature-key
     :authority authority
     :evidence evidences}))

;; --- FactBatch ---

(def batch-schema-version "1")

(defn normalize-fact-batch
  "Normalize one provider's batch of facts.

  A batch is the provenance envelope around everything a single provider
  produced in one run: who produced it, against which source identity, what it
  claims to have covered, and what went wrong while producing it. Coverage and
  diagnostics are what make a provider's silence readable: a provider that
  returned nothing because it failed must not be indistinguishable from one that
  found nothing.

  A fact's own `provider_id` and `freshness` win over the batch defaults. The
  batch fills them in only when the fact left them unset, so an envelope can
  never restate a fact's provenance as something else."
  [batch]
  (let [provider-id (blank->nil (:provider_id batch))
        provider-version (blank->nil (:provider_version batch))
        freshness (or (blank->nil (:freshness batch)) "unknown")
        source-identity (or (:source_identity batch) {})
        stamp (fn [fact]
                (update fact :evidence
                        (fn [evidences]
                          (mapv (fn [ev]
                                  (normalize-fact-evidence
                                   (cond-> ev
                                     (not (blank->nil (:provider_id ev)))
                                     (assoc :provider_id provider-id)

                                     (not (blank->nil (:provider_version ev)))
                                     (assoc :provider_version provider-version)

                                     (not (blank->nil (:freshness ev)))
                                     (assoc :freshness freshness)

                                     (empty? (:source_identity ev))
                                     (assoc :source_identity source-identity))))
                                evidences))))]
    {:batch_schema_version batch-schema-version
     :provider_id provider-id
     :provider_version provider-version
     :operation (blank->nil (:operation batch))
     :freshness freshness
     :source_identity source-identity
     :coverage {:paths (vec (:paths (:coverage batch)))
                :fact_kinds (vec (:fact_kinds (:coverage batch)))
                :complete (boolean (get-in batch [:coverage :complete]))}
     :diagnostics (vec (:diagnostics batch))
     :facts (mapv stamp (:facts batch))}))

(defn fact-batch-errors
  "Structured validation errors for a normalized batch. Empty vector means
  valid. Evidence errors are reported per fact so one bad record does not
  invalidate a whole provider run silently."
  [batch]
  (let [base (cond-> []
               (not (blank->nil (:provider_id batch)))
               (conj {:code :missing-provider-id :field :provider_id
                      :message "FactBatch is missing provider_id."})

               (not (contains? freshness-values (:freshness batch)))
               (conj {:code :invalid-freshness :field :freshness
                      :message (str "Unknown freshness: " (pr-str (:freshness batch)) ".")}))]
    (into base
          (for [[fact-index fact] (map-indexed vector (:facts batch))
                [evidence-index ev] (map-indexed vector (:evidence fact))
                error (fact-evidence-errors ev)]
            (assoc error
                   :fact_index fact-index
                   :evidence_index evidence-index
                   :provider_id (:provider_id ev))))))

(defn arbitrate-facts
  "Deterministically merge a collection of provider facts into canonical facts.

  Each input fact is {:key <CanonicalFactKey map> :evidence [<FactEvidence>...]
  :value <optional map>}. Grouping is by the provider-neutral core key
  (`canonical-fact-key-id`). Within a group:

  - typed evidence partitions the group by `signature_key`;
  - if the group has <=1 distinct typed signature, all facts (typed +
    arity-only) merge into ONE canonical fact (the common case: distinct
    arities, or a single method per arity). Its `fact_identity` equals the core
    key id, so a regex-only unit keeps its identity when SCIP/LSP evidence
    arrives later;
  - if the group has >=2 distinct typed signatures, they are genuinely distinct
    same-arity overloads and SPLIT into one canonical fact each (distinct
    `fact_identity` = core key id + signature digest); any arity-only facts in
    that group cannot be attributed to a specific overload and are surfaced as
    an `:arity_ambiguous_heuristic` diagnostic (finding F1a) rather than merged
    into either overload.

  Merge never lets lower authority overwrite higher authority; all evidence is
  retained; output ordering is deterministic regardless of input order. Returns
  {:facts <sorted vec of canonical facts> :diagnostics <vec>}."
  [facts]
  (let [grouped (group-by (fn [f] (canonical-fact-key-id (:key f))) facts)
        result
        (reduce
         (fn [{:keys [facts diagnostics]} [key-id group]]
           (let [core-fields (core-key-fields (:key (first group)))
                 sig-keys (typed-signature-keys group)]
             (if (>= (count sig-keys) 2)
               ;; Distinct same-arity overloads: split by typed signature.
               (let [typed-facts (filter #(get-in % [:key :overload_identity :signature_key]) group)
                     arity-only (remove #(get-in % [:key :overload_identity :signature_key]) group)
                     by-sig (group-by #(get-in % [:key :overload_identity :signature_key]) typed-facts)
                     split (->> by-sig
                                (map (fn [[sig fs]]
                                       (merge-one-canonical-fact
                                        key-id (str key-id "$sig" (sha1 sig)) core-fields sig fs))))
                     diag (when (seq arity-only)
                            [{:code :arity_ambiguous_heuristic
                              :canonical_fact_key_id key-id
                              :signature_keys (vec (sort sig-keys))
                              :message (str "Arity-only evidence cannot be attributed to one of "
                                            (count sig-keys) " same-arity typed overloads (F1a).")}])]
                 {:facts (into facts split)
                  :diagnostics (into diagnostics (or diag []))})
               ;; Common case: one canonical fact keyed on the core key.
               (let [sig (first sig-keys)]
                 {:facts (conj facts (merge-one-canonical-fact key-id key-id core-fields sig group))
                  :diagnostics diagnostics}))))
         {:facts [] :diagnostics []}
         (sort-by key grouped))]
    {:facts (->> (:facts result)
                 (sort-by :fact_identity)
                 vec)
     :diagnostics (vec (:diagnostics result))}))

(defn arbitrate-batches
  "Normalize provider batches and arbitrate every fact they carry.

  This is the entry point a provider orchestrator uses: it keeps each provider's
  envelope visible next to the merged result, so an empty or failed provider run
  stays readable instead of disappearing into an absence of facts. Batch-level
  validation errors are returned, never thrown and never silently dropped;
  invalid batches do not contribute facts."
  [batches]
  (let [normalized (mapv normalize-fact-batch batches)
        checked (mapv (fn [batch] [batch (fact-batch-errors batch)]) normalized)
        valid (->> checked (remove (fn [[_ errors]] (seq errors))) (mapv first))
        errors (vec (for [[batch batch-errors] checked
                          error batch-errors]
                      (assoc error :provider_id (or (:provider_id error)
                                                    (:provider_id batch)))))
        arbitrated (arbitrate-facts (mapcat :facts valid))]
    (assoc arbitrated
           :batches (mapv (fn [batch]
                            (-> (select-keys batch [:provider_id :provider_version :operation
                                                    :freshness :source_identity :coverage
                                                    :diagnostics])
                                (assoc :fact_count (count (:facts batch)))))
                          normalized)
           :errors errors)))
