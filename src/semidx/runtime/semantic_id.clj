(ns semidx.runtime.semantic-id
  (:require [clojure.string :as str]))

(def ^:private semantic-id-version "v1")

(defn version []
  semantic-id-version)

(defn- sha1-hex [value]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")
        bytes (.digest md (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- canonicalize [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (str %1) (str %2)))
          (map (fn [[k v]] [k (canonicalize v)]))
          value)

    (vector? value)
    (mapv canonicalize value)

    (set? value)
    (->> value
         (map canonicalize)
         (sort-by pr-str)
         vec)

    (sequential? value)
    (->> value
         (map canonicalize)
         vec)

    :else value))

(defn- stable-pr-str [value]
  (pr-str (canonicalize value)))

(defn- normalize-strings [xs]
  (->> xs
       (map str)
       (remove str/blank?)
       distinct
       sort
       vec))

(defn- infer-language [unit]
  (or (:language unit)
      (let [path (str (:path unit))]
        (cond
          (or (str/ends-with? path ".clj")
              (str/ends-with? path ".cljc")
              (str/ends-with? path ".cljs")) "clojure"
          (str/ends-with? path ".java") "java"
          (or (str/ends-with? path ".ex")
              (str/ends-with? path ".exs")) "elixir"
          (str/ends-with? path ".py") "python"
          (or (str/ends-with? path ".ts")
              (str/ends-with? path ".tsx")) "typescript"
          (str/ends-with? path ".lua") "lua"
          :else "unknown"))))

(defn- symbol-owner [symbol]
  (let [symbol* (str (or symbol ""))]
    (cond
      (str/includes? symbol* "/") (first (str/split symbol* #"/" 2))
      (str/includes? symbol* "#") (first (str/split symbol* #"#" 2))
      :else nil)))

(defn- symbol-local-name [symbol]
  (some-> symbol str (str/split #"[#/]") last))

(defn- semantic-slot [unit]
  (let [language (infer-language unit)
        symbol (:symbol unit)
        owner (or (:module unit) (symbol-owner symbol))
        fallback-anchor (when-not (seq symbol)
                          {:path (:path unit)
                           :start_line (:start_line unit)
                           :end_line (:end_line unit)})]
    {:semantic_id_version semantic-id-version
     :language language
     :kind (:kind unit)
     :owner owner
     :symbol symbol
     :symbol_local_name (symbol-local-name symbol)
     :form_operator (:form_operator unit)
     :dispatch_value (:dispatch_value unit)
     :method_arity (:method_arity unit)
     :method_signature_key (:method_signature_key unit)
     :fallback_anchor fallback-anchor}))

(defn- implementation-shape [unit]
  {:semantic_id_version semantic-id-version
   :language (infer-language unit)
   :kind (:kind unit)
   :form_operator (:form_operator unit)
   :dispatch_value (:dispatch_value unit)
   :method_arity (:method_arity unit)
   :method_signature_key (:method_signature_key unit)
   :calls (normalize-strings (:calls unit))
   :call_tokens (normalize-strings (:call_tokens unit))
   :generated_calls (normalize-strings (:generated_calls unit))
   :imports (normalize-strings (:imports unit))})

(defn public-shape [unit]
  {:semantic_id_version semantic-id-version
   :language (infer-language unit)
   :kind (:kind unit)
   :form_operator (:form_operator unit)
   :dispatch_value (:dispatch_value unit)
   :method_arity (:method_arity unit)
   :method_signature_key (:method_signature_key unit)
   :signature (:signature unit)
   :summary (:summary unit)})

(defn semantic-id [unit]
  (str semantic-id-version ":" (sha1-hex (stable-pr-str (semantic-slot unit)))))

(defn semantic-fingerprint [unit]
  (str semantic-id-version ":" (sha1-hex (stable-pr-str (implementation-shape unit)))))

(defn public-shape-fingerprint [unit]
  (str semantic-id-version ":" (sha1-hex (stable-pr-str (public-shape unit)))))

(defn enrich-unit [unit]
  (let [language (infer-language unit)
        version-matches? (= semantic-id-version (:semantic_id_version unit))
        unit* (assoc unit :language language)]
    (if (and version-matches?
             (seq (:semantic_id unit*))
             (seq (:semantic_fingerprint unit*)))
      unit*
      (assoc unit*
             :semantic_id_version semantic-id-version
             :semantic_id (semantic-id unit*)
             :semantic_fingerprint (semantic-fingerprint unit*)))))

(defn enrich-units [units]
  (mapv enrich-unit units))

(defn enrich-index [index]
  (if (and (map? (:units index))
           (vector? (:unit_order index)))
    (let [ordered (->> (:unit_order index)
                       (map #(get (:units index) %))
                       (remove nil?)
                       enrich-units)
          units-by-id (into {} (map (juxt :unit_id identity) ordered))]
      (assoc index :units units-by-id))
    index))
