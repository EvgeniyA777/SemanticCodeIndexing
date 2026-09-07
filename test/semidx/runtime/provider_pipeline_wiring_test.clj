(ns semidx.runtime.provider-pipeline-wiring-test
  "plans/018 Stage 6a: the provider pipeline runs where real indexing happens.

  Until this seam existed the pipeline was only ever exercised by fixtures and
  shadow entry points, so nothing could compare it against the path actually in
  use. It stays default-off: the value of the seam is that it can be switched
  on, not that it is."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.core :as sci]
            [semidx.mcp.core :as mcp]
            [semidx.runtime.index :as idx]
            [semidx.runtime.provider-batch :as batch]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.usage-metrics :as usage]
            [semidx.test-support.scip-toolchain :as toolchain]))

(def ^:private java-corpus "fixtures/provider-authority/corpus/java")

(deftest mode-defaults-to-off-and-rejects-nonsense-test
  (is (= :off (idx/provider-pipeline-mode {})))
  (is (= :off (idx/provider-pipeline-mode {:provider_pipeline "not-a-mode"}))
      "an unknown mode falls back to off rather than to something surprising")
  (is (= :shadow (idx/provider-pipeline-mode {:provider_pipeline "shadow"})))
  (is (= :shadow (idx/provider-pipeline-mode {:provider_pipeline :shadow}))
      "string or keyword, because parser opts arrive from JSON as well"))

(deftest a-default-build-carries-no-provider-summary-test
  (testing "the seam costs nothing when it is off: no key, so no consumer can
            start depending on it by accident"
    (let [index (sci/create-index {:root_path java-corpus})]
      (is (not (contains? index :provider_summary)))
      (is (pos? (count (:units index))) "and the build itself is unaffected"))))

(deftest a-shadow-build-observes-the-pipeline-without-changing-the-index-test
  (let [plain (sci/create-index {:root_path java-corpus})
        shadowed (sci/create-index {:root_path java-corpus
                                    :parser_opts {:provider_pipeline "shadow"}})
        summary (:provider_summary shadowed)]
    (testing "the pipeline ran over the eligible files"
      (is (= "shadow" (:mode summary)))
      (is (= 2 (:files_observed summary)))
      (is (zero? (:files_failed summary)))
      (is (pos? (:fact_count summary))))

    (testing "authorities are reported, which is the point of observing at all"
      (is (seq (:authorities summary)))
      (is (every? string? (keys (:authorities summary)))))

    (testing "the snapshot itself is untouched"
      (is (= (count (:units plain)) (count (:units shadowed))))
      (is (= (set (keys (:units plain))) (set (keys (:units shadowed))))
          "same unit identities: shadow observation, not a second opinion"))))

(deftest a-failing-provider-cannot-fail-the-build-test
  (testing "a shadow observation must never take a real index down"
    (with-redefs [provider-execution/shadow-facts-for-file
                  (fn [_] (throw (ex-info "provider exploded" {})))]
      (let [index (sci/create-index {:root_path java-corpus
                                     :parser_opts {:provider_pipeline "shadow"}})
            summary (:provider_summary index)]
        (is (pos? (count (:units index))) "the build still succeeded")
        (is (= 2 (:files_failed summary)))
        (is (zero? (:fact_count summary))
            "and the failure is counted rather than silently absent")))))

(deftest the-summary-reaches-telemetry-only-when-it-exists-test
  (testing "plans/022 asked for a provider summary on the events; it appears
            exactly when the pipeline ran"
    (let [sink (usage/in-memory-usage-metrics)]
      (sci/create-index {:root_path java-corpus
                         :usage_metrics sink
                         :parser_opts {:provider_pipeline "shadow"}})
      (is (some? (get-in (first (usage/emitted-events sink)) [:payload :provider_summary]))))

    (let [sink (usage/in-memory-usage-metrics)]
      (sci/create-index {:root_path java-corpus :usage_metrics sink})
      (is (not (contains? (:payload (first (usage/emitted-events sink))) :provider_summary))
          "a default build records what it always recorded"))))

(deftest the-deployment-can-switch-observation-on-without-touching-callers-test
  (testing "an operator decides once for a server; a caller should not have to
            repeat it on every create_index, nor be able to forget it"
    (with-redefs [mcp/deployment-parser-opts (constantly {:provider_pipeline "shadow"})]
      (is (= :shadow (idx/provider-pipeline-mode (mcp/normalize-parser-opts nil)))
          "with no caller opts, the deployment setting applies")
      (is (= :shadow (idx/provider-pipeline-mode
                      (mcp/normalize-parser-opts {:clojure_engine :regex})))
          "and it survives caller opts that say nothing about it")
      (is (= :off (idx/provider-pipeline-mode
                   (mcp/normalize-parser-opts {:provider_pipeline "off"})))
          "but an explicit caller value still wins")))

  (testing "with nothing set, nothing changes"
    (with-redefs [mcp/deployment-parser-opts (constantly {})]
      (is (= :off (idx/provider-pipeline-mode (mcp/normalize-parser-opts nil))))
      (is (= mcp/default-parser-opts (mcp/normalize-parser-opts nil))))))

(defn- mcp-create-index-event
  "Drive the real MCP tool handler and return the usage event it emitted.

  The deployment layer is neutralised so the assertion depends on the caller's
  own parser opts rather than on whatever the developer's environment sets."
  [parser-opts]
  (with-redefs [mcp/deployment-parser-opts (constantly {})]
    (let [sink (usage/in-memory-usage-metrics)
          state (mcp/new-session-state {:usage-metrics sink
                                        :session-id "server-session-1"})]
      (mcp/handle-tools-call
       state
       {:name "create_index"
        :arguments (cond-> {:root_path (.getAbsolutePath (io/file java-corpus))}
                     parser-opts (assoc :parser_opts parser-opts))})
      (->> (usage/emitted-events sink)
           (filter #(= "create_index" (:operation %)))
           first))))

(deftest the-mcp-surface-records-the-summary-too-test
  (testing "the MCP transport suppresses the library's own event and emits this
            one instead, so a summary that rides only the library event never
            reaches a real session — which is the only place sessions happen"
    (let [event (mcp-create-index-event {:provider_pipeline "shadow"})]
      (is (some? (get-in event [:payload :provider_summary])))
      (is (= "shadow" (get-in event [:payload :provider_summary :mode])))))

  (testing "and a default build records exactly what it recorded before"
    (let [event (mcp-create-index-event nil)]
      (is (not (contains? (:payload event) :provider_summary))))))

(deftest the-summary-carries-the-tier-comparison-test
  (if (= "ready" (:state (get (batch/project-statuses ["java"] {}) "scip-java")))
    (let [summary (:provider_summary (sci/create-index {:root_path java-corpus
                                                        :parser_opts {:provider_pipeline "shadow"}}))]
      (testing "the project tier runs during a real build, which is what makes a
                comparison possible at all: file-scoped planning alone can only
                ever reach tree-sitter and regex"
        (is (= "ready" (get-in summary [:providers "scip-java" :result])))
        (is (= 2 (get-in summary [:providers "scip-java" :fresh])))
        (is (zero? (get-in summary [:providers "scip-java" :uncovered]))))

      (testing "and the observation reports how the two tiers relate, which the
                counts alone never said"
        (is (pos? (get-in summary [:comparison :agreed])))
        (is (pos? (get-in summary [:comparison :authority_upgrades]))
            "a symbol both tiers found is raised from heuristic to exact")
        (is (pos? (get-in summary [:comparison :multi_provider_symbols]))
            "and it collapses to one canonical fact carrying both providers"))

      (testing "latency is measured around the run rather than beside it"
        (is (pos? (:total_elapsed_ms summary)))))
    (toolchain/unresolved! "scip-java toolchain" "provider pipeline comparison test")))
