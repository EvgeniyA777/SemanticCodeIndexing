(ns semidx.runtime.providers.lsp-java
  "Stage 5b of the Semantic Provider Authority Migration (plans/018, ADR-046):
  the Java live-overlay provider. Opt-in, shadow, default-off.

  A second consumer of the Stage 5a seam, which it uses unchanged:
  `semidx.runtime.provider-overlay` still owns the session lifecycle, source
  identity, the failure taxonomy, coverage, and delivery. What lives here is
  only how jdtls is resolved and started, and how its symbols become facts.

  Four properties of the real server shape this adapter, all verified against
  jdtls 1.54.0 over the protected corpus rather than assumed:

  - **it needs a JDK 21 or newer**, independent of the JDK semidx itself runs
    on. Under 17 the OSGi framework refuses to resolve
    `org.eclipse.jdt.core.manipulation` and the application never registers, so
    the JVM is resolved and version-checked separately;
  - **members arrive late and their absence is silent**: right after `didOpen`,
    `documentSymbol` returns the package and the class with no children and no
    error, so this adapter polls for readiness and treats exhaustion as a
    degradation rather than publishing the early answer;
  - **overload identity stays arity-only**: symbols are named `handle(String)`,
    `handle(String, int)`, `OrderService(Validator)`. Arity is recoverable, but
    the parameter types are simple names — the exact form Stage 4 rejected as
    Variant B for `scip-java`. Types remain evidence, never key material;
  - **references are unavailable** in this mode: without a build file jdtls runs
    an invisible project with no resolved classpath, and
    `textDocument/references` comes back empty even for a method the corpus
    calls twice. The descriptor claims `definitions` only.

  Unlike the TypeScript server, jdtls needs a writable `-configuration`
  directory and a `-data` workspace directory outside the repository, and picks
  a per-platform `config_*` directory. None of that reaches the seam."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [semidx.runtime.lsp-client :as lsp]
            [semidx.runtime.providers :as providers])
  (:import [java.security MessageDigest]))

(def provider-id "java-lsp")
(def provider-version "1")
(def language "java")

(def descriptor
  "Catalog descriptor for the Java LSP provider, re-exported from
  `semidx.runtime.providers`."
  (providers/descriptor provider-id))

(def minimum-jdk-major
  "jdtls 1.54.0 declares `osgi.ee=JavaSE version=21`. A lower JVM does not fail
  a request — the server never starts at all."
  21)

;; ---------------------------------------------------------------------------
;; Toolchain resolution (ADR-047-style chain, minus the ambient step)
;; ---------------------------------------------------------------------------

(def toolchain-dir-name ".jdtls-toolchain")

(defn- present [value]
  (let [value (str value)]
    (when-not (str/blank? value) value)))

(defn- existing-dir [path]
  (when-let [path (present path)]
    (let [file (io/file path)]
      (when (.isDirectory file) (.getAbsolutePath file)))))

(defn resolve-home
  "The unpacked jdtls installation.

  There is deliberately no ambient `PATH` step: a `jdtls` on `PATH` is exactly
  the unpinned install the repo-managed toolchain exists to replace, and its
  version would silently differ between machines."
  [opts]
  (or (existing-dir (:java_lsp_home opts))
      (existing-dir (System/getenv "SEMIDX_JDTLS_HOME"))
      (existing-dir toolchain-dir-name)))

(defn resolve-launcher
  "The Equinox launcher jar inside a jdtls install."
  [home]
  (when home
    (some->> (.listFiles (io/file home "plugins"))
             (filter #(re-find #"^org\.eclipse\.equinox\.launcher_.*\.jar$" (.getName ^java.io.File %)))
             sort
             last
             .getAbsolutePath)))

(defn platform-config-name
  "The `config_*` directory for the current OS and architecture. jdtls ships one
  per platform and starting against the wrong one fails."
  []
  (let [os (str/lower-case (str (System/getProperty "os.name")))
        arch (str/lower-case (str (System/getProperty "os.arch")))
        arm? (or (str/includes? arch "aarch64") (str/includes? arch "arm"))]
    (cond
      (str/includes? os "mac") (if arm? "config_mac_arm" "config_mac")
      (str/includes? os "win") "config_win"
      :else (if arm? "config_linux_arm" "config_linux"))))

(defn- java-command [opts]
  (let [home (or (present (:java_lsp_java_home opts))
                 (present (System/getenv "SEMIDX_JDTLS_JAVA_HOME"))
                 (present (System/getenv "JAVA_HOME")))]
    (if home
      (let [candidate (io/file home "bin" "java")]
        (when (.canExecute candidate) (.getAbsolutePath candidate)))
      "java")))

(defn jdk-major-version
  "Major version of `java-cmd`, or nil when it cannot be determined.

  `java -version` prints to stderr, and reports either `1.8.0_x` or `21.0.9`."
  [java-cmd]
  (when java-cmd
    (try
      (let [{:keys [out err]} (sh/sh java-cmd "-version")
            text (str out err)]
        (when-let [[_ major minor] (re-find #"version \"(\d+)(?:\.(\d+))?" text)]
          (let [major (parse-long major)]
            (if (= 1 major) (some-> minor parse-long) major))))
      (catch Exception _ nil))))

(defn provider-status
  "Observe whether jdtls can run right now: the install, its launcher, its
  platform configuration, and a JVM new enough to host it.

  The JDK check is part of the probe rather than a startup surprise, because a
  too-old JVM produces an OSGi resolution failure and an immediately closed
  stream rather than a readable error."
  ([] (provider-status {}))
  ([opts]
   (let [home (resolve-home opts)
         launcher (resolve-launcher home)
         config (when home (existing-dir (str home "/" (platform-config-name))))
         java-cmd (java-command opts)
         major (jdk-major-version java-cmd)
         base {:provider_id provider-id
               :observed_at (str (java.time.Instant/now))}
         reasons (cond-> []
                   (nil? home) (conj "jdtls_home_missing")
                   (and home (nil? launcher)) (conj "jdtls_launcher_missing")
                   (and home (nil? config)) (conj "jdtls_platform_config_missing")
                   (nil? java-cmd) (conj "jdtls_java_missing")
                   (and java-cmd (nil? major)) (conj "jdtls_java_version_unknown")
                   (and major (< major minimum-jdk-major)) (conj "jdtls_java_too_old"))]
     (if (seq reasons)
       (assoc base :state "unavailable" :reason_codes reasons
              :observed_jdk_major major)
       (assoc base :state "ready" :reason_codes []
              :home home
              :launcher launcher
              :config config
              :java java-cmd
              :jdk_major major)))))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn- sha1-hex [^String value]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (apply str (map #(format "%02x" %) (.digest digest (.getBytes value "UTF-8"))))))

(defn workspace-data-dir
  "Per-workspace jdtls state, outside the repository.

  jdtls writes an Eclipse workspace here, so it must be writable and must not be
  shared between two roots. `~/.cache/semidx/jdtls/<digest>` mirrors where the
  runtime launcher already keeps its own state."
  [opts root-path]
  (or (present (:java_lsp_data_dir opts))
      (str (System/getProperty "user.home")
           "/.cache/semidx/jdtls/"
           (sha1-hex (str (.getCanonicalPath (io/file (str root-path))))))))

(defn open-session
  "Start one jdtls session for a whole overlay operation.

  The `-configuration` directory is a per-workspace copy: jdtls writes into it,
  so pointing every workspace at the shared install would have them corrupt each
  other's state."
  [{:keys [root_path timeout_ms] :as opts}]
  (let [{:keys [state reason_codes home launcher config java]} (provider-status opts)]
    (when (not= "ready" state)
      (throw (ex-info (str "jdtls is not available: " (str/join ", " reason_codes))
                      {:type :lsp_server_missing
                       :provider_id provider-id
                       :reason_codes reason_codes})))
    (let [data-dir (workspace-data-dir opts root_path)
          config-copy (io/file data-dir "config")]
      (.mkdirs (io/file data-dir))
      (when-not (.isDirectory config-copy)
        (.mkdirs config-copy)
        (doseq [^java.io.File file (.listFiles (io/file config))
                :when (.isFile file)]
          (io/copy file (io/file config-copy (.getName file)))))
      (lsp/start-session!
       {:root_path root_path
        :command [java
                  "-Declipse.application=org.eclipse.jdt.ls.core.id1"
                  "-Dosgi.bundles.defaultStartLevel=4"
                  "-Declipse.product=org.eclipse.jdt.ls.core.product"
                  "-Dlog.level=ERROR"
                  "-Xmx1G"
                  "--add-modules=ALL-SYSTEM"
                  "--add-opens" "java.base/java.util=ALL-UNNAMED"
                  "--add-opens" "java.base/java.lang=ALL-UNNAMED"
                  "-jar" launcher
                  "-configuration" (.getAbsolutePath config-copy)
                  "-data" (str data-dir "/workspace")]
        :timeout_ms (or timeout_ms 120000)}))))

(defn close-session [session]
  (when session (lsp/stop-session! session)))

;; ---------------------------------------------------------------------------
;; Symbols -> facts
;; ---------------------------------------------------------------------------

(def ^:private container-kinds
  "Package, class, interface, enum, struct: these carry ownership, not units."
  #{4 5 11 10 23})

(def ^:private unit-kinds
  "Method and constructor. Fields are modelled as relations by plans/017, never
  as units, so they are not minted here either."
  #{6 9})

(defn declared-arity
  "Arity from a jdtls symbol name such as `handle(String, int)`.

  Commas inside generic arguments do not separate parameters, so `Map<K, V>`
  counts once. Returns nil when the name carries no parameter list at all, which
  is the honest answer rather than zero."
  [name*]
  (when-let [open (str/index-of (str name*) "(")]
    (let [text (str name*)
          close (str/last-index-of text ")")]
      (when (and close (< open close))
        (let [params (str/trim (subs text (inc open) close))]
          (if (str/blank? params)
            0
            (loop [chars (seq params) depth 0 count* 1]
              (if-let [c (first chars)]
                (cond
                  (contains? #{\< \( \[} c) (recur (rest chars) (inc depth) count*)
                  (contains? #{\> \) \]} c) (recur (rest chars) (dec depth) count*)
                  (and (= \, c) (zero? depth)) (recur (rest chars) depth (inc count*))
                  :else (recur (rest chars) depth count*))
                count*))))))))

(defn- simple-name [name*]
  (let [text (str name*)]
    (if-let [open (str/index-of text "(")]
      (str/trim (subs text 0 open))
      text)))

(defn- line-of [range edge]
  (some-> range edge :line inc))

(defn flatten-symbols
  "Walk the hierarchical documentSymbol tree into flat definition records.

  The package is a **sibling** of the type it contains, not its parent: jdtls
  returns `example` (kind 4) and `OrderService` (kind 5) both at depth 0. Taking
  ownership from the tree alone therefore produces `OrderService#handle`, which
  is a different canonical key from the `example.OrderService#handle` the regex
  and SCIP tiers emit — the two tiers would never merge. The package name is
  read off the top level and used as the prefix for everything beside it.

  Types contribute ownership; methods and constructors become units."
  ([symbols]
   (let [package (some (fn [symbol*]
                         (when (= 4 (:kind symbol*)) (:name symbol*)))
                       symbols)]
     (flatten-symbols symbols (if (str/blank? (str package)) [] [package]))))
  ([symbols owners]
   (vec
    (mapcat
     (fn [symbol*]
       (let [kind (:kind symbol*)
             raw-name (:name symbol*)
             name* (simple-name raw-name)
             start (line-of (:range symbol*) :start)
             end (line-of (:range symbol*) :end)
             owner (str/join "." owners)
             package? (= 4 kind)
             here (cond
                    (contains? unit-kinds kind)
                    [{:name name*
                      :kind (if (= 9 kind) "constructor" "method")
                      :symbol (str owner "#" name*)
                      :owner owner
                      :arity (declared-arity raw-name)
                      :start_line (or start 1)
                      :end_line (max (or end 1) (or start 1))
                      :selection_position (get-in symbol* [:selectionRange :start])
                      :detail (some-> (:detail symbol*) str/trim not-empty)
                      :native_name raw-name
                      :lsp_kind kind}]

                    (or package? (contains? container-kinds kind))
                    []

                    :else
                    [{:unmapped raw-name
                      :reason (str "unmodelled_lsp_symbol_kind_" kind)}])
             ;; The package prefix is already in `owners`; only a type adds one.
             next-owners (if (and (not package?) (contains? container-kinds kind))
                           (conj owners name*)
                           owners)]
         (into here (flatten-symbols (:children symbol*) next-owners))))
     symbols))))

(defn- definition-fact [{:keys [path source_identity]} definition]
  {:key {:fact_kind "unit"
         :language language
         :path path
         :owner (:owner definition)
         :symbol (:symbol definition)
         ;; Arity-only, exactly like the scip-java and regex tiers: jdtls spells
         ;; parameter types with simple names, which cannot serve as a stable
         ;; typed signature.
         :overload_identity (when-let [arity (:arity definition)]
                              {:arity arity
                               :signature_precision "arity_only"
                               :signature_key nil})}
   :evidence [{:provider_id provider-id
               :provider_version provider-version
               :authority "exact"
               :operation "definitions"
               :freshness "exact"
               :source_identity source_identity
               :evidence_location {:path path
                                   :start_line (:start_line definition)
                                   :end_line (:end_line definition)}
               :native_symbol (:native_name definition)
               :native_details (cond-> {:lsp_symbol_kind (:lsp_kind definition)}
                                 (:detail definition)
                                 (assoc :lsp_detail (:detail definition)))}]
   :value {:kind (:kind definition)
           :signature (:detail definition)}})

(def default-readiness-timeout-ms
  "How long to wait for jdtls to report members for an opened document. The
  server answers immediately with the package and the type and fills in their
  children once the project is built, so an unwaited answer is quietly
  incomplete rather than wrong."
  30000)

(defn- request-symbols [session uri]
  (lsp/request! session "textDocument/documentSymbol" {:textDocument {:uri uri}}))

(defn- ready-symbols
  "Poll `documentSymbol` until the document reports at least one member, or the
  deadline passes.

  A file with types but no members is indistinguishable from a project that has
  not finished building, so exhaustion is reported as a timeout rather than
  published as an empty result."
  [session uri timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [symbols (request-symbols session uri)
            members (mapcat :children symbols)]
        (cond
          (seq members) symbols

          ;; Nothing that could own members: an empty or type-free file is a
          ;; complete answer, not an unready one.
          (empty? (filter #(contains? container-kinds (:kind %)) symbols))
          symbols

          (< deadline (System/currentTimeMillis))
          (throw (ex-info "jdtls reported no members before the readiness deadline"
                          {:type :lsp_project_not_ready
                           :provider_id provider-id
                           :timeout_ms timeout-ms}))

          :else
          (do (Thread/sleep 500) (recur)))))))

(defn document-facts
  "Facts for one document from one live jdtls session.

  The document is opened with the exact text being indexed and stays open until
  the symbols come back, so the evidence describes what was analysed. Failures
  are raised and classified by the overlay boundary."
  [session {:keys [root_path path text source_identity document_version]}
   {:keys [readiness_timeout_ms] :or {readiness_timeout_ms default-readiness-timeout-ms}}]
  (let [uri (str (.toURI (.getCanonicalFile (io/file (str root_path) (str path)))))
        version (or document_version 1)]
    (lsp/notify! session "textDocument/didOpen"
                 {:textDocument {:uri uri
                                 :languageId language
                                 :version version
                                 :text text}})
    (try
      (let [raw (ready-symbols session uri readiness_timeout_ms)
            _ (when-not (or (nil? raw) (sequential? raw))
                (throw (ex-info "documentSymbol returned a non-sequential result"
                                {:type :lsp_malformed_response
                                 :provider_id provider-id
                                 :path path})))
            walked (flatten-symbols raw)
            definitions (filterv :symbol walked)
            context {:path path :source_identity source_identity}]
        {:facts (mapv #(definition-fact context %) definitions)
         :unmapped (filterv :unmapped walked)
         :diagnostics []})
      (finally
        (lsp/notify! session "textDocument/didClose"
                     {:textDocument {:uri uri}})))))
