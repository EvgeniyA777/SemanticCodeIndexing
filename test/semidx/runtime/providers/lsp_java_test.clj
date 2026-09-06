(ns semidx.runtime.providers.lsp-java-test
  "Stage 5b (plans/018, ADR-046) Java live-overlay provider.

  Toolchain resolution, JDK gating, arity parsing, and symbol mapping are
  deterministic and need no server. One end-to-end test starts a real jdtls
  through the Stage 5a seam and is skipped when the toolchain or a JDK 21+ is
  unavailable."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.provider-overlay :as overlay]
            [semidx.runtime.providers.lsp-java :as jl]
            [semidx.test-support.lsp-toolchain :as toolchain]))

(def ^:private corpus-root
  (.getPath (io/file "fixtures/provider-authority/corpus/java")))

;; --- Toolchain and JDK gating -------------------------------------------

(deftest there-is-no-ambient-path-step-test
  (testing "with no explicit option, no environment, and no repo-managed
            install, resolution is nil — a jdtls on PATH is the unpinned install
            the toolchain exists to replace and is never fallen back to"
    (with-redefs [jl/toolchain-dir-name "/semidx/does-not-exist"]
      (is (nil? (jl/resolve-home {:java_lsp_home "/semidx/also-missing"
                                  :lsp_ignored true}))
          "and this holds even though a `jdtls` executable exists on this machine"))))

(deftest missing-toolchain-is-reported-not-guessed-test
  (with-redefs [jl/resolve-home (fn [_] nil)]
    (let [status (jl/provider-status {})]
      (is (= "unavailable" (:state status)))
      (is (contains? (set (:reason_codes status)) "jdtls_home_missing")))))

(deftest a-jdk-below-21-is-refused-before-startup-test
  (testing "under JDK 17 jdtls fails OSGi resolution and closes the stream, so
            the version is part of the probe rather than a startup surprise"
    (with-redefs [jl/resolve-home (fn [_] "/tmp")
                  jl/resolve-launcher (fn [_] "/tmp/launcher.jar")
                  jl/jdk-major-version (fn [_] 17)]
      (let [status (jl/provider-status {})]
        (is (= "unavailable" (:state status)))
        (is (contains? (set (:reason_codes status)) "jdtls_java_too_old"))
        (is (= 17 (:observed_jdk_major status)))))))

(deftest jdk-version-parsing-test
  (testing "the probe reads a major version from a real JVM"
    (let [own (str (System/getProperty "java.home") "/bin/java")
          major (jl/jdk-major-version own)]
      (is (some? major) "the JVM running this suite reports its own version")
      (is (pos? major))))

  (testing "an unresolvable command is nil, never a throw"
    (is (nil? (jl/jdk-major-version "/semidx/no-such-java")))
    (is (nil? (jl/jdk-major-version nil)))))

(deftest platform-config-is-chosen-not-assumed-test
  (is (contains? #{"config_mac" "config_mac_arm" "config_linux" "config_linux_arm"
                   "config_win"}
                 (jl/platform-config-name))))

(deftest workspace-data-lives-outside-the-repository-test
  (let [dir (jl/workspace-data-dir {} corpus-root)]
    (is (not (.startsWith dir (.getCanonicalPath (io/file "."))))
        "jdtls writes an Eclipse workspace, which must not land in the repo")
    (is (not= (jl/workspace-data-dir {} corpus-root)
              (jl/workspace-data-dir {} (.getPath (io/file "fixtures"))))
        "two roots must not share one Eclipse workspace")))

;; --- Arity ---------------------------------------------------------------

(deftest arity-comes-from-the-symbol-name-test
  (is (= 1 (jl/declared-arity "handle(String)")))
  (is (= 2 (jl/declared-arity "handle(String, int)")))
  (is (= 1 (jl/declared-arity "handleAll(List<String>)")))
  (is (= 1 (jl/declared-arity "OrderService(Validator)")))
  (is (= 0 (jl/declared-arity "noParams()")))
  (testing "a comma inside generic arguments does not separate parameters"
    (is (= 2 (jl/declared-arity "put(Map<K, V>, int)")))
    (is (= 1 (jl/declared-arity "of(Map<String, List<Integer>>)"))))
  (testing "no parameter list at all is nil, not zero"
    (is (nil? (jl/declared-arity "validator")))))

;; --- Symbol mapping ------------------------------------------------------

(defn- symbol* [name kind & children]
  {:name name
   :kind kind
   :range {:start {:line 0} :end {:line 3}}
   :selectionRange {:start {:line 0 :character 0}}
   :children (vec children)})

(deftest the-package-is-a-sibling-not-a-parent-test
  (testing "jdtls returns the package and the type at the same depth; taking
            ownership from the tree alone yields OrderService#handle, which
            never merges with the regex tier's example.OrderService#handle"
    (let [walked (jl/flatten-symbols
                  [(symbol* "example" 4)
                   (symbol* "OrderService" 5
                            (symbol* "handle(String)" 6)
                            (symbol* "OrderService(Validator)" 9))])
          definitions (filterv :symbol walked)]
      (is (= ["example.OrderService#handle" "example.OrderService#OrderService"]
             (mapv :symbol definitions)))
      (is (= ["example.OrderService" "example.OrderService"]
             (mapv :owner definitions)))
      (is (= ["method" "constructor"] (mapv :kind definitions)))
      (is (= [1 1] (mapv :arity definitions))))))

(deftest a-file-without-a-package-still-maps-test
  (let [walked (jl/flatten-symbols [(symbol* "Loose" 5 (symbol* "run()" 6))])]
    (is (= ["Loose#run"] (mapv :symbol (filterv :symbol walked))))))

(deftest fields-are-not-units-test
  (testing "plans/017 models entity fields as relations, never as units"
    (let [walked (jl/flatten-symbols
                  [(symbol* "example" 4)
                   (symbol* "OrderService" 5 (symbol* "validator" 8))])]
      (is (empty? (filterv :symbol walked)))
      (is (= ["unmodelled_lsp_symbol_kind_8"] (mapv :reason walked))))))

;; --- End to end through the Stage 5a seam --------------------------------

(deftest end-to-end-through-the-repo-managed-toolchain
  (let [status (jl/provider-status {})]
    (if (= "ready" (:state status))
      (let [result (overlay/shadow-facts-for-overlay
                    {:root_path corpus-root
                     :documents [{:path "src/example/OrderService.java"}]})
            states (get (:documents result) "java-lsp")
            facts (:facts (first (:files result)))
            by-symbol (group-by #(get-in % [:core_key :symbol]) facts)
            handles (get by-symbol "example.OrderService#handle")]
        (is (= ["java-lsp"] (:planned_provider_ids result)))
        (is (= "ready" (:result states)))
        (is (= ["src/example/OrderService.java"] (:analysed states)))

        (testing "the LSP tier lands on the same canonical key as the regex tier"
          (let [merged (filter #(contains? (set (map :provider_id (:evidence %))) "java-lsp")
                               facts)]
            (is (seq merged))
            (is (every? #(= "exact" (:authority %)) merged))
            (is (every? #(contains? (set (map :provider_id (:evidence %))) "java-regex")
                        merged)
                "both tiers merge onto one fact instead of minting two identities")))

        (testing "the two same-name overloads stay distinct by arity"
          (is (= 2 (count handles)))
          (is (= #{1 2} (set (map #(get-in % [:core_key :arity]) handles)))))

        (testing "overload identity stays arity-only, as scip-java already does"
          (is (= [nil nil] (mapv :signature_key handles))
              "asserted on the values themselves: filtering on the key made this
               vacuously true over an empty sequence")
          (is (every? #(= "arity_only" (:signature_precision %)) handles))))
      (toolchain/unresolved! (str "jdtls (" (:reason_codes status) ")")
                             "Java overlay end-to-end test"))))
