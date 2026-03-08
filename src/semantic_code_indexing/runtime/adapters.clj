(ns semantic-code-indexing.runtime.adapters
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private clj-def-re
  #"^\s*\((defn-|defn|defmacro|defmulti|defmethod|def|deftest)\s+([^\s\[\]\)]+)")

(def ^:private clj-call-re
  #"\(([a-zA-Z][a-zA-Z0-9\-\.!/<>\?]*)")

(def ^:private clj-require-re
  #"\[([a-zA-Z0-9\._\-]+)(?:\s+:as\s+[a-zA-Z0-9_\-]+)?\]")

(def ^:private java-package-re #"^\s*package\s+([a-zA-Z0-9_\.]+)\s*;")
(def ^:private java-import-re #"^\s*import\s+([a-zA-Z0-9_\.]+)\s*;")
(def ^:private java-class-re #"^\s*(?:public\s+)?(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)")
(def ^:private java-method-re
  #"^\s*(?:public|private|protected|static|final|native|synchronized|abstract|default|\s)+[a-zA-Z0-9_<>,\[\]\.?\s]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?:\{|throws|;)")
(def ^:private java-call-re #"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")

(def ^:private clj-call-stop
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "deftest" "ns"
    "let" "if" "when" "when-not" "cond" "case" "loop" "recur" "do" "fn"
    "for" "doseq" "->" "->>" "some->" "some->>" "as->" "try" "catch" "finally"
    "and" "or" "not" "comment"})

(def ^:private java-call-stop
  #{"if" "for" "while" "switch" "catch" "return" "throw" "new" "super" "this" "synchronized"})

(defn language-by-path [path]
  (cond
    (or (str/ends-with? path ".clj")
        (str/ends-with? path ".cljc")
        (str/ends-with? path ".cljs")) "clojure"
    (str/ends-with? path ".java") "java"
    :else nil))

(defn source-path? [path]
  (boolean (language-by-path path)))

(defn- slurp-lines [file]
  (-> file slurp str/split-lines vec))

(defn- trim-signature [line]
  (-> line str/trim (subs 0 (min 180 (count (str/trim line))))))

(defn- unit-end-lines [starts total-lines]
  (let [pairs (partition 2 1 (concat starts [(inc total-lines)]))]
    (mapv (fn [[s n]] (max s (dec n))) pairs)))

(defn- clj-kind [kw path]
  (cond
    (= kw "deftest") "test"
    (or (= kw "defn") (= kw "defn-")) "function"
    (= kw "defmethod") "method"
    (= kw "defmacro") "function"
    (= kw "def") "section"
    :else (if (str/includes? path "/test/") "test" "function")))

(defn- extract-clj-calls [body]
  (->> (re-seq clj-call-re body)
       (map second)
       (remove clj-call-stop)
       distinct
       vec))

(defn- parse-clojure-regex [path lines]
  (let [line-count (count lines)
        ns-name (some (fn [line] (some-> (re-find #"^\s*\(ns\s+([^\s\)]+).*" line) second)) lines)
        imports (->> lines
                     (mapcat #(map second (re-seq clj-require-re %)))
                     distinct
                     vec)
        defs (->> (map-indexed vector lines)
                  (keep (fn [[idx line]]
                          (when-let [[_ kw raw-sym] (re-find clj-def-re line)]
                            {:start-line (inc idx)
                             :kind (clj-kind kw path)
                             :raw-symbol raw-sym
                             :signature (trim-signature line)}))))
        starts (mapv :start-line defs)
        ends (unit-end-lines starts line-count)
        units (->> (map vector defs ends)
                   (map (fn [[d end-line]]
                          (let [start-line (:start-line d)
                                body-lines (subvec lines (dec start-line) end-line)
                                body (str/join "\n" body-lines)
                                symbol (if ns-name
                                         (if (str/includes? (:raw-symbol d) "/")
                                           (:raw-symbol d)
                                           (str ns-name "/" (:raw-symbol d)))
                                         (:raw-symbol d))
                                unit-id (str path "::" symbol)]
                            {:unit_id unit-id
                             :kind (:kind d)
                             :symbol symbol
                             :path path
                             :module ns-name
                             :start_line start-line
                             :end_line end-line
                             :signature (:signature d)
                             :summary (str (:kind d) " " symbol)
                             :docstring_excerpt nil
                             :imports imports
                             :calls (extract-clj-calls body)
                             :parser_mode "fallback"})))
                   vec)]
    {:language "clojure"
     :module ns-name
     :imports imports
     :units units
     :diagnostics [{:code "parser_fallback" :summary "Clojure analyzed via regex fallback."}]
     :parser_mode "fallback"}))

(defn- kondo-defined-kind [defined-by path]
  (let [d (str defined-by)]
    (cond
      (or (= d "clojure.core/deftest") (str/ends-with? d "/deftest") (str/includes? path "/test/")) "test"
      (or (= d "clojure.core/defn") (str/ends-with? d "/defn") (= d "clojure.core/defn-") (str/ends-with? d "/defn-")) "function"
      (or (= d "clojure.core/defmethod") (str/ends-with? d "/defmethod")) "method"
      (or (= d "clojure.core/defmacro") (str/ends-with? d "/defmacro")) "function"
      (= d "clojure.core/def") "section"
      :else "function")))

(defn- same-file? [expected actual]
  (let [e (some-> expected io/file .getCanonicalPath)
        a (some-> actual io/file .getCanonicalPath)]
    (= e a)))

(defn- safe-line [lines n]
  (let [idx (dec (max 1 n))]
    (if (< idx (count lines))
      (trim-signature (nth lines idx))
      "")))

(defn- usage->call-token [u]
  (let [to-ns (:to u)
        nm (:name u)]
    (cond
      (and to-ns nm) (str to-ns "/" nm)
      nm (str nm)
      :else nil)))

(defn- parse-clojure-kondo [root-path path lines]
  (let [abs (-> (io/file root-path path) .getCanonicalPath)
        config "{:linters {:namespace-name-mismatch {:level :off}} :output {:format :edn :analysis true :canonical-paths true}}"
        {:keys [exit out err]} (sh/sh "clj-kondo" "--lint" abs "--cache" "false" "--config" config "--fail-level" "error")
        parsed (try (edn/read-string out) (catch Exception _ nil))
        analysis (:analysis parsed)
        var-defs (->> (:var-definitions analysis) (filter #(same-file? abs (:filename %))) vec)
        ns-usages (->> (:namespace-usages analysis) (filter #(same-file? abs (:filename %))) vec)
        var-usages (->> (:var-usages analysis) (filter #(same-file? abs (:filename %))) vec)
        imports (->> ns-usages (keep :to) (map str) distinct vec)
        calls-by-var
        (reduce (fn [acc u]
                  (if-let [from-var (:from-var u)]
                    (if-let [token (usage->call-token u)]
                      (if (contains? clj-call-stop token)
                        acc
                        (update acc (str from-var) (fnil conj #{}) token))
                      acc)
                    acc))
                {}
                var-usages)
        units
        (->> var-defs
             (map (fn [d]
                    (let [ns-name (str (:ns d))
                          nm (str (:name d))
                          sym (str ns-name "/" nm)
                          start (max 1 (int (or (:name-row d) (:row d) 1)))
                          end (max start (int (or (:end-row d) start)))]
                      {:unit_id (str path "::" sym)
                       :kind (kondo-defined-kind (:defined-by d) path)
                       :symbol sym
                       :path path
                       :module ns-name
                       :start_line start
                       :end_line end
                       :signature (safe-line lines start)
                       :summary (str "function " sym)
                       :docstring_excerpt nil
                       :imports imports
                       :calls (->> (get calls-by-var nm #{}) sort vec)
                       :parser_mode "full"})))
             vec)
        findings
        (->> (:findings parsed)
             (filter #(and (same-file? abs (:filename %))
                           (#{:error :warning} (:level %))))
             (mapv (fn [f]
                     {:code (str "kondo_" (name (:type f)))
                      :summary (:message f)})))]
    (cond
      (seq units)
      {:language "clojure"
       :module (some-> units first :module)
       :imports imports
       :units units
       :diagnostics findings
       :parser_mode "full"}

      parsed
      (let [fallback (parse-clojure-regex path lines)
            extra (cond-> [{:code "kondo_no_units" :summary "clj-kondo returned no var definitions for file."}]
                    (seq err) (conj {:code "kondo_stderr"
                                     :summary (subs err 0 (min 200 (count err)))}))]
        (-> fallback
            (update :diagnostics into extra)
            (assoc :parser_mode "fallback")))

      :else
      (let [fallback (parse-clojure-regex path lines)]
        (-> fallback
            (update :diagnostics into [{:code "kondo_parse_failed"
                                        :summary "Unable to parse clj-kondo EDN output."}
                                       {:code "kondo_exit"
                                        :summary (str "clj-kondo exit=" exit)}])
            (assoc :parser_mode "fallback"))))))

(defn- parse-clojure [root-path path lines {:keys [clojure_engine tree_sitter_enabled]
                                            :or {clojure_engine :clj-kondo
                                                 tree_sitter_enabled false}}]
  (let [parsed (case clojure_engine
                 :regex (parse-clojure-regex path lines)
                 :clj-kondo (parse-clojure-kondo root-path path lines)
                 (parse-clojure-kondo root-path path lines))]
    (if tree_sitter_enabled
      (update parsed :diagnostics conj {:code "tree_sitter_optional" :summary "tree-sitter path requested but not enabled in MVP runtime."})
      parsed)))

(defn- java-kind [path method-name]
  (if (or (str/includes? (str/lower-case path) "/test/")
          (str/ends-with? method-name "Test")
          (str/starts-with? method-name "test"))
    "test"
    "method"))

(defn- extract-java-calls [body]
  (->> (re-seq java-call-re body)
       (map second)
       (remove java-call-stop)
       distinct
       vec))

(defn- parse-java [path lines]
  (let [line-count (count lines)
        pkg (some (fn [line] (some-> (re-find java-package-re line) second)) lines)
        imports (->> lines
                     (keep (fn [line] (some-> (re-find java-import-re line) second)))
                     distinct
                     vec)
        class-spots (->> (map-indexed vector lines)
                         (keep (fn [[idx line]]
                                 (when-let [[_ c] (re-find java-class-re line)]
                                   {:line (inc idx) :class c})))
                         vec)
        methods (->> (map-indexed vector lines)
                     (keep (fn [[idx line]]
                             (when-let [[_ m] (re-find java-method-re line)]
                               {:start-line (inc idx)
                                :method m
                                :class (->> class-spots
                                            (filter #(<= (:line %) (inc idx)))
                                            last
                                            :class)
                                :signature (trim-signature line)})))
                     vec)
        starts (mapv :start-line methods)
        ends (unit-end-lines starts line-count)
        units (->> (map vector methods ends)
                   (map (fn [[m end-line]]
                          (let [start-line (:start-line m)
                                cls (or (:class m) "UnknownClass")
                                symbol (str (when pkg (str pkg ".")) cls "#" (:method m))
                                unit-id (str path "::" symbol)
                                body (->> (subvec lines (dec start-line) end-line)
                                          (str/join "\n"))]
                            {:unit_id unit-id
                             :kind (java-kind path (:method m))
                             :symbol symbol
                             :path path
                             :module (if pkg (str pkg "." cls) cls)
                             :start_line start-line
                             :end_line end-line
                             :signature (:signature m)
                             :summary (str "method " symbol)
                             :docstring_excerpt nil
                             :imports imports
                             :calls (extract-java-calls body)
                             :parser_mode "full"})))
                   vec)]
    {:language "java"
     :module pkg
     :imports imports
     :units units
     :diagnostics []
     :parser_mode "full"}))

(defn- fallback-unit [path lines language reason]
  (let [line-count (max 1 (count lines))]
    {:language (or language "unknown")
     :module nil
     :imports []
     :units [{:unit_id (str path "::fallback")
              :kind "section"
              :symbol (str path "::fallback")
              :path path
              :module nil
              :start_line 1
              :end_line line-count
              :signature (if (seq lines) (trim-signature (first lines)) "")
              :summary "fallback section"
              :docstring_excerpt nil
              :imports []
              :calls []
              :parser_mode "fallback"}]
     :diagnostics [{:code "parser_fallback" :summary reason}]
     :parser_mode "fallback"}))

(defn parse-file
  ([root-path file-path] (parse-file root-path file-path {}))
  ([root-path file-path parser-opts]
   (let [abs (io/file root-path file-path)
         language (language-by-path file-path)]
     (try
       (let [lines (slurp-lines abs)]
         (case language
           "clojure" (parse-clojure root-path file-path lines parser-opts)
           "java" (parse-java file-path lines)
           (fallback-unit file-path lines language "unsupported_language")))
       (catch Exception _
         (let [lines (try (slurp-lines abs) (catch Exception _ []))]
           (fallback-unit file-path lines language "parse_exception")))))))
