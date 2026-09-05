(ns semidx.runtime.languages.clojure-test
  "Toolchain-degradation behaviour for the Clojure lane.

  The lane is clj-kondo-primary with a regex fallback. `clojure.java.shell/sh`
  throws `IOException` when the binary is absent — it does not return a non-zero
  exit — and that throw used to escape `parse-file`, aborting the whole index
  build instead of degrading. CI reproduced it as 155 failures and 3 errors on a
  runner that never installed clj-kondo."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
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

;; --- reader metadata (2026-09-05) -----------------------------------

(def ^:private metadata-source
  (str/join "\n"
            ["(ns my.app.meta)"
             "(def plain 1)"
             "(def ^:private secret 2)"
             "(def ^:export exported 3)"
             "(def ^{:doc \"x\"} with-map 4)"
             "(defn ^:private priv-fn [x] x)"
             "(defn- dash-priv [x] x)"
             "(defn ^:deprecated dep-fn [x] x)"
             "(defn plain-fn [x] x)"
             "(def ^String typed \"s\")"]))

(def ^:private metadata-symbols
  #{"my.app.meta/plain"
    "my.app.meta/secret"
    "my.app.meta/exported"
    "my.app.meta/with-map"
    "my.app.meta/priv-fn"
    "my.app.meta/dash-priv"
    "my.app.meta/dep-fn"
    "my.app.meta/plain-fn"
    "my.app.meta/typed"})

(defn- with-metadata-file [f]
  (let [dir (java.io.File/createTempFile "semidx-clj-meta" "")]
    (.delete dir)
    (.mkdirs dir)
    (try
      (spit (io/file dir "meta.clj") metadata-source)
      (f (.getPath dir) "meta.clj" (str/split-lines metadata-source))
      (finally
        (doseq [x (reverse (file-seq dir))] (.delete x))))))

(defn- symbols-with [engine]
  (with-metadata-file
    (fn [root path lines]
      (set (map :symbol (:units (clojure-language/parse-file
                                 root path lines {:clojure_engine engine})))))))

(deftest reader-metadata-does-not-become-the-var-name-in-the-regex-lane
  ;; The regex fallback captured the first token after the operator, so
  ;; `(def ^:private secret 2)` was named `^:private`. Worse, every var
  ;; annotated the same way collapsed onto the same name, giving two distinct
  ;; vars ONE unit id. This lane is the fallback, so it runs wherever clj-kondo
  ;; is absent.
  (let [symbols (symbols-with :regex)]
    (is (= metadata-symbols symbols))
    (is (not-any? #(str/includes? % "^") symbols)
        "no metadata marker may survive into a symbol name")))

(deftest reader-metadata-does-not-become-the-var-name-in-the-tree-sitter-lane
  ;; A symbol type hint nests its own sym_name inside the meta_lit that precedes
  ;; the name, so `(def ^String typed "s")` was named `String`. Keyword and map
  ;; metadata were unaffected because they contain no sym_name.
  (if (str/blank? (str (System/getenv "SEMIDX_TREE_SITTER_CLOJURE_GRAMMAR_PATH")))
    (println "no Clojure tree-sitter grammar; skipping the tree-sitter metadata assertion")
    (let [symbols (symbols-with :tree-sitter)]
      (is (= metadata-symbols symbols))
      (is (contains? symbols "my.app.meta/typed")
          "the var is named after itself, not after its type hint")
      (is (not (contains? symbols "my.app.meta/String"))))))

(deftest both-clojure-engines-agree-on-unit-identity
  ;; The two lanes are meant to describe the same code. Elixir's tree-sitter lane
  ;; silently disagreed with its regex lane on every ExUnit test name until CI
  ;; finally exercised it, so assert agreement here directly rather than trusting
  ;; that each lane is separately correct.
  (if (str/blank? (str (System/getenv "SEMIDX_TREE_SITTER_CLOJURE_GRAMMAR_PATH")))
    (println "no Clojure tree-sitter grammar; skipping the engine-parity assertion")
    (is (= (symbols-with :regex) (symbols-with :tree-sitter)))))
