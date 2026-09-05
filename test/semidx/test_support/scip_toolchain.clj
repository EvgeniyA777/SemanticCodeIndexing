(ns semidx.test-support.scip-toolchain
  "Shared skip gate for the SCIP end-to-end tests (plans/018 Stages 3 and 4).

  Those tests are the only ones that invoke a real indexer: the repo-managed
  `scip-typescript` CLI, or the repo-managed `javac` + `semanticdb-javac` +
  `scip-semanticdb` pipeline. Everything else in the SCIP adapters runs against
  the committed `.scip` fixtures and needs no toolchain. A developer without the
  toolchains installed should not get a red suite, so the end-to-end tests skip
  when resolution fails.

  A silent skip is only safe while some environment actually runs the tests.
  CI installs both toolchains and sets `SEMIDX_REQUIRE_SCIP_TOOLCHAINS=1`; under
  that flag an unresolved toolchain fails the test instead of printing a skip, so
  a removed or broken install step cannot leave the suite green over provider
  adapters that no environment has ever exercised."
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]))

(defn required?
  "True when the environment declares that the SCIP toolchains must be present."
  []
  (not (str/blank? (str (System/getenv "SEMIDX_REQUIRE_SCIP_TOOLCHAINS")))))

(defn unresolved!
  "Record an unresolved toolchain for `what`: a printed skip by default, a failed
  assertion when the environment requires the toolchains."
  [toolchain what]
  (if (required?)
    (is false
        (str toolchain " not resolved while SEMIDX_REQUIRE_SCIP_TOOLCHAINS is"
             " set; " what " did not run. Install it with"
             " ./scripts/setup-scip-typescript.sh or ./scripts/setup-scip-java.sh,"
             " or unset the variable to allow the skip."))
    (println (str toolchain " not resolved; skipping " what))))
