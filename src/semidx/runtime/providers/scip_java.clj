(ns semidx.runtime.providers.scip-java
  "Stage 4 of the Semantic Provider Authority Migration (plans/018, ADR-046):
  the Java SCIP provider adapter. Shadow / default-off.

  Everything language-neutral — the per-document stale gate, the arity-only
  overload guard, `FactBatch` assembly, and the result shapes — lives in
  `semidx.runtime.providers.scip-adapter`. What remains here is only how the
  Java SCIP toolchain is resolved and invoked.

  Toolchain (owner decision 2026-09-05): a repo-managed **external** process,
  never a semidx runtime dependency. `scip-semanticdb` ships no CLI entry point
  and the upstream `scip-java` CLI is a Scala artifact that pulls in coursier and
  an embedded Kotlin compiler, so `scripts/setup-scip-java.sh` installs pinned
  jars plus a compiled driver into a gitignored directory and this adapter runs
  two subprocesses:

    javac + semanticdb-javac plugin  ->  .semanticdb
    java ScipJavaIndexer             ->  .scip

  Keeping it out of the runtime classpath is what stops `scip-semanticdb`'s
  protobuf from meeting the version semidx already resolves through gRPC.

  Identity (owner decision 2026-09-05, reports/024 finding S1): scip-java puts
  no parameter types and no arity in its symbols — it disambiguates overloads
  with a source-order ordinal — so the Java exact tier commits
  `signature_precision \"arity_only\"`, exactly like the heuristic tier, and the
  native symbol, the `+N` disambiguator, and the signature documentation are
  evidence only. Because the whole tier is arity-only, same-arity overloads
  cannot be told apart and the shared overload guard withholds them rather than
  assert a false exact identity (finding S2).

  Source mode mirrors the TypeScript adapter: production generates the artifact
  with the repo-managed toolchain, a missing toolchain is an `:unavailable`
  result rather than an error, and `facts-from-index` is the test/fixture seam."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [semidx.runtime.providers.scip-adapter :as scip-adapter]
            [semidx.runtime.scip :as scip]))

(def provider-id "scip-java")
(def provider-version "1")
(def language "java")

(def descriptor
  "Catalog descriptor for the SCIP Java provider.

  As with the TypeScript adapter, it is kept here rather than in the per-file
  `semidx.runtime.providers` catalog while the provider is project-scoped."
  {:provider_id provider-id
   :provider_version provider-version
   :languages [language]
   :classification "semantic"
   :engine :scip
   :scope :project
   :selectors {:extensions [".java"]}
   :operation_capabilities {:definitions "exact"
                            :references "exact"}})

;; ---------------------------------------------------------------------------
;; Toolchain resolution (ADR-047-style chain)
;; ---------------------------------------------------------------------------

(defn toolchain-dir
  "Resolve the Java SCIP toolchain directory the same way
  `scripts/setup-scip-java.sh` resolves it: explicit option ->
  `SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR` -> repo-managed `.scip-java-toolchain`."
  [opts]
  (or (scip-adapter/present-path (:scip_java_toolchain_dir opts))
      (scip-adapter/present-path (:scip-java-toolchain-dir opts))
      (scip-adapter/present-path (System/getenv "SEMIDX_SCIP_JAVA_TOOLCHAIN_DIR"))
      (str (io/file ".scip-java-toolchain"))))

(defn- jars-in [dir]
  (->> (io/file (str dir))
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".jar"))
       (map #(.getAbsolutePath ^java.io.File %))
       sort
       vec))

(defn resolve-toolchain
  "Resolve the installed toolchain, or nil when it is not usable.

  Returns `{:dir :plugin_jar :classpath}`. Requires all three of: the lib
  directory with the SemanticDB javac plugin, at least one other jar, and the
  compiled driver. A partial install is treated as no install rather than
  something to half-run."
  ([] (resolve-toolchain {}))
  ([opts]
   (let [dir (toolchain-dir opts)
         lib (io/file (str dir) "lib")
         driver (io/file (str dir) "driver")]
     (when (and (scip-adapter/directory? lib) (scip-adapter/directory? driver))
       (let [jars (jars-in lib)
             plugin (first (filter #(str/includes? % "semanticdb-javac") jars))]
         (when (and plugin (> (count jars) 1)
                    (.isFile (io/file driver "ScipJavaIndexer.class")))
           {:dir (str dir)
            :plugin_jar plugin
            :classpath (str/join ":" (conj jars (.getAbsolutePath driver)))}))))))

(defn provider-status
  "Observe whether the SCIP Java provider can run right now.

  `ready` when the pinned toolchain and the compiled driver are present and
  `javac` is on PATH; otherwise `unavailable` with a reason code. This never runs
  the indexer."
  ([] (provider-status {}))
  ([opts]
   (let [toolchain (resolve-toolchain opts)
         ;; Probe result only; the reason a probe failed reaches the caller
         ;; through the reason codes below, never through stdout — these
         ;; adapters run behind the MCP stdio transport.
         javac? (try
                  (zero? (int (:exit (sh/sh "javac" "-version"))))
                  (catch Exception _ false))
         base {:provider_id provider-id
               :observed_at (str (java.time.Instant/now))}
         reasons (cond-> []
                   (nil? toolchain) (conj "scip_java_toolchain_missing")
                   (not javac?) (conj "javac_missing"))]
     (if (seq reasons)
       (assoc base :state "unavailable" :reason_codes reasons)
       (assoc base :state "ready" :reason_codes [] :toolchain_dir (:dir toolchain))))))

;; ---------------------------------------------------------------------------
;; Index run
;; ---------------------------------------------------------------------------

(defn- java-sources [project-root]
  (->> (io/file (str project-root))
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".java"))
       (map #(.getAbsolutePath ^java.io.File %))
       sort
       vec))

(defn- delete-tree! [^java.io.File file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-tree! child)))
  (.delete file))

(defn- run-scip-index!
  "Compile `project-root` with the SemanticDB plugin, convert the result to SCIP,
  and return the parsed index data, or `{:error ...}`.

  `javac_classpath` is forwarded to the compiler for a project with
  dependencies; the compile is what fails on a project whose classpath is not
  supplied, and that failure is reported rather than silently producing an empty
  index."
  [{:keys [plugin_jar classpath]} project-root javac-classpath]
  (let [root (.getAbsolutePath (io/file (str project-root)))
        sources (java-sources root)
        work (java.io.File/createTempFile "semidx-scip-java" "")]
    (try
      (.delete work)
      (.mkdirs work)
      (let [semanticdb-out (io/file work "semanticdb")
            classes-out (io/file work "classes")
            index-out (io/file work "index.scip")]
        (.mkdirs semanticdb-out)
        (.mkdirs classes-out)
        (cond
          (empty? sources)
          {:error {:code :scip_index_failed
                   :message (str "no .java sources under " root)}}

          :else
          (let [javac-args (concat ["javac"
                                    "-processorpath" plugin_jar
                                    (str "-Xplugin:semanticdb -sourceroot:" root
                                         " -targetroot:" (.getAbsolutePath semanticdb-out))
                                    "-d" (.getAbsolutePath classes-out)]
                                   (when-let [cp (scip-adapter/present-path javac-classpath)]
                                     ["-classpath" cp])
                                   sources)
                compile-result (apply sh/sh (concat javac-args [:dir root]))]
            (if-not (zero? (int (:exit compile-result)))
              {:error {:code :scip_index_failed
                       :stage "javac"
                       :exit (int (:exit compile-result))
                       :message (str "javac with the semanticdb plugin exited "
                                     (:exit compile-result)
                                     (when-let [e (scip-adapter/present-path
                                                   (:err compile-result))]
                                       (str ": " e)))}}
              (let [convert-result (sh/sh "java" "-cp" classpath "ScipJavaIndexer"
                                          (.getAbsolutePath semanticdb-out)
                                          root
                                          (.getAbsolutePath index-out)
                                          :dir root)]
                (cond
                  (not (zero? (int (:exit convert-result))))
                  {:error {:code :scip_index_failed
                           :stage "scip-semanticdb"
                           :exit (int (:exit convert-result))
                           :message (str "ScipJavaIndexer exited "
                                         (:exit convert-result)
                                         (when-let [e (scip-adapter/present-path
                                                       (:err convert-result))]
                                           (str ": " e)))}}

                  (not (.isFile index-out))
                  {:error {:code :scip_index_failed
                           :stage "scip-semanticdb"
                           :message "ScipJavaIndexer reported success but wrote no index"}}

                  :else
                  {:index (scip/read-index index-out)}))))))
      (catch Exception e
        {:error {:code :scip_index_failed
                 :message (str "Java SCIP index failed: " (.getMessage e))}})
      (finally
        (delete-tree! work)))))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn facts-from-index
  "Turn an already-read Java SCIP index into arbitrated shadow facts.

  Test / fixture seam only — production callers use `shadow-facts-for-project`.
  The arity-only overload guard is always on for Java: the tier cannot supply a
  typed signature, so an unguarded run could assert one exact identity for two
  distinct same-arity overloads."
  [scip-index opts]
  (scip-adapter/facts-from-index
   scip-index
   (merge {:language language
           :provider-id provider-id
           :provider-version provider-version
           :guard-overloads? true}
          (select-keys opts [:project-root :expected-document-digests
                             :provider-id :provider-version]))))

(defn shadow-facts-for-project
  "Run the repo-managed Java SCIP toolchain over `root_path`, then normalize,
  gate, and arbitrate its output into shadow facts.

  Returns a map with `:result` one of `\"ready\"`, `\"unavailable\"` (no
  toolchain; degrade to tree-sitter/regex), or `\"failed\"` (the toolchain ran
  and errored — a compile failure on a project whose classpath was not supplied
  lands here). Never throws for a missing or failing toolchain, and never writes
  to a snapshot."
  [{:keys [root_path expected_document_digests javac_classpath] :as opts}]
  (when-not root_path
    (throw (ex-info "shadow-facts-for-project requires :root_path"
                    {:error_code :missing_root_path})))
  (let [toolchain (resolve-toolchain opts)]
    (if-not toolchain
      (scip-adapter/unavailable-result
       {:provider-id provider-id
        :provider-version provider-version
        :reason-codes ["scip_java_toolchain_missing"]
        :message (str "no Java SCIP toolchain resolved for " root_path
                      " (run scripts/setup-scip-java.sh); degrade to tree-sitter/regex")})
      (let [{:keys [index error]} (run-scip-index! toolchain root_path javac_classpath)]
        (if error
          (assoc (scip-adapter/failed-result {:provider-id provider-id
                                              :provider-version provider-version
                                              :diagnostic error})
                 :toolchain {:dir (:dir toolchain)})
          (-> (facts-from-index index
                                {:project-root root_path
                                 :expected-document-digests expected_document_digests})
              (assoc :toolchain {:dir (:dir toolchain)})))))))
