(ns semidx.runtime.query-anchors
  (:require [clojure.string :as str]))

(def ^:private max-anchor-candidates 8)

(defn- distinctv [xs]
  (->> xs
       (remove str/blank?)
       distinct
       vec))

(defn- normalize-symbolish [s]
  (-> (str s)
      str/trim
      str/lower-case
      (str/replace "_" "-")))

(defn- details-text [intent]
  (some-> (:details intent) str str/trim))

(defn qualified-symbol-candidates [details]
  (->> (re-seq #"[A-Za-z][A-Za-z0-9_.-]*/[A-Za-z][A-Za-z0-9_?!*\-]*" (or details ""))
       distinctv
       (take max-anchor-candidates)
       vec))

(defn module-candidates [details]
  (let [qualified-symbols (qualified-symbol-candidates details)
        module-from-symbols (map #(first (str/split % #"/" 2)) qualified-symbols)
        dotted-modules (re-seq #"[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)+" (or details ""))]
    (->> (concat module-from-symbols dotted-modules)
         distinctv
         (take max-anchor-candidates)
         vec)))

(defn path-candidates [details]
  (->> (re-seq #"(?:src|test|lib|app|scripts|fixtures)/[A-Za-z0-9_./-]+" (or details ""))
       distinctv
       (take max-anchor-candidates)
       vec))

(defn suspected-symbol-candidates [details]
  (let [qualified-symbols (qualified-symbol-candidates details)
        symbolish-tokens (->> (re-seq #"[A-Za-z][A-Za-z0-9_/-]*[_-][A-Za-z0-9_?!*\-]+" (or details ""))
                              (map normalize-symbolish))]
    (->> (concat qualified-symbols symbolish-tokens)
         distinctv
         (take max-anchor-candidates)
         vec)))

(defn infer-anchors [intent]
  (let [details (details-text intent)
        paths (path-candidates details)
        modules (module-candidates details)
        suspected-symbols (suspected-symbol-candidates details)]
    {:targets {}
     :hints (cond-> {}
              (seq paths) (assoc :preferred_paths paths)
              (seq modules) (assoc :preferred_modules modules)
              (seq suspected-symbols) (assoc :suspected_symbols suspected-symbols))}))
