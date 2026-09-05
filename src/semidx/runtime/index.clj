(ns semidx.runtime.index
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.adapters :as adapters]
            [semidx.runtime.language-activation :as activation]
            [semidx.runtime.projections :as projections]
            [semidx.runtime.relations :as relations]
            [semidx.runtime.repo-identity :as repo-identity]
            [semidx.runtime.semantic-id :as semantic-id]
            [semidx.runtime.storage :as storage]))

(defn- now-iso []
  (-> (java.time.Instant/now) str))

(defn- uuid []
  (str (java.util.UUID/randomUUID)))

(defn- parse-instant [value]
  (when (seq value)
    (try
      (java.time.Instant/parse (str value))
      (catch Exception _ nil))))

(defn- age-seconds [indexed-at]
  (if-let [instant (parse-instant indexed-at)]
    (max 0 (long (.getSeconds (java.time.Duration/between instant (java.time.Instant/now)))))
    0))

(defn- stale? [age-seconds max-snapshot-age-seconds]
  (and (some? max-snapshot-age-seconds)
       (> age-seconds (long max-snapshot-age-seconds))))

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
  (letfn [(distinct-vec [xs]
            (->> xs (remove nil?) distinct vec))
          (enrich-elixir-use-imports [{:keys [files units diagnostics relations]}]
            (let [module->use-imports (->> (vals files)
                                           (filter #(= "elixir" (:language %)))
                                           (keep (fn [{:keys [module use_expansion_imports]}]
                                                   (when (and (seq module) (seq use_expansion_imports))
                                                     [module use_expansion_imports])))
                                           (into {}))
                  files* (reduce-kv (fn [acc path {:keys [language imports use_modules] :as file-rec}]
                                      (if (and (= "elixir" language) (seq use_modules))
                                        (let [implicit-imports (->> use_modules
                                                                    (mapcat #(get module->use-imports % []))
                                                                    distinct-vec)]
                                          (assoc acc path
                                                 (assoc file-rec
                                                        :imports (distinct-vec (concat imports implicit-imports)))))
                                        (assoc acc path file-rec)))
                                    {}
                                    files)
                  units* (mapv (fn [unit]
                                 (let [imports* (get-in files* [(:path unit) :imports])]
                                   (if (and (= "elixir" (get-in files* [(:path unit) :language]))
                                            (seq imports*))
                                     (assoc unit :imports imports*)
                                     unit)))
                               units)]
              {:files files*
               :units units*
               :diagnostics diagnostics
               :relations relations}))]
    (adapters/with-parser-context
     root-path
     paths
     parser-opts
     (fn [context-parser-opts]
       (->> paths
            (reduce
             (fn [acc path]
               (let [parsed (adapters/parse-file root-path path context-parser-opts)
                     file-rec {:path path
                               :language (:language parsed)
                               :module (:module parsed)
                               :imports (:imports parsed)
                               :use_modules (:use_modules parsed)
                               :use_expansion_imports (:use_expansion_imports parsed)
                               :test_target_modules (:test_target_modules parsed)
                               :semantic_pipeline (:semantic_pipeline parsed)
                               :parser_mode (:parser_mode parsed)
                               :diagnostics (:diagnostics parsed)}]
                 (-> acc
                     (update :files assoc path file-rec)
                     (update :units into (:units parsed))
                     (update :relations into (:relations parsed))
                     (update :diagnostics into
                             (map (fn [d] (assoc d :path path)) (:diagnostics parsed))))))
             {:files {} :units [] :diagnostics [] :relations []})
            enrich-elixir-use-imports)))))

(defn- snapshot-file-lines [root-path path]
  (let [f (io/file root-path path)]
    (if (.exists f)
      (-> f slurp str/split-lines vec)
      [])))

(defn- build-file-snapshots [root-path files]
  (reduce-kv (fn [acc path _]
               (assoc acc path (snapshot-file-lines root-path path)))
             {}
             files))

(defn- lower [s]
  (some-> s str/lower-case))

(defn- tail-token [token]
  (some-> token str (str/split #"[\./#]") last))

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
       (let [symbol-tokens (if (:dispatch_value u)
                             #{}
                             (symbol-call-tokens sym))
             alias-tokens (->> (:call_tokens u)
                               (mapcat (fn [token]
                                         (if-let [l (lower token)]
                                           [token l]
                                           [token])))
                               (remove str/blank?)
                               set)]
         (reduce (fn [a token] (update a token (fnil conj #{}) (:unit_id u)))
                 acc
                 (concat symbol-tokens alias-tokens)))
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
      (str/includes? t "/") (first (str/split t #"/" 2))
      (str/includes? t ".") (->> (str/split t #"\.")
                                 butlast
                                 (str/join "."))
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

(defn- import-prefixes [imp]
  (let [base (normalize-import-prefix imp)]
    (cond-> #{base}
      (re-find #"\.[A-Za-z_][A-Za-z0-9_]*$" base)
      (conj (some-> (str/split base #"\.") butlast (->> (str/join ".")))))))

(defn- import-match? [imports candidate]
  (let [scope (some-> candidate :symbol symbol-scope)
        module (:module candidate)
        imports* (->> imports (mapcat import-prefixes) (remove str/blank?) distinct)]
    (if (empty? imports*)
      false
      (some (fn [imp]
              (or (= imp scope)
                  (= imp module)
                  (and scope (str/starts-with? scope (str imp ".")))
                  (and module (str/starts-with? module (str imp ".")))))
            imports*))))

(defn- call-arities-for-token [caller token]
  (let [m (:call_arity_by_token caller)
        tail (some-> token tail-token)]
    (or (get m token)
        (when (seq tail) (get m tail))
        #{})))

(defn- module-superclass-map [units-by-id]
  (->> (vals units-by-id)
       (keep (fn [{:keys [module superclass_module]}]
               (when (and (seq module) (seq superclass_module))
                 [(lower module) (lower superclass_module)])))
       (into {})))

(defn- caller-superclass-modules [caller units-by-id]
  (let [super-map (module-superclass-map units-by-id)]
    (loop [current (some-> caller :superclass_module lower)
           seen #{}
           acc []]
      (if (or (str/blank? current) (contains? seen current))
        acc
        (recur (get super-map current)
               (conj seen current)
               (conj acc current))))))

(defn- superclass-target? [caller candidate units-by-id]
  (let [super-modules (set (caller-superclass-modules caller units-by-id))
        candidate-scope (some-> candidate :symbol symbol-scope lower)
        candidate-module (some-> candidate :module lower)]
    (and (seq super-modules)
         (some (fn [super-module]
                 (or (= super-module candidate-scope)
                     (= super-module candidate-module)
                     (and candidate-scope (str/ends-with? candidate-scope (str "." super-module)))
                     (and candidate-module (str/ends-with? candidate-module (str "." super-module)))))
               super-modules))))

(defn- narrow-targets [caller targets token units-by-id files-by-path]
  (let [by-id #(get units-by-id %)
        candidates (->> targets (map by-id) (remove nil?) vec)
        caller-imports (get-in files-by-path [(:path caller) :imports] [])
        call-arities (call-arities-for-token caller token)]
    (cond
      (<= (count candidates) 1)
      (mapv :unit_id candidates)

      :else
      (let [owner-filtered (if (re-find #"[./#/]" (str token))
                             (let [matching (filter #(owner-match? token %) candidates)
                                   owner (some-> token owner-from-token lower)]
                               (cond
                                 (seq matching) matching
                                 (contains? #{"this" "super"} owner)
                                 (let [local-matching (if (= "super" owner)
                                                        (filter #(superclass-target? caller % units-by-id) candidates)
                                                        (filter #(or (= (:path %) (:path caller))
                                                                     (= (:module %) (:module caller)))
                                                                candidates))]
                                   (if (seq local-matching) local-matching candidates))
                                 :else candidates))
                             candidates)
            arity-filtered (if (seq call-arities)
                             (let [matching (filter #(or (nil? (:method_arity %))
                                                         (contains? call-arities (:method_arity %)))
                                                    owner-filtered)]
                               (if (seq matching) matching owner-filtered))
                             owner-filtered)
            same-path (filter #(= (:path %) (:path caller)) arity-filtered)
            same-module (filter #(= (:module %) (:module caller)) arity-filtered)
            same-superclass (filter #(superclass-target? caller % units-by-id) arity-filtered)
            import-filtered (if (seq caller-imports)
                              (filter #(import-match? caller-imports %) arity-filtered)
                              arity-filtered)]
        (cond
          (seq same-path) (mapv :unit_id same-path)
          (seq same-module) (mapv :unit_id same-module)
          (seq same-superclass) (mapv :unit_id same-superclass)
          (seq import-filtered) (mapv :unit_id import-filtered)
          :else (mapv :unit_id arity-filtered))))))

(defn- resolve-target-ids [caller token token-index units-by-id files-by-path]
  (let [target-ids (->> (expand-call-token caller token)
                        (mapcat #(get token-index % #{}))
                        distinct
                        vec)]
    (narrow-targets caller target-ids token units-by-id files-by-path)))

(defn- relation-resolution-status [target-ids]
  (case (count target-ids)
    0 "unresolved"
    1 "resolved"
    "ambiguous"))

(defn- resolve-relation-targets [relations units-by-id files-by-path]
  (let [token-index (build-call-token-index (vals units-by-id))]
    (mapv (fn [{:keys [source_unit_id target_key target_unit_ids] :as relation}]
            (let [source (get units-by-id source_unit_id)
                  target-ids (if (seq target_unit_ids)
                               (vec target_unit_ids)
                               (when (and source (seq target_key))
                                 (resolve-target-ids source target_key token-index units-by-id files-by-path)))]
              (assoc relation
                     :target_unit_ids (vec (or target-ids []))
                     :resolution_status (relation-resolution-status (or target-ids [])))))
          relations)))

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

(defn- build-callees-index [callers-index]
  (reduce-kv
   (fn [acc callee-id caller-ids]
     (reduce (fn [a caller-id]
               (update a caller-id (fnil conj #{}) callee-id))
             acc
             caller-ids))
   {}
   callers-index))

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

(defn- lifecycle-summary
  [index {:keys [provenance_source parent_snapshot_id requested_snapshot_id
                 reused_snapshot snapshot_pinned max_snapshot_age_seconds rebuild_reason
                 activation_metadata shadow_reuse]}]
  (let [age (age-seconds (:indexed_at index))]
    (cond-> {:reused_snapshot (boolean reused_snapshot)
             :snapshot_pinned (boolean snapshot_pinned)
             :stale (boolean (stale? age max_snapshot_age_seconds))
             :age_seconds age
             :max_snapshot_age_seconds max_snapshot_age_seconds
             :rebuild_reason rebuild_reason
             :repo_identity (:repo_identity index)
             :provenance {:source provenance_source
                          :parent_snapshot_id parent_snapshot_id
                          :requested_snapshot_id requested_snapshot_id}}
      activation_metadata (merge activation_metadata)
      shadow_reuse (assoc :shadow_reuse shadow_reuse))))

(defn- attach-lifecycle [index lifecycle-opts]
  (assoc index :index_lifecycle (lifecycle-summary index lifecycle-opts)))

(defn- build-index-state
  ([root-path files-data]
   (build-index-state root-path files-data {}))
  ([root-path files-data lifecycle-opts]
   (let [units (semantic-id/enrich-units (:units files-data))
         units-by-id (into {} (map (juxt :unit_id identity) units))
         activation-metadata (:activation_metadata lifecycle-opts)
         repo-identity* (repo-identity/resolve-repo-identity root-path)
         callers-index (build-callers-index units (:files files-data))
         callees-index (build-callees-index callers-index)
         resolved-relations (resolve-relation-targets (:relations files-data) units-by-id (:files files-data))
         relation-indexes (relations/index-relations resolved-relations)]
     (attach-lifecycle
      {:root_path root-path
       :snapshot_id (uuid)
       :indexed_at (now-iso)
       :repo_identity repo-identity*
       :repo_key (:repo_key repo-identity*)
       :workspace_path (:workspace_path repo-identity*)
       :workspace_key (:workspace_key repo-identity*)
       :git_branch (:git_branch repo-identity*)
       :git_commit (:git_commit repo-identity*)
       :git_dirty (:git_dirty repo-identity*)
       :identity_source (:identity_source repo-identity*)
       :files (:files files-data)
       :file_snapshots (build-file-snapshots root-path (:files files-data))
       :diagnostics (:diagnostics files-data)
       :units units-by-id
       :unit_order (mapv :unit_id units)
       :symbol_index (build-symbol-index units)
       :path_index (index-by :path units)
       :module_index (index-by :module units)
       :callers_index callers-index
       :callees_index callees-index
       :relations (:relations relation-indexes)
       :relation_forward_index (:relation_forward_index relation-indexes)
       :relation_reverse_index (:relation_reverse_index relation-indexes)
       :relation_diagnostics (:relation_diagnostics relation-indexes)
       :module_dependents (build-module-dependents (:files files-data))
       :test_target_index (build-test-target-index (:files files-data))
       :detected_languages (:detected_languages activation-metadata)
       :active_languages (:active_languages activation-metadata)
       :language_fingerprint (:language_fingerprint activation-metadata)
       :activation_state (:activation_state activation-metadata)
       :supported_languages (:supported_languages activation-metadata)
       :selection_hint (:selection_hint activation-metadata)
       :manual_language_selection (:manual_language_selection activation-metadata)
       :workspace_state (:workspace_state lifecycle-opts)}
      lifecycle-opts))))

(defn- shadow-reuse-mode? [mode]
  (contains? #{:shadow "shadow"} mode))

(defn- repo-shadow-candidate [storage-adapter root-path]
  (let [repo-identity* (repo-identity/resolve-repo-identity root-path)
        repo-key (:repo_key repo-identity*)
        git-commit (:git_commit repo-identity*)
        git-branch (:git_branch repo-identity*)]
    (cond
      (and (seq repo-key) (seq git-commit))
      {:lookup_strategy "repo_commit"
       :candidate (storage/load-index-by-repo-commit storage-adapter repo-key git-commit)}

      (and (seq repo-key) (seq git-branch))
      {:lookup_strategy "repo_branch"
       :candidate (storage/load-latest-index-by-repo-branch storage-adapter repo-key git-branch)}

      (seq repo-key)
      {:lookup_strategy "repo_latest"
       :candidate (storage/load-latest-index-by-repo storage-adapter repo-key)}

      :else
      {:lookup_strategy nil
       :candidate nil})))

(defn- shadow-reuse-summary [storage-adapter root-path {:keys [load_latest pinned_snapshot_id repo_identity_reuse_mode]}]
  (when (shadow-reuse-mode? repo_identity_reuse_mode)
    (cond
      (nil? storage-adapter)
      {:mode "shadow"
       :eligible false
       :reason "no_storage"}

      (seq pinned_snapshot_id)
      {:mode "shadow"
       :eligible false
       :reason "pinned_snapshot"}

      (not load_latest)
      {:mode "shadow"
       :eligible false
       :reason "load_latest_disabled"}

      :else
      (let [{:keys [lookup_strategy candidate]} (repo-shadow-candidate storage-adapter root-path)]
        {:mode "shadow"
         :eligible true
         :reason (if candidate "candidate_found" "no_candidate")
         :lookup_strategy lookup_strategy
         :candidate_found (boolean candidate)
         :candidate_snapshot_id (:snapshot_id candidate)
         :candidate_root_path (:root_path candidate)
         :candidate_workspace_path (:workspace_path candidate)
         :candidate_git_branch (:git_branch candidate)
         :candidate_git_commit (:git_commit candidate)
         :candidate_matches_root_path (boolean (= root-path (:root_path candidate)))
         :would_change_lookup (boolean (and candidate
                                            (not= root-path (:root_path candidate))))}))))

(defn- maybe-load-index [storage-adapter root-path opts]
  (if storage-adapter
    (do
      (storage/init-storage! storage-adapter)
      (let [{:keys [load_latest pinned_snapshot_id]} opts
            shadow-reuse (shadow-reuse-summary storage-adapter root-path opts)
            loaded (cond
                     (seq pinned_snapshot_id)
                     (or (some-> (storage/load-index-by-snapshot storage-adapter root-path pinned_snapshot_id)
                                 semantic-id/enrich-index)
                         (throw (ex-info "requested pinned snapshot was not found"
                                         {:type :invalid_request
                                          :message "requested pinned snapshot was not found"
                                          :details {:root_path root-path
                                                    :snapshot_id pinned_snapshot_id}})))

                     load_latest
                     (some-> (storage/load-latest-index storage-adapter root-path)
                             semantic-id/enrich-index)

                     :else nil)]
        {:loaded loaded
         :shadow_reuse shadow-reuse}))
    {}))

(defn- maybe-save-index! [storage-adapter index]
  (when storage-adapter
    (storage/init-storage! storage-adapter)
    (storage/save-index! storage-adapter index))
  index)

(defn- filtered-paths [paths active-languages]
  (let [active-set (set active-languages)]
    (->> paths
         (filter #(contains? active-set (adapters/language-by-path %)))
         vec)))

(defn create-index
  [opts]
  (if (:raw_build? opts)
    (let [root_path (or (:root_path opts) (:root-path opts) ".")
          parser_opts (or (:parser_opts opts) (:parser-opts opts)
                          {:clojure_engine :clj-kondo
                           :tree_sitter_enabled false})
          storage (or (:storage opts) (:storage_adapter opts))
          pinned_snapshot_id (:pinned_snapshot_id opts)
          max_snapshot_age_seconds (:max_snapshot_age_seconds opts)
          rebuild_reason (:rebuild_reason opts)
          language_policy (:language_policy opts)
          discovery (activation/discover-languages root_path)
          activation-state (activation/resolve-activation discovery language_policy)
          activation-metadata (activation/activation-metadata discovery activation-state)
          no-supported? (and (empty? (:detected_languages activation-state))
                             (empty? (:active_languages activation-state)))]
      (when no-supported?
        (throw (ex-info "no supported languages were detected for this project"
                        {:type :no_supported_languages_found
                         :message "no supported languages were detected for this project"
                         :details (activation/no-supported-languages-details discovery activation-state)})))
      (let [discovered (if (seq (:paths opts))
                         (filtered-paths (normalize-paths (:paths opts)) (:active_languages activation-state))
                         (activation/active-source-paths discovery activation-state))
            files-data (parse-files root_path discovered parser_opts)
            index (build-index-state root_path
                                     files-data
                                     {:provenance_source "fresh_build"
                                      :requested_snapshot_id pinned_snapshot_id
                                      :max_snapshot_age_seconds max_snapshot_age_seconds
                                      :activation_metadata activation-metadata
                                      :rebuild_reason (or rebuild_reason "initial_build")
                                      :workspace_state (:workspace_state opts)})]
        (maybe-save-index! storage index)))
    ((requiring-resolve 'semidx.runtime.index-lifecycle/coordinate-index-lifecycle) opts)))

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
  [index opts]
  (let [added-paths (normalize-paths (or (:added_paths opts) (:added-paths opts) []))
        changed-paths (normalize-paths (or (:changed_paths opts) (:changed-paths opts) []))
        deleted-paths (normalize-paths (or (:deleted_paths opts) (:deleted-paths opts) []))
        parser-opts (or (:parser_opts opts) (:parser-opts opts)
                        {:clojure_engine :clj-kondo
                         :tree_sitter_enabled false})
        storage (or (:storage opts) (:storage_adapter opts))
        workspace-state (:workspace_state opts)
        activation-metadata (or (:activation_metadata opts)
                                (select-keys index [:detected_languages :active_languages
                                                    :language_fingerprint :activation_state
                                                    :supported_languages :selection_hint
                                                    :manual_language_selection]))]
    (if (and (empty? added-paths) (empty? changed-paths) (empty? deleted-paths))
      (create-index {:root_path (:root_path index)
                     :parser_opts parser-opts
                     :storage storage
                     :rebuild_reason "full_rebuild"
                     :workspace_state workspace-state})
      (let [paths-to-remove (vec (concat changed-paths deleted-paths))
            base (remove-paths-from-index index paths-to-remove)
            paths-to-parse (vec (concat added-paths changed-paths))
            parsed (parse-files (:root_path index) paths-to-parse parser-opts)
            merged-files (merge (:files base) (:files parsed))
            merged-units (vec (concat (:units base) (:units parsed)))
            merged-diags (vec (concat (:diagnostics base) (:diagnostics parsed)))
            updated (build-index-state (:root_path index)
                                       {:files merged-files
                                        :units merged-units
                                        :diagnostics merged-diags}
                                       {:provenance_source "incremental_update"
                                        :parent_snapshot_id (:snapshot_id index)
                                        :rebuild_reason "changed_paths_update"
                                        :activation_metadata activation-metadata
                                        :workspace_state workspace-state})]
        (maybe-save-index! storage updated)))))

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
     (projections/with-projection
       {:snapshot_id (:snapshot_id index)
        :indexed_at (:indexed_at index)
        :index_lifecycle (:index_lifecycle index)
        :files files
        :modules modules
        :summary (str "Indexed " (count (:files index)) " files and " (count (:units index)) " units.")}
       :structural
       :selection))))
