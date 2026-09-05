(ns semidx.runtime.languages.clojure
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [semidx.runtime.languages.shared :as shared]))

(def ^:private clj-def-re
  #"^\s*\((defn-|defn|defmacro|defmulti|defmethod|defprotocol|def|deftest)\s+([^\s\[\]\)]+)")

(def ^:private clj-call-re
  #"\(([a-zA-Z][a-zA-Z0-9\-\.!/<>\?]*)")

(def ^:private clj-require-re
  #"\[([a-zA-Z0-9\._\-]+)(?:\s+:as\s+[a-zA-Z0-9_\-]+)?\]")

(def ^:private clj-require-alias-re
  #"\[([a-zA-Z0-9\._\-]+)\s+:as\s+([a-zA-Z0-9_\-]+)\]")

(def ^:private clj-call-stop
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "deftest" "ns"
    "let" "if" "when" "when-not" "cond" "case" "loop" "recur" "do" "fn"
    "for" "doseq" "->" "->>" "some->" "some->>" "as->" "try" "catch" "finally"
    "and" "or" "not" "comment"})

(defn- trim-signature [line]
  (shared/trim-signature line))

(defn- unit-end-lines [starts total-lines]
  (shared/unit-end-lines starts total-lines))

(defn- tail-token [token]
  (shared/tail-token token))

(defn- tree-sitter-available? [parser-opts]
  (shared/tree-sitter-available? parser-opts))

(defn- parser-grammar-path [parser-opts lang]
  (shared/parser-grammar-path parser-opts lang))

(defn- tree-sitter-cst [abs-path grammar-path parser-opts]
  (shared/tree-sitter-cst abs-path grammar-path nil parser-opts))

(defn- add-tree-sitter-diag [parsed enabled? language parser-opts]
  (shared/add-tree-sitter-diag parsed enabled? language parser-opts))

(defn- short-hash [s]
  (subs (format "%08x" (bit-and 0xffffffff (hash (str s)))) 0 8))

(defn- clj-scan-line [{:keys [depth in-string] :as _state} line]
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

(defn- clj-form-operator [form]
  (when (or (seq? form) (vector? form))
    (some-> form first str)))

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

(defn- clj-call-target-key [node alias-map]
  (when (seq? node)
    (let [op (first node)]
      (when (and (symbol? op)
                 (not (contains? clj-call-stop (str op))))
        (last (expand-clj-call-token (str op) alias-map))))))

(defn- defn-body-forms [form]
  (let [args (rest form)
        after-name (rest args)
        after-doc (cond-> after-name
                    (string? (first after-name)) rest
                    (map? (first after-name)) rest)]
    (cond
      (vector? (first after-doc))
      [{:params (first after-doc)
        :body (vec (rest after-doc))}]

      :else
      (->> after-doc
           (keep (fn [arity-form]
                   (when (and (seq? arity-form)
                              (vector? (first arity-form)))
                     {:params (first arity-form)
                      :body (vec (rest arity-form))})))
           vec))))

(defn- defmethod-body-forms [form]
  (let [after-dispatch (drop 3 form)
        after-doc (cond-> after-dispatch
                    (string? (first after-dispatch)) rest
                    (map? (first after-dispatch)) rest)]
    (when (vector? (first after-doc))
      [{:params (first after-doc)
        :body (vec (rest after-doc))}])))

(defn- relation-provenance [parser-mode]
  {:producer "semidx.runtime.languages.clojure"
   :parser_mode parser-mode})

(defn- clj-dataflow-relation
  [{:keys [unit-id relation-type target-key parser-mode evidence-location] :as opts}]
  (cond-> {:source_unit_id unit-id
           :target_key target-key
           :relation_type relation-type
           :resolution_status "unresolved"
           :evidence_quality "medium"
           :provenance (relation-provenance parser-mode)}
    (:local_name opts) (assoc :local_name (:local_name opts))
    (:arg_index opts) (assoc :arg_index (:arg_index opts))
    (seq evidence-location) (assoc :evidence_location evidence-location)))

(defn- clj-dataflow-relations-from-body [unit-id parser-mode alias-map params body-forms start-line]
  (let [param-names (set (clj-binding-symbols params))]
    (letfn [(return-target-key [node]
              (when (seq? node)
                (let [op (some-> (first node) str)
                      args (rest node)]
                  (case op
                    ("let" "loop") (return-target-key (last args))
                    "do" (return-target-key (last args))
                    (clj-call-target-key node alias-map)))))
            (walk [node locals]
              (let [locals* (set locals)]
                (cond
                  (seq? node)
                  (let [op (some-> (first node) str)
                        args (rest node)
                        target-key (clj-call-target-key node alias-map)
                        call-relations (when target-key
                                         (->> args
                                              (map-indexed vector)
                                              (keep (fn [[idx arg]]
                                                      (when (and (symbol? arg)
                                                                 (or (contains? param-names (str arg))
                                                                     (contains? locals* (str arg))))
                                                        (clj-dataflow-relation
                                                         {:unit-id unit-id
                                                          :relation-type "dataflow/passes-argument"
                                                          :target-key target-key
                                                          :parser-mode parser-mode
                                                          :arg_index idx
                                                          :local_name (str arg)
                                                          :evidence-location {:start_line start-line}}))))))]
                    (case op
                      ("quote" "var")
                      []

                      ("let" "loop" "binding")
                      (let [binding-vec (first args)
                            pairs (partition 2 binding-vec)
                            binding-relations (->> pairs
                                                   (keep (fn [[binding expr]]
                                                           (when-let [target-key (clj-call-target-key expr alias-map)]
                                                             (clj-dataflow-relation
                                                              {:unit-id unit-id
                                                               :relation-type "dataflow/local-binding-call-result"
                                                               :target-key target-key
                                                               :parser-mode parser-mode
                                                               :local_name (some-> binding clj-binding-symbols first str)
                                                               :evidence-location {:start_line start-line}})))))
                            binding-calls (mapcat (fn [[_ expr]] (walk expr locals*)) pairs)
                            locals** (into locals* (mapcat (comp clj-binding-symbols first) pairs))]
                        (concat binding-relations binding-calls (mapcat #(walk % locals**) (rest args))))

                      "fn"
                      []

                      (concat call-relations (mapcat #(walk % locals*) args))))

                  (vector? node)
                  (mapcat #(walk % locals*) node)

                  (map? node)
                  (mapcat #(walk % locals*) (concat (keys node) (vals node)))

                  (set? node)
                  (mapcat #(walk % locals*) node)

                  :else [])))]
      (let [return-form (last body-forms)
            return-relation (when-let [target-key (return-target-key return-form)]
                              [(clj-dataflow-relation
                                {:unit-id unit-id
                                 :relation-type "dataflow/returns-call-result"
                                 :target-key target-key
                                 :parser-mode parser-mode
                                 :evidence-location {:start_line start-line}})])]
        (->> (concat return-relation
                     (mapcat #(walk % param-names) body-forms))
             (remove #(= (:target_key %) (:source_unit_id %)))
             distinct
             vec)))))

(defn- clj-dataflow-relations
  [{:keys [operator parser-mode alias-map start-line] :as ctx} unit-id form]
  (let [bodies (case operator
                 ("defn" "defn-" "defmacro" "deftest") (defn-body-forms form)
                 "defmethod" (defmethod-body-forms form)
                 [])]
    (->> bodies
         (mapcat (fn [{:keys [params body]}]
                   (clj-dataflow-relations-from-body unit-id parser-mode alias-map params body start-line)))
         distinct
         vec)))

(defn- clj-unit-from-form
  [{:keys [path ns-name raw-symbol operator kind start-line end-line signature imports calls parser-mode alias-map helper-generated-calls] :as ctx}
   form-text]
  (let [form (clj-read-form form-text)
        operator* (or operator (clj-form-operator form))
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
                       (str " dispatch " dispatch-value)))
        relations (clj-dataflow-relations (assoc ctx
                                                 :operator operator*
                                                 :kind kind*
                                                 :parser-mode parser-mode)
                                          unit-id
                                          form)]
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
      (seq relations)
      (assoc :relations relations)
      dispatch-value
      (assoc :dispatch_value dispatch-value
             :multimethod_symbol symbol))))

(defn- clj-units-from-form
  [ctx form-text]
  (if (= "defprotocol" (:operator ctx))
    (clj-protocol-method-units ctx form-text)
    [(clj-unit-from-form ctx form-text)]))

(defn- detach-unit-relations [units]
  {:units (mapv #(dissoc % :relations) units)
   :relations (->> units
                   (mapcat :relations)
                   distinct
                   vec)})

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
        raw-units (->> form-records
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
                       vec)
        {:keys [units relations]} (detach-unit-relations raw-units)]
    {:language "clojure"
     :module ns-name
     :imports imports
     :test_target_modules test-target-modules
     :units units
     :relations relations
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
  (shared/safe-line lines n))

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
        ;; An absent clj-kondo makes `sh` throw IOException rather than return a
        ;; non-zero exit, and an uncaught throw here aborts the whole index build
        ;; instead of degrading. Every other unavailable-toolchain path in this
        ;; runtime reports and falls back, so this one must too.
        {:keys [exit out err unavailable]}
        (try
          (sh/sh "clj-kondo" "--lint" abs "--cache" "false" "--config" config "--fail-level" "error")
          (catch Exception e
            {:exit -1 :out "" :err (.getMessage e) :unavailable true}))
        parsed (try (edn/read-string out) (catch Exception _ nil))
        analysis (:analysis parsed)
        fallback (parse-clojure-regex path lines)
        var-defs (->> (:var-definitions analysis) (filter #(same-file? abs (:filename %))) vec)
        ns-usages (->> (:namespace-usages analysis) (filter #(same-file? abs (:filename %))) vec)
        var-usages (->> (:var-usages analysis) (filter #(same-file? abs (:filename %))) vec)
        imports (->> ns-usages (keep :to) (map str) distinct vec)
        alias-map (clj-require-alias-map lines)
        test-target-modules (clj-test-target-modules (some-> var-defs first :ns str) imports path)
        primary-records
        (->> var-defs
             (map (fn [d]
                    (let [ns-name (str (:ns d))
                          nm (str (:name d))
                          start (max 1 (int (or (:name-row d) (:row d) 1)))
                          end (max start (int (or (:end-row d) start)))
                          form-text (str/join "\n" (subvec lines (dec start) end))
                          operator (some-> form-text clj-read-form clj-form-operator)]
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
                                                                 alias-map)
        primary-raw-units
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
                                                                           (clj-dispatch-call-tokens form-text ns-name alias-map (set (map #(when (= "defmulti" (:operator %))
                                                                                                                                              (clj-qualified-symbol ns-name (:raw-symbol %)))
                                                                                                                                           primary-records))))))
                                             :parser-mode "full"
                                             :alias-map alias-map
                                             :helper-generated-calls helper-generated-calls}
                                            form-text)))
             vec)
        {:keys [units primary-relations] :or {primary-relations []}}
        (let [{:keys [units relations]} (detach-unit-relations primary-raw-units)]
          {:units units :primary-relations relations})
        existing-unit-ids (set (map :unit_id units))
        supplemental-units (->> (:units fallback)
                                (remove #(contains? existing-unit-ids (:unit_id %)))
                                (map #(assoc % :parser_mode "full"))
                                vec)
        supplemental-relations (->> (:relations fallback)
                                    (remove #(contains? existing-unit-ids (:source_unit_id %)))
                                    (map #(assoc-in % [:provenance :parser_mode] "full"))
                                    vec)
        units (vec (concat units supplemental-units))
        relations (vec (distinct (concat primary-relations supplemental-relations)))
        findings
        (->> (:findings parsed)
             (filter #(and (same-file? abs (:filename %))
                           (#{:error :warning} (:level %))))
             (mapv (fn [f]
                     {:code (str "kondo_" (name (:type f)))
                      :summary (:message f)})))]
    (cond
      ;; Checked before `(seq units)`: when clj-kondo produces nothing, the
      ;; regex fallback's units are still folded in as `supplemental-units` and
      ;; stamped "full", so an unavailable-toolchain run would otherwise report
      ;; lexical output under the full parser mode. ADR-046 forbids exactly that.
      unavailable
      (-> fallback
          (update :diagnostics into
                  [{:code "kondo_unavailable"
                    :summary (str "clj-kondo is not available; using the fallback "
                                  "Clojure parser. Install clj-kondo to restore "
                                  "full Clojure extraction.")}
                   {:code "kondo_stderr"
                    :summary (subs (str err) 0 (min 220 (count (str err))))}])
          (assoc :parser_mode "fallback"))

      (seq units)
      {:language "clojure"
       :module (some-> units first :module)
       :imports imports
       :test_target_modules test-target-modules
       :units units
       :relations relations
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
      (not (tree-sitter-available? parser-opts))
      {:ok? false
       :reason {:code "tree_sitter_unavailable"
                :summary "tree-sitter CLI is unavailable for clojure tree-sitter parser."}}

      (str/blank? (str grammar-path))
      {:ok? false
       :reason {:code "tree_sitter_missing_grammar"
                :summary "No tree-sitter Clojure grammar path configured."}}

      :else
      (let [{:keys [ok? lines err]} (tree-sitter-cst abs grammar-path parser-opts)
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
                raw-units (->> form-records
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
                               vec)
                {:keys [units relations]} (detach-unit-relations raw-units)]
            (if (seq units)
              {:ok? true
               :result {:language "clojure"
                        :module ns-name
                        :imports imports
                        :test_target_modules test-target-modules
                        :units units
                        :relations relations
                        :diagnostics [{:code "tree_sitter_active"
                                       :summary "Clojure analyzed using tree-sitter CST extraction."}]
                        :parser_mode "full"}}
              {:ok? false
               :reason {:code "tree_sitter_no_units"
                        :summary "tree-sitter did not extract Clojure units."}})))))))

(defn parse-file [root-path path lines {:keys [clojure_engine tree_sitter_enabled]
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
    (add-tree-sitter-diag parsed tree_sitter_enabled "clojure" parser-opts)))
