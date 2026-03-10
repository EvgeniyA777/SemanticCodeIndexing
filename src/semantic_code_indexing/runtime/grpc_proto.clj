(ns semantic-code-indexing.runtime.grpc-proto
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

(def ^:private package-name "semantic_code_indexing.runtime.grpc.v1")

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
             {:key :parser_opts_json :proto-name "parser_opts_json" :number 3 :type :string}]}

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
             {:key :retrieval_policy_json :proto-name "retrieval_policy_json" :number 5 :type :string}]}

   :resolve-context-response
   {:proto-name "ResolveContextResponse"
    :fields [{:key :context_packet_json :proto-name "context_packet_json" :number 1 :type :string}
             {:key :guardrail_assessment_json :proto-name "guardrail_assessment_json" :number 2 :type :string}
             {:key :diagnostics_trace_json :proto-name "diagnostics_trace_json" :number 3 :type :string}
             {:key :stage_events_json :proto-name "stage_events_json" :number 4 :type :string}]}})

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
    (.setName builder "proto/semantic_code_indexing/runtime/grpc/v1/runtime.proto")
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

(defn create-index-request [{:keys [root_path paths parser_opts]}]
  (build-message :create-index-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)}))

(defn create-index-request->map [message]
  {:root_path (not-empty (string-field :create-index-request message :root_path))
   :paths (not-empty (repeated-string-field :create-index-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :create-index-request message :parser_opts_json))})

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

(defn resolve-context-request [{:keys [root_path paths parser_opts query retrieval_policy]}]
  (build-message :resolve-context-request
                 {:root_path root_path
                  :paths (or paths [])
                  :parser_opts_json (json-string parser_opts)
                  :query_json (json-string query)
                  :retrieval_policy_json (json-string retrieval_policy)}))

(defn resolve-context-request->map [message]
  {:root_path (not-empty (string-field :resolve-context-request message :root_path))
   :paths (not-empty (repeated-string-field :resolve-context-request message :paths))
   :parser_opts (json-field "parser_opts_json"
                            (string-field :resolve-context-request message :parser_opts_json))
   :query (json-field "query_json"
                      (string-field :resolve-context-request message :query_json))
   :retrieval_policy (json-field "retrieval_policy_json"
                                 (string-field :resolve-context-request message :retrieval_policy_json))})

(defn resolve-context-response [{:keys [context_packet guardrail_assessment diagnostics_trace stage_events]}]
  (build-message :resolve-context-response
                 {:context_packet_json (json-string context_packet)
                  :guardrail_assessment_json (json-string guardrail_assessment)
                  :diagnostics_trace_json (json-string diagnostics_trace)
                  :stage_events_json (json-string stage_events)}))

(defn resolve-context-response->map [message]
  {:context_packet (or (json-field "context_packet_json"
                                   (string-field :resolve-context-response message :context_packet_json))
                       {})
   :guardrail_assessment (or (json-field "guardrail_assessment_json"
                                         (string-field :resolve-context-response message :guardrail_assessment_json))
                             {})
   :diagnostics_trace (or (json-field "diagnostics_trace_json"
                                      (string-field :resolve-context-response message :diagnostics_trace_json))
                          {})
   :stage_events (or (json-field "stage_events_json"
                                 (string-field :resolve-context-response message :stage_events_json))
                     [])})
