(ns semidx.runtime.provider-pipeline-wiring-test
  "plans/018 Stage 6a: the provider pipeline runs where real indexing happens.

  Until this seam existed the pipeline was only ever exercised by fixtures and
  shadow entry points, so nothing could compare it against the path actually in
  use. It stays default-off: the value of the seam is that it can be switched
  on, not that it is."
  (:require [clojure.test :refer [deftest testing is]]
            [semidx.core :as sci]
            [semidx.runtime.index :as idx]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.usage-metrics :as usage]))

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
