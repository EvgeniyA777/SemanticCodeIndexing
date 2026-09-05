(ns semidx.test-runner-test
  (:require [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]
            [semidx.runtime.grpc-prep :as grpc-prep]
            [semidx.test-runner :as test-runner]))

(defn- thrown-ex-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))

(deftest selected-test-namespaces-defaults-to-discovery-test
  (with-redefs [test-runner/discover-test-namespaces
                (constantly ['semidx.alpha-test 'semidx.beta-test])]
    (is (= ['semidx.alpha-test 'semidx.beta-test]
           (test-runner/selected-test-namespaces [])))))

(deftest selected-test-namespaces-supports-short-selector-test
  (is (= ['semidx.runtime.capabilities-test]
         (test-runner/selected-test-namespaces
          ["-n" "semidx.runtime.capabilities-test"]))))

(deftest selected-test-namespaces-supports-long-selector-test
  (is (= ['semidx.runtime.http-test]
         (test-runner/selected-test-namespaces
          ["--namespace" "semidx.runtime.http-test"]))))

(deftest selected-test-namespaces-preserves-repeated-selector-order-test
  (is (= ['semidx.runtime.capabilities-test
          'semidx.runtime.http-test]
         (test-runner/selected-test-namespaces
          ["-n" "semidx.runtime.capabilities-test"
           "--namespace" "semidx.runtime.http-test"]))))

(deftest selected-test-namespaces-rejects-unknown-args-test
  (is (= {:type :invalid_test_runner_args
          :argument "--bad"
          :reason :unknown_argument}
         (thrown-ex-data
          #(test-runner/selected-test-namespaces ["--bad"])))))

(deftest selected-test-namespaces-rejects-missing-values-test
  (testing "missing terminal value"
    (is (= {:type :invalid_test_runner_args
            :argument "-n"
            :reason :missing_namespace_value}
           (thrown-ex-data
            #(test-runner/selected-test-namespaces ["-n"])))))
  (testing "next selector is not accepted as a namespace value"
    (is (= {:type :invalid_test_runner_args
            :argument "-n"
            :reason :missing_namespace_value}
           (thrown-ex-data
            #(test-runner/selected-test-namespaces
              ["-n" "--namespace" "semidx.runtime.http-test"]))))))

(deftest run-prepares-generated-grpc-classes-before-loading-tests-test
  (let [prep-calls (atom 0)]
    (with-redefs [grpc-prep/ensure-grpc-classes! #(swap! prep-calls inc)
                  t/run-tests (fn [& _]
                                {:test 0 :pass 0 :fail 0 :error 0})]
      (is (= {:test 0 :pass 0 :fail 0 :error 0}
             (test-runner/run [])))
      (is (= 1 @prep-calls)))))

(deftest committed-generated-grpc-classes-are-current-test
  (is (= :current (:status (grpc-prep/ensure-grpc-classes!))))
  (is (true? (grpc-prep/grpc-classes-current?))))

(deftest stale-generated-grpc-output-is-rebuilt-test
  (let [marker (io/file "target/classes/.semidx-grpc-compiled")
        stale-class (io/file "target/classes/semidx/runtime/grpc/v1/StaleGenerated.class")]
    (try
      (io/make-parents stale-class)
      (spit stale-class "stale")
      (spit marker "stale-manifest")
      (is (= :compiled (:status (grpc-prep/ensure-grpc-classes!))))
      (is (false? (.exists stale-class)))
      (is (true? (grpc-prep/grpc-classes-current?)))
      (finally
        (when (.exists stale-class)
          (io/delete-file stale-class true))
        (when-not (grpc-prep/grpc-classes-current?)
          (grpc-prep/ensure-grpc-classes!))))))
