(ns semidx.test-support.lsp-toolchain
  "Shared skip gate for the LSP end-to-end tests (plans/018 Stage 5a).

  Those tests are the only ones that start a real language server. Everything
  else in the overlay boundary runs against an injected fact source and needs no
  toolchain, so a developer without it installed should not get a red suite.

  A silent skip is only safe while some environment actually runs the tests. CI
  installs the toolchain and sets `SEMIDX_REQUIRE_LSP_TOOLCHAINS=1`; under that
  flag an unresolved server fails the test instead of printing a skip, so a
  removed or broken install step cannot leave the suite green over a provider no
  environment has ever exercised. This mirrors the SCIP gate, and it is separate
  from it on purpose: installing a toolchain and requiring it are different
  decisions, and so are the two toolchains."
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]))

(defn required?
  "True when the environment declares that the LSP toolchain must be present."
  []
  (not (str/blank? (str (System/getenv "SEMIDX_REQUIRE_LSP_TOOLCHAINS")))))

(defn unresolved!
  "Record an unresolved toolchain for `what`: a printed skip by default, a failed
  assertion when the environment requires it."
  [toolchain what]
  (if (required?)
    (is false
        (str toolchain " not resolved while SEMIDX_REQUIRE_LSP_TOOLCHAINS is set; "
             what " did not run. Install it with ./scripts/setup-typescript-lsp.sh,"
             " or unset the variable to allow the skip."))
    (println (str toolchain " not resolved; skipping " what))))
