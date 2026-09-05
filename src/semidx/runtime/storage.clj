(ns semidx.runtime.storage
  (:require [clojure.data.json :as json]
            [semidx.runtime.semantic-id :as semantic-id]
            [next.jdbc :as jdbc]))

(defprotocol IndexStorage
  (init-storage! [storage])
  (save-index! [storage index])
  (load-latest-index [storage root-path])
  (load-latest-index-by-repo [storage repo-key])
  (load-latest-index-by-repo-branch [storage repo-key git-branch])
  (load-index-by-repo-commit [storage repo-key git-commit])
  (load-index-by-snapshot [storage root-path snapshot-id])
  (fetch-units [storage root-path opts])
  (fetch-callers [storage root-path unit-id opts])
  (fetch-callees [storage root-path unit-id opts]))

(defn- all-snapshots [entries]
  (->> entries
       vals
       (mapcat (comp vals :snapshots))
       (remove nil?)))

(defn- latest-matching-index [entries pred]
  (some->> (all-snapshots entries)
           (filter pred)
           seq
           (sort-by #(or (:indexed_at %) ""))
           last))

(defrecord InMemoryStorage [state]
  IndexStorage
  (init-storage! [_] true)
  (save-index! [_ index]
    (swap! state update (:root_path index)
           (fn [entry]
             {:latest_snapshot_id (:snapshot_id index)
              :snapshots (assoc (or (:snapshots entry) {})
                                (:snapshot_id index)
                                index)}))
    true)
  (load-latest-index [_ root-path]
    (let [entry (get @state root-path)]
      (get-in entry [:snapshots (:latest_snapshot_id entry)])))
  (load-latest-index-by-repo [_ repo-key]
    (latest-matching-index @state #(= repo-key (:repo_key %))))
  (load-latest-index-by-repo-branch [_ repo-key git-branch]
    (latest-matching-index @state #(and (= repo-key (:repo_key %))
                                        (= git-branch (:git_branch %)))))
  (load-index-by-repo-commit [_ repo-key git-commit]
    (latest-matching-index @state #(and (= repo-key (:repo_key %))
                                        (= git-commit (:git_commit %)))))
  (load-index-by-snapshot [_ root-path snapshot-id]
    (get-in @state [root-path :snapshots snapshot-id]))
  (fetch-units [_ root-path {:keys [snapshot_id module symbol limit] :or {limit 100}}]
    (let [entry (get @state root-path)
          idx (if snapshot_id
                (get-in entry [:snapshots snapshot_id])
                (get-in entry [:snapshots (:latest_snapshot_id entry)]))]
      (if (or (nil? idx)
              (and snapshot_id (not= snapshot_id (:snapshot_id idx))))
        []
        (->> (:unit_order idx)
             (map #(get (:units idx) %))
             (remove nil?)
             (map semantic-id/enrich-unit)
             (filter #(if module (= module (:module %)) true))
             (filter #(if symbol (= symbol (:symbol %)) true))
             (take limit)
             vec))))
  (fetch-callers [_ root-path unit-id {:keys [snapshot_id limit] :or {limit 100}}]
    (let [entry (get @state root-path)
          idx (if snapshot_id
                (get-in entry [:snapshots snapshot_id])
                (get-in entry [:snapshots (:latest_snapshot_id entry)]))]
      (if (or (nil? idx)
              (and snapshot_id (not= snapshot_id (:snapshot_id idx))))
        []
        (->> (get (:callers_index idx) unit-id #{})
             (map #(get (:units idx) %))
             (remove nil?)
             (map semantic-id/enrich-unit)
             (take limit)
             vec))))
  (fetch-callees [_ root-path unit-id {:keys [snapshot_id limit] :or {limit 100}}]
    (let [entry (get @state root-path)
          idx (if snapshot_id
                (get-in entry [:snapshots snapshot_id])
                (get-in entry [:snapshots (:latest_snapshot_id entry)]))]
      (if (or (nil? idx)
              (and snapshot_id (not= snapshot_id (:snapshot_id idx))))
        []
        (let [callees (->> (:callers_index idx)
                           (filter (fn [[_ callers]] (contains? callers unit-id)))
                           (map first)
                           vec)]
          (->> callees
               (map #(get (:units idx) %))
               (remove nil?)
               (map semantic-id/enrich-unit)
               (take limit)
               vec))))))

(defn in-memory-storage []
  (->InMemoryStorage (atom {})))

(defn- normalize-db-spec [{:keys [db-spec jdbc-url user password]}]
  (cond
    db-spec db-spec
    jdbc-url (cond-> {:jdbcUrl jdbc-url}
               user (assoc :user user)
               password (assoc :password password))
    :else (throw (ex-info "postgres storage requires :db-spec or :jdbc-url"
                          {:type :invalid_storage_config}))))

(defn- ->json [m]
  (json/write-str m))

(defn- parse-json [v]
  (cond
    (nil? v) nil
    (string? v) (json/read-str v :key-fn keyword)
    :else
    (let [s (str v)]
      (json/read-str s :key-fn keyword))))

(defn- ordered-units [index]
  (->> (:unit_order index)
       (map #(get (:units index) %))
       (remove nil?)
       vec))

(defn- call-edge-rows [index]
  (->> (:callers_index index)
       (mapcat (fn [[callee callers]]
                 (map (fn [caller]
                        {:caller caller :callee callee})
                      callers)))
       vec))

(defn- relation-rows
  "Flatten snapshot typed relations into one row per (relation, target unit id)
  for the forward-only semantic_index_relations projection (ADR-040). Unresolved
  relations carry no target unit ids and therefore produce no rows, which matches
  the in-memory traversal semantics for unit-id nodes."
  [index]
  (->> (:relations index)
       vals
       (mapcat (fn [rel]
                 (map (fn [target]
                        {:relation_id (:relation_id rel)
                         :relation_type (:relation_type rel)
                         :resolution_status (:resolution_status rel)
                         :source_unit_id (:source_unit_id rel)
                         :target_unit_id target
                         :target_key (:target_key rel)
                         :evidence_quality (:evidence_quality rel)})
                      (:target_unit_ids rel))))
       vec))

(defn- row-payload [row]
  (let [payload (parse-json (:semantic_index_units/payload row
                                                           (:semantic_index_snapshots/payload row
                                                                                              (:payload row))))]
    (if (map? payload)
      (semantic-id/enrich-unit payload)
      payload)))

(defn- latest-snapshot-id [datasource root-path]
  (when-let [row (first (jdbc/execute! datasource
                                       ["select snapshot_id
                                         from semantic_index_snapshots
                                         where root_path = ?
                                         order by id desc
                                         limit 1"
                                        root-path]))]
    (or (:semantic_index_snapshots/snapshot_id row)
        (:snapshot_id row))))

(defn- load-first-index [datasource sql-params]
  (when-let [row (first (jdbc/execute! datasource sql-params))]
    (some-> (parse-json (:semantic_index_snapshots/payload row (:payload row)))
            semantic-id/enrich-index)))

(defn- resolve-snapshot-id [datasource root-path snapshot-id]
  (or snapshot-id (latest-snapshot-id datasource root-path)))

(defn- save-index-tx! [tx index]
  (jdbc/execute! tx
                 ["insert into semantic_index_snapshots(root_path, snapshot_id, repo_key, workspace_path, workspace_key, git_branch, git_commit, git_dirty, identity_source, payload)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                   on conflict (root_path, snapshot_id)
                   do update set
                     repo_key = excluded.repo_key,
                     workspace_path = excluded.workspace_path,
                     workspace_key = excluded.workspace_key,
                     git_branch = excluded.git_branch,
                     git_commit = excluded.git_commit,
                     git_dirty = excluded.git_dirty,
                     identity_source = excluded.identity_source,
                     payload = excluded.payload,
                     indexed_at = now()"
                  (:root_path index)
                  (:snapshot_id index)
                  (:repo_key index)
                  (:workspace_path index)
                  (:workspace_key index)
                  (:git_branch index)
                  (:git_commit index)
                  (:git_dirty index)
                  (:identity_source index)
                  (->json index)])
  (jdbc/execute! tx
                 ["delete from semantic_index_units where root_path = ? and snapshot_id = ?"
                  (:root_path index)
                  (:snapshot_id index)])
  (jdbc/execute! tx
                 ["delete from semantic_index_call_edges where root_path = ? and snapshot_id = ?"
                  (:root_path index)
                  (:snapshot_id index)])
  (doseq [u (ordered-units index)]
    (jdbc/execute! tx
                   ["insert into semantic_index_units
                     (root_path, snapshot_id, unit_id, semantic_id, semantic_id_version, semantic_fingerprint, path, module, symbol, kind, start_line, end_line, parser_mode, payload)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))"
                    (:root_path index)
                    (:snapshot_id index)
                    (:unit_id u)
                    (:semantic_id u)
                    (:semantic_id_version u)
                    (:semantic_fingerprint u)
                    (:path u)
                    (:module u)
                    (:symbol u)
                    (:kind u)
                    (:start_line u)
                    (:end_line u)
                    (:parser_mode u)
                    (->json u)]))
  (doseq [{:keys [caller callee]} (call-edge-rows index)]
    (jdbc/execute! tx
                   ["insert into semantic_index_call_edges(root_path, snapshot_id, caller_unit_id, callee_unit_id)
                     values (?, ?, ?, ?)"
                    (:root_path index)
                    (:snapshot_id index)
                    caller
                    callee]))
  (jdbc/execute! tx
                 ["delete from semantic_index_relations where root_path = ? and snapshot_id = ?"
                  (:root_path index)
                  (:snapshot_id index)])
  (doseq [r (relation-rows index)]
    (jdbc/execute! tx
                   ["insert into semantic_index_relations
                     (root_path, snapshot_id, relation_id, relation_type, resolution_status, source_unit_id, target_unit_id, target_key, evidence_quality)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    (:root_path index)
                    (:snapshot_id index)
                    (:relation_id r)
                    (:relation_type r)
                    (:resolution_status r)
                    (:source_unit_id r)
                    (:target_unit_id r)
                    (:target_key r)
                    (:evidence_quality r)])))

(defrecord PostgresStorage [datasource]
  IndexStorage
  (init-storage! [_]
    (jdbc/execute! datasource
                   ["create table if not exists semantic_index_snapshots (
                       id bigserial primary key,
                       root_path text not null,
                       snapshot_id text not null,
                       repo_key text,
                       workspace_path text,
                       workspace_key text,
                       git_branch text,
                       git_commit text,
                       git_dirty boolean,
                       identity_source text,
                       indexed_at timestamptz not null default now(),
                       payload jsonb not null
                     )"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists repo_key text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists workspace_path text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists workspace_key text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists git_branch text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists git_commit text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists git_dirty boolean"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_snapshots
                     add column if not exists identity_source text"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_snapshots_root_path_id
                     on semantic_index_snapshots(root_path, id desc)"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_snapshots_repo_key_id
                     on semantic_index_snapshots(repo_key, id desc)"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_snapshots_repo_branch_id
                     on semantic_index_snapshots(repo_key, git_branch, id desc)"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_snapshots_repo_commit
                     on semantic_index_snapshots(repo_key, git_commit)"])
    (jdbc/execute! datasource
                   ["create unique index if not exists uq_semantic_index_snapshots_root_snapshot
                     on semantic_index_snapshots(root_path, snapshot_id)"])
    (jdbc/execute! datasource
                   ["create table if not exists semantic_index_units (
                       id bigserial primary key,
                       root_path text not null,
                       snapshot_id text not null,
                       unit_id text not null,
                       semantic_id text,
                       semantic_id_version text,
                       semantic_fingerprint text,
                       path text not null,
                       module text,
                       symbol text,
                       kind text not null,
                       start_line integer not null,
                       end_line integer not null,
                       parser_mode text,
                       payload jsonb not null
                     )"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_units_root_snapshot
                     on semantic_index_units(root_path, snapshot_id)"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_units
                     add column if not exists semantic_id text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_units
                     add column if not exists semantic_id_version text"])
    (jdbc/execute! datasource
                   ["alter table semantic_index_units
                     add column if not exists semantic_fingerprint text"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_units_unit
                     on semantic_index_units(unit_id)"])
    (jdbc/execute! datasource
                   ["create table if not exists semantic_index_call_edges (
                       id bigserial primary key,
                       root_path text not null,
                       snapshot_id text not null,
                       caller_unit_id text not null,
                       callee_unit_id text not null
                     )"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_call_edges_root_snapshot
                     on semantic_index_call_edges(root_path, snapshot_id)"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_call_edges_callee
                     on semantic_index_call_edges(callee_unit_id)"])
    (jdbc/execute! datasource
                   ["create table if not exists semantic_index_relations (
                       id bigserial primary key,
                       root_path text not null,
                       snapshot_id text not null,
                       relation_id text not null,
                       relation_type text not null,
                       resolution_status text not null,
                       source_unit_id text not null,
                       target_unit_id text not null,
                       target_key text,
                       evidence_quality text
                     )"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_relations_source
                     on semantic_index_relations(root_path, snapshot_id, source_unit_id)"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_relations_target
                     on semantic_index_relations(root_path, snapshot_id, target_unit_id)"])
    true)
  (save-index! [_ index]
    (jdbc/with-transaction [tx datasource]
      (save-index-tx! tx index))
    true)
  (load-latest-index [_ root-path]
    (load-first-index datasource
                      ["select payload
                        from semantic_index_snapshots
                        where root_path = ?
                        order by id desc
                        limit 1"
                       root-path]))
  (load-latest-index-by-repo [_ repo-key]
    (load-first-index datasource
                      ["select payload
                        from semantic_index_snapshots
                        where repo_key = ?
                        order by id desc
                        limit 1"
                       repo-key]))
  (load-latest-index-by-repo-branch [_ repo-key git-branch]
    (load-first-index datasource
                      ["select payload
                        from semantic_index_snapshots
                        where repo_key = ?
                          and git_branch = ?
                        order by id desc
                        limit 1"
                       repo-key git-branch]))
  (load-index-by-repo-commit [_ repo-key git-commit]
    (load-first-index datasource
                      ["select payload
                        from semantic_index_snapshots
                        where repo_key = ?
                          and git_commit = ?
                        order by id desc
                        limit 1"
                       repo-key git-commit]))
  (load-index-by-snapshot [_ root-path snapshot-id]
    (load-first-index datasource
                      ["select payload
                        from semantic_index_snapshots
                        where root_path = ?
                          and snapshot_id = ?
                        limit 1"
                       root-path snapshot-id]))
  (fetch-units [_ root-path {:keys [snapshot_id module symbol limit] :or {limit 100}}]
    (if-let [sid (resolve-snapshot-id datasource root-path snapshot_id)]
      (let [sql (str "select payload
                      from semantic_index_units
                      where root_path = ? and snapshot_id = ?"
                     (when module " and module = ?")
                     (when symbol " and symbol = ?")
                     " order by path asc, start_line asc limit ?")
            params (cond-> [sql root-path sid]
                     module (conj module)
                     symbol (conj symbol)
                     :always (conj limit))]
        (->> (jdbc/execute! datasource params)
             (mapv row-payload)))
      []))
  (fetch-callers [_ root-path unit-id {:keys [snapshot_id limit] :or {limit 100}}]
    (if-let [sid (resolve-snapshot-id datasource root-path snapshot_id)]
      (->> (jdbc/execute! datasource
                          ["select u.payload
                            from semantic_index_call_edges e
                            join semantic_index_units u
                              on u.root_path = e.root_path
                             and u.snapshot_id = e.snapshot_id
                             and u.unit_id = e.caller_unit_id
                            where e.root_path = ?
                              and e.snapshot_id = ?
                              and e.callee_unit_id = ?
                            order by u.path asc, u.start_line asc
                            limit ?"
                           root-path sid unit-id limit])
           (mapv row-payload))
      []))
  (fetch-callees [_ root-path unit-id {:keys [snapshot_id limit] :or {limit 100}}]
    (if-let [sid (resolve-snapshot-id datasource root-path snapshot_id)]
      (->> (jdbc/execute! datasource
                          ["select u.payload
                            from semantic_index_call_edges e
                            join semantic_index_units u
                              on u.root_path = e.root_path
                             and u.snapshot_id = e.snapshot_id
                             and u.unit_id = e.callee_unit_id
                            where e.root_path = ?
                              and e.snapshot_id = ?
                              and e.caller_unit_id = ?
                            order by u.path asc, u.start_line asc
                            limit ?"
                           root-path sid unit-id limit])
           (mapv row-payload))
      [])))

(defn postgres-storage [opts]
  (let [db-spec (normalize-db-spec opts)]
    (->PostgresStorage (jdbc/get-datasource db-spec))))

(defn query-units [storage root-path opts]
  (init-storage! storage)
  (fetch-units storage root-path opts))

(defn load-latest-by-repo [storage repo-key]
  (init-storage! storage)
  (load-latest-index-by-repo storage repo-key))

(defn load-latest-by-repo-branch [storage repo-key git-branch]
  (init-storage! storage)
  (load-latest-index-by-repo-branch storage repo-key git-branch))

(defn load-by-repo-commit [storage repo-key git-commit]
  (init-storage! storage)
  (load-index-by-repo-commit storage repo-key git-commit))

(defn query-callers [storage root-path unit-id opts]
  (init-storage! storage)
  (fetch-callers storage root-path unit-id opts))

(defn query-callees [storage root-path unit-id opts]
  (init-storage! storage)
  (fetch-callees storage root-path unit-id opts))

(defn- relation-row-field [row unqualified]
  (let [qualified (keyword "semantic_index_relations" (name unqualified))]
    (get row qualified (get row unqualified))))

(defn pg-relation-neighbor-provider
  "PostgreSQL batched frontier neighbor provider for the traversal kernel
  (`semidx.runtime.relations/traverse-relations-with`, ADR-040). Given a frontier
  of nodes and a direction, it issues exactly one query per depth level over the
  `semantic_index_relations` projection and returns {node -> (seq relations)}
  shaped for the kernel (`:relation_id`, `:relation_type`, `:resolution_status`,
  `:source_unit_id`, `:target_unit_ids`). Storage only optimizes the neighbor
  fetch; the kernel still owns eligibility, fan-out, ordering, cycle handling, and
  budgets, so its output stays at parity with the pure in-memory provider."
  [datasource root-path snapshot-id]
  (fn [nodes direction]
    (if (empty? nodes)
      {}
      (let [node-col (case direction
                       :downstream "source_unit_id"
                       :upstream "target_unit_id")
            rows (jdbc/execute! datasource
                                [(str "select relation_id, relation_type, resolution_status,
                                        source_unit_id, target_unit_id
                                        from semantic_index_relations
                                        where root_path = ? and snapshot_id = ?
                                          and " node-col " = any(?)")
                                 root-path snapshot-id (into-array String (vec nodes))])
            norm (mapv (fn [row]
                         {:relation_id (relation-row-field row :relation_id)
                          :relation_type (relation-row-field row :relation_type)
                          :resolution_status (relation-row-field row :resolution_status)
                          :source_unit_id (relation-row-field row :source_unit_id)
                          :target_unit_id (relation-row-field row :target_unit_id)})
                       rows)]
        (case direction
          :downstream
          (into {}
                (map (fn [[src src-rows]]
                       [src (->> (group-by :relation_id src-rows)
                                 (mapv (fn [[_ rs]]
                                         (let [f (first rs)]
                                           {:relation_id (:relation_id f)
                                            :relation_type (:relation_type f)
                                            :resolution_status (:resolution_status f)
                                            :source_unit_id (:source_unit_id f)
                                            :target_unit_ids (vec (distinct (map :target_unit_id rs)))}))))]))
                (group-by :source_unit_id norm))

          :upstream
          (into {}
                (map (fn [[tgt tgt-rows]]
                       [tgt (mapv (fn [r]
                                    {:relation_id (:relation_id r)
                                     :relation_type (:relation_type r)
                                     :resolution_status (:resolution_status r)
                                     :source_unit_id (:source_unit_id r)
                                     :target_unit_ids [(:target_unit_id r)]})
                                  tgt-rows)]))
                (group-by :target_unit_id norm)))))))
