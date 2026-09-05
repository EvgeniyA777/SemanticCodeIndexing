(ns semidx.runtime.grpc-prep
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [javax.tools DiagnosticCollector JavaCompiler ToolProvider]))

(def ^:private generated-src-dir "src-generated/java")
(def ^:private class-dir "target/classes")

(def ^:private generated-package-dir
  (io/file class-dir "semidx/runtime/grpc/v1"))

(def ^:private compile-marker
  (io/file class-dir ".semidx-grpc-compiled"))
(def ^:private compile-lock (Object.))

(defn- java-source-files []
  (let [root (io/file generated-src-dir)]
    (->> (file-seq root)
         (filter #(.isFile ^File %))
         (filter #(str/ends-with? (.getName ^File %) ".java"))
         (sort-by #(.getPath ^File %))
         vec)))

(defn- class-file-for [^File source]
  (let [source-root (.toPath (io/file generated-src-dir))
        relative (str (.relativize source-root (.toPath source)))
        class-relative (str/replace relative #"\.java$" ".class")]
    (io/file class-dir class-relative)))

(defn- source-manifest [sources]
  (let [source-root (.toPath (io/file generated-src-dir))]
    (->> sources
         (map (fn [^File source]
                (str (str/replace (str (.relativize source-root (.toPath source))) "\\" "/")
                     "\t" (.length source)
                     "\t" (.lastModified source))))
         (str/join "\n"))))

(defn- delete-tree! [^File root]
  (when (.exists root)
    (doseq [^File file (reverse (file-seq root))]
      (io/delete-file file true))))

(defn grpc-classes-current? []
  (let [sources (java-source-files)]
    (boolean
     (and (seq sources)
          (.isFile compile-marker)
          (= (source-manifest sources) (slurp compile-marker))
          (every? (fn [^File source]
                    (let [^File class-file (class-file-for source)]
                      (and (.isFile class-file)
                           (>= (.lastModified class-file)
                               (.lastModified source)))))
                  sources)))))

(defn- compile-generated-java! []
  (let [sources (java-source-files)
        ^JavaCompiler compiler (ToolProvider/getSystemJavaCompiler)]
    (when-not (seq sources)
      (throw (ex-info "Committed generated gRPC Java sources are missing"
                      {:type :generated_java_sources_missing
                       :path generated-src-dir})))
    (when-not compiler
      (throw (ex-info "A JDK with javac is required to compile generated gRPC sources"
                      {:type :jdk_compiler_unavailable
                       :java_home (System/getProperty "java.home")})))
    (.mkdirs (io/file class-dir))
    (io/delete-file compile-marker true)
    (delete-tree! generated-package-dir)
    (let [diagnostics (DiagnosticCollector.)
          file-manager (.getStandardFileManager compiler diagnostics nil nil)]
      (try
        (let [units (.getJavaFileObjectsFromFiles file-manager sources)
              options ["-classpath" (System/getProperty "java.class.path")
                       "-d" (.getAbsolutePath (io/file class-dir))
                       "--release" "17"]
              task (.getTask compiler nil file-manager diagnostics options nil units)]
          (when-not (.call task)
            (throw (ex-info "javac failed for generated gRPC sources"
                            {:type :generated_java_compilation_failed
                             :diagnostics (mapv str (.getDiagnostics diagnostics))}))))
        (finally
          (.close file-manager))))
    (spit compile-marker (source-manifest sources))
    {:status :compiled
     :source_count (count sources)}))

(defn ensure-grpc-classes! []
  (locking compile-lock
    (if (grpc-classes-current?)
      {:status :current
       :source_count (count (java-source-files))}
      (compile-generated-java!))))
