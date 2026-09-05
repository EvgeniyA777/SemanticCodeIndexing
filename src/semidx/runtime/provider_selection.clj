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

(defn provider-plan
  "Build a ProviderPlan for one file.

  `operations` defaults to every operation the eligible descriptors claim, so a
  caller cannot silently plan fewer operations than the catalog supports."
  [{:keys [path language source_identity operations mode parser_opts
           execution_policy denied_providers statuses]
    :or {mode default-mode}}]
  (let [descriptors (providers/descriptors-for path)
        statuses (or statuses (providers/statuses path (or parser_opts {})))
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
