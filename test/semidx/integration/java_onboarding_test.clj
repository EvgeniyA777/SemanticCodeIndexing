(ns semidx.integration.java-onboarding-test
  "plans/023: every language lane carries the same onboarding artifacts. This is
  Java's end-to-end regression test — the lane had none before, which is why
  the onboarding checklist failed for it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- temp-root [label]
  (str (java.nio.file.Files/createTempDirectory
        label (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- resolve-target [index symbol* path]
  (sci/resolve-context-detail index
                              {:api_version "1.0"
                               :schema_version "1.0"
                               :intent {:purpose "code_understanding"
                                        :details (str "Locate " symbol* ".")}
                               :targets {:symbols [symbol*] :paths [path]}
                               :constraints {:token_budget 1000
                                             :max_raw_code_level "enclosing_unit"
                                             :freshness "current_snapshot"}
                               :hints {:prefer_definitions_over_callers true}
                               :options {:include_tests false
                                         :include_impact_hints true
                                         :allow_raw_code_escalation false}
                               :trace {:trace_id "bbbbbbb2-2222-4222-8222-bbbbbbbbbbbb"
                                       :request_id "java-onboarding-test-001"
                                       :actor_id "test_runner"}}))

(defn- selected-symbols [result]
  (set (map :symbol (get-in result [:context_packet :relevant_units]))))

(deftest java-adapter-onboarding-regression-test
  (let [root (temp-root "sci-java-onboarding-test")
        _ (write-file! root "src/example/OrderService.java"
                       "package example;\n\npublic class OrderService {\n  public String handle(String order) {\n    return normalize(order);\n  }\n\n  public String normalize(String value) {\n    return value.trim();\n  }\n}\n")
        index (sci/create-index {:root_path root})
        result (resolve-target index "example.OrderService#handle" "src/example/OrderService.java")]

    (testing "the lane indexes its own files as itself"
      (is (= "java" (get-in index [:files "src/example/OrderService.java" :language])))
      (is (= "full" (get-in index [:files "src/example/OrderService.java" :parser_mode])))
      (is (= "example" (get-in index [:files "src/example/OrderService.java" :module]))))

    (testing "methods are package-and-class-qualified units"
      (let [symbols (set (map :symbol (vals (:units index))))]
        (is (contains? symbols "example.OrderService#handle"))
        (is (contains? symbols "example.OrderService#normalize"))))

    (testing "the unit id carries the arity, which is what keeps overloads apart
              in a lane whose evidence is arity-only"
      (is (some #(re-find #"\$arity1" (str (:unit_id %))) (vals (:units index)))))

    (testing "and retrieval returns the requested method"
      (is (contains? (selected-symbols result) "example.OrderService#handle")))))

(deftest java-class-modifiers-do-not-hide-the-owning-class-test
  (testing "a class declared with any legal modifier combination still owns its
            methods: accepting only `public` sent every method of a final,
            abstract, or package-private class to `UnknownClass`, which is wrong
            in the two places it matters most — the unit's module and the
            canonical fact key built from its symbol"
    (let [root (temp-root "sci-java-modifiers-test")
          _ (write-file! root "src/example/Normalizer.java"
                         "package example;\n\npublic final class Normalizer {\n  public static String normalize(String raw) {\n    return raw.trim();\n  }\n}\n")
          _ (write-file! root "src/example/Base.java"
                         "package example;\n\npublic abstract class Base {\n  public String describe() {\n    return \"base\";\n  }\n}\n")
          _ (write-file! root "src/example/Hidden.java"
                         "package example;\n\nfinal class Hidden {\n  public String value() {\n    return \"hidden\";\n  }\n}\n")
          index (sci/create-index {:root_path root})
          symbols (set (map :symbol (vals (:units index))))]
      (is (contains? symbols "example.Normalizer#normalize"))
      (is (contains? symbols "example.Base#describe"))
      (is (contains? symbols "example.Hidden#value"))
      (is (not-any? #(re-find #"UnknownClass" (str (:symbol %))) (vals (:units index)))
          "no unit may be attributed to a class name the parser invented"))))
