(ns semantic-code-indexing.runtime.index
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semantic-code-indexing.runtime.adapters :as adapters]
            [semantic-code-indexing.runtime.storage :as storage]))

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

(defn- parse-files [root-path paths parser-opts]
  (reduce
   (fn [acc path]
     (let [parsed (adapters/parse-file root-path path parser-opts)
           file-rec {:path path
                     :language (:language parsed)
                     :module (:module parsed)
                     :imports (:imports parsed)
                     :test_target_modules (:test_target_modules parsed)
                     :parser_mode (:parser_mode parsed)
                     :diagnostics (:diagnostics parsed)}]
       (-> acc
           (update :files assoc path file-rec)
           (update :units into (:units parsed))
           (update :diagnostics into
                   (map (fn [d] (assoc d :path path)) (:diagnostics parsed))))))
   {:files {} :units [] :diagnostics []}
   paths))

(defn- lower [s]
  (some-> s str/lower-case))

(defn- symbol-call-tokens [symbol]
  (let [sym (str symbol)
        tokens-a (if (str/includes? sym "/")
                   (let [[prefix name] (str/split sym #"/" 2)]
                     (cond-> #{sym}
                       (seq name) (conj name)
                       (and (seq prefix) (seq name)) (conj (str prefix "." name) (str prefix "/" name))))
                   #{sym})
        tokens (if (str/includes? sym "#")
                 (let [[prefix name] (str/split sym #"#" 2)
                       cls (last (str/split prefix #"\."))]
                   (cond-> tokens-a
                     (seq name) (conj name)
                     (and (seq cls) (seq name)) (conj (str cls "#" name))
                     (and (seq prefix) (seq name)) (conj (str prefix "." name) (str prefix "#" name))))
                 tokens-a)
        with-tail (reduce (fn [acc token]
                            (let [tail (last (str/split token #"[./#]"))]
                              (cond-> acc
                                (seq token) (conj token)
                                (seq tail) (conj tail))))
                          #{}
                          tokens)]
    (->> with-tail
         (mapcat (fn [t] (if-let [l (lower t)] [t l] [t])))
         (remove str/blank?)
         set)))

(defn- build-call-token-index [units]
  (reduce
   (fn [acc u]
     (if-let [sym (:symbol u)]
       (reduce (fn [a token] (update a token (fnil conj #{}) (:unit_id u)))
               acc
               (symbol-call-tokens sym))
       acc))
   {}
   units))

(defn- call-token-scopes [u]
  (let [module (:module u)
        symbol (str (:symbol u))
        prefix-slash (first (str/split symbol #"/" 2))
        prefix-hash (first (str/split symbol #"#" 2))]
    (->> [module prefix-slash prefix-hash]
         (remove str/blank?)
         distinct
         vec)))

(defn- expand-call-token [caller token]
  (let [t (str token)
        tail (last (str/split t #"[./#]"))
        scoped (when-not (re-find #"[./#/]" t)
                 (mapcat (fn [scope] [(str scope "/" t) (str scope "." t) (str scope "#" t)])
                         (call-token-scopes caller)))]
    (->> (concat [t tail] scoped)
         (remove str/blank?)
         (mapcat (fn [x] (if-let [l (lower x)] [x l] [x])))
         distinct
         vec)))

(defn- symbol-scope [symbol]
  (let [s (str symbol)]
    (cond
      (str/includes? s "/") (first (str/split s #"/" 2))
      (str/includes? s "#") (first (str/split s #"#" 2))
      :else nil)))

(defn- owner-from-token [token]
  (let [t (str token)]
    (cond
      (str/includes? t "#") (first (str/split t #"#" 2))
      (str/includes? t ".") (first (str/split t #"\." 2))
      (str/includes? t "/") (first (str/split t #"/" 2))
      :else nil)))

(defn- owner-match? [token candidate]
  (let [owner (some-> token owner-from-token lower)
        cand-scope (some-> candidate :symbol symbol-scope lower)
        cand-module (some-> candidate :module lower)
        scope-tail (some-> cand-scope (str/split #"\.") last)]
    (if (str/blank? owner)
      true
      (or (= owner cand-scope)
          (= owner cand-module)
          (= owner scope-tail)
          (and cand-scope (str/ends-with? cand-scope (str "." owner)))
          (and cand-module (str/ends-with? cand-module (str "." owner)))))))

(defn- normalize-import-prefix [imp]
  (let [s (str imp)]
    (if (str/ends-with? s ".*")
      (subs s 0 (- (count s) 2))
      s)))

(defn- import-match? [imports candidate]
  (let [scope (some-> candidate :symbol symbol-scope)
        module (:module candidate)
        imports* (->> imports (map normalize-import-prefix) (remove str/blank?) distinct)]
    (if (empty? imports*)
      false
      (some (fn [imp]
              (or (= imp scope)
                  (= imp module)
                  (and scope (str/starts-with? scope (str imp ".")))
                  (and module (str/starts-with? module (str imp ".")))))
            imports*))))

(defn- narrow-targets [caller targets token units-by-id files-by-path]
  (let [by-id #(get units-by-id %)
        candidates (->> targets (map by-id) (remove nil?) vec)
        caller-imports (get-in files-by-path [(:path caller) :imports] [])]
    (cond
      (<= (count candidates) 1)
      (mapv :unit_id candidates)

      :else
      (let [owner-filtered (if (re-find #"[./#/]" (str token))
                             (filter #(owner-match? token %) candidates)
                             candidates)
            import-filtered (if (seq caller-imports)
                              (filter #(import-match? caller-imports %) owner-filtered)
                              owner-filtered)
            same-path (filter #(= (:path %) (:path caller)) import-filtered)
            same-module (filter #(= (:module %) (:module caller)) import-filtered)]
        (cond
          (seq same-path) (mapv :unit_id same-path)
          (seq same-module) (mapv :unit_id same-module)
          (seq import-filtered) (mapv :unit_id import-filtered)
          :else (mapv :unit_id owner-filtered))))))

(defn- resolve-target-ids [caller token token-index units-by-id files-by-path]
  (let [target-ids (->> (expand-call-token caller token)
                        (mapcat #(get token-index % #{}))
                        distinct
                        vec)]
    (narrow-targets caller target-ids token units-by-id files-by-path)))

(defn- macro-unit? [u]
  (= "defmacro" (:form_operator u)))

(declare recursive-generated-target-ids)

(defn- recursive-generated-target-ids [macro-unit token-index units-by-id files-by-path seen]
  (if (or (nil? macro-unit)
          (not (macro-unit? macro-unit))
          (contains? seen (:unit_id macro-unit)))
    []
    (let [seen* (conj seen (:unit_id macro-unit))]
      (->> (or (:generated_calls macro-unit) [])
           (mapcat #(resolve-target-ids macro-unit % token-index units-by-id files-by-path))
           distinct
           (mapcat (fn [target-id]
                     (let [target (get units-by-id target-id)]
                       (if (macro-unit? target)
                         (recursive-generated-target-ids target token-index units-by-id files-by-path seen*)
                         [target-id]))))
           distinct
           vec))))

(defn- inherited-macro-target-ids [resolved-target-ids token-index units-by-id files-by-path]
  (->> resolved-target-ids
       (map #(get units-by-id %))
       (remove nil?)
       (filter macro-unit?)
       (mapcat #(recursive-generated-target-ids % token-index units-by-id files-by-path #{}))
       distinct
       vec))

(defn- build-callers-index [units files-by-path]
  (let [token-index (build-call-token-index units)
        units-by-id (into {} (map (juxt :unit_id identity) units))]
    (reduce
     (fn [acc caller]
       (reduce
        (fn [acc2 token]
          (let [direct-targets (resolve-target-ids caller token token-index units-by-id files-by-path)
                inherited-targets (inherited-macro-target-ids direct-targets token-index units-by-id files-by-path)
                all-targets (distinct (concat direct-targets inherited-targets))]
            (reduce (fn [a target-id]
                      (if (= target-id (:unit_id caller))
                        a
                        (update a target-id (fnil conj #{}) (:unit_id caller))))
                    acc2
                    all-targets)))
        acc
        (:calls caller)))
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

(defn- build-test-target-index [files]
  (reduce-kv
   (fn [acc path {:keys [test_target_modules]}]
     (reduce (fn [a module]
               (update a module (fnil conj #{}) path))
             acc
             test_target_modules))
   {}
   files))

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
     :callers_index (build-callers-index units (:files files-data))
     :module_dependents (build-module-dependents (:files files-data))
     :test_target_index (build-test-target-index (:files files-data))}))

(defn- maybe-load-latest [storage-adapter root-path load-latest?]
  (when (and storage-adapter load-latest?)
    (storage/init-storage! storage-adapter)
    (storage/load-latest-index storage-adapter root-path)))

(defn- maybe-save-index! [storage-adapter index]
  (when storage-adapter
    (storage/init-storage! storage-adapter)
    (storage/save-index! storage-adapter index))
  index)

(defn create-index
  [{:keys [root_path paths parser_opts storage load_latest]
    :or {root_path "."
         parser_opts {:clojure_engine :clj-kondo
                      :tree_sitter_enabled false}
         load_latest false}}]
  (if-let [latest (maybe-load-latest storage root_path load_latest)]
    latest
    (let [discovered (if (seq paths)
                       (normalize-paths paths)
                       (discover-source-files root_path))
          files-data (parse-files root_path discovered parser_opts)
          index (build-index-state root_path files-data)]
      (maybe-save-index! storage index))))

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
  [index {:keys [changed_paths parser_opts storage]
          :or {changed_paths []
               parser_opts {:clojure_engine :clj-kondo
                            :tree_sitter_enabled false}}}]
  (if (empty? changed_paths)
    (create-index {:root_path (:root_path index)
                   :parser_opts parser_opts
                   :storage storage})
    (let [paths (normalize-paths changed_paths)
          base (remove-paths-from-index index paths)
          parsed (parse-files (:root_path index) paths parser_opts)
          merged-files (merge (:files base) (:files parsed))
          merged-units (vec (concat (:units base) (:units parsed)))
          merged-diags (vec (concat (:diagnostics base) (:diagnostics parsed)))
          updated (build-index-state (:root_path index)
                                     {:files merged-files
                                      :units merged-units
                                      :diagnostics merged-diags})]
      (maybe-save-index! storage updated))))

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
