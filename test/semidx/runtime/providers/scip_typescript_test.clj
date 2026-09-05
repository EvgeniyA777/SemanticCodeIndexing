(ns semidx.runtime.providers.scip-typescript-test
  "Stage 3 (plans/018, ADR-046) tests for the TypeScript SCIP provider adapter.

  The deterministic assertions run `facts-from-index` over the committed real
  artifact `typescript-corpus.scrubbed.scip` with the committed corpus as the
  project root, so they need no toolchain. One end-to-end test runs the
  repo-managed `scip-typescript` CLI and is skipped when it does not resolve."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.fact-arbitration :as fa]
            [semidx.runtime.providers.scip-typescript :as st]
            [semidx.runtime.scip :as scip]))

(def ^:private fixture-scip
  "fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip")

(def ^:private corpus-root
  (io/file "fixtures/provider-authority/corpus/typescript"))

(def ^:private modelled-symbols
  #{"src.orders/normalize"
    "src.orders/createOrder"
    "src.orders.OrderService#handle"
    "src.validator.Validator#validate"})

(defn- fixture-index [] (scip/read-index fixture-scip))

(defn- symbols-of [result]
  (set (map (comp :symbol :core_key) (:facts result))))

;; --- CLI resolution / status -------------------------------------------

(deftest resolve-cli-prefers-an-explicit-executable-option
  (let [real (io/file ".scip-toolchain/node_modules/.bin/scip-typescript")]
    (testing "an explicit option pointing at an executable wins"
      (when (.exists real)
        (is (= (.getPath real)
               (st/resolve-cli {:scip_typescript_cli_path (.getPath real)}))))))

  (testing "nothing resolvable -> nil, never a throw"
    (is (nil? (st/resolve-cli {:scip_toolchain_dir "/semidx/does-not-exist"})))))

(deftest provider-status-reports-scip-cli-missing-when-unresolved
  (let [status (st/provider-status {:scip_toolchain_dir "/semidx/does-not-exist"})]
    (is (= "unavailable" (:state status)))
    (is (= ["scip_cli_missing"] (:reason_codes status)))
    (is (= "scip-typescript" (:provider_id status)))))

;; --- facts-from-index: happy path -------------------------------------

(deftest facts-from-index-mints-exact-facts-for-the-modelled-symbols
  (let [result (st/facts-from-index (fixture-index) {:project-root corpus-root})]
    (is (= "ready" (:result result)))
    (is (empty? (:errors result)))
    (is (true? (get-in result [:coverage :complete])))
    (is (empty? (get-in result [:coverage :stale_documents])))

    (testing "exactly the four kinds semidx models, all at exact authority"
      (is (= modelled-symbols (symbols-of result)))
      (is (every? #(= "exact" (:authority %)) (:facts result))))

    (testing "every evidence record is anchored to a real content digest"
      (is (every? (fn [ev]
                    (some-> ev :source_identity :content_digest
                            (as-> d (re-matches #"sha256:[0-9a-f]{64}" d))))
                  (mapcat :evidence (:facts result)))))

    (testing "SCIP keys match what the regex tier produces for the same symbol"
      (let [normalize-fact (first (filter #(= "src.orders/normalize"
                                              (:symbol (:core_key %)))
                                          (:facts result)))]
        (is (= (:canonical_fact_key_id normalize-fact)
               (fa/canonical-fact-key-id {:fact_kind "unit" :language "typescript"
                                          :path "src/orders.ts" :owner "src.orders"
                                          :symbol "src.orders/normalize"
                                          :overload_identity nil})))))

    (testing "unmapped SCIP symbols are summarised in one diagnostic, with reasons"
      (let [summary (first (filter #(= :scip_symbols_unmapped (:code %))
                                   (:diagnostics result)))]
        (is (some? summary))
        (is (pos? (:count summary)))
        (is (contains? (set (keys (:by_reason summary))) :external-symbol))))))

(deftest facts-from-index-requires-a-project-root
  (is (thrown? clojure.lang.ExceptionInfo
               (st/facts-from-index (fixture-index) {}))))

;; --- facts-from-index: stale gate ------------------------------------

(deftest a-missing-workspace-file-drops-the-whole-document
  (let [result (st/facts-from-index (fixture-index)
                                    {:project-root (io/file (System/getProperty "java.io.tmpdir")
                                                            "semidx-scip-no-such-project")})]
    (is (empty? (:facts result)) "no exact facts when nothing can be anchored")
    (is (false? (get-in result [:coverage :complete])))
    (is (= #{"src/validator.ts" "src/orders.ts" "src/index.ts"}
           (set (get-in result [:coverage :stale_documents]))))
    (is (every? #(= :scip_document_source_missing (:code %))
                (filter #(:document %) (:diagnostics result))))))

(deftest a-digest-mismatch-drops-only-that-documents-occurrences
  (let [result (st/facts-from-index
                (fixture-index)
                {:project-root corpus-root
                 :expected-document-digests {"src/orders.ts" "sha256:0000"}})]
    (is (= ["src/orders.ts"] (get-in result [:coverage :stale_documents])))
    (is (false? (get-in result [:coverage :complete])))

    (testing "the stale document's own occurrences are gone"
      (let [ops-by-symbol (into {}
                                (map (juxt #(:symbol (:core_key %))
                                           #(set (map :operation (:evidence %)))))
                                (:facts result))]
        (is (nil? (ops-by-symbol "src.orders.OrderService#handle"))
            "a method defined and only used in orders.ts disappears entirely")
        (is (= #{"references"} (ops-by-symbol "src.orders/normalize"))
            "the orders.ts definition is dropped; cross-file refs from index.ts remain")))

    (testing "the diagnostic names the expected and actual digest"
      (let [d (first (filter #(= :scip_document_stale (:code %)) (:diagnostics result)))]
        (is (= "src/orders.ts" (:document d)))
        (is (= "sha256:0000" (:expected d)))
        (is (re-matches #"sha256:[0-9a-f]{64}" (:actual d)))))))

;; --- shadow-facts-for-project ---------------------------------------

(deftest shadow-facts-for-project-is-unavailable-not-an-error-without-a-cli
  (let [result (st/shadow-facts-for-project {:root_path corpus-root
                                             :scip_toolchain_dir "/semidx/does-not-exist"})]
    (is (= "unavailable" (:result result)))
    (is (= ["scip_cli_missing"] (:reason_codes result)))
    (is (empty? (:facts result)))
    (is (= [:scip_provider_unavailable] (map :code (:diagnostics result))))))

(deftest shadow-facts-for-project-requires-a-root-path
  (is (thrown? clojure.lang.ExceptionInfo
               (st/shadow-facts-for-project {}))))

(deftest end-to-end-through-the-repo-managed-cli
  (if-let [cli (st/resolve-cli {})]
    (let [result (st/shadow-facts-for-project {:root_path corpus-root
                                               :scip_typescript_cli_path cli})]
      (is (= "ready" (:result result)))
      (is (= "0.4.0" (get-in result [:cli :version])))
      (is (empty? (:errors result)))
      (is (= modelled-symbols (symbols-of result))
          "the CLI path produces the same facts as the committed fixture")
      (is (every? #(= "exact" (:authority %)) (:facts result))))
    (println "scip-typescript CLI not resolved; skipping end-to-end test")))
