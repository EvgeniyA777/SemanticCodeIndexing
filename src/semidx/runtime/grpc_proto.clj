(ns semidx.runtime.grpc-proto
  (:require [clojure.data.json :as json])
  (:import [com.google.protobuf Descriptors$FieldDescriptor
                                Descriptors$FieldDescriptor$JavaType
                                Message]
           [semidx.runtime.grpc.v1 CreateIndexRequest CreateIndexResponse
                                   ExpandContextRequest ExpandContextResponse
                                   FetchContextDetailRequest FetchContextDetailResponse
                                   HealthRequest HealthResponse
                                   LiteralFileSliceRequest LiteralFileSliceResponse
                                   ResolveContextRequest ResolveContextResponse
                                   SnapshotDiffRequest SnapshotDiffResponse
                                   TraverseRelationsRequest TraverseRelationsResponse]))

(def ^:private default-instances
  {:health-request (HealthRequest/getDefaultInstance)
   :health-response (HealthResponse/getDefaultInstance)
   :create-index-request (CreateIndexRequest/getDefaultInstance)
   :create-index-response (CreateIndexResponse/getDefaultInstance)
   :resolve-context-request (ResolveContextRequest/getDefaultInstance)
   :resolve-context-response (ResolveContextResponse/getDefaultInstance)
   :expand-context-request (ExpandContextRequest/getDefaultInstance)
   :expand-context-response (ExpandContextResponse/getDefaultInstance)
   :fetch-context-detail-request (FetchContextDetailRequest/getDefaultInstance)
   :fetch-context-detail-response (FetchContextDetailResponse/getDefaultInstance)
   :literal-file-slice-request (LiteralFileSliceRequest/getDefaultInstance)
   :literal-file-slice-response (LiteralFileSliceResponse/getDefaultInstance)
   :snapshot-diff-request (SnapshotDiffRequest/getDefaultInstance)
   :snapshot-diff-response (SnapshotDiffResponse/getDefaultInstance)
   :traverse-relations-request (TraverseRelationsRequest/getDefaultInstance)
   :traverse-relations-response (TraverseRelationsResponse/getDefaultInstance)})

(defn default-instance [message-key]
  (or (get default-instances message-key)
      (throw (ex-info (str "unknown gRPC proto message " message-key)
                      {:message_key message-key}))))

(defn- field-descriptor [message-key ^Message message field-key]
  (or (-> message
          .getDescriptorForType
          (.findFieldByName (name field-key)))
      (throw (ex-info "Generated gRPC field is missing"
                      {:type :generated_grpc_field_missing
                       :message_key message-key
                       :field_key field-key}))))

(defn- coerce-field-value [^Descriptors$FieldDescriptor field value]
  (let [java-type (.getJavaType field)]
    (cond
      (= java-type Descriptors$FieldDescriptor$JavaType/STRING) (str value)
      (= java-type Descriptors$FieldDescriptor$JavaType/INT) (int (long (or value 0)))
      :else value)))

(defn- build-message [message-key values]
  (let [default ^Message (default-instance message-key)
        builder (.newBuilderForType default)]
    (doseq [[field-key value] values
            :when (some? value)]
      (let [field ^Descriptors$FieldDescriptor (field-descriptor message-key default field-key)]
        (if (.isRepeated field)
          (doseq [item value]
            (.addRepeatedField builder field (coerce-field-value field item)))
          (.setField builder field (coerce-field-value field value)))))
    (.build builder)))

(defn- string-field [message-key ^Message message field-key]
  (str (.getField message (field-descriptor message-key message field-key))))

(defn- int-field [message-key ^Message message field-key]
  (int (.getField message (field-descriptor message-key message field-key))))

(defn- repeated-string-field [message-key ^Message message field-key]
  (->> (.getField message (field-descriptor message-key message field-key))
       seq
       (mapv str)))

(defn- json-field [field-name raw]
  (when-let [value (not-empty (str raw))]
    (try
      (json/read-str value :key-fn keyword)
      (catch Exception e
        (throw (ex-info (str field-name " must contain valid JSON")
                        {:type :invalid_request
                         :message (str field-name " must contain valid JSON")}
                        e))))))

(defn- json-string [value]
  (when (some? value)
    (json/write-str value :escape-slash false)))

(defn health-request []
  (build-message :health-request {}))

(defn health-response [{:keys [status service capabilities_json]}]
  (build-message :health-response
                 (cond-> {:status status
                          :service service}
                   capabilities_json (assoc :capabilities_json capabilities_json))))

(defn health-response->map [message]
  {:status (string-field :health-response message :status)
   :service (string-field :health-response message :service)
   :capabilities_json (string-field :health-response message :capabilities_json)})

(defn create-index-request [{:keys [root_path paths parser_opts language_policy]}]
  (build-message :create-index-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :language_policy_json (json-string language_policy)}))

(defn create-index-request->map [message]
  {:root_path (not-empty (string-field :create-index-request message :root_path))
   :paths (not-empty (repeated-string-field :create-index-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :create-index-request message :parser_opts_json))
   :language_policy (json-field "language_policy_json"
                                (string-field :create-index-request message :language_policy_json))})

(defn create-index-response [{:keys [snapshot_id indexed_at file_count unit_count repo_map index_lifecycle]}]
  (build-message :create-index-response
                 {:snapshot_id snapshot_id
                  :indexed_at indexed_at
                  :file_count (or file_count 0)
                  :unit_count (or unit_count 0)
                  :repo_map_json (json-string repo_map)
                  :index_lifecycle_json (json-string index_lifecycle)}))

(defn create-index-response->map [message]
  {:snapshot_id (string-field :create-index-response message :snapshot_id)
   :indexed_at (string-field :create-index-response message :indexed_at)
   :file_count (int-field :create-index-response message :file_count)
   :unit_count (int-field :create-index-response message :unit_count)
   :index_lifecycle (or (json-field "index_lifecycle_json"
                                    (string-field :create-index-response message :index_lifecycle_json))
                        {})
   :repo_map (or (json-field "repo_map_json"
                             (string-field :create-index-response message :repo_map_json))
                 {})})

(defn resolve-context-request [{:keys [root_path paths parser_opts query retrieval_policy language_policy]}]
  (build-message :resolve-context-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :query_json (json-string query)
                  :retrieval_policy_json (json-string retrieval_policy)
                  :language_policy_json (json-string language_policy)}))

(defn resolve-context-request->map [message]
  {:root_path (not-empty (string-field :resolve-context-request message :root_path))
   :paths (not-empty (repeated-string-field :resolve-context-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :resolve-context-request message :parser_opts_json))
   :query (json-field "query_json"
                      (string-field :resolve-context-request message :query_json))
   :retrieval_policy (json-field "retrieval_policy_json"
                                 (string-field :resolve-context-request message :retrieval_policy_json))
   :language_policy (json-field "language_policy_json"
                                (string-field :resolve-context-request message :language_policy_json))})

(defn resolve-context-response [{:keys [selection_result] :as payload}]
  (build-message :resolve-context-response
                 {:selection_result_json (json-string (or selection_result payload))}))

(defn resolve-context-response->map [message]
  (or (json-field "selection_result_json"
                  (string-field :resolve-context-response message :selection_result_json))
      {}))

(defn expand-context-request [{:keys [root_path paths parser_opts selection_id snapshot_id unit_ids include_impact_hints language_policy]}]
  (build-message :expand-context-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :selection_id selection_id
                  :snapshot_id snapshot_id
                  :unit_ids (or unit_ids [])
                  :include_impact_hints (when (some? include_impact_hints) (str include_impact_hints))
                  :language_policy_json (json-string language_policy)}))

(defn expand-context-request->map [message]
  {:root_path (not-empty (string-field :expand-context-request message :root_path))
   :paths (not-empty (repeated-string-field :expand-context-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :expand-context-request message :parser_opts_json))
   :selection_id (not-empty (string-field :expand-context-request message :selection_id))
   :snapshot_id (not-empty (string-field :expand-context-request message :snapshot_id))
   :unit_ids (not-empty (repeated-string-field :expand-context-request message :unit_ids))
   :include_impact_hints (let [raw (some-> (string-field :expand-context-request message :include_impact_hints) not-empty)]
                           (when raw
                             (= "true" raw)))
   :language_policy (json-field "language_policy_json"
                                (string-field :expand-context-request message :language_policy_json))})

(defn expand-context-response [{:keys [expansion_result] :as payload}]
  (build-message :expand-context-response
                 {:expansion_result_json (json-string (or expansion_result payload))}))

(defn expand-context-response->map [message]
  (or (json-field "expansion_result_json"
                  (string-field :expand-context-response message :expansion_result_json))
      {}))

(defn fetch-context-detail-request [{:keys [root_path paths parser_opts selection_id snapshot_id unit_ids detail_level language_policy]}]
  (build-message :fetch-context-detail-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :selection_id selection_id
                  :snapshot_id snapshot_id
                  :unit_ids (or unit_ids [])
                  :detail_level detail_level
                  :language_policy_json (json-string language_policy)}))

(defn fetch-context-detail-request->map [message]
  {:root_path (not-empty (string-field :fetch-context-detail-request message :root_path))
   :paths (not-empty (repeated-string-field :fetch-context-detail-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :fetch-context-detail-request message :parser_opts_json))
   :selection_id (not-empty (string-field :fetch-context-detail-request message :selection_id))
   :snapshot_id (not-empty (string-field :fetch-context-detail-request message :snapshot_id))
   :unit_ids (not-empty (repeated-string-field :fetch-context-detail-request message :unit_ids))
   :detail_level (not-empty (string-field :fetch-context-detail-request message :detail_level))
   :language_policy (json-field "language_policy_json"
                                (string-field :fetch-context-detail-request message :language_policy_json))})

(defn fetch-context-detail-response [{:keys [detail_result] :as payload}]
  (build-message :fetch-context-detail-response
                 {:detail_result_json (json-string (or detail_result payload))}))

(defn fetch-context-detail-response->map [message]
  (or (json-field "detail_result_json"
                  (string-field :fetch-context-detail-response message :detail_result_json))
      {}))

(defn literal-file-slice-request [{:keys [root_path paths parser_opts selection_id snapshot_id path start_line end_line language_policy]}]
  (build-message :literal-file-slice-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :selection_id selection_id
                  :snapshot_id snapshot_id
                  :path path
                  :start_line (or start_line 0)
                  :end_line (or end_line 0)
                  :language_policy_json (json-string language_policy)}))

(defn literal-file-slice-request->map [message]
  {:root_path (not-empty (string-field :literal-file-slice-request message :root_path))
   :paths (not-empty (repeated-string-field :literal-file-slice-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :literal-file-slice-request message :parser_opts_json))
   :selection_id (not-empty (string-field :literal-file-slice-request message :selection_id))
   :snapshot_id (not-empty (string-field :literal-file-slice-request message :snapshot_id))
   :path (not-empty (string-field :literal-file-slice-request message :path))
   :start_line (int-field :literal-file-slice-request message :start_line)
   :end_line (int-field :literal-file-slice-request message :end_line)
   :language_policy (json-field "language_policy_json"
                                (string-field :literal-file-slice-request message :language_policy_json))})

(defn literal-file-slice-response [{:keys [literal_slice_result] :as payload}]
  (build-message :literal-file-slice-response
                 {:literal_slice_result_json (json-string (or literal_slice_result payload))}))

(defn literal-file-slice-response->map [message]
  (or (json-field "literal_slice_result_json"
                  (string-field :literal-file-slice-response message :literal_slice_result_json))
      {}))

(defn snapshot-diff-request [{:keys [root_path paths parser_opts baseline_snapshot_id include_unchanged language_policy]}]
  (build-message :snapshot-diff-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :baseline_snapshot_id baseline_snapshot_id
                  :include_unchanged (when (some? include_unchanged) (str include_unchanged))
                  :language_policy_json (json-string language_policy)}))

(defn snapshot-diff-request->map [message]
  {:root_path (not-empty (string-field :snapshot-diff-request message :root_path))
   :paths (not-empty (repeated-string-field :snapshot-diff-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :snapshot-diff-request message :parser_opts_json))
   :baseline_snapshot_id (not-empty (string-field :snapshot-diff-request message :baseline_snapshot_id))
   :include_unchanged (let [raw (some-> (string-field :snapshot-diff-request message :include_unchanged) not-empty)]
                        (when raw
                          (= "true" raw)))
   :language_policy (json-field "language_policy_json"
                                (string-field :snapshot-diff-request message :language_policy_json))})

(defn snapshot-diff-response [{:keys [snapshot_diff_result] :as payload}]
  (build-message :snapshot-diff-response
                 {:snapshot_diff_result_json (json-string (or snapshot_diff_result payload))}))

(defn snapshot-diff-response->map [message]
  (or (json-field "snapshot_diff_result_json"
                  (string-field :snapshot-diff-response message :snapshot_diff_result_json))
      {}))

(defn traverse-relations-request [{:keys [root_path paths parser_opts direction start_nodes
                                          relation_types resolved_only budgets snapshot_id language_policy]}]
  (build-message :traverse-relations-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :direction direction
                  :start_nodes (or start_nodes [])
                  :relation_types (or relation_types [])
                  :resolved_only (when (some? resolved_only) (str resolved_only))
                  :budgets_json (json-string budgets)
                  :snapshot_id snapshot_id
                  :language_policy_json (json-string language_policy)}))

(defn traverse-relations-request->map [message]
  {:root_path (not-empty (string-field :traverse-relations-request message :root_path))
   :paths (not-empty (repeated-string-field :traverse-relations-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :traverse-relations-request message :parser_opts_json))
   :direction (not-empty (string-field :traverse-relations-request message :direction))
   :start_nodes (not-empty (repeated-string-field :traverse-relations-request message :start_nodes))
   :relation_types (not-empty (repeated-string-field :traverse-relations-request message :relation_types))
   :resolved_only (let [raw (some-> (string-field :traverse-relations-request message :resolved_only) not-empty)]
                    (when raw
                      (= "true" raw)))
   :budgets (json-field "budgets_json"
                        (string-field :traverse-relations-request message :budgets_json))
   :snapshot_id (not-empty (string-field :traverse-relations-request message :snapshot_id))
   :language_policy (json-field "language_policy_json"
                                (string-field :traverse-relations-request message :language_policy_json))})

(defn traverse-relations-response [{:keys [traverse_relations_result] :as payload}]
  (build-message :traverse-relations-response
                 {:traverse_relations_result_json (json-string (or traverse_relations_result payload))}))

(defn traverse-relations-response->map [message]
  (or (json-field "traverse_relations_result_json"
                  (string-field :traverse-relations-response message :traverse_relations_result_json))
      {}))
