(ns semantic-code-indexing.runtime.retrieval-policy
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.runtime.index :as idx]))

(def ^:private default-policy
  {:policy_id "heuristic_v1"
   :version "2026-03-10"
   :weights {:exact_target_resolved 140
             :target_path_match 95
             :diff_overlap_direct 90
             :target_module_match 70
             :target_test_match 50
             :dispatch_value_match 25
             :hint_preferred_path 15
             :hint_preferred_module 10
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

(def ^:private policy-tuning-keys
  #{:weights :caps :thresholds :confidence_scores :raw_fetch})

(def ^:private registry-metadata-keys
  [:notes :created_at :updated_at :activated_at :retired_at :shadow_review])

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
     :policies policies}))

(defn load-registry [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))]
    (normalize-registry (edn/read rdr))))

(defn write-registry! [path registry]
  (spit path (pr-str (normalize-registry registry))))

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

(defn capability-summary
  ([index]
   (capability-summary index (idx/all-units index)))
  ([index units]
   (let [units* (vec units)
         unit-language (fn [u] (or (:language u)
                                   (get-in index [:files (:path u) :language])))
         index-languages (->> (vals (:files index)) (keep :language) distinct sort vec)
         selected-languages (->> units* (keep unit-language) distinct sort vec)
         parser-modes (->> units* (keep :parser_mode) distinct sort vec)
         fallback-unit-count (count (filter #(= "fallback" (:parser_mode %)) units*))
         strong-languages (->> units*
                               (remove #(= "fallback" (:parser_mode %)))
                               (keep unit-language)
                               distinct
                               sort
                               vec)]
     {:index_languages index-languages
      :selected_languages selected-languages
      :parser_modes parser-modes
      :coverage_level (coverage-level units*)
      :fallback_unit_count fallback-unit-count
      :selected_unit_count (count units*)
      :strong_languages strong-languages
      :index_snapshot_id (:snapshot_id index)})))
