(ns semidx.runtime.grpc-proto
  (:require [clojure.data.json :as json])
  (:import [com.google.protobuf DynamicMessage
                                DescriptorProtos$DescriptorProto
                                DescriptorProtos$FieldDescriptorProto
                                DescriptorProtos$FieldDescriptorProto$Label
                                DescriptorProtos$FieldDescriptorProto$Type
                                DescriptorProtos$FileDescriptorProto
                                Descriptors$Descriptor
                                Descriptors$FieldDescriptor
                                Descriptors$FileDescriptor]
           [io.grpc.protobuf ProtoUtils]))

(def ^:private package-name "semidx.runtime.grpc.v1")

(def ^:private message-definitions
  {:health-request
   {:proto-name "HealthRequest"
    :fields []}

   :health-response
   {:proto-name "HealthResponse"
    :fields [{:key :status :proto-name "status" :number 1 :type :string}
             {:key :service :proto-name "service" :number 2 :type :string}]}

   :create-index-request
   {:proto-name "CreateIndexRequest"
    :fields [{:key :root_path :proto-name "root_path" :number 1 :type :string}
             {:key :paths :proto-name "paths" :number 2 :type :string :repeated? true}
             {:key :parser_opts_json :proto-name "parser_opts_json" :number 3 :type :string}
             {:key :language_policy_json :proto-name "language_policy_json" :number 4 :type :string}]}

   :create-index-response
   {:proto-name "CreateIndexResponse"
    :fields [{:key :snapshot_id :proto-name "snapshot_id" :number 1 :type :string}
             {:key :indexed_at :proto-name "indexed_at" :number 2 :type :string}
             {:key :file_count :proto-name "file_count" :number 3 :type :int32}
             {:key :unit_count :proto-name "unit_count" :number 4 :type :int32}
             {:key :repo_map_json :proto-name "repo_map_json" :number 5 :type :string}
             {:key :index_lifecycle_json :proto-name "index_lifecycle_json" :number 6 :type :string}]}

   :resolve-context-request
   {:proto-name "ResolveContextRequest"
    :fields [{:key :root_path :proto-name "root_path" :number 1 :type :string}
             {:key :paths :proto-name "paths" :number 2 :type :string :repeated? true}
             {:key :parser_opts_json :proto-name "parser_opts_json" :number 3 :type :string}
             {:key :query_json :proto-name "query_json" :number 4 :type :string}
             {:key :retrieval_policy_json :proto-name "retrieval_policy_json" :number 5 :type :string}
             {:key :language_policy_json :proto-name "language_policy_json" :number 6 :type :string}]}

   :resolve-context-response
   {:proto-name "ResolveContextResponse"
    :fields [{:key :selection_result_json :proto-name "selection_result_json" :number 1 :type :string}]}

   :expand-context-request
    {:proto-name "ExpandContextRequest"
     :fields [{:key :root_path :proto-name "root_path" :number 1 :type :string}
              {:key :paths :proto-name "paths" :number 2 :type :string :repeated? true}
              {:key :parser_opts_json :proto-name "parser_opts_json" :number 3 :type :string}
              {:key :selection_id :proto-name "selection_id" :number 4 :type :string}
              {:key :snapshot_id :proto-name "snapshot_id" :number 5 :type :string}
              {:key :unit_ids :proto-name "unit_ids" :number 6 :type :string :repeated? true}
              {:key :include_impact_hints :proto-name "include_impact_hints" :number 7 :type :string}
              {:key :language_policy_json :proto-name "language_policy_json" :number 8 :type :string}]}

   :expand-context-response
   {:proto-name "ExpandContextResponse"
    :fields [{:key :expansion_result_json :proto-name "expansion_result_json" :number 1 :type :string}]}

   :fetch-context-detail-request
    {:proto-name "FetchContextDetailRequest"
     :fields [{:key :root_path :proto-name "root_path" :number 1 :type :string}
              {:key :paths :proto-name "paths" :number 2 :type :string :repeated? true}
              {:key :parser_opts_json :proto-name "parser_opts_json" :number 3 :type :string}
              {:key :selection_id :proto-name "selection_id" :number 4 :type :string}
              {:key :snapshot_id :proto-name "snapshot_id" :number 5 :type :string}
              {:key :unit_ids :proto-name "unit_ids" :number 6 :type :string :repeated? true}
              {:key :detail_level :proto-name "detail_level" :number 7 :type :string}
              {:key :language_policy_json :proto-name "language_policy_json" :number 8 :type :string}]}

   :fetch-context-detail-response
   {:proto-name "FetchContextDetailResponse"
    :fields [{:key :detail_result_json :proto-name "detail_result_json" :number 1 :type :string}]}

   :literal-file-slice-request
   {:proto-name "LiteralFileSliceRequest"
    :fields [{:key :root_path :proto-name "root_path" :number 1 :type :string}
             {:key :paths :proto-name "paths" :number 2 :type :string :repeated? true}
             {:key :parser_opts_json :proto-name "parser_opts_json" :number 3 :type :string}
             {:key :selection_id :proto-name "selection_id" :number 4 :type :string}
             {:key :snapshot_id :proto-name "snapshot_id" :number 5 :type :string}
             {:key :path :proto-name "path" :number 6 :type :string}
             {:key :start_line :proto-name "start_line" :number 7 :type :int32}
             {:key :end_line :proto-name "end_line" :number 8 :type :int32}
             {:key :language_policy_json :proto-name "language_policy_json" :number 9 :type :string}]}

   :literal-file-slice-response
   {:proto-name "LiteralFileSliceResponse"
    :fields [{:key :literal_slice_result_json :proto-name "literal_slice_result_json" :number 1 :type :string}]}})

(defn- require-definition [message-key]
  (or (get message-definitions message-key)
      (throw (ex-info (str "unknown gRPC proto message " message-key)
                      {:message_key message-key}))))

(defn- field-type-enum [field-type]
  (case field-type
    :string DescriptorProtos$FieldDescriptorProto$Type/TYPE_STRING
    :int32 DescriptorProtos$FieldDescriptorProto$Type/TYPE_INT32
    (throw (ex-info (str "unsupported field type " field-type)
                    {:field_type field-type}))))

(defn- field-proto [{:keys [proto-name number type repeated?]}]
  (-> (DescriptorProtos$FieldDescriptorProto/newBuilder)
      (.setName proto-name)
      (.setNumber (int number))
      (.setType (field-type-enum type))
      (.setLabel (if repeated?
                   DescriptorProtos$FieldDescriptorProto$Label/LABEL_REPEATED
                   DescriptorProtos$FieldDescriptorProto$Label/LABEL_OPTIONAL))
      (.build)))

(defn- message-proto [{:keys [proto-name fields]}]
  (let [builder (DescriptorProtos$DescriptorProto/newBuilder)]
    (.setName builder proto-name)
    (doseq [field fields]
      (.addField builder (field-proto field)))
    (.build builder)))

(def ^:private file-descriptor
  (let [builder (DescriptorProtos$FileDescriptorProto/newBuilder)]
    (.setName builder "proto/semidx/runtime/grpc/v1/runtime.proto")
    (.setPackage builder package-name)
    (.setSyntax builder "proto3")
    (doseq [[_ definition] message-definitions]
      (.addMessageType builder (message-proto definition)))
    (Descriptors$FileDescriptor/buildFrom
     (.build builder)
     (make-array Descriptors$FileDescriptor 0))))

(def ^:private descriptors
  (into {}
        (map (fn [[message-key {:keys [proto-name]}]]
               [message-key (.findMessageTypeByName file-descriptor proto-name)]))
        message-definitions))

(def ^:private field-descriptors
  (into {}
        (map (fn [[message-key {:keys [fields]}]]
               [message-key
                (into {}
                      (map (fn [{:keys [key proto-name]}]
                             [key (.findFieldByName ^Descriptors$Descriptor
                                                    (get descriptors message-key)
                                                    proto-name)]))
                      fields)]))
        message-definitions))

(defn default-instance [message-key]
  (DynamicMessage/getDefaultInstance ^Descriptors$Descriptor (get descriptors message-key)))

(defn marshaller [message-key]
  (ProtoUtils/marshaller (default-instance message-key)))

(defn- coerce-field-value [field-type value]
  (case field-type
    :string (str value)
    :int32 (int (long (or value 0)))
    value))

(defn- build-message [message-key values]
  (let [definition (require-definition message-key)
        descriptor ^Descriptors$Descriptor (get descriptors message-key)
        builder (DynamicMessage/newBuilder descriptor)]
    (doseq [{:keys [key type repeated?]} (:fields definition)]
      (let [field-desc ^Descriptors$FieldDescriptor (get-in field-descriptors [message-key key])
            value (get values key)]
        (cond
          repeated?
          (doseq [item (or value [])]
            (.addRepeatedField builder field-desc (coerce-field-value type item)))

          (some? value)
          (.setField builder field-desc (coerce-field-value type value)))))
    (.build builder)))

(defn- string-field [message-key message field-key]
  (str (.getField ^DynamicMessage message (get-in field-descriptors [message-key field-key]))))

(defn- int-field [message-key message field-key]
  (int (.getField ^DynamicMessage message (get-in field-descriptors [message-key field-key]))))

(defn- repeated-string-field [message-key message field-key]
  (->> (.getField ^DynamicMessage message (get-in field-descriptors [message-key field-key]))
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

(defn health-response [{:keys [status service]}]
  (build-message :health-response
                 {:status status
                  :service service}))

(defn health-response->map [message]
  {:status (string-field :health-response message :status)
   :service (string-field :health-response message :service)})

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
