(ns semidx.runtime.provider-batch
  "Stage 4.5 of the Semantic Provider Authority Migration (plans/018, ADR-046):
  the project-scoped provider execution boundary. Shadow / default-off.

  A SCIP provider indexes a project once and then yields facts for the documents
  that run covered. That is a different execution shape from
  `semidx.runtime.provider-execution`, which runs one provider against one file,
  so it gets its own boundary rather than a mode flag on the per-file one.

  This namespace is also the only place that requires both SCIP adapters. They
  load generated protobuf classes through `semidx.runtime.scip`, and keeping
  that dependency here is what lets `semidx.runtime.providers` and
  `semidx.runtime.provider-selection` — the per-file planning path — stay free
  of it.

  What it does:

  1. observes project provider status through each adapter's own probe;
  2. plans admitted providers with `provider-selection/project-plan`, which uses
     the same admission and exclusion rules as the per-file plan;
  3. runs each admitted provider exactly once, isolating failures;
  4. reports coverage in one document-state vocabulary for every language;
  5. hands the resulting facts to per-file execution through the injected
     `run-provider` role, so a project provider's evidence reaches arbitration
     on the same path as every other tier.

  What it does not do: change default extraction, write a snapshot, decide
  authority, or branch on a language. Nothing here runs unless a caller asks for
  it, and a provider is planned only when its status was actually observed."
  (:require [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.provider-selection :as provider-selection]
            [semidx.runtime.providers :as providers]
            [semidx.runtime.providers.scip-java :as scip-java]
            [semidx.runtime.providers.scip-typescript :as scip-typescript])
  (:import [java.time Instant]))

(def project-roles
  "Executable roles for the project-scoped providers, keyed by provider id.

  The catalog owns the descriptors; this map owns the two functions a project
  provider must supply: a status probe that never runs the indexer, and a run
  function returning the project result contract below."
  {"scip-typescript" {:status-fn scip-typescript/provider-status
                      :run-fn scip-typescript/shadow-facts-for-project}
   "scip-java" {:status-fn scip-java/provider-status
                :run-fn scip-java/shadow-facts-for-project}})

(def result-states
  "The `:result` values a project provider may report. `unavailable` means the
  toolchain is absent and the caller degrades; `failed` means it ran and
  errored. Neither is an exception."
  #{"ready" "unavailable" "failed"})

(defn- now-iso [] (str (Instant/now)))

(defn- failure-result
  "A project result for a provider that could not be run at all, in the same
  shape a provider would have returned. A failure that produced no result map
  must still be readable as a result, not as an absence."
  [provider-id code message]
  {:provider_id provider-id
   :provider_version (:provider_version (providers/descriptor provider-id))
   :result "failed"
   :facts []
   :raw_facts []
   :batches []
   :errors []
   :diagnostics [{:code code :provider_id provider-id :message message}]
   :coverage {:covered_paths []
              :stale_documents []
              :invalid_documents []
              :withheld_fact_count 0
              :complete false}
   :unmapped []})

;; ---------------------------------------------------------------------------
;; Status
;; ---------------------------------------------------------------------------

(defn project-statuses
  "Observe every project provider for `languages`, keyed by provider id.

  `semidx.runtime.providers/provider-status` deliberately refuses these ids: it
  cannot test a SCIP toolchain and would report `ready` for it. Each adapter's
  own probe is called instead. A probe that throws is reported as unavailable
  with the reason on the status, never swallowed — an unobserved provider and a
  provider whose probe blew up must not look alike.

  `roles` overrides `project-roles`; it is the only substitution seam, so an
  injected role gets the same isolation a registered one does."
  ([] (project-statuses nil {}))
  ([languages opts] (project-statuses languages opts project-roles))
  ([languages opts roles]
   (into (sorted-map)
         (map (fn [descriptor]
                (let [provider-id (:provider_id descriptor)
                      status-fn (get-in roles [provider-id :status-fn])]
                  [provider-id
                   (if-not status-fn
                     {:provider_id provider-id
                      :observed_at (now-iso)
                      :state "unavailable"
                      :reason_codes ["no_project_role_registered"]}
                     (try
                       (status-fn (or opts {}))
                       (catch Throwable t
                         {:provider_id provider-id
                          :observed_at (now-iso)
                          :state "unavailable"
                          :reason_codes ["provider_status_probe_failed"]
                          :message (or (.getMessage t) (str (class t)))})))]))
              (providers/descriptors-for-project languages)))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- run-one-project-provider
  "Run one project provider once, converting every failure into a result.

  A provider that throws, or returns something outside the result contract, must
  not take the run down and must not disappear either. Substitution happens on
  `roles`, never on this wrapper, so an injected role is isolated exactly like a
  registered one."
  [roles provider-id opts]
  (let [run-fn (get-in roles [provider-id :run-fn])]
    (if-not run-fn
      (failure-result provider-id :no_project_role_registered
                      (str provider-id " has no registered project run role"))
      (try
        (let [result (run-fn opts)]
          (if (contains? result-states (:result result))
            result
            (failure-result provider-id :project_provider_contract_violation
                            (str provider-id " returned :result "
                                 (pr-str (:result result))
                                 ", which is not one of " (pr-str (sort result-states))))))
        (catch Throwable t
          (failure-result provider-id :project_provider_failed
                          (str provider-id " threw during its project run: "
                               (or (.getMessage t) (str (class t))))))))))

(defn- provider-opts
  "Options forwarded to a provider's run function: the project root, the stale
  gate expectations, and the toolchain-resolution keys each adapter reads.
  Orchestration keys stay here."
  [opts]
  (dissoc opts
          :run-provider :project_roles :paths :languages :parser_opts
          :mode :denied_providers :execution_policy :project_statuses))

(defn execute-project-plan
  "Run every provider the project plan admits, once each, in plan order.

  Returns the per-provider results plus their diagnostics. Order is the plan's
  deterministic provider order, and each provider is independent: one
  unavailable or failing toolchain leaves the others untouched. `:project_roles`
  substitutes the role registry."
  [plan {:keys [project_roles] :as opts}]
  (let [roles (or project_roles project-roles)
        provider-ids (provider-selection/planned-provider-ids plan)
        forwarded (provider-opts opts)
        results (into (sorted-map)
                      (map (fn [provider-id]
                             [provider-id (run-one-project-provider roles provider-id forwarded)]))
                      provider-ids)]
    {:root_path (:root_path plan)
     :mode (:mode plan)
     :planned_provider_ids (vec provider-ids)
     :results results
     :diagnostics (vec (for [[provider-id result] results
                             diagnostic (:diagnostics result)]
                         (update diagnostic :provider_id #(or % provider-id))))}))

(defn summarize-execution
  "The execution envelope without the fact payload.

  Facts reach a caller through the per-file results, which arbitrate them; a
  project-level copy would double the size of the shadow artifact for nothing."
  [execution]
  (update execution :results
          (fn [results]
            (into (sorted-map)
                  (map (fn [[provider-id result]]
                         [provider-id
                          (-> (select-keys result [:provider_id :provider_version :result
                                                   :reason_codes :coverage :diagnostics
                                                   :errors])
                              (assoc :fact_count (count (:facts result))
                                     :raw_fact_count (count (:raw_facts result))))]))
                  results))))

;; ---------------------------------------------------------------------------
;; Batch -> per-file planning and execution
;; ---------------------------------------------------------------------------

(defn batch-coverage
  "`provider_id -> covered paths`, the map `provider-plan` takes as
  `:batch_coverage`.

  Only a `ready` run contributes coverage, and only for documents that passed
  the freshness gate. An unavailable, failed, stale, or path-invalid document
  therefore produces no exact contribution at all: the per-file plan never sees
  the provider and degrades to the file-scoped tiers on its own."
  [execution]
  (into (sorted-map)
        (keep (fn [[provider-id result]]
                (when (= "ready" (:result result))
                  (when-let [paths (seq (get-in result [:coverage :covered_paths]))]
                    [provider-id (vec paths)]))))
        (:results execution)))

(defn- facts-index
  "`provider_id -> {[path operation] [facts]}` over the pre-arbitration facts of
  a run. Path comes from the canonical fact key, so it is the workspace-relative
  path the normalizer resolved, not a provider-native spelling."
  [execution]
  (into {}
        (map (fn [[provider-id result]]
               [provider-id (group-by (fn [fact]
                                        [(get-in fact [:key :path])
                                         (-> fact :evidence first :operation)])
                                      (:raw_facts result))]))
        (:results execution)))

(defn batch-run-provider
  "The `run-provider` role for per-file execution over a completed batch.

  A project provider does not parse the file. Its facts were produced once by
  the project run, and this role hands back the ones belonging to the
  (path, operation) the per-file plan admitted it for. Every other provider goes
  to `delegate`, or to the file catalog when no delegate was injected."
  ([execution] (batch-run-provider execution nil))
  ([execution delegate]
   (let [index (facts-index execution)]
     (fn [provider-id {:keys [path operation] :as request}]
       (if-let [by-key (get index provider-id)]
         {:facts (vec (get by-key [path (name operation)] []))
          :diagnostics []
          :parser_mode nil}
         ((or delegate providers/run-provider) provider-id request))))))

(defn document-states
  "Per-provider document states in one vocabulary for every language.

  `fresh` documents anchor exact evidence; `stale` and `invalid` were dropped by
  the shared gate in `semidx.runtime.providers.scip-adapter`; `uncovered` is
  what the run never accounted for among the paths the caller asked about. A
  reader needs all four before treating anything as exact, and both languages
  must answer in the same words."
  [execution paths]
  (let [requested (vec (distinct paths))]
    (into (sorted-map)
          (map (fn [[provider-id result]]
                 (let [descriptor (providers/descriptor provider-id)
                       coverage (:coverage result)
                       fresh (vec (:covered_paths coverage))
                       stale (vec (:stale_documents coverage))
                       invalid (vec (:invalid_documents coverage))
                       accounted (set (concat fresh stale invalid))
                       eligible (filterv #(providers/selects-path? descriptor %) requested)]
                   [provider-id {:result (:result result)
                                 :fresh fresh
                                 :stale stale
                                 :invalid invalid
                                 :uncovered (vec (remove accounted eligible))
                                 :withheld_fact_count (or (:withheld_fact_count coverage) 0)
                                 :complete (boolean (:complete coverage))}])))
          (:results execution))))

(defn- languages-for
  "Languages whose project providers select at least one of `paths`."
  [paths]
  (->> (providers/descriptors-for-project)
       (filter (fn [descriptor]
                 (some #(providers/selects-path? descriptor %) paths)))
       (mapcat :languages)
       distinct
       vec))

(defn shadow-facts-for-project
  "Run the project providers over `:root_path`, then plan and execute every path
  in `:paths` with their coverage available.

  This is the Stage 4.5 seam end to end. Its result is a shadow artifact: no
  caller writes it into a snapshot, `adapters/parse-file` is untouched, and a
  path outside a fresh batch is planned exactly as it was before this stage.

  Options:
  - `:root_path` (required) and `:paths` — the workspace-relative files to plan;
  - `:languages` — narrows the project providers; derived from `:paths` when
    absent;
  - `:project_statuses` — pre-observed statuses, mostly for tests;
  - `:project_roles` — role registry override, the substitution seam;
  - `:run-provider` — file-scoped execution role, forwarded to per-file runs;
  - toolchain and stale-gate keys are forwarded to each adapter unchanged."
  [{:keys [root_path paths languages parser_opts mode denied_providers
           execution_policy project_statuses project_roles run-provider]
    :or {mode "shadow"}
    :as opts}]
  (when-not root_path
    (throw (ex-info "shadow-facts-for-project requires :root_path"
                    {:error_code :missing_root_path})))
  (let [paths (vec paths)
        languages (vec (or (seq languages) (languages-for paths)))
        roles (or project_roles project-roles)
        statuses (or project_statuses
                     (project-statuses languages (provider-opts opts) roles))
        plan (provider-selection/project-plan {:root_path root_path
                                               :languages languages
                                               :mode mode
                                               :execution_policy execution_policy
                                               :denied_providers denied_providers
                                               :statuses statuses})
        execution (execute-project-plan plan opts)
        coverage (batch-coverage execution)
        runner (batch-run-provider execution run-provider)
        files (mapv (fn [path]
                      (provider-execution/shadow-facts-for-file
                       {:root_path root_path
                        :path path
                        :parser_opts parser_opts
                        :mode mode
                        :denied_providers denied_providers
                        :execution_policy execution_policy
                        :batch_coverage coverage
                        :observed_statuses statuses
                        :run-provider runner}))
                    paths)]
    {:root_path root_path
     :mode mode
     :languages languages
     :project_plan plan
     :project_execution (summarize-execution execution)
     :batch_coverage coverage
     :documents (document-states execution paths)
     :files files
     :diagnostics (vec (concat (:diagnostics execution)
                               (mapcat :diagnostics files)))}))
