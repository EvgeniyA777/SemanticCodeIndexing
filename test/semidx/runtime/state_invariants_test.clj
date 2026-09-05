(ns semidx.runtime.state-invariants-test
  (:require [clojure.test :refer [deftest is testing]]
            [semidx.runtime.state-invariants :as state-invariants]))

(defn- state-query []
  {:intent {:purpose "change_impact"
            :details "Change disconnect lifecycle state"}
   :targets {:symbols ["example.ConnectionService#disconnect"]}})

(defn- entity-unit [n]
  (let [suffix (format "%02d" n)
        class-name (str "Model" suffix)
        module (str "example.model." class-name)
        path (str "src/example/model/" class-name ".java")]
    {:unit_id (str path "::" module "#setStatus$arity1")
     :path path
     :kind "method"
     :module module
     :symbol (str module "#setStatus")
     :signature "public void setStatus(String status) {"
     :class_name class-name
     :imports []}))

(defn- synthetic-index [selected entities]
  (let [units (into [selected] entities)]
    {:units (into {} (map (juxt :unit_id identity) units))
     :unit_order (mapv :unit_id units)
     :module_index (reduce (fn [index unit]
                             (update index (:module unit) (fnil conj []) (:unit_id unit)))
                           {}
                           units)
     :path_index {}
     :callers_index {}
     :callees_index {}
     :test_target_index {}}))

(deftest assemble-bounds-and-orders-state-invariant-lists-test
  (let [entities (mapv entity-unit (range 15))
        selected {:unit_id "src/example/ConnectionService.java::example.ConnectionService#disconnect$arity1"
                  :path "src/example/ConnectionService.java"
                  :kind "method"
                  :module "example.ConnectionService"
                  :symbol "example.ConnectionService#disconnect"
                  :signature "public void disconnect(Object entity) {"
                  :class_name "ConnectionService"
                  :imports (mapv :module entities)}
        packet (state-invariants/assemble
                (synthetic-index selected entities)
                (state-query)
                [selected]
                [])]
    (testing "the public packet lists are independently bounded"
      (is (= 12 (count (:entity_candidates packet))))
      (is (= 1 (count (:state_writers packet))))
      (is (empty? (:assertion_tests packet)))
      (is (empty? (:fixture_helpers packet))))
    (testing "entity representatives are deterministic and path ordered"
      (is (= (mapv #(str "src/example/model/Model" (format "%02d" %) ".java")
                   (range 12))
             (mapv :path (:entity_candidates packet)))))))

(deftest assemble-prioritizes-corroborated-tests-within-budget-test
  (let [entity (entity-unit 0)
        selected {:unit_id "src/example/ConnectionService.java::example.ConnectionService#disconnect$arity1"
                  :path "src/example/ConnectionService.java"
                  :kind "method"
                  :module "example.ConnectionService"
                  :symbol "example.ConnectionService#disconnect"
                  :signature "public void disconnect(Object entity) {"
                  :class_name "ConnectionService"
                  :imports [(:module entity)]}
        corroborated-test
        {:unit_id "test/example/StrongServiceTest.java::example.StrongServiceTest#preservesState$arity0"
         :path "test/example/StrongServiceTest.java"
         :kind "method"
         :module "example.StrongServiceTest"
         :symbol "example.StrongServiceTest#preservesState"
         :signature "public void preservesState() {"
         :class_name "StrongServiceTest"
         :imports [(:module entity)]}
        related-test-paths (mapv #(str "test/example/Related" % "Test.java")
                                 (range 12))
        packet (state-invariants/assemble
                (synthetic-index selected [entity corroborated-test])
                (state-query)
                [selected]
                related-test-paths)]
    (is (= 12 (count (:assertion_tests packet))))
    (is (= "test/example/StrongServiceTest.java"
           (first (:assertion_tests packet))))
    (is (some #{"test/example/StrongServiceTest.java"}
              (:assertion_tests packet)))))

(deftest assemble-omits-packet-when-no-entity-candidate-exists-test
  (let [selected {:unit_id "src/example/Formatter.java::example.Formatter#updateSummary$arity1"
                  :path "src/example/Formatter.java"
                  :kind "method"
                  :module "example.Formatter"
                  :symbol "example.Formatter#updateSummary"
                  :signature "public void updateSummary(String value) {"
                  :class_name "Formatter"
                  :imports []}
        index (synthetic-index selected [])]
    (is (nil? (state-invariants/assemble index (state-query) [selected] [])))))
