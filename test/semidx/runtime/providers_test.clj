(ns semidx.runtime.providers-test
  "Stage 2 (plans/018) provider catalog: descriptors, status probes, and the
  role functions that turn a parse into facts."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.fact-arbitration :as fa]
            [semidx.runtime.languages.shared :as shared]
            [semidx.runtime.providers :as providers]
            [semidx.runtime.provider-execution :as execution]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.workspace-state :as workspace-state]))

(def ^:private java-root "fixtures/provider-authority/corpus/java")
(def ^:private java-path "src/example/OrderService.java")
(def ^:private ts-root "fixtures/provider-authority/corpus/typescript")
(def ^:private ts-path "src/orders.ts")

(defn- lines-for [root path]
  (shared/slurp-lines (io/file root path)))

(defn- run [provider-id root path]
  (providers/run-provider provider-id
                          {:root_path root
                           :path path
                           :lines (lines-for root path)
                           :parser_opts {}}))

(deftest descriptors-are-serializable-data-test
  (testing "the catalog holds data, not functions, so it can be stored and diffed"
    (is (= providers/descriptors (read-string (pr-str providers/descriptors))))))

(deftest selectors-choose-providers-by-path-test
  (is (= #{"java-tree-sitter" "java-regex"}
         (set (map :provider_id (providers/descriptors-for java-path)))))
  (is (= #{"typescript-tree-sitter" "typescript-regex"}
         (set (map :provider_id (providers/descriptors-for ts-path)))))
  (is (empty? (providers/descriptors-for "src/main.py")))
  (testing "an operation the catalog does not claim selects nothing"
    (is (empty? (providers/descriptors-for java-path :type_hierarchy)))))

(deftest regex-providers-are-always-ready-and-never-exact-test
  (let [status (providers/provider-status "java-regex")]
    (is (= "ready" (:state status)))
    (is (empty? (:reason_codes status))))
  (testing "ADR-046: a lexical provider may not claim exact for any operation"
    (doseq [descriptor providers/descriptors
            :when (= "lexical" (:classification descriptor))
            [operation authority] (:operation_capabilities descriptor)]
      (is (not= "exact" authority)
          (str (:provider_id descriptor) " claims exact for " operation)))))

(deftest tree-sitter-status-names-what-is-missing-test
  (let [status (providers/provider-status "java-tree-sitter" {})]
    (is (contains? #{"ready" "unavailable"} (:state status)))
    (when (= "unavailable" (:state status))
      (is (seq (:reason_codes status))
          "an unavailable provider must say which prerequisite is missing")
      (is (every? #{"tree_sitter_cli_missing" "tree_sitter_grammar_missing"}
                  (:reason_codes status))))))

(deftest unknown-provider-is-refused-not-guessed-test
  (is (= "unavailable" (:state (providers/provider-status "scip-java"))))
  (is (= ["unknown_provider"] (:reason_codes (providers/provider-status "scip-java"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (providers/run-provider "scip-java" {:path java-path :lines []}))))

(deftest java-facts-commit-arity-only-per-variant-c-test
  (let [{:keys [facts]} (run "java-regex" java-root java-path)
        handles (filter #(= "example.OrderService#handle" (get-in % [:key :symbol])) facts)]
    (is (= 4 (count facts)))
    (testing "the heuristic tier commits arity and never a typed signature"
      (doseq [fact facts]
        (let [overload (get-in fact [:key :overload_identity])]
          (is (= "arity_only" (:signature_precision overload)))
          (is (nil? (:signature_key overload)))
          (is (number? (:arity overload))))))
    (testing "distinct overloads of one method stay distinct facts"
      (is (= 2 (count handles)))
      (is (= #{1 2} (set (map #(get-in % [:key :overload_identity :arity]) handles))))
      (is (= 2 (count (distinct (map #(fa/canonical-fact-key-id (:key %)) handles))))))))

(deftest typescript-facts-carry-no-overload-identity-test
  (let [{:keys [facts]} (run "typescript-regex" ts-root ts-path)]
    (is (seq facts))
    (testing "TypeScript exposes no arity, and the fixture's origin key has none"
      (is (every? nil? (map #(get-in % [:key :overload_identity]) facts))))
    (is (some #(= "src.orders/normalize" (get-in % [:key :symbol])) facts))))

(deftest evidence-is-anchored-and-classified-test
  (let [{:keys [facts]} (run "java-regex" java-root java-path)
        evidence (first (:evidence (first facts)))]
    (testing "regex evidence is heuristic, as ADR-046 requires"
      (is (= "heuristic" (:authority evidence))))
    (testing "evidence is anchored to the content that produced it"
      (is (re-find #"^sha256:" (get-in evidence [:source_identity :content_digest])))
      (is (fa/anchored-source-identity? (:source_identity evidence))))
    (testing "the provider's native spelling is retained as evidence only"
      (is (= "example.OrderService#OrderService" (:native_symbol evidence)))
      (is (not (contains? (:key (first facts)) :native_symbol))))
    (testing "every emitted evidence record is valid on its own terms"
      (doseq [fact facts
              ev (:evidence fact)]
        (is (empty? (fa/fact-evidence-errors (fa/normalize-fact-evidence ev))))))))

(deftest source-identity-names-its-digest-basis-test
  (testing "a readable file is digested by bytes, the same basis as workspace state"
    (let [identity* (providers/source-identity {:root_path java-root :path java-path})]
      (is (= providers/file-digest-basis (:digest_basis identity*)))
      (is (= (str "sha256:" (workspace-state/sha256-file (io/file java-root java-path)))
             (:content_digest identity*))
          "provider evidence must be comparable to workspace freshness")))

  (testing "lines-only callers get a digest that says it is not the file basis"
    (let [identity* (providers/source-identity {:lines ["a" "b"]})]
      (is (= providers/lines-digest-basis (:digest_basis identity*)))
      (is (not= (:content_digest identity*)
                (:content_digest (providers/source-identity {:root_path java-root
                                                             :path java-path}))))))

  (testing "the joined-lines digest still tracks its own content"
    (is (= (providers/lines-digest ["a" "b"]) (providers/lines-digest ["a" "b"])))
    (is (not= (providers/lines-digest ["a" "b"]) (providers/lines-digest ["a" "c"])))))

(deftest tree-sitter-provider-refuses-a-silent-regex-fallback-test
  (testing "the fallback signal is recognised"
    (is (some? (providers/tree-sitter-fallback-diagnostic
                {:diagnostics [{:code "tree_sitter_missing_grammar"}]})))
    (is (some? (providers/tree-sitter-fallback-diagnostic
                {:diagnostics [{:code "tree_sitter_unavailable"}]})))
    (is (nil? (providers/tree-sitter-fallback-diagnostic
               {:diagnostics [{:code "tree_sitter_probe"}]}))
        "the positive CLI probe is not a fallback")
    (is (some? (providers/tree-sitter-fallback-diagnostic
                {:diagnostics [{:code "tree_sitter_some_future_failure"}]}))
        "an unknown tree_sitter_* code must fail closed, not pass as structural"))

  (testing "a tree-sitter provider fails rather than labelling regex facts structural"
    ;; Both cases degrade on any machine: with no grammar configured, and with a
    ;; grammar path that cannot parse. Either way parse-file silently returns
    ;; regex units, which must not be emitted under the structural claim.
    (doseq [[case-name parser-opts] [["no grammar configured" {:tree_sitter_grammars {}}]
                                     ["unusable grammar path"
                                      {:tree_sitter_grammars {:java "/nonexistent/grammar"}}]]]
      (let [ex (try
                 (providers/run-provider "java-tree-sitter"
                                         {:root_path java-root
                                          :path java-path
                                          :lines (lines-for java-root java-path)
                                          :parser_opts parser-opts})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str "expected a refusal for: " case-name))
        (is (= :tree_sitter_fallback_refused (:error_code (ex-data ex))))
        (is (= "java-tree-sitter" (:provider_id (ex-data ex))))
        (is (seq (get-in (ex-data ex) [:diagnostic :code]))))))

  (testing "the refusal reaches the orchestrator as a failed batch, not a crash"
    (let [result (execution/execute-plan
                  (selection/provider-plan
                   {:path java-path
                    :source_identity {:content_digest "sha256:test"}
                    :mode "forced"
                    :parser_opts {:tree_sitter_grammars {}}})
                  {:root_path java-root
                   :lines (lines-for java-root java-path)
                   :parser_opts {:tree_sitter_grammars {}}})
          by-provider (into {} (map (juxt :provider_id identity)) (:batches result))]
      (is (= [:provider_failed]
             (mapv :code (:diagnostics (get by-provider "java-tree-sitter")))))
      (is (empty? (:facts (get by-provider "java-tree-sitter"))))
      (testing "and the regex provider supplies the same facts as heuristic"
        (let [regex-batch (get by-provider "java-regex")]
          (is (= 4 (count (:facts regex-batch))))
          (is (= #{"heuristic"}
                 (set (map #(get-in % [:evidence 0 :authority]) (:facts regex-batch))))))))))
