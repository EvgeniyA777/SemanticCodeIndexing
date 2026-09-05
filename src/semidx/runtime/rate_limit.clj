(ns semidx.runtime.rate-limit
  (:require [clojure.string :as str]))

(def ^:private default-window-ms 60000)
(def ^:private default-max-subjects 10000)

(def ^:private supported-subject-scopes #{"tenant" "tenant_actor"})

(defn- positive-long [field value]
  (let [value* (cond
                 (integer? value) (long value)
                 (string? value) (parse-long value)
                 :else nil)]
    (when-not (and value* (pos? value*))
      (throw (ex-info (str (name field) " must be a positive integer")
                      {:type :invalid_request
                       :details {:field (name field)}})))
    value*))

(defn normalize-config [config]
  (if-not (and (map? config) (some? (:requests_per_window config)))
    {:enabled false}
    (let [subject-scope (or (some-> (:subject_scope config) str)
                            "tenant_actor")]
      (when-not (contains? supported-subject-scopes subject-scope)
        (throw (ex-info
                "subject_scope must be tenant or tenant_actor"
                {:type :invalid_request
                 :details {:field "subject_scope"
                           :supported_values (sort supported-subject-scopes)}})))
      {:enabled true
       :requests_per_window (positive-long :requests_per_window
                                           (:requests_per_window config))
       :window_ms (positive-long :window_ms
                                 (or (:window_ms config) default-window-ms))
       :max_subjects (positive-long :max_subjects
                                    (or (:max_subjects config)
                                        default-max-subjects))
       :subject_scope subject-scope})))

(defn- monotonic-ms []
  (quot (System/nanoTime) 1000000))

(defn limiter
  ([config]
   (limiter config monotonic-ms))
  ([config now-ms]
   {:config (normalize-config config)
    :state (atom {})
    :now_ms now-ms}))

(defn enabled? [limiter*]
  (true? (get-in limiter* [:config :enabled])))

(defn- subject-part [value fallback]
  (if (str/blank? (str (or value ""))) fallback (str value)))

(defn subject-key
  ([subject-context]
   (subject-key subject-context "tenant_actor"))
  ([{:keys [tenant_id actor_id]} subject-scope]
   (let [tenant (subject-part tenant_id "anonymous-tenant")]
     (case subject-scope
       "tenant" [tenant]
       "tenant_actor" [tenant
                       (subject-part actor_id "anonymous-actor")]))))

(defn- evict-for-subject [state subject now-ms max-subjects]
  (if (or (contains? state subject) (< (count state) max-subjects))
    state
    (let [without-expired (into {}
                                (remove (fn [[_ {:keys [window_end_ms]}]]
                                          (<= window_end_ms now-ms)))
                                state)]
      (if (< (count without-expired) max-subjects)
        without-expired
        (dissoc without-expired
                (->> without-expired
                     (apply min-key (comp :window_end_ms val))
                     key))))))

(defn check! [limiter* subject-context]
  (if-not (enabled? limiter*)
    {:allowed? true :enabled? false}
    (let [{:keys [requests_per_window window_ms max_subjects subject_scope]}
          (:config limiter*)
          state (:state limiter*)
          now-ms (long ((:now_ms limiter*)))
          subject (subject-key subject-context subject_scope)]
      ;; INVARIANT: `state` is an atom used as a plain mutable box guarded by
      ;; this monitor, not through CAS. Every read-modify-write of the limiter
      ;; state MUST go through `(locking state ...)`; a bare swap!/reset!
      ;; elsewhere would race with this critical section. The single monitor
      ;; also serialises all subjects, acceptable only because the limiter is
      ;; default-off defence-in-depth (ingress owns distributed quotas).
      (locking state
        (let [state* (evict-for-subject @state subject now-ms max_subjects)
              entry (get state* subject)
              fresh-window? (or (nil? entry)
                                (<= (:window_end_ms entry) now-ms))
              entry* (if fresh-window?
                       {:count 0 :window_end_ms (+ now-ms window_ms)}
                       entry)
              allowed? (< (:count entry*) requests_per_window)
              next-entry (if allowed? (update entry* :count inc) entry*)
              retry-after-seconds (when-not allowed?
                                    (max 1
                                         (long (Math/ceil
                                                (/ (- (:window_end_ms entry*) now-ms)
                                                   1000.0)))))]
          (reset! state (assoc state* subject next-entry))
          (cond-> {:allowed? allowed?
                   :enabled? true
                   :limit requests_per_window
                   :remaining (max 0 (- requests_per_window (:count next-entry)))
                   :window_ms window_ms
                   :subject_scope subject_scope}
            retry-after-seconds
            (assoc :retry_after_seconds retry-after-seconds)))))))

(defn rejection-exception [decision]
  (ex-info "runtime rate limit exceeded"
           {:type :rate_limited
            :details (select-keys decision
                                  [:limit :remaining :window_ms
                                   :retry_after_seconds :subject_scope])}))

(defn decision-event [surface operation correlation decision]
  (let [allowed? (:allowed? decision)]
    (merge {:surface surface
            :operation "rate_limit_decision"
            :status (if allowed? "success" "error")
            :result_status (if allowed? "allowed" "rejected")
            :payload (merge {:decision (if allowed? "allowed" "rejected")
                             :limited_operation (some-> operation name)}
                            (select-keys decision
                                         [:limit :remaining :window_ms
                                          :retry_after_seconds :subject_scope]))}
           (select-keys correlation
                        [:tenant_id :actor_id :trace_id :request_id
                         :session_id :task_id]))))
