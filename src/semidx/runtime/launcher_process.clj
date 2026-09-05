(ns semidx.runtime.launcher-process
  "Process runner for launcher-managed local runtimes.

  Knows about command arguments, explicitly passed environment values, and
  process liveness. Does not know about retrieval requests, response bodies, or
  launcher state files."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.net InetSocketAddress Socket]
           [java.util.concurrent TimeUnit]))

(def default-port-probe-timeout-ms 300)

(def default-stop-timeout-ms 5000)

(def profile-aliases
  {"runtime-http" "runtime-http"
   "mcp-http" "mcp-http"})

(defn pid-alive?
  "True when the recorded PID still maps to a live process."
  [pid]
  (boolean
   (when (some? pid)
     (let [handle (ProcessHandle/of (long pid))]
       (and (.isPresent handle)
            (.isAlive (.get handle)))))))

(defn port-open?
  "True when a TCP connection to host/port succeeds.

  A refused connection or timeout is the answer here, not a swallowed failure:
  it means the recorded endpoint is not listening."
  ([host port] (port-open? host port default-port-probe-timeout-ms))
  ([host port timeout-ms]
   (if (nil? port)
     false
     (try
       (with-open [socket (Socket.)]
         (.connect socket
                   (InetSocketAddress. ^String (str (or host "127.0.0.1")) (int port))
                   (int timeout-ms))
         true)
       (catch java.io.IOException _
         false)))))

(defn runtime-command
  "Command line that starts a long-lived runtime for `runtime`."
  [{:keys [profile host port]} {:keys [clojure_bin]}]
  (let [alias (get profile-aliases profile)]
    (when-not alias
      (throw (ex-info "no launcher process alias for profile"
                      {:type :invalid_launcher_profile
                       :profile profile})))
    [(or clojure_bin "clojure")
     (str "-M:" alias)
     "--host" (str host)
     "--port" (str port)]))

(defn start-runtime!
  "Start a detached local runtime process for `runtime`.

  `log_file` receives merged stdout/stderr so a failed start is inspectable.
  `env` values are added to the inherited environment; callers must never pass
  secrets they are not willing to place in a child process environment."
  [runtime {:keys [log_file working_dir env] :as opts}]
  (let [command (runtime-command runtime opts)
        ^File log (io/file log_file)
        _ (some-> (.getParentFile log) (.mkdirs))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (io/file (or working_dir (:root_path runtime))))
                  (.redirectErrorStream true)
                  (.redirectOutput (ProcessBuilder$Redirect/appendTo log)))]
    (doseq [[k v] env]
      (when (some? v)
        (.put (.environment builder) (str (name k)) (str v))))
    (let [process (.start builder)]
      {:pid (.pid process)
       :command command
       :log_path (.getPath log)})))

(defn stop-runtime!
  "Stop a launcher-managed runtime process, escalating to a forced kill."
  ([pid] (stop-runtime! pid {}))
  ([pid {:keys [timeout_ms] :or {timeout_ms default-stop-timeout-ms}}]
   (let [handle (ProcessHandle/of (long pid))]
     (if-not (.isPresent handle)
       {:stopped true :reason :process_absent :forced false}
       (let [process (.get handle)]
         (.destroy process)
         (if (try
               (.get (.onExit process) (long timeout_ms) TimeUnit/MILLISECONDS)
               true
               (catch java.util.concurrent.TimeoutException _
                 false))
           {:stopped true :reason :terminated :forced false}
           (do
             (.destroyForcibly process)
             {:stopped (not (.isAlive process))
              :reason :forced
              :forced true})))))))
