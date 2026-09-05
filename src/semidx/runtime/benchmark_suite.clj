(ns semidx.runtime.benchmark-suite
  "Task-suite definition layer for the retrieval value benchmark
   (plans/020 Stage 2).

   The suite is a frozen experimental input: task ground truth decides whether
   an attempt succeeded, so a suite that silently drifts would invalidate a
   scored run. `validate-suite` enforces the preregistered corpus invariants
   (external repository present, negative-utility calibration cases present,
   task-type spread) before a run may start."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-suite-path
  "fixtures/benchmark/task_suite_v1.edn")

(def required-negative-utility-task-ids
  "Calibration cases required by plans/020 and reports/023 section 2.5."
  ["zig_api_surface_signatures_v1"
   "zig_struct_config_fields_v1"
   "zig_controlled_runtime_blast_radius_v1"
   "stale_snapshot_after_edit_v1"])

(def valid-arms #{"A" "B" "C" "D"})

(def minimum-task-types
  "Minimum number of distinct task types the corpus must span so the suite is
   not silently narrowed to the cases semidx is expected to win."
  5)

(defn load-suite
  "Read a task suite from EDN. Defaults to the committed v1 suite."
  ([] (load-suite default-suite-path))
  ([path]
   (let [file (io/file (str path))]
     (when-not (.exists file)
       (throw (ex-info "Benchmark task suite not found"
                       {:error_code "benchmark_suite_not_found" :path (str path)})))
     (-> file slurp edn/read-string (assoc :suite_path (str path))))))

(defn repositories-by-key [suite]
  (into {} (map (juxt :repo_key identity) (:repositories suite))))

(defn tasks-by-id [suite]
  (into {} (map (juxt :task_id identity) (:tasks suite))))

(defn task
  "Look up one task definition by id."
  [suite task-id]
  (get (tasks-by-id suite) task-id))

(defn workspace-path
  "Resolve the on-disk workspace path of a repository.

   The environment override exists so the suite can be run on a checkout that
   does not live at the path recorded in the committed fixture."
  [repository]
  (let [env-name (:workspace_path_env repository)
        from-env (when (seq (str env-name)) (System/getenv (str env-name)))]
    (if (str/blank? (str from-env))
      (:default_workspace_path repository)
      from-env)))

(defn repository
  "Repository entry for a task, with `:workspace_path` resolved."
  [suite repo-key]
  (when-let [entry (get (repositories-by-key suite) repo-key)]
    (assoc entry :workspace_path (workspace-path entry))))

(defn- ground-truth-present? [task]
  (let [gt (:ground_truth task)]
    (boolean (or (seq (:required_paths gt))
                 (seq (:required_symbols gt))
                 (seq (:required_facts gt))))))

(defn- task-violations [suite task]
  (let [repo-keys (set (keys (repositories-by-key suite)))
        task-id (:task_id task)
        arms (set (:arms task))]
    (cond-> []
      (str/blank? (str task-id))
      (conj {:code "task_missing_id" :task_id task-id})

      (not (contains? repo-keys (:repo_key task)))
      (conj {:code "task_repo_key_not_declared" :task_id task-id :repo_key (:repo_key task)})

      (str/blank? (str (:task_type task)))
      (conj {:code "task_missing_task_type" :task_id task-id})

      (str/blank? (str (:prompt task)))
      (conj {:code "task_missing_prompt" :task_id task-id})

      (or (empty? arms) (seq (remove valid-arms arms)))
      (conj {:code "task_invalid_arms" :task_id task-id :arms (:arms task)})

      (not (ground-truth-present? task))
      (conj {:code "task_missing_ground_truth" :task_id task-id}))))

(defn- negative-utility-violations [suite]
  (let [by-id (tasks-by-id suite)]
    (reduce
     (fn [acc task-id]
       (let [task (get by-id task-id)
             nu (:negative_utility task)]
         (cond
           (nil? task)
           (conj acc {:code "missing_negative_utility_case" :task_id task-id})

           (not (and (seq (str (:expected_failure_mode nu)))
                     (seq (str (:comparator_requirement nu)))
                     (seq (str (:scoring_rule nu)))))
           (conj acc {:code "incomplete_negative_utility_case" :task_id task-id})

           :else acc)))
     []
     required-negative-utility-task-ids)))

(defn- freshness-violations [suite]
  (let [task (task suite "stale_snapshot_after_edit_v1")]
    (cond-> []
      (and task (nil? (:workspace_mutation task)))
      (conj {:code "stale_snapshot_case_missing_mutation" :task_id (:task_id task)})

      (and task (nil? (:freshness_check task)))
      (conj {:code "stale_snapshot_case_missing_freshness_check" :task_id (:task_id task)}))))

(defn- external-repo-violations [suite]
  (let [repos (repositories-by-key suite)
        used-external (->> (:tasks suite)
                           (keep (fn [task] (get repos (:repo_key task))))
                           (filter :external)
                           seq)]
    (if used-external
      []
      [{:code "no_external_repository_in_corpus"}])))

(defn- duplicate-task-id-violations [suite]
  (->> (:tasks suite)
       (map :task_id)
       frequencies
       (keep (fn [[task-id n]]
               (when (> n 1) {:code "duplicate_task_id" :task_id task-id :count n})))
       vec))

(defn- task-type-spread-violations [suite]
  (let [types (set (map :task_type (:tasks suite)))]
    (if (< (count types) minimum-task-types)
      [{:code "insufficient_task_type_spread"
        :task_types (vec (sort types))
        :minimum minimum-task-types}]
      [])))

(defn validate-suite
  "Validate corpus invariants. Returns `{:valid? bool :violations [...]}`."
  [suite]
  (let [violations (vec (concat
                         (when (str/blank? (str (:suite_version suite)))
                           [{:code "suite_missing_version"}])
                         (when (empty? (:tasks suite))
                           [{:code "suite_has_no_tasks"}])
                         (when (empty? (:repositories suite))
                           [{:code "suite_has_no_repositories"}])
                         (duplicate-task-id-violations suite)
                         (mapcat (partial task-violations suite) (:tasks suite))
                         (negative-utility-violations suite)
                         (freshness-violations suite)
                         (external-repo-violations suite)
                         (task-type-spread-violations suite)))]
    {:valid? (empty? violations)
     :violations violations
     :suite_version (:suite_version suite)
     :task_count (count (:tasks suite))
     :task_types (vec (sort (set (map :task_type (:tasks suite)))))}))

(defn validated-suite
  "Load and validate a suite, throwing when the corpus invariants fail."
  ([] (validated-suite default-suite-path))
  ([path]
   (let [suite (load-suite path)
         report (validate-suite suite)]
     (when-not (:valid? report)
       (throw (ex-info "Benchmark task suite is invalid"
                       {:error_code "benchmark_suite_invalid"
                        :path (str path)
                        :violations (:violations report)})))
     suite)))
