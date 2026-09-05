(ns semidx.runtime.rate-limit-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.rate-limit :as rate-limit]))

(deftest limiter-is-default-off
  (let [limiter (rate-limit/limiter nil)]
    (is (false? (rate-limit/enabled? limiter)))
    (is (every? :allowed?
                (repeatedly 5 #(rate-limit/check! limiter {}))))))

(deftest limiter-is-scoped-by-tenant-and-actor-and-resets
  (let [now-ms (atom 0)
        limiter (rate-limit/limiter {:requests_per_window 2
                                     :window_ms 1000}
                                    #(deref now-ms))]
    (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant-a"
                                               :actor_id "actor-a"})))
    (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant-a"
                                               :actor_id "actor-a"})))
    (let [denied (rate-limit/check! limiter {:tenant_id "tenant-a"
                                             :actor_id "actor-a"})]
      (is (false? (:allowed? denied)))
      (is (= 1 (:retry_after_seconds denied))))
    (testing "a different actor receives an independent bucket"
      (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant-a"
                                                 :actor_id "actor-b"}))))
    (testing "the original bucket resets after its window"
      (reset! now-ms 1000)
      (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant-a"
                                                 :actor_id "actor-a"}))))))

(deftest limiter-supports-tenant-wide-scope
  (let [limiter (rate-limit/limiter {:requests_per_window 1
                                     :window_ms 60000
                                     :subject_scope "tenant"}
                                    (constantly 0))]
    (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant-a"
                                               :actor_id "actor-a"})))
    (is (false? (:allowed?
                 (rate-limit/check! limiter {:tenant_id "tenant-a"
                                             :actor_id "actor-b"}))))
    (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant-b"
                                               :actor_id "actor-a"})))))

(deftest limiter-state-is-bounded
  (let [limiter (rate-limit/limiter {:requests_per_window 1
                                     :window_ms 60000
                                     :max_subjects 2}
                                    (constantly 0))]
    (doseq [actor ["actor-a" "actor-b" "actor-c"]]
      (is (:allowed? (rate-limit/check! limiter {:tenant_id "tenant"
                                                 :actor_id actor}))))
    (is (= 2 (count @(:state limiter))))))

(deftest limiter-rejects-invalid-enabled-config
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requests_per_window must be a positive integer"
                        (rate-limit/limiter {:requests_per_window 0})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"window_ms must be a positive integer"
                        (rate-limit/limiter {:requests_per_window 1
                                             :window_ms "invalid"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"subject_scope must be tenant or tenant_actor"
                        (rate-limit/limiter {:requests_per_window 1
                                             :subject_scope "actor"}))))
