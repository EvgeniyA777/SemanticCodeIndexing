(ns semantic-code-indexing.test-runner
  (:require [clojure.test :as t]
            [semantic-code-indexing.evaluation-test]
            [semantic-code-indexing.mcp-server-test]
            [semantic-code-indexing.runtime-grpc-test]
            [semantic-code-indexing.runtime-http-test]
            [semantic-code-indexing.typescript-onboarding-test]
            [semantic-code-indexing.runtime-test]
            [semantic-code-indexing.usage-metrics-test]))

(defn -main [& _]
  (let [result (t/run-tests 'semantic-code-indexing.mcp-server-test
                            'semantic-code-indexing.evaluation-test
                            'semantic-code-indexing.runtime-test
                            'semantic-code-indexing.runtime-grpc-test
                            'semantic-code-indexing.runtime-http-test
                            'semantic-code-indexing.typescript-onboarding-test
                            'semantic-code-indexing.usage-metrics-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
