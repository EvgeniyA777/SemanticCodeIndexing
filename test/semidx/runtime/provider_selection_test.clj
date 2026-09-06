(ns semidx.runtime.provider-selection-test
  "Stage 2 (plans/018) planning policy: deterministic, bounded provider plans
  that explain their own exclusions, plus the Stage 4.5 project-scoped plan and
  the batch-coverage input to per-file planning."
  (:require [clojure.test :refer [deftest testing is]]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.providers :as providers]))

(def ^:private java-path "src/example/OrderService.java")

(defn- status [state & reason-codes]
  {:state state :reason_codes (vec reason-codes) :observed_at "2026-08-28T00:00:00Z"})

(def ^:private all-ready
  {"java-tree-sitter" (status "ready")
   "java-regex" (status "ready")})

(def ^:private tree-sitter-missing
  {"java-tree-sitter" (status "unavailable" "tree_sitter_grammar_missing")
   "java-regex" (status "ready")})

(defn- plan [statuses & {:as overrides}]
  (selection/provider-plan (merge {:path java-path :statuses statuses} overrides)))

(deftest stronger-authority-is-planned-first-test
  (let [definitions (get-in (plan all-ready) [:operations :definitions])]
    (is (= ["java-tree-sitter" "java-regex"] (mapv :provider_id (:providers definitions)))
        "structural outranks heuristic, and the order is not registration order")
    (is (empty? (:excluded definitions)))))

(deftest planning-is-deterministic-test
  (is (= (plan all-ready) (plan all-ready)))
  (is (= (:operations (plan tree-sitter-missing))
         (:operations (plan tree-sitter-missing)))))

(deftest unavailable-providers-are-excluded-with-their-reason-test
  (let [definitions (get-in (plan tree-sitter-missing) [:operations :definitions])]
    (is (= ["java-regex"] (mapv :provider_id (:providers definitions)))
        "an unavailable structural provider routes to the lexical one")
    (is (= [{:provider_id "java-tree-sitter"
             :authority "structural"
             :reason "provider_unavailable"
             :state "unavailable"
             :reason_codes ["tree_sitter_grammar_missing"]}]
           (:excluded definitions))
        "the degradation is explicit, not an empty list")))

(deftest forced-mode-ignores-status-gating-test
  (let [definitions (get-in (plan tree-sitter-missing :mode "forced") [:operations :definitions])]
    (is (= ["java-tree-sitter" "java-regex"] (mapv :provider_id (:providers definitions)))
        "forced is a test control: it plans providers status would have excluded")))

(deftest execution-limit-is-explicit-and-recorded-test
  (let [definitions (get-in (plan all-ready
                                  :execution_policy {:max_providers_per_operation 1})
                            [:operations :definitions])]
    (is (= ["java-tree-sitter"] (mapv :provider_id (:providers definitions))))
    (is (= ["execution_limit_reached"] (mapv :reason (:excluded definitions))))))

(deftest denied-providers-are-excluded-by-override-test
  (let [definitions (get-in (plan all-ready :denied_providers ["java-tree-sitter"])
                            [:operations :definitions])]
    (is (= ["java-regex"] (mapv :provider_id (:providers definitions))))
    (is (= ["denied_by_override"] (mapv :reason (:excluded definitions))))))

(deftest plan-defaults-to-shadow-and-carries-its-policy-test
  (let [p (plan all-ready)]
    (is (= "shadow" (:mode p)))
    (is (= "shadow" (:mode (plan all-ready :mode "nonsense")))
        "an unknown mode falls back to shadow rather than planning the active path")
    (is (= 3 (get-in p [:execution_policy :max_providers_per_operation])))
    (is (pos? (get-in p [:execution_policy :timeout_ms])))
    (is (= "src/example/OrderService.java" (:path p)))))

(deftest every-claimed-operation-is-planned-test
  (let [p (plan all-ready)]
    (is (= [:definitions] (vec (keys (:operations p))))
        "a caller cannot silently plan fewer operations than the catalog claims")
    (is (= ["java-tree-sitter" "java-regex"] (selection/planned-provider-ids p)))))

(deftest an-unobserved-provider-is-not-assumed-ready-test
  (testing "a provider with no status is excluded, not admitted on a default"
    (let [definitions (get-in (plan {"java-regex" (status "ready")})
                              [:operations :definitions])]
      (is (= ["java-regex"] (mapv :provider_id (:providers definitions))))
      (is (= [{:provider_id "java-tree-sitter"
               :authority "structural"
               :reason "provider_status_unknown"
               :state "unknown"
               :reason_codes ["status_not_observed"]}]
             (:excluded definitions)))))

  (testing "forced mode still admits it, and says the state was not observed"
    (let [definitions (get-in (plan {"java-regex" (status "ready")} :mode "forced")
                              [:operations :definitions])]
      (is (= ["java-tree-sitter" "java-regex"] (mapv :provider_id (:providers definitions))))
      (is (= "forced" (:state (first (:providers definitions))))))))

(deftest planned-tasks-are-per-operation-test
  (let [p (plan all-ready)]
    (is (= [{:operation :definitions :provider_id "java-tree-sitter" :authority "structural"}
            {:operation :definitions :provider_id "java-regex" :authority "heuristic"}]
           (selection/planned-tasks p))
        "one task per (operation, provider), so a batch can name the operation it answered")))

;; --- Stage 4.5: project-scoped planning -------------------------------

(def ^:private ts-path "src/orders.ts")

(def ^:private scip-ready
  {"scip-typescript" (status "ready")})

(defn- comparable-plan
  "A plan with the wall-clock observation time removed. `:observed_at` is the one
  field two otherwise identical plans always differ on."
  [plan]
  (update plan :statuses #(into {} (map (fn [[k v]] [k (dissoc v :observed_at)])) %)))

(deftest a-plan-without-batch-input-is-the-pre-stage-plan-test
  (testing "the project catalog cannot influence a plan built without batch coverage"
    (let [with-catalog (selection/provider-plan {:path ts-path})
          without-catalog (with-redefs [providers/project-descriptors []]
                            (selection/provider-plan {:path ts-path}))]
      (is (= (comparable-plan without-catalog) (comparable-plan with-catalog))
          "emptying the project catalog must change nothing on the default path")))

  (testing "no project provider appears anywhere in the plan"
    (let [plan (selection/provider-plan {:path ts-path})
          mentioned (set (concat (keys (:statuses plan))
                                 (map :provider_id (mapcat :providers (vals (:operations plan))))
                                 (map :provider_id (mapcat :excluded (vals (:operations plan))))))]
      (is (not (contains? mentioned "scip-typescript")))
      (is (= [:definitions] (vec (keys (:operations plan))))
          "references is claimed only by the exact tier and must not appear"))))

(deftest batch-coverage-admits-the-exact-tier-first-test
  (let [plan (selection/provider-plan {:path ts-path
                                       :statuses {"typescript-regex" (status "ready")}
                                       :batch_coverage {"scip-typescript" [ts-path]}
                                       :observed_statuses scip-ready})]
    (testing "exact outranks the file tiers for definitions"
      (is (= ["scip-typescript" "typescript-regex"]
             (mapv :provider_id (get-in plan [:operations :definitions :providers])))))

    (testing "an operation only the exact tier claims becomes plannable"
      (is (= ["scip-typescript"]
             (mapv :provider_id (get-in plan [:operations :references :providers])))))))

(deftest batch-coverage-is-per-path-not-per-selector-test
  (testing "a provider whose batch did not cover this path is not a candidate"
    (let [plan (selection/provider-plan {:path ts-path
                                         :statuses {"typescript-regex" (status "ready")}
                                         :batch_coverage {"scip-typescript" ["src/other.ts"]}
                                         :observed_statuses scip-ready})]
      (is (= ["typescript-regex"]
             (mapv :provider_id (get-in plan [:operations :definitions :providers]))))
      (is (= [:definitions] (vec (keys (:operations plan))))))))

(deftest covered-but-unobserved-batch-provider-is-excluded-test
  (testing "coverage is not a status: without an observed status the exact tier is
            excluded with the same reason any unobserved provider gets"
    (let [plan (selection/provider-plan {:path ts-path
                                         :statuses {"typescript-regex" (status "ready")}
                                         :batch_coverage {"scip-typescript" [ts-path]}})
          definitions (get-in plan [:operations :definitions])
          exclusion (first (filter #(= "scip-typescript" (:provider_id %))
                                   (:excluded definitions)))]
      (is (= ["typescript-regex"] (mapv :provider_id (:providers definitions))))
      (is (= "provider_status_unknown" (:reason exclusion)))
      (is (= ["status_not_observed"] (:reason_codes exclusion))))))

(deftest project-plan-refuses-an-unobserved-provider-test
  (testing "no statuses at all: everything is excluded, nothing is assumed ready"
    (let [plan (selection/project-plan {:root_path "/tmp/project" :languages ["java"]})]
      (is (empty? (selection/planned-provider-ids plan)))
      (is (= [["scip-java" "provider_status_unknown"]]
             (mapv (juxt :provider_id :reason)
                   (get-in plan [:operations :definitions :excluded]))))))

  (testing "an unavailable toolchain is excluded with its own reason codes"
    (let [plan (selection/project-plan
                {:root_path "/tmp/project"
                 :languages ["java"]
                 :statuses {"scip-java" (status "unavailable" "scip_java_toolchain_missing")}})
          excluded (first (get-in plan [:operations :definitions :excluded]))]
      (is (= "provider_unavailable" (:reason excluded)))
      (is (= ["scip_java_toolchain_missing"] (:reason_codes excluded))))))

(deftest project-plan-admits-once-per-provider-test
  (let [plan (selection/project-plan {:root_path "/tmp/project"
                                      :statuses {"scip-java" (status "ready")
                                                 "scip-typescript" (status "ready")}})]
    (testing "both operations are planned"
      (is (= [:definitions :references] (vec (keys (:operations plan))))))

    (testing "but a batch runner iterates each provider once, not once per operation"
      (is (= ["scip-java" "scip-typescript"] (selection/planned-provider-ids plan))))

    (testing "the plan carries its scope and language set"
      (is (= "project" (:scope plan)))
      (is (= #{"java" "typescript"} (set (:languages plan)))))))

(deftest project-plan-honours-denied-providers-test
  (let [plan (selection/project-plan {:root_path "/tmp/project"
                                      :languages ["typescript"]
                                      :statuses scip-ready
                                      :denied_providers ["scip-typescript"]})]
    (is (empty? (selection/planned-provider-ids plan)))
    (is (= ["denied_by_override"]
           (mapv :reason (get-in plan [:operations :definitions :excluded]))))))
