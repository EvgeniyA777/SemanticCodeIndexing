(ns semidx.runtime.launcher-cli
  "Command orchestration for the local runtime launcher.

  Owns `status`, `start`, `stop`, and `request` on top of the pure decision
  kernel in `semidx.runtime.launcher`. Every side effect goes through an
  injectable role so state transitions stay testable without spawning JVMs.

  This namespace forwards to the existing runtime HTTP contract. It is not a
  second retrieval API and must not reshape ContextPacket or retrieval results."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.launcher :as launcher]
            [semidx.runtime.launcher-http :as launcher-http]
            [semidx.runtime.launcher-process :as launcher-process]
            [semidx.runtime.launcher-state :as launcher-state])
  (:import [java.time Instant]))

(def default-start-timeout-ms 90000)

(def default-start-poll-interval-ms 250)

(def ok-decisions #{:reused :started :stale-cleaned :stopped :noop})

(defn default-deps
  "Production roles: real state store, health client, request client, and
  process runner."
  []
  {:home (launcher-state/launcher-home)
   :read-state launcher-state/read-state
   :write-state! launcher-state/write-state!
   :clear-state! launcher-state/clear-state!
   :with-start-lock launcher-state/with-start-lock
   :state-file launcher-state/state-file
   :log-file launcher-state/log-file
   :check-health launcher-http/check-health
   :send-request launcher-http/resolve-context-detail
   :pid-alive? launcher-process/pid-alive?
   :port-open? launcher-process/port-open?
   :start-process! launcher-process/start-runtime!
   :stop-process! launcher-process/stop-runtime!
   :now (fn [] (str (Instant/now)))
   :now-ms (fn [] (System/currentTimeMillis))
   :sleep (fn [ms] (Thread/sleep (long ms)))})

(defn- client-opts
  [opts]
  (cond-> {}
    (some-> (:api_key opts) str str/trim not-empty) (assoc :api_key (:api_key opts))))

(defn- runtime-summary
  [desired]
  (select-keys desired [:root_path :repo_key :workspace_key :profile :host :port]))

(defn- safe-state
  [state]
  (some-> state (dissoc :auth_token_ref)))

(defn- safe-health
  [health]
  (some-> health (select-keys [:healthy :status_code :service :error :error_class])))

(defn- observe-runtime
  "Collect process and health observations for the pure decision kernel.

  Observations target the persisted endpoint when state exists, otherwise the
  desired endpoint, so an already-running local runtime can be adopted instead
  of duplicated."
  [deps desired persisted opts]
  (let [endpoint (or persisted desired)
        port-open ((:port-open? deps) (:host endpoint) (:port endpoint))
        health (when port-open
                 ((:check-health deps) endpoint (client-opts opts)))
        pid-alive (if (:pid persisted)
                    ((:pid-alive? deps) (:pid persisted))
                    true)]
    {:process {:pid_alive pid-alive
               :port_open port-open}
     :health (or health {:healthy false :error "port_closed"})}))

(defn- adopted-state
  "State record for a healthy runtime this launcher did not start."
  [deps desired]
  (assoc (select-keys desired [:schema_version :root_path :repo_key :workspace_key
                               :profile :host :port])
         :pid nil
         :owned false
         :started_at nil
         :last_health_at ((:now deps))))

(defn- assess
  [deps desired opts lock]
  (let [home (:home deps)
        persisted ((:read-state deps) home desired)
        {:keys [process health]} (observe-runtime deps desired persisted opts)
        state (or persisted
                  (when (:healthy health)
                    (adopted-state deps desired)))
        decision (launcher/decide-runtime-reuse
                  (cond-> {:desired desired
                           :state state
                           :health health
                           :process process}
                    (some? lock) (assoc :lock lock)))]
    {:persisted persisted
     :state state
     :process process
     :health health
     :decision decision}))

(defn- report
  [command desired assessment extra]
  (merge {:command command
          :decision (get-in assessment [:decision :decision])
          :reason (get-in assessment [:decision :reason])
          :runtime (runtime-summary desired)
          :state (safe-state (:state assessment))
          :health (safe-health (:health assessment))
          :process (:process assessment)}
         extra))

(defn- now-ms
  [deps]
  ((or (:now-ms deps) (fn [] (System/currentTimeMillis)))))

(defn- await-health
  "Poll until the runtime reports healthy, the deadline passes, or the caller's
  timeout is reached. Returns the health observation with the waited time, so a
  slow start is visible as a number rather than as a feeling."
  [deps desired opts]
  (let [timeout-ms (or (:start_timeout_ms opts) default-start-timeout-ms)
        interval-ms (or (:start_poll_interval_ms opts) default-start-poll-interval-ms)
        started-ms (now-ms deps)
        deadline (+ started-ms (long timeout-ms))]
    (loop [attempt 0]
      (let [health ((:check-health deps) desired (client-opts opts))
            elapsed (- (now-ms deps) started-ms)]
        (cond
          (:healthy health) (assoc health :waited_ms elapsed :health_attempts (inc attempt))
          (>= (now-ms deps) deadline) (assoc health
                                             :timed_out true
                                             :waited_ms elapsed
                                             :health_attempts (inc attempt))
          :else (do ((:sleep deps) interval-ms)
                    (recur (inc attempt))))))))

(defn- start-runtime!
  [deps desired opts assessment]
  (let [home (:home deps)
        log-file ((:log-file deps) home desired)
        spawn-started-ms (now-ms deps)
        started ((:start-process! deps)
                 desired
                 {:log_file log-file
                  :working_dir (:root_path desired)
                  :clojure_bin (:clojure_bin opts)
                  :env (when (some-> (:api_key opts) str str/trim not-empty)
                         {:SEMIDX_RUNTIME_API_KEY (:api_key opts)})})
        spawn-ms (- (now-ms deps) spawn-started-ms)
        health (await-health deps desired opts)
        timings {:spawn_ms spawn-ms
                 :health_wait_ms (:waited_ms health)
                 :health_attempts (:health_attempts health)
                 :startup_ms (+ spawn-ms (long (or (:waited_ms health) 0)))}]
    (if (:healthy health)
      (let [now ((:now deps))
            state (assoc (select-keys desired [:schema_version :root_path :repo_key
                                               :workspace_key :profile :host :port])
                         :pid (:pid started)
                         :owned true
                         :started_at now
                         :last_health_at now)]
        ((:write-state! deps) home desired state)
        (report "start" desired (assoc assessment :state state :health health)
                {:started true
                 :pid (:pid started)
                 :log_path (str log-file)
                 :timings timings}))
      (do
        (when (:pid started)
          ((:stop-process! deps) (:pid started) {}))
        ((:clear-state! deps) home desired)
        {:command "start"
         :decision :blocked
         :reason :runtime_start_unhealthy
         :runtime (runtime-summary desired)
         :health (safe-health health)
         :pid (:pid started)
         :log_path (str log-file)
         :timings timings}))))

(defn- persist-adoption!
  "Record an adopted runtime so later `status` and `stop` calls know this
  launcher did not start it."
  [deps desired assessment]
  (when (and (nil? (:persisted assessment))
             (some? (:state assessment)))
    ((:write-state! deps) (:home deps) desired (:state assessment))))

(defn ensure-runtime!
  "Reuse a healthy local runtime, or start one under an exclusive slot lock.

  Health is re-observed after the lock is acquired so two concurrent clients do
  not start two runtimes for the same project."
  [deps desired opts]
  (let [home (:home deps)
        first-pass (assess deps desired opts nil)]
    (if (= :reused (get-in first-pass [:decision :decision]))
      (do
        (persist-adoption! deps desired first-pass)
        (report "ensure" desired first-pass {:started false}))
      ((:with-start-lock deps) home desired
       (fn [lock]
         (let [locked (assess deps desired opts lock)
               outcome (get-in locked [:decision :decision])]
           (case outcome
             :reused (do
                       (persist-adoption! deps desired locked)
                       (report "ensure" desired locked {:started false}))
             :blocked (report "ensure" desired locked {:started false})
             :stale-cleaned (do
                              ((:clear-state! deps) home desired)
                              (-> (start-runtime! deps desired opts locked)
                                  (assoc :decision :stale-cleaned
                                         :reason (get-in locked [:decision :reason])
                                         :stale_cleaned true)))
             :started (start-runtime! deps desired opts locked))))))))

(defn- timed
  "Run a command and record how long it took.

  Every command reports `timings.total_ms`, so the reuse claim can be read off
  the command output instead of being taken on trust."
  [deps f]
  (let [started-ms (now-ms deps)
        result (f)]
    (assoc result :timings (assoc (:timings result)
                                  :total_ms (- (now-ms deps) started-ms)))))

(defn status
  "Report whether a project-scoped runtime is currently reusable."
  ([opts] (status (default-deps) opts))
  ([deps opts]
   (timed deps
          (fn []
            (let [desired (launcher/desired-runtime (:root opts) opts)
                  assessment (assess deps desired opts nil)]
              (report "status" desired assessment
                      {:running (= :reused (get-in assessment [:decision :decision]))
                       :state_path (str ((:state-file deps) (:home deps) desired))}))))))

(defn start!
  "Start a project-scoped runtime unless a healthy one can be reused."
  ([opts] (start! (default-deps) opts))
  ([deps opts]
   (timed deps
          (fn []
            (let [desired (launcher/desired-runtime (:root opts) opts)]
              (assoc (ensure-runtime! deps desired opts) :command "start"))))))

(defn- stop-runtime!
  [deps opts]
  (let [home (:home deps)
        desired (launcher/desired-runtime (:root opts) opts)
        state ((:read-state deps) home desired)
        base {:command "stop" :runtime (runtime-summary desired)}]
    (cond
      (nil? state)
      (assoc base :decision :noop :reason :no_state)

      (false? (:owned state))
      (assoc base
             :decision :blocked
             :reason :not_launcher_owned
             :state (safe-state state))

      (nil? (:pid state))
      (do
        ((:clear-state! deps) home desired)
        (assoc base :decision :stale-cleaned :reason :no_pid_recorded))

      :else
      (let [result ((:stop-process! deps) (:pid state) (select-keys opts [:timeout_ms]))]
        ((:clear-state! deps) home desired)
        (assoc base
               :decision :stopped
               :reason (:reason result)
               :pid (:pid state)
               :forced (boolean (:forced result)))))))

(defn stop!
  "Stop the launcher-owned runtime for this project slot."
  ([opts] (stop! (default-deps) opts))
  ([deps opts] (timed deps (fn [] (stop-runtime! deps opts)))))

(defn- read-query
  [path]
  (with-open [rdr (io/reader path)]
    (json/read rdr :key-fn keyword)))

(defn- write-json!
  [path data]
  (with-open [w (io/writer path)]
    (json/write data w :indent true)))

(defn- run-request-command
  [deps opts]
  (let [desired (launcher/desired-runtime (:root opts) opts)]
    (if-not (= "runtime-http" (:profile desired))
      {:command "request"
       :ok false
       :decision :blocked
       :reason :request_unsupported_for_profile
       :runtime (runtime-summary desired)
       :hint (str "the " (:profile desired) " profile is driven by an MCP client, "
                  "not by launcher request; use `status`/`start` and point the "
                  "client at the endpoint")}
      (let [ensured (ensure-runtime! deps desired opts)]
        (if-not (contains? ok-decisions (:decision ensured))
          (assoc ensured :command "request" :ok false)
          (let [query (or (:query opts) (read-query (:query_path opts)))
                request-started-ms (now-ms deps)
                response ((:send-request deps)
                          desired
                          {:root_path (:root_path desired)
                           :query query
                           :paths (:paths opts)}
                          (client-opts opts))
                request-ms (- (now-ms deps) request-started-ms)]
            (assoc (dissoc ensured :state :health :process)
                   :command "request"
                   :ok (boolean (:ok? response))
                   :reused (= :reused (:decision ensured))
                   :timings (assoc (:timings ensured) :request_ms request-ms)
                   :response response)))))))

(defn request!
  "Run one retrieval request against a reused or freshly started runtime.

  Only the `runtime-http` profile serves this request path. An MCP HTTP runtime
  speaks JSON-RPC over its own session-bearing endpoint, so the request is
  refused here instead of being forwarded to a path that server does not have,
  and no process is started for it.

  The report carries `timings.request_ms` for the forwarded call and
  `timings.total_ms` for the whole command, so a warm reuse can be compared
  against a cold start without external instrumentation."
  ([opts] (request! (default-deps) opts))
  ([deps opts] (timed deps (fn [] (run-request-command deps opts)))))

(defn- parse-args
  [args]
  (loop [m {:root "."} xs args]
    (if (empty? xs)
      m
      (let [[k v & more] xs]
        (case k
          "--root" (recur (assoc m :root v) more)
          "--profile" (recur (assoc m :profile v) more)
          "--host" (recur (assoc m :host v) more)
          "--port" (recur (assoc m :port v) more)
          "--query" (recur (assoc m :query_path v) more)
          "--out" (recur (assoc m :out_path v) more)
          "--api-key" (recur (assoc m :api_key v) more)
          "--clojure-bin" (recur (assoc m :clojure_bin v) more)
          "--start-timeout-ms" (recur (assoc m :start_timeout_ms (some-> v parse-long)) more)
          (recur m (cons v more)))))))

(def ^:private usage
  (str "Usage:\n"
       "  clojure -M:launcher status  --root <repo-root> [--profile runtime-http|mcp-http] [--host <host>] [--port <port>]\n"
       "  clojure -M:launcher start   --root <repo-root> [--profile runtime-http|mcp-http] [--port <port>]\n"
       "  clojure -M:launcher stop    --root <repo-root> [--profile runtime-http|mcp-http]\n"
       "  clojure -M:launcher request --root <repo-root> --query <query.json> [--out <output.json>]\n"
       "\n"
       "Profiles:\n"
       "  runtime-http (default, port 8787)  one-shot retrieval requests over the runtime HTTP contract;\n"
       "                                     this is the only profile `request` can drive.\n"
       "  mcp-http     (port 8791)           long-lived MCP endpoint for clients that speak Streamable HTTP;\n"
       "                                     the launcher owns its process, the MCP client owns the protocol.\n"))

(defn- print-report!
  [report]
  (println (json/write-str report :escape-slash false)))

(defn- run-request!
  [opts]
  (let [result (request! opts)]
    (if-not (:ok result)
      (do (print-report! result) 1)
      (let [payload (get-in result [:response :result])]
        ;; The measurement goes to stderr so stdout stays exactly the runtime
        ;; payload that callers pipe, while the reuse claim stays checkable.
        (binding [*out* *err*]
          (println (json/write-str (select-keys result [:command :decision :reused :timings])
                                   :escape-slash false)))
        (if-let [out-path (:out_path opts)]
          (do (write-json! out-path payload)
              (println (str "wrote " out-path)))
          (println (json/write-str payload :escape-slash false)))
        0))))

(defn -main [& args]
  (let [[command & rest-args] args
        opts (parse-args rest-args)
        exit (case command
               "status" (let [result (status opts)]
                          (print-report! result)
                          (if (contains? ok-decisions (:decision result)) 0 1))
               "start" (let [result (start! opts)]
                         (print-report! result)
                         (if (contains? ok-decisions (:decision result)) 0 1))
               "stop" (let [result (stop! opts)]
                        (print-report! result)
                        (if (contains? ok-decisions (:decision result)) 0 1))
               "request" (if-not (:query_path opts)
                           (do (println usage) 1)
                           (run-request! opts))
               (do (println usage) 1))]
    (flush)
    (System/exit exit)))
