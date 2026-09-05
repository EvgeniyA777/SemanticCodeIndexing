(ns semidx.integration.zig-onboarding-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]))

(defn- write-file! [root rel-path content]
  (let [file (io/file root rel-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- tmp-root [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- units-for-path [index path]
  (->> (:unit_order index)
       (map #(get (:units index) %))
       (filter #(= path (:path %)))
       vec))

(defn- provider-for [index path]
  (some->> (get-in index [:workspace_state :files])
           (filter #(= path (:path %)))
           first
           :provider_id))

(deftest zig-adapter-onboarding-regression-test
  (let [root (tmp-root "sci-zig-onboarding")
        _ (write-file! root "src/helpers.zig"
                       "pub fn normalize(value: []const u8) []const u8 {\n    return value;\n}\n")
        _ (write-file! root "src/main.zig"
                       "const helpers = @import(\"helpers.zig\");\n\npub fn run(value: []const u8) []const u8 {\n    return helpers.normalize(value);\n}\n\npub const Runner = struct {\n    pub fn execute(self: *Runner, value: []const u8) []const u8 {\n        _ = self;\n        return run(value);\n    }\n};\n")
        _ (write-file! root "test/main_test.zig"
                       "const main = @import(\"../src/main.zig\");\n\ntest \"run normalizes input\" {\n    _ = main.run(\"A-1\");\n}\n")
        storage (sci/in-memory-storage)
        index (sci/create-index {:root_path root :storage storage})
        main-units (units-for-path index "src/main.zig")
        test-units (units-for-path index "test/main_test.zig")
        helper-id (some->> (units-for-path index "src/helpers.zig")
                           (filter #(= "src.helpers/normalize" (:symbol %)))
                           first
                           :unit_id)
        run-id (some->> main-units
                        (filter #(= "src.main/run" (:symbol %)))
                        first
                        :unit_id)
        helper-callers (sci/query-callers storage root helper-id {:limit 20})
        run-callers (sci/query-callers storage root run-id {:limit 20})
        result (sci/resolve-context-detail
                index
                {:api_version "1.0"
                 :schema_version "1.0"
                 :intent {:purpose "code_understanding"
                          :details "Locate the Zig authority implementation for src.main/run."}
                 :targets {:symbols ["src.main/run"]
                           :paths ["src/main.zig"]}
                 :constraints {:token_budget 1200
                               :language_allowlist ["zig"]
                               :max_raw_code_level "enclosing_unit"
                               :freshness "current_snapshot"}
                 :hints {:prefer_definitions_over_callers true}
                 :options {:include_tests false
                           :include_impact_hints true
                           :allow_raw_code_escalation false}
                 :trace {:trace_id "48484848-4848-4484-8484-484848484848"
                         :request_id "zig-onboarding-test-001"
                         :actor_id "test_runner"}})]
    (testing "Zig is detected, activated, parsed, and provider-tagged"
      (is (= "zig" (get-in index [:files "src/main.zig" :language])))
      (is (= "full" (get-in index [:files "src/main.zig" :parser_mode])))
      (is (some #{"zig"} (:detected_languages index)))
      (is (some #{"zig"} (:active_languages index)))
      (is (= "zig-zls" (provider-for index "src/main.zig")))
      (is (contains? #{"zig_zls_active" "zig_zls_unavailable" "zig_zls_fallback"}
                     (get-in index [:files "src/main.zig" :diagnostics 0 :code]))))
    (testing "functions, container methods, imports, and tests become stable units"
      (is (some #(= "src.main/run" (:symbol %)) main-units))
      (is (some #(= "src.main.Runner#execute" (:symbol %)) main-units))
      (is (= ["src.helpers"] (get-in index [:files "src/main.zig" :imports])))
      (is (= ["src.main"] (get-in index [:files "test/main_test.zig" :imports])))
      (is (some #(= "test" (:kind %)) test-units)))
    (testing "imported and test calls link to their authority functions"
      (is helper-id)
      (is run-id)
      (is (some #(= "src.main/run" (:symbol %)) helper-callers))
      (is (some #(= "src.main.Runner#execute" (:symbol %)) run-callers))
      (is (some #(= "test/main_test.zig" (:path %)) run-callers)))
    (testing "exact Zig symbol targeting remains available under the low confidence ceiling"
      (is (= "low" (get-in result [:context_packet :confidence :level])))
      (is (some #(= "src.main/run" (:symbol %))
                (get-in result [:context_packet :relevant_units]))))))

(deftest zig-language-policy-regression-test
  (let [root (tmp-root "sci-zig-policy")
        _ (write-file! root "src/app.clj" "(ns app)\n(defn run [] :ok)\n")
        _ (write-file! root "src/main.zig" "pub fn run() void {}\n")
        index (sci/create-index {:root_path root
                                 :language_policy {:allow_languages ["clojure"]}})]
    (is (= ["clojure"] (:active_languages index)))
    (is (contains? (:files index) "src/app.clj"))
    (is (not (contains? (:files index) "src/main.zig")))))
