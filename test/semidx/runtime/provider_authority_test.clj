(ns semidx.runtime.provider-authority-test
  "Stage 6.1 (plans/018, ADR-046): the provider plan as the default extraction
  path for Java and TypeScript.

  Two of these tests run against the committed TypeScript corpus and its
  scrubbed `.scip` fixture, so the merge is exercised against an artifact no
  test wrote. The rest inject a project result, because what they prove is the
  merge policy — upgrade, add, annotate, and leave every other language alone."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.adapters :as adapters]
            [semidx.runtime.provider-authority :as authority]
            [semidx.runtime.provider-batch :as batch]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.providers :as providers]
            [semidx.runtime.providers.scip-typescript :as scip-typescript]
            [semidx.runtime.scip :as scip]))

(def ^:private corpus-root (io/file "fixtures/provider-authority/corpus/typescript"))
(def ^:private fixture-scip "fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip")

(defn- root [] (.getPath corpus-root))

(defn- ready-status []
  {:state "ready" :reason_codes [] :observed_at "2026-09-06T00:00:00Z"})

(defn- ctx-serving
  "A build context whose project tier returns `result`."
  [result]
  (let [statuses {"scip-typescript" (ready-status)}
        plan (selection/project-plan {:root_path (root)
                                      :languages ["typescript"]
                                      :mode "default"
                                      :statuses statuses})
        execution (batch/execute-project-plan
                   plan
                   {:root_path (root)
                    :provider_negative_cache false
                    :project_roles {"scip-typescript" {:status-fn (fn [_] (ready-status))
                                                       :run-fn (fn [_] result)}}})]
    {:languages ["typescript"]
     :statuses statuses
     :project_plan plan
     :project_execution execution
     :coverage (batch/batch-coverage execution)}))

(defn- project-result
  "A ready project result carrying `facts` and covering `paths`."
  [facts paths]
  {:provider_id "scip-typescript"
   :provider_version "1"
   :result "ready"
   :facts []
   :raw_facts (vec facts)
   :batches []
   :errors []
   :diagnostics []
   :coverage {:covered_paths (vec paths)
              :stale_documents []
              :invalid_documents []
              :withheld_fact_count 0
              :complete true}
   :unmapped []})

(defn- provider-fact
  "One definition fact as a provider tier would emit it for `unit`."
  [authority-level path unit]
  (providers/unit->fact
   {:provider_id "scip-typescript"
    :provider_version "1"
    :authority authority-level
    :language "typescript"
    :source_identity (providers/source-identity {:root_path (root) :path path})}
   (merge {:path path :start_line 1 :end_line 2} unit)))

(deftest an-exact-fact-upgrades-the-unit-it-matches-test
  (let [scip-result (scip-typescript/facts-from-index (scip/read-index fixture-scip)
                                                      {:project-root corpus-root})
        parsed (authority/parse-file (root) "src/orders.ts" {} (ctx-serving scip-result))
        upgraded (filterv #(= "exact" (:authority %)) (:units parsed))]
    (is (seq upgraded)
        "the exact tier covered this document, so at least one parsed unit is exact")
    (is (every? #(contains? (set (:evidence_providers %)) "scip-typescript") upgraded))
    (is (every? #(contains? (set (:evidence_providers %)) "typescript-regex") upgraded)
        "the parse the unit came from stays on the evidence: an upgrade is not a
         replacement")
    (is (every? :canonical_fact_key_id upgraded))

    (testing "the unit keeps everything only the parser knows"
      (let [unit (first upgraded)]
        (is (seq (:signature unit)))
        (is (some? (:module unit)))
        (is (contains? unit :calls))))))

(deftest a-fact-with-no-parsed-counterpart-becomes-a-unit-test
  (let [path "src/orders.ts"
        baseline (adapters/parse-file (root) path {})
        invented (provider-fact "exact" path {:symbol "orders/neverParsedByRegex"
                                              :module "orders"
                                              :kind "function"
                                              :unit_id (str path "::orders/neverParsedByRegex")
                                              :signature "export function neverParsedByRegex()"})
        parsed (authority/parse-file (root) path {}
                                     (ctx-serving (project-result [invented] [path])))
        added (first (filter #(= "orders/neverParsedByRegex" (:symbol %)) (:units parsed)))]
    (is (some? added)
        "a symbol the semantic tier resolved and the lexical tier missed is a unit,
         not a number in a shadow report")
    (is (= "exact" (:authority added)))
    (is (= ["scip-typescript"] (:evidence_providers added)))
    (is (= (inc (count (:units baseline))) (count (:units parsed))))
    (is (some #(= "provider_authority_units_added" (str (:code %))) (:diagnostics parsed))
        "the addition is announced in the file diagnostics")))

(deftest an-equal-authority-conflict-annotates-and-keeps-the-unit-test
  (let [path "src/orders.ts"
        baseline (adapters/parse-file (root) path {})
        parsed-unit (first (:units baseline))
        ;; Same key, same authority as the lexical tier, different value: the
        ;; contradiction the owner decided to annotate rather than block.
        contradicting (provider-fact "heuristic" path
                                     (assoc (select-keys parsed-unit
                                                         [:symbol :module :kind :method_arity])
                                            :unit_id (:unit_id parsed-unit)
                                            :signature "a different signature entirely"))
        parsed (authority/parse-file (root) path {}
                                     (ctx-serving (project-result [contradicting] [path])))
        same-unit (first (filter #(= (:symbol parsed-unit) (:symbol %)) (:units parsed)))]
    (is (some? same-unit) "the contradicted unit is still in the snapshot")
    (is (true? (:evidence_conflict same-unit))
        "and it says it is contradicted rather than hiding it")
    (is (= (count (:units baseline)) (count (:units parsed)))
        "annotating adds no unit and drops none")
    (is (some #(= "equal_authority_value_conflict" (str (:code %))) (:diagnostics parsed))
        "the arbitration diagnostic reaches the file, not just a shadow report")))

(deftest without-a-context-the-default-path-is-untouched-test
  (testing "no context is what an :off or :shadow build passes"
    (is (= (adapters/parse-file (root) "src/orders.ts" {})
           (authority/parse-file (root) "src/orders.ts" {} nil))))

  (testing "and a language outside the switch falls through even with a context"
    (let [ctx (ctx-serving (project-result [] []))]
      (is (= (adapters/parse-file "." "src/semidx/runtime/provider_authority.clj" {})
             (authority/parse-file "." "src/semidx/runtime/provider_authority.clj" {} ctx))))))

(deftest an-unavailable-project-tier-leaves-the-units-lexical-test
  (testing "the common case — no SCIP toolchain — keeps every unit the parser
            produced, and each one states the authority of the only tier that saw
            it rather than borrowing one it was never given"
    (let [path "src/orders.ts"
          baseline (adapters/parse-file (root) path {})
          ctx (ctx-serving (assoc (project-result [] []) :result "unavailable"
                                  :reason_codes ["scip_cli_missing"]))
          parsed (authority/parse-file (root) path {} ctx)]
      (is (= (mapv :unit_id (:units baseline)) (mapv :unit_id (:units parsed)))
          "no unit is added, dropped, or reidentified when only the lexical tier ran")
      (is (= (mapv :parser_mode (:units baseline)) (mapv :parser_mode (:units parsed)))
          "and none is relabelled: degradation labelling is Stage 6.2, and 6.1 must
           not smuggle it in")
      (is (= #{"heuristic"} (set (map :authority (:units parsed))))
          "the lexical tier is heuristic and says so")
      (is (= #{["typescript-regex"]} (set (map :evidence_providers (:units parsed))))
          "with no second tier there is nothing else on the evidence"))))
