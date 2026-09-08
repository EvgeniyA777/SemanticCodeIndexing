(ns semidx.runtime.index-lifecycle
  (:require [clojure.string :as str]
            [semidx.runtime.workspace-state :as ws]
            [semidx.runtime.freshness :as freshness]
            [semidx.runtime.storage :as storage]
            [semidx.runtime.semantic-id :as semantic-id]
            [semidx.runtime.language-activation :as activation]
            [semidx.runtime.repo-identity :as repo-identity]))

(defonce ^:private root-locks (atom {}))

(defn- get-root-lock [root-path]
  (let [canonical (activation/canonical-root-path root-path)]
    (or (get @root-locks canonical)
        (locking root-locks
          (if-let [lk (get @root-locks canonical)]
            lk
            (let [new-lk (Object.)]
              (swap! root-locks assoc canonical new-lk)
              new-lk))))))

(defn- get-opt [opts k]
  (let [k-dash (keyword (str/replace (name k) "_" "-"))
        k-under (keyword (str/replace (name k) "-" "_"))]
    (if (contains? opts k-dash)
      (get opts k-dash)
      (get opts k-under))))

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

(defn- shadow-reuse-summary [storage-adapter root-path opts]
  (let [repo-reuse-mode (get-opt opts :repo_identity_reuse_mode)
        load-latest? (get-opt opts :load_latest)
        pinned-id (get-opt opts :pinned_snapshot_id)]
    (when (shadow-reuse-mode? repo-reuse-mode)
      (cond
        (nil? storage-adapter)
        {:mode "shadow"
         :eligible false
         :reason "no_storage"}

        (seq pinned-id)
        {:mode "shadow"
         :eligible false
         :reason "pinned_snapshot"}

        (not load-latest?)
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
                                              (not= root-path (:root_path candidate))))})))))

(defn- load-prior-snapshot [storage root-path opts]
  (when (and storage (not (seq (get-opt opts :paths))))
    (storage/init-storage! storage)
    (let [pinned-id (get-opt opts :pinned_snapshot_id)
          load-latest? (get-opt opts :load_latest)]
      (cond
        (seq pinned-id)
        (some-> (storage/load-index-by-snapshot storage root-path pinned-id)
                semantic-id/enrich-index)
        
        load-latest?
        (some-> (storage/load-latest-index storage root-path)
                semantic-id/enrich-index)
        
        :else nil))))

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

(defn coordinate-index-lifecycle
  [opts]
  (let [root-path (or (get-opt opts :root_path) ".")
        storage (get-opt opts :storage)
        pinned-id (get-opt opts :pinned_snapshot_id)
        paths (get-opt opts :paths)]
    ;; Pre-run validations to conform to expected error taxonomy
    (when (and (seq pinned-id) (nil? storage))
      (throw (ex-info "pinned_snapshot_id requires a storage adapter"
                      {:type :invalid_request
                       :message "pinned_snapshot_id requires a storage adapter"})))
    (when (and (seq pinned-id) (seq paths))
      (throw (ex-info "pinned_snapshot_id cannot be combined with paths subset indexing"
                      {:type :invalid_request
                       :message "pinned_snapshot_id cannot be combined with paths subset indexing"})))
    
    (let [lock-obj (get-root-lock root-path)]
      (locking lock-obj
        (loop [attempts 0]
          (if (>= attempts 3)
            (throw (ex-info "Failed to atomically publish index after 3 attempts due to concurrent updates"
                            {:type :concurrency_failure
                             :root_path root-path}))
            (let [language-policy (get-opt opts :language_policy)
                  discovery-profile {:paths paths :language_policy language-policy}

                  ;; Resolve language activation once, up front. Used to (a) restrict
                  ;; the workspace manifest to the active language set so freshness
                  ;; deltas never include files excluded by :language_policy, and
                  ;; (b) preserve activation metadata through incremental updates.
                  discovery (activation/discover-languages root-path)
                  activation-state (activation/resolve-activation discovery language-policy)
                  activation-metadata (activation/activation-metadata discovery activation-state)
                  active-paths (activation/active-source-paths discovery activation-state)

                  ;; Resolve shadow reuse diagnostics
                  shadow-reuse (shadow-reuse-summary storage root-path opts)

                  ;; 2. Load prior snapshot from storage
                  prior-snapshot (load-prior-snapshot storage root-path opts)

                  ;; A pinned snapshot must be returned exactly; a missing one is invalid.
                  _ (when (and (seq pinned-id) (nil? prior-snapshot))
                      (throw (ex-info "pinned snapshot not found"
                                      {:type :invalid_request
                                       :message "pinned snapshot not found"
                                       :details {:pinned_snapshot_id pinned-id}})))

                  ;; plans/018 Stage 6.3. The authority model a build runs under
                  ;; is part of workspace identity: the same files indexed under
                  ;; a different model produce different units and different
                  ;; labels. Resolved lazily, like every other provider entry
                  ;; point here, so the lifecycle load path stays free of the
                  ;; provider namespaces when nobody asks for them.
                  authority-model (let [parser-opts (or (get-opt opts :parser_opts) {})
                                        mode ((requiring-resolve
                                               'semidx.runtime.index/provider-pipeline-mode)
                                              parser-opts)]
                                    ((requiring-resolve
                                      'semidx.runtime.provider-authority/authority-model)
                                     mode))

                  ;; 1. Capture current workspace state (skipped for pinned reuse).
                  current-workspace-state (when-not (seq pinned-id)
                                            (ws/capture-workspace-state
                                             root-path
                                             discovery-profile
                                             "1"
                                             (some-> prior-snapshot :workspace_state)
                                             active-paths
                                             authority-model))

                  ;; 3. Decide freshness. Pinned requests short-circuit to reuse and
                  ;; never run rebuild/update, per the runtime API contract.
                  freshness-decision (if (seq pinned-id)
                                       {:action :reuse
                                        :reason "pinned_snapshot"
                                        :added_paths [] :changed_paths [] :deleted_paths []
                                        :diagnostics ["Pinned snapshot requested; returning exact stored snapshot."]}
                                       (freshness/decide-freshness prior-snapshot current-workspace-state opts))
                  action (:action freshness-decision)
                  reason (:reason freshness-decision)
                  diagnostics (:diagnostics freshness-decision)]
              
              (case action
                :reuse
                ;; Return prior snapshot with lifecycle outcome attached
                (let [reused-lifecycle-opts {:provenance_source (if (seq pinned-id)
                                                                  "storage_pinned"
                                                                  "storage_latest")
                                             :requested_snapshot_id pinned-id
                                             :reused_snapshot true
                                             :snapshot_pinned (boolean (seq pinned-id))
                                             :max_snapshot_age_seconds (get-opt opts :max_snapshot_age_seconds)
                                             :shadow_reuse shadow-reuse}
                      updated-lifecycle (assoc (lifecycle-summary prior-snapshot reused-lifecycle-opts)
                                               :lifecycle_action (name action)
                                               :lifecycle_reason reason
                                               :lifecycle_diagnostics diagnostics)]
                  (with-meta
                    (assoc prior-snapshot :index_lifecycle updated-lifecycle)
                    (meta prior-snapshot)))
                
                :incremental_update
                (let [update-index-fn (requiring-resolve 'semidx.runtime.index/update-index)
                      ;; Widened options for update-index
                      update-opts (cond-> {:added_paths (:added_paths freshness-decision)
                                           :changed_paths (:changed_paths freshness-decision)
                                           :deleted_paths (:deleted_paths freshness-decision)
                                           :parser_opts (get-opt opts :parser_opts)
                                           :storage storage
                                           :activation_metadata activation-metadata
                                           :workspace_state current-workspace-state}
                                    (get-opt opts :policy_registry)
                                    (assoc :policy_registry (get-opt opts :policy_registry))
                                    (get-opt opts :usage_metrics)
                                    (assoc :usage_metrics (get-opt opts :usage_metrics))
                                    (get-opt opts :selection_cache)
                                    (assoc :selection_cache (get-opt opts :selection_cache)))
                      updated (update-index-fn prior-snapshot update-opts)
                      
                      ;; Verification for compare-and-set (TOCTOU)
                      latest-snapshot (load-prior-snapshot storage root-path (assoc opts :load_latest true))
                      latest-fp (get-in latest-snapshot [:workspace_state :workspace_fingerprint])
                      expected-fp (:workspace_fingerprint current-workspace-state)]
                  (if (or (nil? storage)
                          (= latest-fp expected-fp))
                    ;; Save is handled inside update-index, but we return it
                    (let [rebuilt-lifecycle-opts {:provenance_source "incremental_update"
                                                  :parent_snapshot_id (:snapshot_id prior-snapshot)
                                                  :max_snapshot_age_seconds (get-opt opts :max_snapshot_age_seconds)
                                                  :shadow_reuse shadow-reuse}
                          updated-lifecycle (assoc (lifecycle-summary updated rebuilt-lifecycle-opts)
                                                   :lifecycle_action (name action)
                                                   :lifecycle_reason reason
                                                   :lifecycle_diagnostics diagnostics)]
                      (with-meta
                        (assoc updated :index_lifecycle updated-lifecycle)
                        (meta updated)))
                    (recur (inc attempts))))
                
                :full_rebuild
                (let [create-index-fn (requiring-resolve 'semidx.runtime.index/create-index)
                      ;; Resolve specific rebuild reason
                      resolved-rebuild-reason (cond
                                                (= reason "force_rebuild_requested") "force_rebuild_requested"
                                                (= reason "pinned_snapshot_missing") "pinned_snapshot_missing"
                                                
                                                (= reason "snapshot_stale")
                                                (if (some? (get-opt opts :max_snapshot_age_seconds))
                                                  "max_age_stale"
                                                  "staleness_rule_stale")

                                                ;; An actual initial build is the only
                                                ;; case that may be attributed to
                                                ;; something other than what freshness
                                                ;; decided: there is no prior snapshot, so
                                                ;; the interesting fact is why this build
                                                ;; was scoped the way it was.
                                                (= reason "initial_build")
                                                (cond
                                                  (:manual_language_selection activation-state)
                                                  "manual_language_selection"

                                                  (seq paths) "paths_subset_requested"
                                                  :else "initial_build")

                                                ;; Everything else freshness decided is
                                                ;; forwarded verbatim — bugs/002. A
                                                ;; whitelist used to send
                                                ;; `no_prior_manifest`,
                                                ;; `manifest_schema_incompatible`,
                                                ;; `provider_or_pipeline_version_changed`,
                                                ;; `delta_exceeds_threshold` and
                                                ;; `authority_model_changed` to
                                                ;; `initial_build`, so a workspace that
                                                ;; rebuilt on every run because its delta
                                                ;; kept exceeding the threshold reported
                                                ;; the same reason as a cold start, and
                                                ;; sent whoever asked why to look at
                                                ;; storage instead of at the ratio.
                                                (seq reason) reason

                                                :else "initial_build")
                      
                      build-opts (cond-> (assoc opts
                                                :raw_build? true
                                                :rebuild_reason resolved-rebuild-reason
                                                :workspace_state current-workspace-state)
                                   (seq paths) (dissoc :storage :storage_adapter))
                      built (create-index-fn build-opts)
                      
                      ;; Verification for compare-and-set (TOCTOU)
                      latest-snapshot (load-prior-snapshot storage root-path (assoc opts :load_latest true))
                      latest-fp (get-in latest-snapshot [:workspace_state :workspace_fingerprint])
                      expected-fp (:workspace_fingerprint current-workspace-state)]
                  (if (or (nil? storage)
                          (seq paths)
                          (= latest-fp expected-fp))
                    ;; Save is handled inside create-index, but we return it
                    (let [rebuilt-lifecycle-opts {:provenance_source "fresh_build"
                                                  :parent_snapshot_id (some-> prior-snapshot :snapshot_id)
                                                  :max_snapshot_age_seconds (get-opt opts :max_snapshot_age_seconds)
                                                  :rebuild_reason resolved-rebuild-reason
                                                  :shadow_reuse shadow-reuse}
                          updated-lifecycle (assoc (lifecycle-summary built rebuilt-lifecycle-opts)
                                                   :lifecycle_action (name action)
                                                   :lifecycle_reason reason
                                                   :lifecycle_diagnostics diagnostics)]
                      (with-meta
                        (assoc built :index_lifecycle updated-lifecycle)
                        (meta built)))
                    (recur (inc attempts))))))))))))
