(ns semidx.runtime.freshness
  (:require [clojure.string :as str]
            [semidx.runtime.workspace-state :as ws]))

(def default-freshness-delta-rebuild-ratio 0.5)

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

(defn- build-diagnostics [diff delta-count total-count threshold action reason]
  (cond-> []
    (seq (:added_paths diff)) (conj (str "Added " (count (:added_paths diff)) " files."))
    (seq (:changed_paths diff)) (conj (str "Changed " (count (:changed_paths diff)) " files."))
    (seq (:deleted_paths diff)) (conj (str "Deleted " (count (:deleted_paths diff)) " files."))
    (seq (:compatibility_changes diff)) (conj (str "Compatibility changes: " (str/join ", " (:compatibility_changes diff))))
    true (conj (str "Action decided: " (name action) " (" reason "). Delta: " delta-count ", Total: " total-count ", Threshold: " threshold))))

(defn decide-freshness
  [previous-snapshot current-workspace-state opts]
  (let [ratio (or (:freshness_delta_rebuild_ratio opts)
                  (:freshness-delta-rebuild-ratio opts)
                  default-freshness-delta-rebuild-ratio)]
    (cond
      (or (:force_rebuild opts) (:force-rebuild opts))
      {:action :full_rebuild
       :reason "force_rebuild_requested"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Force rebuild requested by options."]}

      (nil? previous-snapshot)
      {:action :full_rebuild
       :reason "initial_build"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["No prior snapshot found."]}

      (nil? (:workspace_state previous-snapshot))
      {:action :full_rebuild
       :reason "no_prior_manifest"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Prior snapshot does not contain a workspace state manifest."]}

      (let [prev-ws (:workspace_state previous-snapshot)]
        (not= (:schema_version prev-ws) (:schema_version current-workspace-state)))
      {:action :full_rebuild
       :reason "manifest_schema_incompatible"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Workspace manifest schema version is incompatible."]}

      (let [prev-ws (:workspace_state previous-snapshot)]
        (or (not= (:provider_registry_version prev-ws) (:provider_registry_version current-workspace-state))
            (not= (:semantic_pipeline_version prev-ws) (:semantic_pipeline_version current-workspace-state))))
      {:action :full_rebuild
       :reason "provider_or_pipeline_version_changed"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Provider or semantic pipeline version changed."]}

      ;; plans/018 Stage 6.3. A changed authority model invalidates every file,
      ;; not a delta: the units, their authorities, and their parser_mode labels
      ;; are all produced under it. An incremental update would leave the
      ;; untouched files carrying labels from the model that no longer applies.
      (let [prev-ws (:workspace_state previous-snapshot)]
        (not= (:authority_model prev-ws) (:authority_model current-workspace-state)))
      {:action :full_rebuild
       :reason "authority_model_changed"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Provider authority model changed; every unit is rebuilt under it."]}

      (let [max-age (or (:max_snapshot_age_seconds opts) (:max-snapshot-age-seconds opts))
            age (age-seconds (:indexed_at previous-snapshot))]
        (and (stale? age max-age)
             (not (seq (or (:pinned_snapshot_id opts) (:pinned-snapshot-id opts))))))
      {:action :full_rebuild
       :reason "snapshot_stale"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Snapshot age exceeds max_snapshot_age_seconds."]}

      (let [prev-ws (:workspace_state previous-snapshot)]
        (= (:workspace_fingerprint prev-ws) (:workspace_fingerprint current-workspace-state)))
      {:action :reuse
       :reason "workspace_unchanged"
       :added_paths []
       :changed_paths []
       :deleted_paths []
       :diagnostics ["Workspace fingerprint matches. Reuse prior snapshot."]}

      :else
      (let [prev-ws (:workspace_state previous-snapshot)
            diff (ws/diff-workspace-state prev-ws current-workspace-state)
            added (:added_paths diff)
            changed (:changed_paths diff)
            deleted (:deleted_paths diff)
            delta-count (+ (count added) (count changed) (count deleted))
            total-count (count (:files current-workspace-state))
            threshold (double (* total-count ratio))]
        (if (> delta-count threshold)
          (let [diagnostics (build-diagnostics diff delta-count total-count threshold :full_rebuild "delta_exceeds_threshold")]
            {:action :full_rebuild
             :reason "delta_exceeds_threshold"
             :added_paths added
             :changed_paths changed
             :deleted_paths deleted
             :diagnostics diagnostics})
          (let [diagnostics (build-diagnostics diff delta-count total-count threshold :incremental_update "workspace_changed")]
            {:action :incremental_update
             :reason "workspace_changed"
             :added_paths added
             :changed_paths changed
             :deleted_paths deleted
             :diagnostics diagnostics}))))))
