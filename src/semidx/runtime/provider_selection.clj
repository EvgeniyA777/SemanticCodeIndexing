(ns semidx.runtime.provider-selection
  "Stage 2 provider planning policy (plans/018).

  Turns file identity plus provider status into a deterministic, bounded
  ProviderPlan: which providers may run for which operation, in which order, and
  under what execution limits.

  It decides precedence and nothing else. It does not execute providers, does
  not normalize facts, and does not decide how evidence merges — arbitration is
  `semidx.runtime.fact-arbitration`."
  (:require [semidx.runtime.fact-arbitration :as fact-arbitration]
            [semidx.runtime.providers :as providers]))

(def plan-schema-version "1")

(def modes
  "`default` plans the active path, `shadow` runs beside it without affecting
  the snapshot, `forced` is a test control that ignores status gating."
  #{"default" "shadow" "forced"})

(def default-mode "shadow")

(def default-execution-policy
  {:max_providers_per_operation 3
   :timeout_ms 5000
   :max_concurrency 4})

(defn- authority-rank [authority]
  (get fact-arbitration/authority-rank authority Long/MAX_VALUE))

(defn- ordered-candidates
  "Deterministic candidate order: strongest claimed authority first, provider id
  as the tie-break so two providers with equal claims never swap between runs."
  [descriptors operation]
  (->> descriptors
       (keep (fn [descriptor]
               (when-let [authority (get-in descriptor [:operation_capabilities operation])]
                 {:provider_id (:provider_id descriptor)
                  :provider_version (:provider_version descriptor)
                  :authority authority})))
       (sort-by (juxt #(authority-rank (:authority %)) :provider_id))
       vec))

(defn- excluded-entry [candidate status reason]
  {:provider_id (:provider_id candidate)
   :authority (:authority candidate)
   :reason reason
   :state (:state status)
   :reason_codes (vec (:reason_codes status))})

(defn plan-operation
  "Plan one operation: the admitted provider list plus everything excluded and
  why. An exclusion is recorded, never silently dropped, so a plan explains its
  own gaps."
  [{:keys [descriptors operation statuses mode execution_policy denied_providers]}]
  (let [candidates (ordered-candidates descriptors operation)
        limit (:max_providers_per_operation execution_policy)
        {:keys [admitted excluded]}
        (reduce (fn [acc candidate]
                  (let [status (get statuses (:provider_id candidate))
                        denied? (contains? (set denied_providers) (:provider_id candidate))
                        forced? (= "forced" mode)
                        ;; An unobserved provider is not a working one. Admitting
                        ;; it on a default of "ready" would let an external tool
                        ;; that was never probed into the plan.
                        unknown? (and (not forced?) (nil? status))
                        unavailable? (and (not forced?)
                                          (= "unavailable" (:state status)))]
                    (cond
                      denied?
                      (update acc :excluded conj
                              (excluded-entry candidate status "denied_by_override"))

                      unknown?
                      (update acc :excluded conj
                              (excluded-entry candidate
                                              {:state "unknown" :reason_codes ["status_not_observed"]}
                                              "provider_status_unknown"))

                      unavailable?
                      (update acc :excluded conj
                              (excluded-entry candidate status "provider_unavailable"))

                      (>= (count (:admitted acc)) limit)
                      (update acc :excluded conj
                              (excluded-entry candidate status "execution_limit_reached"))

                      :else
                      (update acc :admitted conj
                              (assoc candidate :state (:state status "forced"))))))
                {:admitted [] :excluded []}
                candidates)]
    {:providers admitted
     :excluded excluded}))

(defn- batch-descriptors-for
  "Project descriptors whose completed batch covered `path`.

  Two conditions must hold: the descriptor selects the path, and the run
  reported that path as covered. Coverage is the signal, not the selector — an
  unavailable or failed project run reports no covered paths, so it contributes
  no candidate and the plan falls back to the file-scoped tiers on its own."
  [path batch-coverage]
  (when (seq batch-coverage)
    (vec (for [d (providers/descriptors-for-project)
               :let [covered (set (get batch-coverage (:provider_id d)))]
               :when (and (contains? covered path)
                          (providers/selects-path? d path))]
           d))))

(defn provider-plan
  "Build a ProviderPlan for one file.

  `operations` defaults to every operation the eligible descriptors claim, so a
  caller cannot silently plan fewer operations than the catalog supports.

  Stage 4.5 adds two optional inputs, both absent by default:

  - `:batch_coverage` — `provider_id -> covered paths` from a completed project
    provider run;
  - `:batch_statuses` — `provider_id -> status` for those providers, observed by
    `semidx.runtime.provider-batch`. Without it a project candidate is excluded
    as `provider_status_unknown`, exactly like any unobserved provider.

  With neither supplied, the candidate list, the statuses, and the operation set
  are the ones this function produced before Stage 4.5, so the plan is
  unchanged."
  [{:keys [path language source_identity operations mode parser_opts
           execution_policy denied_providers statuses batch_coverage batch_statuses]
    :or {mode default-mode}}]
  (let [batch-descriptors (batch-descriptors-for path batch_coverage)
        descriptors (into (providers/descriptors-for path) batch-descriptors)
        statuses (merge (or statuses (providers/statuses path (or parser_opts {})))
                        (select-keys (or batch_statuses {})
                                     (mapv :provider_id batch-descriptors)))
        policy (merge default-execution-policy execution_policy)
        operations (or (seq operations)
                       (->> descriptors
                            (mapcat (comp keys :operation_capabilities))
                            distinct
                            sort
                            vec))
        planned (into (sorted-map)
                      (map (fn [operation]
                             [operation (plan-operation {:descriptors descriptors
                                                         :operation operation
                                                         :statuses statuses
                                                         :mode mode
                                                         :execution_policy policy
                                                         :denied_providers denied_providers})]))
                      operations)]
    {:plan_schema_version plan-schema-version
     :catalog_version providers/catalog-version
     :path path
     :language (or language (first (mapcat :languages descriptors)))
     :source_identity (or source_identity {})
     :mode (if (contains? modes mode) mode default-mode)
     :execution_policy policy
     :operations planned
     :statuses statuses}))

(defn project-plan
  "Build a project-scoped ProviderPlan for one repository root (Stage 4.5).

  Admission is `plan-operation`, unchanged: strongest claimed authority first,
  provider id as the tie-break, every exclusion recorded with its reason, and an
  unobserved status excluded rather than assumed ready. `:statuses` must be
  supplied — the file catalog cannot probe a project toolchain, so an absent map
  means every candidate is excluded as `provider_status_unknown`. That is the
  intended default: nothing plans a SCIP provider unless a caller observed it.

  Execution is once per provider, not once per (operation, provider);
  `planned-provider-ids` is the list a batch runner iterates."
  [{:keys [root_path languages operations mode execution_policy denied_providers statuses]
    :or {mode default-mode}}]
  (let [descriptors (providers/descriptors-for-project languages)
        statuses (or statuses {})
        policy (merge default-execution-policy execution_policy)
        operations (or (seq operations)
                       (->> descriptors
                            (mapcat (comp keys :operation_capabilities))
                            distinct
                            sort
                            vec))
        planned (into (sorted-map)
                      (map (fn [operation]
                             [operation (plan-operation {:descriptors descriptors
                                                         :operation operation
                                                         :statuses statuses
                                                         :mode mode
                                                         :execution_policy policy
                                                         :denied_providers denied_providers})]))
                      operations)]
    {:plan_schema_version plan-schema-version
     :catalog_version providers/catalog-version
     :scope "project"
     :root_path root_path
     :languages (vec (or (seq languages)
                         (distinct (mapcat :languages descriptors))))
     :mode (if (contains? modes mode) mode default-mode)
     :execution_policy policy
     :operations planned
     :statuses statuses}))

(defn planned-provider-ids
  "Every provider the plan admits, deduplicated, in stable order."
  [plan]
  (->> (vals (:operations plan))
       (mapcat :providers)
       (map :provider_id)
       distinct
       vec))

(defn planned-tasks
  "One task per (operation, provider) the plan admits, in stable order.

  Execution is per operation, not per provider: the same provider may be
  admitted for several operations with different claims, and collapsing them
  would make a batch unable to say which operation it answered."
  [plan]
  (vec (for [[operation {:keys [providers]}] (:operations plan)
             provider providers]
         {:operation operation
          :provider_id (:provider_id provider)
          :authority (:authority provider)})))
