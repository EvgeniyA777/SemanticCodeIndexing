(ns semidx.runtime.provider-execution
  "Stage 2 provider execution orchestrator (plans/018).

  Executes a ProviderPlan with bounded concurrency, per-provider timeouts,
  failure isolation, gap tracking, and diagnostics, and emits one `FactBatch`
  per provider for `semidx.runtime.fact-arbitration`.

  It knows how to run providers and how to report what happened. It does not
  decide authority, merge semantics, retrieval confidence, or transport shapes.

  Everything here is shadow work: it never writes to the active snapshot and
  never changes what `adapters/parse-file` returns."
  (:require [semidx.runtime.fact-arbitration :as fact-arbitration]
            [semidx.runtime.languages.shared :as shared]
            [semidx.runtime.provider-selection :as provider-selection]
            [semidx.runtime.providers :as providers])
  (:import [java.io File]
           [java.util.concurrent TimeUnit TimeoutException]))

(defn- failure-batch
  [provider-id operation source-identity diagnostic]
  {:provider_id provider-id
   :provider_version (:provider_version (providers/descriptor provider-id))
   :operation (name operation)
   :freshness "unknown"
   :source_identity source-identity
   :coverage {:paths [] :fact_kinds [] :complete false}
   :diagnostics [diagnostic]
   :facts []})

(defn- run-one
  "Run a single provider, converting any failure into a batch that says so.

  A provider that throws or hangs must not take the run down with it, and must
  not disappear either: the batch it produces records the failure and reports
  incomplete coverage."
  [{:keys [provider_id operation]} {:keys [path source_identity timeout_ms run-provider]
                                    :as request}]
  (let [provider-id provider_id
        runner (or run-provider providers/run-provider)
        task (future
               (try
                 {:ok (runner provider-id (-> request
                                              (dissoc :run-provider)
                                              (assoc :operation operation)))}
                 (catch Throwable t
                   {:error (or (.getMessage t) (str (class t)))
                    :error_class (.getName (class t))})))
        outcome (deref task timeout_ms ::timeout)]
    (cond
      (= ::timeout outcome)
      (do (future-cancel task)
          (failure-batch provider-id operation source_identity
                         {:code :provider_timeout
                          :provider_id provider-id
                          :timeout_ms timeout_ms
                          :message (str provider-id " exceeded " timeout_ms "ms on " path)}))

      (:error outcome)
      (failure-batch provider-id operation source_identity
                     {:code :provider_failed
                      :provider_id provider-id
                      :error_class (:error_class outcome)
                      :message (str provider-id " failed on " path ": " (:error outcome))})

      :else
      (let [{:keys [facts diagnostics parser_mode]} (:ok outcome)]
        {:provider_id provider-id
         :provider_version (:provider_version (providers/descriptor provider-id))
         :operation (name operation)
         :freshness "exact"
         :source_identity source_identity
         :coverage {:paths [path] :fact_kinds ["unit"] :complete true}
         :diagnostics (vec (concat diagnostics
                                   (when parser_mode
                                     [{:code :parser_mode
                                       :provider_id provider-id
                                       :parser_mode parser_mode}])))
         :facts (vec facts)}))))

(defn- run-bounded
  "Run planned tasks with at most `concurrency` in flight at a time."
  [tasks request concurrency]
  (->> (partition-all (max 1 (long concurrency)) tasks)
       (mapcat (fn [chunk]
                 (->> chunk
                      (mapv (fn [task] (future (run-one task request))))
                      (mapv deref))))
       vec))

(defn- operation-gaps
  "Operations the plan could not cover, and why.

  A gap is the difference between what the catalog claims and what actually
  produced facts; without it, a run where every provider failed looks the same
  as a file with nothing in it."
  [plan batches]
  (vec (for [[operation {:keys [providers excluded]}] (:operations plan)
             :let [produced (->> batches
                                 (filter #(= (name operation) (:operation %)))
                                 (filter #(seq (:facts %))))]
             :when (empty? produced)]
         {:operation operation
          :planned_providers (mapv :provider_id providers)
          :excluded (mapv #(select-keys % [:provider_id :reason :reason_codes]) excluded)
          :reason (cond
                    (empty? providers) "no_provider_admitted"
                    :else "no_provider_produced_facts")})))

(defn execute-plan
  "Execute a ProviderPlan and return its batches, gaps, and diagnostics.

  `run-provider` may be injected to drive the orchestrator without real
  parsers."
  [plan {:keys [root_path lines parser_opts run-provider] :as opts}]
  (let [policy (:execution_policy plan)
        tasks (provider-selection/planned-tasks plan)
        request {:root_path root_path
                 :path (:path plan)
                 :lines lines
                 :parser_opts (or parser_opts {})
                 :source_identity (:source_identity plan)
                 :timeout_ms (:timeout_ms policy)
                 :run-provider run-provider}
        batches (run-bounded tasks request (:max_concurrency policy))]
    {:path (:path plan)
     :mode (:mode plan)
     :source_identity (:source_identity plan)
     :batches batches
     :gaps (operation-gaps plan batches)
     :diagnostics (vec (mapcat :diagnostics batches))}))

(defn shadow-facts-for-file
  "Plan, execute, and arbitrate one file's providers in shadow mode.

  This is the Stage 2 seam end to end. Its result is a shadow artifact: no
  caller writes it into a snapshot, and default extraction is untouched."
  [{:keys [root_path path lines parser_opts mode denied_providers execution_policy
           run-provider]
    :or {mode "shadow"}}]
  (let [lines (or lines (shared/slurp-lines (File. (str root_path) (str path))))
        source-identity (providers/source-identity {:root_path root_path
                                                    :path path
                                                    :lines lines})
        plan (provider-selection/provider-plan
              {:path path
               :source_identity source-identity
               :mode mode
               :parser_opts parser_opts
               :denied_providers denied_providers
               :execution_policy execution_policy})
        execution (execute-plan plan {:root_path root_path
                                      :lines lines
                                      :parser_opts parser_opts
                                      :run-provider run-provider})
        arbitrated (fact-arbitration/arbitrate-batches (:batches execution))]
    {:path path
     :mode mode
     :plan plan
     :execution (dissoc execution :batches)
     :facts (:facts arbitrated)
     :diagnostics (:diagnostics arbitrated)
     :errors (:errors arbitrated)
     :batches (:batches arbitrated)
     ;; Pre-arbitration provider batches (facts included), kept so a shadow
     ;; comparison can co-arbitrate these with another tier's facts in one pass.
     :raw_batches (:batches execution)}))
