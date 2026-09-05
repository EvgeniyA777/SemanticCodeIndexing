(ns semidx.runtime.launcher
  (:require [clojure.string :as str]
            [semidx.runtime.language-activation :as activation]
            [semidx.runtime.repo-identity :as repo-identity]))

(def current-state-schema-version "1")

(def supported-profiles #{"runtime-http" "mcp-http"})

(def default-profile "runtime-http")

(def default-host "127.0.0.1")

(def default-ports
  {"runtime-http" 8787
   "mcp-http" 8791})

(def ^:private missing ::missing)

(defn- trim-to-nil [value]
  (let [value* (some-> value str str/trim)]
    (when (seq value*)
      value*)))

(defn- field
  [m k]
  (let [n (name k)
        dash (str/replace n "_" "-")
        under (str/replace n "-" "_")
        variants [k
                  (keyword n)
                  (keyword dash)
                  (keyword under)
                  n
                  dash
                  under]]
    (reduce (fn [_ candidate]
              (if (contains? m candidate)
                (reduced (get m candidate))
                missing))
            missing
            variants)))

(defn- present-field
  [m k]
  (let [value (field m k)]
    (when-not (= missing value)
      value)))

(defn- parse-long-safe
  [value]
  (cond
    (nil? value) nil
    (integer? value) (long value)
    (number? value) (long value)
    :else (try
            (Long/parseLong (str/trim (str value)))
            (catch Exception _ nil))))

(defn normalize-profile
  [profile]
  (let [profile* (or (some-> profile name str/lower-case trim-to-nil)
                     default-profile)]
    (if (contains? supported-profiles profile*)
      profile*
      (throw (ex-info "unsupported runtime launcher profile"
                      {:type :invalid_launcher_profile
                       :profile profile*
                       :supported_profiles (sort supported-profiles)})))))

(defn default-port
  [profile]
  (get default-ports (normalize-profile profile)))

(defn desired-runtime
  ([root-path]
   (desired-runtime root-path {}))
  ([root-path opts]
   (let [profile (normalize-profile (:profile opts))
         identity (or (:repo_identity opts)
                      (:repo-identity opts)
                      (repo-identity/resolve-repo-identity root-path))
         root-path* (or (:workspace_path identity)
                        (activation/canonical-root-path (or root-path ".")))]
     {:schema_version current-state-schema-version
      :root_path root-path*
      :repo_key (:repo_key identity)
      :workspace_key (:workspace_key identity)
      :profile profile
      :host (or (trim-to-nil (:host opts)) default-host)
      :port (or (parse-long-safe (:port opts)) (default-port profile))})))

(defn normalize-desired-runtime
  [desired]
  (let [profile (normalize-profile (:profile desired))]
    {:schema_version (or (some-> (:schema_version desired) str)
                         current-state-schema-version)
     :root_path (or (trim-to-nil (:root_path desired)) ".")
     :repo_key (trim-to-nil (:repo_key desired))
     :workspace_key (trim-to-nil (:workspace_key desired))
     :profile profile
     :host (or (trim-to-nil (:host desired)) default-host)
     :port (or (parse-long-safe (:port desired)) (default-port profile))}))

(defn runtime-slot-key
  "Stable directory-safe key for one project/profile launcher slot.

  Workspace identity is preferred so two checkouts of the same repository keep
  independent local runtimes."
  [desired]
  (let [desired* (normalize-desired-runtime desired)]
    (str (or (:workspace_key desired*)
             (:repo_key desired*)
             "unknown-workspace")
         "-"
         (:profile desired*))))

(defn normalize-runtime-state
  [state]
  (when state
    (let [profile (normalize-profile (present-field state :profile))]
      (cond-> {:schema_version (or (some-> (present-field state :schema_version) str)
                                   current-state-schema-version)
               :root_path (trim-to-nil (present-field state :root_path))
               :repo_key (trim-to-nil (present-field state :repo_key))
               :workspace_key (trim-to-nil (present-field state :workspace_key))
               :profile profile
               :host (or (trim-to-nil (present-field state :host)) default-host)
               :port (or (parse-long-safe (present-field state :port))
                         (default-port profile))
               :pid (parse-long-safe (present-field state :pid))
               :started_at (trim-to-nil (present-field state :started_at))
               :last_health_at (trim-to-nil (present-field state :last_health_at))}
        (present-field state :auth_token_ref)
        (assoc :auth_token_ref (trim-to-nil (present-field state :auth_token_ref)))

        (not= missing (field state :owned))
        (assoc :owned (boolean (present-field state :owned)))))))

(def profile-services
  "The `service` identifier each profile's server reports on `/health`.

  Both local servers answer `GET /health` with `status: \"ok\"`, so liveness
  alone cannot tell them apart. Without this map a launcher asking for one
  profile would happily adopt the other profile's server listening on the
  requested port."
  {"runtime-http" "semidx-runtime-http"
   "mcp-http" "semidx-mcp-http"})

(defn healthy-observation?
  [health]
  (boolean
   (or (true? (:healthy health))
       (= "ok" (:status health))
       (= "ok" (:body_status health))
       (= "ok" (get-in health [:json :status]))
       (= 200 (:status health))
       (= 200 (:status_code health)))))

(defn- health-root-path
  [health]
  (or (:root_path health)
      (get-in health [:project_context :root_path])
      (get-in health [:json :project_context :root_path])
      (get-in health [:json :root_path])))

(defn- health-repo-key
  [health]
  (or (:repo_key health)
      (get-in health [:repo_identity :repo_key])
      (get-in health [:json :repo_identity :repo_key])
      (get-in health [:index_lifecycle :repo_identity :repo_key])
      (get-in health [:json :index_lifecycle :repo_identity :repo_key])))

(defn- same-nonblank?
  [a b]
  (and (seq (str a))
       (seq (str b))
       (= (str a) (str b))))

(defn- health-matches-desired?
  [desired health]
  (and (healthy-observation? health)
       (let [observed-root (health-root-path health)
             observed-repo-key (health-repo-key health)]
         (and (or (nil? observed-root)
                  (same-nonblank? (:root_path desired) observed-root))
              (or (nil? observed-repo-key)
                  (nil? (:repo_key desired))
                  (same-nonblank? (:repo_key desired) observed-repo-key))))))

(defn- health-root-mismatch?
  [desired health]
  (let [observed-root (health-root-path health)]
    (and (healthy-observation? health)
         (some? observed-root)
         (not (same-nonblank? (:root_path desired) observed-root)))))

(defn- health-repo-key-mismatch?
  [desired health]
  (let [observed-repo-key (health-repo-key health)]
    (and (healthy-observation? health)
         (seq (:repo_key desired))
         (some? observed-repo-key)
         (not (same-nonblank? (:repo_key desired) observed-repo-key)))))

(defn health-service
  "Service identifier reported by an observed health response, if any."
  [health]
  (or (:service health)
      (get-in health [:json :service])))

(defn- health-service-mismatch?
  "True when the endpoint is healthy but is serving a different profile.

  An unknown or absent service is not treated as a mismatch: an older server
  that does not report one must stay adoptable."
  [desired health]
  (let [observed (trim-to-nil (health-service health))
        expected (trim-to-nil (get profile-services (:profile desired)))]
    (and (healthy-observation? health)
         (some? observed)
         (some? expected)
         (not= observed expected))))

(defn- contended-lock?
  [lock]
  (contains? #{:contended "contended" :held-by-other "held-by-other" :blocked "blocked"}
             lock))

(defn- state-root-mismatch?
  [desired state]
  (and (seq (:root_path state))
       (not= (:root_path desired) (:root_path state))))

(defn- state-profile-mismatch?
  [desired state]
  (not= (:profile desired) (:profile state)))

(defn- state-endpoint-mismatch?
  [desired state]
  (or (not= (:host desired) (:host state))
      (not= (:port desired) (:port state))))

(defn- decision
  [kind reason desired state extra]
  (merge {:decision kind
          :reason reason
          :root_path (:root_path desired)
          :repo_key (:repo_key desired)
          :profile (:profile desired)
          :host (:host desired)
          :port (:port desired)}
         (when (:pid state)
           {:pid (:pid state)})
         extra))

(defn decide-runtime-reuse
  "Pure decision kernel for local runtime reuse.

  Inputs are observations supplied by the future launcher:

  - `desired`: target runtime map, usually from `desired-runtime`.
  - `state`: persisted launcher state, if any.
  - `health`: observed health response for the recorded endpoint, if any.
  - `process`: optional liveness observation, e.g. `{:pid_alive false}` or
    `{:port_open false}`.
  - `lock`: optional lock observation, e.g. `:contended`.

  The function does not start, stop, clean up, or perform I/O."
  [{:keys [desired state health process lock]}]
  (let [desired* (normalize-desired-runtime desired)
        state* (normalize-runtime-state state)]
    (cond
      (nil? state*)
      (if (contended-lock? lock)
        (decision :blocked :start_lock_contended desired* nil {})
        (decision :started :no_state desired* nil {}))

      (state-root-mismatch? desired* state*)
      (decision :blocked :state_root_mismatch desired* state*
                {:state_root_path (:root_path state*)})

      (health-root-mismatch? desired* health)
      (decision :blocked :health_root_mismatch desired* state*
                {:health_root_path (health-root-path health)})

      (health-repo-key-mismatch? desired* health)
      (decision :blocked :health_repo_key_mismatch desired* state*
                {:health_repo_key (health-repo-key health)})

      (health-service-mismatch? desired* health)
      (decision :blocked :health_service_mismatch desired* state*
                {:health_service (health-service health)
                 :expected_service (get profile-services (:profile desired*))})

      (and (health-matches-desired? desired* health)
           (not (state-profile-mismatch? desired* state*))
           (not (state-endpoint-mismatch? desired* state*)))
      (decision :reused :healthy_runtime desired* state* {})

      (contended-lock? lock)
      (decision :blocked :start_lock_contended desired* state* {})

      (state-profile-mismatch? desired* state*)
      (decision :stale-cleaned :profile_mismatch desired* state*
                {:state_profile (:profile state*)})

      (state-endpoint-mismatch? desired* state*)
      (decision :stale-cleaned :endpoint_mismatch desired* state*
                {:state_host (:host state*)
                 :state_port (:port state*)})

      (false? (:pid_alive process))
      (decision :stale-cleaned :pid_not_alive desired* state* {})

      (false? (:port_open process))
      (decision :stale-cleaned :port_not_open desired* state* {})

      (and health (not (healthy-observation? health)))
      (decision :stale-cleaned :health_check_failed desired* state* {})

      :else
      (decision :started :no_healthy_runtime desired* state* {}))))
