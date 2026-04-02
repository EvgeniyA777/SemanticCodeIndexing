(ns semidx.runtime.snapshot-diff
  (:require [clojure.string :as str]
            [semidx.runtime.index :as idx]
            [semidx.runtime.semantic-id :as semantic-id]
            [semidx.runtime.storage :as storage]))

(def ^:private api-version "1.0")

(defn- invalid-request [message details]
  (throw (ex-info message
                  {:type :invalid_request
                   :message message
                   :details details})))

(defn- normalize-paths [paths]
  (some->> paths
           (map #(str/replace (str %) "\\" "/"))
           (remove str/blank?)
           distinct
           vec))

(defn- resolve-storage-adapter [index opts]
  (or (:storage opts)
      (:storage_adapter (meta index))))

(defn- resolve-baseline-snapshot-id [index opts]
  (or (:baseline_snapshot_id opts)
      (get-in index [:index_lifecycle :provenance :parent_snapshot_id])))

(defn- load-baseline-index [index opts]
  (let [baseline-snapshot-id (resolve-baseline-snapshot-id index opts)]
    (when-not (seq baseline-snapshot-id)
      (invalid-request "baseline snapshot_id is required because the current index has no parent snapshot"
                       {:current_snapshot_id (:snapshot_id index)}))
    (if (= (str baseline-snapshot-id) (str (:snapshot_id index)))
      index
      (let [storage-adapter (resolve-storage-adapter index opts)]
        (when-not storage-adapter
          (invalid-request "snapshot diff requires a storage adapter to load the baseline snapshot"
                           {:baseline_snapshot_id baseline-snapshot-id
                            :current_snapshot_id (:snapshot_id index)}))
        (or (storage/load-index-by-snapshot storage-adapter (:root_path index) baseline-snapshot-id)
            (invalid-request "baseline snapshot was not found"
                             {:baseline_snapshot_id baseline-snapshot-id
                              :current_snapshot_id (:snapshot_id index)}))))))

(defn- ordered-units [index]
  (->> (idx/all-units index)
       (map semantic-id/enrich-unit)
       (sort-by (juxt :path :start_line :unit_id))
       vec))

(defn- public-shape-fingerprint [unit]
  (semantic-id/public-shape-fingerprint unit))

(defn- classify-same-slot [baseline current]
  (let [same-public-shape? (= (public-shape-fingerprint baseline)
                              (public-shape-fingerprint current))
        same-fingerprint? (= (:semantic_fingerprint baseline)
                             (:semantic_fingerprint current))]
    (cond
      (and same-public-shape? same-fingerprint?) :unchanged
      same-public-shape? :implementation_changed
      :else :meaning_changed)))

(defn- usage-key [unit]
  [(:semantic_id unit) (:semantic_fingerprint unit)])

(defn- one-to-one-matches [baseline-units current-units]
  (let [baseline-groups (group-by :semantic_fingerprint baseline-units)
        current-groups (group-by :semantic_fingerprint current-units)]
    (reduce-kv
     (fn [acc fingerprint baseline-group]
       (let [current-group (get current-groups fingerprint)
             match-count (min (count baseline-group) (count current-group))]
         (if (pos? match-count)
           (into acc
                 (map vector
                      (take match-count (sort-by usage-key baseline-group))
                      (take match-count (sort-by usage-key current-group))))
           acc)))
     []
     baseline-groups)))

(defn- classify-change [baseline current]
  {:change_type :moved_or_renamed
   :path (:path current)
   :baseline_path (:path baseline)
   :unit_id (:unit_id current)
   :baseline_unit_id (:unit_id baseline)
   :symbol (:symbol current)
   :baseline_symbol (:symbol baseline)
   :semantic_id (:semantic_id current)
   :baseline_semantic_id (:semantic_id baseline)
   :semantic_fingerprint (:semantic_fingerprint current)
   :classification_basis {:matched_on :semantic_fingerprint
                          :same_public_shape (= (public-shape-fingerprint baseline)
                                                (public-shape-fingerprint current))
                          :baseline_snapshot_slot (:semantic_id baseline)
                          :current_snapshot_slot (:semantic_id current)}})

(defn- same-slot-change [baseline current change-type]
  {:change_type change-type
   :path (:path current)
   :baseline_path (:path baseline)
   :unit_id (:unit_id current)
   :baseline_unit_id (:unit_id baseline)
   :symbol (:symbol current)
   :baseline_symbol (:symbol baseline)
   :semantic_id (:semantic_id current)
   :baseline_semantic_id (:semantic_id baseline)
   :semantic_fingerprint (:semantic_fingerprint current)
   :classification_basis {:matched_on :semantic_id
                          :same_public_shape (= (public-shape-fingerprint baseline)
                                                (public-shape-fingerprint current))
                          :same_fingerprint (= (:semantic_fingerprint baseline)
                                               (:semantic_fingerprint current))}})

(defn- added-change [unit]
  {:change_type :added
   :path (:path unit)
   :baseline_path nil
   :unit_id (:unit_id unit)
   :baseline_unit_id nil
   :symbol (:symbol unit)
   :baseline_symbol nil
   :semantic_id (:semantic_id unit)
   :baseline_semantic_id nil
   :semantic_fingerprint (:semantic_fingerprint unit)
   :classification_basis {:matched_on :none}})

(defn- removed-change [unit]
  {:change_type :removed
   :path nil
   :baseline_path (:path unit)
   :unit_id nil
   :baseline_unit_id (:unit_id unit)
   :symbol nil
   :baseline_symbol (:symbol unit)
   :semantic_id nil
   :baseline_semantic_id (:semantic_id unit)
   :semantic_fingerprint nil
   :classification_basis {:matched_on :none}})

(defn- filter-changes-by-paths [changes paths]
  (if (seq paths)
    (let [path-set (set paths)]
      (->> changes
           (filter (fn [{:keys [path baseline_path]}]
                     (or (contains? path-set path)
                         (contains? path-set baseline_path))))
           vec))
    changes))

(defn- deterministic-order [changes]
  (->> changes
       (sort-by (juxt (comp str :change_type)
                      #(or (:path %) (:baseline_path %) "")
                      #(or (:baseline_path %) "")
                      #(or (:symbol %) (:baseline_symbol %) "")
                      #(or (:unit_id %) (:baseline_unit_id %) "")))
       vec))

(defn- summary [changes]
  (let [frequencies* (frequencies (map :change_type changes))]
    {:change_counts {:added (get frequencies* :added 0)
                     :removed (get frequencies* :removed 0)
                     :moved_or_renamed (get frequencies* :moved_or_renamed 0)
                     :implementation_changed (get frequencies* :implementation_changed 0)
                     :meaning_changed (get frequencies* :meaning_changed 0)
                     :unchanged (get frequencies* :unchanged 0)}
     :total_changes (count changes)}))

(defn snapshot-diff
  ([index]
   (snapshot-diff index {}))
  ([index opts]
   (let [baseline-index (load-baseline-index index opts)
         baseline-units (ordered-units baseline-index)
         current-units (ordered-units index)
         baseline-by-slot (group-by :semantic_id baseline-units)
         current-by-slot (group-by :semantic_id current-units)
         same-slot-ids (->> (keys baseline-by-slot)
                            (filter #(contains? current-by-slot %))
                            sort
                            vec)
         same-slot-changes (mapcat (fn [semantic-id]
                                     (let [baseline-group (sort-by usage-key (get baseline-by-slot semantic-id))
                                           current-group (sort-by usage-key (get current-by-slot semantic-id))
                                           match-count (min (count baseline-group) (count current-group))
                                           matched (map (fn [baseline current]
                                                          (same-slot-change baseline current (classify-same-slot baseline current)))
                                                        (take match-count baseline-group)
                                                        (take match-count current-group))
                                           unmatched-baseline (drop match-count baseline-group)
                                           unmatched-current (drop match-count current-group)]
                                       (concat matched
                                               (map removed-change unmatched-baseline)
                                               (map added-change unmatched-current))))
                                   same-slot-ids)
         baseline-only (->> baseline-units
                            (remove #(contains? current-by-slot (:semantic_id %)))
                            vec)
         current-only (->> current-units
                           (remove #(contains? baseline-by-slot (:semantic_id %)))
                           vec)
         rename-matches (one-to-one-matches baseline-only current-only)
         matched-baseline-ids (set (map (comp :unit_id first) rename-matches))
         matched-current-ids (set (map (comp :unit_id second) rename-matches))
         rename-changes (map (fn [[baseline current]]
                               (classify-change baseline current))
                             rename-matches)
         added-changes (->> current-only
                            (remove #(contains? matched-current-ids (:unit_id %)))
                            (map added-change))
         removed-changes (->> baseline-only
                              (remove #(contains? matched-baseline-ids (:unit_id %)))
                              (map removed-change))
         include-unchanged? (boolean (:include_unchanged? opts))
         paths (normalize-paths (:paths opts))
         all-changes (concat same-slot-changes rename-changes added-changes removed-changes)
         changes* (->> all-changes
                       (remove #(and (= :unchanged (:change_type %))
                                     (not include-unchanged?)))
                       vec)
         filtered (filter-changes-by-paths changes* paths)
         ordered (deterministic-order filtered)]
     {:api_version api-version
      :baseline_snapshot_id (:snapshot_id baseline-index)
      :current_snapshot_id (:snapshot_id index)
      :summary (summary ordered)
      :changes ordered})))
