(ns semidx.runtime.errors
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
   :rate_limited {:error_code "rate_limited"
                  :error_category "capacity"
                  :http_status 429
                  :grpc_status Status/RESOURCE_EXHAUSTED}
   :internal_error {:error_code "internal_error"
                    :error_category "internal"
                    :http_status 500
                    :grpc_status Status/INTERNAL}
   :policy_not_found {:error_code "policy_not_found"
                      :error_category "not_found"
                      :http_status 404
                      :grpc_status Status/NOT_FOUND}
   :policy_not_eligible {:error_code "policy_not_eligible"
                         :error_category "conflict"
                         :http_status 409
                         :grpc_status Status/FAILED_PRECONDITION}
   :policy_blocked {:error_code "policy_blocked"
                    :error_category "conflict"
                    :http_status 409
                    :grpc_status Status/FAILED_PRECONDITION}
   :stale_promotion_decision {:error_code "stale_promotion_decision"
                              :error_category "conflict"
                              :http_status 409
                              :grpc_status Status/FAILED_PRECONDITION}
   :policy_approval_required {:error_code "policy_approval_required"
                              :error_category "conflict"
                              :http_status 409
                              :grpc_status Status/FAILED_PRECONDITION}
   :policy_registry_not_configured {:error_code "policy_registry_not_configured"
                                    :error_category "not_found"
                                    :http_status 404
                                    :grpc_status Status/NOT_FOUND}
   :registry_persistence_failed {:error_code "registry_persistence_failed"
                                 :error_category "internal"
                                 :http_status 500
                                 :grpc_status Status/INTERNAL}})

(def ^:private grpc-error-code-key
  (Metadata$Key/of "x-sci-error-code" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private grpc-error-category-key
  (Metadata$Key/of "x-sci-error-category" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private grpc-retry-after-key
  (Metadata$Key/of "x-sci-retry-after-seconds" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private grpc-activation-started-key
  (Metadata$Key/of "x-sci-activation-started-at" Metadata/ASCII_STRING_MARSHALLER))
(def ^:private grpc-recommended-action-key
  (Metadata$Key/of "x-sci-recommended-action" Metadata/ASCII_STRING_MARSHALLER))

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

(defn http-error-headers [e]
  (let [info (error-info e)
        retry-after (get-in info [:details :retry_after_seconds])]
    (cond-> {}
      (some? retry-after) (assoc "Retry-After" (str retry-after)))))

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
    (when-let [retry-after (get-in info [:details :retry_after_seconds])]
      (.put metadata grpc-retry-after-key (str retry-after)))
    (when-let [activation-started-at (get-in info [:details :activation_started_at])]
      (.put metadata grpc-activation-started-key (str activation-started-at)))
    (when-let [recommended-action (get-in info [:details :recommended_action])]
      (.put metadata grpc-recommended-action-key (str recommended-action)))
    metadata))

(defn mcp-error-details [e]
  (let [info (error-info e)]
    (cond-> {:code (:error_code info)
             :category (:error_category info)}
      (:details info) (assoc :details (:details info))
      (:errors info) (assoc :errors (:errors info)))))
