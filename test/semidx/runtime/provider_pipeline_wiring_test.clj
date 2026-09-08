(ns semidx.runtime.provider-pipeline-wiring-test
  "plans/018 Stage 6a: the provider pipeline runs where real indexing happens.

  Until this seam existed the pipeline was only ever exercised by fixtures and
  shadow entry points, so nothing could compare it against the path actually in
  use. It stays default-off: the value of the seam is that it can be switched
  on, not that it is."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.core :as sci]
            [semidx.mcp.core :as mcp]
            [semidx.runtime.http :as runtime-http]
            [semidx.runtime.index :as idx]
            [semidx.runtime.provider-batch :as batch]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.storage :as storage]
            [semidx.runtime.usage-metrics :as usage]
            [semidx.test-support.scip-toolchain :as toolchain])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:private java-corpus "fixtures/provider-authority/corpus/java")

(deftest mode-defaults-to-authority-and-rejects-nonsense-test
  (is (= :authority (idx/provider-pipeline-mode {}))
      "the default since 2026-09-08; :off is now the deliberate opt-out")
  (is (= :authority (idx/provider-pipeline-mode {:provider_pipeline "not-a-mode"}))
      "an unknown mode resolves to the default rather than silently opting the
       caller out of the semantic tier")
  (is (= :off (idx/provider-pipeline-mode {:provider_pipeline "off"}))
      "and the opt-out is available by name")
  (is (= :shadow (idx/provider-pipeline-mode {:provider_pipeline "shadow"})))
  (is (= :shadow (idx/provider-pipeline-mode {:provider_pipeline :shadow}))
      "string or keyword, because parser opts arrive from JSON as well"))

(deftest an-opted-out-build-carries-no-provider-summary-test
  (testing "the seam costs nothing when it is off: no key, so no consumer can
            start depending on it by accident"
    (let [index (sci/create-index {:root_path java-corpus
                                   :parser_opts {:provider_pipeline "off"}})]
      (is (not (contains? index :provider_summary)))
      (is (pos? (count (:units index))) "and the build itself is unaffected"))))

(deftest a-shadow-build-observes-the-pipeline-without-changing-the-index-test
  (let [plain (sci/create-index {:root_path java-corpus
                                 :parser_opts {:provider_pipeline "off"}})
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
      (sci/create-index {:root_path java-corpus :usage_metrics sink
                         :parser_opts {:provider_pipeline "off"}})
      (is (not (contains? (:payload (first (usage/emitted-events sink))) :provider_summary))
          "an opted-out build records what it always recorded"))))

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

  (testing "with nothing set, the build gets the default"
    (with-redefs [mcp/deployment-parser-opts (constantly {})]
      (is (= :authority (idx/provider-pipeline-mode (mcp/normalize-parser-opts nil))))
      (is (= mcp/default-parser-opts (mcp/normalize-parser-opts nil))
          "and the parser opts themselves are untouched: the default lives in the
           runtime, not in the MCP defaults"))))

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

  (testing "and an opted-out build records exactly what it recorded before"
    (let [event (mcp-create-index-event {:provider_pipeline "off"})]
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

;; --- Stage 6.3: the authority model is part of workspace identity -------------

(deftest a-snapshot-is-never-served-across-authority-models-test
  (testing "the failure this prevents is silent: identical files under a
            different authority model produce different units and different
            labels, so reusing across models hands back a snapshot the caller
            did not ask for"
    (let [store (storage/in-memory-storage)
          build (fn [mode]
                  (sci/create-index {:root_path java-corpus
                                     :storage store
                                     :load_latest true
                                     :parser_opts {:provider_pipeline mode}}))
          first-authority (build "authority")
          repeat-authority (build "authority")
          switched-off (build "off")
          back-again (build "authority")]

      (testing "an unchanged model still reuses, so caching is not the casualty"
        (is (= "reuse" (get-in repeat-authority [:index_lifecycle :lifecycle_action])))
        (is (true? (get-in repeat-authority [:index_lifecycle :reused_snapshot])))
        (is (= (:snapshot_id first-authority) (:snapshot_id repeat-authority))))

      (testing "switching the pipeline off rebuilds instead of returning the
                authority-labelled snapshot"
        (is (= "full_rebuild" (get-in switched-off [:index_lifecycle :lifecycle_action])))
        (is (false? (get-in switched-off [:index_lifecycle :reused_snapshot])))
        (is (empty? (keep :authority (vals (:units switched-off))))
            "and the units it returns carry no authority, as an off build must"))

      (testing "and switching back rebuilds again rather than serving the off snapshot"
        (is (= "full_rebuild" (get-in back-again [:index_lifecycle :lifecycle_action]))))

      (testing "the rebuild says why it happened, rather than reporting the
                fallback reason a whitelist would have given it"
        (is (= "authority_model_changed"
               (get-in switched-off [:index_lifecycle :rebuild_reason])))
        (is (= "authority_model_changed"
               (get-in back-again [:index_lifecycle :rebuild_reason])))))))

;; --- Stage 6.4: the authority build reports on the same key ------------------

(deftest an-authority-build-reports-what-it-produced-test
  (let [index (sci/create-index {:root_path java-corpus
                                 :parser_opts {:provider_pipeline "authority"}})
        summary (:provider_summary index)]
    (testing "switching the pipeline on must not cost the operator the observation:
              shadow reported, authority reported nothing at all before this"
      (is (some? summary))
      (is (= "authority" (:mode summary)))
      (is (= ["java"] (:languages summary)))
      (is (= 2 (:files_observed summary))))

    (testing "the counts describe the snapshot rather than a shadow run"
      (is (= (count (filter #(= "java" (:language %)) (vals (:units index))))
             (:units_observed summary)))
      (is (= (:units_observed summary)
             (reduce + 0 (vals (:authorities summary))))
          "every observed unit carries an authority, because the merge assigns one")
      (is (contains? summary :units_supplied))
      (is (contains? summary :files_degraded)))

    (testing "and it does not borrow the shadow comparison, which names two tiers
              neither of which is the snapshot"
      (is (not (contains? summary :comparison))))))

(deftest the-authority-summary-reaches-a-real-mcp-session-test
  (testing "the same defect Stage 6a hit: the MCP transport emits its own event
            from a literal map, so a summary that rides only the library event
            never reaches the only surface that produces sessions"
    (let [event (mcp-create-index-event {:provider_pipeline "authority"})]
      (is (= "authority" (get-in event [:payload :provider_summary :mode])))
      (is (some? (get-in event [:payload :provider_summary :authorities]))))))

(deftest every-surface-that-can-carry-the-summary-does-test
  ;; plans/018 Stage 6.4. Telemetry already carried the summary; a caller reading
  ;; a response could not see the same thing, which is a strange kind of
  ;; observability — visible to the operator's database and not to the client
  ;; whose build it describes.
  (let [parser-opts {:provider_pipeline "authority"}
        abs-root (.getAbsolutePath (io/file java-corpus))]

    (testing "library"
      (let [index (sci/create-index {:root_path java-corpus :parser_opts parser-opts})]
        (is (= "authority" (get-in index [:provider_summary :mode])))))

    (testing "MCP tool response, not only the usage event"
      (with-redefs [mcp/deployment-parser-opts (constantly {})]
        (let [state (mcp/new-session-state {})
              result (mcp/tool-create-index state {:root_path abs-root
                                                   :parser_opts parser-opts})]
          (is (= "authority" (get-in result [:provider_summary :mode]))))))

    (testing "HTTP"
      (let [server (runtime-http/start-server {:host "127.0.0.1" :port 0})
            port (-> server .getAddress .getPort)]
        (try
          (let [body (json/write-str {:root_path abs-root :parser_opts parser-opts})
                request (-> (HttpRequest/newBuilder
                             (URI/create (str "http://127.0.0.1:" port "/v1/index/create")))
                            (.header "Content-Type" "application/json")
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            (.build))
                response (.send (HttpClient/newHttpClient) request
                                (HttpResponse$BodyHandlers/ofString))
                payload (json/read-str (.body response) :key-fn keyword)]
            (is (= 200 (.statusCode response)))
            (is (= "authority" (get-in payload [:provider_summary :mode]))))
          (finally (.stop server 0)))))

    (testing "and a build that opts out answers exactly what it answered before"
      (let [index (sci/create-index {:root_path java-corpus
                                     :parser_opts {:provider_pipeline "off"}})]
        (is (not (contains? index :provider_summary)))))))
