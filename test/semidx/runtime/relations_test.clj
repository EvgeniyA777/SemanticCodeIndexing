(ns semidx.runtime.relations-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.core :as sci]
            [semidx.runtime.relations :as relations]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- sample-root! []
  (let [root (str (java.nio.file.Files/createTempDirectory "semidx-relations" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (write-file! root "src/my/app/core.clj" "(ns my.app.core)\n(defn run [] :ok)\n")
    root))

(deftest normalize-relation-adds-deterministic-identity-and-defaults-test
  (let [relation {:source_unit_id " unit:source "
                  :target_unit_ids ["unit:target" "unit:target" nil ""]
                  :relation_type " dataflow/returns-call-result "
                  :evidence_quality "high"
                  :provenance {:producer "test"}}
        relation-a (assoc relation :provenance (array-map :z 2 :a (array-map :b 1 :a 0)))
        relation-b (assoc relation :provenance (array-map :a (array-map :a 0 :b 1) :z 2))
        normalized (relations/normalize-relation relation)
        normalized-again (relations/normalize-relation relation)
        normalized-a (relations/normalize-relation relation-a)
        normalized-b (relations/normalize-relation relation-b)]
    (is (= (:relation_id normalized) (:relation_id normalized-again)))
    (is (= (:relation_id normalized-a) (:relation_id normalized-b)))
    (is (= "v1" (:relation_schema_version normalized)))
    (is (= "unit:source" (:source_unit_id normalized)))
    (is (= ["unit:target"] (:target_unit_ids normalized)))
    (is (= "resolved" (:resolution_status normalized)))
    (is (relations/valid-relation? normalized))))

(deftest relation-id-preserves-dataflow-argument-payload-test
  (let [base {:source_unit_id "unit:wrapper"
              :target_key "save!"
              :target_unit_ids ["unit:save"]
              :relation_type "dataflow/passes-argument"
              :resolution_status "resolved"
              :evidence_quality "medium"
              :evidence_location {:start_line 10}
              :provenance {:producer "test"}}
        first-arg (relations/normalize-relation (assoc base :local_name "order" :arg_index 0))
        second-arg (relations/normalize-relation (assoc base :local_name "client" :arg_index 1))]
    (is (not= (:relation_id first-arg) (:relation_id second-arg)))))

(deftest index-relations-builds-forward-and-reverse-indexes-test
  (let [resolved (relations/normalize-relation
                  {:source_unit_id "unit:wrapper"
                   :target_unit_ids ["unit:callee"]
                   :relation_type "dataflow/local-binding-call-result"
                   :evidence_quality "medium"
                   :provenance {:producer "test"}})
        unresolved (relations/normalize-relation
                    {:source_unit_id "unit:wrapper"
                     :target_key "my.app/missing"
                     :relation_type "dataflow/passes-argument"
                     :resolution_status "unresolved"
                     :evidence_quality "low"
                     :provenance {:producer "test"}})
        indexed (relations/index-relations [unresolved resolved])]
    (testing "relations are keyed by deterministic relation id"
      (is (= #{(:relation_id resolved) (:relation_id unresolved)}
             (set (keys (:relations indexed))))))
    (testing "forward index is keyed by source unit"
      (is (= #{(:relation_id resolved) (:relation_id unresolved)}
             (get (:relation_forward_index indexed) "unit:wrapper"))))
    (testing "reverse index includes resolved unit targets and unresolved target keys"
      (is (= #{(:relation_id resolved)}
             (get (:relation_reverse_index indexed) "unit:callee")))
      (is (= #{(:relation_id unresolved)}
             (get (:relation_reverse_index indexed) "my.app/missing"))))))

(deftest create-index-attaches-empty-relation-indexes-test
  (let [index (sci/create-index {:root_path (sample-root!)})]
    (is (= {} (:relations index)))
    (is (= {} (:relation_forward_index index)))
    (is (= {} (:relation_reverse_index index)))
    (is (map? (:callers_index index)))
    (is (map? (:callees_index index)))))

(deftest relation-id-stable-across-resolution-and-evidence-test
  (testing "identity derives only from stable semantic fields; resolving an
  unresolved fact and attaching richer evidence keeps one relation_id"
    (let [semantic {:source_unit_id "unit:wrapper"
                    :target_key "save!"
                    :relation_type "dataflow/passes-argument"
                    :local_name "order"
                    :arg_index 0}
          unresolved (relations/normalize-relation
                      (assoc semantic
                             :resolution_status "unresolved"
                             :evidence_quality "low"))
          resolved (relations/normalize-relation
                    (assoc semantic
                           :target_unit_ids ["unit:save"]
                           :resolution_status "resolved"
                           :evidence_quality "high"
                           :evidence_location {:start_line 10}
                           :provenance {:producer "scip"}))]
      (is (= (:relation_id unresolved) (:relation_id resolved)))
      (is (relations/valid-relation? unresolved))
      (is (relations/valid-relation? resolved)))))

(deftest relation-validation-surfaces-structured-diagnostics-test
  (let [ok (relations/normalize-relation
            {:source_unit_id "unit:src"
             :target_unit_ids ["unit:dst"]
             :relation_type "dataflow/returns-call-result"
             :evidence_quality "medium"})
        unknown-type (relations/normalize-relation
                      {:source_unit_id "unit:other"
                       :target_unit_ids ["unit:dst"]
                       :relation_type "bogus/type"
                       :evidence_quality "medium"})
        resolved-no-targets (assoc ok :resolution_status "resolved" :target_unit_ids [])
        {:keys [relations diagnostics]}
        (relations/normalize-relations-with-diagnostics
         [ok unknown-type resolved-no-targets])
        codes (set (mapcat #(map :code (:errors %)) diagnostics))]
    (testing "valid facts pass and invalid facts are excluded"
      (is (relations/valid-relation? ok))
      (is (not (relations/valid-relation? unknown-type)))
      (is (= [(:relation_id ok)] (mapv :relation_id relations))))
    (testing "invalid facts are surfaced as structured diagnostics, not dropped"
      (is (= 2 (count diagnostics)))
      (is (contains? codes :invalid-relation-type))
      (is (contains? codes :resolved-without-targets)))))

(deftest index-relations-surfaces-invalid-fact-diagnostics-test
  (let [valid (relations/normalize-relation
               {:source_unit_id "unit:src"
                :target_unit_ids ["unit:dst"]
                :relation_type "dataflow/returns-call-result"
                :evidence_quality "medium"})
        invalid (relations/normalize-relation
                 {:source_unit_id "unit:other"
                  :target_unit_ids ["unit:dst"]
                  :relation_type "bogus/type"
                  :evidence_quality "medium"})
        indexed (relations/index-relations [valid invalid])]
    (is (= #{(:relation_id valid)} (set (keys (:relations indexed)))))
    (is (= 1 (count (:relation_diagnostics indexed))))
    (is (= :invalid-relation-type
           (-> indexed :relation_diagnostics first :errors first :code)))))

(defn- traversal-rel [src tgt status typ]
  {:source_unit_id src
   :target_unit_ids (if (vector? tgt) tgt [tgt])
   :relation_type typ
   :resolution_status status
   :evidence_quality "medium"})

(defn- node-ids [result]
  (mapv :unit_id (:nodes result)))

(deftest traverse-relations-downstream-depth-and-direction-test
  (let [idx (relations/index-relations
             [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
              (traversal-rel "B" "C" "resolved" "dataflow/returns-call-result")
              (traversal-rel "C" "D" "resolved" "dataflow/returns-call-result")
              (traversal-rel "D" "E" "resolved" "dataflow/returns-call-result")
              (traversal-rel "E" "F" "resolved" "dataflow/returns-call-result")])
        down (relations/traverse-relations idx {:direction :downstream :start_nodes ["A"]})
        up (relations/traverse-relations idx {:direction :upstream :start_nodes ["C"]})]
    (testing "downstream stops at the depth ceiling and flags the truncation"
      (is (= ["A" "B" "C" "D" "E"] (node-ids down)))
      (is (true? (get-in down [:truncated :max_depth])))
      (is (= 4 (get-in down [:budgets :max_depth]))))
    (testing "requested depth cannot exceed the ceiling"
      (is (= 4 (get-in (relations/traverse-relations idx {:direction :downstream
                                                          :start_nodes ["A"]
                                                          :max_depth 99})
                       [:budgets :max_depth]))))
    (testing "an explicit zero budget is honored as a lower bound, not widened"
      (let [zero (relations/traverse-relations idx {:direction :downstream
                                                    :start_nodes ["A"]
                                                    :max_depth 0})]
        (is (= 0 (get-in zero [:budgets :max_depth])))
        (is (= ["A"] (node-ids zero)))
        (is (true? (get-in zero [:truncated :max_depth])))))
    (testing "upstream walks target -> source"
      (is (= ["C" "B" "A"] (node-ids up))))))

(deftest traverse-relations-relation-type-filter-test
  (let [idx (relations/index-relations
             [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
              (traversal-rel "A" "C" "resolved" "dataflow/passes-argument")])
        filtered (relations/traverse-relations
                  idx {:direction :downstream :start_nodes ["A"]
                       :relation_types ["dataflow/returns-call-result"]})]
    (is (= ["A" "B"] (node-ids filtered)))))

(deftest traverse-relations-resolved-only-conservatism-test
  (let [idx (relations/index-relations
             [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
              (assoc (traversal-rel "A" ["D1" "D2"] "ambiguous" "dataflow/returns-call-result")
                     :target_key "amb")])
        default (relations/traverse-relations idx {:direction :downstream :start_nodes ["A"]})
        permissive (relations/traverse-relations idx {:direction :downstream
                                                      :start_nodes ["A"]
                                                      :resolved_only false})]
    (testing "ambiguous edges are excluded by default"
      (is (= #{"A" "B"} (set (node-ids default))))
      (is (true? (get-in default [:budgets :resolved_only]))))
    (testing "ambiguous edges fan out only when explicitly requested"
      (is (= #{"A" "B" "D1" "D2"} (set (node-ids permissive)))))))

(deftest traverse-relations-cycle-safe-test
  (let [idx (relations/index-relations
             [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
              (traversal-rel "B" "A" "resolved" "dataflow/returns-call-result")])
        res (relations/traverse-relations idx {:direction :downstream :start_nodes ["A"]})]
    (testing "each node is discovered once and traversal terminates on a cycle"
      (is (= #{"A" "B"} (set (node-ids res))))
      (is (= 2 (count (:edges res)))))))

(deftest traverse-relations-node-and-path-budgets-test
  (let [chain (relations/index-relations
               [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
                (traversal-rel "B" "C" "resolved" "dataflow/returns-call-result")])
        branch (relations/index-relations
                [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
                 (traversal-rel "A" "C" "resolved" "dataflow/passes-argument")])
        node-capped (relations/traverse-relations chain {:direction :downstream
                                                         :start_nodes ["A"]
                                                         :max_nodes 2})
        path-capped (relations/traverse-relations branch {:direction :downstream
                                                          :start_nodes ["A"]
                                                          :max_discovery_paths 1})]
    (testing "node budget stops discovery and flags truncation"
      (is (= ["A" "B"] (node-ids node-capped)))
      (is (true? (get-in node-capped [:truncated :max_nodes]))))
    (testing "path budget caps recorded paths and flags truncation"
      (is (= 1 (count (:discovery_paths path-capped))))
      (is (true? (get-in path-capped [:truncated :max_discovery_paths]))))))

(deftest traverse-relations-deterministic-and-guarded-test
  (let [idx (relations/index-relations
             [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
              (traversal-rel "A" "C" "resolved" "dataflow/passes-argument")])
        req {:direction :downstream :start_nodes ["A"]}]
    (testing "identical requests yield identical results"
      (is (= (relations/traverse-relations idx req)
             (relations/traverse-relations idx req))))
    (testing "an unknown direction is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (relations/traverse-relations idx {:direction :sideways
                                                      :start_nodes ["A"]}))))))

(deftest traverse-relations-with-batched-frontier-provider-parity-test
  (let [idx (relations/index-relations
             [(traversal-rel "A" "B" "resolved" "dataflow/returns-call-result")
              (traversal-rel "A" "C" "resolved" "dataflow/passes-argument")
              (traversal-rel "B" "D" "resolved" "dataflow/returns-call-result")
              (traversal-rel "C" "E" "resolved" "dataflow/returns-call-result")
              (traversal-rel "D" "A" "resolved" "dataflow/returns-call-result")])
        calls (atom [])
        base-provider (relations/in-memory-neighbor-provider idx)
        recording-provider (fn [nodes direction]
                             (swap! calls conj (vec nodes))
                             (base-provider nodes direction))
        req {:direction :downstream :start_nodes ["A"]}
        via-provider (relations/traverse-relations-with recording-provider req)
        pure (relations/traverse-relations idx req)]
    (testing "the provider seam is byte-identical to the pure in-memory kernel"
      (is (= pure via-provider)))
    (testing "neighbors are fetched once per depth level, not once per node (no N+1)"
      ;; levels: [A] -> [B C] -> [D E] (E has no out-edges, D closes the cycle)
      (is (= 3 (count @calls)))
      (is (= [#{"A"} #{"B" "C"} #{"D" "E"}] (mapv set @calls)))
      (is (some #(> (count %) 1) @calls)))))
