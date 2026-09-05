(ns semidx.runtime.languages.java
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.languages.shared :as shared]))

(def ^:private java-package-re #"^\s*package\s+([a-zA-Z0-9_\.]+)\s*;")
(def ^:private java-import-re #"^\s*import\s+(?:static\s+)?([a-zA-Z0-9_\.\*]+)\s*;")
(def ^:private java-class-re #"^\s*(?:public\s+)?(?:class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+extends\s+([A-Za-z0-9_\.]+))?")
(def ^:private java-method-re
  #"^\s*(?:(public|private|protected)\s+)?(?:(?:static|final|native|synchronized|abstract|default)\s+)*([A-Za-z0-9_<>,\[\]\.\?]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:\{|throws|;)")
(def ^:private java-constructor-re
  #"^\s*(?:(public|private|protected)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:\{|throws)")

(def ^:private java-call-stop
  #{"if" "for" "while" "switch" "catch" "return" "throw" "new" "super" "this" "synchronized"})

(defn- trim-signature [line]
  (shared/trim-signature line))

(defn- unit-end-lines [starts total-lines]
  (shared/unit-end-lines starts total-lines))

(defn- tail-token [token]
  (shared/tail-token token))

(defn- safe-line [lines n]
  (shared/safe-line lines n))

(defn- tree-sitter-available? [parser-opts]
  (shared/tree-sitter-available? parser-opts))

(defn- parser-grammar-path [parser-opts lang]
  (shared/parser-grammar-path parser-opts lang))

(defn- tree-sitter-cst [abs-path grammar-path parser-opts]
  (shared/tree-sitter-cst abs-path grammar-path nil parser-opts))

(defn- add-tree-sitter-diag [parsed enabled? language parser-opts]
  (shared/add-tree-sitter-diag parsed enabled? language parser-opts))

(defn- short-hash [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")
        bytes (.digest md (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (format "%02x%02x%02x%02x" (aget bytes 0) (aget bytes 1) (aget bytes 2) (aget bytes 3))))

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

            (and (= ch \:) (< (inc idx) n) (= (.charAt text (inc idx)) \:))
            (let [prefix (subs text 0 idx)
                  suffix (subs text (+ idx 2))
                  owner (some-> (re-find #"([A-Za-z_][A-Za-z0-9_\.]*)\s*$" prefix) second)
                  method-name (some-> (re-find #"^\s*([A-Za-z_][A-Za-z0-9_]*)" suffix) second)]
              (recur (+ idx 2)
                     in-string?
                     false
                     (if (or (str/blank? owner)
                             (str/blank? method-name)
                             (contains? java-call-stop (str/lower-case method-name)))
                       details
                       (conj details {:token (str owner "#" method-name)
                                      :arity nil
                                      :method_reference true}))))

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
                (and (seq token) (number? arity)) (update token (fnil conj #{}) arity)
                (and (seq tail) (not= tail token) (number? arity)) (update tail (fnil conj #{}) arity))))
          {}
          call-details))

(defn- java-call-tokens [call-details]
  (->> call-details
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

(defn- java-call-scan-body [lines start-line end-line]
  (let [segment (subvec lines (dec start-line) end-line)
        first-line (first segment)
        stripped-first (if-let [brace-idx (some-> first-line (str/index-of "{"))]
                         (subs first-line (inc brace-idx))
                         "")
        body-lines (cond-> [(or stripped-first "")]
                     (> (count segment) 1) (into (subvec segment 1)))]
    (str/join "\n" body-lines)))

(defn- java-resolve-class-name [pkg imports class-name]
  (let [nm (str/trim (str class-name))]
    (cond
      (str/blank? nm) nil
      (str/includes? nm ".") nm
      :else
      (or (some (fn [imp]
                  (let [candidate (str imp)]
                    (cond
                      (= candidate nm) candidate
                      (str/ends-with? candidate (str "." nm)) candidate
                      (str/ends-with? candidate ".*") (str (subs candidate 0 (- (count candidate) 2)) "." nm)
                      :else nil)))
                imports)
          (when (seq pkg) (str pkg "." nm))
          nm))))

(defn- java-class-spots [pkg imports lines]
  (->> (map-indexed vector lines)
       (keep (fn [[idx line]]
               (when-let [[_ class-name super-name] (re-find java-class-re line)]
                 {:line (inc idx)
                  :class class-name
                  :superclass_module (java-resolve-class-name pkg imports super-name)})))
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

(defn- java-method-unit-id [path symbol params]
  (let [norm (java-normalized-params params)
        arity (java-method-arity norm)
        suffix (if (str/blank? norm)
                 (str "$arity" arity)
                 (str "$arity" arity "$sig" (short-hash norm)))]
    {:unit_id (str path "::" symbol suffix)
     :method_arity arity
     :method_signature_key norm}))

;; --- Field-declaration relations (plans/017 Stage 1, ADR-045) ---
;;
;; Entity fields are modeled as relation target keys, never units. The producer
;; emits `structure/declares-field` relations only for entity-like classes and
;; only for class-body field declarations (method-body locals are excluded), so
;; the unit model and every existing graph consumer stay byte-identical. These
;; relations carry no target unit (a field has none), so they normalize to
;; `unresolved` and are ignored by resolved-only traversal.

(def ^:private java-field-re
  #"^\s*(?:(?:public|private|protected|static|final|transient|volatile)\s+)+[A-Za-z_][A-Za-z0-9_<>\[\],\.\?\s]*?\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=[^;]*)?;\s*$")

(def ^:private java-annotation-line-re
  #"^\s*(@[A-Za-z_][A-Za-z0-9_\.]*(?:\([^)]*\))?)\s*$")

(def ^:private java-leading-annotation-re
  #"^\s*(@[A-Za-z_][A-Za-z0-9_\.]*(?:\([^)]*\))?)\s*")

(def ^:private java-entity-path-re
  #"(?i)(?:^|/)(?:entity|entities|model|models)(?:/|$)")

(def ^:private java-entity-annotation-re
  #"(?i)^\s*@(?:Entity|Table|Embeddable|MappedSuperclass|Document)\b")

(defn- java-relation-provenance []
  {:producer "semidx.runtime.languages.java"
   :parser_mode "full"})

(defn- strip-leading-annotations
  "Split leading annotations off a declaration line, returning
  [annotations remainder] so the field regex can match the modifier-led tail."
  [line]
  (loop [s (str line)
         annos []]
    (if-let [m (re-find java-leading-annotation-re s)]
      (recur (subs s (count (first m))) (conj annos (second m)))
      [annos s])))

(defn- java-field-nullable
  "Best-effort nullability from annotation evidence. nil means unknown; this is
  evidence, not identity."
  [annotations]
  (let [text (str/join " " annotations)]
    (cond
      (re-find #"(?i)nullable\s*=\s*false" text) false
      (re-find #"(?i)nullable\s*=\s*true" text) true
      (re-find #"(?i)@(?:Id|NotNull|NonNull)\b" text) false
      :else nil)))

(defn- java-entity-class?
  "Conservative entity/model detection: an entity annotation on the class, an
  entity/model path segment, or an entity/model class or module suffix."
  [path module cls lines class-line]
  (let [lower-path (str/lower-case (str path))
        lower-cls (str/lower-case (str cls))
        lower-mod (str/lower-case (str module))
        start (max 0 (- class-line 6))
        stop (max 0 (dec class-line))
        preceding (->> lines (drop start) (take (max 0 (- stop start))))
        annotated? (boolean (some #(re-find java-entity-annotation-re (str %)) preceding))]
    (boolean
     (or annotated?
         (re-find java-entity-path-re lower-path)
         (str/ends-with? lower-cls "entity")
         (str/ends-with? lower-cls "model")
         (str/ends-with? lower-mod "entity")
         (str/ends-with? lower-mod "model")))))

(defn- java-class-at [class-spots line-no]
  (->> class-spots
       (filter #(<= (:line %) line-no))
       last))

(defn- java-declares-field-relation
  [{:keys [path module field line-number annotations nullable]}]
  {:source_unit_id (str path "::" module)
   :target_key (str module "#" field)
   :relation_type "structure/declares-field"
   :resolution_status "unresolved"
   :evidence_quality "medium"
   :provenance (java-relation-provenance)
   :evidence_location (cond-> {:start_line line-number}
                        (seq annotations) (assoc :annotations (vec annotations))
                        (some? nullable) (assoc :nullable nullable))})

(defn- java-field-relations
  "Emit `structure/declares-field` relations for class-body fields of entity-like
  classes. `method-spans` is a seq of [start-line end-line] pairs whose lines are
  excluded (method-body locals are not fields). Deterministic, engine-agnostic."
  [path pkg lines method-spans class-spots]
  (let [inside-method? (fn [line-no]
                         (boolean (some (fn [[s e]] (and s e (<= s line-no e)))
                                        method-spans)))
        entity? (memoize
                 (fn [spot]
                   (when spot
                     (let [cls (:class spot)
                           module (if pkg (str pkg "." cls) cls)]
                       (java-entity-class? path module cls lines (:line spot))))))]
    (loop [idx 0
           pending []
           acc []]
      (if (>= idx (count lines))
        (vec acc)
        (let [line (nth lines idx)
              line-no (inc idx)
              trimmed (str/trim (str line))]
          (cond
            (str/blank? trimmed)
            (recur (inc idx) [] acc)

            (re-find java-annotation-line-re line)
            (recur (inc idx)
                   (conj pending (second (re-find java-annotation-line-re line)))
                   acc)

            :else
            (let [spot (java-class-at class-spots line-no)
                  [inline-annos remainder] (strip-leading-annotations line)
                  field-match (re-find java-field-re remainder)]
              (if (and spot
                       (entity? spot)
                       (not (inside-method? line-no))
                       field-match)
                (let [field-name (second field-match)
                      cls (:class spot)
                      module (if pkg (str pkg "." cls) cls)
                      annotations (vec (concat pending inline-annos))]
                  (recur (inc idx)
                         []
                         (conj acc
                               (java-declares-field-relation
                                {:path path
                                 :module module
                                 :field field-name
                                 :line-number line-no
                                 :annotations annotations
                                 :nullable (java-field-nullable annotations)}))))
                (recur (inc idx) [] acc)))))))))

;; --- Field-write relations (plans/017 Stage 3, ADR-045) ---
;;
;; `dataflow/writes-field` attributes which fields a state-transition method
;; writes, via setter calls (`x.setStatus(..)`) or, inside entity classes, direct
;; `this.field = ...` assignments. Target keys use a `field:` sentinel so field
;; names never collide with call tokens and always normalize to `unresolved`,
;; keeping resolved-only projections and legacy outputs unaffected.

(def ^:private java-setter-call-re #"\.set([A-Z][A-Za-z0-9_]*)\s*\(")

(def ^:private java-this-assign-re
  #"(?:^|[^.\w])this\.([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)")

(def ^:private java-writer-name-re
  #"(?i)^(?:save|update|disconnect|clear|reset|set|connect|revoke|invalidate)")

(defn- java-decapitalize [s]
  (if (seq s)
    (str (str/lower-case (subs s 0 1)) (subs s 1))
    s))

(defn- java-method-field-writes
  "Field names a method body writes: setter-call fields (`x.setStatus(..)` ->
  `status`) plus, for entity classes, direct `this.field = ...` assignments.
  Returns a deterministic sorted set."
  [body entity?]
  (into (sorted-set)
        (concat
         (->> (re-seq java-setter-call-re (str body))
              (map (comp java-decapitalize second)))
         (when entity?
           (->> (re-seq java-this-assign-re (str body))
                (map second))))))

(defn- java-writes-field-relation [unit-id field start-line]
  {:source_unit_id unit-id
   :target_key (str "field:" field)
   :relation_type "dataflow/writes-field"
   :resolution_status "unresolved"
   :evidence_quality "low"
   :provenance (java-relation-provenance)
   :evidence_location {:start_line start-line}})

(defn- java-writes-field-relations
  "Emit `dataflow/writes-field` relations for state-transition methods (writer-named
  methods or methods in entity-like classes). `method-units` carry :start-line
  :end-line :method :class :params; source ids match the emitted method units."
  [path pkg lines method-units class-spots]
  (->> method-units
       (mapcat (fn [{:keys [start-line end-line method class params]}]
                 (let [cls (or class "UnknownClass")
                       module (if pkg (str pkg "." cls) cls)
                       symbol (str (when pkg (str pkg ".")) cls "#" method)
                       unit-id (:unit_id (java-method-unit-id path symbol params))
                       spot (java-class-at class-spots start-line)
                       entity? (boolean (and spot
                                             (java-entity-class? path module cls lines (:line spot))))
                       writer? (boolean (re-find java-writer-name-re (str method)))]
                   (if (or entity? writer?)
                     (let [body (java-call-scan-body lines start-line end-line)]
                       (map #(java-writes-field-relation unit-id % start-line)
                            (java-method-field-writes body entity?)))
                     []))))
       vec))

(defn- parse-java-regex [path lines]
  (let [line-count (count lines)
        pkg (some (fn [line] (some-> (re-find java-package-re line) second)) lines)
        imports (->> lines
                     (keep (fn [line] (some-> (re-find java-import-re line) second)))
                     distinct
                     vec)
        class-spots (java-class-spots pkg imports lines)
        methods (->> (map-indexed vector lines)
                     (keep (fn [[idx line]]
                             (let [class-name (->> class-spots
                                                   (filter #(<= (:line %) (inc idx)))
                                                   last
                                                   :class)
                                   super-module (->> class-spots
                                                     (filter #(<= (:line %) (inc idx)))
                                                     last
                                                     :superclass_module)
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
                                      :superclass_module super-module
                                      :signature (trim-signature line)
                                      :kind "constructor"}))

                                 method-match
                                 (let [[_ _visibility return-type m params] method-match]
                                   (when-not (contains? java-call-stop (str/lower-case (str return-type)))
                                     {:start-line (inc idx)
                                      :method m
                                      :params params
                                      :class class-name
                                      :superclass_module super-module
                                      :signature (trim-signature line)
                                      :kind nil}))))))
                     vec)
        starts (mapv :start-line methods)
        ends (unit-end-lines starts line-count)
        method-spans (map (fn [m e] [(:start-line m) e]) methods ends)
        method-units (map (fn [m e] (assoc m :end-line e)) methods ends)
        relations (into (java-field-relations path pkg lines method-spans class-spots)
                        (java-writes-field-relations path pkg lines method-units class-spots))
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
                             :class_name cls
                             :superclass_module (:superclass_module m)
                             :start_line start-line
                             :end_line end-line
                             :signature (:signature m)
                             :summary (str "method " symbol)
                             :docstring_excerpt nil
                             :imports imports
                             :method_arity method_arity
                             :method_signature_key method_signature_key
                             :calls (java-call-tokens call-details)
                             :call_arity_by_token (java-call-arity-index call-details)
                             :parser_mode "full"})))
                   vec)]
    {:language "java"
     :module pkg
     :imports imports
     :units units
     :relations relations
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
                     vec)
        class-spots (java-class-spots pkg imports src-lines)]
    (cond
      (not (tree-sitter-available? parser-opts))
      {:ok? false
       :reason {:code "tree_sitter_unavailable"
                :summary "tree-sitter CLI is unavailable for java tree-sitter parser."}}

      (str/blank? (str grammar-path))
      {:ok? false
       :reason {:code "tree_sitter_missing_grammar"
                :summary "No tree-sitter Java grammar path configured."}}

      :else
      (let [{:keys [ok? lines err]} (tree-sitter-cst abs grammar-path parser-opts)
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
                                          class-spot (->> class-spots
                                                          (filter #(and (= cls (:class %))
                                                                        (<= (:line %) (inc (:start-row m)))))
                                                          last)
                                          constructor? (= "constructor_declaration" (:node-type m))
                                          method-name (if constructor?
                                                        (or cls "UnknownClass")
                                                        (or (node-name-inside ts-lines m "name:") "unknownMethod"))
                                          body (java-call-scan-body src-lines (inc (:start-row m)) (inc (:end-row m)))
                                          call-details (java-call-details body)]
                                      {:start-line (inc (:start-row m))
                                       :end-line (inc (:end-row m))
                                       :method method-name
                                       :kind (when constructor? "constructor")
                                       :class (or cls "UnknownClass")
                                       :params (java-param-fragment-from-source src-lines (inc (:start-row m)))
                                       :superclass_module (:superclass_module class-spot)
                                       :call_details call-details})))
                             vec)
                method-spans (map (fn [m] [(:start-line m) (:end-line m)]) methods)
                relations (into (java-field-relations path pkg src-lines method-spans class-spots)
                                (java-writes-field-relations path pkg src-lines methods class-spots))
                units (->> methods
                           (map (fn [{:keys [start-line end-line method kind class call_details params superclass_module]}]
                                  (let [symbol (str (when pkg (str pkg ".")) class "#" method)
                                        {:keys [unit_id method_arity method_signature_key]}
                                        (java-method-unit-id path symbol params)]
                                    {:unit_id unit_id
                                     :kind (java-kind path method kind)
                                     :symbol symbol
                                     :path path
                                     :module (if pkg (str pkg "." class) class)
                                     :class_name class
                                     :superclass_module superclass_module
                                     :start_line start-line
                                     :end_line end-line
                                     :signature (safe-line src-lines start-line)
                                     :summary (str "method " symbol)
                                     :docstring_excerpt nil
                                     :imports imports
                                     :method_arity method_arity
                                     :method_signature_key method_signature_key
                                     :calls (java-call-tokens call_details)
                                     :call_arity_by_token (java-call-arity-index call_details)
                                     :parser_mode "full"})))
                           vec)]
            (if (seq units)
              {:ok? true
               :result {:language "java"
                        :module pkg
                        :imports imports
                        :units units
                        :relations relations
                        :diagnostics [{:code "tree_sitter_active"
                                       :summary "Java analyzed using tree-sitter CST extraction."}]
                        :parser_mode "full"}}
              {:ok? false
               :reason {:code "tree_sitter_no_units"
                        :summary "tree-sitter did not extract Java units."}})))))))

(defn parse-file [root-path path lines {:keys [java_engine tree_sitter_enabled]
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
    (add-tree-sitter-diag parsed tree_sitter_enabled "java" parser-opts)))
