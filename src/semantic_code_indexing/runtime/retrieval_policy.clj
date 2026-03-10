(ns semantic-code-indexing.runtime.retrieval-policy
  (:require [clojure.string :as str]
            [semantic-code-indexing.runtime.index :as idx]))

(def ^:private default-policy
  {:policy_id "heuristic_v1"
   :version "2026-03-10"
   :weights {:exact_target_resolved 140
             :target_path_match 95
             :diff_overlap_direct 90
             :target_module_match 70
             :target_test_match 50
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

(defn default-retrieval-policy []
  default-policy)

(defn normalize-policy [policy]
  (let [policy* (or policy {})]
    (-> default-policy
        (merge (select-keys policy* [:policy_id :version]))
        (update :weights merge (:weights policy*))
        (update :caps merge (:caps policy*))
        (update :thresholds merge (:thresholds policy*))
        (update :confidence_scores merge (:confidence_scores policy*))
        (update :raw_fetch merge (:raw_fetch policy*)))))

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
