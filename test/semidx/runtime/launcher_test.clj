(ns semidx.runtime.launcher-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.launcher :as launcher]))

(def desired-runtime
  {:root_path "/work/repo-a"
   :repo_key "repo-a"
   :workspace_key "workspace-a"
   :profile "runtime-http"
   :host "127.0.0.1"
   :port 8787})

(def persisted-state
  {:schema_version "1"
   :root_path "/work/repo-a"
   :repo_key "repo-a"
   :workspace_key "workspace-a"
   :profile "runtime-http"
   :host "127.0.0.1"
   :port 8787
   :pid 4242
   :started_at "2026-08-27T09:00:00Z"
   :last_health_at "2026-08-27T09:01:00Z"})

(deftest normalize-profile-test
  (testing "defaults to runtime-http"
    (is (= "runtime-http" (launcher/normalize-profile nil))))

  (testing "accepts keyword and string profiles"
    (is (= "runtime-http" (launcher/normalize-profile :runtime-http)))
    (is (= "mcp-http" (launcher/normalize-profile "mcp-http"))))

  (testing "rejects unsupported profiles"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported runtime launcher profile"
                          (launcher/normalize-profile :stdio)))))

(deftest normalize-runtime-state-test
  (testing "normalizes string, underscore, and keyword fields"
    (is (= {:schema_version "1"
            :root_path "/work/repo-a"
            :repo_key "repo-a"
            :workspace_key "workspace-a"
            :profile "runtime-http"
            :host "127.0.0.1"
            :port 8787
            :pid 4242
            :started_at "2026-08-27T09:00:00Z"
            :last_health_at "2026-08-27T09:01:00Z"}
           (launcher/normalize-runtime-state
            {"schema_version" "1"
             "root_path" "/work/repo-a"
             "repo_key" "repo-a"
             "workspace-key" "workspace-a"
             :profile :runtime-http
             :host "127.0.0.1"
             :port "8787"
             :pid "4242"
             :started_at "2026-08-27T09:00:00Z"
             :last-health-at "2026-08-27T09:01:00Z"})))))

(deftest decide-runtime-reuse-test
  (testing "missing state starts a runtime"
    (is (= :started
           (:decision (launcher/decide-runtime-reuse
                       {:desired desired-runtime})))))

  (testing "healthy matching state is reused"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :health {:healthy true}})]
      (is (= :reused (:decision result)))
      (is (= :healthy_runtime (:reason result)))
      (is (= 4242 (:pid result)))))

  (testing "healthy state with explicit root confirmation is reused"
    (is (= :reused
           (:decision (launcher/decide-runtime-reuse
                       {:desired desired-runtime
                        :state persisted-state
                        :health {:status 200
                                 :json {:status "ok"
                                        :project_context {:root_path "/work/repo-a"}}}})))))

  (testing "root mismatch blocks reuse instead of cleaning another runtime"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state (assoc persisted-state :root_path "/work/repo-b")
                   :health {:healthy true}})]
      (is (= :blocked (:decision result)))
      (is (= :state_root_mismatch (:reason result)))
      (is (= "/work/repo-b" (:state_root_path result)))))

  (testing "healthy endpoint with a different root is blocked"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :health {:healthy true
                            :project_context {:root_path "/work/repo-b"}}})]
      (is (= :blocked (:decision result)))
      (is (= :health_root_mismatch (:reason result)))
      (is (= "/work/repo-b" (:health_root_path result)))))

  (testing "healthy endpoint with a different repo key is blocked"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :health {:healthy true
                            :repo_identity {:repo_key "repo-b"}}})]
      (is (= :blocked (:decision result)))
      (is (= :health_repo_key_mismatch (:reason result)))
      (is (= "repo-b" (:health_repo_key result)))))

  (testing "profile mismatch is stale metadata for the desired profile"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state (assoc persisted-state :profile "mcp-http")
                   :health {:healthy true}})]
      (is (= :stale-cleaned (:decision result)))
      (is (= :profile_mismatch (:reason result)))
      (is (= "mcp-http" (:state_profile result)))))

  (testing "endpoint mismatch is stale metadata for the desired profile"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state (assoc persisted-state :port 8877)
                   :health {:healthy true}})]
      (is (= :stale-cleaned (:decision result)))
      (is (= :endpoint_mismatch (:reason result)))
      (is (= 8877 (:state_port result)))))

  (testing "dead pid is stale metadata"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :process {:pid_alive false}})]
      (is (= :stale-cleaned (:decision result)))
      (is (= :pid_not_alive (:reason result)))))

  (testing "closed port is stale metadata"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :process {:port_open false}})]
      (is (= :stale-cleaned (:decision result)))
      (is (= :port_not_open (:reason result)))))

  (testing "failed health check is stale metadata"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :health {:status "error"}})]
      (is (= :stale-cleaned (:decision result)))
      (is (= :health_check_failed (:reason result)))))

  (testing "lock contention blocks start when no healthy runtime is available"
    (let [result (launcher/decide-runtime-reuse
                  {:desired desired-runtime
                   :state persisted-state
                   :lock :contended})]
      (is (= :blocked (:decision result)))
      (is (= :start_lock_contended (:reason result)))))

  (testing "lock contention does not block reuse of an already healthy runtime"
    (is (= :reused
           (:decision (launcher/decide-runtime-reuse
                       {:desired desired-runtime
                        :state persisted-state
                        :health {:healthy true}
                        :lock :contended}))))))

(deftest health-service-decides-which-profile-answered-test
  (let [mcp-desired (assoc desired-runtime :profile "mcp-http" :port 8791)
        mcp-state (assoc persisted-state :profile "mcp-http" :port 8791)]
    (testing "a matching service is reused"
      (is (= :reused
             (:decision (launcher/decide-runtime-reuse
                         {:desired mcp-desired
                          :state mcp-state
                          :health {:healthy true :service "semidx-mcp-http"}})))))

    (testing "another profile's server on the requested port is not adopted"
      (let [result (launcher/decide-runtime-reuse
                    {:desired mcp-desired
                     :state mcp-state
                     :health {:healthy true :service "semidx-runtime-http"}})]
        (is (= :blocked (:decision result)))
        (is (= :health_service_mismatch (:reason result)))
        (is (= "semidx-runtime-http" (:health_service result)))
        (is (= "semidx-mcp-http" (:expected_service result)))))

    (testing "the check reads the service out of a raw health body too"
      (is (= :health_service_mismatch
             (:reason (launcher/decide-runtime-reuse
                       {:desired desired-runtime
                        :state persisted-state
                        :health {:status 200
                                 :json {:status "ok" :service "semidx-mcp-http"}}})))))

    (testing "a server that reports no service stays adoptable"
      (is (= :reused
             (:decision (launcher/decide-runtime-reuse
                         {:desired mcp-desired
                          :state mcp-state
                          :health {:healthy true}})))))))
