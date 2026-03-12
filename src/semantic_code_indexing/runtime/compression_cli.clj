(ns semantic-code-indexing.runtime.compression-cli
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [semantic-code-indexing.core :as sci]
            [semantic-code-indexing.runtime.compression :as compression]))

(defn- parse-args [args]
  (loop [m {:root_path "."
            :positionals []}
         xs args]
    (if (empty? xs)
      m
      (let [[k & more] xs]
        (case k
          "--root" (recur (assoc m :root_path (first more)) (rest more))
          "--out" (recur (assoc m :out_path (first more)) (rest more))
          "--format" (recur (assoc m :format (keyword (first more))) (rest more))
          "--changed" (recur (assoc m :changed_only true) more)
          "--force-hook" (recur (assoc m :force true) more)
          (recur (update m :positionals conj k) more))))))

(defn- usage! []
  (binding [*out* *err*]
    (println "Usage: clojure -M:ccc <init|refresh|check|summary|export> [--root <repo-root>] [--changed] [--out <path>] [--format markdown|json|edn|dot]")
    (flush))
  (System/exit 1))

(defn- build-index [root-path]
  (sci/create-index {:root_path root-path}))

(defn- artifact->string [artifact format]
  (case format
    :markdown (:summary_markdown artifact)
    :json (json/write-str artifact :indent true :escape-slash false)
    :edn (pr-str artifact)
    :dot (:dependency_graph_dot artifact)
    (:summary_markdown artifact)))

(defn- write-output! [path content]
  (some-> path java.io.File. .getParentFile .mkdirs)
  (spit path content))

(defn- command-init [{:keys [root_path force]}]
  (let [index (build-index root_path)
        result (sci/init-project-compression! index {:force force})]
    (println (str "compression " (get-in result [:refresh :status]) " at " (get-in result [:refresh :paths :markdown_path])))
    (println (str "hook " (if (get-in result [:hook :installed?]) "installed" "skipped")
                  " (" (get-in result [:hook :reason]) ")"))
    (System/exit 0)))

(defn- command-refresh [{:keys [root_path changed_only]}]
  (let [index (build-index root_path)
        result (sci/refresh-project-compression index {:changed_only changed_only})]
    (println (str "compression " (:status result) " at " (get-in result [:paths :markdown_path])))
    (System/exit 0)))

(defn- command-check [{:keys [root_path]}]
  (let [index (build-index root_path)
        result (sci/compression-drift-report index)]
    (if (:stale? result)
      (do
        (binding [*out* *err*]
          (println "code context summary is stale; run `clojure -M:ccc refresh --root" root_path "`")
          (flush))
        (System/exit 1))
      (do
        (println "code context summary is up to date")
        (System/exit 0)))))

(defn- command-summary [{:keys [root_path out_path]}]
  (let [index (build-index root_path)
        artifact (sci/compress-project index)]
    (if out_path
      (do
        (write-output! out_path (:summary_markdown artifact))
        (println (str "wrote " out_path)))
      (print (:summary_markdown artifact)))
    (System/exit 0)))

(defn- command-export [{:keys [root_path out_path format] :or {format :markdown}}]
  (let [index (build-index root_path)
        artifact (sci/compress-project index)
        content (artifact->string artifact format)]
    (if out_path
      (do
        (write-output! out_path content)
        (println (str "wrote " out_path)))
      (println content))
    (System/exit 0)))

(defn -main [& args]
  (let [{:keys [positionals] :as parsed} (parse-args args)
        command (first positionals)]
    (case command
      "init" (command-init parsed)
      "refresh" (command-refresh parsed)
      "check" (command-check parsed)
      "summary" (command-summary parsed)
      "export" (command-export parsed)
      (usage!))))
