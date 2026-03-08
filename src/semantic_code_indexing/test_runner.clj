(ns semantic-code-indexing.test-runner
  (:require [clojure.test :as t]
            [semantic-code-indexing.runtime-test]))

(defn -main [& _]
  (let [result (t/run-tests 'semantic-code-indexing.runtime-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
