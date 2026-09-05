(ns semidx.runtime.languages.clojure-test
  "Toolchain-degradation behaviour for the Clojure lane.

  The lane is clj-kondo-primary with a regex fallback. `clojure.java.shell/sh`
  throws `IOException` when the binary is absent — it does not return a non-zero
  exit — and that throw used to escape `parse-file`, aborting the whole index
  build instead of degrading. CI reproduced it as 155 failures and 3 errors on a
  runner that never installed clj-kondo."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.languages.clojure :as clojure-language]))

(def ^:private source-path "src/semidx/core.clj")

(defn- source-lines []
  (str/split-lines (slurp source-path)))

(defn- parse [] (clojure-language/parse-file "." source-path (source-lines) {}))

(defn- diagnostic-codes [parsed]
  (set (map :code (:diagnostics parsed))))

(deftest an-absent-clj-kondo-degrades-instead-of-throwing
  (let [parsed (with-redefs [sh/sh (fn [& _]
                                     (throw (java.io.IOException.
                                             "Cannot run program \"clj-kondo\": error=2, No such file or directory")))]
                 (parse))]
    (testing "the parse completes rather than aborting the index build"
      (is (map? parsed))
      (is (seq (:units parsed))
          "the regex fallback still extracts units"))

    (testing "the degradation is explicit, never silent"
      (is (= "fallback" (:parser_mode parsed))
          "ADR-046: lexical output must not be reported under the full parser mode")
      (is (contains? (diagnostic-codes parsed) "kondo_unavailable"))
      (is (contains? (diagnostic-codes parsed) "kondo_stderr")
          "the underlying reason is surfaced, not swallowed"))))

(deftest an-absent-clj-kondo-is-not-reported-as-a-full-parse
  ;; Regression for the ordering bug this fix exposed: when clj-kondo yields
  ;; nothing, the fallback's units are still folded in as `supplemental-units`
  ;; and stamped "full", so a `(seq units)` check would have reported lexical
  ;; output as a full parse. The unavailable branch has to be tested first.
  (let [parsed (with-redefs [sh/sh (fn [& _]
                                     (throw (java.io.IOException. "no clj-kondo here")))]
                 (parse))]
    (is (not= "full" (:parser_mode parsed)))
    (is (every? #(not= "full" (:parser_mode %))
                (filter :parser_mode (:units parsed)))
        "no individual unit may claim a full parse either")))

(deftest a-present-clj-kondo-still-produces-a-full-parse
  ;; Guards the fix against over-correcting: with the real toolchain the lane
  ;; must be unchanged. Skipped where clj-kondo is genuinely unavailable, which
  ;; is exactly the environment the other tests simulate.
  (let [available? (try
                     (zero? (int (:exit (sh/sh "clj-kondo" "--version"))))
                     (catch Exception _ false))]
    (if available?
      (let [parsed (parse)]
        (is (= "full" (:parser_mode parsed)))
        (is (seq (:units parsed)))
        (is (not (contains? (diagnostic-codes parsed) "kondo_unavailable"))))
      (println "clj-kondo not installed; skipping the full-parse assertion"))))
