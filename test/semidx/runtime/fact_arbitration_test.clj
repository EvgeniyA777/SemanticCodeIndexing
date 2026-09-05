(ns semidx.runtime.fact-arbitration-test
  "Stage 1 (plans/018, ADR-046) tests for the provider-neutral CanonicalFactKey
  (Variant C) and the deterministic same-key arbitration kernel. Scenarios are
  grounded in the Stage 0 identity fixtures under
  fixtures/provider-authority/identity/."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [next.jdbc :as jdbc]
            [semidx.runtime.fact-arbitration :as fa]))

;; --- Fixtures (mirror fixtures/provider-authority/identity) ---

(defn- unit-key [overload]
  {:fact_kind "unit"
   :language "java"
   :path "src/example/OrderService.java"
   :owner "example.OrderService"
   :symbol "example.OrderService#handle"
   :overload_identity overload})

(def regex-handle1
  {:key (unit-key {:arity 1 :signature_precision "arity_only"})
   :evidence [{:provider_id "java-regex" :authority "heuristic" :freshness "unknown"}]})

(def treesitter-handle1
  {:key (unit-key {:arity 1 :signature_precision "arity_only"})
   :evidence [{:provider_id "java-tree-sitter" :authority "structural" :freshness "unknown"}]})

(def scip-handle1
  {:key (unit-key {:arity 1 :signature_precision "typed" :signature_key "java.lang.String"})
   :evidence [{:provider_id "scip-java" :authority "exact" :freshness "exact"
               :source_identity {:content_digest "sha256:orderservice"}}]})

(def lsp-handle1
  {:key (unit-key {:arity 1 :signature_precision "typed" :signature_key "java.lang.String"})
   :evidence [{:provider_id "java-lsp" :authority "exact" :freshness "exact"
               :source_identity {:document_version 42}}]})

(def scip-handle2
  {:key (unit-key {:arity 2 :signature_precision "typed" :signature_key "java.lang.String,int"})
   :evidence [{:provider_id "scip-java" :authority "exact" :freshness "exact"
               :source_identity {:content_digest "sha256:orderservice"}}]})

(def scip-handle1-int
  {:key (unit-key {:arity 1 :signature_precision "typed" :signature_key "int"})
   :evidence [{:provider_id "scip-java" :authority "exact" :freshness "exact"
               :source_identity {:content_digest "sha256:orderservice"}}]})

;; --- CanonicalFactKey identity ---

(deftest core-key-ignores-provider-spelling-and-map-order
  (testing "arity_only and typed spellings of the same overload share one core key id"
    (is (= (fa/canonical-fact-key-id (:key regex-handle1))
           (fa/canonical-fact-key-id (:key scip-handle1)))))
  (testing "map entry order does not change the key id"
    (let [a {:fact_kind "unit" :language "java" :path "p" :owner "O" :symbol "O#h"
             :overload_identity {:arity 1 :signature_precision "arity_only"}}
          b {:symbol "O#h" :owner "O" :overload_identity {:signature_precision "arity_only" :arity 1}
             :path "p" :language "java" :fact_kind "unit"}]
      (is (= (fa/canonical-fact-key-id a) (fa/canonical-fact-key-id b))))))

(deftest distinct-symbols-do-not-collide
  (testing "same legacy signature spelling, different owner/symbol => distinct key"
    (let [handle {:fact_kind "unit" :language "java" :path "src/example/OrderService.java"
                  :owner "example.OrderService" :symbol "example.OrderService#handle"
                  :overload_identity {:arity 1 :signature_precision "arity_only"}}
          validate {:fact_kind "unit" :language "java" :path "src/example/Validator.java"
                    :owner "example.Validator" :symbol "example.Validator#validate"
                    :overload_identity {:arity 1 :signature_precision "arity_only"}}]
      (is (not= (fa/canonical-fact-key-id handle)
                (fa/canonical-fact-key-id validate))))))

(deftest distinct-arities-stay-distinct
  (is (not= (fa/canonical-fact-key-id (:key scip-handle1))
            (fa/canonical-fact-key-id (:key scip-handle2)))))

;; --- Arbitration: common case ---

(deftest same-overload-across-four-providers-merges-to-one-exact-fact
  (let [{:keys [facts diagnostics]}
        (fa/arbitrate-facts [regex-handle1 treesitter-handle1 scip-handle1 lsp-handle1])]
    (is (= 1 (count facts)))
    (is (empty? diagnostics))
    (let [f (first facts)]
      (is (= "exact" (:authority f)) "strongest authority wins")
      (is (= "typed" (:signature_precision f)))
      (is (= "java.lang.String" (:signature_key f)))
      (is (= #{"java-regex" "java-tree-sitter" "scip-java" "java-lsp"}
             (set (map :provider_id (:evidence f))))
          "all provider evidence is retained"))))

(deftest common-case-identity-is-stable-when-typed-evidence-arrives
  (testing "Variant C invariant: a regex-only fact keeps its identity after SCIP attaches"
    (let [id-regex (:fact_identity (first (:facts (fa/arbitrate-facts [regex-handle1]))))
          id-merged (:fact_identity (first (:facts (fa/arbitrate-facts [regex-handle1 scip-handle1]))))]
      (is (= id-regex id-merged)))))

;; --- Arbitration: same-arity overloads (F1a) ---

(deftest distinct-same-arity-typed-overloads-split
  (let [{:keys [facts diagnostics]} (fa/arbitrate-facts [scip-handle1 scip-handle1-int])]
    (is (= 2 (count facts)))
    (is (empty? diagnostics))
    (is (apply distinct? (map :fact_identity facts)) "split facts have distinct identity")
    (is (= #{"int" "java.lang.String"} (set (map :signature_key facts))))))

(deftest arity-only-evidence-under-same-arity-overloads-is-ambiguous
  (let [{:keys [facts diagnostics]}
        (fa/arbitrate-facts [scip-handle1 scip-handle1-int regex-handle1])]
    (is (= 2 (count facts)) "arity-only heuristic is not attributed to a specific overload")
    (is (= [:arity_ambiguous_heuristic] (map :code diagnostics))
        "F1a: unattributable arity-only evidence is surfaced, not silently merged")))

;; --- Authority + determinism ---

(deftest lower-authority-never-outranks-higher
  (let [f (first (:facts (fa/arbitrate-facts [scip-handle1 regex-handle1])))]
    (is (= "exact" (:authority f)))))

(deftest arbitration-is-order-independent
  (let [inputs [regex-handle1 treesitter-handle1 scip-handle1 lsp-handle1 scip-handle1-int]
        base (fa/arbitrate-facts inputs)]
    (is (= base (fa/arbitrate-facts (reverse inputs))))
    (doseq [_ (range 25)]
      (is (= base (fa/arbitrate-facts (shuffle inputs)))))))

;; --- FactEvidence validation (ADR-046) ---

(deftest exact-authority-requires-fresh-identity
  (testing "exact + stale/unknown freshness is rejected"
    (doseq [freshness ["stale" "unknown"]]
      (is (some #{:exact-without-fresh-identity}
                (map :code (fa/fact-evidence-errors
                            (fa/normalize-fact-evidence
                             {:provider_id "scip" :authority "exact" :freshness freshness
                              :source_identity {:content_digest "sha256:abc"}})))))))

  (testing "ADR-046: exact authority needs source identity tied to the content"
    (is (= [:exact-without-source-identity]
           (map :code (fa/fact-evidence-errors
                       (fa/normalize-fact-evidence
                        {:provider_id "scip" :authority "exact" :freshness "exact"}))))
        "a provider's own freshness claim is not an anchor")
    (is (= [:exact-without-source-identity]
           (map :code (fa/fact-evidence-errors
                       (fa/normalize-fact-evidence
                        {:provider_id "scip" :authority "exact" :freshness "exact"
                         :source_identity {:provider_healthy true}}))))
        "provider health is explicitly not acceptable freshness evidence"))

  (testing "each anchor ADR-046 names is accepted"
    (doseq [source-identity [{:content_digest "sha256:abc"}
                             {:document_version 42}
                             {:revision "9f2c1a"}]]
      (is (empty? (fa/fact-evidence-errors
                   (fa/normalize-fact-evidence
                    {:provider_id "scip" :authority "exact" :freshness "exact"
                     :source_identity source-identity})))
          (str "anchor rejected: " (pr-str source-identity)))))

  (testing "lower authority does not need an anchor"
    (is (empty? (fa/fact-evidence-errors
                 (fa/normalize-fact-evidence
                  {:provider_id "java-tree-sitter" :authority "structural"
                   :freshness "unknown"}))))))

(deftest heuristic-evidence-is-valid-without-fresh-identity
  (is (empty? (fa/fact-evidence-errors
               (fa/normalize-fact-evidence
                {:provider_id "java-regex" :authority "heuristic" :freshness "unknown"})))))

(deftest evidence-requires-provider-and-known-authority
  (is (= #{:missing-provider-id :invalid-authority}
         (set (map :code (fa/fact-evidence-errors
                          (fa/normalize-fact-evidence
                           {:authority "compiler" :freshness "exact"})))))))

;; --- Relation facts (ADR-039 aligned) ---

(deftest relation-facts-key-on-adr039-identity
  (let [rel-key {:fact_kind "relation"
                 :relation_type "dataflow/passes-argument"
                 :source_unit_key {:language "java" :symbol "example.OrderService#handle"}
                 :target_key {:language "java" :symbol "example.Validator#validate"}
                 :flow_identity {:arg_index 0}}
        a (fa/arbitrate-facts [{:key rel-key
                                :evidence [{:provider_id "scip-java" :authority "exact" :freshness "exact"
               :source_identity {:content_digest "sha256:orderservice"}}]}
                               {:key rel-key
                                :evidence [{:provider_id "java-regex" :authority "heuristic" :freshness "unknown"}]}])]
    (is (= 1 (count (:facts a))) "same relation identity from two providers merges to one fact")
    (is (= "exact" (:authority (first (:facts a)))))))

;; --------------------------------------------------------------------------
;; Golden parity read from the Stage 0 identity fixtures
;;
;; The scenarios above mirror those fixtures by hand. Mirroring drifts silently:
;; if a fixture is corrected, nothing above notices. These tests read the
;; committed JSON so the kernel is checked against the artifact the plan calls
;; the admission baseline, not against a copy of it.
;; --------------------------------------------------------------------------

(defn- read-identity-fixture [file-name]
  (with-open [rdr (io/reader (io/file "fixtures/provider-authority/identity" file-name))]
    (json/read rdr :key-fn keyword)))

(defn- expected->key
  "CanonicalFactKey input built from a fixture's structured expected key."
  ([expected] (expected->key expected nil))
  ([expected overload]
   {:fact_kind (:fact_kind expected)
    :language (:language expected)
    :path (:path expected)
    :owner (:owner expected)
    :symbol (:symbol expected)
    :dispatch_identity (:dispatch_identity expected)
    :overload_identity overload}))

(defn- evidence-for
  "FactEvidence for one provider spelling.

  Freshness is supplied by the test rather than by the fixture: ADR-046 requires
  exact authority to carry fresh source identity, while the fixture describes
  identity spellings, not run freshness."
  [spelling]
  (cond-> {:provider_id (:provider_id spelling)
           :authority (:authority spelling)
           :freshness (if (= "exact" (:authority spelling)) "exact" "unknown")
           :native_symbol (or (:native_symbol spelling)
                              (:native_exported_symbol spelling))}
    ;; ADR-046 requires exact authority to be anchored to content; the fixture
    ;; describes spellings, so the anchor is supplied here.
    (= "exact" (:authority spelling))
    (assoc :source_identity {:content_digest "sha256:fixture-corpus"})))

(deftest java-overload-identity-fixture-parity-test
  (let [fixture (read-identity-fixture "java-overload-canonical-key.json")
        {:keys [expected_canonical_key provider_spellings]} (:same_fact_must_merge fixture)
        arity (get-in expected_canonical_key [:overload_identity :arity])
        ;; The per-spelling contribution carries precision, the expected key
        ;; carries the arity they all share.
        facts (mapv (fn [spelling]
                      {:key (expected->key expected_canonical_key
                                           (assoc (:variant_c_contribution spelling)
                                                  :arity arity))
                       :evidence [(evidence-for spelling)]})
                    provider_spellings)
        {:keys [facts diagnostics]} (fa/arbitrate-facts facts)
        merged (first facts)
        [language path fact-kind owner symbol expected-arity] (:core_key expected_canonical_key)]
    (testing "every provider spelling of one overload merges into one fact"
      (is (= 1 (count facts)))
      (is (empty? diagnostics)))

    (testing "the canonical core key matches the fixture field by field"
      (is (= language (get-in merged [:core_key :language])))
      (is (= path (get-in merged [:core_key :path])))
      (is (= fact-kind (get-in merged [:core_key :fact_kind])))
      (is (= owner (get-in merged [:core_key :owner])))
      (is (= symbol (get-in merged [:core_key :symbol])))
      (is (= expected-arity (get-in merged [:core_key :arity]))))

    (testing "the exact tier's typed refinement wins without changing identity"
      (let [overload (:overload_identity expected_canonical_key)]
        (is (= (:signature_precision overload) (:signature_precision merged)))
        (is (= (:signature_key overload) (:signature_key merged))))
      (is (= "exact" (:authority merged)))
      (is (= (:canonical_fact_key_id merged) (:fact_identity merged))
          "Variant C: the common case keeps the core key id as its identity"))

    (testing "every provider's evidence is retained, native spellings included"
      (is (= (set (map :provider_id provider_spellings))
             (set (map :provider_id (:evidence merged)))))
      (is (every? some? (map :native_symbol (:evidence merged)))))

    (testing "the fixture's distinct facts keep distinct keys"
      (doseq [distinct-fact (:distinct_facts_must_not_merge fixture)
              :let [expected (:expected_canonical_key distinct-fact)
                    overload (merge {:arity (:arity expected)}
                                    (:expected_canonical_overload_identity distinct-fact))]]
        (is (not= (:canonical_fact_key_id merged)
                  (fa/canonical-fact-key-id (expected->key expected overload)))
            (str (:fact distinct-fact) " — " (:reason distinct-fact)))))))

(deftest typescript-re-export-identity-fixture-parity-test
  (let [fixture (read-identity-fixture "typescript-re-export-canonical-key.json")
        edge (:re_export_edge_must_resolve_to_origin fixture)
        origin (:expected_canonical_key_of_origin edge)
        spellings (:provider_spellings edge)
        origin-facts (mapv (fn [spelling]
                             {:key (expected->key origin (:overload_identity origin))
                              :evidence [(evidence-for spelling)]})
                           spellings)
        {:keys [facts]} (fa/arbitrate-facts origin-facts)
        merged (first facts)]
    (testing "every provider's native re-export target resolves to one origin key"
      (is (= 1 (count facts)))
      (is (= (:language origin) (get-in merged [:core_key :language])))
      (is (= (:path origin) (get-in merged [:core_key :path])))
      (is (= (:owner origin) (get-in merged [:core_key :owner])))
      (is (= (:symbol origin) (get-in merged [:core_key :symbol]))))

    (testing "provider-native monikers stay evidence, never the merge key"
      (is (= (set (map :provider_id spellings))
             (set (map :provider_id (:evidence merged)))))
      (is (= 1 (count (distinct (map :canonical_fact_key_id facts))))))

    (testing "the re-export relation keys on ADR-039 identity across providers"
      ;; A provider that does not emit the re-export as a fact of its own
      ;; (scip-typescript@0.4.0: relationship_emitted false) contributes no
      ;; relation fact; the fixture records that and the test must not fabricate
      ;; one on its behalf.
      (let [relation (:expected_re_export_relation edge)
            edge-spellings (remove (comp false? :relationship_emitted) spellings)
            relation-facts (mapv (fn [spelling]
                                   {:key {:fact_kind "relation"
                                          :relation_type (:relation_type relation)
                                          :source_unit_key {:symbol (:source_symbol relation)}
                                          :target_key (:target_key relation)}
                                    :evidence [(evidence-for spelling)]})
                                 edge-spellings)
            merged-relation (first (:facts (fa/arbitrate-facts relation-facts)))]
        (is (not (contains? (set (map :provider_id edge-spellings)) "scip-typescript"))
            "scip-typescript emits no re-export relationship; excluded from the edge providers")
        (is (= 1 (count (:facts (fa/arbitrate-facts relation-facts)))))
        (is (= "relation" (get-in merged-relation [:core_key :fact_kind])))
        (is (= (:relation_type relation) (get-in merged-relation [:core_key :relation_type])))))

    (testing "an alias export never collapses into the origin unit"
      (doseq [distinct-fact (:distinct_facts_must_not_merge fixture)]
        (is (not= (:canonical_fact_key_id merged)
                  (fa/canonical-fact-key-id (expected->key (:expected_canonical_key distinct-fact))))
            (str (:fact distinct-fact) " — " (:reason distinct-fact)))))))

;; --------------------------------------------------------------------------
;; FactBatch: the provider envelope around facts
;; --------------------------------------------------------------------------

(def ^:private batch-fact
  {:key (unit-key {:arity 1 :signature_precision "arity_only"})
   :evidence [{:authority "heuristic"}]})

(deftest batch-fills-in-provenance-but-never-restates-it-test
  (let [batch (fa/normalize-fact-batch
               {:provider_id "java-regex"
                :provider_version "1.2.3"
                :freshness "unknown"
                :source_identity {:content_digest "abc"}
                :coverage {:paths ["src/example/OrderService.java"] :complete true}
                :facts [batch-fact
                        {:key (unit-key {:arity 2 :signature_precision "arity_only"})
                         :evidence [{:provider_id "java-tree-sitter"
                                     :authority "structural"
                                     :freshness "stale"}]}]})
        [inherited own] (:facts batch)]
    (testing "a fact that stated nothing inherits the batch provenance"
      (is (= "java-regex" (get-in inherited [:evidence 0 :provider_id])))
      (is (= "1.2.3" (get-in inherited [:evidence 0 :provider_version])))
      (is (= "unknown" (get-in inherited [:evidence 0 :freshness])))
      (is (= {:content_digest "abc"} (get-in inherited [:evidence 0 :source_identity]))))

    (testing "a fact that stated its own provenance keeps it"
      (is (= "java-tree-sitter" (get-in own [:evidence 0 :provider_id])))
      (is (= "stale" (get-in own [:evidence 0 :freshness]))))

    (testing "coverage is normalized so an incomplete run is readable"
      (is (true? (get-in batch [:coverage :complete])))
      (is (= ["src/example/OrderService.java"] (get-in batch [:coverage :paths]))))))

(deftest batch-errors-name-the-offending-fact-test
  (let [batch (fa/normalize-fact-batch
               {:provider_id "scip-java"
                :freshness "stale"
                :facts [{:key (unit-key {:arity 1})
                         :evidence [{:authority "exact"}]}]})
        errors (fa/fact-batch-errors batch)]
    (testing "a stale batch cannot lend exact authority to its facts"
      (is (= [:exact-without-fresh-identity :exact-without-source-identity]
             (mapv :code errors))
          "a stale, unanchored exact fact fails both ADR-046 requirements")
      (is (= 0 (:fact_index (first errors))))
      (is (= 0 (:evidence_index (first errors))))
      (is (= "scip-java" (:provider_id (first errors))))))

  (testing "a batch with no provider is rejected"
    (is (some #(= :missing-provider-id (:code %))
              (fa/fact-batch-errors (fa/normalize-fact-batch {:facts []}))))))

(deftest arbitrate-batches-keeps-empty-and-failed-providers-visible-test
  (let [result (fa/arbitrate-batches
                [{:provider_id "java-regex"
                  :freshness "unknown"
                  :coverage {:paths ["src/example/OrderService.java"] :complete true}
                  :facts [batch-fact]}
                 {:provider_id "scip-java"
                  :freshness "exact"
                  :coverage {:paths [] :complete false}
                  :diagnostics [{:code :provider_unavailable
                                 :message "scip-java binary not installed"}]
                  :facts []}])]
    (testing "facts from the healthy provider are arbitrated"
      (is (= 1 (count (:facts result))))
      (is (= "heuristic" (:authority (first (:facts result))))))

    (testing "the provider that produced nothing is not silently absent"
      (let [scip (first (filter #(= "scip-java" (:provider_id %)) (:batches result)))]
        (is (= 0 (:fact_count scip)))
        (is (false? (get-in scip [:coverage :complete])))
        (is (= [:provider_unavailable] (mapv :code (:diagnostics scip))))))

    (is (empty? (:errors result)))))

(deftest invalid-batches-do-not-contribute-facts-test
  (let [result (fa/arbitrate-batches
                [{:provider_id "scip-java"
                  :freshness "stale"
                  :facts [{:key (unit-key {:arity 1})
                           :evidence [{:authority "exact"}]}]}])]
    (is (empty? (:facts result))
        "an invalid batch must not reach arbitration")
    (is (= [:exact-without-fresh-identity :exact-without-source-identity]
           (mapv :code (:errors result)))
        "and its rejection must be reported, not swallowed")
    (is (= 1 (count (:batches result)))
        "while the provider itself stays visible")))

;; --------------------------------------------------------------------------
;; Round-trip coverage: in-memory and PostgreSQL
;; --------------------------------------------------------------------------

(def ^:private round-trip-facts
  [regex-handle1 treesitter-handle1 scip-handle1 lsp-handle1 scip-handle2])

(defn- json-round-trip [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(deftest arbitration-output-survives-an-edn-round-trip-test
  (let [result (fa/arbitrate-facts round-trip-facts)]
    (is (= result (read-string (pr-str result)))
        "the kernel emits plain data that a snapshot payload can hold")))

(deftest identity-is-stable-across-a-json-round-trip-test
  (let [direct (fa/arbitrate-facts round-trip-facts)
        through-json (fa/arbitrate-facts (json-round-trip round-trip-facts))]
    (testing "serializing the inputs does not change what they identify"
      (is (= (mapv :fact_identity (:facts direct))
             (mapv :fact_identity (:facts through-json))))
      (is (= (mapv :canonical_fact_key_id (:facts direct))
             (mapv :canonical_fact_key_id (:facts through-json))))
      (is (= (mapv :authority (:facts direct))
             (mapv :authority (:facts through-json)))))
    (testing "and the arbitration output itself survives serialization"
      (is (= (json-round-trip direct) (json-round-trip through-json))))))

(deftest postgres-jsonb-round-trip-preserves-fact-identity-test
  (if-let [jdbc-url (System/getenv "SEMIDX_TEST_POSTGRES_URL")]
    (let [ds (jdbc/get-datasource {:jdbcUrl jdbc-url})
          table (str "semidx_fact_roundtrip_" (System/currentTimeMillis))
          result (fa/arbitrate-facts round-trip-facts)
          payload (json/write-str result)]
      (try
        (jdbc/execute! ds [(str "create table " table " (id text primary key, payload jsonb)")])
        (jdbc/execute! ds [(str "insert into " table " (id, payload) values (?, cast(? as jsonb))")
                           "run-1" payload])
        (let [row (first (jdbc/execute! ds [(str "select payload from " table " where id = ?") "run-1"]))
              stored (json/read-str (str (or (:payload row) (get row (keyword table "payload"))))
                                    :key-fn keyword)]
          (is (= (mapv :fact_identity (:facts result))
                 (mapv :fact_identity (:facts stored)))
              "fact identity must survive the jsonb round trip")
          (is (= (mapv :authority (:facts result))
                 (mapv :authority (:facts stored))))
          (is (= (count (mapcat :evidence (:facts result)))
                 (count (mapcat :evidence (:facts stored))))
              "no evidence may be dropped by storage")
          (is (= (json-round-trip result) stored)
              "the whole arbitration payload round trips unchanged"))
        (finally
          (jdbc/execute! ds [(str "drop table if exists " table)]))))
    (is true "SEMIDX_TEST_POSTGRES_URL is not set; skipping postgres fact round-trip test.")))
