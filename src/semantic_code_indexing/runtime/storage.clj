(ns semantic-code-indexing.runtime.storage
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defprotocol IndexStorage
  (init-storage! [storage])
  (save-index! [storage index])
  (load-latest-index [storage root-path]))

(defrecord InMemoryStorage [state]
  IndexStorage
  (init-storage! [_] true)
  (save-index! [_ index]
    (swap! state assoc (:root_path index) index)
    true)
  (load-latest-index [_ root-path]
    (get @state root-path)))

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

(defrecord PostgresStorage [datasource]
  IndexStorage
  (init-storage! [_]
    (jdbc/execute! datasource
                   ["create table if not exists semantic_index_snapshots (
                       id bigserial primary key,
                       root_path text not null,
                       snapshot_id text not null,
                       indexed_at timestamptz not null default now(),
                       payload jsonb not null
                     )"])
    (jdbc/execute! datasource
                   ["create index if not exists idx_semantic_index_snapshots_root_path_id
                     on semantic_index_snapshots(root_path, id desc)"])
    (jdbc/execute! datasource
                   ["create unique index if not exists uq_semantic_index_snapshots_root_snapshot
                     on semantic_index_snapshots(root_path, snapshot_id)"])
    (jdbc/execute! datasource
                   ["create table if not exists semantic_index_units (
                       id bigserial primary key,
                       root_path text not null,
                       snapshot_id text not null,
                       unit_id text not null,
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
    true)
  (save-index! [_ index]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute! tx
                     ["insert into semantic_index_snapshots(root_path, snapshot_id, payload)
                       values (?, ?, cast(? as jsonb))
                       on conflict (root_path, snapshot_id)
                       do update set payload = excluded.payload, indexed_at = now()"
                      (:root_path index)
                      (:snapshot_id index)
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
                         (root_path, snapshot_id, unit_id, path, module, symbol, kind, start_line, end_line, parser_mode, payload)
                         values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))"
                        (:root_path index)
                        (:snapshot_id index)
                        (:unit_id u)
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
                        callee])))
    true)
  (load-latest-index [_ root-path]
    (when-let [row (first (jdbc/execute! datasource
                                         ["select payload
                                           from semantic_index_snapshots
                                           where root_path = ?
                                           order by id desc
                                           limit 1"
                                          root-path]))]
      (parse-json (:semantic_index_snapshots/payload row (:payload row))))))

(defn postgres-storage [opts]
  (let [db-spec (normalize-db-spec opts)]
    (->PostgresStorage (jdbc/get-datasource db-spec))))
