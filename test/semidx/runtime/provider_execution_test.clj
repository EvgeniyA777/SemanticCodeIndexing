(ns semidx.runtime.provider-execution-test
  "Stage 2 (plans/018) orchestrator: bounded execution, failure isolation,
  timeouts, gap tracking, and the shadow seam end to end."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [semidx.runtime.index :as index]
            [semidx.runtime.provider-execution :as execution]
            [semidx.runtime.provider-selection :as selection]
            [semidx.runtime.providers :as providers]))

(def ^:private java-root "fixtures/provider-authority/corpus/java")
(def ^:private java-path "src/example/OrderService.java")

(defn- status [state & reason-codes]
  {:state state :reason_codes (vec reason-codes) :observed_at "2026-08-28T00:00:00Z"})

(defn- plan-for [statuses & {:as overrides}]
  (selection/provider-plan (merge {:path java-path
                                   :source_identity {:content_digest "sha256:test"}
                                   :statuses statuses}
                                  overrides)))

(defn- scripted-runner
  "Runner that replays a per-provider script instead of parsing."
  [script]
  (fn [provider-id _request]
    (let [entry (get script provider-id)]
      (cond
        (fn? entry) (entry)
        (nil? entry) {:facts [] :diagnostics []}
        :else entry))))

(def ^:private one-fact
  {:facts [{:key {:fact_kind "unit"
                  :language "java"
                  :path java-path
                  :owner "example.OrderService"
                  :symbol "example.OrderService#handle"
                  :overload_identity {:arity 1 :signature_precision "arity_only"}}
            :evidence [{:authority "heuristic"}]}]
   :diagnostics []})

;; --------------------------------------------------------------------------
;; Isolation
;; --------------------------------------------------------------------------

(deftest a-failing-provider-does-not-take-the-run-down-test
  (let [result (execution/execute-plan
                (plan-for {"java-tree-sitter" (status "ready")
                           "java-regex" (status "ready")})
                {:run-provider (scripted-runner
                                {"java-tree-sitter" (fn [] (throw (ex-info "grammar exploded" {})))
                                 "java-regex" one-fact})})
        by-provider (into {} (map (juxt :provider_id identity)) (:batches result))]
    (testing "the healthy provider still produced its facts"
      (is (= 1 (count (:facts (get by-provider "java-regex"))))))

    (testing "the failure is recorded as an incomplete batch, not lost"
      (let [failed (get by-provider "java-tree-sitter")]
        (is (empty? (:facts failed)))
        (is (false? (get-in failed [:coverage :complete])))
        (is (= [:provider_failed] (mapv :code (:diagnostics failed))))
        (is (re-find #"grammar exploded" (:message (first (:diagnostics failed)))))))))

(deftest a-hanging-provider-is-timed-out-test
  (let [result (execution/execute-plan
                (plan-for {"java-tree-sitter" (status "ready") "java-regex" (status "ready")}
                          :execution_policy {:timeout_ms 50})
                {:run-provider (scripted-runner
                                {"java-tree-sitter" (fn [] (Thread/sleep 5000) one-fact)
                                 "java-regex" one-fact})})
        hung (first (filter #(= "java-tree-sitter" (:provider_id %)) (:batches result)))]
    (is (= [:provider_timeout] (mapv :code (:diagnostics hung))))
    (is (empty? (:facts hung)))
    (is (= 50 (:timeout_ms (first (:diagnostics hung)))))
    (testing "the rest of the plan still completed"
      (is (some #(seq (:facts %)) (:batches result))))))

(deftest gaps-distinguish-an-empty-file-from-a-failed-run-test
  (testing "no provider produced facts: the operation is reported as a gap"
    (let [result (execution/execute-plan
                  (plan-for {"java-tree-sitter" (status "ready") "java-regex" (status "ready")})
                  {:run-provider (scripted-runner {})})
          definitions (first (filter #(= :definitions (:operation %)) (:gaps result)))]
      (is (some? definitions))
      (is (= "no_provider_produced_facts" (:reason definitions)))
      (is (= ["java-tree-sitter" "java-regex"] (:planned_providers definitions)))))

  (testing "no provider was even admitted: the gap says so and carries the exclusions"
    (let [result (execution/execute-plan
                  (plan-for {"java-tree-sitter" (status "unavailable" "tree_sitter_cli_missing")
                             "java-regex" (status "unavailable" "disabled_for_test")})
                  {:run-provider (scripted-runner {})})
          definitions (first (filter #(= :definitions (:operation %)) (:gaps result)))]
      (is (= "no_provider_admitted" (:reason definitions)))
      (is (= ["java-tree-sitter" "java-regex"] (mapv :provider_id (:excluded definitions))))))

  (testing "a covered operation is not a gap"
    (let [result (execution/execute-plan
                  (plan-for {"java-regex" (status "ready")
                             "java-tree-sitter" (status "unavailable" "tree_sitter_cli_missing")})
                  {:run-provider (scripted-runner {"java-regex" one-fact})})]
      (is (empty? (filter #(= :definitions (:operation %)) (:gaps result)))))))

;; --------------------------------------------------------------------------
;; Shadow seam end to end
;; --------------------------------------------------------------------------

(deftest shadow-run-produces-heuristic-facts-from-the-real-corpus-test
  ;; Deliberately not asserting an exact provider list: whether
  ;; `java-tree-sitter` is admitted depends on whether a Java grammar is
  ;; installed, so an equality check here encodes the machine it was written on.
  ;; It did: this passed locally without a grammar and failed on CI, which has
  ;; one. The invariants below hold either way.
  (let [result (execution/shadow-facts-for-file {:root_path java-root :path java-path})
        batches (mapv (juxt :provider_id :operation) (:batches result))]
    (is (= "shadow" (:mode result)))
    (is (= 4 (count (:facts result)))
        "the same four units regardless of how many providers found them")
    (testing "regex evidence never reaches exact authority"
      (is (empty? (remove #{"heuristic" "structural"} (map :authority (:facts result))))
          "a shadow run has no exact tier at all")
      (is (contains? (set (map :authority (:facts result))) "heuristic")
          "the regex tier always contributes"))
    (is (empty? (:errors result))
        "every emitted batch must satisfy the Stage 1 evidence contract")
    (testing "batches name the operation they answered"
      (is (some #{["java-regex" "definitions"]} batches)
          "regex is admitted on every machine")
      (is (every? #(= "definitions" (second %)) batches)
          "Stage 2 claims definitions only")
      (is (empty? (remove #{"java-regex" "java-tree-sitter"} (map first batches)))
          "no provider outside the Java catalog may appear"))
    (testing "the plan that produced them is retained with the result"
      (is (= "file_bytes_sha256" (get-in result [:plan :source_identity :digest_basis])))
      (is (= "sha256" (subs (get-in result [:plan :source_identity :content_digest]) 0 6)))
      (is (seq (get-in result [:plan :operations :definitions :providers]))))))

(deftest shadow-run-is-deterministic-test
  (let [strip (fn [result]
                (-> result
                    (dissoc :plan)
                    (update :batches (partial mapv #(dissoc % :diagnostics)))))
        first-run (execution/shadow-facts-for-file {:root_path java-root :path java-path})
        second-run (execution/shadow-facts-for-file {:root_path java-root :path java-path})]
    (is (= (strip first-run) (strip second-run))
        "same content and same catalog must produce the same shadow facts")
    (is (= (get-in first-run [:plan :operations]) (get-in second-run [:plan :operations]))
        "planning is deterministic too; only status observation times differ")))

(deftest tree-sitter-unavailability-routes-to-regex-with-a-degradation-test
  (let [result (execution/shadow-facts-for-file
                {:root_path java-root
                 :path java-path
                 :denied_providers ["java-tree-sitter"]})
        definitions (get-in result [:plan :operations :definitions])]
    (testing "the structural provider is excluded with a stated reason"
      (is (= ["java-tree-sitter"] (mapv :provider_id (:excluded definitions))))
      (is (seq (:reason (first (:excluded definitions))))))
    (testing "the lexical provider covers the operation, so there is no gap"
      (is (= ["java-regex"] (mapv :provider_id (:providers definitions))))
      (is (seq (:facts result)))
      (is (empty? (get-in result [:execution :gaps]))))))

;; --------------------------------------------------------------------------
;; The default path must not move
;; --------------------------------------------------------------------------

(deftest shadow-execution-does-not-change-default-indexing-test
  (let [build #(index/create-index {:raw_build? true
                                    :root_path java-root
                                    :parser_opts {:tree_sitter_enabled false}})
        differing (fn [a b] (set (for [k (keys a) :when (not= (get a k) (get b k))] k)))
        ;; Control: two builds with nothing between them. snapshot_id and
        ;; indexed_at are minted per build, so a bare equality check on the whole
        ;; index would fail with or without a shadow run and prove nothing.
        control-a (build)
        control-b (build)
        control-diff (differing control-a control-b)
        before (build)
        shadow (execution/shadow-facts-for-file {:root_path java-root :path java-path})
        after (build)]
    (is (seq (:facts shadow)) "the shadow run must actually have done something")

    (testing "a shadow run introduces no difference the control does not already have"
      (is (= control-diff (differing before after)))
      (is (= #{:snapshot_id :indexed_at} control-diff)))

    (testing "every content-bearing field is untouched"
      (is (= (:units before) (:units after)))
      (is (= (:relations before) (:relations after)))
      (is (= (:files before) (:files after)))
      (is (= (:diagnostics before) (:diagnostics after)))
      (is (= (:parser_mode before) (:parser_mode after))))

    (testing "the shadow seam contributed nothing to the snapshot"
      (is (not (contains? after :facts)))
      (is (not (contains? after :provider_shadow))))))
