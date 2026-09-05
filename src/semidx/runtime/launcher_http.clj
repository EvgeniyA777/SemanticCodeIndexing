(ns semidx.runtime.launcher-http
  "Health and request clients for the `runtime-http` launcher profile.

  Knows about endpoint shape, timeouts, and the optional local API key header.
  Does not know about lock files, state mutation, or process startup. Request
  forwarding preserves the existing runtime HTTP request and response bodies."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient
            HttpRequest
            HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def default-health-timeout-ms 2000)

(def default-request-timeout-ms 120000)

(def resolve-context-path "/v1/retrieval/resolve-context")

(def fetch-context-detail-path "/v1/retrieval/fetch-context-detail")

(def health-path "/health")

(defn base-url
  [{:keys [host port]}]
  (str "http://" (or host "127.0.0.1") ":" port))

(defn- auth-headers
  [{:keys [api_key]}]
  (if-let [api-key (some-> api_key str str/trim not-empty)]
    {"x-api-key" api-key}
    {}))

(defn- http-client
  ^HttpClient [timeout-ms]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis (long timeout-ms)))
      (.build)))

(defn- send-request
  "Send one HTTP request and decode the JSON body.

  Returns `{:status .. :json ..}` on a completed exchange, or `{:error ..}` when
  the endpoint could not be reached. Transport failures are reported, never
  swallowed."
  [{:keys [method url body headers timeout_ms]}]
  (let [timeout (long (or timeout_ms default-health-timeout-ms))
        publisher (if (some? body)
                    (HttpRequest$BodyPublishers/ofString body)
                    (HttpRequest$BodyPublishers/noBody))
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout))
                    (.header "Content-Type" "application/json")
                    (#(reduce (fn [builder [header-name header-value]]
                                (.header builder (str header-name) (str header-value)))
                              %
                              headers))
                    (.method method publisher)
                    (.build))]
    (try
      (let [response (.send (http-client timeout) request (HttpResponse$BodyHandlers/ofString))
            text (.body response)]
        {:status (.statusCode response)
         :body text
         :json (when (seq text)
                 (try
                   (json/read-str text :key-fn keyword)
                   (catch Exception e
                     {:parse_error (.getMessage e)})))})
      (catch Exception e
        {:error (or (.getMessage e) (str (class e)))
         :error_class (.getName (class e))}))))

(defn check-health
  "Observe runtime health for the launcher decision kernel.

  The returned map is consumed by `semidx.runtime.launcher/decide-runtime-reuse`
  as a health observation."
  ([runtime] (check-health runtime {}))
  ([runtime opts]
   (let [response (send-request {:method "GET"
                                 :url (str (base-url runtime) health-path)
                                 :headers (auth-headers opts)
                                 :timeout_ms (or (:timeout_ms opts) default-health-timeout-ms)})]
     (if (:error response)
       {:healthy false
        :error (:error response)
        :error_class (:error_class response)}
       {:healthy (and (= 200 (:status response))
                      (= "ok" (get-in response [:json :status])))
        :status_code (:status response)
        :service (get-in response [:json :service])
        :json (:json response)}))))

(defn- post-json
  [runtime path payload opts]
  (send-request {:method "POST"
                 :url (str (base-url runtime) path)
                 :body (json/write-str payload :escape-slash false)
                 :headers (auth-headers opts)
                 :timeout_ms (or (:timeout_ms opts) default-request-timeout-ms)}))

(defn- stage-failure
  [stage response]
  {:ok? false
   :stage stage
   :status (:status response)
   :error (:error response)
   :error_class (:error_class response)
   :body (:json response)})

(defn resolve-context-detail
  "Forward a one-shot retrieval query to a long-lived runtime HTTP server.

  Mirrors `semidx.core/resolve-context-detail`: resolve a compact selection,
  then fetch detail for it. Request and response bodies stay exactly as the
  runtime HTTP contract defines them."
  ([runtime request] (resolve-context-detail runtime request {}))
  ([runtime {:keys [root_path query paths]} opts]
   (let [base (cond-> {:root_path root_path}
                (seq paths) (assoc :paths paths))
         selection (post-json runtime resolve-context-path (assoc base :query query) opts)]
     (if (not= 200 (:status selection))
       (stage-failure "resolve_context" selection)
       (let [selection-body (:json selection)
             detail (post-json runtime
                               fetch-context-detail-path
                               (assoc base
                                      :selection_id (:selection_id selection-body)
                                      :snapshot_id (:snapshot_id selection-body))
                               opts)]
         (if (not= 200 (:status detail))
           (stage-failure "fetch_context_detail" detail)
           {:ok? true
            :selection_id (:selection_id selection-body)
            :snapshot_id (:snapshot_id selection-body)
            :result (:json detail)}))))))
