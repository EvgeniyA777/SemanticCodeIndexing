(ns semidx.runtime.provider-negative-cache-test
  "Stage 6c (plans/018, ADR-046): the process-local negative cache for
  project-scoped providers.

  What is being proved is a policy, not an indexer, so every test drives the
  boundary with an injected role and an injected cache: how many times the run
  role is called is the observable, and a provider that stops being called must
  still be visible in the execution. The clock is injected too — a TTL test that
  slept would be slow and flaky for no gain.

  The workspace is a fresh temporary directory because eligibility is read off
  the filesystem: a directory with no `package.json` is exactly the case the
  cache is allowed to remember."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.provider-batch :as batch]
            [semidx.runtime.provider-negative-cache :as negative-cache]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.providers :as providers])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root []
  (str (Files/createTempDirectory "semidx-negative-cache" (into-array FileAttribute []))))

(defn- ready-status []
  {:state "ready" :reason_codes [] :cli_path "/tmp/scip-typescript"
   :observed_at "2026-09-06T00:00:00Z"})

(defn- failed-result
  "A failed project result in the shape the SCIP adapters return."
  [code]
  {:provider_id "scip-typescript"
   :provider_version "1"
   :result "failed"
   :facts []
   :raw_facts []
   :batches []
   :errors []
   :diagnostics [{:code code :message "scip-typescript index exited 1"}]
   :coverage {:covered_paths [] :stale_documents [] :invalid_documents []
              :withheld_fact_count 0 :complete false}
   :unmapped []})

(defn- execute
  "Run the project plan for `root` once, counting how often the run role fired."
  [root {:keys [calls run-fn cache now-fn ttl-ms]
         :or {run-fn (fn [_] (failed-result :scip_index_failed))}}]
  (batch/execute-project-plan
   (selection/project-plan {:root_path root
                            :languages ["typescript"]
                            :statuses {"scip-typescript" (ready-status)}})
   (cond-> {:root_path root
            :project_roles {"scip-typescript"
                            {:status-fn (fn [_] (ready-status))
                             :run-fn (fn [opts]
                                       (when calls (swap! calls inc))
                                       (run-fn opts))}}
            :provider_negative_cache cache}
     now-fn (assoc :provider_negative_cache_now_fn now-fn)
     ttl-ms (assoc :provider_negative_cache_ttl_ms ttl-ms))))

(defn- result-of [execution]
  (get-in execution [:results "scip-typescript"]))

(deftest a-cacheable-failure-is-not-retried-test
  (let [root (temp-root)
        cache (atom {})
        calls (atom 0)]
    (execute root {:calls calls :cache cache})
    (is (= 1 @calls))
    (let [second-run (execute root {:calls calls :cache cache})]
      (is (= 1 @calls)
          "the second build reuses the negative result instead of paying for the run")
      (is (= "skipped" (:result (result-of second-run)))
          "not attempted is not the same as attempted and failed"))))

(deftest a-cache-hit-stays-visible-in-the-execution-test
  (let [root (temp-root)
        cache (atom {})]
    (execute root {:cache cache})
    (let [execution (execute root {:cache cache})
          result (result-of execution)
          diagnostic (first (:diagnostics result))]
      (testing "the provider is still in the results, not silently gone"
        (is (contains? (:results execution) "scip-typescript"))
        (is (= ["scip-typescript"] (:planned_provider_ids execution))))

      (testing "the diagnostic names both the skip and what it stands for"
        (is (= :cached_negative_result (:code diagnostic)))
        (is (= :scip_index_failed (:original_code diagnostic)))
        (is (some? (:cached_at diagnostic)))
        (is (some? (:expires_at diagnostic))))

      (testing "the execution diagnostics carry it too, so a summary can count it"
        (is (= [:cached_negative_result] (mapv :code (:diagnostics execution))))
        (is (= ["scip-typescript"] (mapv :provider_id (:diagnostics execution)))))

      (testing "a skipped provider survives the summary that strips the facts"
        (let [summarized (batch/summarize-execution execution)]
          (is (= "skipped" (get-in summarized [:results "scip-typescript" :result])))
          (is (= 0 (get-in summarized [:results "scip-typescript" :fact_count])))))

      (testing "and it contributes no coverage, exactly like the failure it replaces"
        (is (= {} (batch/batch-coverage execution)))
        (is (= "skipped" (get-in (batch/document-states execution ["src/a.ts"])
                                 ["scip-typescript" :result])))))))

(deftest a-failure-that-cannot-be-classified-is-retried-test
  (testing "a throw is a broken run, not a negative result about the workspace"
    (let [root (temp-root)
          cache (atom {})
          calls (atom 0)
          run-fn (fn [_] (throw (ex-info "toolchain exploded" {})))]
      (execute root {:calls calls :cache cache :run-fn run-fn})
      (execute root {:calls calls :cache cache :run-fn run-fn})
      (is (= 2 @calls))
      (is (= {} @cache) "nothing was remembered")))

  (testing "a code outside the allow-list is retried"
    (let [root (temp-root)
          cache (atom {})
          calls (atom 0)
          run-fn (fn [_] (failed-result :scip_toolchain_timeout))]
      (execute root {:calls calls :cache cache :run-fn run-fn})
      (execute root {:calls calls :cache cache :run-fn run-fn})
      (is (= 2 @calls))
      (is (= {} @cache))))

  (testing "the generic index failure is retried when the workspace IS a project
            of this kind: there the failure describes today's run, not the
            workspace, and remembering it would suppress the exact tier"
    (let [root (temp-root)
          cache (atom {})
          calls (atom 0)]
      (spit (io/file root "package.json") "{}")
      (execute root {:calls calls :cache cache})
      (execute root {:calls calls :cache cache})
      (is (= 2 @calls))
      (is (= {} @cache)))))

(deftest an-expired-negative-result-is-retried-test
  (let [root (temp-root)
        cache (atom {})
        calls (atom 0)
        clock (atom 1000)]
    (execute root {:calls calls :cache cache :now-fn #(deref clock) :ttl-ms 60000})
    (swap! clock + 59000)
    (execute root {:calls calls :cache cache :now-fn #(deref clock) :ttl-ms 60000})
    (is (= 1 @calls) "still inside the TTL")
    (swap! clock + 2000)
    (execute root {:calls calls :cache cache :now-fn #(deref clock) :ttl-ms 60000})
    (is (= 2 @calls) "past the TTL the provider gets another chance")))

(deftest a-changed-workspace-is-retried-test
  (testing "adding the manifest that decides eligibility invalidates the entry"
    (let [root (temp-root)
          cache (atom {})
          calls (atom 0)]
      (execute root {:calls calls :cache cache})
      (is (= 1 @calls))
      (spit (io/file root "package.json") "{}")
      (execute root {:calls calls :cache cache})
      (is (= 2 @calls))))

  (testing "so does a provider version bump: evidence from one version says
            nothing about the next"
    (let [root (temp-root)
          cache (atom {})
          calls (atom 0)
          original providers/descriptor]
      (execute root {:calls calls :cache cache})
      (is (= 1 @calls))
      (with-redefs [providers/descriptor (fn [provider-id]
                                           (some-> (original provider-id)
                                                   (assoc :provider_version "2")))]
        (execute root {:calls calls :cache cache}))
      (is (= 2 @calls))))

  (testing "and so does a toolchain the status probe now resolves differently"
    (let [root (temp-root)
          cache (atom {})
          calls (atom 0)]
      (execute root {:calls calls :cache cache})
      (batch/execute-project-plan
       (selection/project-plan {:root_path root
                                :languages ["typescript"]
                                :statuses {"scip-typescript"
                                           (assoc (ready-status)
                                                  :cli_path "/opt/other/scip-typescript")}})
       {:root_path root
        :project_roles {"scip-typescript"
                        {:status-fn (fn [_] (ready-status))
                         :run-fn (fn [_] (swap! calls inc) (failed-result :scip_index_failed))}}
        :provider_negative_cache cache})
      (is (= 2 @calls)))))

(deftest the-cache-can-be-switched-off-test
  (let [root (temp-root)
        calls (atom 0)]
    (execute root {:calls calls :cache false})
    (execute root {:calls calls :cache false})
    (is (= 2 @calls)
        "false runs every admitted provider unconditionally, which is the
         rollback path if a remembered negative ever proves wrong")))

(deftest an-injected-cache-is-the-only-one-written-test
  (let [root (temp-root)
        cache (atom {})]
    (execute root {:cache cache})
    (is (= [(negative-cache/cache-key "scip-typescript" root)] (keys @cache))
        "the entry is keyed by canonical root, provider, and provider version")
    (let [entry (val (first @cache))]
      (is (= :scip_index_failed (:failure_code entry)))
      (is (= "scip-typescript" (:provider_id entry)))
      (is (some? (:recorded_at_ms entry)))
      (is (= negative-cache/default-ttl-ms (:ttl_ms entry))))))

(deftest classification-defaults-to-retry-test
  (let [eligible {:manifests_declared true :manifest_present false}]
    (testing "admitted only with eligibility evidence behind the generic code"
      (is (true? (negative-cache/cacheable-negative-result?
                  (failed-result :scip_index_failed) eligible)))
      (is (false? (negative-cache/cacheable-negative-result?
                   (failed-result :scip_index_failed)
                   {:manifests_declared true :manifest_present true})))
      (is (false? (negative-cache/cacheable-negative-result?
                   (failed-result :scip_index_failed)
                   {:manifests_declared false :manifest_present false}))
          "a provider that declares no manifest offers no eligibility evidence"))

    (testing "anything unclassified is retried"
      (is (false? (negative-cache/cacheable-negative-result?
                   (assoc (failed-result :scip_index_failed) :diagnostics []) eligible))
          "a failure with no code at all")
      (is (false? (negative-cache/cacheable-negative-result?
                   (assoc (failed-result :scip_index_failed)
                          :diagnostics [{:code :scip_index_failed}
                                        {:code :scip_runtime_classes_unavailable}])
                   eligible))
          "one unclassified code is enough to make the whole result unrememberable")
      (is (false? (negative-cache/cacheable-negative-result?
                   (assoc (failed-result :scip_index_failed) :result "unavailable")
                   eligible))
          "an absent toolchain costs nothing to observe and is not cached"))))
