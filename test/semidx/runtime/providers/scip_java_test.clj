(ns semidx.runtime.providers.scip-java-test
  "Stage 4 (plans/018, ADR-046) tests for the Java SCIP provider adapter.

  Deterministic assertions run `facts-from-index` over the committed real
  artifact `java-corpus.scrubbed.scip` with the committed corpus as the project
  root, so they need no toolchain. Two tests run the real repo-managed toolchain
  and are skipped when it does not resolve."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.fact-arbitration :as fa]
            [semidx.runtime.providers.scip-adapter :as scip-adapter]
            [semidx.runtime.providers.scip-java :as sj]
            [semidx.runtime.providers.scip-shadow-compare :as cmp]
            [semidx.runtime.scip :as scip]))

(def ^:private fixture-scip
  "fixtures/provider-authority/scip/java-corpus.scrubbed.scip")

(def ^:private corpus-root
  (io/file "fixtures/provider-authority/corpus/java"))

(def ^:private modelled-symbols
  #{"example.OrderService#handle"
    "example.OrderService#handleAll"
    "example.OrderService#OrderService"
    "example.Validator#validate"
    "example.Validator#Validator"})

(defn- fixture-index [] (scip/read-index fixture-scip))

(defn- facts-of [result]
  (set (map (comp :symbol :core_key) (:facts result))))

;; --- toolchain resolution --------------------------------------------

(deftest provider-status-reports-a-missing-toolchain
  (let [status (sj/provider-status {:scip_java_toolchain_dir "/semidx/does-not-exist"})]
    (is (= "unavailable" (:state status)))
    (is (contains? (set (:reason_codes status)) "scip_java_toolchain_missing"))
    (is (= "scip-java" (:provider_id status)))))

(deftest a-partial-toolchain-is-treated-as-no-toolchain
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "semidx-scip-java-partial-" (System/nanoTime)))]
    (try
      (.mkdirs (io/file dir "lib"))
      (.mkdirs (io/file dir "driver"))
      (spit (io/file dir "lib" "semanticdb-javac-0.12.3.jar") "not really a jar")
      (is (nil? (sj/resolve-toolchain {:scip_java_toolchain_dir (.getPath dir)}))
          "a lib dir with no compiled driver must not be half-run")
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete f))))))

;; --- facts-from-index over the committed artifact --------------------

(deftest java-facts-are-exact-arity-only-and-key-like-the-heuristic-tier
  (let [result (sj/facts-from-index (fixture-index) {:project-root corpus-root})]
    (is (= "ready" (:result result)))
    (is (empty? (:errors result)))
    (is (true? (get-in result [:coverage :complete])))
    (is (= modelled-symbols (facts-of result)))
    (is (every? #(= "exact" (:authority %)) (:facts result)))

    (testing "the exact tier commits arity only; no typed signature is invented"
      (is (every? #(= "arity_only" (:signature_precision %)) (:facts result)))
      (is (every? #(nil? (:signature_key %)) (:facts result))))

    (testing "the two same-name overloads keep distinct canonical keys"
      (let [handle (filter #(= "example.OrderService#handle" (:symbol (:core_key %)))
                           (:facts result))]
        (is (= 2 (count handle)))
        (is (= #{1 2} (set (map (comp :arity :core_key) handle))))
        (is (= 2 (count (distinct (map :canonical_fact_key_id handle)))))))

    (testing "the provider-native ordinal rides as evidence, never as key material"
      (let [arity-2 (first (filter #(and (= "example.OrderService#handle"
                                            (:symbol (:core_key %)))
                                         (= 2 (:arity (:core_key %))))
                                   (:facts result)))]
        (is (= "+1" (get-in (first (:evidence arity-2))
                            [:native_details :disambiguator])))
        (is (= "public String handle(String order, int retries)"
               (get-in (first (:evidence arity-2))
                       [:native_details :signature_documentation])))
        (is (nil? (get-in arity-2 [:core_key :signature_key]))
            "and none of it reaches the key")))

    (testing "every evidence record is anchored to a real content digest"
      (is (every? (fn [ev]
                    (some-> ev :source_identity :content_digest
                            (as-> d (re-matches #"sha256:[0-9a-f]{64}" d))))
                  (mapcat :evidence (:facts result)))))

    (testing "a cross-file reference keys on the defining file"
      (let [validate (first (filter #(= "example.Validator#validate"
                                        (:symbol (:core_key %)))
                                    (:facts result)))]
        (is (= "src/example/Validator.java" (:path (:core_key validate))))
        (is (contains? (set (map (comp :path :evidence_location) (:evidence validate)))
                       "src/example/OrderService.java")
            "the reference from OrderService is evidence on the Validator identity")))))

(deftest java-scip-keys-match-the-regex-tier-at-corpus-scale
  (let [scip-result (sj/facts-from-index (fixture-index) {:project-root corpus-root})
        comparison (cmp/compare-scip-run
                    scip-result
                    {:root_path corpus-root
                     :ts_paths ["src/example/OrderService.java"
                                "src/example/Validator.java"]
                     :parser_opts {:tree_sitter_enabled false}})]
    (testing "the language-neutral shadow harness diffs Java the same way"
      (is (= "ready" (:scip_result comparison)))
      (is (zero? (get-in comparison [:co_arbitration :diagnostic_count]))))

    (testing "every symbol both tiers found collapses to one canonical fact"
      (is (seq (get-in comparison [:comparison :agreed])))
      (is (empty? (get-in comparison [:comparison :legacy_only]))
          "the regex lane models nothing here that SCIP misses")
      (doseq [entry (get-in comparison [:co_arbitration :multi_provider_symbols])]
        (is (= "exact" (:authority entry)))
        (is (= ["java-regex" "scip-java"] (:providers entry)))))

    (testing "SCIP raises every shared symbol from heuristic to exact"
      (is (every? #(= {:legacy "heuristic" :exact "exact"}
                      (select-keys % [:legacy :exact]))
                  (get-in comparison [:comparison :authority_upgrade]))))))

;; --- the same-arity overload guard (Stage 4 exit criterion) ----------

(def ^:private same-arity-index
  "Two same-arity overloads spelled the way scip-java spells them: no types in
  the symbol, distinguished only by the source-order ordinal.

  Synthetic on purpose. The protected corpus has no same-arity pair (`handle` is
  arity 1 and arity 2) and adding one would change the Stage 0 extraction
  baseline, so the fixture specifies this case and the test supplies it."
  {:documents
   [{:relative-path "src/example/OrderService.java"
     :symbols [{:symbol "semanticdb maven . . example/OrderService#handle()."
                :kind :method
                :signature-documentation {:text "public String handle(String order)"}}
               {:symbol "semanticdb maven . . example/OrderService#handle(+1)."
                :kind :method
                :signature-documentation {:text "public String handle(int code)"}}]
     :occurrences [{:symbol "semanticdb maven . . example/OrderService#handle()."
                    :roles #{:definition} :range [24 18 24]}
                   {:symbol "semanticdb maven . . example/OrderService#handle(+1)."
                    :roles #{:definition} :range [28 18 24]}]}]})

(deftest same-arity-overloads-are-withheld-not-merged
  (let [result (sj/facts-from-index same-arity-index {:project-root corpus-root})]
    (testing "no exact fact is emitted for the ambiguous group"
      (is (empty? (:facts result)))
      (is (= 2 (get-in result [:coverage :withheld_fact_count])))
      (is (false? (get-in result [:coverage :complete]))))

    (testing "the diagnostic names the group and both native spellings"
      (let [diagnostic (first (filter #(= :same_arity_arity_only_overload_ambiguous
                                          (:code %))
                                      (:diagnostics result)))]
        (is (some? diagnostic))
        (is (= "example.OrderService#handle" (:symbol diagnostic)))
        (is (= 1 (:arity diagnostic)))
        (is (= ["semanticdb maven . . example/OrderService#handle()."
                "semanticdb maven . . example/OrderService#handle(+1)."]
               (:native_symbols diagnostic)))))))

(deftest without-the-guard-the-same-input-asserts-a-false-exact-identity
  ;; This is the defect the guard exists for, kept executable so the guard can
  ;; never be quietly removed: arbitrate-facts splits a core-key group only on
  ;; two or more distinct TYPED signatures, and an all-arity_only tier supplies
  ;; none. See reports/024 finding S2.
  (let [unguarded (scip-adapter/facts-from-index
                   same-arity-index
                   {:language "java"
                    :provider-id "scip-java"
                    :provider-version "1"
                    :project-root corpus-root
                    :guard-overloads? false})]
    (is (= 1 (count (:facts unguarded)))
        "two distinct overloads collapse into one canonical fact")
    (is (= ["exact"] (map :authority (:facts unguarded))))
    (is (empty? (filter #(= :same_arity_arity_only_overload_ambiguous (:code %))
                        (:diagnostics unguarded)))
        "and nothing warns about it, which is why the guard is mandatory")))

(deftest a-single-definition-per-arity-is-never-withheld
  (let [result (sj/facts-from-index (fixture-index) {:project-root corpus-root})]
    (is (zero? (get-in result [:coverage :withheld_fact_count]))
        "the corpus overloads differ in arity, so the guard must not fire")
    (is (empty? (filter #(= :same_arity_arity_only_overload_ambiguous (:code %))
                        (:diagnostics result))))))

;; --- stale gate ------------------------------------------------------

(deftest a-stale-java-document-contributes-no-exact-facts
  (let [result (sj/facts-from-index
                (fixture-index)
                {:project-root corpus-root
                 :expected-document-digests {"src/example/OrderService.java" "sha256:0000"}})]
    (is (= ["src/example/OrderService.java"] (get-in result [:coverage :stale_documents])))
    (is (false? (get-in result [:coverage :complete])))
    (is (not-any? #(= "src/example/OrderService.java" (:path (:core_key %)))
                  (:facts result))
        "the stale document's own definitions are gone")
    (let [diagnostic (first (filter #(= :scip_document_stale (:code %)) (:diagnostics result)))]
      (is (= "sha256:0000" (:expected diagnostic)))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:actual diagnostic))))))

(deftest a-missing-workspace-file-drops-the-whole-document
  (let [result (sj/facts-from-index
                (fixture-index)
                {:project-root (io/file (System/getProperty "java.io.tmpdir")
                                        "semidx-scip-java-no-such-project")})]
    (is (empty? (:facts result)))
    (is (false? (get-in result [:coverage :complete])))
    (is (every? #(= :scip_document_source_missing (:code %))
                (filter :document (:diagnostics result))))))

;; --- shadow-facts-for-project ---------------------------------------

(deftest shadow-facts-for-project-is-unavailable-not-an-error
  (let [result (sj/shadow-facts-for-project
                {:root_path corpus-root
                 :scip_java_toolchain_dir "/semidx/does-not-exist"})]
    (is (= "unavailable" (:result result)))
    (is (= ["scip_java_toolchain_missing"] (:reason_codes result)))
    (is (empty? (:facts result)))
    (is (= [:scip_provider_unavailable] (map :code (:diagnostics result))))))

(deftest shadow-facts-for-project-requires-a-root-path
  (is (thrown? clojure.lang.ExceptionInfo (sj/shadow-facts-for-project {}))))

(deftest end-to-end-through-the-repo-managed-toolchain
  (if (sj/resolve-toolchain {})
    (let [result (sj/shadow-facts-for-project {:root_path corpus-root})]
      (is (= "ready" (:result result)))
      (is (empty? (:errors result)))
      (is (= modelled-symbols (facts-of result))
          "the toolchain path produces the same facts as the committed fixture")
      (is (every? #(= "exact" (:authority %)) (:facts result)))
      (is (zero? (get-in result [:coverage :withheld_fact_count]))))
    (println "Java SCIP toolchain not resolved; skipping end-to-end test")))

(deftest a-project-with-no-java-sources-fails-visibly
  (if (sj/resolve-toolchain {})
    (let [empty-dir (io/file (System/getProperty "java.io.tmpdir")
                             (str "semidx-scip-java-empty-" (System/nanoTime)))]
      (try
        (.mkdirs empty-dir)
        (let [result (sj/shadow-facts-for-project {:root_path empty-dir})]
          (is (= "failed" (:result result)))
          (is (= [:scip_index_failed] (map :code (:diagnostics result))))
          (is (empty? (:facts result))))
        (finally (.delete empty-dir))))
    (println "Java SCIP toolchain not resolved; skipping empty-project test")))

;; --- review regressions (2026-09-05) --------------------------------

(def ^:private annotated-index
  "A method and a constructor carrying annotations with arguments, spelled the
  way scip-java 0.12.3 really spells them.

  The signature text and the `\\n` between the annotation and the declaration are
  ground truth captured from a scratch project through the real toolchain, not
  invented. Reading the annotation's paren group instead of the declaration's
  gave `f` arity 1 and `g` arity 2 — wrong in both directions, and enough to mint
  an exact fact on the wrong overload bucket."
  {:documents
   [{:relative-path "src/example/OrderService.java"
     :symbols [{:symbol "semanticdb maven . . example/Annotated#f()."
                :kind :method
                :signature-documentation {:text "@Ann(x = 1)\npublic void f(String a, int b)"}}
               {:symbol "semanticdb maven . . example/Annotated#g()."
                :kind :method
                :signature-documentation
                {:text "@Ann(x = 2, note = \"two\")\npublic String g(String a)"}}
               {:symbol "semanticdb maven . . example/Annotated#`<init>`()."
                :kind :constructor
                :signature-documentation
                {:text "@Ann(x = 3)\npublic Annotated(String a, int b, int c)"}}]
     :occurrences [{:symbol "semanticdb maven . . example/Annotated#f()."
                    :roles #{:definition} :range [5 16 17]}
                   {:symbol "semanticdb maven . . example/Annotated#g()."
                    :roles #{:definition} :range [9 18 19]}
                   {:symbol "semanticdb maven . . example/Annotated#`<init>`()."
                    :roles #{:definition} :range [13 11 20]}]}]})

(deftest annotation-arguments-do-not-corrupt-the-arity-in-the-key
  (let [result (sj/facts-from-index annotated-index {:project-root corpus-root})
        arity-of (fn [symbol]
                   (->> (:facts result)
                        (filter #(= symbol (:symbol (:core_key %))))
                        first
                        :core_key
                        :arity))]
    (is (= "ready" (:result result)))
    (is (= 2 (arity-of "example.Annotated#f")))
    (is (= 1 (arity-of "example.Annotated#g")))
    (is (= 3 (arity-of "example.Annotated#Annotated"))
        "an annotated constructor is located by the class name the text repeats")

    (testing "the corrected arity is what the canonical key is built from"
      (is (= (fa/canonical-fact-key-id
              {:fact_kind "unit" :language "java"
               :path "src/example/OrderService.java"
               :owner "example.Annotated" :symbol "example.Annotated#f"
               :overload_identity {:arity 2 :signature_precision "arity_only"
                                   :signature_key nil}})
             (->> (:facts result)
                  (filter #(= "example.Annotated#f" (:symbol (:core_key %))))
                  first
                  :canonical_fact_key_id))))))

(deftest a-method-with-an-unreadable-signature-degrades-rather-than-guessing
  (let [index {:documents
               [{:relative-path "src/example/OrderService.java"
                 :symbols [{:symbol "semanticdb maven . . example/Odd#mystery()."
                            :kind :method
                            :signature-documentation {:text "no parameter list here"}}]
                 :occurrences [{:symbol "semanticdb maven . . example/Odd#mystery()."
                                :roles #{:definition} :range [1 0 7]}]}]}
        result (sj/facts-from-index index {:project-root corpus-root})]
    (is (empty? (:facts result)) "no exact fact is minted from a guessed arity")
    (is (= [:arity-unavailable]
           (distinct (map :reason (:unmapped result)))))))

(deftest a-scip-document-path-cannot-escape-the-project-root
  (testing "a traversal path is refused without being read"
    (let [index {:documents
                 [{:relative-path "../typescript/src/orders.ts"
                   :symbols []
                   :occurrences [{:symbol "semanticdb maven . . example/X#y()."
                                  :roles #{:definition} :range [1 0 1]}]}]}
          result (sj/facts-from-index index {:project-root corpus-root})]
      (is (empty? (:facts result)))
      (is (= ["../typescript/src/orders.ts"] (get-in result [:coverage :invalid_documents])))
      (is (empty? (get-in result [:coverage :stale_documents]))
          "an unsafe path is not the same condition as a stale one")
      (is (false? (get-in result [:coverage :complete])))
      (let [diagnostic (first (filter #(= :scip_document_path_invalid (:code %))
                                      (:diagnostics result)))]
        (is (= :parent_traversal_in_document_path (:reason diagnostic))))))

  (testing "an absolute path degrades instead of throwing"
    (doseq [[path reason] {"/etc/hosts" :absolute_document_path
                           "C:\\Windows\\win.ini" :absolute_document_path
                           "" :blank_document_path}]
      (is (= {:state :invalid :reason reason}
             (scip-adapter/document-freshness corpus-root path nil))
          path)))

  (testing "a legitimate project-relative path is unaffected"
    (is (= :fresh (:state (scip-adapter/document-freshness
                           corpus-root "src/example/OrderService.java" nil))))
    (is (= :missing (:state (scip-adapter/document-freshness
                             corpus-root "src/example/Missing.java" nil))))))

(def ^:private nul-path
  "A path with a NUL byte: unnameable on any supported filesystem, so resolving
  it throws inside the JDK. Built here rather than written as a literal."
  (str "a" (char 0) "b"))

(deftest the-stale-gate-never-writes-to-stdout
  ;; These adapters run behind the MCP stdio transport, where anything on stdout
  ;; corrupts the protocol stream. Every failure path must return its reason for
  ;; a diagnostic instead of printing it.
  (testing "no path — valid, missing, unsafe, or unresolvable — prints"
    (doseq [path ["src/example/OrderService.java"
                  "src/example/Missing.java"
                  "../typescript/src/orders.ts"
                  "/etc/hosts"
                  ""
                  nul-path]]
      (is (= "" (with-out-str
                  (scip-adapter/document-freshness corpus-root path nil)))
          (str "document-freshness printed for " (pr-str path)))))

  (testing "a whole adapter run over a hostile index prints nothing"
    (let [hostile {:documents [{:relative-path "../typescript/src/orders.ts"
                                :symbols []
                                :occurrences []}
                               {:relative-path "/etc/hosts"
                                :symbols []
                                :occurrences []}]}]
      (is (= "" (with-out-str
                  (sj/facts-from-index hostile {:project-root corpus-root})))))))

(deftest an-unresolvable-document-path-degrades-with-a-reason
  (let [freshness (scip-adapter/document-freshness corpus-root nul-path nil)]
    (is (= :invalid (:state freshness)))
    (is (contains? #{:document_path_unresolvable :document_path_unreadable}
                   (:reason freshness)))
    (is (string? (:detail freshness))
        "the filesystem's own message is carried for the diagnostic")
    (let [diagnostic (scip-adapter/stale-diagnostic nul-path freshness)]
      (is (= :scip_document_path_invalid (:code diagnostic)))
      (is (string? (:detail diagnostic))))))
