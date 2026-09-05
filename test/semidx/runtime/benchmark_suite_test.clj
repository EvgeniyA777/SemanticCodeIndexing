(ns semidx.runtime.benchmark-suite-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.benchmark-suite :as bs]))

(def committed-suite (bs/load-suite))

(deftest committed-suite-is-valid-test
  (let [report (bs/validate-suite committed-suite)]
    (is (:valid? report) (str "violations: " (:violations report)))
    (is (>= (:task_count report) 8))
    (is (>= (count (:task_types report)) bs/minimum-task-types))))

(deftest committed-suite-carries-every-negative-utility-case-test
  (doseq [task-id bs/required-negative-utility-task-ids]
    (let [task (bs/task committed-suite task-id)]
      (is (some? task) (str "missing calibration case " task-id))
      (is (seq (get-in task [:negative_utility :comparator_requirement]))
          (str task-id " must state what the competent baseline has to do")))))

(deftest committed-suite-includes-an-external-repository-test
  (let [repos (bs/repositories-by-key committed-suite)
        used (set (map :repo_key (:tasks committed-suite)))
        external (filter (fn [[repo-key repo]] (and (:external repo) (contains? used repo-key)))
                         repos)]
    (is (seq external)
        "measuring only on the semidx repo would overstate the result")))

(deftest stale-snapshot-case-declares-a-mutation-test
  (let [task (bs/task committed-suite "stale_snapshot_after_edit_v1")]
    (is (= "append_text" (get-in task [:workspace_mutation :kind])))
    (is (true? (get-in task [:freshness_check :require_post_mutation_snapshot])))))

(deftest workspace-path-prefers-the-environment-override-test
  (let [repo {:default_workspace_path "/default/path"
              :workspace_path_env "SEMIDX_BENCH_DEFINITELY_UNSET_PATH"}]
    (is (= "/default/path" (bs/workspace-path repo))))
  (testing "an env var name that is absent falls back to the default"
    (is (= "." (bs/workspace-path {:default_workspace_path "."})))))

(deftest repository-lookup-resolves-workspace-path-test
  (let [repo (bs/repository committed-suite "semidx")]
    (is (= "semidx" (:repo_key repo)))
    (is (= "." (:workspace_path repo))))
  (is (nil? (bs/repository committed-suite "not-a-repo"))))

(def minimal-suite
  {:suite_version "test_v1"
   :repositories [{:repo_key "local" :external false :default_workspace_path "."}]
   :tasks [{:task_id "t1" :task_type "symbol_lookup" :repo_key "local"
            :arms ["A" "B"] :prompt "find it"
            :ground_truth {:required_paths ["src/a.clj"]}}]})

(deftest validation-requires-the-negative-utility-cases-test
  (let [report (bs/validate-suite minimal-suite)
        codes (set (map :code (:violations report)))]
    (is (false? (:valid? report)))
    (is (contains? codes "missing_negative_utility_case"))
    (is (contains? codes "no_external_repository_in_corpus"))
    (is (contains? codes "insufficient_task_type_spread"))))

(deftest validation-rejects-duplicate-task-ids-test
  (let [suite (update minimal-suite :tasks conj (first (:tasks minimal-suite)))
        codes (set (map :code (:violations (bs/validate-suite suite))))]
    (is (contains? codes "duplicate_task_id"))))

(deftest validation-rejects-tasks-without-ground-truth-test
  (let [suite (assoc minimal-suite :tasks [{:task_id "t1" :task_type "symbol_lookup"
                                            :repo_key "local" :arms ["A"] :prompt "find it"
                                            :ground_truth {}}])
        codes (set (map :code (:violations (bs/validate-suite suite))))]
    (is (contains? codes "task_missing_ground_truth"))))

(deftest validation-rejects-unknown-repo-and-arms-test
  (let [suite (assoc minimal-suite :tasks [{:task_id "t1" :task_type "symbol_lookup"
                                            :repo_key "elsewhere" :arms ["Z"] :prompt "find it"
                                            :ground_truth {:required_paths ["src/a.clj"]}}])
        codes (set (map :code (:violations (bs/validate-suite suite))))]
    (is (contains? codes "task_repo_key_not_declared"))
    (is (contains? codes "task_invalid_arms"))))

(deftest validated-suite-throws-on-invalid-corpus-test
  (is (thrown? clojure.lang.ExceptionInfo
               (bs/validated-suite "fixtures/benchmark/does_not_exist.edn"))))
