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
    true)
  (save-index! [_ index]
    (jdbc/execute! datasource
                   ["insert into semantic_index_snapshots(root_path, snapshot_id, payload)
                     values (?, ?, cast(? as jsonb))"
                    (:root_path index)
                    (:snapshot_id index)
                    (->json index)])
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
