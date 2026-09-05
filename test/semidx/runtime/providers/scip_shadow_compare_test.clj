(ns semidx.runtime.providers.scip-shadow-compare-test
  "Stage 3 (plans/018, ADR-046): the SCIP-vs-Stage-2 shadow comparison harness.

  Deterministic assertions drive `compare-scip-run` with a SCIP result built
  from the committed `typescript-corpus.scrubbed.scip` fixture (no toolchain);
  one end-to-end test runs `shadow-report` through the real CLI and is skipped
  when it does not resolve."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.providers.scip-shadow-compare :as cmp]
            [semidx.runtime.providers.scip-typescript :as st]
            [semidx.runtime.scip :as scip]))

(def ^:private corpus-root
  (io/file "fixtures/provider-authority/corpus/typescript"))

(def ^:private fixture-scip
  "fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip")

(def ^:private modelled-symbols
  #{"src.orders/normalize"
    "src.orders/createOrder"
    "src.orders.OrderService#handle"
    "src.validator.Validator#validate"})

(defn- fixture-comparison []
  (cmp/compare-scip-run
   (st/facts-from-index (scip/read-index fixture-scip) {:project-root corpus-root})
   {:root_path corpus-root :ts_paths (cmp/discover-ts-paths corpus-root)}))

;; --- helpers ----------------------------------------------------------

(deftest discover-ts-paths-lists-the-corpus-sources
  (is (= ["src/index.ts" "src/orders.ts" "src/validator.ts"]
         (cmp/discover-ts-paths corpus-root))))

(deftest measure-reports-elapsed-milliseconds
  (let [m (cmp/measure (fn [] (Thread/sleep 3) :done))]
    (is (= :done (:value m)))
    (is (number? (:elapsed_ms m)))
    (is (pos? (:elapsed_ms m)))))

;; --- comparison ------------------------------------------------------

(deftest scip-and-legacy-agree-on-the-shared-symbols-under-one-key
  (let [{:keys [comparison]} (fixture-comparison)]
    (testing "every symbol both tiers produce lands on the same canonical key"
      (is (= modelled-symbols (set (:agreed comparison)))))

    (testing "SCIP adds nothing the legacy tier missed on this corpus"
      (is (= [] (:exact_only comparison))))

    (testing "the index.ts re-export aliases are legacy-only, as SCIP mints no re-export unit"
      (is (= ["src/canonicalize" "src/createOrder"] (:legacy_only comparison))))

    (testing "SCIP raises every shared symbol from heuristic to exact"
      (is (= (repeat 4 {:legacy "heuristic" :exact "exact"})
             (map #(select-keys % [:legacy :exact]) (:authority_upgrade comparison))))
      (is (= modelled-symbols (set (map :symbol (:authority_upgrade comparison))))))))

(deftest co-arbitration-proves-no-duplicate-semantic-identity
  (let [{:keys [co_arbitration comparison]} (fixture-comparison)]
    (testing "raw SCIP + raw legacy facts collapse to one canonical fact per key"
      (is (= (+ (count (:agreed comparison)) (count (:legacy_only comparison)))
             (:canonical_fact_count co_arbitration)))
      (is (zero? (:diagnostic_count co_arbitration))))

    (testing "each shared symbol is one exact fact carrying both providers' evidence"
      (is (= modelled-symbols (set (map :symbol (:multi_provider_symbols co_arbitration)))))
      (is (every? #(= ["scip-typescript" "typescript-regex"] (:providers %))
                  (:multi_provider_symbols co_arbitration)))
      (is (every? #(= "exact" (:authority %))
                  (:multi_provider_symbols co_arbitration))))))

(deftest comparison-carries-coverage-and-size-metrics
  (let [{:keys [scip_coverage scip_unmapped_by_reason size]} (fixture-comparison)]
    (is (true? (:complete scip_coverage)))
    (is (empty? (:stale_documents scip_coverage)))
    (is (contains? scip_unmapped_by_reason :external-symbol))

    (testing "SCIP carries more evidence per fact than the legacy tier"
      (is (= 4 (get-in size [:scip :fact_count])))
      (is (> (get-in size [:scip :evidence_count])
             (get-in size [:scip :fact_count]))
          "definition + references per symbol")
      (is (= (get-in size [:legacy :evidence_count])
             (get-in size [:legacy :fact_count]))
          "the regex tier emits one evidence record per fact")
      (is (pos? (get-in size [:scip :serialized_bytes]))))))

(deftest a-not-ready-scip-result-skips-the-comparison
  (let [result (cmp/compare-scip-run
                {:result "unavailable" :reason_codes ["scip_cli_missing"]}
                {:root_path corpus-root :ts_paths ["src/orders.ts"]})]
    (is (= :skipped_scip_not_ready (:comparison result)))
    (is (= "unavailable" (:scip_result result)))))

;; --- end to end ----------------------------------------------------

(deftest shadow-report-end-to-end-through-the-cli
  (if (st/resolve-cli {})
    (let [report (cmp/shadow-report {:root_path corpus-root
                                     :ts_paths (cmp/discover-ts-paths corpus-root)
                                     :scip_typescript_cli_path (st/resolve-cli {})})]
      (is (= "ready" (:scip_result report)))
      (is (= "0.4.0" (get-in report [:cli :version])))
      (is (number? (get-in report [:latency :scip_run_ms])))
      (is (= modelled-symbols (set (get-in report [:comparison :agreed])))
          "the CLI path agrees with the fixture path")
      (is (zero? (get-in report [:co_arbitration :diagnostic_count]))))
    (println "scip-typescript CLI not resolved; skipping shadow-report end-to-end test")))
