(ns semidx.runtime.storage-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [semidx.core :as sci]
            [semidx.runtime.relations :as relations]
            [semidx.runtime.storage :as storage]))

(defn- write-file! [root rel-path content]
  (let [f (io/file root rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- sample-root! []
  (let [root (str (java.nio.file.Files/createTempDirectory "semidx-storage" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (write-file! root "src/my/app/core.clj" "(ns my.app.core)\n(defn run [] :ok)\n")
    root))

(defn- shadow-index [root repo-key workspace-key git-branch git-commit indexed-at]
  (-> (sci/create-index {:root_path root})
      (assoc :repo_key repo-key
             :workspace_path root
             :workspace_key workspace-key
             :git_branch git-branch
             :git_commit git-commit
             :indexed_at indexed-at)))

(deftest postgres-init-storage-adds-repo-identity-columns-and-indexes-test
  (let [statements (atom [])
        storage-adapter (storage/map->PostgresStorage {:datasource ::fake})]
    (with-redefs [jdbc/execute! (fn [_ sqlvec]
                                  (swap! statements conj (first sqlvec))
                                  [])]
      (storage/init-storage! storage-adapter))
    (testing "snapshot table includes additive identity columns"
      (is (some #(str/includes? % "repo_key text") @statements))
      (is (some #(str/includes? % "workspace_path text") @statements))
      (is (some #(str/includes? % "workspace_key text") @statements))
      (is (some #(str/includes? % "git_branch text") @statements))
      (is (some #(str/includes? % "git_commit text") @statements))
      (is (some #(str/includes? % "git_dirty boolean") @statements))
      (is (some #(str/includes? % "identity_source text") @statements)))
    (testing "repo-aware indexes are created additively"
      (is (some #(str/includes? % "idx_semantic_index_snapshots_repo_key_id") @statements))
      (is (some #(str/includes? % "idx_semantic_index_snapshots_repo_branch_id") @statements))
      (is (some #(str/includes? % "idx_semantic_index_snapshots_repo_commit") @statements)))))

(deftest save-index-tx-writes-repo-identity-metadata-into-snapshot-row-test
  (let [root (sample-root!)
        index (sci/create-index {:root_path root})
        statements (atom [])]
    (with-redefs [jdbc/execute! (fn [_ sqlvec]
                                  (swap! statements conj sqlvec)
                                  [])]
      (#'semidx.runtime.storage/save-index-tx! ::fake-tx index))
    (let [snapshot-insert (first @statements)]
      (testing "snapshot insert persists additive repo metadata"
        (is (str/includes? (first snapshot-insert) "repo_key"))
        (is (= (:root_path index) (nth snapshot-insert 1)))
        (is (= (:snapshot_id index) (nth snapshot-insert 2)))
        (is (= (:repo_key index) (nth snapshot-insert 3)))
        (is (= (:workspace_path index) (nth snapshot-insert 4)))
        (is (= (:workspace_key index) (nth snapshot-insert 5)))
        (is (= (:git_branch index) (nth snapshot-insert 6)))
        (is (= (:git_commit index) (nth snapshot-insert 7)))
        (is (= (:git_dirty index) (nth snapshot-insert 8)))
        (is (= (:identity_source index) (nth snapshot-insert 9)))))))

(deftest shadow-repo-lookups-work-across-workspaces-test
  (let [root-a (sample-root!)
        root-b (sample-root!)
        storage-adapter (storage/in-memory-storage)
        repo-key "repo/shared"
        index-a (shadow-index root-a repo-key "workspace-a" "main" "commit-a" "2026-04-03T16:10:00Z")
        index-b (shadow-index root-b repo-key "workspace-b" "feature-x" "commit-b" "2026-04-03T16:11:00Z")]
    (storage/save-index! storage-adapter index-a)
    (storage/save-index! storage-adapter index-b)
    (testing "latest by repo can reuse a newer snapshot from another workspace"
      (is (= (:snapshot_id index-b)
             (:snapshot_id (sci/load-latest-by-repo storage-adapter repo-key)))))
    (testing "repo+branch lookup narrows to the matching branch"
      (is (= (:snapshot_id index-a)
             (:snapshot_id (sci/load-latest-by-repo-branch storage-adapter repo-key "main"))))
      (is (= (:snapshot_id index-b)
             (:snapshot_id (sci/load-latest-by-repo-branch storage-adapter repo-key "feature-x")))))
    (testing "repo+commit lookup resolves the exact snapshot"
      (is (= (:snapshot_id index-a)
             (:snapshot_id (sci/load-by-repo-commit storage-adapter repo-key "commit-a"))))
      (is (= (:snapshot_id index-b)
             (:snapshot_id (sci/load-by-repo-commit storage-adapter repo-key "commit-b")))))))

(deftest create-index-load-latest-remains-root-path-scoped-test
  (let [root-a (sample-root!)
        root-b (sample-root!)
        storage-adapter (storage/in-memory-storage)
        repo-key "repo/shared"
        index-a (shadow-index root-a repo-key "workspace-a" "main" "commit-a" "2026-04-03T16:10:00Z")
        index-b (shadow-index root-b repo-key "workspace-b" "feature-x" "commit-b" "2026-04-03T16:11:00Z")]
    (storage/save-index! storage-adapter index-a)
    (storage/save-index! storage-adapter index-b)
    (let [loaded-a (sci/create-index {:root_path root-a
                                      :storage storage-adapter
                                      :load_latest true})]
      (testing "legacy load_latest still prefers the current workspace root path"
        (is (= (:snapshot_id index-a) (:snapshot_id loaded-a)))
        (is (= (:root_path index-a) (:root_path loaded-a)))
        (is (not= (:snapshot_id index-b) (:snapshot_id loaded-a)))))
    (testing "shadow lookup helpers remain independently available"
      (is (= (:snapshot_id index-b)
             (:snapshot_id (sci/load-latest-by-repo storage-adapter repo-key)))))))

(deftest in-memory-storage-workspace-state-round-trip-test
  (let [root (sample-root!)
        storage-adapter (storage/in-memory-storage)
        index (sci/create-index {:root_path root :storage storage-adapter})
        _ (storage/save-index! storage-adapter index)
        loaded (storage/load-latest-index storage-adapter root)]
    (testing "workspace state round-trip via InMemoryStorage"
      (is (some? (:workspace_state loaded)))
      (is (= (get-in index [:workspace_state :workspace_fingerprint])
             (get-in loaded [:workspace_state :workspace_fingerprint]))))))

(defn- flow-fixture-root! []
  (let [root (str (java.nio.file.Files/createTempDirectory "semidx-relations-proj" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (write-file! root "src/my/app/flow.clj"
                 "(ns my.app.flow)\n(defn make-client [config] config)\n(defn normalize [order] order)\n(defn save! [order client] order)\n(defn wrapper [order config]\n  (let [client (make-client config)]\n    (save! order client)\n    (normalize order)))\n")
    root))

(deftest postgres-init-storage-creates-relations-projection-test
  (let [statements (atom [])
        storage-adapter (storage/map->PostgresStorage {:datasource ::fake})]
    (with-redefs [jdbc/execute! (fn [_ sqlvec]
                                  (swap! statements conj (first sqlvec))
                                  [])]
      (storage/init-storage! storage-adapter))
    (testing "the forward-only relation projection table and its frontier indexes are created"
      (is (some #(str/includes? % "create table if not exists semantic_index_relations") @statements))
      (is (some #(str/includes? % "idx_semantic_index_relations_source") @statements))
      (is (some #(str/includes? % "idx_semantic_index_relations_target") @statements)))))

(deftest save-index-tx-writes-relation-projection-rows-test
  (let [root (flow-fixture-root!)
        index (sci/create-index {:root_path root :parser_opts {:clojure_engine :regex}})
        statements (atom [])]
    (with-redefs [jdbc/execute! (fn [_ sqlvec]
                                  (swap! statements conj sqlvec)
                                  [])]
      (#'semidx.runtime.storage/save-index-tx! ::fake-tx index))
    (let [sqls (map first @statements)
          relation-inserts (filter #(str/includes? (first %) "insert into semantic_index_relations") @statements)]
      (testing "the projection is rewritten forward-only for this snapshot"
        (is (some #(str/includes? % "delete from semantic_index_relations") sqls))
        (is (seq relation-inserts)))
      (testing "each relation row carries snapshot-scoped source/target endpoints"
        (let [row (first relation-inserts)]
          (is (= (:root_path index) (nth row 1)))
          (is (= (:snapshot_id index) (nth row 2)))
          (is (string? (nth row 6)))
          (is (string? (nth row 7))))))))

(deftest pg-relation-neighbor-provider-parity-test
  (let [root (flow-fixture-root!)
        index (sci/create-index {:root_path root :parser_opts {:clojure_engine :regex}})
        rows (#'semidx.runtime.storage/relation-rows index)
        calls (atom [])
        fake-execute! (fn [_ sqlvec]
                        (swap! calls conj (vec (last sqlvec)))
                        (let [sql (first sqlvec)
                              nodes (set (seq (last sqlvec)))
                              col (cond (str/includes? sql "source_unit_id = any") :source_unit_id
                                        (str/includes? sql "target_unit_id = any") :target_unit_id)]
                          (->> rows
                               (filter #(contains? nodes (get % col)))
                               (mapv (fn [r]
                                       {:semantic_index_relations/relation_id (:relation_id r)
                                        :semantic_index_relations/relation_type (:relation_type r)
                                        :semantic_index_relations/resolution_status (:resolution_status r)
                                        :semantic_index_relations/source_unit_id (:source_unit_id r)
                                        :semantic_index_relations/target_unit_id (:target_unit_id r)})))))
        provider (storage/pg-relation-neighbor-provider ::fake (:root_path index) (:snapshot_id index))
        wrapper-id "src/my/app/flow.clj::my.app.flow/wrapper"
        make-client-id "src/my/app/flow.clj::my.app.flow/make-client"
        requests [{:direction :downstream :start_nodes [wrapper-id]}
                  {:direction :upstream :start_nodes [make-client-id]}
                  {:direction :downstream :start_nodes [wrapper-id] :max_depth 1}
                  {:direction :downstream :start_nodes [wrapper-id] :max_nodes 2}]]
    (with-redefs [jdbc/execute! fake-execute!]
      (testing "PostgreSQL execution adapter output is byte-identical to the pure in-memory kernel"
        (doseq [req requests]
          (is (= (relations/traverse-relations index req)
                 (relations/traverse-relations-with provider req))
              (str "parity for " req))))
      (testing "neighbors are fetched once per depth level (batched, no N+1)"
        (reset! calls [])
        (let [result (relations/traverse-relations-with provider {:direction :downstream :start_nodes [wrapper-id]})]
          ;; wrapper -> {make-client save! normalize} -> (leaves); frontier queries per level
          (is (seq (:nodes result)))
          (is (every? #(>= (count %) 1) @calls))
          (is (some #(> (count %) 1) @calls)))))))

(deftest postgres-relation-traversal-roundtrip-parity-test
  (if-let [jdbc-url (System/getenv "SEMIDX_TEST_POSTGRES_URL")]
    (let [root (flow-fixture-root!)
          storage-adapter (sci/postgres-storage {:jdbc-url jdbc-url})
          index (sci/create-index {:root_path root :parser_opts {:clojure_engine :regex}})]
      (storage/init-storage! storage-adapter)
      (storage/save-index! storage-adapter index)
      (let [provider (storage/pg-relation-neighbor-provider
                      (:datasource storage-adapter) (:root_path index) (:snapshot_id index))
            wrapper-id "src/my/app/flow.clj::my.app.flow/wrapper"
            make-client-id "src/my/app/flow.clj::my.app.flow/make-client"]
        (testing "real PostgreSQL projection traversal matches the pure in-memory kernel"
          (doseq [req [{:direction :downstream :start_nodes [wrapper-id]}
                       {:direction :upstream :start_nodes [make-client-id]}
                       {:direction :downstream :start_nodes [wrapper-id] :max_depth 1}]]
            (is (= (relations/traverse-relations index req)
                   (relations/traverse-relations-with provider req))
                (str "pg parity for " req))))))
    (is true "SEMIDX_TEST_POSTGRES_URL is not set; skipping postgres relation traversal parity test.")))

