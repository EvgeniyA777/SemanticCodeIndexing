(ns semantic-code-indexing.runtime.adapters
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private clj-def-re
  #"^\s*\((defn-|defn|defmacro|defmulti|defmethod|defprotocol|def|deftest)\s+([^\s\[\]\)]+)")

(def ^:private clj-call-re
  #"\(([a-zA-Z][a-zA-Z0-9\-\.!/<>\?]*)")

(def ^:private clj-require-re
  #"\[([a-zA-Z0-9\._\-]+)(?:\s+:as\s+[a-zA-Z0-9_\-]+)?\]")

(def ^:private clj-require-alias-re
  #"\[([a-zA-Z0-9\._\-]+)\s+:as\s+([a-zA-Z0-9_\-]+)\]")

(def ^:private java-package-re #"^\s*package\s+([a-zA-Z0-9_\.]+)\s*;")
(def ^:private java-import-re #"^\s*import\s+(?:static\s+)?([a-zA-Z0-9_\.\*]+)\s*;")
(def ^:private java-class-re #"^\s*(?:public\s+)?(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)")
(def ^:private java-method-re
  #"^\s*(?:(public|private|protected)\s+)?(?:(?:static|final|native|synchronized|abstract|default)\s+)*([A-Za-z0-9_<>,\[\]\.\?]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:\{|throws|;)")
(def ^:private java-constructor-re
  #"^\s*(?:(public|private|protected)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:\{|throws)")
(def ^:private java-call-re #"\b([A-Za-z_][A-Za-z0-9_\.]*)\s*\(")

(def ^:private ex-module-re #"^\s*defmodule\s+([A-Za-z0-9_\.]+)\s+do")
(def ^:private ex-import-re #"^\s*(?:import|require|use)\s+([A-Za-z0-9_\.]+)")
(def ^:private ex-import-only-re #"^\s*import\s+([A-Za-z0-9_\.]+)")
(def ^:private ex-use-only-re #"^\s*use\s+([A-Za-z0-9_\.]+)")
(def ^:private ex-alias-line-re #"^\s*alias\s+(.+)$")
(def ^:private ex-def-re #"^\s*(defdelegate|defp?|defmacro|defmacrop)\s+([a-zA-Z_][a-zA-Z0-9_!?]*)")
(def ^:private ex-test-re #"^\s*test\s+\"([^\"]+)\"\s+do")
(def ^:private ex-call-re #"\b([A-Za-z_][A-Za-z0-9_\.!?]*)\s*\(")
(def ^:private ex-call-with-args-re #"\b([A-Za-z_][A-Za-z0-9_\.!?]*)\s*\(([^)]*)\)")

(def ^:private py-import-re #"^\s*import\s+([a-zA-Z0-9_\.]+)(?:\s+as\s+([A-Za-z0-9_]+))?")
(def ^:private py-from-import-re #"^\s*from\s+([a-zA-Z0-9_\.]+)\s+import\s+([A-Za-z0-9_,\s\*_]+)")
(def ^:private py-class-re #"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)")
(def ^:private py-def-re #"^\s*(?:async\s+def|def)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
(def ^:private py-call-re #"\b([A-Za-z_][A-Za-z0-9_\.]*)\s*\(")
(declare py-normalize-relative-module)

(def ^:private ts-import-from-re #"^\s*(?:import|export)\s+.+?\s+from\s+['\"]([^'\"]+)['\"]")
(def ^:private ts-import-clause-re #"^\s*import\s+(.+?)\s+from\s+['\"]([^'\"]+)['\"]")
(def ^:private ts-import-bare-re #"^\s*import\s+['\"]([^'\"]+)['\"]")
(def ^:private ts-class-re #"^\s*(?:export\s+)?(?:default\s+)?class\s+([A-Za-z_$][A-Za-z0-9_$]*)")
(def ^:private ts-function-re #"^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(")
(def ^:private ts-arrow-re #"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][A-Za-z0-9_$]*)\s*=>")
(def ^:private ts-function-expression-re #"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?function\b")
(def ^:private ts-method-re #"^\s*(?:(?:public|private|protected|static|async|readonly|get|set)\s+)*([A-Za-z_$][A-Za-z0-9_$]*)\s*\([^;]*\)\s*(?::\s*[^\{=]+)?\s*\{")
(def ^:private ts-call-re #"\b([A-Za-z_$][A-Za-z0-9_$\.]*)\s*\(")

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

(def ^:private ts-call-stop
  #{"if" "for" "while" "switch" "catch" "return" "throw" "new" "super" "this"
    "function" "class" "import" "export" "typeof" "instanceof" "await" "delete"
    "do" "try" "finally" "of" "in"})

(def ^:private ts-line-re
  #"^\s*(\d+):(\d+)\s*-\s*(\d+):(\d+)(\s+)(.+?)\s*$")

(defn language-by-path [path]
  (cond
    (or (str/ends-with? path ".clj")
        (str/ends-with? path ".cljc")
        (str/ends-with? path ".cljs")) "clojure"
    (str/ends-with? path ".java") "java"
    (or (str/ends-with? path ".ex")
        (str/ends-with? path ".exs")) "elixir"
    (str/ends-with? path ".py") "python"
    (or (str/ends-with? path ".ts") (str/ends-with? path ".tsx")) "typescript"
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

(defn- clj-scan-line [{:keys [depth in-string] :as state} line]
  (loop [chars (seq (str line))
         depth* (or depth 0)
         in-string* (true? in-string)
         escaped? false]
    (if-let [ch (first chars)]
      (cond
        escaped?
        (recur (next chars) depth* in-string* false)

        in-string*
        (cond
          (= ch \\) (recur (next chars) depth* in-string* true)
          (= ch \") (recur (next chars) depth* false false)
          :else (recur (next chars) depth* in-string* false))

        (= ch \;)
        {:depth depth* :in-string in-string*}

        (= ch \")
        (recur (next chars) depth* true false)

        (#{\( \[ \{} ch)
        (recur (next chars) (inc depth*) in-string* false)

        (#{\) \] \}} ch)
        (recur (next chars) (max 0 (dec depth*)) in-string* false)

        :else
        (recur (next chars) depth* in-string* false))
      {:depth depth* :in-string in-string*})))

(defn- clj-line-start-depths [lines]
  (loop [remaining lines
         state {:depth 0 :in-string false}
         depths []]
    (if-let [line (first remaining)]
      (recur (rest remaining)
             (clj-scan-line state line)
             (conj depths (:depth state)))
      depths)))

(defn- clj-form-end-line [lines start-line]
  (let [line-count (count lines)
        start-idx (max 0 (dec start-line))]
    (loop [idx start-idx
           state {:depth 0 :in-string false}]
      (if (>= idx line-count)
        line-count
        (let [next-state (clj-scan-line state (nth lines idx))]
          (if (zero? (:depth next-state))
            (inc idx)
            (recur (inc idx) next-state)))))))

(defn- parse-python-import [module line]
  (if-let [[_ from names] (re-find py-from-import-re line)]
    (let [resolved-from (py-normalize-relative-module module from)
          parts (->> (str/split names #",")
                     (map str/trim)
                     (remove str/blank?))]
      (vec (cons resolved-from
                 (map (fn [n]
                        (let [[_ name _alias] (or (re-find #"^([A-Za-z0-9_\*]+)(?:\s+as\s+([A-Za-z0-9_]+))?$" n)
                                                  [nil n nil])]
                          (if (= name "*")
                            resolved-from
                            (str resolved-from "." name))))
                      parts))))
    (when-let [[_ imp] (re-find py-import-re line)]
      [imp])))

(defn- ts-strip-ext [path]
  (-> (str path)
      (str/replace #"\.(ts|tsx|js|jsx|mjs|cjs)$" "")
      (str/replace #"/index$" "")))

(defn- ts-module-name [path]
  (-> path
      str
      (str/replace "\\" "/")
      ts-strip-ext
      (str/replace #"^\./+" "")
      (str/replace #"^/+" "")
      (str/replace #"/" ".")
      (str/replace #"^\.+|\.+$" "")))

(defn- ts-resolve-import-path [path spec]
  (let [spec* (str spec)]
    (if (str/starts-with? spec* ".")
      (let [dir (or (some-> path io/file .getParent) "")
            joined (if (str/blank? dir) spec* (str dir "/" spec*))]
        (-> joined io/file .toPath .normalize str (str/replace "\\" "/")))
      (str/replace spec* "\\" "/"))))

(defn- parse-typescript-import [path line]
  (let [spec (or (some-> (re-find ts-import-clause-re line) (nth 2))
                 (some-> (re-find ts-import-bare-re line) second))]
    (when (seq spec)
      [(-> (ts-resolve-import-path path spec)
           ts-module-name)])))

(defn- ts-parse-named-imports [clause]
  (let [body (some-> (re-find #"\{([^}]*)\}" (str clause)) second)]
    (reduce
     (fn [acc part]
       (let [part* (-> part str/trim (str/replace #"^type\s+" ""))]
         (if (str/blank? part*)
           acc
           (let [[_ exported local] (or (re-find #"^([A-Za-z_$][A-Za-z0-9_$]*)(?:\s+as\s+([A-Za-z_$][A-Za-z0-9_$]*))?$" part*)
                                        [nil part* nil])]
             (assoc acc (or local exported) exported)))))
     {}
     (str/split (or body "") #","))))

(defn- ts-import-state [path lines]
  (reduce
   (fn [{:keys [imports module-aliases symbol-aliases default-aliases] :as acc} line]
     (if-let [[_ clause spec] (re-find ts-import-clause-re line)]
       (let [resolved (-> (ts-resolve-import-path path spec) ts-module-name)
             clause* (str/trim clause)
             default-alias (when-not (or (str/starts-with? clause* "{")
                                         (str/starts-with? clause* "*"))
                             (some-> (re-find #"^(?:type\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:,|$)" clause*) second))
             namespace-alias (some-> (re-find #"\*\s+as\s+([A-Za-z_$][A-Za-z0-9_$]*)" clause*) second)
             named-aliases (ts-parse-named-imports clause*)]
         {:imports (conj imports resolved)
          :module-aliases (cond-> module-aliases
                            (seq namespace-alias) (assoc namespace-alias resolved))
          :symbol-aliases (reduce-kv (fn [m local exported]
                                       (assoc m local (str resolved "/" exported)))
                                     symbol-aliases
                                     named-aliases)
          :default-aliases (cond-> default-aliases
                             (seq default-alias) (assoc default-alias (str resolved "/default")))})
       (if-let [[_ spec] (re-find ts-import-bare-re line)]
         (update acc :imports conj (-> (ts-resolve-import-path path spec) ts-module-name))
         acc)))
   {:imports [] :module-aliases {} :symbol-aliases {} :default-aliases {}}
   lines))

(defn- ts-default-export-line? [line]
  (boolean (re-find #"^\s*export\s+default\s+" (str line))))

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
                       :typescript "SCI_TREE_SITTER_TYPESCRIPT_GRAMMAR_PATH"
                       nil))))

(defn- parse-ts-line [line]
  (when-let [[_ sr sc er ec spacing text] (re-find ts-line-re line)]
    (let [plain (str/trim (first (str/split text #"`")))
          source (if (str/includes? plain ":")
                   (second (str/split plain #":\s*" 2))
                   plain)
          node-type (last (str/split (str/trim source) #"\s+"))
          value (some-> (re-find #"`([^`]*)`" text) second)]
      {:indent (count spacing)
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
  (if (and enabled? (#{"clojure" "java" "typescript"} language))
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

(defn- clj-require-alias-map [lines]
  (reduce (fn [acc line]
            (reduce (fn [m [_ ns-name alias]]
                      (assoc m alias ns-name))
                    acc
                    (re-seq clj-require-alias-re line)))
          {}
          lines))

(defn- rewrite-clj-call-token [token alias-map]
  (let [token* (str token)]
    (if-let [[_ alias suffix] (re-matches #"([A-Za-z0-9_\-]+)/(.*)" token*)]
      (if-let [ns-name (get alias-map alias)]
        (str ns-name "/" suffix)
        token*)
      token*)))

(defn- expand-clj-call-token [token alias-map]
  (let [rewritten (rewrite-clj-call-token token alias-map)]
    (if (= (str token) rewritten)
      [(str token)]
      [(str token) rewritten])))

(def ^:private clj-test-module-suffixes
  ["-test" "-spec"])

(defn- clj-test-module? [module path]
  (let [module* (str (or module ""))]
    (or (re-find #"(^|/)test/" (str path))
        (some #(str/ends-with? module* %) clj-test-module-suffixes))))

(defn- strip-clj-test-suffix [module]
  (reduce (fn [acc suffix]
            (if (str/ends-with? acc suffix)
              (subs acc 0 (- (count acc) (count suffix)))
              acc))
          (str module)
          clj-test-module-suffixes))

(defn- clj-test-target-modules [module imports path]
  (if (clj-test-module? module path)
    (->> (concat [(strip-clj-test-suffix module)] imports)
         (remove #(or (str/blank? %)
                      (= % "clojure.test")))
         distinct
         vec)
    []))

(defn- clj-binding-symbols [binding-form]
  (letfn [(collect [node]
            (cond
              (symbol? node)
              (let [s (str node)]
                (if (or (str/blank? s) (= "&" s))
                  []
                  [s]))

              (vector? node)
              (mapcat collect node)

              (map? node)
              (concat
               (when-let [as-binding (:as node)]
                 (collect as-binding))
               (mapcat collect (vals (apply dissoc node [:as :keys :syms :strs :or])))
               (map name (:keys node))
               (map name (:syms node))
               (map str (:strs node)))

              (seq? node)
              (mapcat collect node)

              :else
              []))]
    (->> (collect binding-form)
         (remove #(or (str/blank? %) (= "&" %)))
         distinct
         vec)))

(defn- clj-sequential-binding-calls [bindings locals walk]
  (loop [pairs (partition 2 2 [] bindings)
         locals* (set locals)
         acc []]
    (if (empty? pairs)
      {:calls acc :locals locals*}
      (let [[binding init] (first pairs)
            calls* (into acc (walk init locals*))
            locals** (into locals* (clj-binding-symbols binding))]
        (recur (rest pairs) locals** calls*)))))

(defn- clj-comprehension-binding-calls [bindings locals walk]
  (loop [items (seq bindings)
         locals* (set locals)
         acc []]
    (if (empty? items)
      {:calls acc :locals locals*}
      (let [head (first items)
            next-item (second items)]
        (cond
          (= head :let)
          (let [{:keys [calls locals]} (clj-sequential-binding-calls next-item locals* walk)]
            (recur (nnext items) locals (into acc calls)))

          (#{:when :while} head)
          (recur (nnext items) locals* (into acc (walk next-item locals*)))

          :else
          (recur (nnext items)
                 (into locals* (clj-binding-symbols head))
                 (into acc (walk next-item locals*))))))))

(declare clj-read-form)
(declare clj-dispatch-fragment)
(declare clj-qualified-symbol)

(defn- clj-dispatch-call-token [symbol dispatch-value]
  (str symbol "$dispatch:" dispatch-value))

(defn- clj-literal-dispatch-fragment [node]
  (when (or (keyword? node)
            (string? node)
            (number? node)
            (char? node))
    (clj-dispatch-fragment node)))

(defn- clj-dispatch-call-tokens [form-text ns-name alias-map dispatch-symbols]
  (let [form (clj-read-form form-text)]
    (letfn [(walk [node]
              (cond
                (seq? node)
                (let [items (seq node)
                      op-node (first items)
                      op-str (some-> op-node str)
                      rewritten-op (some-> op-str (rewrite-clj-call-token alias-map))
                      op-qualified (when rewritten-op
                                     (if (str/includes? rewritten-op "/")
                                       rewritten-op
                                       (clj-qualified-symbol ns-name rewritten-op)))
                      dispatch-arg (first (rest items))
                      direct-token (when-let [dispatch-value (clj-literal-dispatch-fragment dispatch-arg)]
                                     (when (contains? dispatch-symbols op-qualified)
                                       (clj-dispatch-call-token op-qualified dispatch-value)))]
                  (concat
                   (when direct-token [direct-token])
                   (mapcat walk items)))

                (vector? node)
                (mapcat walk node)

                (map? node)
                (mapcat walk (concat (keys node) (vals node)))

                (set? node)
                (mapcat walk node)

                :else
                []))]
      (->> (walk form)
           distinct
           vec))))

(defn- clj-protocol-method-specs [form-text]
  (let [form (clj-read-form form-text)]
    (when (and (seq? form)
               (= "defprotocol" (some-> form first str)))
      (let [body (drop 2 form)
            body (cond-> body
                   (string? (first body)) rest
                   (map? (first body)) rest)]
        (->> body
             (keep (fn [entry]
                     (when (seq? entry)
                       (let [method-name (some-> entry first str)
                             arglists (->> (rest entry)
                                           (filter vector?)
                                           vec)]
                         (when (and (seq method-name) (seq arglists))
                           {:method-name method-name
                            :arity (count (first arglists))
                            :signature (trim-signature (pr-str (take 2 entry)))})))))
             vec)))))

(defn- clj-protocol-method-units
  [{:keys [path ns-name start-line end-line imports parser-mode]} form-text]
  (->> (clj-protocol-method-specs form-text)
       (mapv (fn [{:keys [method-name arity signature]}]
               (let [symbol (clj-qualified-symbol ns-name method-name)]
                 {:unit_id (str path "::" symbol "$protocol$arity" arity)
                  :kind "method"
                  :symbol symbol
                  :path path
                  :module ns-name
                  :form_operator "defprotocol"
                  :start_line start-line
                  :end_line end-line
                  :signature signature
                  :summary (str "method " symbol " protocol")
                  :docstring_excerpt nil
                  :imports imports
                  :method_arity arity
                  :calls []
                  :parser_mode parser-mode})))))

(defn- extract-clj-calls
  ([body]
   (extract-clj-calls body {}))
  ([body alias-map]
   (let [form (clj-read-form body)]
     (if (nil? form)
       (->> (re-seq clj-call-re body)
            (map second)
            (remove clj-call-stop)
            (mapcat #(expand-clj-call-token % alias-map))
            distinct
            vec)
       (letfn [(walk [node locals]
                 (let [locals* (set locals)]
                   (cond
                     (nil? node)
                     []

                     (seq? node)
                     (let [items (seq node)
                           op-node (first items)
                           op (some-> op-node str)
                           args (rest items)
                           op-calls (if (and (symbol? op-node)
                                             (not (contains? locals* op))
                                             (not (contains? clj-call-stop op)))
                                      (expand-clj-call-token op alias-map)
                                      [])]
                       (->> (case op
                              ("quote" "var")
                              []

                              ("let" "loop" "binding")
                              (let [binding-vec (first args)
                                    body-forms (rest args)
                                    {:keys [calls locals]} (clj-sequential-binding-calls binding-vec locals* walk)]
                                (concat calls (mapcat #(walk % locals) body-forms)))

                              ("when-let" "when-some")
                              (let [binding-vec (first args)
                                    body-forms (rest args)
                                    {:keys [calls locals]} (clj-sequential-binding-calls binding-vec locals* walk)]
                                (concat calls (mapcat #(walk % locals) body-forms)))

                              ("if-let" "if-some")
                              (let [binding-vec (first args)
                                    then-form (second args)
                                    else-form (nth args 2 nil)
                                    {:keys [calls locals]} (clj-sequential-binding-calls binding-vec locals* walk)]
                                (concat calls
                                        (walk then-form locals)
                                        (when else-form (walk else-form locals*))))

                              ("for" "doseq")
                              (let [binding-vec (first args)
                                    body-forms (rest args)
                                    {:keys [calls locals]} (clj-comprehension-binding-calls binding-vec locals* walk)]
                                (concat calls (mapcat #(walk % locals) body-forms)))

                              "as->"
                              (let [expr (first args)
                                    binding-sym (second args)
                                    body-forms (drop 2 args)
                                    locals** (into locals* (clj-binding-symbols binding-sym))]
                                (concat (walk expr locals*)
                                        (mapcat #(walk % locals**) body-forms)))

                              "fn"
                              (let [[fn-name arg-tail] (if (symbol? (first args))
                                                         [(str (first args)) (rest args)]
                                                         [nil args])]
                                (if (vector? (first arg-tail))
                                  (let [params (first arg-tail)
                                        body-forms (rest arg-tail)
                                        locals** (into locals* (clj-binding-symbols params))
                                        locals** (cond-> locals**
                                                   fn-name (conj fn-name))]
                                    (mapcat #(walk % locals**) body-forms))
                                  (mapcat (fn [arity-form]
                                            (if (seq? arity-form)
                                              (let [params (first arity-form)
                                                    body-forms (rest arity-form)
                                                    locals** (into locals* (clj-binding-symbols params))
                                                    locals** (cond-> locals**
                                                               fn-name (conj fn-name))]
                                                (mapcat #(walk % locals**) body-forms))
                                              (walk arity-form locals*)))
                                          arg-tail)))

                              ("defn" "defn-" "defmacro")
                              (let [after-name (rest args)
                                    after-doc (cond-> after-name
                                                (string? (first after-name)) rest
                                                (map? (first after-name)) rest)]
                                (if (vector? (first after-doc))
                                  (let [params (first after-doc)
                                        body-forms (rest after-doc)
                                        locals** (into locals* (clj-binding-symbols params))]
                                    (mapcat #(walk % locals**) body-forms))
                                  (mapcat (fn [arity-form]
                                            (if (seq? arity-form)
                                              (let [params (first arity-form)
                                                    body-forms (rest arity-form)
                                                    locals** (into locals* (clj-binding-symbols params))]
                                                (mapcat #(walk % locals**) body-forms))
                                              (walk arity-form locals*)))
                                          after-doc)))

                              "defmethod"
                              (let [after-dispatch (drop 2 args)
                                    after-doc (cond-> after-dispatch
                                                (string? (first after-dispatch)) rest
                                                (map? (first after-dispatch)) rest)
                                    params (first after-doc)
                                    body-forms (rest after-doc)
                                    locals** (into locals* (clj-binding-symbols params))]
                                (mapcat #(walk % locals**) body-forms))

                              "letfn"
                              (let [bindings (first args)
                                    helper-names (->> bindings
                                                      (keep (fn [binding]
                                                              (when (seq? binding)
                                                                (some-> binding first str))))
                                                      set)
                                    binding-calls (mapcat (fn [binding]
                                                            (if (seq? binding)
                                                              (let [parts (rest binding)
                                                                    params (first parts)
                                                                    body-forms (rest parts)
                                                                    locals** (into locals* helper-names)
                                                                    locals** (into locals** (clj-binding-symbols params))]
                                                                (mapcat #(walk % locals**) body-forms))
                                                              []))
                                                          bindings)
                                    body-locals (into locals* helper-names)]
                                (concat binding-calls
                                        (mapcat #(walk % body-locals) (rest args))))

                              (concat op-calls
                                      (mapcat #(walk % locals*) items)))
                            distinct
                            vec))

                     (vector? node)
                     (mapcat #(walk % locals*) node)

                     (map? node)
                     (mapcat #(walk % locals*) (concat (keys node) (vals node)))

                     (set? node)
                     (mapcat #(walk % locals*) node)

                     :else
                     [])))]
         (->> (walk form #{})
              distinct
              vec))))))

(declare short-hash usage->call-token)

(defn- clj-qualified-symbol [ns-name raw-symbol]
  (let [raw* (str raw-symbol)]
    (if (and ns-name (not (str/includes? raw* "/")))
      (str ns-name "/" raw*)
      raw*)))

(defn- clj-read-form [form-text]
  (try
    (binding [*read-eval* false]
      (read-string form-text))
    (catch Exception _
      nil)))

(defn- generated-call-form-texts [form-text]
  (let [text (str form-text)
        n (count text)
        opening->closing {\( \) \[ \] \{ \}}
        whitespace? #(Character/isWhitespace ^char %)]
    (letfn [(skip-ws [j]
              (loop [k j]
                (if (and (< k n) (whitespace? (.charAt text k)))
                  (recur (inc k))
                  k)))
            (read-quoted-form [start]
              (let [open-ch (.charAt text start)
                    close-ch (get opening->closing open-ch)]
                (when close-ch
                  (loop [j (inc start)
                         depth 1
                         in-string2? false
                         escaped2? false
                         comment2? false]
                    (cond
                      (>= j n) nil

                      comment2?
                      (recur (inc j) depth in-string2? escaped2? (not= (.charAt text j) \newline))

                      escaped2?
                      (recur (inc j) depth in-string2? false comment2?)

                      in-string2?
                      (let [ch2 (.charAt text j)]
                        (cond
                          (= ch2 \\) (recur (inc j) depth in-string2? true comment2?)
                          (= ch2 \") (recur (inc j) depth false false comment2?)
                          :else (recur (inc j) depth in-string2? false comment2?)))

                      :else
                      (let [ch2 (.charAt text j)]
                        (cond
                          (= ch2 \;) (recur (inc j) depth in-string2? false true)
                          (= ch2 \") (recur (inc j) depth true false comment2?)
                          (= ch2 open-ch) (recur (inc j) (inc depth) in-string2? false comment2?)
                          (= ch2 close-ch) (if (= depth 1)
                                             {:next-idx (inc j)
                                              :quoted-form (subs text start (inc j))}
                                             (recur (inc j) (dec depth) in-string2? false comment2?))
                          :else (recur (inc j) depth in-string2? false comment2?))))))))]
      (loop [idx 0
             in-string? false
             escaped? false
             comment? false
             acc []]
        (if (>= idx n)
          acc
          (let [ch (.charAt text idx)]
            (cond
              comment?
              (recur (inc idx) in-string? escaped? (not= ch \newline) acc)

              escaped?
              (recur (inc idx) in-string? false comment? acc)

              in-string?
              (cond
                (= ch \\) (recur (inc idx) in-string? true comment? acc)
                (= ch \") (recur (inc idx) false false comment? acc)
                :else (recur (inc idx) in-string? false comment? acc))

              (= ch \;)
              (recur (inc idx) in-string? false true acc)

              (= ch \")
              (recur (inc idx) true false comment? acc)

              (= ch \`)
              (let [start (skip-ws (inc idx))
                    quoted (when (< start n) (read-quoted-form start))]
                (if quoted
                  (recur (:next-idx quoted) in-string? false comment? (conj acc (:quoted-form quoted)))
                  (recur (inc idx) in-string? false comment? acc)))

              :else
              (recur (inc idx) in-string? false comment? acc))))))))

(def ^:private clj-generated-builder-ops
  #{"list" "clojure.core/list"
    "list*" "clojure.core/list*"
    "cons" "clojure.core/cons"})

(def ^:private clj-generated-apply-ops
  #{"apply" "clojure.core/apply"})

(def ^:private clj-generated-threading-ops
  #{"->" "->>" "some->" "some->>"})

(def ^:private clj-generated-conditional-ops
  #{"if" "if-not" "cond" "case" "when" "when-not"})

(defn- quoted-call-token [form]
  (when (and (seq? form)
             (= 'quote (first form)))
    (some-> form second str)))

(defn- defmacro-expansion-forms [form]
  (let [parts (->> (drop 2 form)
                   (drop-while #(or (string? %)
                                    (map? %))))]
    (cond
      (vector? (first parts))
      (some->> (rest parts) last vector)

      (and (seq? (first parts))
           (vector? (ffirst parts)))
      (->> parts
           (keep (fn [arity-form]
                   (some->> (rest arity-form) last))))

      :else
      [])))

(declare generated-builder-call-tokens*)

(defn- token-intersection [colls]
  (let [sets (->> colls
                  (map #(set (or % [])))
                  vec)]
    (if (seq sets)
      (->> (apply set/intersection sets)
           vec)
      [])))

(defn- conditional-branch-generated-tokens [op args walk helper-generated-calls*]
  (case op
    ("if" "if-not")
    (let [then-node (second args)
          else-node (nth args 2 nil)]
      (if else-node
        (token-intersection [(walk then-node helper-generated-calls* false)
                             (walk else-node helper-generated-calls* false)])
        []))

    ("when" "when-not")
    []

    "cond"
    (let [branch-nodes (->> args
                            (partition 2 2 [])
                            (keep second)
                            vec)]
      (if (>= (count branch-nodes) 2)
        (token-intersection (map #(walk % helper-generated-calls* false) branch-nodes))
        []))

    "case"
    (let [branch-tail (drop 1 args)
          branch-nodes (->> branch-tail
                            (partition 2 2 [])
                            (mapcat (fn [pair]
                                      (let [[_ result] pair]
                                        (when result [result]))))
                            vec)]
      (if (>= (count branch-nodes) 2)
        (token-intersection (map #(walk % helper-generated-calls* false) branch-nodes))
        []))

    []))

(defn- letfn-helper-generated-calls [bindings]
  (->> bindings
       (keep (fn [binding]
               (when (seq? binding)
                 (let [helper-name (some-> binding first str)
                       parts (rest binding)
                       bodies (cond
                                (vector? (first parts))
                                (some->> parts last vector)

                                (and (seq? (first parts))
                                     (vector? (ffirst parts)))
                                (->> parts (keep last))

                                :else
                                [])]
                   (when (and (seq helper-name) (seq bodies))
                     [helper-name (->> bodies
                                       (mapcat #(generated-builder-call-tokens* % {} false))
                                       distinct
                                       vec)])))))
       (into {})))

(defn- direct-generated-call-token [step]
  (cond
    (symbol? step) (str step)
    (seq? step) (some-> step first str)
    :else nil))

(defn- threading-generated-tokens [args walk helper-generated-calls*]
  (let [steps (rest args)]
    (->> steps
         (mapcat (fn [step]
                   (let [direct-token (direct-generated-call-token step)
                         helper-tokens (when (and direct-token
                                                  (contains? helper-generated-calls* direct-token))
                                         (get helper-generated-calls* direct-token))
                         nested-tokens (walk step helper-generated-calls* false)]
                     (concat
                      (when (and direct-token
                                 (not (contains? clj-call-stop direct-token)))
                        [direct-token])
                      helper-tokens
                      nested-tokens))))
         distinct
         vec)))

(defn- generated-builder-call-tokens*
  [form helper-generated-calls generated-context?]
  (letfn [(walk [node helper-generated-calls* generated-context?]
            (cond
              (nil? node)
              []

              (seq? node)
              (let [items (seq node)
                    op (some-> items first str)
                    args (rest items)
                    helper-generated-calls** (if (= "letfn" op)
                                               (merge helper-generated-calls*
                                                      (letfn-helper-generated-calls (first args)))
                                               helper-generated-calls*)
                    builder-token (cond
                                    (contains? clj-generated-builder-ops op)
                                    (quoted-call-token (first args))

                                    (contains? clj-generated-apply-ops op)
                                    (let [builder-op (some-> args first str)]
                                      (when (contains? clj-generated-builder-ops builder-op)
                                        (quoted-call-token (second args))))

                                    :else
                                    nil)
                    helper-tokens (when (and generated-context?
                                             (contains? helper-generated-calls** op))
                                    (get helper-generated-calls** op))
                    threading-tokens (when (contains? clj-generated-threading-ops op)
                                       (threading-generated-tokens args walk helper-generated-calls**))
                    branch-tokens (when (contains? clj-generated-conditional-ops op)
                                    (conditional-branch-generated-tokens op args walk helper-generated-calls**))
                    child-generated-context? (or generated-context?
                                                (contains? clj-generated-builder-ops op)
                                                (contains? clj-generated-apply-ops op))
                    children (cond
                               (contains? clj-generated-conditional-ops op) []
                               (= "letfn" op) (rest args)
                               :else items)]
                (concat
                 (when builder-token [builder-token])
                 helper-tokens
                 threading-tokens
                 branch-tokens
                 (mapcat #(walk % helper-generated-calls** child-generated-context?) children)))

              (vector? node)
              (mapcat #(walk % helper-generated-calls* generated-context?) node)

              (map? node)
              (mapcat #(walk % helper-generated-calls* generated-context?) (concat (keys node) (vals node)))

              (set? node)
              (mapcat #(walk % helper-generated-calls* generated-context?) node)

              :else
              []))]
    (walk form helper-generated-calls generated-context?)))

(defn- generated-builder-call-tokens
  ([form]
   (generated-builder-call-tokens form {}))
  ([form helper-generated-calls]
   (->> (generated-builder-call-tokens* form helper-generated-calls false)
       distinct
       vec)))

(defn- helper-generated-call-tokens [form-text alias-map helper-generated-calls]
  (let [form (clj-read-form form-text)]
    (->> (generated-call-form-texts form-text)
         (mapcat #(extract-clj-calls % alias-map))
         (concat
          (->> (generated-builder-call-tokens form helper-generated-calls)
               (mapcat #(expand-clj-call-token % alias-map))
               (remove clj-call-stop)))
         distinct
         vec)))

(defn- extract-clj-generated-calls [form-text alias-map helper-generated-calls]
  (let [form (clj-read-form form-text)]
    (->> (helper-generated-call-tokens form-text alias-map helper-generated-calls)
         (concat
          (->> (defmacro-expansion-forms form)
               (mapcat #(generated-builder-call-tokens % helper-generated-calls))
               (mapcat #(expand-clj-call-token % alias-map))
               (remove clj-call-stop)))
         distinct
         vec)))

(defn- helper-form-record? [{:keys [operator]}]
  (contains? #{"defn" "defn-"} (str operator)))

(defn- top-level-helper-generated-calls [form-records ns-name alias-map]
  (let [helper-records (->> form-records
                            (filter helper-form-record?)
                            vec)]
    (loop [helper-map {}
           remaining 6]
      (let [next-map (reduce (fn [acc {:keys [raw-symbol form-text]}]
                               (let [tokens (helper-generated-call-tokens form-text alias-map acc)
                                     qualified (clj-qualified-symbol ns-name raw-symbol)]
                                 (cond-> acc
                                   (seq tokens) (assoc (str raw-symbol) tokens
                                                       (str qualified) tokens))))
                             helper-map
                             helper-records)]
        (if (or (= next-map helper-map) (<= remaining 0))
          next-map
          (recur next-map (dec remaining)))))))

(defn- clj-dispatch-fragment [dispatch-value]
  (some-> dispatch-value pr-str (str/replace #"\s+" " ") str/trim))

(defn- clj-unit-from-form
  [{:keys [path ns-name raw-symbol operator kind start-line end-line signature imports calls parser-mode alias-map helper-generated-calls]}
   form-text]
  (let [form (clj-read-form form-text)
        operator* (or operator (some-> form first str))
        kind* (or kind (clj-kind operator* path))
        symbol (clj-qualified-symbol ns-name raw-symbol)
        dispatch-value (when (= "defmethod" operator*)
                         (some-> form (nth 2 nil) clj-dispatch-fragment))
        generated-calls (when (= "defmacro" operator*)
                          (extract-clj-generated-calls form-text alias-map helper-generated-calls))
        unit-id (if dispatch-value
                  (str path "::" symbol "$dispatch" (short-hash dispatch-value))
                  (str path "::" symbol))
        summary (str kind* " " symbol
                     (when dispatch-value
                       (str " dispatch " dispatch-value)))]
    (cond-> {:unit_id unit-id
             :kind kind*
             :symbol symbol
             :path path
             :module ns-name
             :form_operator operator*
             :start_line start-line
             :end_line end-line
             :signature signature
             :summary summary
             :docstring_excerpt nil
             :imports imports
             :calls calls
             :parser_mode parser-mode}
      dispatch-value
      (assoc :call_tokens [(clj-dispatch-call-token symbol dispatch-value)])
      (seq generated-calls)
      (assoc :generated_calls generated-calls)
      dispatch-value
      (assoc :dispatch_value dispatch-value
             :multimethod_symbol symbol))))

(defn- clj-units-from-form
  [ctx form-text]
  (if (= "defprotocol" (:operator ctx))
    (clj-protocol-method-units ctx form-text)
    [(clj-unit-from-form ctx form-text)]))

(defn- usage-in-line-range? [usage start-line end-line]
  (let [row (long (or (:row usage) (:name-row usage) 0))]
    (and (pos? row)
         (<= (long start-line) row (long end-line)))))

(defn- clj-kondo-unit-calls [var-usages start-line end-line]
  (->> var-usages
       (filter #(usage-in-line-range? % start-line end-line))
       (keep usage->call-token)
       (remove #(contains? clj-call-stop %))
       distinct
       sort
       vec))

(defn- parse-clojure-regex [path lines]
  (let [line-count (count lines)
        line-start-depths (clj-line-start-depths lines)
        ns-name (some (fn [line] (some-> (re-find #"^\s*\(ns\s+([^\s\)]+).*" line) second)) lines)
        alias-map (clj-require-alias-map lines)
        imports (->> lines
                     (mapcat #(map second (re-seq clj-require-re %)))
                     distinct
                     vec)
        dispatch-symbols (->> lines
                              (map-indexed vector)
                              (keep (fn [[idx line]]
                                      (when (zero? (nth line-start-depths idx 1))
                                        (when-let [[_ kw raw-sym] (re-find clj-def-re line)]
                                          (when (= kw "defmulti")
                                            (clj-qualified-symbol ns-name raw-sym))))))
                              set)
        test-target-modules (clj-test-target-modules ns-name imports path)
        defs (->> (map-indexed vector lines)
                  (keep (fn [[idx line]]
                          (when (zero? (nth line-start-depths idx 1))
                            (when-let [[_ kw raw-sym] (re-find clj-def-re line)]
                              {:start-line (inc idx)
                                            :kind (clj-kind kw path)
                                            :operator kw
                                            :raw-symbol raw-sym
                                            :signature (trim-signature line)})))))
        starts (mapv :start-line defs)
        ends (if (seq starts)
               (mapv #(clj-form-end-line lines %) starts)
               (unit-end-lines starts line-count))
        form-records (->> (map vector defs ends)
                          (mapv (fn [[d end-line]]
                                  (let [start-line (:start-line d)
                                        body-lines (subvec lines (dec start-line) end-line)
                                        body (str/join "\n" body-lines)
                                        form-text (str/join "\n" body-lines)]
                                    {:def d
                                     :end-line end-line
                                     :body body
                                     :form-text form-text}))))
        helper-generated-calls (top-level-helper-generated-calls form-records ns-name alias-map)
        units (->> form-records
                   (mapcat (fn [{:keys [def end-line body form-text]}]
                             (clj-units-from-form {:path path
                                                   :ns-name ns-name
                                                   :raw-symbol (:raw-symbol def)
                                                   :operator (:operator def)
                                                   :kind (:kind def)
                                                   :start-line (:start-line def)
                                                   :end-line end-line
                                                   :signature (:signature def)
                                                   :imports imports
                                                   :calls (vec (distinct (concat (extract-clj-calls body alias-map)
                                                                                 (clj-dispatch-call-tokens form-text ns-name alias-map dispatch-symbols))))
                                                   :parser-mode "fallback"
                                                   :alias-map alias-map
                                                   :helper-generated-calls helper-generated-calls}
                                                  form-text)))
                   vec)]
    {:language "clojure"
     :module ns-name
     :imports imports
     :test_target_modules test-target-modules
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
        fallback (parse-clojure-regex path lines)
        var-defs (->> (:var-definitions analysis) (filter #(same-file? abs (:filename %))) vec)
        ns-usages (->> (:namespace-usages analysis) (filter #(same-file? abs (:filename %))) vec)
        var-usages (->> (:var-usages analysis) (filter #(same-file? abs (:filename %))) vec)
        imports (->> ns-usages (keep :to) (map str) distinct vec)
        test-target-modules (clj-test-target-modules (some-> var-defs first :ns str) imports path)
        primary-records
        (->> var-defs
             (map (fn [d]
                    (let [ns-name (str (:ns d))
                          nm (str (:name d))
                          start (max 1 (int (or (:name-row d) (:row d) 1)))
                          end (max start (int (or (:end-row d) start)))
                          form-text (str/join "\n" (subvec lines (dec start) end))
                          operator (some-> form-text clj-read-form first str)]
                      {:ns-name ns-name
                       :raw-symbol nm
                       :kind (kondo-defined-kind (:defined-by d) path)
                       :start start
                       :end end
                       :form-text form-text
                       :signature (safe-line lines start)
                       :operator operator})))
             vec)
        helper-generated-calls (top-level-helper-generated-calls (mapv (fn [{:keys [operator raw-symbol form-text]}]
                                                                         {:operator operator
                                                                          :raw-symbol raw-symbol
                                                                          :form-text form-text})
                                                                       primary-records)
                                                                 (some-> primary-records first :ns-name)
                                                                 (clj-require-alias-map lines))
        primary-units
        (->> primary-records
             (mapcat (fn [{:keys [ns-name raw-symbol kind start end form-text signature operator]}]
                       (clj-units-from-form {:path path
                                             :ns-name ns-name
                                             :raw-symbol raw-symbol
                                             :operator operator
                                             :kind kind
                                             :start-line start
                                             :end-line end
                                             :signature signature
                                             :imports imports
                                             :calls (vec (distinct (concat (clj-kondo-unit-calls var-usages start end)
                                                                           (clj-dispatch-call-tokens form-text ns-name (clj-require-alias-map lines) (set (map #(when (= "defmulti" (:operator %))
                                                                                                                                                            (clj-qualified-symbol ns-name (:raw-symbol %)))
                                                                                                                                                         primary-records))))))
                                             :parser-mode "full"
                                             :alias-map (clj-require-alias-map lines)
                                             :helper-generated-calls helper-generated-calls}
                                            form-text)))
             vec)
        existing-unit-ids (set (map :unit_id primary-units))
        supplemental-units (->> (:units fallback)
                                (remove #(contains? existing-unit-ids (:unit_id %)))
                                (map #(assoc % :parser_mode "full"))
                                vec)
        units (vec (concat primary-units supplemental-units))
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
       :test_target_modules test-target-modules
       :units units
       :diagnostics findings
       :parser_mode "full"}

      parsed
      (let [extra (cond-> [{:code "kondo_no_units" :summary "clj-kondo returned no var definitions for file."}]
                    (seq err) (conj {:code "kondo_stderr"
                                     :summary (subs err 0 (min 220 (count err)))}))]
        (-> fallback
            (update :diagnostics into extra)
            (assoc :parser_mode "fallback")))

      :else
      (-> fallback
          (update :diagnostics into [{:code "kondo_parse_failed"
                                      :summary "Unable to parse clj-kondo EDN output."}
                                     {:code "kondo_exit"
                                      :summary (str "clj-kondo exit=" exit)}])
          (assoc :parser_mode "fallback")))))

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
        alias-map (clj-require-alias-map src-lines)
        imports (->> src-lines (mapcat #(map second (re-seq clj-require-re %))) distinct vec)
        ns-name (some (fn [line] (some-> (re-find #"^\s*\(ns\s+([^\s\)]+).*" line) second)) src-lines)
        test-target-modules (clj-test-target-modules ns-name imports path)]
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
                                    (when (and op raw-name (contains? #{"defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol" "def" "deftest"} op))
                                      {:start-line (inc (:start-row r))
                                       :end-line (inc (:end-row r))
                                       :operator op
                                       :raw-symbol raw-name
                                       :calls (->> (drop 2 syms)
                                                   (remove clj-call-stop)
                                                   (mapcat #(expand-clj-call-token % alias-map))
                                                   distinct
                                                   vec)}))))
                          vec)
                form-records (->> defs
                                  (mapv (fn [{:keys [start-line end-line operator raw-symbol calls]}]
                                          {:start-line start-line
                                           :end-line end-line
                                           :operator operator
                                           :raw-symbol raw-symbol
                                           :calls calls
                                           :form-text (str/join "\n" (subvec src-lines (dec start-line) end-line))})))
                helper-generated-calls (top-level-helper-generated-calls form-records ns-name alias-map)
                dispatch-symbols (->> form-records
                                      (keep (fn [{:keys [operator raw-symbol]}]
                                              (when (= "defmulti" operator)
                                                (clj-qualified-symbol ns-name raw-symbol))))
                                      set)
                units (->> form-records
                           (mapcat (fn [{:keys [start-line end-line operator raw-symbol calls form-text]}]
                                     (clj-units-from-form {:path path
                                                           :ns-name ns-name
                                                           :raw-symbol raw-symbol
                                                           :operator operator
                                                           :start-line start-line
                                                           :end-line end-line
                                                           :signature (safe-line src-lines start-line)
                                                           :imports imports
                                                           :calls (vec (distinct (concat calls
                                                                                         (clj-dispatch-call-tokens form-text ns-name alias-map dispatch-symbols))))
                                                           :parser-mode "full"
                                                           :alias-map alias-map
                                                           :helper-generated-calls helper-generated-calls}
                                                          form-text)))
                           vec)]
            (if (seq units)
              {:ok? true
               :result {:language "clojure"
                        :module ns-name
                        :imports imports
                        :test_target_modules test-target-modules
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

(defn- java-kind
  ([path method-name]
   (java-kind path method-name nil))
  ([path method-name explicit-kind]
   (if explicit-kind
     explicit-kind
     (if (or (str/includes? (str/lower-case path) "/test/")
             (str/ends-with? method-name "Test")
             (str/starts-with? method-name "test"))
       "test"
       "method"))))

(defn- java-args-arity [args-text]
  (let [text (str/trim (or args-text ""))]
    (if (str/blank? text)
      0
      (loop [chars (seq text)
             depth 0
             in-string? false
             escaped? false
             commas 0]
        (if-let [ch (first chars)]
          (cond
            escaped?
            (recur (next chars) depth in-string? false commas)

            in-string?
            (cond
              (= ch \\) (recur (next chars) depth in-string? true commas)
              (= ch \") (recur (next chars) depth false false commas)
              :else (recur (next chars) depth in-string? false commas))

            (= ch \")
            (recur (next chars) depth true false commas)

            (#{\( \[ \< \{} ch)
            (recur (next chars) (inc depth) in-string? false commas)

            (#{\) \] \> \}} ch)
            (recur (next chars) (max 0 (dec depth)) in-string? false commas)

            (and (= ch \,) (zero? depth))
            (recur (next chars) depth in-string? false (inc commas))

            :else
            (recur (next chars) depth in-string? false commas))
          (inc commas))))))

(defn- java-call-details [body]
  (let [text (str body)
        n (count text)]
    (loop [idx 0
           in-string? false
           escaped? false
           details []]
      (if (>= idx n)
        details
        (let [ch (.charAt text idx)]
          (cond
            escaped?
            (recur (inc idx) in-string? false details)

            in-string?
            (cond
              (= ch \\) (recur (inc idx) in-string? true details)
              (= ch \") (recur (inc idx) false false details)
              :else (recur (inc idx) in-string? false details))

            (= ch \")
            (recur (inc idx) true false details)

            (= ch \()
            (let [prefix (subs text 0 idx)
                  token (some-> (re-find #"([A-Za-z_][A-Za-z0-9_\.]*)\s*$" prefix) second)
                  close-idx (loop [j (inc idx)
                                   depth 1
                                   in-string2? false
                                   escaped2? false]
                              (if (>= j n)
                                n
                                (let [ch2 (.charAt text j)]
                                  (cond
                                    escaped2?
                                    (recur (inc j) depth in-string2? false)

                                    in-string2?
                                    (cond
                                      (= ch2 \\) (recur (inc j) depth in-string2? true)
                                      (= ch2 \") (recur (inc j) depth false false)
                                      :else (recur (inc j) depth in-string2? false))

                                    (= ch2 \")
                                    (recur (inc j) depth true false)

                                    (= ch2 \()
                                    (recur (inc j) (inc depth) in-string2? false)

                                    (= ch2 \))
                                    (if (= depth 1)
                                      j
                                      (recur (inc j) (dec depth) in-string2? false))

                                    :else
                                    (recur (inc j) depth in-string2? false)))))
                  args-text (if (< idx close-idx) (subs text (inc idx) close-idx) "")]
              (recur (inc idx)
                     in-string?
                     false
                     (if (or (str/blank? token)
                             (contains? java-call-stop (str/lower-case token)))
                       details
                       (conj details {:token token
                                      :arity (java-args-arity args-text)}))))

            :else
            (recur (inc idx) in-string? false details)))))))

(defn- java-call-arity-index [call-details]
  (reduce (fn [acc {:keys [token arity]}]
            (let [tail (tail-token token)]
              (cond-> acc
                (seq token) (update token (fnil conj #{}) arity)
                (and tail (not= tail token)) (update tail (fnil conj #{}) arity))))
          {}
          call-details))

(defn- java-call-scan-body [lines start-line end-line]
  (let [segment (subvec lines (dec start-line) end-line)
        first-line (first segment)
        stripped-first (if-let [brace-idx (some-> first-line (str/index-of "{"))]
                         (subs first-line (inc brace-idx))
                         "")
        body-lines (cond-> [(or stripped-first "")]
                     (> (count segment) 1) (into (subvec segment 1)))]
    (str/join "\n" body-lines)))

(defn- extract-java-calls [body]
  (->> (java-call-details body)
       (map :token)
       (mapcat (fn [token]
                 (if (re-find #"[.#]" (str token))
                   [token]
                   (let [tail (tail-token token)]
                     (cond-> [token]
                       (and tail (not= tail token)) (conj tail))))))
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
                             (let [class-name (->> class-spots
                                                   (filter #(<= (:line %) (inc idx)))
                                                   last
                                                   :class)
                                   method-match (re-find java-method-re line)
                                   constructor-match (re-find java-constructor-re line)]
                               (cond
                                 constructor-match
                                 (let [[_ _visibility ctor-name params] constructor-match]
                                   (when (= ctor-name class-name)
                                     {:start-line (inc idx)
                                      :method ctor-name
                                      :params params
                                      :class class-name
                                      :signature (trim-signature line)
                                      :kind "constructor"}))

                                 method-match
                                 (let [[_ _visibility return-type m params] method-match]
                                   (when-not (contains? java-call-stop (str/lower-case (str return-type)))
                                     {:start-line (inc idx)
                                      :method m
                                      :params params
                                      :class class-name
                                      :signature (trim-signature line)
                                      :kind nil}))))))
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
                                body (java-call-scan-body lines start-line end-line)
                                call-details (java-call-details body)]
                            {:unit_id unit_id
                             :kind (java-kind path (:method m) (:kind m))
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
                             :call_arity_by_token (java-call-arity-index call-details)
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
                             (filter #(contains? #{"method_declaration" "constructor_declaration"} (:node-type %)))
                             (map (fn [m]
                                    (let [cls (->> classes
                                                   (filter #(<= (:start-row %) (:start-row m) (:end-row %)))
                                                   (sort-by :start-row)
                                                   last
                                                   :class-name)
                                          constructor? (= "constructor_declaration" (:node-type m))
                                          method-name (if constructor?
                                                        (or cls "UnknownClass")
                                                        (or (node-name-inside ts-lines m "name:") "unknownMethod"))
                                          calls (->> ts-lines
                                                     (filter #(and (= "method_invocation" (:node-type %))
                                                                   (<= (:start-row m) (:start-row %) (:end-row m))))
                                                     (map #(node-name-inside ts-lines % "name:"))
                                                     (remove nil?)
                                                     distinct
                                                     vec)
                                          call-details (mapv (fn [token] {:token token}) calls)]
                                      {:start-line (inc (:start-row m))
                                       :end-line (inc (:end-row m))
                                       :method method-name
                                       :kind (when constructor? "constructor")
                                       :class (or cls "UnknownClass")
                                       :params (java-param-fragment-from-source src-lines (inc (:start-row m)))
                                       :calls calls
                                       :call_details call-details})))
                             vec)
                units (->> methods
                           (map (fn [{:keys [start-line end-line method kind class calls call_details params]}]
                                  (let [symbol (str (when pkg (str pkg ".")) class "#" method)
                                        {:keys [unit_id method_arity method_signature_key]}
                                        (java-method-unit-id path symbol params)]
                                    {:unit_id unit_id
                                     :kind (java-kind path method kind)
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
                                     :call_arity_by_token (java-call-arity-index call_details)
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

(def ^:private ex-test-module-suffixes
  ["Test"])

(defn- ex-last-segment [module]
  (some-> module str (str/split #"\.") last))

(defn- ex-strip-test-suffix [module]
  (reduce (fn [acc suffix]
            (if (str/ends-with? acc suffix)
              (subs acc 0 (- (count acc) (count suffix)))
              acc))
          (str module)
          ex-test-module-suffixes))

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

(defn- ex-directive-targets [lines directive-re alias-map]
  (->> lines
       (keep (fn [line]
               (some-> (re-find directive-re line)
                       second
                       ex-strip-comment
                       (str/split #"," 2)
                       first
                       str/trim
                       (ex-resolve-alias-ref alias-map))))
       (remove str/blank?)
       distinct
       vec))

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

(defn- ex-expand-module-self-token [token module-name]
  (let [token* (str token)]
    (if-let [[_ suffix] (re-matches #"__MODULE__\.(.+)" token*)]
      (when (seq module-name)
        [(str module-name "." suffix)
         (str module-name "/" suffix)])
      [])))

(defn- ex-local-call-shadowed? [token arities local-call-arities]
  (let [local-arities (get local-call-arities (str token))]
    (cond
      (empty? local-arities) false
      (seq arities) (boolean (some #(contains? local-arities %) arities))
      :else true)))

(defn- ex-expand-import-token [token import-modules local-call-arities call-arity-index]
  (let [arities (or (get call-arity-index (str token))
                    (get call-arity-index (tail-token token))
                    #{})]
    (when-not (or (str/includes? (str token) ".")
                  (ex-local-call-shadowed? token arities local-call-arities))
      (->> import-modules
           (mapcat (fn [module]
                     [(str module "." token)
                      (str module "/" token)]))
           distinct
           vec))))

(defn- ex-args-arity [args-text]
  (let [s (str/trim (or args-text ""))]
    (if (str/blank? s)
      0
      (loop [chars (seq s)
             depth 0
             in-string? false
             escaped? false
             arity 1]
        (if-let [ch (first chars)]
          (cond
            escaped?
            (recur (next chars) depth in-string? false arity)

            in-string?
            (cond
              (= ch \\) (recur (next chars) depth in-string? true arity)
              (= ch \") (recur (next chars) depth false false arity)
              :else (recur (next chars) depth in-string? false arity))

            (= ch \")
            (recur (next chars) depth true false arity)

            (#{\( \[ \{} ch)
            (recur (next chars) (inc depth) in-string? false arity)

            (#{\) \] \}} ch)
            (recur (next chars) (max 0 (dec depth)) in-string? false arity)

            (and (= ch \,) (zero? depth))
            (recur (next chars) depth in-string? false (inc arity))

            :else
            (recur (next chars) depth in-string? false arity))
          arity)))))

(defn- ex-def-arity [line]
  (when-let [[_ _ _ args] (re-find #"^\s*(defdelegate|defp?|defmacro|defmacrop)\s+([a-zA-Z_][a-zA-Z0-9_!?]*)\s*(?:\(([^)]*)\))?" line)]
    (when (some? args)
      (ex-args-arity args))))

(defn- ex-call-arity-index [body]
  (reduce (fn [acc [_ token args]]
            (let [arity (ex-args-arity args)
                  tail (tail-token token)]
              (cond-> acc
                (seq token) (update token (fnil conj #{}) arity)
                (and tail (not= tail token)) (update tail (fnil conj #{}) arity))))
          {}
          (re-seq ex-call-with-args-re body)))

(defn- ex-delegate-calls [body alias-map]
  (if-let [[_ fun target] (re-find #"defdelegate\s+([a-zA-Z_][a-zA-Z0-9_!?]*)\s*(?:\([^)]*\))?\s*,\s*to:\s*([A-Za-z0-9_\.]+)" body)]
    (let [target* (ex-resolve-alias-ref target alias-map)]
      (->> [(str target* "." fun)
            (str target* "/" fun)]
           (remove str/blank?)
           distinct
           vec))
    []))

(defn- ex-use-expansion-imports [body alias-map]
  (->> (str/split-lines (str body))
       (mapcat #(or (ex-directive-targets [%] ex-import-only-re alias-map) []))
       distinct
       vec))

(defn- extract-ex-calls [body module-name alias-map import-modules local-call-arities call-arity-index]
  (->> (re-seq ex-call-re body)
       (map second)
       (mapcat (fn [token]
                 (let [expanded (ex-expand-alias-token token alias-map)
                       module-self (ex-expand-module-self-token token module-name)
                       imported (ex-expand-import-token token import-modules local-call-arities call-arity-index)]
                   (cond-> [token]
                     (seq expanded) (conj expanded)
                     (seq module-self) (into module-self)
                     (seq imported) (into imported)))))
       (mapcat (fn [token]
                 (let [tail (tail-token token)]
                   (cond-> [token]
                     (and tail (not= tail token)) (conj tail)))))
      (remove #(contains? ex-call-stop %))
      distinct
      vec))

(defn- ex-test-target-modules [module imports uses path]
  (let [module* (str (or module ""))
        exunit-test? (some #{"ExUnit.Case"} uses)]
    (if (or (str/includes? (str path) "/test/")
            (str/ends-with? module* "Test")
            exunit-test?)
      (->> (concat [(ex-strip-test-suffix module*)] imports)
           (remove #(or (str/blank? %)
                        (= % "ExUnit.Case")))
           distinct
           vec)
      [])))

(defn- parse-elixir [path lines]
  (let [line-count (count lines)
        module-name (some (fn [line] (some-> (re-find ex-module-re line) second)) lines)
        alias-map (ex-alias-map lines)
        import-modules (ex-directive-targets lines ex-import-only-re alias-map)
        use-modules (ex-directive-targets lines ex-use-only-re alias-map)
        call-expansion-modules (->> (concat import-modules use-modules)
                                    distinct
                                    vec)
        imports (->> (concat
                      import-modules
                      use-modules
                      (vals alias-map))
                     distinct
                     vec)
        test-target-modules (ex-test-target-modules module-name imports use-modules path)
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
                               :method_arity (ex-def-arity line)
                               :signature (trim-signature line)}))))
                  vec)
        local-call-arities (->> defs
                                (keep (fn [{:keys [raw-symbol method_arity]}]
                                        (when-let [fn-name (some-> raw-symbol str (str/split #"/" 2) last)]
                                          [fn-name method_arity])))
                                (reduce (fn [acc [fn-name arity]]
                                          (update acc fn-name (fnil conj #{}) arity))
                                        {}))
        ends (->> defs
                  (map-indexed
                   (fn [idx d]
                     (let [start-line (:start-line d)
                           next-start (:start-line (nth defs (inc idx) nil))
                           ceiling-line (max start-line (or (some-> next-start dec) line-count))]
                       (ex-unit-end-line lines start-line ceiling-line (:form d)))))
                  vec)
        unit-records (->> (map vector defs ends)
                          (mapv
                           (fn [[d end-line]]
                             (let [start-line (:start-line d)
                                   body-lines (subvec lines (dec start-line) end-line)
                                   body (str/join "\n" body-lines)
                                   method-arity (:method_arity d)
                                   call-arity-index (if (= "defdelegate" (:form d))
                                                      {}
                                                      (ex-call-arity-index body))]
                               {:unit {:unit_id (cond-> (str path "::" (:raw-symbol d))
                                                  (some? method-arity) (str "$arity" method-arity))
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
                                       :calls (if (= "defdelegate" (:form d))
                                                (ex-delegate-calls body alias-map)
                                                (extract-ex-calls body module-name alias-map call-expansion-modules local-call-arities call-arity-index))
                                       :method_arity method-arity
                                       :call_arity_by_token (if (= "defdelegate" (:form d))
                                                              {}
                                                              call-arity-index)
                                       :ex_form (:form d)
                                       :parser_mode "full"}
                                :use-expansion-imports
                                (when (and (= "defmacro" (:form d))
                                           (= (str module-name "/__using__") (:raw-symbol d)))
                                  (ex-use-expansion-imports body alias-map))}))))
        units (mapv :unit unit-records)
        use-expansion-imports (->> unit-records
                                   (mapcat #(or (:use-expansion-imports %) []))
                                   distinct
                                   vec)]
    {:language "elixir"
     :module module-name
     :imports imports
     :use_modules use-modules
     :use_expansion_imports use-expansion-imports
     :test_target_modules test-target-modules
     :units units
     :diagnostics []
     :parser_mode "full"}))

(defn- py-module-name [path]
  (-> path
      (str/replace #"\.py$" "")
      (str/replace #"/" ".")
      (str/replace #"^\.+" "")))

(defn- py-normalize-relative-module [module import-module]
  (let [import* (str import-module)]
    (if (str/starts-with? import* ".")
      (let [dot-count (count (re-find #"^\.*" import*))
            suffix (str/replace import* #"^\.*" "")
            module-parts (->> (str/split (str module) #"\.")
                              (remove str/blank?))
            package-parts (vec (butlast module-parts))
            up-levels (max 0 (dec dot-count))
            kept-count (max 0 (- (count package-parts) up-levels))
            base-parts (subvec package-parts 0 kept-count)
            suffix-parts (->> (str/split suffix #"\.")
                              (remove str/blank?))
            resolved (concat base-parts suffix-parts)]
        (str/join "." resolved))
      import*)))

(defn- py-test-path? [path]
  (let [p (str/lower-case (str path))]
    (or (str/includes? p "/test/")
        (str/includes? p "/tests/")
        (str/ends-with? p "_test.py")
        (str/starts-with? (last (str/split p #"/")) "test_"))))

(defn- py-strip-test-module [module]
  (let [m (str module)]
    (cond
      (str/ends-with? m "_test") (subs m 0 (- (count m) 5))
      (re-find #"\.test_[^.]+$" m) (str/replace m #"\.test_[^.]+$" "")
      :else m)))

(defn- py-kind [path fn-name]
  (if (or (py-test-path? path)
          (str/starts-with? fn-name "test_")
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

(defn- py-import-state [module lines]
  (reduce
   (fn [{:keys [imports module-aliases symbol-aliases] :as acc} line]
     (cond
       (re-find py-from-import-re line)
       (let [[_ from names] (re-find py-from-import-re line)
             from (py-normalize-relative-module module from)
             parts (->> (str/split names #",")
                        (map str/trim)
                        (remove str/blank?))
             imports* (into imports [from])
             symbol-aliases* (reduce (fn [m part]
                                       (let [[_ name alias] (or (re-find #"^([A-Za-z0-9_\*]+)(?:\s+as\s+([A-Za-z0-9_]+))?$" part)
                                                                [nil part nil])
                                             local (or alias name)]
                                         (if (= name "*")
                                           m
                                           (assoc m local (str from "/" name)))))
                                     symbol-aliases
                                     parts)]
         {:imports imports*
          :module-aliases module-aliases
          :symbol-aliases symbol-aliases*})

       (re-find py-import-re line)
       (let [[_ imp alias] (re-find py-import-re line)]
         {:imports (conj imports imp)
          :module-aliases (cond-> module-aliases
                            (seq alias) (assoc alias imp))
          :symbol-aliases symbol-aliases})

       :else
       acc))
   {:imports [] :module-aliases {} :symbol-aliases {}}
   lines))

(defn- py-expand-module-alias [token module-aliases]
  (let [token* (str token)]
    (if-let [[_ alias suffix] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)\.(.+)" token*)]
      (when-let [base (get module-aliases alias)]
        (str base "." suffix))
      nil)))

(defn- py-expand-self-token [token module class-name]
  (let [token* (str token)]
    (if-let [[_ owner suffix] (re-matches #"(self|cls)\.(.+)" token*)]
      (let [base (str module "." class-name)]
        [(str base "." suffix) (str base "/" suffix)])
      [])))

(defn- py-expand-local-class-token [token module local-class-names]
  (let [token* (str token)]
    (if-let [[_ cls suffix] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)\.(.+)" token*)]
      (when (contains? local-class-names cls)
        (let [base (str module "." cls)]
          [(str base "." suffix) (str base "/" suffix)]))
      [])))

(defn- py-expand-symbol-import [token symbol-aliases local-call-names]
  (when (not (contains? local-call-names (str token)))
    (when-let [resolved (get symbol-aliases (str token))]
      [(str resolved)
       (str/replace (str resolved) #"/" ".")])))

(defn- py-local-call-names [defs]
  (->> defs
       (keep :raw-symbol)
       (map (fn [symbol]
              (let [s (str symbol)]
                (cond
                  (str/includes? s "/") (last (str/split s #"/" 2))
                  (str/includes? s ".") (last (str/split s #"\."))
                  :else s))))
       (remove str/blank?)
       set))

(defn- extract-py-calls [body {:keys [module class-name module-aliases symbol-aliases local-call-names local-class-names]}]
  (->> (re-seq py-call-re body)
       (map second)
       (mapcat (fn [token]
                 (let [module-alias-token (py-expand-module-alias token module-aliases)
                       imported-symbols (py-expand-symbol-import token symbol-aliases local-call-names)
                       self-symbols (if (and class-name module)
                                      (py-expand-self-token token module class-name)
                                      [])
                       class-symbols (if module
                                       (py-expand-local-class-token token module local-class-names)
                                       [])
                       tail (tail-token token)]
                   (cond-> [token]
                     (seq module-alias-token) (conj module-alias-token)
                     (seq imported-symbols) (into imported-symbols)
                     (seq self-symbols) (into self-symbols)
                     (seq class-symbols) (into class-symbols)
                     (and tail (not= tail token)) (conj tail)))))
       (remove #(contains? py-call-stop %))
       distinct
       vec))
(defn- py-test-target-modules [module imports path]
  (if (py-test-path? path)
    (->> (concat [(py-strip-test-module module)] imports)
         (remove #(or (str/blank? %)
                      (= % "unittest")
                      (= % "pytest")))
         distinct
         vec)
    []))

(defn- parse-python [path lines]
  (let [line-count (count lines)
        module (py-module-name path)
        {:keys [imports module-aliases symbol-aliases]} (py-import-state module lines)
        imports (->> imports distinct vec)
        test-target-modules (py-test-target-modules module imports path)
        defs (loop [idx 0
                    class-stack []
                 out []]
               (if (>= idx line-count)
                 out
                 (let [line (nth lines idx)
                       indent (py-indent line)
                       blank-or-comment? (or (str/blank? (str/trim line))
                                             (str/starts-with? (str/trim line) "#"))
                       pruned (if blank-or-comment?
                                class-stack
                                (->> class-stack
                                     (filter #(< (:indent %) indent))
                                     vec))]
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
                                  :class-name class-name
                                  :signature (trim-signature line)}]
                       (recur (inc idx) pruned (conj out entry)))

                     :else
                     (recur (inc idx) pruned out)))))
        local-call-names (py-local-call-names defs)
        local-class-names (->> defs
                               (filter #(= "class" (:kind %)))
                               (keep (fn [{:keys [raw-symbol]}]
                                       (some-> raw-symbol str (str/split #"\.") last)))
                               set)
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
                             :calls (extract-py-calls body {:module module
                                                           :class-name (:class-name d)
                                                           :module-aliases module-aliases
                                                           :symbol-aliases symbol-aliases
                                                           :local-call-names local-call-names
                                                           :local-class-names local-class-names})
                             :parser_mode "full"})))
                   vec)]
    {:language "python"
     :module module
     :imports imports
     :test_target_modules test-target-modules
     :units units
     :diagnostics []
     :parser_mode "full"}))

(defn- ts-test-path? [path]
  (let [p (str/lower-case (str path))]
    (or (str/includes? p "/test/")
        (str/ends-with? p ".test.ts")
        (str/ends-with? p ".test.tsx")
        (str/ends-with? p ".spec.ts")
        (str/ends-with? p ".spec.tsx")
        (str/ends-with? p "_test.ts")
        (str/ends-with? p "_test.tsx"))))

(defn- ts-kind [path name method?]
  (if (or (ts-test-path? path)
          (str/starts-with? (str/lower-case (or name "")) "test"))
    "test"
    (if method? "method" "function")))

(defn- ts-brace-delta [line]
  (- (count (re-seq #"\{" (str line)))
     (count (re-seq #"\}" (str line)))))

(defn- ts-class-ranges [lines]
  (let [line-count (count lines)]
    (loop [idx 0
           out []]
      (if (>= idx line-count)
        out
        (let [line (nth lines idx)]
          (if-let [[_ cls] (re-find ts-class-re line)]
            (let [end-line
                  (loop [j idx
                         depth 0
                         saw-open? false]
                    (if (>= j line-count)
                      line-count
                      (let [ln (nth lines j)
                            open-n (count (re-seq #"\{" ln))
                            close-n (count (re-seq #"\}" ln))
                            saw-open?* (or saw-open? (pos? open-n))
                            depth* (+ depth open-n (- close-n))]
                        (if (and saw-open?* (<= depth* 0))
                          (inc j)
                          (recur (inc j) depth* saw-open?*)))))
                  range {:class cls
                         :start-line (inc idx)
                         :end-line (max (inc idx) end-line)}]
              (recur (inc idx) (conj out range)))
            (recur (inc idx) out)))))))

(defn- ts-class-for-line [class-ranges line-no]
  (->> class-ranges
       (filter #(<= (:start-line %) line-no (:end-line %)))
       (sort-by :start-line)
       last))

(defn- ts-local-call-names [defs]
  (->> defs
       (keep :raw-symbol)
       (map (fn [symbol]
              (let [s (str symbol)]
                (cond
                  (str/includes? s "#") (last (str/split s #"#"))
                  (str/includes? s "/") (last (str/split s #"/" 2))
                  :else s))))
       (remove str/blank?)
       set))

(defn- ts-expand-module-alias [token module-aliases]
  (let [token* (str token)]
    (when-let [[_ alias suffix] (re-matches #"([A-Za-z_$][A-Za-z0-9_$]*)\.(.+)" token*)]
      (when-let [base (get module-aliases alias)]
        (str base "." suffix)))))

(defn- ts-expand-symbol-import [token symbol-aliases local-call-names]
  (let [token* (str token)]
    (when (not (contains? local-call-names token*))
      (when-let [resolved (get symbol-aliases token*)]
        [(str resolved)
         (str/replace (str resolved) #"/" ".")]))))

(defn- ts-expand-default-import [token default-aliases local-call-names]
  (let [token* (str token)]
    (when (not (contains? local-call-names token*))
      (when-let [resolved (get default-aliases token*)]
        [(str resolved)
         (str/replace (str resolved) #"/" ".")]))))

(defn- ts-expand-this-token [token owner-module]
  (let [token* (str token)]
    (if-let [[_ _ suffix] (re-matches #"(this|super)\.(.+)" token*)]
      [(str owner-module "#" suffix)
       (str owner-module "." suffix)]
      [])))

(defn- ts-expand-local-class-token [token file-module local-class-names]
  (let [token* (str token)]
    (if-let [[_ cls suffix] (re-matches #"([A-Za-z_$][A-Za-z0-9_$]*)\.(.+)" token*)]
      (when (contains? local-class-names cls)
        (let [base (str file-module "." cls)]
          [(str base "#" suffix)
           (str base "." suffix)]))
      [])))

(defn- extract-ts-calls [body {:keys [owner-module file-module module-aliases symbol-aliases
                                      default-aliases local-call-names local-class-names]}]
  (->> (re-seq ts-call-re body)
       (map second)
       (mapcat (fn [token]
                 (let [module-alias-token (ts-expand-module-alias token module-aliases)
                       imported-symbols (ts-expand-symbol-import token symbol-aliases local-call-names)
                       imported-defaults (ts-expand-default-import token default-aliases local-call-names)
                       this-symbols (if (seq owner-module)
                                      (ts-expand-this-token token owner-module)
                                      [])
                       class-symbols (if (seq file-module)
                                       (ts-expand-local-class-token token file-module local-class-names)
                                       [])
                       tail (tail-token token)]
                   (cond-> [token]
                     (seq module-alias-token) (conj module-alias-token)
                     (seq imported-symbols) (into imported-symbols)
                     (seq imported-defaults) (into imported-defaults)
                     (seq this-symbols) (into this-symbols)
                     (seq class-symbols) (into class-symbols)
                     (and tail (not= tail token)) (conj tail)))))
       (remove #(contains? ts-call-stop %))
       distinct
       vec))

(defn- parse-typescript-regex [path lines]
  (let [line-count (count lines)
        module (ts-module-name path)
        {:keys [imports module-aliases symbol-aliases default-aliases]} (ts-import-state path lines)
        imports (->> imports distinct vec)
        class-ranges (ts-class-ranges lines)
        defs (->> (map-indexed vector lines)
                  (keep (fn [[idx line]]
                          (let [line-no (inc idx)
                                class-ctx (ts-class-for-line class-ranges line-no)]
                            (cond
                              (and (nil? class-ctx) (re-find ts-function-re line))
                              (let [[_ fn-name] (re-find ts-function-re line)]
                                {:start-line line-no
                                 :kind (ts-kind path fn-name false)
                                 :raw-symbol (str module "/" fn-name)
                                 :call_tokens (cond-> []
                                                (ts-default-export-line? line) (conj (str module "/default")))
                                 :module module
                                 :signature (trim-signature line)})

                              (and (nil? class-ctx) (re-find ts-arrow-re line))
                              (let [[_ fn-name] (re-find ts-arrow-re line)]
                                {:start-line line-no
                                 :kind (ts-kind path fn-name false)
                                 :raw-symbol (str module "/" fn-name)
                                 :module module
                                 :signature (trim-signature line)})

                              (and (nil? class-ctx) (re-find ts-function-expression-re line))
                              (let [[_ fn-name] (re-find ts-function-expression-re line)]
                                {:start-line line-no
                                 :kind (ts-kind path fn-name false)
                                 :raw-symbol (str module "/" fn-name)
                                 :module module
                                 :signature (trim-signature line)})

                              (and class-ctx (re-find ts-method-re line))
                              (let [[_ method-name] (re-find ts-method-re line)]
                                (when-not (or (contains? ts-call-stop method-name)
                                              (= "constructor" method-name))
                                  {:start-line line-no
                                   :kind (ts-kind path method-name true)
                                   :raw-symbol (str module "." (:class class-ctx) "#" method-name)
                                   :class-name (:class class-ctx)
                                   :module (str module "." (:class class-ctx))
                                   :signature (trim-signature line)}))

                              :else nil))))
                  vec)
        local-call-names (ts-local-call-names defs)
        local-class-names (->> class-ranges
                               (keep :class)
                               set)
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
                             :module (:module d)
                             :start_line start-line
                             :end_line end-line
                             :signature (:signature d)
                             :summary (str (:kind d) " " (:raw-symbol d))
                             :docstring_excerpt nil
                             :imports imports
                             :call_tokens (vec (:call_tokens d))
                             :calls (extract-ts-calls body {:owner-module (:module d)
                                                           :file-module module
                                                           :module-aliases module-aliases
                                                           :symbol-aliases symbol-aliases
                                                           :default-aliases default-aliases
                                                           :local-call-names local-call-names
                                                           :local-class-names local-class-names})
                             :parser_mode "full"})))
                   vec)]
    {:language "typescript"
     :module module
     :imports imports
     :units units
     :diagnostics []
     :parser_mode "full"}))

(defn- ts-node-type [node]
  (let [node-type (str (:node-type node))
        txt (str (:text node))]
    (if (str/ends-with? node-type ":")
      (or (some-> (re-find #":\s*([A-Za-z_][A-Za-z0-9_]*)" txt) second)
          node-type)
      node-type)))

(defn- ts-node-values-inside [ts-lines node]
  (->> ts-lines
       (filter #(<= (:start-row node) (:start-row %) (:end-row node)))
       (filter #(< (:indent node) (:indent %)))
       (filter #(contains? #{"identifier" "property_identifier" "type_identifier"} (ts-node-type %)))
       (keep :value)
       (remove str/blank?)
       vec))

(defn- ts-named-value-inside [ts-lines node marker]
  (->> ts-lines
       (filter #(<= (:start-row node) (:start-row %) (:end-row node)))
       (filter #(< (:indent node) (:indent %)))
       (filter #(contains? #{"identifier" "property_identifier" "type_identifier"} (ts-node-type %)))
       (filter #(str/includes? (:text %) marker))
       (keep :value)
       (remove str/blank?)
       first))

(defn- ts-call-name [ts-lines call-node]
  (or (ts-named-value-inside ts-lines call-node "function:")
      (ts-named-value-inside ts-lines call-node "property:")
      (some-> (ts-node-values-inside ts-lines call-node) last)))

(defn- parse-typescript-tree-sitter [root-path path src-lines parser-opts]
  (let [grammar-path (parser-grammar-path parser-opts :typescript)
        abs (-> (io/file root-path path) .getCanonicalPath)
        module (ts-module-name path)
        {:keys [imports module-aliases symbol-aliases default-aliases]} (ts-import-state path src-lines)
        imports (->> imports distinct vec)]
    (cond
      (not (tree-sitter-available?))
      {:ok? false
       :reason {:code "tree_sitter_unavailable"
                :summary "tree-sitter CLI is unavailable for typescript tree-sitter parser."}}

      (str/blank? (str grammar-path))
      {:ok? false
       :reason {:code "tree_sitter_missing_grammar"
                :summary "No tree-sitter TypeScript grammar path configured."}}

      :else
      (let [{:keys [ok? lines err]} (tree-sitter-cst abs grammar-path)
            ts-lines lines]
        (if-not ok?
          {:ok? false
           :reason {:code "tree_sitter_parse_failed"
                    :summary (str "tree-sitter parse failed: " (subs (str err) 0 (min 220 (count (str err)))))}}
          (let [class-nodes (->> ts-lines
                                 (filter #(= "class_declaration" (ts-node-type %)))
                                 (map (fn [c]
                                        (assoc c :class-name
                                               (or (ts-named-value-inside ts-lines c "name:")
                                                   (some-> (ts-node-values-inside ts-lines c) first)
                                                   "UnknownClass"))))
                                 vec)
                class-for-row (fn [row]
                                (->> class-nodes
                                     (filter #(<= (:start-row %) row (:end-row %)))
                                     (sort-by :start-row)
                                     last))
                local-class-names (->> class-nodes
                                       (keep :class-name)
                                       set)
                fn-defs (->> ts-lines
                             (filter #(= "function_declaration" (ts-node-type %)))
                             (map (fn [n]
                                    (let [nm (or (ts-named-value-inside ts-lines n "name:")
                                                 (some-> (ts-node-values-inside ts-lines n) first)
                                                 "unknownFunction")
                                          start-line (inc (:start-row n))
                                          end-line (inc (:end-row n))]
                                      {:start-line (inc (:start-row n))
                                       :end-line (inc (:end-row n))
                                       :kind (ts-kind path nm false)
                                       :symbol (str module "/" nm)
                                       :call_tokens (cond-> []
                                                      (ts-default-export-line? (safe-line src-lines start-line))
                                                      (conj (str module "/default")))
                                       :module module
                                       :file-module module
                                       :calls (extract-ts-calls (str/join "\n" (subvec src-lines (dec start-line) end-line))
                                                                {:owner-module module
                                                                 :file-module module
                                                                 :module-aliases module-aliases
                                                                 :symbol-aliases symbol-aliases
                                                                 :default-aliases default-aliases
                                                                 :local-call-names #{}
                                                                 :local-class-names local-class-names})})))
                             vec)
                arrow-defs (->> ts-lines
                                (filter #(= "variable_declarator" (ts-node-type %)))
                                (keep (fn [n]
                                        (let [has-callable? (some (fn [child]
                                                                    (and (<= (:start-row n) (:start-row child) (:end-row n))
                                                                         (< (:indent n) (:indent child))
                                                                         (contains? #{"arrow_function" "function_expression"} (ts-node-type child))))
                                                                  ts-lines)]
                                          (when has-callable?
                                            (let [nm (or (ts-named-value-inside ts-lines n "name:")
                                                         (some-> (ts-node-values-inside ts-lines n) first)
                                                         "unknownArrow")
                                                  start-line (inc (:start-row n))
                                                  end-line (inc (:end-row n))]
                                              {:start-line start-line
                                               :end-line end-line
                                               :kind (ts-kind path nm false)
                                               :symbol (str module "/" nm)
                                               :module module
                                               :file-module module
                                               :calls (extract-ts-calls (str/join "\n" (subvec src-lines (dec start-line) end-line))
                                                                        {:owner-module module
                                                                         :file-module module
                                                                         :module-aliases module-aliases
                                                                         :symbol-aliases symbol-aliases
                                                                         :default-aliases default-aliases
                                                                         :local-call-names #{}
                                                                         :local-class-names local-class-names})})))))
                                vec)
                method-defs (->> ts-lines
                                 (filter #(= "method_definition" (ts-node-type %)))
                                 (keep (fn [n]
                                         (let [class-ctx (class-for-row (:start-row n))
                                               nm (or (ts-named-value-inside ts-lines n "name:")
                                                      (some-> (ts-node-values-inside ts-lines n) first))]
                                           (when (and class-ctx (seq nm) (not= "constructor" nm))
                                             (let [owner-module (str module "." (:class-name class-ctx))
                                                   start-line (inc (:start-row n))
                                                   end-line (inc (:end-row n))]
                                               {:start-line start-line
                                                :end-line end-line
                                                :kind (ts-kind path nm true)
                                                :symbol (str owner-module "#" nm)
                                                :module owner-module
                                                :file-module module
                                                :calls (extract-ts-calls (str/join "\n" (subvec src-lines (dec start-line) end-line))
                                                                         {:owner-module owner-module
                                                                          :file-module module
                                                                          :module-aliases module-aliases
                                                                          :symbol-aliases symbol-aliases
                                                                          :default-aliases default-aliases
                                                                          :local-call-names #{}
                                                                          :local-class-names local-class-names})})))))
                                 vec)
                defs (->> (concat fn-defs arrow-defs method-defs)
                          (sort-by (juxt :start-line :symbol))
                          distinct
                          vec)
                local-call-names (ts-local-call-names (map #(assoc % :raw-symbol (:symbol %)) defs))
                units (->> defs
                           (map (fn [{:keys [start-line end-line kind symbol module file-module calls call_tokens]}]
                                  {:unit_id (str path "::" symbol)
                                   :kind kind
                                   :symbol symbol
                                   :path path
                                   :module module
                                   :start_line start-line
                                   :end_line (max start-line end-line)
                                   :signature (safe-line src-lines start-line)
                                   :summary (str kind " " symbol)
                                   :docstring_excerpt nil
                                   :imports imports
                                   :call_tokens (vec call_tokens)
                                   :calls (extract-ts-calls (str/join "\n" (subvec src-lines (dec start-line) end-line))
                                                            {:owner-module module
                                                             :file-module file-module
                                                             :module-aliases module-aliases
                                                             :symbol-aliases symbol-aliases
                                                             :default-aliases default-aliases
                                                             :local-call-names local-call-names
                                                             :local-class-names local-class-names})
                                   :parser_mode "full"}))
                           vec)]
            (if (seq units)
              {:ok? true
               :result {:language "typescript"
                        :module module
                        :imports imports
                        :units units
                        :diagnostics [{:code "tree_sitter_active"
                                       :summary "TypeScript analyzed using tree-sitter CST extraction."}]
                        :parser_mode "full"}}
              {:ok? false
               :reason {:code "tree_sitter_no_units"
                        :summary "tree-sitter did not extract TypeScript units."}})))))))

(defn- parse-typescript [root-path path lines {:keys [typescript_engine tree_sitter_enabled]
                                               :or {typescript_engine :regex}
                                               :as parser-opts}]
  (let [engine (if (true? tree_sitter_enabled) :tree-sitter typescript_engine)
        parsed (if (= engine :tree-sitter)
                 (let [{:keys [ok? result reason]} (parse-typescript-tree-sitter root-path path lines parser-opts)]
                   (if ok?
                     result
                     (-> (parse-typescript-regex path lines)
                         (update :diagnostics conj reason))))
                 (parse-typescript-regex path lines))]
    (add-tree-sitter-diag parsed tree_sitter_enabled "typescript")))

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
           "typescript" (parse-typescript root-path file-path lines parser-opts)
           (fallback-unit file-path lines language "unsupported_language")))
       (catch Exception _
         (let [lines (try (slurp-lines abs) (catch Exception _ []))]
           (fallback-unit file-path lines language "parse_exception")))))))
