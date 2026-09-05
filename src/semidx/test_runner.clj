(ns semidx.test-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [clojure.tools.namespace.find :as ns-find]
            [semidx.runtime.grpc-prep :as grpc-prep]))

(defn discover-test-namespaces
  "Discover every `*-test` namespace under the `test` directory so new tests need
  no manual registration. Sorted for a deterministic default order."
  []
  (->> (ns-find/find-namespaces-in-dir (io/file "test"))
       (filter #(str/ends-with? (name %) "-test"))
       distinct
       sort
       vec))

(defn- missing-namespace-value [arg]
  (throw (ex-info (str arg " requires a namespace value")
                  {:type :invalid_test_runner_args
                   :argument arg
                   :reason :missing_namespace_value})))

(defn- parse-test-runner-args [args]
  (loop [remaining (seq args)
         namespaces []]
    (if-not remaining
      {:namespaces (not-empty namespaces)}
      (let [arg (first remaining)]
        (case arg
          ("-n" "--namespace")
          (let [value (second remaining)]
            (if (or (nil? value) (str/starts-with? value "-"))
              (missing-namespace-value arg)
              (recur (nnext remaining) (conj namespaces (symbol value)))))

          (throw (ex-info (str "Unknown test runner argument: " arg)
                          {:type :invalid_test_runner_args
                           :argument arg
                           :reason :unknown_argument})))))))

(defn selected-test-namespaces [args]
  (or (:namespaces (parse-test-runner-args args))
      (discover-test-namespaces)))

(defn run [namespaces]
  (grpc-prep/ensure-grpc-classes!)
  (run! require namespaces)
  (apply t/run-tests namespaces))

(defn -main [& args]
  (try
    (let [parsed (parse-test-runner-args args)
          namespaces (or (:namespaces parsed) (discover-test-namespaces))]
      (if (:namespaces parsed)
        (println "Selected" (count namespaces) "test namespace(s)")
        (println "Discovered" (count namespaces) "test namespaces"))
      (let [result (run namespaces)
            failures (+ (:fail result) (:error result))]
        (System/exit (if (zero? failures) 0 1))))
    (catch clojure.lang.ExceptionInfo ex
      (binding [*out* *err*]
        (println (.getMessage ex)))
      (System/exit 2))))
