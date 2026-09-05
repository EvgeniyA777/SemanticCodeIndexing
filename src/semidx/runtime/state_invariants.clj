(ns semidx.runtime.state-invariants
  (:require [clojure.string :as str]
            [semidx.runtime.index :as idx]
            [semidx.runtime.query-anchors :as query-anchors]))

(def ^:private output-limit 12)

(defn- unit-reference [unit]
  (select-keys unit [:unit_id :path :symbol]))

(defn- method-name [unit]
  (last (str/split (str (:symbol unit)) #"[#/]")))

(defn- test-unit? [unit]
  (let [path (str (:path unit))]
    (or (= "test" (:kind unit))
        (str/starts-with? path "test/")
        (str/includes? path "/test/")
        (boolean (re-find #"(?:_test|Test)\.[^.]+$" path)))))

(defn- entity-unit? [unit]
  (let [path (str/lower-case (str (:path unit)))
        class-name (str/lower-case (str (:class_name unit)))
        module (str/lower-case (str (:module unit)))]
    (or (boolean (re-find #"(?:^|/)(?:entity|entities|model|models)(?:/|$)" path))
        (str/ends-with? class-name "entity")
        (str/ends-with? class-name "model")
        (str/ends-with? module "entity")
        (str/ends-with? module "model"))))

(defn- writer-unit? [unit]
  (boolean
   (re-find #"(?i)^(?:save|update|disconnect|clear|reset|set)"
            (method-name unit))))

(defn- fixture-helper? [unit entity-class-names]
  (let [candidate-name (method-name unit)
        signature (str/lower-case (str (:signature unit)))]
    (and (boolean (re-find #"(?i)^(?:build|create|make|fixture|sample|given|new)"
                           candidate-name))
         (some #(str/includes? signature %) entity-class-names))))

(defn- distinct-index-units [units]
  (->> units
       (remove nil?)
       (reduce (fn [by-id unit]
                 (assoc by-id (:unit_id unit) unit))
               (sorted-map))
       vals
       vec))

(defn- direct-impact-neighbors [index selected]
  (let [selected-ids (map :unit_id selected)
        neighbor-ids (mapcat (fn [unit-id]
                               (concat (get (:callers_index index) unit-id #{})
                                       (get (:callees_index index) unit-id #{})))
                             selected-ids)
        imported-modules (->> selected
                              (mapcat :imports)
                              (remove nil?)
                              distinct)]
    (distinct-index-units
     (concat selected
             (map #(idx/unit-by-id index %) neighbor-ids)
             (mapcat #(idx/units-for-module index %) imported-modules)))))

(defn- first-reference-per-path [units]
  (second
   (reduce (fn [[seen references] unit]
             (if (contains? seen (:path unit))
               [seen references]
               [(conj seen (:path unit))
                (conj references (unit-reference unit))]))
           [#{} []]
           (sort-by (juxt :path :unit_id) units))))

(defn- assertion-test-paths
  [index all-units selected entity-units related-test-paths]
  (let [interesting-modules (set (keep :module (concat selected entity-units)))
        entity-class-names (->> entity-units
                                (keep :class_name)
                                (map str/lower-case)
                                set)
        target-ids (map :unit_id (concat selected entity-units))
        caller-ids (->> target-ids
                        (mapcat #(get (:callers_index index) % #{}))
                        set)
        linked-test-paths (->> interesting-modules
                               (mapcat #(get (:test_target_index index) % #{}))
                               (remove nil?)
                               distinct
                               sort)
        referencing-test-paths
        (->> all-units
             (filter test-unit?)
             (filter (fn [unit]
                       (let [imports (set (:imports unit))
                             signature (str/lower-case (str (:signature unit)))]
                         (or (contains? caller-ids (:unit_id unit))
                             (some imports interesting-modules)
                             (some #(str/includes? signature %) entity-class-names)))))
             (map :path)
             (remove nil?)
             distinct
             sort)]
    (->> (concat referencing-test-paths
                 linked-test-paths
                 (sort (remove nil? related-test-paths)))
         distinct
         (take output-limit)
         vec)))

(def ^:private field-limit 24)

(defn- relations-from
  "Typed relations whose source endpoint is `source-id`, read from the snapshot
   relation indexes. Empty when the index carries no relation facts."
  [index source-id]
  (let [rels (:relations index)]
    (->> (get (:relation_forward_index index) source-id)
         (keep #(get rels %)))))

(defn- field-name-from-target [target-key]
  (let [tk (str target-key)]
    (cond
      (str/starts-with? tk "field:") (subs tk (count "field:"))
      (str/includes? tk "#") (last (str/split tk #"#"))
      :else tk)))

(def ^:private state-field-re
  #"(?i)(?:at$|_at$|status|state|token|secret|credential|password|connect|validated|enabled|active|expir|timestamp|refresh)")

(defn- state-bearing-field? [field-name nullable]
  (boolean (or (false? nullable)
               (re-find state-field-re (str field-name)))))

(defn- entity-field-entries
  "One entry per entity candidate that has `structure/declares-field` relations,
   listing its declared fields with annotation/nullability evidence and a
   state-bearing hint. Empty when no field relations are available (ADR-045)."
  [index entity-units]
  (->> entity-units
       (map (fn [u] {:entity (:module u)
                     :path (:path u)
                     :class-node (str (:path u) "::" (:module u))}))
       distinct
       (keep (fn [{:keys [entity path class-node]}]
               (let [decls (->> (relations-from index class-node)
                                (filter #(= "structure/declares-field" (:relation_type %))))]
                 (when (seq decls)
                   (let [fields (->> decls
                                     (reduce
                                      (fn [acc r]
                                        (let [nm (field-name-from-target (:target_key r))]
                                          (if (contains? acc nm)
                                            acc
                                            (let [ev (:evidence_location r)
                                                  nullable (:nullable ev)]
                                              (assoc acc nm
                                                     (cond-> {:name nm
                                                              :state_bearing (state-bearing-field? nm nullable)}
                                                       (some? nullable) (assoc :nullable nullable)
                                                       (seq (:annotations ev)) (assoc :annotations (vec (:annotations ev)))))))))
                                      (sorted-map))
                                     vals
                                     (take field-limit)
                                     vec)]
                     (cond-> {:entity entity :fields fields}
                       path (assoc :path path)))))))
       (take output-limit)
       vec))

(defn- field-write-entries
  "Per selected state-writer, the field names it writes via `dataflow/writes-field`
   relations. Empty when no such relations exist."
  [index writer-units]
  (->> writer-units
       (keep (fn [u]
               (let [writes (->> (relations-from index (:unit_id u))
                                 (filter #(= "dataflow/writes-field" (:relation_type %)))
                                 (map #(field-name-from-target (:target_key %)))
                                 distinct
                                 sort
                                 (take field-limit)
                                 vec)]
                 (when (seq writes)
                   (cond-> {:unit_id (:unit_id u) :writes writes}
                     (:symbol u) (assoc :symbol (:symbol u)))))))
       (take output-limit)
       vec))

(defn- state-bearing-names [entity-fields]
  (->> entity-fields
       (mapcat :fields)
       (filter :state_bearing)
       (map :name)
       distinct
       sort
       vec))

(defn- build-guardrail [entity-fields field-writes]
  (let [bearing (state-bearing-names entity-fields)]
    (cond
      (seq field-writes)
      {:code "state_invariants_verify_field_preservation"
       :recommendation
       (str "Selected writers touch fields ["
            (str/join ", " (->> field-writes (mapcat :writes) distinct sort))
            "]. The entity declares state-bearing fields [" (str/join ", " bearing)
            "]. Verify that state-bearing fields not written by the change are"
            " intentionally preserved, and still read the entity and its tests"
            " before editing.")}

      (seq entity-fields)
      {:code "state_invariants_review_declared_fields"
       :recommendation
       (str "The entity declares state-bearing fields [" (str/join ", " bearing)
            "]. Verify your change preserves the ones it must not touch, and read"
            " the entity/model files, primary service tests, and fixture helpers"
            " before editing.")}

      :else
      {:code "state_invariants_require_whole_file_read"
       :recommendation
       "Read the complete entity/model files, primary service tests, and fixture helpers before editing; field-level write and preservation facts are not available in this packet."})))

(defn assemble
  "Assemble the bounded state-invariant packet from index facts and, when
   available, `structure/declares-field` / `dataflow/writes-field` relations
   (plans/017, ADR-045). Returns nil unless the query is stateful and an
   entity/model candidate is found. `packet_version` is 1.2 when field writes are
   available, 1.1 when only declared fields are, otherwise 1.0 (Slice-1)."
  [index query selected related-test-paths]
  (let [triggered-by (vec (take output-limit
                                (query-anchors/matched-state-terms query)))
        entity-units (when (seq triggered-by)
                       (->> (direct-impact-neighbors index selected)
                            (filter entity-unit?)
                            vec))]
    (when (seq entity-units)
      (let [all-units (idx/all-units index)
            assertion-tests (assertion-test-paths
                             index
                             all-units
                             selected
                             entity-units
                             related-test-paths)
            assertion-test-set (set assertion-tests)
            entity-class-names (->> entity-units
                                    (keep :class_name)
                                    (map str/lower-case)
                                    set)
            fixture-helpers (->> all-units
                                 (filter #(contains? assertion-test-set (:path %)))
                                 (filter #(fixture-helper? % entity-class-names))
                                 (sort-by :unit_id)
                                 (take output-limit)
                                 (mapv unit-reference))
            writer-units (->> selected
                              (filter writer-unit?)
                              (sort-by :unit_id)
                              (take output-limit)
                              vec)
            state-writers (mapv unit-reference writer-units)
            entity-fields (entity-field-entries index entity-units)
            field-writes (field-write-entries index writer-units)]
        (cond-> {:packet_version (cond (seq field-writes) "1.2"
                                       (seq entity-fields) "1.1"
                                       :else "1.0")
                 :triggered_by triggered-by
                 :entity_candidates (->> entity-units
                                         first-reference-per-path
                                         (take output-limit)
                                         vec)
                 :state_writers state-writers
                 :assertion_tests assertion-tests
                 :fixture_helpers fixture-helpers
                 :guardrail (build-guardrail entity-fields field-writes)}
          (seq entity-fields) (assoc :entity_fields entity-fields)
          (seq field-writes) (assoc :field_writes field-writes))))))
