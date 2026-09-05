(ns semidx.runtime.zig-language-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.languages.zig :as zig]))

(def ^:private sample-lines
  ["const helpers = @import(\"helpers.zig\");"
   "pub fn run("
   "    value: []const u8,"
   ") []const u8 {"
   "    return helpers.normalize(value);"
   "}"
   ""
   "pub const Runner = struct {"
   "    pub fn execute(self: *Runner, value: []const u8) []const u8 {"
   "        _ = self;"
   "        return run(value);"
   "    }"
   "};"])

(def ^:private sample-document-symbols
  [{:name "run"
    :detail "fn run(\n    value: []const u8,\n) []const u8"
    :kind 12
    :range {:start {:line 1 :character 0}
            :end {:line 5 :character 1}}
    :selectionRange {:start {:line 1 :character 7}
                     :end {:line 1 :character 10}}
    :children []}
   {:name "Runner"
    :kind 14
    :range {:start {:line 7 :character 0}
            :end {:line 12 :character 2}}
    :selectionRange {:start {:line 7 :character 10}
                     :end {:line 7 :character 16}}
    :children [{:name "execute"
                :detail "fn execute(self: *Runner, value: []const u8) []const u8"
                :kind 12
                :range {:start {:line 8 :character 4}
                        :end {:line 11 :character 5}}
                :selectionRange {:start {:line 8 :character 11}
                                 :end {:line 8 :character 18}}
                :children []}]}])

(deftest zls-document-symbols-are-primary-definition-facts-test
  (let [request (atom nil)
        parsed (zig/parse-file
                "/workspace"
                "src/main.zig"
                sample-lines
                {:zig_lsp_fact_source
                 (fn [fact-request]
                   (reset! request fact-request)
                   {:symbols sample-document-symbols})})
        units (:units parsed)
        run-unit (some #(when (= "src.main/run" (:symbol %)) %) units)
        execute-unit (some #(when (= "src.main.Runner#execute" (:symbol %)) %) units)]
    (testing "the fact source receives the exact in-memory document"
      (is (= (clojure.string/join "\n" sample-lines) (:text @request)))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:content_digest @request))))
    (testing "ZLS ranges handle multiline functions and container ownership"
      (is (= [2 6] ((juxt :start_line :end_line) run-unit)))
      (is (= "function" (:kind run-unit)))
      (is (= "method" (:kind execute-unit)))
      (is (= "zig-zls" (:semantic_provider execute-unit))))
    (testing "regex remains supplemental for imports and call extraction"
      (is (= ["src.helpers"] (:imports parsed)))
      (is (some #{"src.helpers/normalize"} (:calls run-unit)))
      (is (some #{"src.main/run"} (:calls execute-unit))))
    (is (= "zig_zls_active" (get-in parsed [:diagnostics 0 :code])))))

(deftest zls-failure-degrades-to-bounded-regex-test
  (let [parsed (zig/parse-file
                "/workspace"
                "src/main.zig"
                sample-lines
                {:zig_lsp_fact_source
                 (fn [_]
                   (throw (ex-info "mock ZLS failure" {})))})]
    (is (some #(= "src.main.Runner#execute" (:symbol %)) (:units parsed)))
    (is (= "zig_zls_fallback" (get-in parsed [:diagnostics 0 :code])))
    (is (= "full" (:parser_mode parsed)))))

(deftest empty-zls-definition-result-does-not-erase-regex-visible-units-test
  (let [parsed (zig/parse-file
                "/workspace"
                "src/main.zig"
                sample-lines
                {:zig_lsp_fact_source (constantly {:symbols []})})]
    (is (some #(= "src.main/run" (:symbol %)) (:units parsed)))
    (is (= "zig_zls_fallback" (get-in parsed [:diagnostics 0 :code])))
    (is (= "clojure.lang.ExceptionInfo"
           (get-in parsed [:diagnostics 0 :error_class]))
        "fallback retains diagnostic detail without treating empty ZLS facts as success")))

(deftest missing-zls-process-degrades-once-at-parser-context-boundary-test
  (let [parsed (zig/with-parser-context
                "."
                ["src/main.zig"]
                {:zls_command "/definitely/missing/semidx-zls"}
                (fn [parser-opts]
                  (zig/parse-file "." "src/main.zig" sample-lines parser-opts)))]
    (is (some #(= "src.main/run" (:symbol %)) (:units parsed)))
    (is (= "zig_zls_unavailable" (get-in parsed [:diagnostics 0 :code])))
    (is (= "java.io.IOException"
           (get-in parsed [:diagnostics 0 :reason :class])))))
