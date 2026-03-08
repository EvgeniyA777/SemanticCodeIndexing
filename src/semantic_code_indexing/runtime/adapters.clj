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
(def ^:private java-import-re #"^\s*import\s+([a-zA-Z0-9_\.\*]+)\s*;")
(def ^:private java-class-re #"^\s*(?:public\s+)?(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)")
(def ^:private java-method-re
  #"^\s*(?:(public|private|protected)\s+)?(?:(?:static|final|native|synchronized|abstract|default)\s+)*([A-Za-z0-9_<>,\[\]\.\?]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:\{|throws|;)")
(def ^:private java-call-re #"\b([A-Za-z_][A-Za-z0-9_\.]*)\s*\(")

(def ^:private ex-module-re #"^\s*defmodule\s+([A-Za-z0-9_\.]+)\s+do")
(def ^:private ex-import-re #"^\s*(?:import|require|use)\s+([A-Za-z0-9_\.]+)")
(def ^:private ex-alias-line-re #"^\s*alias\s+(.+)$")
(def ^:private ex-def-re #"^\s*(defdelegate|defp?|defmacro|defmacrop)\s+([a-zA-Z_][a-zA-Z0-9_!?]*)")
(def ^:private ex-test-re #"^\s*test\s+\"([^\"]+)\"\s+do")
(def ^:private ex-call-re #"\b([A-Za-z_][A-Za-z0-9_\.!?]*)\s*\(")

(def ^:private py-import-re #"^\s*import\s+([a-zA-Z0-9_\.]+)")
(def ^:private py-from-import-re #"^\s*from\s+([a-zA-Z0-9_\.]+)\s+import\s+([A-Za-z0-9_,\s\*]+)")
(def ^:private py-class-re #"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)")
(def ^:private py-def-re #"^\s*(?:async\s+def|def)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
(def ^:private py-call-re #"\b([A-Za-z_][A-Za-z0-9_\.]*)\s*\(")

(def ^:private clj-call-stop
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "deftest" "ns"
    "let" "if" "when" "when-not" "cond" "case" "loop" "recur" "do" "fn"
    "for" "doseq" "->" "->>" "some->" "some->>" "as->" "try" "catch" "finally"
    "and" "or" "not" "comment"})

(def ^:private java-call-stop
  #{"if" "for" "while" "switch" "catch" "return" "throw" "new" "super" "this" "synchronized"})

(def ^:private ex-call-stop
  #{"if" "case" "cond" "with" "fn" "def" "defp" "defmacro" "defdelegate" "defmodule" "test" "describe" "quote" "unquote" "alias" "import" "require" "use"})

(def ^:private py-call-stop
  #{"if" "for" "while" "return" "yield" "lambda" "class" "def" "print"})

(def ^:private ts-line-re
  #"^\s*(\d+):(\d+)\s*-\s*(\d+):(\d+)\s+(.+?)\s*$")

(defn language-by-path [path]
  (cond
    (or (str/ends-with? path ".clj")
        (str/ends-with? path ".cljc")
        (str/ends-with? path ".cljs")) "clojure"
    (str/ends-with? path ".java") "java"
    (or (str/ends-with? path ".ex")
        (str/ends-with? path ".exs")) "elixir"
    (str/ends-with? path ".py") "python"
    :else nil))

(defn source-path? [path]
  (boolean (language-by-path path)))

(defn- slurp-lines [file]
  (-> file slurp str/split-lines vec))

(defn- trim-signature [line]
  (let [t (str/trim (or line ""))]
    (subs t 0 (min 180 (count t)))))

(defn- unit-end-lines [starts total-lines]
  (let [pairs (partition 2 1 (concat starts [(inc total-lines)]))]
    (mapv (fn [[s n]] (max s (dec n))) pairs)))

(defn- tail-token [token]
  (some-> token str (str/split #"[\./#]") last))

(defn- parse-python-import [line]
  (if-let [[_ from names] (re-find py-from-import-re line)]
    (let [parts (->> (str/split names #",")
                     (map str/trim)
                     (remove str/blank?))]
      (vec (cons from
                 (map (fn [n]
                        (if (= n "*")
                          from
                          (str from "." n)))
                      parts))))
    (when-let [[_ imp] (re-find py-import-re line)]
      [imp])))

(defonce ^:private tree-sitter-availability (atom nil))

(defn- tree-sitter-available? []
  (if (some? @tree-sitter-availability)
    @tree-sitter-availability
    (let [{:keys [exit]} (try
                          (sh/sh "tree-sitter" "--version")
                          (catch Exception _ {:exit 127}))
          available? (zero? (int exit))]
      (reset! tree-sitter-availability available?)
      available?)))

(defn- parser-grammar-path [parser-opts lang]
  (or (get-in parser-opts [:tree_sitter_grammars lang])
      (get-in parser-opts [:tree_sitter_grammars (keyword lang)])
      (get parser-opts (keyword (str "tree_sitter_" (name lang) "_grammar")))
      (System/getenv (case lang
                       :clojure "SCI_TREE_SITTER_CLOJURE_GRAMMAR_PATH"
                       :java "SCI_TREE_SITTER_JAVA_GRAMMAR_PATH"
                       nil))))

(defn- parse-ts-line [line]
  (when-let [[_ sr sc er ec text] (re-find ts-line-re line)]
    (let [plain (str/trim (first (str/split text #"`")))
          source (if (str/includes? plain ":")
                   (second (str/split plain #":\s*" 2))
                   plain)
          node-type (last (str/split (str/trim source) #"\s+"))
          value (some-> (re-find #"`([^`]*)`" text) second)]
      {:indent (count (re-find #"^\s*" line))
       :start-row (parse-long sr)
       :start-col (parse-long sc)
       :end-row (parse-long er)
       :end-col (parse-long ec)
       :text text
       :node-type node-type
       :value value})))

(defn- tree-sitter-cst [abs-path grammar-path]
  (let [{:keys [exit out err]}
        (try
          (sh/sh "tree-sitter" "parse" "--cst" "--grammar-path" grammar-path abs-path)
          (catch Exception e
            {:exit 127 :out "" :err (.getMessage e)}))]
    (if (zero? (int exit))
      {:ok? true
       :lines (->> (str/split-lines out) (keep parse-ts-line) vec)
       :err nil}
      {:ok? false
       :lines []
       :err (or err "tree-sitter parse failed")})))

(defn- add-tree-sitter-diag [parsed enabled? language]
  (if (and enabled? (#{"clojure" "java"} language))
    (if (tree-sitter-available?)
      (update parsed :diagnostics conj {:code "tree_sitter_probe"
                                        :summary "tree-sitter CLI detected."})
      (update parsed :diagnostics conj {:code "tree_sitter_unavailable"
                                        :summary "tree-sitter requested but CLI is unavailable; using adapter parser."}))
    parsed))

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
                                         (:raw-symbol d))]
                            {:unit_id (str path "::" symbol)
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
                                     :summary (subs err 0 (min 220 (count err)))}))]
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

(defn- extract-top-level-list-ranges [ts-lines]
  (let [lists (->> ts-lines (filter #(= "list_lit" (:node-type %))) vec)]
    (->> lists
         (remove (fn [node]
                   (some (fn [outer]
                           (and (not= node outer)
                                (<= (:start-row outer) (:start-row node))
                                (>= (:end-row outer) (:end-row node))
                                (or (< (:start-row outer) (:start-row node))
                                    (> (:end-row outer) (:end-row node)))))
                         lists)))
         (sort-by (juxt :start-row :start-col))
         vec)))

(defn- sym-names-in-range [ts-lines start-row end-row]
  (->> ts-lines
       (filter #(= "sym_name" (:node-type %)))
       (filter #(<= start-row (:start-row %) end-row))
       (sort-by (juxt :start-row :start-col))
       (keep :value)
       vec))

(defn- parse-clojure-tree-sitter [root-path path src-lines parser-opts]
  (let [grammar-path (parser-grammar-path parser-opts :clojure)
        abs (-> (io/file root-path path) .getCanonicalPath)
        imports (->> src-lines (mapcat #(map second (re-seq clj-require-re %))) distinct vec)
        ns-name (some (fn [line] (some-> (re-find #"^\s*\(ns\s+([^\s\)]+).*" line) second)) src-lines)]
    (cond
      (not (tree-sitter-available?))
      {:ok? false
       :reason {:code "tree_sitter_unavailable"
                :summary "tree-sitter CLI is unavailable for clojure tree-sitter parser."}}

      (str/blank? (str grammar-path))
      {:ok? false
       :reason {:code "tree_sitter_missing_grammar"
                :summary "No tree-sitter Clojure grammar path configured."}}

      :else
      (let [{:keys [ok? lines err]} (tree-sitter-cst abs grammar-path)
            ts-lines lines]
        (if-not ok?
          {:ok? false
           :reason {:code "tree_sitter_parse_failed"
                    :summary (str "tree-sitter parse failed: " (subs (str err) 0 (min 220 (count (str err)))))}}
          (let [ranges (extract-top-level-list-ranges ts-lines)
                defs (->> ranges
                          (keep (fn [r]
                                  (let [syms (sym-names-in-range ts-lines (:start-row r) (:end-row r))
                                        op (first syms)
                                        raw-name (second syms)]
                                    (when (and op raw-name (contains? #{"defn" "defn-" "defmacro" "defmulti" "defmethod" "def" "deftest"} op))
                                      {:start-line (inc (:start-row r))
                                       :end-line (inc (:end-row r))
                                       :operator op
                                       :raw-symbol raw-name
                                       :calls (->> (drop 2 syms)
                                                   (remove clj-call-stop)
                                                   distinct
                                                   vec)}))))
                          vec)
                units (->> defs
                           (map (fn [{:keys [start-line end-line operator raw-symbol calls]}]
                                  (let [symbol (if (and ns-name (not (str/includes? raw-symbol "/")))
                                                 (str ns-name "/" raw-symbol)
                                                 raw-symbol)]
                                    {:unit_id (str path "::" symbol)
                                     :kind (clj-kind operator path)
                                     :symbol symbol
                                     :path path
                                     :module ns-name
                                     :start_line start-line
                                     :end_line end-line
                                     :signature (safe-line src-lines start-line)
                                     :summary (str (clj-kind operator path) " " symbol)
                                     :docstring_excerpt nil
                                     :imports imports
                                     :calls calls
                                     :parser_mode "full"})))
                           vec)]
            (if (seq units)
              {:ok? true
               :result {:language "clojure"
                        :module ns-name
                        :imports imports
                        :units units
                        :diagnostics [{:code "tree_sitter_active"
                                       :summary "Clojure analyzed using tree-sitter CST extraction."}]
                        :parser_mode "full"}}
              {:ok? false
               :reason {:code "tree_sitter_no_units"
                        :summary "tree-sitter did not extract Clojure units."}})))))))

(defn- parse-clojure [root-path path lines {:keys [clojure_engine tree_sitter_enabled]
                                            :or {clojure_engine :clj-kondo
                                                 tree_sitter_enabled false}
                                            :as parser-opts}]
  (let [engine (or clojure_engine :clj-kondo)
        parsed (case engine
                 :regex (parse-clojure-regex path lines)
                 :tree-sitter
                 (let [{:keys [ok? result reason]} (parse-clojure-tree-sitter root-path path lines parser-opts)]
                   (if ok?
                     result
                     (-> (parse-clojure-kondo root-path path lines)
                         (update :diagnostics conj reason))))
                 :clj-kondo (parse-clojure-kondo root-path path lines)
                 (parse-clojure-kondo root-path path lines))]
    (add-tree-sitter-diag parsed tree_sitter_enabled "clojure")))

(defn- java-kind [path method-name]
  (if (or (str/includes? (str/lower-case path) "/test/")
          (str/ends-with? method-name "Test")
          (str/starts-with? method-name "test"))
    "test"
    "method"))

(defn- extract-java-calls [body]
  (->> (re-seq java-call-re body)
       (map second)
       (mapcat (fn [token]
                 (let [tail (tail-token token)]
                   (cond-> [token]
                     (and tail (not= tail token)) (conj tail)))))
       (remove #(contains? java-call-stop %))
       distinct
       vec))

(defn- java-normalized-params [params]
  (-> (or params "")
      str
      (str/replace #"\s+" "")
      (str/replace #",+" ",")
      (str/replace #"^,+|,+$" "")))

(defn- java-param-fragment-from-source [src-lines start-line]
  (let [idx (max 0 (dec start-line))
        window (->> (subvec src-lines idx (min (count src-lines) (+ idx 6)))
                    (str/join " "))]
    (some-> (re-find #"\(([^)]*)\)" window) second)))

(defn- java-method-arity [params]
  (let [p (java-normalized-params params)]
    (if (str/blank? p) 0 (count (str/split p #",")))))

(defn- short-hash [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")
        bytes (.digest md (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (format "%02x%02x%02x%02x" (aget bytes 0) (aget bytes 1) (aget bytes 2) (aget bytes 3))))

(defn- java-method-unit-id [path symbol params]
  (let [norm (java-normalized-params params)
        arity (java-method-arity norm)
        suffix (if (str/blank? norm)
                 (str "$arity" arity)
                 (str "$arity" arity "$sig" (short-hash norm)))]
    {:unit_id (str path "::" symbol suffix)
     :method_arity arity
     :method_signature_key norm}))

(defn- parse-java-regex [path lines]
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
                             (when-let [[_ _visibility return-type m params] (re-find java-method-re line)]
                               (when-not (contains? java-call-stop (str/lower-case (str return-type)))
                                 {:start-line (inc idx)
                                  :method m
                                  :params params
                                  :class (->> class-spots
                                              (filter #(<= (:line %) (inc idx)))
                                              last
                                              :class)
                                  :signature (trim-signature line)}))))
                     vec)
        starts (mapv :start-line methods)
        ends (unit-end-lines starts line-count)
        units (->> (map vector methods ends)
                   (map (fn [[m end-line]]
                          (let [start-line (:start-line m)
                                cls (or (:class m) "UnknownClass")
                                symbol (str (when pkg (str pkg ".")) cls "#" (:method m))
                                {:keys [unit_id method_arity method_signature_key]}
                                (java-method-unit-id path symbol (:params m))
                                body (->> (subvec lines (dec start-line) end-line)
                                          (str/join "\n"))]
                            {:unit_id unit_id
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
                             :method_arity method_arity
                             :method_signature_key method_signature_key
                             :calls (extract-java-calls body)
                             :parser_mode "full"})))
                   vec)]
    {:language "java"
     :module pkg
     :imports imports
     :units units
     :diagnostics []
     :parser_mode "full"}))

(defn- node-name-inside [ts-lines node name-marker]
  (->> ts-lines
       (filter #(<= (:start-row node) (:start-row %) (:end-row node)))
       (filter #(< (:indent node) (:indent %)))
       (filter #(and (= "identifier" (:node-type %))
                     (str/includes? (:text %) name-marker)
                     (:value %)))
       (sort-by (juxt :start-row :start-col))
       first
       :value))

(defn- parse-java-tree-sitter [root-path path src-lines parser-opts]
  (let [grammar-path (parser-grammar-path parser-opts :java)
        abs (-> (io/file root-path path) .getCanonicalPath)
        pkg (some (fn [line] (some-> (re-find java-package-re line) second)) src-lines)
        imports (->> src-lines
                     (keep (fn [line] (some-> (re-find java-import-re line) second)))
                     distinct
                     vec)]
    (cond
      (not (tree-sitter-available?))
      {:ok? false
       :reason {:code "tree_sitter_unavailable"
                :summary "tree-sitter CLI is unavailable for java tree-sitter parser."}}

      (str/blank? (str grammar-path))
      {:ok? false
       :reason {:code "tree_sitter_missing_grammar"
                :summary "No tree-sitter Java grammar path configured."}}

      :else
      (let [{:keys [ok? lines err]} (tree-sitter-cst abs grammar-path)
            ts-lines lines]
        (if-not ok?
          {:ok? false
           :reason {:code "tree_sitter_parse_failed"
                    :summary (str "tree-sitter parse failed: " (subs (str err) 0 (min 220 (count (str err)))))}}
          (let [classes (->> ts-lines
                             (filter #(= "class_declaration" (:node-type %)))
                             (map (fn [c] (assoc c :class-name (or (node-name-inside ts-lines c "name:") "UnknownClass"))))
                             vec)
                methods (->> ts-lines
                             (filter #(= "method_declaration" (:node-type %)))
                             (map (fn [m]
                                    (let [method-name (or (node-name-inside ts-lines m "name:") "unknownMethod")
                                          cls (->> classes
                                                   (filter #(<= (:start-row %) (:start-row m) (:end-row %)))
                                                   (sort-by :start-row)
                                                   last
                                                   :class-name)
                                          calls (->> ts-lines
                                                     (filter #(and (= "method_invocation" (:node-type %))
                                                                   (<= (:start-row m) (:start-row %) (:end-row m))))
                                                     (map #(node-name-inside ts-lines % "name:"))
                                                     (remove nil?)
                                                     distinct
                                                     vec)]
                                      {:start-line (inc (:start-row m))
                                       :end-line (inc (:end-row m))
                                       :method method-name
                                       :class (or cls "UnknownClass")
                                       :params (java-param-fragment-from-source src-lines (inc (:start-row m)))
                                       :calls calls})))
                             vec)
                units (->> methods
                           (map (fn [{:keys [start-line end-line method class calls params]}]
                                  (let [symbol (str (when pkg (str pkg ".")) class "#" method)
                                        {:keys [unit_id method_arity method_signature_key]}
                                        (java-method-unit-id path symbol params)]
                                    {:unit_id unit_id
                                     :kind (java-kind path method)
                                     :symbol symbol
                                     :path path
                                     :module (if pkg (str pkg "." class) class)
                                     :start_line start-line
                                     :end_line end-line
                                     :signature (safe-line src-lines start-line)
                                     :summary (str "method " symbol)
                                     :docstring_excerpt nil
                                     :imports imports
                                     :method_arity method_arity
                                     :method_signature_key method_signature_key
                                     :calls (->> calls
                                                 (mapcat (fn [token]
                                                           (let [tail (tail-token token)]
                                                             (cond-> [token]
                                                               (and tail (not= tail token)) (conj tail)))))
                                                 (remove #(contains? java-call-stop %))
                                                 distinct
                                                 vec)
                                     :parser_mode "full"})))
                           vec)]
            (if (seq units)
              {:ok? true
               :result {:language "java"
                        :module pkg
                        :imports imports
                        :units units
                        :diagnostics [{:code "tree_sitter_active"
                                       :summary "Java analyzed using tree-sitter CST extraction."}]
                        :parser_mode "full"}}
              {:ok? false
               :reason {:code "tree_sitter_no_units"
                        :summary "tree-sitter did not extract Java units."}})))))))

(defn- parse-java [root-path path lines {:keys [java_engine tree_sitter_enabled]
                                         :or {java_engine :regex}
                                         :as parser-opts}]
  (let [engine (if (true? tree_sitter_enabled) :tree-sitter java_engine)
        parsed (if (= engine :tree-sitter)
                 (let [{:keys [ok? result reason]} (parse-java-tree-sitter root-path path lines parser-opts)]
                   (if ok?
                     result
                     (-> (parse-java-regex path lines)
                         (update :diagnostics conj reason))))
                 (parse-java-regex path lines))]
    (add-tree-sitter-diag parsed tree_sitter_enabled "java")))

(defn- ex-test-symbol [module test-name]
  (let [slug (-> test-name
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (str (or module "Elixir.Unknown") "/test-" (if (seq slug) slug "unnamed"))))

(defn- ex-last-segment [module]
  (some-> module str (str/split #"\.") last))

(defn- ex-strip-comment [line]
  (first (str/split (str line) #"#" 2)))

(defn- ex-rewrite-alias-prefix [token alias-map]
  (let [parts (->> (str/split (str token) #"\.")
                   (remove str/blank?)
                   vec)
        n (count parts)]
    (loop [i n]
      (when (pos? i)
        (let [prefix (str/join "." (take i parts))]
          (if-let [mapped (get alias-map prefix)]
            (let [suffix (drop i parts)]
              (if (seq suffix)
                (str mapped "." (str/join "." suffix))
                mapped))
            (recur (dec i))))))))

(defn- ex-resolve-alias-ref [token alias-map]
  (loop [current (str token)
         step 0]
    (if (>= step 8)
      current
      (if-let [rewritten (ex-rewrite-alias-prefix current alias-map)]
        (if (= rewritten current)
          current
          (recur rewritten (inc step)))
        current))))

(defn- ex-expand-alias-targets [target-expr alias-map]
  (let [target* (-> target-expr ex-strip-comment str/trim)]
    (if-let [[_ base inner] (re-find #"^([A-Za-z0-9_\.]+)\.\{([^}]+)\}$" target*)]
      (let [base* (ex-resolve-alias-ref base alias-map)]
        (->> (str/split inner #",")
             (map str/trim)
             (remove str/blank?)
             (map (fn [tail]
                    (ex-resolve-alias-ref (str base* "." tail) alias-map)))
             vec))
      [(ex-resolve-alias-ref target* alias-map)])))

(defn- ex-alias-entries-for-line [line alias-map]
  (when-let [[_ body] (re-find ex-alias-line-re line)]
    (let [body* (-> body ex-strip-comment str/trim)
          [_ target-expr explicit-as] (or (re-find #"^(.*?),\s*as:\s*([A-Za-z0-9_\.]+)\s*$" body*)
                                          [nil body* nil])
          targets (ex-expand-alias-targets target-expr alias-map)]
      (->> targets
           (map-indexed
            (fn [idx full]
              (let [alias (if (and (seq explicit-as) (= 1 (count targets)) (zero? idx))
                            explicit-as
                            (ex-last-segment full))]
                (when (and (seq alias) (seq full))
                  [alias full]))))
           (remove nil?)
           vec))))

(defn- ex-alias-map [lines]
  (reduce
   (fn [acc line]
     (reduce (fn [m [alias full]] (assoc m alias full))
             acc
             (or (ex-alias-entries-for-line line acc) [])))
   {}
   lines))

(defn- ex-keyword-count [line kw]
  (count (re-seq (re-pattern (str "\\b" kw "\\b")) (str line))))

(defn- ex-unit-end-line [lines start-line ceiling-line form]
  (if (= form "defdelegate")
    start-line
    (let [start-idx (max 0 (dec start-line))
          end-idx (max start-idx (dec ceiling-line))]
      (loop [idx start-idx
             depth 0
             saw-do false]
        (if (> idx end-idx)
          (inc end-idx)
          (let [line (nth lines idx "")
                do-count (ex-keyword-count line "do")
                end-count (ex-keyword-count line "end")
                depth* (- (+ depth do-count) end-count)
                saw-do* (or saw-do (pos? do-count))
                complete? (and saw-do* (<= depth* 0))]
            (if complete?
              (inc idx)
              (recur (inc idx) depth* saw-do*))))))))

(defn- ex-expand-alias-token [token alias-map]
  (let [expanded (ex-resolve-alias-ref token alias-map)]
    (when (not= (str token) expanded)
      expanded)))

(defn- extract-ex-calls [body alias-map]
  (->> (re-seq ex-call-re body)
       (map second)
       (mapcat (fn [token]
                 (let [expanded (ex-expand-alias-token token alias-map)]
                   (cond-> [token]
                     (seq expanded) (conj expanded)))))
       (mapcat (fn [token]
                 (let [tail (tail-token token)]
                   (cond-> [token]
                     (and tail (not= tail token)) (conj tail)))))
       (remove #(contains? ex-call-stop %))
       distinct
       vec))

(defn- parse-elixir [path lines]
  (let [line-count (count lines)
        module-name (some (fn [line] (some-> (re-find ex-module-re line) second)) lines)
        alias-map (ex-alias-map lines)
        imports (->> (concat
                      (keep (fn [line] (some-> (re-find ex-import-re line) second)) lines)
                      (vals alias-map))
                     distinct
                     vec)
        defs (->> (map-indexed vector lines)
                  (keep (fn [[idx line]]
                          (cond
                            (re-find ex-test-re line)
                            (let [[_ nm] (re-find ex-test-re line)]
                              {:start-line (inc idx)
                               :form "test"
                               :kind "test"
                               :raw-symbol (ex-test-symbol module-name nm)
                               :signature (trim-signature line)})

                            (re-find ex-def-re line)
                            (let [[_ kw nm] (re-find ex-def-re line)
                                  kind (if (str/includes? path "/test/") "test" "function")]
                              {:start-line (inc idx)
                               :form kw
                               :kind kind
                               :raw-symbol (str (or module-name "Elixir.Unknown") "/" nm)
                               :signature (trim-signature line)}))))
                  vec)
        ends (->> defs
                  (map-indexed
                   (fn [idx d]
                     (let [start-line (:start-line d)
                           next-start (:start-line (nth defs (inc idx) nil))
                           ceiling-line (max start-line (or (some-> next-start dec) line-count))]
                       (ex-unit-end-line lines start-line ceiling-line (:form d)))))
                  vec)
        units (->> (map vector defs ends)
                   (map (fn [[d end-line]]
                          (let [start-line (:start-line d)
                                body-lines (subvec lines (dec start-line) end-line)
                                body (str/join "\n" body-lines)]
                            {:unit_id (str path "::" (:raw-symbol d))
                             :kind (:kind d)
                             :symbol (:raw-symbol d)
                             :path path
                             :module module-name
                             :start_line start-line
                             :end_line end-line
                             :signature (:signature d)
                             :summary (str (:form d) " " (:raw-symbol d))
                             :docstring_excerpt nil
                             :imports imports
                             :calls (extract-ex-calls body alias-map)
                             :ex_form (:form d)
                             :parser_mode "full"})))
                   vec)]
    {:language "elixir"
     :module module-name
     :imports imports
     :units units
     :diagnostics []
     :parser_mode "full"}))

(defn- py-module-name [path]
  (-> path
      (str/replace #"\.py$" "")
      (str/replace #"/" ".")
      (str/replace #"^\.+" "")))

(defn- py-kind [path fn-name]
  (if (or (str/includes? path "/test/")
          (str/starts-with? fn-name "test_")
          (str/ends-with? path "_test.py")
          (str/starts-with? (str/lower-case (or fn-name "")) "test"))
    "test"
    "function"))

(defn- py-indent [line]
  (count (re-find #"^\s*" line)))

(defn- py-in-class-context [stack indent]
  (->> stack
       (filter #(< (:indent %) indent))
       last
       :name))

(defn- extract-py-calls [body]
  (->> (re-seq py-call-re body)
       (map second)
       (mapcat (fn [token]
                 (let [tail (tail-token token)]
                   (cond-> [token]
                     (and tail (not= tail token)) (conj tail)))))
       (remove #(contains? py-call-stop %))
       distinct
       vec))

(defn- parse-python [path lines]
  (let [line-count (count lines)
        module (py-module-name path)
        imports (->> lines
                     (mapcat parse-python-import)
                     distinct
                     vec)
        defs (loop [idx 0
                    class-stack []
                    out []]
               (if (>= idx line-count)
                 out
                 (let [line (nth lines idx)
                       indent (py-indent line)
                       pruned (->> class-stack
                                   (filter #(< (:indent %) indent))
                                   vec)]
                   (cond
                     (re-find py-class-re line)
                     (let [[_ cls] (re-find py-class-re line)
                           entry {:start-line (inc idx)
                                  :kind "class"
                                  :raw-symbol (str module "." cls)
                                  :signature (trim-signature line)}]
                       (recur (inc idx) (conj pruned {:name cls :indent indent}) (conj out entry)))

                     (re-find py-def-re line)
                     (let [[_ fn-name] (re-find py-def-re line)
                           class-name (py-in-class-context pruned indent)
                           symbol (if class-name
                                    (str module "." class-name "/" fn-name)
                                    (str module "/" fn-name))
                           kind (if class-name
                                  (if (str/starts-with? (str/lower-case fn-name) "test") "test" "method")
                                  (py-kind path fn-name))
                           entry {:start-line (inc idx)
                                  :kind kind
                                  :raw-symbol symbol
                                  :signature (trim-signature line)}]
                       (recur (inc idx) pruned (conj out entry)))

                     :else
                     (recur (inc idx) pruned out)))))
        starts (mapv :start-line defs)
        ends (unit-end-lines starts line-count)
        units (->> (map vector defs ends)
                   (map (fn [[d end-line]]
                          (let [start-line (:start-line d)
                                body-lines (subvec lines (dec start-line) end-line)
                                body (str/join "\n" body-lines)]
                            {:unit_id (str path "::" (:raw-symbol d))
                             :kind (:kind d)
                             :symbol (:raw-symbol d)
                             :path path
                             :module module
                             :start_line start-line
                             :end_line end-line
                             :signature (:signature d)
                             :summary (str (:kind d) " " (:raw-symbol d))
                             :docstring_excerpt nil
                             :imports imports
                             :calls (extract-py-calls body)
                             :parser_mode "full"})))
                   vec)]
    {:language "python"
     :module module
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
           "java" (parse-java root-path file-path lines parser-opts)
           "elixir" (parse-elixir file-path lines)
           "python" (parse-python file-path lines)
           (fallback-unit file-path lines language "unsupported_language")))
       (catch Exception _
         (let [lines (try (slurp-lines abs) (catch Exception _ []))]
           (fallback-unit file-path lines language "parse_exception")))))))
