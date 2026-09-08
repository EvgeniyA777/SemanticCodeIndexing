(ns semidx.runtime.provider-batch-test
  "Stage 4.5 (plans/018, ADR-046) project-scoped provider execution.

  Most assertions drive the boundary with an injected role, because what is
  being proved is orchestration: isolation, coverage, degradation, delivery, and
  order independence. Three tests use the real TypeScript corpus — two over the
  committed `.scip` fixture, one over the repo-managed CLI — so the seam is also
  exercised against an artifact no test wrote."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.provider-batch :as batch]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.providers.scip-typescript :as scip-typescript]
            [semidx.runtime.scip :as scip]
            [semidx.test-support.scip-toolchain :as toolchain]))

(def ^:private corpus-root
  (io/file "fixtures/provider-authority/corpus/typescript"))

(def ^:private fixture-scip
  "fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip")

(def ^:private corpus-paths ["src/orders.ts" "src/validator.ts" "src/index.ts"])

(defn- ready-status [] {:state "ready" :reason_codes [] :observed_at "2026-09-05T00:00:00Z"})

(defn- unit-fact [path symbol operation]
  {:key {:fact_kind "unit"
         :language "typescript"
         :path path
         :owner "orders"
         :symbol symbol
         :overload_identity nil}
   :evidence [{:provider_id "scip-typescript"
               :provider_version "1"
               :authority "exact"
               :operation operation
               :freshness "exact"
               :source_identity {:content_digest "sha256:deadbeef"}
               :evidence_location {:path path :start_line 1 :end_line 2}}]
   :value {:kind "function"}})

(defn- ready-result
  "A project result in the shape every project provider returns."
  [{:keys [facts covered stale invalid]
    :or {facts [] covered [] stale [] invalid []}}]
  {:provider_id "scip-typescript"
   :provider_version "1"
   :result "ready"
   :facts []
   :raw_facts (vec facts)
   :batches []
   :errors []
   :diagnostics []
   :coverage {:covered_paths (vec covered)
              :stale_documents (vec stale)
              :invalid_documents (vec invalid)
              :withheld_fact_count 0
              :complete (and (empty? stale) (empty? invalid))}
   :unmapped []})

(defn- roles [run-fn]
  {"scip-typescript" {:status-fn (fn [_] (ready-status))
                      :run-fn run-fn}})

(defn- ts-plan []
  (selection/project-plan {:root_path (.getPath corpus-root)
                           :languages ["typescript"]
                           :statuses {"scip-typescript" (ready-status)}}))

(defn- run-with [run-fn]
  (batch/execute-project-plan (ts-plan) {:project_roles (roles run-fn)}))

(defn- comparable-plan
  "A plan with the wall-clock observation time removed. `:observed_at` is the
  one field that differs between two otherwise identical plans, so comparing
  raw plans would only ever prove that time passed."
  [plan]
  (update plan :statuses #(into {} (map (fn [[k v]] [k (dissoc v :observed_at)])) %)))

;; --- Failure isolation --------------------------------------------------

(deftest a-throwing-provider-becomes-a-result-not-an-exception-test
  (let [execution (run-with (fn [_] (throw (ex-info "toolchain exploded" {}))))
        result (get-in execution [:results "scip-typescript"])]
    (is (= "failed" (:result result)))
    (is (= [:project_provider_failed] (mapv :code (:diagnostics execution))))
    (is (empty? (:raw_facts result)))
    (is (= {} (batch/batch-coverage execution))
        "a failed run contributes no coverage, so no file plans it")))

(deftest a-result-outside-the-contract-is-refused-test
  (testing "an unexpected :result value is a reported failure, not a silent pass"
    (let [execution (run-with (fn [_] {:result "probably-fine"}))]
      (is (= "failed" (get-in execution [:results "scip-typescript" :result])))
      (is (= [:project_provider_contract_violation]
             (mapv :code (:diagnostics execution)))))))

(deftest an-unregistered-role-is-reported-test
  (let [execution (batch/execute-project-plan (ts-plan) {:project_roles {}})]
    (is (= "failed" (get-in execution [:results "scip-typescript" :result])))
    (is (= [:no_project_role_registered] (mapv :code (:diagnostics execution))))))

(deftest a-throwing-status-probe-is-unavailable-not-a-crash-test
  (let [statuses (batch/project-statuses
                  ["typescript"] {}
                  {"scip-typescript" {:status-fn (fn [_] (throw (ex-info "probe down" {})))}})
        status (get statuses "scip-typescript")]
    (is (= "unavailable" (:state status)))
    (is (= ["provider_status_probe_failed"] (:reason_codes status)))
    (is (some? (:message status))
        "the reason survives; an unobserved provider and a broken probe must not look alike")))

(deftest project-statuses-refuse-a-provider-with-no-role-test
  (let [statuses (batch/project-statuses ["typescript"] {} {})]
    (is (= "unavailable" (get-in statuses ["scip-typescript" :state])))
    (is (= ["no_project_role_registered"]
           (get-in statuses ["scip-typescript" :reason_codes])))))

;; --- Coverage and document states ---------------------------------------

(deftest only-a-fresh-document-of-a-ready-run-contributes-coverage-test
  (testing "ready run"
    (let [execution (run-with (fn [_] (ready-result {:covered ["src/orders.ts"]
                                                     :stale ["src/validator.ts"]})))]
      (is (= {"scip-typescript" ["src/orders.ts"]} (batch/batch-coverage execution))
          "a stale document is not coverage, so it cannot anchor an exact fact")))

  (testing "unavailable run"
    (let [execution (run-with (fn [_] (assoc (ready-result {}) :result "unavailable"
                                             :reason_codes ["scip_cli_missing"])))]
      (is (= {} (batch/batch-coverage execution))))))

(deftest document-states-use-one-vocabulary-for-every-language-test
  (let [execution (run-with (fn [_] (ready-result {:covered ["src/orders.ts"]
                                                   :stale ["src/validator.ts"]
                                                   :invalid ["../outside.ts"]})))
        states (batch/document-states execution (conj corpus-paths "Other.java"))
        ts (get states "scip-typescript")]
    (is (= ["src/orders.ts"] (:fresh ts)))
    (is (= ["src/validator.ts"] (:stale ts)))
    (is (= ["../outside.ts"] (:invalid ts)))
    (is (= ["src/index.ts"] (:uncovered ts))
        "a requested path the run never accounted for is named, not silently absent")
    (is (false? (:complete ts)))
    (is (not (contains? (set (:uncovered ts)) "Other.java"))
        "uncovered is scoped by the provider's own selectors")))

;; --- Delivery into per-file execution -----------------------------------

(deftest batch-facts-reach-the-file-they-belong-to-test
  (let [facts [(unit-fact "src/orders.ts" "orders/normalize" "definitions")
               (unit-fact "src/validator.ts" "validator/check" "definitions")]
        execution (run-with (fn [_] (ready-result {:facts facts
                                                   :covered ["src/orders.ts" "src/validator.ts"]})))
        runner (batch/batch-run-provider execution)]
    (is (= ["orders/normalize"]
           (mapv #(get-in % [:key :symbol])
                 (:facts (runner "scip-typescript" {:path "src/orders.ts"
                                                    :operation :definitions})))))
    (is (empty? (:facts (runner "scip-typescript" {:path "src/orders.ts"
                                                   :operation :references})))
        "operation is part of the key: a definition is not handed back as a reference")
    (is (empty? (:facts (runner "scip-typescript" {:path "src/index.ts"
                                                   :operation :definitions}))))))

(deftest a-file-scoped-provider-still-goes-to-its-own-runner-test
  (let [execution (run-with (fn [_] (ready-result {})))
        seen (atom [])
        runner (batch/batch-run-provider execution
                                         (fn [provider-id _]
                                           (swap! seen conj provider-id)
                                           {:facts [] :diagnostics []}))]
    (runner "typescript-regex" {:path "src/orders.ts" :operation :definitions})
    (is (= ["typescript-regex"] @seen)
        "only project providers are served from the batch")))

;; --- End to end over the real corpus ------------------------------------

(deftest project-seam-merges-exact-and-legacy-into-one-identity-test
  (let [scip-result (scip-typescript/facts-from-index (scip/read-index fixture-scip)
                                                      {:project-root corpus-root})
        result (batch/facts-for-project
                {:root_path (.getPath corpus-root)
                 :paths corpus-paths
                 :project_roles (roles (fn [_] scip-result))})
        orders (first (filter #(= "src/orders.ts" (:path %)) (:files result)))
        multi-provider (filter #(< 1 (count (set (map :provider_id (:evidence %)))))
                               (:facts orders))]
    (is (= "ready" (get-in result [:project_execution :results "scip-typescript" :result])))
    (is (seq (get (:batch_coverage result) "scip-typescript")))

    (testing "a symbol both tiers found is one canonical fact carrying both evidences"
      (is (seq multi-provider))
      (is (every? #(= "exact" (:authority %)) multi-provider)
          "the exact tier wins the authority while the heuristic evidence is retained")
      (is (every? #(contains? (set (map :provider_id (:evidence %))) "scip-typescript")
                  multi-provider)))

    (testing "no duplicate identity: one key per symbol across both tiers"
      (let [ids (map :canonical_fact_key_id (:facts orders))]
        (is (= (count ids) (count (set ids))))))

    (testing "the shadow artifact carries no snapshot side effect"
      (is (= "shadow" (:mode result))))))

(deftest batch-execution-order-does-not-change-arbitration-test
  (let [scip-result (scip-typescript/facts-from-index (scip/read-index fixture-scip)
                                                      {:project-root corpus-root})
        run (fn [paths]
              (->> (batch/facts-for-project
                    {:root_path (.getPath corpus-root)
                     :paths paths
                     :project_roles (roles (fn [_] scip-result))})
                   :files
                   (map (juxt :path #(mapv :canonical_fact_key_id (:facts %))))
                   (into (sorted-map))))]
    (is (= (run corpus-paths) (run (reverse corpus-paths)))
        "arbitrated output is a function of the plan, not of run order")))

(deftest a-workspace-without-a-toolchain-degrades-to-the-file-tiers-test
  (let [with-batch (batch/facts-for-project
                    {:root_path (.getPath corpus-root)
                     :paths corpus-paths
                     :project_roles (roles (fn [_] (assoc (ready-result {})
                                                          :result "unavailable"
                                                          :reason_codes ["scip_cli_missing"])))})
        plain (mapv (fn [path]
                      (comparable-plan
                       (:plan (provider-execution/facts-for-file
                               {:root_path (.getPath corpus-root) :path path}))))
                    corpus-paths)]
    (is (= {} (:batch_coverage with-batch)))
    (is (= plain (mapv (comp comparable-plan :plan) (:files with-batch)))
        "with no coverage the per-file plans are exactly the pre-Stage-4.5 plans")))

(deftest end-to-end-through-the-repo-managed-cli
  (if (scip-typescript/resolve-cli {})
    (let [result (batch/facts-for-project {:root_path (.getPath corpus-root)
                                                  :paths corpus-paths})
          coverage (get (:batch_coverage result) "scip-typescript")]
      (is (= "ready" (get-in result [:project_execution :results "scip-typescript" :result])))
      (is (seq coverage) "a real run covers the corpus documents")
      (is (empty? (filter #(= "failed" (:result %))
                          (vals (get-in result [:project_execution :results]))))))
    (toolchain/unresolved! "scip-typescript CLI" "provider-batch end-to-end test")))

(deftest an-unbuilt-protobuf-runtime-is-named-not-guessed-test
  (testing "the adapters are resolved on first use, so a deployment that never
            built the generated SCIP classes gets an unavailable provider rather
            than a load error — and the reason says which of the two problems it
            is, because the class loader's own message names neither"
    (let [statuses (batch/project-statuses
                    ["typescript"] {}
                    {"scip-typescript"
                     {:status-fn (fn [_] (throw (ClassNotFoundException. "scip.Scip$Diagnostic")))}})
          status (get statuses "scip-typescript")]
      (is (= "unavailable" (:state status)))
      (is (= ["scip_runtime_classes_unavailable"] (:reason_codes status)))))

  (testing "a cause buried under a require's own exception counts too"
    (let [statuses (batch/project-statuses
                    ["typescript"] {}
                    {"scip-typescript"
                     {:status-fn (fn [_] (throw (ex-info "Syntax error macroexpanding" {}
                                                         (NoClassDefFoundError. "scip.Scip"))))}})]
      (is (= ["scip_runtime_classes_unavailable"]
             (:reason_codes (get statuses "scip-typescript"))))))

  (testing "and an ordinary broken probe keeps its own reason"
    (let [statuses (batch/project-statuses
                    ["typescript"] {}
                    {"scip-typescript" {:status-fn (fn [_] (throw (ex-info "probe bug" {})))}})]
      (is (= ["provider_status_probe_failed"]
             (:reason_codes (get statuses "scip-typescript")))))))
