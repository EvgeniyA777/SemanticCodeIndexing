(ns semantic-code-indexing.runtime.cli
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [semantic-code-indexing.core :as sci]))

(defn- parse-args [args]
  (loop [m {:root_path "."} xs args]
    (if (empty? xs)
      m
      (let [[k v & rest] xs]
        (case k
          "--root" (recur (assoc m :root_path v) rest)
          "--query" (recur (assoc m :query_path v) rest)
          "--out" (recur (assoc m :out_path v) rest)
          (recur m rest))))))

(defn- read-json [path]
  (with-open [rdr (io/reader path)]
    (json/read rdr :key-fn keyword)))

(defn- write-json [path data]
  (with-open [w (io/writer path)]
    (json/write data w :indent true)))

(defn -main [& args]
  (let [{:keys [root_path query_path out_path]} (parse-args args)]
    (when-not query_path
      (println "Usage: clojure -M:runtime --root <repo-root> --query <query.json> [--out <output.json>]")
      (System/exit 1))
    (let [query (read-json query_path)
          index (sci/create-index {:root_path root_path})
          result (sci/resolve-context index query)]
      (if out_path
        (do (write-json out_path result)
            (println (str "wrote " out_path)))
        (println (json/write-str result :escape-slash false)))
      (System/exit 0))))
