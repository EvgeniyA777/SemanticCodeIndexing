(ns semidx.runtime.launcher-state
  "Local state store for the runtime launcher.

  Owns runtime metadata files, launcher log placement, and the start lock. It
  does not know about request payloads, retrieval semantics, or process
  spawning."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.launcher :as launcher])
  (:import [java.io File RandomAccessFile]
           [java.nio.channels OverlappingFileLockException]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def ^:private default-home-segments [".cache" "semidx" "runtime"])

(def state-file-name "state.edn")

(def lock-file-name "start.lock")

(def log-file-name "runtime.log")

(defn- warn! [message]
  (binding [*out* *err*]
    (println message)
    (flush)))

(defn launcher-home
  "Resolve the launcher state home directory.

  `SEMIDX_RUNTIME_LAUNCHER_HOME` overrides the default per-user cache location
  so tests and sandboxes never write into the repository."
  ^File []
  (if-let [override (some-> (System/getenv "SEMIDX_RUNTIME_LAUNCHER_HOME")
                            str/trim
                            not-empty)]
    (io/file override)
    (apply io/file (System/getProperty "user.home") default-home-segments)))

(defn slot-dir
  "Directory holding launcher state for one project/profile slot."
  ^File [home desired]
  (io/file home (launcher/runtime-slot-key desired)))

(defn state-file ^File [home desired]
  (io/file (slot-dir home desired) state-file-name))

(defn lock-file ^File [home desired]
  (io/file (slot-dir home desired) lock-file-name))

(defn log-file ^File [home desired]
  (io/file (slot-dir home desired) log-file-name))

(defn- restrict-permissions! [^File file permissions]
  (try
    (Files/setPosixFilePermissions (.toPath file)
                                   (PosixFilePermissions/fromString permissions))
    true
    (catch UnsupportedOperationException _
      ;; Non-POSIX filesystem: fall back to the JDK owner-only flags.
      (.setReadable file false false)
      (.setWritable file false false)
      (.setReadable file true true)
      (.setWritable file true true)
      false)
    (catch Exception e
      (warn! (str "launcher_state_permissions_not_applied path=" (.getPath file)
                  " error=" (.getMessage e)))
      false)))

(defn ensure-slot-dir!
  "Create the slot directory with owner-only permissions."
  ^File [home desired]
  (let [dir (slot-dir home desired)]
    (.mkdirs dir)
    (restrict-permissions! dir "rwx------")
    dir))

(defn read-state
  "Read persisted launcher state for a slot, or nil when absent or unreadable."
  [home desired]
  (let [file (state-file home desired)]
    (when (.exists file)
      (try
        (launcher/normalize-runtime-state (edn/read-string (slurp file)))
        (catch Exception e
          (warn! (str "launcher_state_unreadable path=" (.getPath file)
                      " error=" (.getMessage e)))
          nil)))))

(defn write-state!
  "Persist launcher state for a slot with owner-only file permissions."
  [home desired state]
  (let [file (state-file home desired)]
    (ensure-slot-dir! home desired)
    (spit file (pr-str state))
    (restrict-permissions! file "rw-------")
    state))

(defn clear-state!
  "Remove persisted launcher state for a slot. Returns true when a file was
  deleted."
  [home desired]
  (let [file (state-file home desired)]
    (if (.exists file)
      (let [deleted (.delete file)]
        (when-not deleted
          (warn! (str "launcher_state_delete_failed path=" (.getPath file))))
        deleted)
      false)))

(defn with-start-lock
  "Run `f` with a start-lock observation for the slot.

  Calls `(f :acquired)` while holding an exclusive lock on the slot lock file,
  or `(f :contended)` when another process or thread already holds it. The
  observation is passed straight to the pure decision kernel."
  [home desired f]
  (ensure-slot-dir! home desired)
  (let [file (lock-file home desired)]
    (with-open [raf (RandomAccessFile. file "rw")
                channel (.getChannel raf)]
      (restrict-permissions! file "rw-------")
      (let [lock (try
                   (.tryLock channel)
                   (catch OverlappingFileLockException _
                     ;; Another thread in this JVM already holds the slot lock.
                     nil))]
        (if (nil? lock)
          (f :contended)
          (try
            (f :acquired)
            (finally
              (.release lock))))))))
