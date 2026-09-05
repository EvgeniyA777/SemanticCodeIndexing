(ns semidx.runtime.provider-selection-test
  "Stage 2 (plans/018) planning policy: deterministic, bounded provider plans
  that explain their own exclusions."
  (:require [clojure.test :refer [deftest testing is]]
            [semidx.runtime.provider-selection :as selection]))

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
