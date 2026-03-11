(ns semantic-code-indexing.runtime.errors
  (:import [io.grpc Metadata Metadata$Key Status]))

(def ^:private taxonomy
  {:invalid_request {:error_code "invalid_request"
                     :error_category "client"
                     :http_status 400
                     :grpc_status Status/INVALID_ARGUMENT}
   :unsupported_api_version {:error_code "unsupported_api_version"
                             :error_category "client"
                             :http_status 400
                             :grpc_status Status/INVALID_ARGUMENT}
   :snapshot_mismatch {:error_code "snapshot_mismatch"
                       :error_category "conflict"
                       :http_status 409
                       :grpc_status Status/FAILED_PRECONDITION}
   :invalid_query {:error_code "invalid_query"
                   :error_category "client"
                   :http_status 400
                   :grpc_status Status/INVALID_ARGUMENT}
   :unauthorized {:error_code "unauthorized"
                  :error_category "auth"
                  :http_status 401
                  :grpc_status Status/UNAUTHENTICATED}
   :forbidden {:error_code "forbidden"
               :error_category "auth"
               :http_status 403
               :grpc_status Status/PERMISSION_DENIED}
   :forbidden_root {:error_code "forbidden_root"
                    :error_category "auth"
                    :http_status 403
                    :grpc_status Status/PERMISSION_DENIED}
   :index_not_found {:error_code "index_not_found"
                     :error_category "not_found"
                     :http_status 404
                     :grpc_status Status/NOT_FOUND}
   :selection_not_found {:error_code "selection_not_found"
                         :error_category "not_found"
                         :http_status 404
                         :grpc_status Status/NOT_FOUND}
   :selection_evicted {:error_code "selection_evicted"
                       :error_category "not_found"
                       :http_status 410
                       :grpc_status Status/FAILED_PRECONDITION}
   :protocol_error {:error_code "protocol_error"
                    :error_category "protocol"
                    :http_status 400
                    :grpc_status Status/INVALID_ARGUMENT}
   :internal_contract_error {:error_code "internal_contract_error"
                             :error_category "internal"
                             :http_status 500
                             :grpc_status Status/INTERNAL}
   :invalid_storage_config {:error_code "invalid_storage_config"
                            :error_category "client"
                            :http_status 400
                            :grpc_status Status/INVALID_ARGUMENT}
   :no_supported_languages_found {:error_code "no_supported_languages_found"
                                  :error_category "client"
                                  :http_status 400
                                  :grpc_status Status/INVALID_ARGUMENT}
   :language_refresh_required {:error_code "language_refresh_required"
                               :error_category "conflict"
                               :http_status 409
                               :grpc_status Status/FAILED_PRECONDITION}
   :language_activation_in_progress {:error_code "language_activation_in_progress"
                                     :error_category "conflict"
                                     :http_status 409
                                     :grpc_status Status/FAILED_PRECONDITION}
   :language_policy_blocked {:error_code "language_policy_blocked"
                             :error_category "client"
                             :http_status 400
                             :grpc_status Status/INVALID_ARGUMENT}
   :internal_error {:error_code "internal_error"
                    :error_category "internal"
                    :http_status 500
                    :grpc_status Status/INTERNAL}})

(def ^:private grpc-error-code-key
  (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private grpc-error-category-key
  (Metadata$Key/of "x-sci-error-category" Metadata/ASCII_STRING_MARSHALLER))

(defn error-info
  ([e]
   (error-info e nil))
  ([e overrides]
   (let [data (if (map? e) e (or (ex-data e) {}))
         type-key (or (:type overrides)
                      (:type data)
                      :internal_error)
         taxonomy-entry (get taxonomy type-key (:internal_error taxonomy))
         message (or (:message overrides)
                     (:message data)
                     (when (instance? Throwable e) (.getMessage ^Throwable e))
                     "internal error")
         details (or (:details overrides) (:details data))
         errors (:errors data)]
     (cond-> (merge taxonomy-entry
                    {:type type-key
                     :message message})
       (some? details) (assoc :details details)
       (some? errors) (assoc :errors errors)))))

(defn normalize-exception [e]
  (let [info (error-info e)]
    (if (and (= (:type info) (:type (ex-data e)))
             (= (:error_code info) (:error_code (ex-data e)))
             (= (:error_category info) (:error_category (ex-data e))))
      e
      (ex-info (:message info) info e))))

(defn usage-error-payload [e]
  (let [info (error-info e)]
    (cond-> {:error_class (.getName (class e))
             :error_message (:message info)
             :error_code (:error_code info)
             :error_category (:error_category info)}
      (:details info) (assoc :error_details (:details info))
      (:errors info) (assoc :error_validation (:errors info)))))

(defn http-error-body
  ([e]
   (http-error-body e nil))
  ([e overrides]
   (let [info (error-info e overrides)]
     {:status (:http_status info)
      :body (cond-> {:error (:error_code info)
                     :error_code (:error_code info)
                     :error_category (:error_category info)
                     :message (:message info)}
              (:details info) (assoc :details (:details info))
              (:errors info) (assoc :errors (:errors info)))})))

(defn grpc-status [e]
  (:grpc_status (error-info e)))

(defn grpc-description [e]
  (let [info (error-info e)]
    (str (:error_code info) ": " (:message info))))

(defn grpc-trailers [e]
  (let [info (error-info e)
        metadata (Metadata.)]
    (.put metadata grpc-error-code-key (:error_code info))
    (.put metadata grpc-error-category-key (:error_category info))
    metadata))

(defn mcp-error-details [e]
  (let [info (error-info e)]
    (cond-> {:code (:error_code info)
             :category (:error_category info)}
      (:details info) (assoc :details (:details info))
      (:errors info) (assoc :errors (:errors info)))))
