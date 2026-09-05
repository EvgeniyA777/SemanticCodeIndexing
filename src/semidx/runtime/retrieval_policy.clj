(ns semidx.runtime.retrieval-policy
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.index :as idx]
            [semidx.runtime.language-registry :as registry]))

(def ^:private default-policy
  {:policy_id "heuristic_v1"
   :version "2026-03-10"
   :weights {:exact_target_resolved 140
             :target_path_match 95
             :diff_overlap_direct 90
             :target_module_match 70
             :target_test_match 50
             :dispatch_value_match 25
             :graph_callee_neighbor 44
             :graph_caller_neighbor 32
             :graph_module_neighbor 18
             :graph_path_neighbor 16
             :graph_related_test_neighbor 28
             :hint_preferred_path 15
             :hint_preferred_module 10
             :hint_focus_on_tests 42
             :hint_suspected_symbol_exact 42
             :hint_suspected_symbol_segment 18
             :source_path_prior 6
             :lexical_overlap 8
             :parser_fallback 0}
   :caps {:no_tier1_max 89
          :fallback_max 59}
   :thresholds {:top_authority_min 120
                :useful_support_min 80
                :exploratory_min 30
                :ambiguity_delta_max 10
                :broad_impact_neighbor_threshold 2}
   :confidence_scores {:high 0.90
                       :medium 0.62
                       :low 0.30}
   :raw_fetch {:medium_upgrade_min_snippets 2}})

(def ^:private lifecycle-states
  #{"draft" "shadow" "active" "retired"})

(def ^:private promotion-modes
  #{"auto_promotable" "manual_approval_required" "blocked"})

(def ^:private approval-tiers
  #{"standard" "restricted" "critical"})

(def ^:private policy-tuning-keys
  #{:weights :caps :thresholds :confidence_scores :raw_fetch})

(def ^:private registry-metadata-keys
  [:notes
   :created_at
   :updated_at
   :activated_at
   :retired_at
   :shadow_review
   :approvals
   :governance])

(def ^:private default-governance
  {:promotion_mode "auto_promotable"
   :approval_tier "standard"})

(def ^:private confidence-level-rank
  {"low" 0
   "medium" 1
   "high" 2})

(defn default-retrieval-policy []
  default-policy)

(declare normalize-policy)

(defn lifecycle-state? [state]
  (contains? lifecycle-states (str/lower-case (str (or state "")))))

(defn normalize-lifecycle-state [state]
  (let [normalized (str/lower-case (str (or state "draft")))]
    (if (lifecycle-state? normalized)
      normalized
      (throw (ex-info (str "unsupported retrieval policy lifecycle state " state)
                      {:type :invalid_request
                       :message (str "unsupported retrieval policy lifecycle state " state)})))))

(defn registry-entry? [value]
  (and (map? value)
       (map? (:policy value))
       (string? (:policy_id value))
       (string? (:version value))))

(defn registry-entry
  ([policy] (registry-entry policy {}))
  ([policy {:keys [state] :as metadata}]
   (let [normalized (normalize-policy policy)]
     (cond-> {:policy_id (:policy_id normalized)
              :version (:version normalized)
              :state (normalize-lifecycle-state state)
              :policy normalized}
       true (merge (select-keys metadata registry-metadata-keys))))))

(defn normalize-governance [governance]
  (let [governance* (merge default-governance (or governance {}))
        promotion-mode (str/lower-case (str (:promotion_mode governance*)))
        approval-tier (str/lower-case (str (:approval_tier governance*)))]
    (when-not (contains? promotion-modes promotion-mode)
      (throw (ex-info (str "unsupported policy promotion mode " promotion-mode)
                      {:type :invalid_request
                       :message (str "unsupported policy promotion mode " promotion-mode)})))
    (when-not (contains? approval-tiers approval-tier)
      (throw (ex-info (str "unsupported policy approval tier " approval-tier)
                      {:type :invalid_request
                       :message (str "unsupported policy approval tier " approval-tier)})))
    {:promotion_mode promotion-mode
     :approval_tier approval-tier}))

(defn normalize-policy [policy]
  (let [policy* (or policy {})
        policy-source (if (registry-entry? policy*)
                        (merge (:policy policy*)
                               (select-keys policy* [:policy_id :version]))
                        policy*)]
    (-> default-policy
        (merge (select-keys policy-source [:policy_id :version]))
        (update :weights merge (:weights policy-source))
        (update :caps merge (:caps policy-source))
        (update :thresholds merge (:thresholds policy-source))
        (update :confidence_scores merge (:confidence_scores policy-source))
        (update :raw_fetch merge (:raw_fetch policy-source)))))

(defn empty-registry []
  {:schema_version "1.0"
   :policies []})

(defn normalize-registry [registry]
  (let [registry* (or registry {})
        policies (->> (:policies registry*)
                      (mapv (fn [entry]
                              (registry-entry (or (:policy entry) entry)
                                              (merge {:state (:state entry)}
                                                     (select-keys entry registry-metadata-keys))))))]
    {:schema_version (or (:schema_version registry*) "1.0")
     :policies (mapv (fn [entry]
                       (update entry :governance normalize-governance))
                     policies)}))

(defn load-registry [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))]
    (normalize-registry (edn/read rdr))))

(defn write-registry! [path registry]
  (let [normalized (normalize-registry registry)
        content (pr-str normalized)
        target (io/file path)
        dir (or (.getParentFile target) (io/file "."))
        tmp (java.io.File/createTempFile (str (.getName target) ".") ".edn" dir)]
    (try
      (spit tmp content)
      (let [from (.toPath tmp)
            to (.toPath target)]
        (try
          (java.nio.file.Files/move from to
                                    (into-array java.nio.file.CopyOption
                                                [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                 java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
          (catch java.nio.file.AtomicMoveNotSupportedException _
            (java.nio.file.Files/move from to
                                      (into-array java.nio.file.CopyOption
                                                  [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))
      (catch Throwable t
        (io/delete-file tmp true)
        (throw t)))))

(defn resolve-registry-source [value]
  (cond
    (nil? value) nil
    (string? value) (load-registry value)
    (map? value) (normalize-registry value)
    :else
    (throw (ex-info "unsupported policy registry source"
                    {:type :invalid_request
                     :message "unsupported policy registry source"}))))

(defn list-registry-entries [registry]
  (:policies (normalize-registry registry)))

(defn resolve-registry-entry
  ([registry policy-id]
   (resolve-registry-entry registry policy-id nil))
  ([registry policy-id version]
   (->> (list-registry-entries registry)
        (filter #(= (str policy-id) (:policy_id %)))
        (filter #(if version
                   (= (str version) (:version %))
                   true))
        first)))

(defn active-registry-entry [registry]
  (->> (list-registry-entries registry)
       (filter #(= "active" (:state %)))
       first))

(defn policy-from-entry [entry]
  (when entry
    (normalize-policy entry)))

(defn effective-governance [entry]
  (normalize-governance (:governance entry)))

(defn promotion-mode [entry]
  (:promotion_mode (effective-governance entry)))

(defn approval-tier [entry]
  (:approval_tier (effective-governance entry)))

(defn auto-promotable? [entry]
  (= "auto_promotable" (promotion-mode entry)))

(defn manual-approval-required? [entry]
  (= "manual_approval_required" (promotion-mode entry)))

(defn blocked? [entry]
  (= "blocked" (promotion-mode entry)))

(defn- policy-selector-map? [policy]
  (and (map? policy)
       (contains? policy :policy_id)
       (not-any? policy policy-tuning-keys)))

(defn resolve-policy
  ([policy]
   (resolve-policy policy nil))
  ([policy registry]
   (let [registry* (resolve-registry-source registry)]
     (cond
       (registry-entry? policy)
       (policy-from-entry policy)

       (and (nil? policy) registry*)
       (or (some-> (active-registry-entry registry*) policy-from-entry)
           (default-retrieval-policy))

       (policy-selector-map? policy)
       (if registry*
         (or (some-> (resolve-registry-entry registry*
                                             (:policy_id policy)
                                             (:version policy))
                     policy-from-entry)
             (throw (ex-info "retrieval policy not found in registry"
                             {:type :invalid_request
                              :message "retrieval policy not found in registry"})))
         (normalize-policy policy))

       (map? policy)
       (normalize-policy policy)

       :else
       (default-retrieval-policy)))))

(defn upsert-registry-entry [registry entry]
  (let [entry* (registry-entry entry (merge {:state (:state entry)}
                                            (select-keys entry registry-metadata-keys)))
        entries (list-registry-entries registry)
        replaced? (volatile! false)
        policies (mapv (fn [existing]
                         (if (and (= (:policy_id existing) (:policy_id entry*))
                                  (= (:version existing) (:version entry*)))
                           (do (vreset! replaced? true)
                               (merge existing entry*))
                           existing))
                       entries)]
    (assoc (normalize-registry registry)
           :policies (if @replaced?
                       policies
                       (conj policies entry*)))))

(defn set-entry-state [registry policy-id version next-state]
  (let [state* (normalize-lifecycle-state next-state)]
    (update (normalize-registry registry)
            :policies
            (fn [entries]
              (mapv (fn [entry]
                      (if (and (= (:policy_id entry) (str policy-id))
                               (= (:version entry) (str version)))
                        (assoc entry :state state*)
                        entry))
                    entries)))))

(defn policy-summary [policy]
  (let [policy* (normalize-policy policy)]
    {:policy_id (:policy_id policy*)
     :version (:version policy*)}))

(defn weight [policy code]
  (get-in (normalize-policy policy) [:weights (keyword code)] 0))

(defn cap [policy cap-k]
  (get-in (normalize-policy policy) [:caps cap-k]))

(defn threshold [policy threshold-k]
  (get-in (normalize-policy policy) [:thresholds threshold-k]))

(defn confidence-score [policy level]
  (get-in (normalize-policy policy) [:confidence_scores (keyword level)] 0.30))

(defn confidence-level<=? [left right]
  (<= (get confidence-level-rank (str left) -1)
      (get confidence-level-rank (str right) -1)))

(defn min-confidence-level
  ([levels]
   (reduce min-confidence-level "high" levels))
  ([left right]
   (if (confidence-level<=? left right)
     (str left)
     (str right))))

(defn raw-fetch-threshold [policy threshold-k]
  (get-in (normalize-policy policy) [:raw_fetch threshold-k]))

(defn rank-band [policy score]
  (let [policy* (normalize-policy policy)]
    (cond
      (>= score (get-in policy* [:thresholds :top_authority_min])) "top_authority"
      (>= score (get-in policy* [:thresholds :useful_support_min])) "useful_support"
      (>= score (get-in policy* [:thresholds :exploratory_min])) "exploratory"
      :else "below_threshold_noise")))

(defn- coverage-level [selected]
  (let [total (count selected)
        fallback (count (filter #(= "fallback" (:parser_mode %)) selected))]
    (cond
      (zero? total) "unknown"
      (zero? fallback) "full"
      (< fallback total) "mixed"
      :else "fallback_only")))

(defn- unit-language [index unit]
  (or (:language unit)
      (get-in index [:files (:path unit) :language])))

(defn- language-strength [language]
  (registry/strength-for-language language))

(defn- selected-language-strengths [index units]
  (let [by-language (->> units
                         (group-by #(unit-language index %))
                         (remove (comp nil? key))
                         (sort-by key))]
    (into {}
          (map (fn [[language grouped-units]]
                 [language
                  (if (every? #(= "fallback" (:parser_mode %)) grouped-units)
                    "low"
                    (language-strength language))]))
          by-language)))

(defn- confidence-ceiling [coverage-level selected-language-strengths]
  (let [language-ceiling (if (seq selected-language-strengths)
                           (min-confidence-level (vals selected-language-strengths))
                           "low")]
    (case coverage-level
      "full" language-ceiling
      "mixed" (min-confidence-level language-ceiling "medium")
      "fallback_only" "low"
      "unknown" "low"
      language-ceiling)))

(defn capability-summary
  ([index]
   (capability-summary index (idx/all-units index)))
  ([index units]
   (let [units* (vec units)
         index-languages (->> (vals (:files index)) (keep :language) distinct sort vec)
         selected-languages (->> units* (keep #(unit-language index %)) distinct sort vec)
         parser-modes (->> units* (keep :parser_mode) distinct sort vec)
         fallback-unit-count (count (filter #(= "fallback" (:parser_mode %)) units*))
         coverage (coverage-level units*)
         strong-languages (->> units*
                               (remove #(= "fallback" (:parser_mode %)))
                               (keep #(unit-language index %))
                               distinct
                               sort
                               vec)
         strengths (selected-language-strengths index units*)]
     {:index_languages index-languages
      :selected_languages selected-languages
      :parser_modes parser-modes
      :coverage_level coverage
      :fallback_unit_count fallback-unit-count
      :selected_unit_count (count units*)
      :strong_languages strong-languages
      :selected_language_strengths strengths
      :confidence_ceiling (confidence-ceiling coverage strengths)
      :index_age_seconds (get-in index [:index_lifecycle :age_seconds] 0)
      :index_stale (boolean (get-in index [:index_lifecycle :stale]))
      :snapshot_pinned (boolean (get-in index [:index_lifecycle :snapshot_pinned]))
      :index_provenance_source (get-in index [:index_lifecycle :provenance :source])
      :index_snapshot_id (:snapshot_id index)})))

;; ---------------------------------------------------------------------------
;; Online policy transition domain functions
;; ---------------------------------------------------------------------------

(def promotion-gate-version "1.0")

(defn- canonical-value [value]
  (cond
    (map? value)
    (into (sorted-map-by (fn [left right]
                           (compare (pr-str left) (pr-str right))))
          (map (fn [[key nested]]
                 [key (canonical-value nested)]))
          value)

    (set? value)
    (->> value
         (map canonical-value)
         (sort-by pr-str)
         vec)

    (sequential? value)
    (mapv canonical-value value)

    :else value))

(defn content-digest [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (pr-str (canonical-value value))
                         java.nio.charset.StandardCharsets/UTF_8)]
    (->> (.digest digest bytes)
         (map #(format "%02x" (bit-and 0xff %)))
         (apply str))))

(defn policy-entry-digest [entry]
  (content-digest
   (select-keys entry
                [:policy_id :version :state :policy :governance])))

(defn registry-revision [registry]
  (let [registry* (normalize-registry registry)]
    (content-digest
     {:schema_version (:schema_version registry*)
      :policies (mapv #(select-keys %
                                    [:policy_id
                                     :version
                                     :state
                                     :policy
                                     :governance])
                      (:policies registry*))})))

(defn- decision-expired? [decision]
  (when-let [expires-at (:expires_at decision)]
    (try
      (.isBefore (java.time.Instant/parse expires-at)
                 (java.time.Instant/now))
      (catch Exception _
        true))))

(defn- valid-approval? [entry approval-id decision-id]
  (boolean
   (some (fn [approval]
           (and (= approval-id (:approval_id approval))
                (= decision-id (:decision_id approval))
                (= "policy_approver"
                   (some-> (:role approval) name))))
         (:approvals entry))))

(defn record-policy-approval
  "Record an offline approval bound to the current promotion decision."
  [registry
   {:keys [policy_id
           version
           decision_id
           approval_id
           actor_id
           role
           approved_at]}]
  (let [registry* (normalize-registry registry)
        entry (resolve-registry-entry registry* policy_id version)
        current-decision-id (get-in entry
                                    [:shadow_review
                                     :promotion_decision
                                     :decision_id])
        role-name (some-> role name)]
    (cond
      (not entry)
      {:ok? false
       :error-type :policy_not_found
       :message "candidate policy not found in registry"}

      (or (not (seq decision_id))
          (not= decision_id current-decision-id))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "approval does not target the current promotion decision"}

      (or (not (seq approval_id))
          (not (seq actor_id))
          (not= "policy_approver" role-name)
          (not (seq approved_at)))
      {:ok? false
       :error-type :invalid_request
       :message "approval_id, actor_id, policy_approver role, and approved_at are required"}

      :else
      (let [approval {:approval_id approval_id
                      :decision_id decision_id
                      :actor_id actor_id
                      :role role-name
                      :approved_at approved_at}
            approvals (->> (conj (vec (:approvals entry)) approval)
                           (reduce (fn [by-id record]
                                     (assoc by-id
                                            (:approval_id record)
                                            record))
                                   {})
                           vals
                           (sort-by :approval_id)
                           vec)]
        {:ok? true
         :registry
         (upsert-registry-entry
          registry*
          (assoc entry :approvals approvals))}))))

(defn promote-reviewed-policy
  "Validate an offline promotion decision and return the corresponding pure
   registry transition. Persistence and publication belong to the runtime edge."
  [{:keys [registry policy_id version decision_id approval_id]}]
  (let [registry* (normalize-registry registry)
        entry (resolve-registry-entry registry* policy_id version)
        baseline (active-registry-entry registry*)
        review (:shadow_review entry)
        decision (:promotion_decision review)
        expected-candidate (:candidate decision)
        expected-baseline (:baseline decision)
        outcome (:outcome decision)]
    (cond
      (not entry)
      {:ok? false
       :error-type :policy_not_found
       :message "candidate policy not found in registry"}

      (not= "shadow" (:state entry))
      {:ok? false
       :error-type :policy_not_eligible
       :message "candidate policy must be in shadow state to promote"}

      (blocked? entry)
      {:ok? false
       :error-type :policy_blocked
       :message "policy promotion is blocked by governance configuration"}

      (or (not (seq decision_id))
          (not (map? decision)))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "a persisted offline promotion decision is required"}

      (not= decision_id (:decision_id decision))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "decision_id does not match the current shadow review"}

      (not= promotion-gate-version (:gate_version decision))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "promotion decision uses an unsupported gate version"}

      (decision-expired? decision)
      {:ok? false
       :error-type :stale_promotion_decision
       :message "promotion decision has expired"}

      (not (:eligible_for_promotion review))
      {:ok? false
       :error-type :policy_not_eligible
       :message "policy has not passed the offline promotion gates"}

      (not= {:policy_id (:policy_id entry)
             :version (:version entry)}
            (select-keys expected-candidate [:policy_id :version]))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "promotion decision targets a different candidate"}

      (not= (:digest expected-candidate)
            (policy-entry-digest entry))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "candidate policy changed after offline review"}

      (or (not baseline)
          (not= {:policy_id (:policy_id baseline)
                 :version (:version baseline)}
                (select-keys expected-baseline [:policy_id :version]))
          (not= (:digest expected-baseline)
                (policy-entry-digest baseline)))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "active baseline changed after offline review"}

      (not= (:registry_revision decision)
            (registry-revision registry*))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "policy registry changed after offline review"}

      (= "promotion_denied" outcome)
      {:ok? false
       :error-type :policy_not_eligible
       :message "offline promotion decision denied this candidate"}

      (and (manual-approval-required? entry)
           (not (valid-approval? entry approval_id decision_id)))
      {:ok? false
       :error-type :policy_approval_required
       :message "a matching policy_approver approval record is required"}

      (and (manual-approval-required? entry)
           (not= "approval_required" outcome))
      {:ok? false
       :error-type :stale_promotion_decision
       :message "promotion decision does not match the approval tier"}

      (and (not (manual-approval-required? entry))
           (not= "promotion_allowed" outcome))
      {:ok? false
       :error-type :policy_not_eligible
       :message "offline promotion decision does not allow promotion"}

      :else
      (let [retired (set-entry-state registry*
                                     (:policy_id baseline)
                                     (:version baseline)
                                     "retired")
            promoted (set-entry-state retired policy_id version "active")]
        {:ok? true
         :decision_id decision_id
         :registry promoted}))))

(defn retire-policy
  "Return the pure registry transition for retiring a policy. The online retire
   surface only decommissions non-active candidates: the active baseline is
   retired exclusively by `promote-reviewed-policy`, which atomically swaps in a
   replacement, so a standalone retire can never leave the registry without an
   active policy. Retiring an already-retired entry is rejected as an idempotent
   no-op."
  [{:keys [registry policy_id version]}]
  (let [registry* (normalize-registry registry)
        entry (resolve-registry-entry registry* policy_id version)]
    (cond
      (not entry)
      {:ok? false
       :error-type :policy_not_found
       :message "policy not found in registry"}

      (= "active" (:state entry))
      {:ok? false
       :error-type :policy_not_eligible
       :message "active policy is retired only by promoting a replacement"}

      (= "retired" (:state entry))
      {:ok? false
       :error-type :policy_not_eligible
       :message "policy is already retired"}

      :else
      {:ok? true
       :registry (set-entry-state registry*
                                  policy_id
                                  version
                                  "retired")})))
