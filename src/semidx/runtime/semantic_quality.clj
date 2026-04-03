(ns semidx.runtime.semantic-quality
  (:require [clojure.string :as str]
            [semidx.runtime.snapshot-diff :as snapshot-diff]))

(def ^:private api-version "1.0")
(def ^:private stable-slot-types #{:unchanged :implementation_changed :meaning_changed})
(def ^:private impl-vs-meaning-types #{:implementation_changed :meaning_changed})
(def ^:private review-change-fields
  [:change_type
   :path
   :baseline_path
   :symbol
   :baseline_symbol
   :unit_id
   :baseline_unit_id
   :semantic_id
   :baseline_semantic_id])
(def ^:private identity-fields
  [:path
   :baseline_path
   :symbol
   :baseline_symbol
   :unit_id
   :baseline_unit_id
   :semantic_id
   :baseline_semantic_id])
(def ^:private default-thresholds
  {:identity_stability_rate 0.95
   :move_rename_recovery_rate 0.95
   :implementation_vs_meaning_accuracy 0.95
   :expected_change_match_rate 0.95
   :unmatched_rate 0.05})
(def ^:private metric-specs
  [{:metric :identity_stability_rate
    :direction :at_least}
   {:metric :move_rename_recovery_rate
    :direction :at_least}
   {:metric :implementation_vs_meaning_accuracy
    :direction :at_least}
   {:metric :expected_change_match_rate
    :direction :at_least}
   {:metric :unmatched_rate
    :direction :at_most}])

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn- rate [numerator denominator]
  (if (pos? (long (or denominator 0)))
    (/ (double numerator) (double denominator))
    nil))

(defn- normalize-change-type [value]
  (cond
    (keyword? value) value
    (string? value) (-> value str/lower-case (str/replace "-" "_") keyword)
    :else value))

(defn- normalize-change [change]
  (cond-> change
    (contains? change :change_type) (update :change_type normalize-change-type)))

(defn- normalize-expected-change [expected]
  (-> expected
      normalize-change
      (select-keys review-change-fields)))

(defn- supplied-identity-fields [expected]
  (->> identity-fields
       (filter #(contains? expected %))
       vec))

(defn- matches-on-fields? [actual expected fields]
  (every? (fn [field]
            (= (get actual field) (get expected field)))
          fields))

(defn- identity-match? [actual expected]
  (let [fields (supplied-identity-fields expected)]
    (and (seq fields)
         (matches-on-fields? actual expected fields))))

(defn- exact-match? [actual expected]
  (and (= (:change_type actual) (:change_type expected))
       (if (seq (supplied-identity-fields expected))
         (identity-match? actual expected)
         true)))

(defn- take-first-match [pred coll]
  (loop [prefix []
         remaining (seq coll)]
    (if-let [item (first remaining)]
      (if (pred item)
        [item (vec (concat prefix (rest remaining)))]
        (recur (conj prefix item) (next remaining)))
      [nil (vec prefix)])))

(defn- review-sample [change]
  (select-keys change review-change-fields))

(defn- identity-stable? [actual]
  (= :semantic_id (get-in actual [:classification_basis :matched_on])))

(defn- expected-change [item]
  (if (contains? item :expected)
    (:expected item)
    item))

(defn- match-expected-changes [expected-changes actual-changes]
  (loop [expected-remaining (vec expected-changes)
         actual-remaining (vec actual-changes)
         exact-matches []
         classification-mismatches []
         missing-expected []]
    (if-let [expected (first expected-remaining)]
      (let [[exact actual-after-exact] (take-first-match #(exact-match? % expected) actual-remaining)]
        (if exact
          (recur (subvec expected-remaining 1)
                 actual-after-exact
                 (conj exact-matches {:expected expected :actual exact})
                 classification-mismatches
                 missing-expected)
          (let [[identity actual-after-identity] (take-first-match #(identity-match? % expected) actual-remaining)]
            (if identity
              (recur (subvec expected-remaining 1)
                     actual-after-identity
                     exact-matches
                     (conj classification-mismatches {:expected expected :actual identity})
                     missing-expected)
              (recur (subvec expected-remaining 1)
                     actual-remaining
                     exact-matches
                     classification-mismatches
                     (conj missing-expected expected))))))
      {:exact_matches exact-matches
       :classification_mismatches classification-mismatches
       :missing_expected missing-expected
       :unexpected_actual actual-remaining})))

(defn- case-metrics [matching actual-changes]
  (let [exact-matches (:exact_matches matching)
        classification-mismatches (:classification_mismatches matching)
        missing-expected (:missing_expected matching)
        unexpected-actual (:unexpected_actual matching)
        expected-total (+ (count exact-matches)
                          (count classification-mismatches)
                          (count missing-expected))
        move-total (count (filter #(= :moved_or_renamed (:change_type (expected-change %)))
                                  (concat exact-matches classification-mismatches missing-expected)))
        move-hit (count (filter #(= :moved_or_renamed (:change_type (expected-change %))) exact-matches))
        impl-total (count (filter #(contains? impl-vs-meaning-types (:change_type (expected-change %)))
                                  (concat exact-matches classification-mismatches missing-expected)))
        impl-hit (count (filter #(contains? impl-vs-meaning-types (:change_type (expected-change %))) exact-matches))
        stable-cases (filter #(contains? stable-slot-types (:change_type (expected-change %)))
                             (concat exact-matches classification-mismatches))
        stable-total (+ (count stable-cases)
                        (count (filter #(contains? stable-slot-types (:change_type (expected-change %))) missing-expected)))
        stable-hit (count (filter #(identity-stable? (:actual %)) stable-cases))
        actual-total (count actual-changes)
        exact-count (count exact-matches)
        mismatch-count (count classification-mismatches)
        missing-count (count missing-expected)
        unexpected-count (count unexpected-actual)]
    {:expected_changes expected-total
     :exact_matches exact-count
     :classification_mismatches mismatch-count
     :missing_expected_changes missing-count
     :unexpected_actual_changes unexpected-count
     :actual_changes actual-total
     :identity_stability_numerator stable-hit
     :identity_stability_denominator stable-total
     :move_rename_numerator move-hit
     :move_rename_denominator move-total
     :implementation_vs_meaning_numerator impl-hit
     :implementation_vs_meaning_denominator impl-total
     :expected_change_match_rate (rate exact-count expected-total)
     :identity_stability_rate (rate stable-hit stable-total)
     :move_rename_recovery_rate (rate move-hit move-total)
     :implementation_vs_meaning_accuracy (rate impl-hit impl-total)
     :unmatched_rate (if (zero? actual-total)
                       0.0
                       (/ (double unexpected-count) (double actual-total)))}))

(defn- review-samples [matching review-case-limit]
  (let [limit* (max 1 (long (or review-case-limit 10)))]
    {:classification_mismatches (->> (:classification_mismatches matching)
                                     (take limit*)
                                     (mapv (fn [{:keys [expected actual]}]
                                             {:expected (review-sample expected)
                                              :actual (review-sample actual)})))
     :missing_expected (->> (:missing_expected matching)
                            (take limit*)
                            (mapv review-sample))
     :unexpected_actual (->> (:unexpected_actual matching)
                             (take limit*)
                             (mapv review-sample))}))

(defn- resolve-diff-result [{:keys [current_index baseline_index] :as case}]
  (let [diff-opts (cond-> {}
                    (:baseline_snapshot_id case) (assoc :baseline_snapshot_id (:baseline_snapshot_id case))
                    (:storage case) (assoc :storage (:storage case))
                    (seq (:paths case)) (assoc :paths (:paths case))
                    (contains? case :include_unchanged?) (assoc :include_unchanged? (:include_unchanged? case)))]
    (cond
      (and baseline_index current_index)
      (snapshot-diff/snapshot-diff-between baseline_index current_index diff-opts)

      current_index
      (snapshot-diff/snapshot-diff current_index diff-opts)

      :else
      (throw (ex-info "semantic quality cases require :current_index or {:baseline_index :current_index}"
                      {:type :invalid_request
                       :message "semantic quality cases require :current_index or {:baseline_index :current_index}"})))))

(defn- case-report [{:keys [case_id expected_changes] :as case} review-case-limit]
  (let [diff-result (resolve-diff-result case)
        actual-changes (mapv normalize-change (:changes diff-result))
        expected* (mapv normalize-expected-change (or expected_changes []))
        matching (match-expected-changes expected* actual-changes)
        metrics (case-metrics matching actual-changes)]
    {:case_id (or case_id
                  (:baseline_snapshot_id diff-result)
                  (:current_snapshot_id diff-result))
     :baseline_snapshot_id (:baseline_snapshot_id diff-result)
     :current_snapshot_id (:current_snapshot_id diff-result)
     :diff_summary (:summary diff-result)
     :metrics metrics
     :review_samples (review-samples matching review-case-limit)}))

(defn- aggregate-case-metrics [case-reports]
  (reduce (fn [acc report]
            (let [metrics (:metrics report)]
              (-> acc
                  (update :expected_changes + (long (or (:expected_changes metrics) 0)))
                  (update :exact_matches + (long (or (:exact_matches metrics) 0)))
                  (update :classification_mismatches + (long (or (:classification_mismatches metrics) 0)))
                  (update :missing_expected_changes + (long (or (:missing_expected_changes metrics) 0)))
                  (update :unexpected_actual_changes + (long (or (:unexpected_actual_changes metrics) 0)))
                  (update :actual_changes + (long (or (:actual_changes metrics) 0)))
                  (update :identity_stability_numerator + (long (or (:identity_stability_numerator metrics) 0)))
                  (update :identity_stability_denominator + (long (or (:identity_stability_denominator metrics) 0)))
                  (update :move_rename_numerator + (long (or (:move_rename_numerator metrics) 0)))
                  (update :move_rename_denominator + (long (or (:move_rename_denominator metrics) 0)))
                  (update :implementation_vs_meaning_numerator + (long (or (:implementation_vs_meaning_numerator metrics) 0)))
                  (update :implementation_vs_meaning_denominator + (long (or (:implementation_vs_meaning_denominator metrics) 0))))))
          {:expected_changes 0
           :exact_matches 0
           :classification_mismatches 0
           :missing_expected_changes 0
           :unexpected_actual_changes 0
           :actual_changes 0
           :identity_stability_numerator 0
           :identity_stability_denominator 0
           :move_rename_numerator 0
           :move_rename_denominator 0
           :implementation_vs_meaning_numerator 0
           :implementation_vs_meaning_denominator 0}
          case-reports))

(defn semantic-quality-gate-decision
  ([report]
   (semantic-quality-gate-decision report default-thresholds))
  ([report thresholds]
   (let [metrics (get-in report [:summary :metrics])
         thresholds* (merge default-thresholds thresholds)
         checks (mapv (fn [{:keys [metric direction]}]
                        (let [value (get metrics metric)
                              threshold (get thresholds* metric)
                              applicable? (some? value)
                              passed? (when applicable?
                                        (case direction
                                          :at_least (>= (double value) (double threshold))
                                          :at_most (<= (double value) (double threshold))
                                          false))]
                          {:metric metric
                           :direction direction
                           :threshold threshold
                           :value value
                           :applicable? applicable?
                           :passed? (if applicable? passed? true)}))
                      metric-specs)]
     {:eligible? (every? :passed? checks)
      :checks checks})))

(defn semantic-quality-report [{:keys [cases review_case_limit thresholds]
                                :or {review_case_limit 10}}]
  (let [case-reports (mapv #(case-report % review_case_limit) cases)
        aggregates (aggregate-case-metrics case-reports)
        summary {:cases (count case-reports)
                 :expected_changes (:expected_changes aggregates)
                 :exact_matches (:exact_matches aggregates)
                 :classification_mismatches (:classification_mismatches aggregates)
                 :missing_expected_changes (:missing_expected_changes aggregates)
                 :unexpected_actual_changes (:unexpected_actual_changes aggregates)
                 :actual_changes (:actual_changes aggregates)
                 :metrics {:expected_change_match_rate (rate (:exact_matches aggregates)
                                                            (:expected_changes aggregates))
                           :identity_stability_rate (rate (:identity_stability_numerator aggregates)
                                                          (:identity_stability_denominator aggregates))
                           :move_rename_recovery_rate (rate (:move_rename_numerator aggregates)
                                                            (:move_rename_denominator aggregates))
                           :implementation_vs_meaning_accuracy (rate (:implementation_vs_meaning_numerator aggregates)
                                                                     (:implementation_vs_meaning_denominator aggregates))
                           :unmatched_rate (if (zero? (:actual_changes aggregates))
                                             0.0
                                             (/ (double (:unexpected_actual_changes aggregates))
                                                (double (:actual_changes aggregates))))}
                 :metric_counts {:identity_stability_cases (:identity_stability_denominator aggregates)
                                 :move_rename_cases (:move_rename_denominator aggregates)
                                 :implementation_vs_meaning_cases (:implementation_vs_meaning_denominator aggregates)}}
        report {:schema_version api-version
                :generated_at (now-iso)
                :summary summary
                :cases case-reports
                :thresholds (merge default-thresholds thresholds)}]
    (assoc report :gate_decision (semantic-quality-gate-decision report thresholds))))
