(ns semantic-code-indexing.runtime.index
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.runtime.adapters :as adapters]))

(defn- now-iso []
  (-> (java.time.Instant/now) str))

(defn- uuid []
  (str (java.util.UUID/randomUUID)))

(defn- relative-path [root file]
  (let [root-path (.toPath (io/file root))
        file-path (.toPath (io/file file))]
    (-> (.relativize root-path file-path)
        (.normalize)
        (str))))

(defn- discover-source-files [root-path]
  (->> (file-seq (io/file root-path))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (map #(relative-path root-path %))
       (filter adapters/source-path?)
       sort
       vec))

(defn- normalize-paths [paths]
  (->> paths
       (map #(str/replace (str %) "\\\\" "/"))
       distinct
       vec))

(defn- index-by [k coll]
  (reduce (fn [acc x] (update acc (k x) (fnil conj []) (:unit_id x))) {} coll))

(defn- build-symbol-index [units]
  (reduce
   (fn [acc u]
     (if-let [sym (:symbol u)]
       (update acc sym (fnil conj []) (:unit_id u))
       acc))
   {}
   units))

(defn- parse-files [root-path paths]
  (reduce
   (fn [acc path]
     (let [parsed (adapters/parse-file root-path path)
           file-rec {:path path
                     :language (:language parsed)
                     :module (:module parsed)
                     :imports (:imports parsed)
                     :parser_mode (:parser_mode parsed)
                     :diagnostics (:diagnostics parsed)}]
       (-> acc
           (update :files assoc path file-rec)
           (update :units into (:units parsed))
           (update :diagnostics into
                   (map (fn [d] (assoc d :path path)) (:diagnostics parsed))))))
   {:files {} :units [] :diagnostics []}
   paths))

(defn- match-call-token? [token symbol]
  (or (= token symbol)
      (and (str/includes? symbol "/") (= token (last (str/split symbol #"/"))))
      (and (str/includes? symbol "#") (= token (last (str/split symbol #"#"))))
      (and (str/includes? token "/") (= symbol token))))

(defn- build-callers-index [units]
  (let [units-by-id (into {} (map (juxt :unit_id identity) units))
        symbol-candidates (->> units (keep :symbol) distinct vec)]
    (reduce
     (fn [acc u]
       (reduce
        (fn [acc2 token]
          (let [targets (->> units
                             (filter (fn [cand]
                                       (some-> cand :symbol (match-call-token? token))))
                             (map :unit_id)
                             distinct)]
            (reduce (fn [a t] (update a t (fnil conj #{}) (:unit_id u))) acc2 targets)))
        acc
        (:calls (get units-by-id (:unit_id u)))))
     {}
     units)))

(defn- build-module-dependents [files]
  (reduce
   (fn [acc {:keys [module imports]}]
     (if module
       (reduce (fn [a imp] (update a imp (fnil conj #{}) module)) acc imports)
       acc))
   {}
   (vals files)))

(defn- build-index-state [root-path files-data]
  (let [units (:units files-data)
        units-by-id (into {} (map (juxt :unit_id identity) units))]
    {:root_path root-path
     :snapshot_id (uuid)
     :indexed_at (now-iso)
     :files (:files files-data)
     :diagnostics (:diagnostics files-data)
     :units units-by-id
     :unit_order (mapv :unit_id units)
     :symbol_index (build-symbol-index units)
     :path_index (index-by :path units)
     :module_index (index-by :module units)
     :callers_index (build-callers-index units)
     :module_dependents (build-module-dependents (:files files-data))}))

(defn create-index
  [{:keys [root_path paths] :or {root_path "."}}]
  (let [discovered (if (seq paths)
                     (normalize-paths paths)
                     (discover-source-files root_path))
        files-data (parse-files root_path discovered)]
    (build-index-state root_path files-data)))

(defn- remove-paths-from-index [index paths]
  (let [path-set (set paths)
        remaining-units (->> (:unit_order index)
                             (map #(get (:units index) %))
                             (remove #(contains? path-set (:path %)))
                             vec)
        remaining-files (apply dissoc (:files index) paths)
        remaining-diagnostics (vec (remove #(contains? path-set (:path %)) (:diagnostics index)))]
    {:files remaining-files
     :units remaining-units
     :diagnostics remaining-diagnostics}))

(defn update-index
  [index {:keys [changed_paths] :or {changed_paths []}}]
  (if (empty? changed_paths)
    (create-index {:root_path (:root_path index)})
    (let [paths (normalize-paths changed_paths)
          base (remove-paths-from-index index paths)
          parsed (parse-files (:root_path index) paths)
          merged-files (merge (:files base) (:files parsed))
          merged-units (vec (concat (:units base) (:units parsed)))
          merged-diags (vec (concat (:diagnostics base) (:diagnostics parsed)))]
      (build-index-state (:root_path index)
                         {:files merged-files
                          :units merged-units
                          :diagnostics merged-diags}))))

(defn unit-by-id [index unit-id]
  (get (:units index) unit-id))

(defn units-by-ids [index unit-ids]
  (->> unit-ids (map #(unit-by-id index %)) (remove nil?) vec))

(defn all-units [index]
  (units-by-ids index (:unit_order index)))

(defn units-for-path [index path]
  (units-by-ids index (get (:path_index index) path [])))

(defn units-for-module [index module]
  (units-by-ids index (get (:module_index index) module [])))

(defn units-for-symbol [index symbol]
  (units-by-ids index (get (:symbol_index index) symbol [])))

(defn repo-map
  ([index] (repo-map index {:max_files 20 :max_modules 20}))
  ([index {:keys [max_files max_modules] :or {max_files 20 max_modules 20}}]
   (let [files (->> (vals (:files index))
                    (sort-by :path)
                    (take max_files)
                    (map :path)
                    vec)
         modules (->> (keys (:module_index index))
                      (remove nil?)
                      sort
                      (take max_modules)
                      vec)]
     {:snapshot_id (:snapshot_id index)
      :indexed_at (:indexed_at index)
      :files files
      :modules modules
      :summary (str "Indexed " (count (:files index)) " files and " (count (:units index)) " units.")})))
