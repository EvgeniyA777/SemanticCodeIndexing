(ns build
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.io File]
           [java.math BigInteger]
           [java.net URI]
           [java.nio.file Files]
           [java.security MessageDigest]))

(def ^:private proto-root "proto")

;; Every `.proto` compiled into `src-generated/java` by the repo-managed
;; toolchain (ADR-042). `:file` is relative to `proto-root`. `:grpc?` runs the
;; grpc-java plugin for protos that declare a service. `:expect` is a generated
;; file that must exist afterwards, as a generation-completeness probe.
(def ^:private proto-specs
  [{:file "semidx/runtime/grpc/v1/runtime.proto"
    :grpc? true
    :expect "semidx/runtime/grpc/v1/RuntimeServiceGrpc.java"}
   {:file "scip/scip.proto"
    :grpc? false
    :expect "scip/Scip.java"}])
(def ^:private generated-src-dir "src-generated/java")
(def ^:private class-dir "target/classes")
(def ^:private cache-root ".cache/semidx/protobuf")
(def ^:private maven-base-url "https://repo.maven.apache.org/maven2")

(def ^:private tool-specs
  {:protoc
   {:artifact-path "com/google/protobuf/protoc"
    :artifact-name "protoc"
    :version "3.25.1"
    :binary-name "protoc"
    :sha256
    {"osx-aarch_64" "b6ed65c0d20a9ab88ec6995644f747d557cb9a087eab0152fef5367c34645dc3"
     "linux-x86_64" "936e423041c6977036208366507964d5615782b5a450ec8d3d52ff557ffc7101"}}

   :grpc-java
   {:artifact-path "io/grpc/protoc-gen-grpc-java"
    :artifact-name "protoc-gen-grpc-java"
    :version "1.63.0"
    :binary-name "protoc-gen-grpc-java"
    :sha256
    {"osx-aarch_64" "28290117a2ee9ea60f50f94273ab139dc2b3be4b8f2a557bef7e6efefee5b363"
     "linux-x86_64" "0e3e8db80ba1fbddeed97ea3220b52cfaa95764ff8bf00716df7322883ce47e8"}}})

(defn- normalized-arch [arch]
  (case (str/lower-case arch)
    ("aarch64" "arm64") "aarch_64"
    ("amd64" "x86_64") "x86_64"
    nil))

(defn platform-classifier []
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        arch (normalized-arch (System/getProperty "os.arch"))
        os-prefix (cond
                    (str/includes? os-name "mac") "osx"
                    (str/includes? os-name "linux") "linux"
                    (str/includes? os-name "windows") "windows"
                    :else nil)]
    (or (when (and os-prefix arch)
          (str os-prefix "-" arch))
        (throw (ex-info "Unsupported protobuf toolchain platform"
                        {:type :unsupported_protobuf_toolchain_platform
                         :os_name (System/getProperty "os.name")
                         :os_arch (System/getProperty "os.arch")})))))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [read-count (.read input buffer)]
          (when (pos? read-count)
            (.update digest buffer 0 read-count)
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- artifact-file-name [{:keys [artifact-name version]} classifier]
  (str artifact-name "-" version "-" classifier ".exe"))

(defn- artifact-url [{:keys [artifact-path version] :as spec} classifier]
  (str maven-base-url "/" artifact-path "/" version "/"
       (artifact-file-name spec classifier)))

(defn- cached-tool-file [{:keys [binary-name version]} classifier]
  (io/file cache-root binary-name version classifier
           (str binary-name (when (str/starts-with? classifier "windows-") ".exe"))))

(defn- download-tool! [url ^File target expected-sha]
  (io/make-parents target)
  (try
    (with-open [input (.openStream (.toURL (URI/create url)))
                output (io/output-stream target)]
      (io/copy input output))
    (let [actual-sha (sha256 target)]
      (when-not (= expected-sha actual-sha)
        (throw (ex-info "Downloaded protobuf tool checksum mismatch"
                        {:type :protobuf_tool_checksum_mismatch
                         :url url
                         :path (.getPath target)
                         :expected_sha256 expected-sha
                         :actual_sha256 actual-sha}))))
    (when-not (.setExecutable target true)
      (throw (ex-info "Unable to make protobuf tool executable"
                      {:type :protobuf_tool_not_executable
                       :path (.getPath target)})))
    target
    (catch Throwable error
      (Files/deleteIfExists (.toPath target))
      (throw error))))

(defn- ensure-tool! [tool-key classifier]
  (let [{:keys [sha256] :as spec} (get tool-specs tool-key)
        expected-sha (get sha256 classifier)
        target (cached-tool-file spec classifier)]
    (when-not expected-sha
      (throw (ex-info "Protobuf toolchain classifier has not been validated"
                      {:type :unvalidated_protobuf_toolchain_classifier
                       :tool tool-key
                       :classifier classifier
                       :validated_classifiers (sort (keys sha256))})))
    (if (and (.isFile target) (= expected-sha (sha256 target)))
      (do
        (.setExecutable target true)
        target)
      (download-tool! (artifact-url spec classifier) target expected-sha))))

(defn- toolchain []
  (let [classifier (platform-classifier)]
    {:classifier classifier
     :protoc (ensure-tool! :protoc classifier)
     :grpc-java (ensure-tool! :grpc-java classifier)}))

(defn- run-command! [command]
  (let [builder (doto (ProcessBuilder. ^java.util.List (mapv str command))
                  (.directory (io/file "."))
                  (.inheritIO))
        process (.start builder)
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (throw (ex-info "External build command failed"
                      {:type :external_build_command_failed
                       :command (mapv str command)
                       :exit_code exit-code})))))

(defn- strip-trailing-whitespace!
  "protoc emits lines with trailing spaces in the generated Java. Normalize them
  so ordinary whitespace gates stay green. `grpc-verify-generated` hashes this
  same normalized output, so the committed sources and a fresh generation still
  match byte for byte."
  [output-path]
  (doseq [^File file (file-seq (io/file output-path))
          :when (and (.isFile file) (str/ends-with? (.getName file) ".java"))]
    (let [original (slurp file)
          cleaned (str/replace original #"(?m)[ \t]+$" "")]
      (when-not (= original cleaned)
        (spit file cleaned)))))

(defn- generate-into! [output-dir]
  (let [{:keys [classifier protoc grpc-java]} (toolchain)
        output-path (str (b/resolve-path output-dir))]
    (b/delete {:path output-path})
    (.mkdirs (io/file output-path))
    (doseq [{:keys [file grpc? expect]} proto-specs]
      (let [command (concat [(.getAbsolutePath ^File protoc)
                             (str "--proto_path=" (b/resolve-path proto-root))
                             (str "--java_out=" output-path)]
                            (when grpc?
                              [(str "--plugin=protoc-gen-grpc-java=" (.getAbsolutePath ^File grpc-java))
                               (str "--grpc-java_out=" output-path)])
                            [(str (b/resolve-path (str proto-root "/" file)))])
            expected (io/file output-path expect)]
        (run-command! (vec command))
        (when-not (.isFile expected)
          (throw (ex-info "protobuf code generation did not produce the expected stub"
                          {:type :proto_generation_incomplete
                           :classifier classifier
                           :proto file
                           :expected_file (.getPath expected)})))))
    (strip-trailing-whitespace! output-path)
    output-path))

(defn- java-file-hashes [root-path]
  (let [root (io/file root-path)
        root-nio (.toPath root)]
    (into (sorted-map)
          (for [^File file (file-seq root)
                :when (and (.isFile file) (str/ends-with? (.getName file) ".java"))]
            [(str/replace (str (.relativize root-nio (.toPath file))) "\\" "/")
             (sha256 file)]))))

(defn- generated-diff [expected-root actual-root]
  (let [expected (java-file-hashes expected-root)
        actual (java-file-hashes actual-root)
        expected-paths (set (keys expected))
        actual-paths (set (keys actual))]
    {:added (sort (set/difference actual-paths expected-paths))
     :removed (sort (set/difference expected-paths actual-paths))
     :changed (sort (for [path (set/intersection expected-paths actual-paths)
                          :when (not= (get expected path) (get actual path))]
                      path))}))

(defn- with-temp-dir [prefix f]
  (let [path (str (Files/createTempDirectory prefix
                                             (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f path)
      (finally
        (b/delete {:path path})))))

(defn grpc-toolchain [_]
  (let [{:keys [classifier protoc grpc-java]} (toolchain)]
    (println (str "protobuf_toolchain_classifier=" classifier))
    (println (str "protoc=" (.getPath ^File protoc)))
    (println (str "protoc_gen_grpc_java=" (.getPath ^File grpc-java)))))

(defn grpc-generate [_]
  (with-temp-dir
    "semidx-grpc-generate-"
    (fn [temp-dir]
      (generate-into! temp-dir)
      (b/delete {:path generated-src-dir})
      (b/copy-dir {:src-dirs [temp-dir] :target-dir generated-src-dir})
      (println (str "generated_java_sources=" (count (java-file-hashes generated-src-dir)))))))

(defn grpc-verify-generated [_]
  (with-temp-dir
    "semidx-grpc-verify-"
    (fn [temp-dir]
      (generate-into! temp-dir)
      (let [diff (generated-diff generated-src-dir temp-dir)]
        (when (some seq (vals diff))
          (throw (ex-info "Committed gRPC Java sources differ from pinned code generation"
                          (assoc diff :type :grpc_generated_sources_drift))))
        (println (str "grpc_generated_sources_verified="
                      (count (java-file-hashes temp-dir))))))))

(defn compile-java [_]
  (when (empty? (java-file-hashes generated-src-dir))
    (throw (ex-info "No generated Java sources found; run grpc-generate first"
                    {:type :generated_java_sources_missing
                     :path generated-src-dir})))
  (b/javac {:src-dirs [generated-src-dir]
            :class-dir class-dir
            :basis (b/create-basis {:project "deps.edn"})
            :javac-opts ["--release" "17"]})
  (println (str "compiled_java_sources=" (count (java-file-hashes generated-src-dir)))))
