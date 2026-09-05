(ns semidx.runtime.launcher-state-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.launcher-state :as launcher-state])
  (:import [java.io StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def desired-runtime
  {:root_path "/work/repo-a"
   :repo_key "repo-a"
   :workspace_key "workspace-a"
   :profile "runtime-http"
   :host "127.0.0.1"
   :port 8787})

(defn- temp-home []
  (.toFile (Files/createTempDirectory "sci-launcher-state-test"
                                      (make-array FileAttribute 0))))

(deftest slot-layout-test
  (let [home (temp-home)]
    (testing "slot directory is scoped by workspace key and profile"
      (is (= "workspace-a-runtime-http"
             (.getName (launcher-state/slot-dir home desired-runtime))))
      (is (= "workspace-a-mcp-http"
             (.getName (launcher-state/slot-dir home (assoc desired-runtime
                                                            :profile "mcp-http"))))))

    (testing "state, lock, and log files live inside the slot directory"
      (is (= "state.edn" (.getName (launcher-state/state-file home desired-runtime))))
      (is (= "start.lock" (.getName (launcher-state/lock-file home desired-runtime))))
      (is (= "runtime.log" (.getName (launcher-state/log-file home desired-runtime))))
      (is (= (.getPath (launcher-state/slot-dir home desired-runtime))
             (.getPath (.getParentFile (launcher-state/state-file home desired-runtime))))))))

(deftest state-roundtrip-test
  (let [home (temp-home)]
    (testing "missing state reads as nil"
      (is (nil? (launcher-state/read-state home desired-runtime))))

    (testing "written state reads back normalized"
      (launcher-state/write-state! home desired-runtime
                                   (assoc desired-runtime
                                          :schema_version "1"
                                          :pid 4242
                                          :owned true
                                          :started_at "2026-08-27T09:00:00Z"
                                          :last_health_at "2026-08-27T09:01:00Z"))
      (let [state (launcher-state/read-state home desired-runtime)]
        (is (= "/work/repo-a" (:root_path state)))
        (is (= "runtime-http" (:profile state)))
        (is (= 8787 (:port state)))
        (is (= 4242 (:pid state)))
        (is (true? (:owned state)))))

    (testing "state file is owner-readable only where POSIX permissions apply"
      (let [file (launcher-state/state-file home desired-runtime)
            permissions (try
                          (Files/getPosixFilePermissions (.toPath file)
                                                         (make-array java.nio.file.LinkOption 0))
                          (catch UnsupportedOperationException _ nil))]
        (when permissions
          (is (= #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
                   java.nio.file.attribute.PosixFilePermission/OWNER_WRITE}
                 (set permissions))))))

    (testing "clearing state removes the file"
      (is (true? (launcher-state/clear-state! home desired-runtime)))
      (is (nil? (launcher-state/read-state home desired-runtime)))
      (is (false? (launcher-state/clear-state! home desired-runtime))))))

(deftest unreadable-state-is-reported-test
  (let [home (temp-home)
        _ (launcher-state/ensure-slot-dir! home desired-runtime)
        file (launcher-state/state-file home desired-runtime)
        _ (spit file "{:root_path \"/work/repo-a\"")
        err (StringWriter.)
        state (binding [*err* err]
                (launcher-state/read-state home desired-runtime))]
    (testing "corrupt state reads as nil"
      (is (nil? state)))
    (testing "corrupt state is surfaced, not swallowed"
      (is (re-find #"launcher_state_unreadable" (str err))))))

(deftest start-lock-test
  (let [home (temp-home)]
    (testing "an uncontended lock is acquired"
      (is (= :acquired (launcher-state/with-start-lock home desired-runtime identity))))

    (testing "a lock held elsewhere is observed as contended"
      (is (= [:acquired :contended]
             (launcher-state/with-start-lock
               home
               desired-runtime
               (fn [outer]
                 [outer
                  (launcher-state/with-start-lock home desired-runtime identity)])))))

    (testing "the lock is released after the body returns"
      (is (= :acquired (launcher-state/with-start-lock home desired-runtime identity))))

    (testing "different slots do not contend"
      (is (= [:acquired :acquired]
             (launcher-state/with-start-lock
               home
               desired-runtime
               (fn [outer]
                 [outer
                  (launcher-state/with-start-lock home
                                                  (assoc desired-runtime :profile "mcp-http")
                                                  identity)])))))))

(deftest launcher-home-override-test
  (testing "the default home stays outside the repository"
    (let [home (launcher-state/launcher-home)]
      (is (io/file home))
      (is (re-find #"semidx" (.getPath home))))))
