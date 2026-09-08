(ns semidx.integration.freshness-regression-test
  "Regression tests for review findings H1-H3 (see
  reports/005_stage_0_1_workspace_freshness_progress_log.md)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- tmp-root [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

;; H1 — a pinned snapshot must be returned exactly, even when the workspace has
;; since changed, and a missing pinned snapshot is an invalid request.
(deftest pinned-snapshot-returns-exact-snapshot-after-change-test
  (let [root (tmp-root "sci-h1-pinned")
        _ (write-file! root "src/foo.clj" "(ns foo)\n(defn a [] 1)\n")
        storage (sci/in-memory-storage)
        index-a (sci/create-index {:root_path root :storage storage :load_latest true})]
    (testing "pinned snapshot is returned exactly despite a content change"
      (write-file! root "src/foo.clj" "(ns foo)\n(defn a [] 2)\n")
      (let [pinned (sci/create-index {:root_path root
                                      :storage storage
                                      :pinned_snapshot_id (:snapshot_id index-a)})]
        (is (= (:snapshot_id index-a) (:snapshot_id pinned)))
        (is (true? (get-in pinned [:index_lifecycle :snapshot_pinned])))
        (is (= "storage_pinned" (get-in pinned [:index_lifecycle :provenance :source])))))
    (testing "missing pinned snapshot is rejected as invalid_request"
      (try
        (sci/create-index {:root_path root
                           :storage storage
                           :pinned_snapshot_id "does-not-exist"})
        (is false "expected invalid_request")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid_request (:type (ex-data e)))))))))

;; H2 — incremental freshness must not index files excluded by :language_policy.
(deftest incremental-update-respects-language-policy-test
  (let [root (tmp-root "sci-h2-policy")
        _ (write-file! root "src/a.clj" "(ns a)\n(defn a [] 1)\n")
        _ (write-file! root "src/b.clj" "(ns b)\n(defn b [] 2)\n")
        _ (write-file! root "src/c.clj" "(ns c)\n(defn c [] 3)\n")
        storage (sci/in-memory-storage)
        policy {:allow_languages ["clojure"]}
        index-a (sci/create-index {:root_path root
                                   :storage storage
                                   :load_latest true
                                   :language_policy policy})]
    (is (= ["clojure"] (:active_languages index-a)))
    (testing "adding an excluded-language file does not pull it into the index"
      (write-file! root "src/intruder.py" "def x():\n    return 1\n")
      (let [after (sci/create-index {:root_path root
                                     :storage storage
                                     :load_latest true
                                     :language_policy policy})]
        (is (not (contains? (:files after) "src/intruder.py")))))))

;; H3 — an incremental update must preserve language activation metadata.
(deftest incremental-update-preserves-activation-metadata-test
  (let [root (tmp-root "sci-h3-activation")
        _ (write-file! root "src/a.clj" "(ns a)\n(defn a [] 1)\n")
        _ (write-file! root "src/b.clj" "(ns b)\n(defn b [] 2)\n")
        _ (write-file! root "src/c.clj" "(ns c)\n(defn c [] 3)\n")
        storage (sci/in-memory-storage)
        index-a (sci/create-index {:root_path root :storage storage :load_latest true})]
    (testing "a single-file change updates incrementally and keeps :active_languages"
      (write-file! root "src/a.clj" "(ns a)\n(defn a [] 11)\n")
      (let [after (sci/create-index {:root_path root :storage storage :load_latest true})]
        (is (= "incremental_update" (get-in after [:index_lifecycle :lifecycle_action])))
        (is (seq (:active_languages after)))
        (is (= (:active_languages index-a) (:active_languages after)))))))

;; bugs/002 — the lifecycle used to resolve its rebuild reason through a
;; whitelist that sent everything it did not recognise to `initial_build`, so a
;; workspace rebuilding on every run because its delta kept exceeding the
;; threshold reported the same reason as a cold start.
(deftest a-rebuild-reports-the-reason-freshness-actually-decided-test
  (let [root (tmp-root "sci-bugs-002-reason")
        storage (sci/in-memory-storage)
        build #(sci/create-index {:root_path root :storage storage :load_latest true
                                  :freshness_delta_rebuild_ratio 0.5})]
    (write-file! root "src/a.clj" "(ns a)\n(defn one [] 1)\n")
    (write-file! root "src/b.clj" "(ns b)\n(defn two [] 2)\n")
    (let [first-build (build)]
      (testing "a genuine cold start still says so"
        (is (= "full_rebuild" (get-in first-build [:index_lifecycle :lifecycle_action])))
        (is (= "initial_build" (get-in first-build [:index_lifecycle :rebuild_reason]))))

      (testing "and a rebuild driven by the delta ratio says that instead"
        ;; Both files change: delta 2 over a threshold of 1.0.
        (write-file! root "src/a.clj" "(ns a)\n(defn one [] 11)\n")
        (write-file! root "src/b.clj" "(ns b)\n(defn two [] 22)\n")
        (let [second-build (build)]
          (is (= "full_rebuild" (get-in second-build [:index_lifecycle :lifecycle_action])))
          (is (= "delta_exceeds_threshold"
                 (get-in second-build [:index_lifecycle :rebuild_reason]))
              "reported as itself, not as initial_build"))))))
