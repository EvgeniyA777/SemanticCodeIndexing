(ns semidx.runtime.providers.scip-normalize-test
  "Stage 3/4 (plans/018, ADR-046) tests for SCIP -> CanonicalFactKey
  normalization.

  Layers:

  1. `parse-scip-symbol` against hand-written SCIP symbol strings. It is
     language-neutral, so both indexers' grammars are checked against it.
  2. `scip-symbol->unit` — the per-language moniker -> semidx spelling bridge.
  3. `normalize-index` over the committed real artifacts
     `typescript-corpus.scrubbed.scip` and `java-corpus.scrubbed.scip`, checked
     against the Stage 0 identity fixtures and the arbitration kernel."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.fact-arbitration :as fa]
            [semidx.runtime.languages.java :as java-language]
            [semidx.runtime.providers.scip-normalize :as sn]
            [semidx.runtime.scip :as scip]))

(def ^:private fixture-scip
  "fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip")

(def ^:private java-fixture-scip
  "fixtures/provider-authority/scip/java-corpus.scrubbed.scip")

(def ^:private java-corpus-root
  "fixtures/provider-authority/corpus/java")

(def ^:private anchored-identity
  {:content_digest "sha256:corpus-fixture" :revision "test-revision"})

;; --- parse-scip-symbol ---------------------------------------------------

(deftest parses-the-scip-symbol-grammar
  (testing "package fields plus namespace/method descriptors"
    (is (= {:scheme "scip-typescript" :manager "npm" :package-name "." :version "."
            :descriptors [{:kind :namespace :name "src"}
                          {:kind :namespace :name "orders.ts"}
                          {:kind :method :name "normalize" :disambiguator ""}]}
           (sn/parse-scip-symbol "scip-typescript npm . . src/`orders.ts`/normalize()."))))

  (testing "a type descriptor before the method"
    (is (= [{:kind :namespace :name "src"}
            {:kind :namespace :name "orders.ts"}
            {:kind :type :name "OrderService"}
            {:kind :method :name "handle" :disambiguator ""}]
           (:descriptors (sn/parse-scip-symbol
                          "scip-typescript npm . . src/`orders.ts`/OrderService#handle().")))))

  (testing "backtick-escaped constructor name"
    (is (= {:kind :method :name "<constructor>" :disambiguator ""}
           (last (:descriptors (sn/parse-scip-symbol
                                "scip-typescript npm . . src/`orders.ts`/OrderService#`<constructor>`()."))))))

  (testing "a trailing parameter descriptor"
    (is (= {:kind :parameter :name "value"}
           (last (:descriptors (sn/parse-scip-symbol
                                "scip-typescript npm . . src/`orders.ts`/normalize().(value)"))))))

  (testing "a field is a term descriptor"
    (is (= {:kind :term :name "validator"}
           (last (:descriptors (sn/parse-scip-symbol
                                "scip-typescript npm . . src/`orders.ts`/OrderService#validator."))))))

  (testing "an external package keeps its real name and version"
    (let [parsed (sn/parse-scip-symbol
                  "scip-typescript npm typescript 5.9.3 lib/`lib.es5.d.ts`/String#trim().")]
      (is (= "typescript" (:package-name parsed)))
      (is (= "5.9.3" (:version parsed)))))

  (testing "local and empty symbols"
    (is (= {:scheme "local" :local-id "0" :descriptors []}
           (sn/parse-scip-symbol "local 0")))
    (is (= {:error :empty-symbol} (sn/parse-scip-symbol "")))
    (is (= :malformed-symbol (:error (sn/parse-scip-symbol "not a scip symbol")))))

  (testing "a descriptor sequence the reader rejects is an :error, never a throw"
    (doseq [bad ["scip-typescript npm . . src/`orders.ts"          ; unterminated backtick
                 "scip-typescript npm . . src/`orders.ts`/normalize("]] ; malformed method
      (is (= :unparseable-descriptors (:error (sn/parse-scip-symbol bad))) bad)
      (is (string? (:message (sn/parse-scip-symbol bad))) bad))))

;; --- scip-symbol->unit -------------------------------------------------

(defn- unit-of [sym]
  (sn/scip-symbol->unit "typescript" (sn/parse-scip-symbol sym)))

(deftest bridges-mappable-symbols-onto-semidx-spelling
  (testing "top-level function: <module>/<name>, module from the file path"
    (is (= {:owner "src.orders" :symbol "src.orders/normalize" :kind "function"
            :path "src/orders.ts"}
           (unit-of "scip-typescript npm . . src/`orders.ts`/normalize().")))
    (is (= {:owner "src.orders" :symbol "src.orders/createOrder" :kind "function"
            :path "src/orders.ts"}
           (unit-of "scip-typescript npm . . src/`orders.ts`/createOrder()."))))

  (testing "class method: <module>.<Class>#<method>"
    (is (= {:owner "src.orders.OrderService" :symbol "src.orders.OrderService#handle"
            :kind "function" :path "src/orders.ts"}
           (unit-of "scip-typescript npm . . src/`orders.ts`/OrderService#handle().")))
    (is (= {:owner "src.validator.Validator" :symbol "src.validator.Validator#validate"
            :kind "function" :path "src/validator.ts"}
           (unit-of "scip-typescript npm . . src/`validator.ts`/Validator#validate()."))))

  (testing "a top-level term (const/let) maps as a :kind \"term\" unit"
    ;; scip-typescript spells every top-level binding as a term descriptor,
    ;; whether it holds an arrow function or a plain value.
    (is (= {:owner "src.config" :symbol "src.config/normalize" :kind "term"
            :path "src/config.ts"}
           (unit-of "scip-typescript npm . . src/`config.ts`/normalize."))
        "an arrow-function const still keys on <module>/<name>")
    (is (= {:owner "src.config" :symbol "src.config/VERSION" :kind "term"
            :path "src/config.ts"}
           (unit-of "scip-typescript npm . . src/`config.ts`/VERSION."))
        "a plain value const is an exact-only term unit"))

  (testing "an arrow-function term shares its canonical key with the regex tier"
    (let [u (unit-of "scip-typescript npm . . src/`config.ts`/normalize.")]
      (is (= (fa/canonical-fact-key-id {:fact_kind "unit" :language "typescript"
                                        :path "src/config.ts" :owner "src.config"
                                        :symbol "src.config/normalize" :overload_identity nil})
             (fa/canonical-fact-key-id {:fact_kind "unit" :language "typescript"
                                        :path (:path u) :owner (:owner u)
                                        :symbol (:symbol u) :overload_identity nil}))))))

(deftest records-kinds-semidx-does-not-model-as-unmapped
  (doseq [[reason sym]
          {:type-symbol "scip-typescript npm . . src/`orders.ts`/OrderService#"
           :field-symbol "scip-typescript npm . . src/`orders.ts`/OrderService#validator."
           :constructor-symbol "scip-typescript npm . . src/`orders.ts`/OrderService#`<constructor>`()."
           :non-unit-descriptor "scip-typescript npm . . src/`orders.ts`/normalize().(value)"
           :module-symbol "scip-typescript npm . . src/`orders.ts`/"
           :external-symbol "scip-typescript npm typescript 5.9.3 lib/`lib.es5.d.ts`/String#trim()."
           :local-symbol "local 0"}]
    (is (= reason (:unmapped (unit-of sym))) sym)))

;; --- normalize-index over the real artifact ---------------------------

(deftest normalizes-the-committed-scip-artifact
  (let [{:keys [facts unmapped]}
        (sn/normalize-index (scip/read-index fixture-scip)
                            {:language "typescript"
                             :source-identity anchored-identity})]

    (testing "every emitted fact is a well-formed exact-authority unit fact"
      (is (seq facts))
      (is (every? #(= "unit" (get-in % [:key :fact_kind])) facts))
      (is (every? #(= "typescript" (get-in % [:key :language])) facts))
      (is (empty? (mapcat (comp fa/fact-evidence-errors fa/normalize-fact-evidence)
                          (mapcat :evidence facts)))
          "anchored source identity + exact + fresh must validate clean"))

    (testing "SCIP normalizes onto the SAME key the regex tier produces"
      (let [normalize-fact (first (filter #(= "src.orders/normalize"
                                              (get-in % [:key :symbol]))
                                          facts))]
        (is (some? normalize-fact))
        (is (= (fa/canonical-fact-key-id (:key normalize-fact))
               (fa/canonical-fact-key-id {:fact_kind "unit" :language "typescript"
                                          :path "src/orders.ts" :owner "src.orders"
                                          :symbol "src.orders/normalize"
                                          :overload_identity nil})))))

    (testing "the re-export surface: index.ts references resolve to the orders.ts origin"
      (let [normalize-evidence (->> facts
                                    (filter #(= "src.orders/normalize" (get-in % [:key :symbol])))
                                    (mapcat :evidence))]
        (is (= #{"definitions" "references"} (set (map :operation normalize-evidence))))
        (is (= 1 (count (filter #(= "definitions" (:operation %)) normalize-evidence))))
        (is (contains? (set (map (comp :path :evidence_location) normalize-evidence))
                       "src/index.ts")
            "the canonicalize/normalize re-export tokens in index.ts corroborate the origin")))

    (testing "only the kinds semidx models are minted: top-level fns and methods"
      (is (= #{"src.orders/normalize"
               "src.orders/createOrder"
               "src.orders.OrderService#handle"
               "src.validator.Validator#validate"}
             (set (map #(get-in % [:key :symbol]) facts)))
          "class, constructor, field, param, module, and stdlib symbols mint no fact")
      (is (not-any? #(str/starts-with? (str (get-in % [:key :owner])) "src.index") facts)
          "the alias export src/canonicalize is never a SCIP unit fact"))

    (testing "unmapped symbols carry a reason"
      (is (every? :reason unmapped))
      (is (contains? (set (map :reason unmapped)) :external-symbol)))))

(deftest one-malformed-occurrence-does-not-abort-the-index
  (let [{:keys [facts unmapped]}
        (sn/normalize-index
         {:documents [{:relative-path "src/x.ts"
                       :occurrences [{:symbol "scip-typescript npm . . src/`x.ts`/foo()."
                                      :roles #{:definition} :range [1 0 3]}
                                     {:symbol "scip-typescript npm . . src/`x.ts`/bar("
                                      :roles #{} :range [2 0 3]}]}]}
         {:language "typescript" :source-identity anchored-identity})]
    (is (= ["src.x/foo"] (map #(get-in % [:key :symbol]) facts))
        "the good occurrence still produces its fact")
    (is (= [:unparseable-descriptors] (map :reason unmapped))
        "the malformed occurrence becomes unmapped, not an exception")))

(deftest scip-and-regex-evidence-merge-into-one-exact-fact
  (let [{:keys [facts]} (sn/normalize-index (scip/read-index fixture-scip)
                                            {:language "typescript"
                                             :source-identity anchored-identity})
        scip-normalize (filter #(= "src.orders/normalize" (get-in % [:key :symbol])) facts)
        regex-fact {:key {:fact_kind "unit" :language "typescript" :path "src/orders.ts"
                          :owner "src.orders" :symbol "src.orders/normalize"
                          :overload_identity nil}
                    :evidence [{:provider_id "typescript-regex" :authority "heuristic"
                                :freshness "unknown"
                                :native_symbol "src.orders/normalize"}]}
        {:keys [facts diagnostics]} (fa/arbitrate-facts (conj (vec scip-normalize) regex-fact))
        merged (first facts)]
    (is (= 1 (count facts)))
    (is (empty? diagnostics))
    (is (= "exact" (:authority merged)) "SCIP exact wins over regex heuristic")
    (is (= #{"scip-typescript" "typescript-regex"}
           (set (map :provider_id (:evidence merged))))
        "both tiers' evidence is retained on the one fact")
    (is (= (:canonical_fact_key_id merged) (:fact_identity merged))
        "Variant C: attaching SCIP evidence does not change the regex-only identity")))

;; --- identity fixture parity -----------------------------------------

(defn- read-identity-fixture [file-name]
  (with-open [rdr (io/reader (io/file "fixtures/provider-authority/identity" file-name))]
    (json/read rdr :key-fn keyword)))

(deftest scip-spelling-matches-the-typescript-re-export-identity-fixture
  (let [fixture (read-identity-fixture "typescript-re-export-canonical-key.json")
        edge (:re_export_edge_must_resolve_to_origin fixture)
        origin (:expected_canonical_key_of_origin edge)
        scip-spelling (->> (:provider_spellings edge)
                           (filter #(= "scip-typescript" (:provider_id %)))
                           first)
        ;; the SCIP native re-export target moniker, mapped by this slice
        mapped (sn/scip-symbol->unit
                "typescript"
                (sn/parse-scip-symbol (:native_re_export_target scip-spelling)))]
    (testing "the fixture still describes scip-typescript's verified behaviour"
      (is (false? (:alias_symbol_emitted scip-spelling)))
      (is (false? (:relationship_emitted scip-spelling))))

    (testing "the SCIP moniker resolves to the origin's canonical key, field by field"
      (is (= (:owner origin) (:owner mapped)))
      (is (= (:symbol origin) (:symbol mapped)))
      (is (= (:path origin) (:path mapped)))
      (is (= (fa/canonical-fact-key-id {:fact_kind "unit"
                                        :language (:language origin)
                                        :path (:path origin)
                                        :owner (:owner origin)
                                        :symbol (:symbol origin)
                                        :overload_identity (:overload_identity origin)})
             (fa/canonical-fact-key-id {:fact_kind "unit"
                                        :language "typescript"
                                        :path (:path mapped)
                                        :owner (:owner mapped)
                                        :symbol (:symbol mapped)
                                        :overload_identity nil}))))

    (testing "the alias export stays a distinct identity SCIP never mints"
      (doseq [distinct-fact (:distinct_facts_must_not_merge fixture)
              :let [expected (:expected_canonical_key distinct-fact)]]
        (is (not= (fa/canonical-fact-key-id {:fact_kind "unit" :language "typescript"
                                             :path (:path mapped) :owner (:owner mapped)
                                             :symbol (:symbol mapped) :overload_identity nil})
                  (fa/canonical-fact-key-id {:fact_kind "unit" :language "typescript"
                                             :path (:path expected) :owner (:owner expected)
                                             :symbol (:symbol expected) :overload_identity nil}))
            (str (:fact distinct-fact) " — " (:reason distinct-fact)))))))

;; --- Java bridge -------------------------------------------------------
;;
;; scip-java puts neither parameter types nor arity in the symbol: it
;; disambiguates overloads with a source-order ordinal (`handle(+1).`) and
;; carries the signature only as documentation text. The Java exact tier
;; therefore commits arity_only, and arity is parsed from that text. See
;; reports/024 finding S1 and the identity fixture's
;; `scip_java_verified_contract`.

(deftest signature-arity-counts-parameters-not-commas
  (testing "plain and empty parameter lists"
    (is (= 1 (sn/signature-arity "public String handle(String order)" "handle")))
    (is (= 2 (sn/signature-arity "public String handle(String order, int retries)" "handle")))
    (is (= 0 (sn/signature-arity "public void noArgs()" "noArgs")))
    (is (= 1 (sn/signature-arity "public OrderService(Validator validator)" "OrderService"))))

  (testing "a comma inside a generic or an array does not add an argument"
    (is (= 1 (sn/signature-arity "public List<String> handleAll(List<String> orders)" "handleAll")))
    (is (= 2 (sn/signature-arity "int[] f(int[] a, Map<String, List<Integer>> m)" "f"))
        "the comma inside Map<String, List<Integer>> is nested, not a separator"))

  (testing "an annotation with arguments is not the parameter list"
    ;; These texts are the real shape scip-java 0.12.3 emits, captured from a
    ;; scratch project. Reading the annotation's paren group instead of the
    ;; declaration's was wrong in both directions.
    (is (= 2 (sn/signature-arity "@Ann(x = 1)\npublic void f(String a, int b)" "f"))
        "under-counted as 1 before the fix")
    (is (= 1 (sn/signature-arity "@Ann(x = 2, note = \"two\")\npublic String g(String a)" "g"))
        "over-counted as 2 before the fix")
    (is (= 3 (sn/signature-arity "@Ann(x = 3)\npublic Annotated(String a, int b, int c)"
                                 "Annotated"))
        "a constructor is located by its class name, which is what the text repeats")
    (is (= 0 (sn/signature-arity "@Deprecated\npublic void none()" "none"))
        "a marker annotation has no paren group at all"))

  (testing "the declaration wins over an annotation that repeats its name"
    (is (= 1 (sn/signature-arity "@f(1)\npublic void f(int x)" "f"))
        "the last identifier-boundary match is the declaration, not the annotation"))

  (testing "an unlocatable parameter list yields nil so the caller degrades"
    (is (nil? (sn/signature-arity nil "f")))
    (is (nil? (sn/signature-arity "" "f")))
    (is (nil? (sn/signature-arity "not a signature" "f")))
    (is (nil? (sn/signature-arity "public void f(int x)" nil))
        "no declaration name means the list cannot be located")
    (is (nil? (sn/signature-arity "public void f(int x" "f"))
        "an unbalanced group is not guessed at")))

(defn- java-fixture-index [] (scip/read-index java-fixture-scip))

(defn- java-unit-of
  ([sym] (java-unit-of sym (sn/java-index-context (java-fixture-index))))
  ([sym context]
   (sn/scip-symbol->unit "java"
                         (assoc (sn/parse-scip-symbol sym) :native_symbol sym)
                         context)))

(deftest java-bridge-reproduces-the-heuristic-lane-spelling
  (testing "a method keys on <package>.<Class>#<method>, the regex lane's spelling"
    (let [unit (java-unit-of "semanticdb maven . . example/OrderService#handle().")]
      (is (= "example.OrderService" (:owner unit)))
      (is (= "example.OrderService#handle" (:symbol unit)))
      (is (= "method" (:kind unit)))
      (is (= "src/example/OrderService.java" (:path unit))
          "the path comes from the declaring document, not the moniker")))

  (testing "the overload ordinal is evidence, and both overloads key on arity"
    (let [first-overload (java-unit-of "semanticdb maven . . example/OrderService#handle().")
          second-overload (java-unit-of "semanticdb maven . . example/OrderService#handle(+1).")]
      (is (= 1 (get-in first-overload [:overload_identity :arity])))
      (is (= 2 (get-in second-overload [:overload_identity :arity])))
      (is (= "" (:native_disambiguator first-overload)))
      (is (= "+1" (:native_disambiguator second-overload)))
      (is (= "arity_only" (get-in second-overload [:overload_identity :signature_precision])))
      (is (nil? (get-in second-overload [:overload_identity :signature_key]))
          "owner decision 2026-09-05: the Java exact tier commits no typed signature")))

  (testing "a constructor is translated from <init> to the class-name spelling"
    (let [unit (java-unit-of "semanticdb maven . . example/OrderService#`<init>`().")]
      (is (= "example.OrderService#OrderService" (:symbol unit)))
      (is (= "constructor" (:kind unit)))
      (is (= 1 (get-in unit [:overload_identity :arity]))))))

(deftest java-bridge-unmapped-reasons
  (let [context (sn/java-index-context (java-fixture-index))]
    (doseq [[reason sym]
            {:type-symbol "semanticdb maven . . example/OrderService#"
             :field-symbol "semanticdb maven . . example/OrderService#validator."
             :external-symbol "semanticdb maven jdk 17 java/lang/String#trim()."
             :local-symbol "local 0"
             :nested-type-symbol "semanticdb maven . . example/Outer#Inner#run()."
             :symbol-not-declared-in-index "semanticdb maven . . example/Absent#gone()."}]
      (is (= reason (:unmapped (java-unit-of sym context))) sym))))

(deftest java-normalization-over-the-committed-artifact
  (let [{:keys [facts unmapped]}
        (sn/normalize-index (java-fixture-index)
                            {:language "java" :source-identity anchored-identity})]

    (testing "every emitted fact is a well-formed exact-authority java unit fact"
      (is (seq facts))
      (is (every? #(= "java" (get-in % [:key :language])) facts))
      (is (every? #(= "arity_only" (get-in % [:key :overload_identity :signature_precision]))
                  facts))
      (is (empty? (mapcat (comp fa/fact-evidence-errors fa/normalize-fact-evidence)
                          (mapcat :evidence facts)))
          "anchored source identity + exact + fresh must validate clean"))

    (testing "the two same-name overloads stay distinct canonical keys"
      (let [handle-keys (->> facts
                             (filter #(= "example.OrderService#handle"
                                         (get-in % [:key :symbol])))
                             (map #(fa/canonical-fact-key-id (:key %)))
                             set)]
        (is (= 2 (count handle-keys))
            "arity 1 and arity 2 must not collapse")))

    (testing "a cross-file reference keys on the DEFINING file, not the referencing one"
      (let [validate-facts (filter #(= "example.Validator#validate"
                                       (get-in % [:key :symbol]))
                                   facts)
            evidence (mapcat :evidence validate-facts)]
        (is (seq validate-facts))
        (is (every? #(= "src/example/Validator.java" (get-in % [:key :path])) validate-facts)
            "the identity is the declaration's path")
        (is (contains? (set (map (comp :path :evidence_location) evidence))
                       "src/example/OrderService.java")
            "but the evidence records where the reference physically is")))

    (testing "unmapped symbols carry a reason, and locals/externals are among them"
      (is (every? :reason unmapped))
      (is (contains? (set (map :reason unmapped)) :external-symbol))
      (is (contains? (set (map :reason unmapped)) :local-symbol)))))

(deftest java-scip-keys-match-the-regex-tier
  (let [{:keys [facts]} (sn/normalize-index (java-fixture-index)
                                            {:language "java"
                                             :source-identity anchored-identity})
        path "src/example/OrderService.java"
        lines (str/split-lines (slurp (io/file java-corpus-root path)))
        regex-units (:units (java-language/parse-file java-corpus-root path lines {}))
        scip-key-ids (set (map #(fa/canonical-fact-key-id (:key %)) facts))]
    (is (seq regex-units))
    (testing "every regex-tier unit lands on a key SCIP also produced"
      (doseq [unit regex-units]
        (let [regex-key-id (fa/canonical-fact-key-id
                            {:fact_kind "unit"
                             :language "java"
                             :path path
                             :owner (:module unit)
                             :symbol (:symbol unit)
                             :overload_identity (when-let [arity (:method_arity unit)]
                                                  {:arity arity
                                                   :signature_precision "arity_only"
                                                   :signature_key nil})})]
          (is (contains? scip-key-ids regex-key-id)
              (str (:symbol unit) " arity " (:method_arity unit)
                   " must produce one key across both tiers")))))))

(deftest java-identity-fixture-parity
  (let [fixture (read-identity-fixture "java-overload-canonical-key.json")
        spelling (->> (get-in fixture [:same_fact_must_merge :provider_spellings])
                      (filter #(= "scip-java" (:provider_id %)))
                      first)
        expected (get-in fixture [:same_fact_must_merge :expected_canonical_key])
        mapped (java-unit-of (:native_symbol spelling))]

    (testing "the fixture records the verified scip-java contract"
      (is (true? (:ground_truth spelling)))
      (is (nil? (:native_signature_key spelling)))
      (is (= "arity_only" (get-in spelling [:variant_c_contribution :signature_precision])))
      (is (nil? (get-in spelling [:variant_c_contribution :signature_key]))))

    (testing "the scip-java moniker resolves to the fixture's canonical key"
      (is (= (:owner expected) (:owner mapped)))
      (is (= (:symbol expected) (:symbol mapped)))
      (is (= (:path expected) (:path mapped)))
      (is (= (get-in expected [:overload_identity :arity])
             (get-in mapped [:overload_identity :arity]))))

    (testing "the fixture's distinct facts keep distinct keys"
      (let [mapped-key-id (fa/canonical-fact-key-id
                           {:fact_kind "unit" :language "java" :path (:path mapped)
                            :owner (:owner mapped) :symbol (:symbol mapped)
                            :overload_identity (:overload_identity mapped)})]
        (doseq [distinct-fact (:distinct_facts_must_not_merge fixture)
                :let [key (:expected_canonical_key distinct-fact)]]
          (is (not= mapped-key-id
                    (fa/canonical-fact-key-id
                     {:fact_kind "unit" :language "java" :path (:path key)
                      :owner (:owner key) :symbol (:symbol key)
                      :overload_identity {:arity (:arity key)
                                          :signature_precision "arity_only"
                                          :signature_key nil}}))
              (str (:fact distinct-fact) " — " (:reason distinct-fact))))))))

(deftest an-unsupported-language-is-refused-not-guessed
  (is (thrown? clojure.lang.ExceptionInfo
               (sn/normalize-index {:documents []} {:language "cobol"})))
  (is (= :unsupported-language
         (:unmapped (sn/scip-symbol->unit "cobol" (sn/parse-scip-symbol "x y . . a/b#c()."))))))
