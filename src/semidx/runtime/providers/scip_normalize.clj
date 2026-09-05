(ns semidx.runtime.providers.scip-normalize
  "Stage 3/4 of the Semantic Provider Authority Migration (plans/018, ADR-046):
  normalize SCIP index data into provider-neutral facts for
  `semidx.runtime.fact-arbitration`.

  Input is the plain data returned by `semidx.runtime.scip/read-index`; output is
  a collection of `{:key <CanonicalFactKey> :evidence [<FactEvidence>]}` facts
  plus a record of every SCIP symbol that was deliberately not turned into a
  fact.

  Three pieces do the work:

  - `parse-scip-symbol` decomposes a SCIP symbol string into its scheme, package
    fields, and typed descriptors (namespace `/`, type `#`, term `.`, method
    `().`, parameter `(x)`, ...), handling backtick-escaped names. It is
    language-neutral and needs no per-language branch.
  - a **language bridge** maps a parsed moniker onto semidx's own conceptual
    spelling. Bridges are plain data (`bridges`), one per language, because the
    two indexers disagree on where identity even lives: a TypeScript moniker
    reconstructs the source file path, while a Java moniker carries the package
    and the path must come from `Document.relative_path`.
  - `normalize-index` walks occurrences and applies the bridge.

  Scope of the units minted (owner-confirmed): only the definition kinds semidx
  models. Parameters, locals, and external/stdlib symbols are recorded as
  `:unmapped` with a reason and become no fact; whether SCIP should promote
  SCIP-only kinds is a later decision.

  Not here: source-identity anchoring and artifact-freshness checks (the caller
  injects `source-identity`; the provider adapter computes the real digest and
  the stale-artifact gate), call-hierarchy facts (no `call/*` relation type
  exists), the same-arity ambiguity guard (adapter policy, see
  `semidx.runtime.providers.scip-overloads`), and any wiring into the default
  extraction path."
  (:require [clojure.string :as str]
            [semidx.runtime.language-registry :as language-registry]
            [semidx.runtime.languages.typescript :as ts]))

(def default-provider-version "1")

;; --- SCIP symbol grammar ---------------------------------------------------
;;
;; <symbol>   ::= <scheme> ' ' <manager> ' ' <package-name> ' ' <version> ' ' <descriptor>+
;;              | 'local ' <local-id>
;; <name>     ::= <identifier> | '`' <escaped> '`'      ('``' is an escaped backtick)
;; descriptor suffixes: '/' namespace, '#' type, '.' term, '().' method,
;;                      '(x)' parameter, '[x]' type-parameter, ':' meta, '!' macro
;;
;; Verified against both scip-typescript@0.4.0 and scip-java 0.12.3. The Java
;; overload disambiguator (`handle(+1).`) parses as a method descriptor whose
;; `:disambiguator` is "+1"; it is evidence, never key material.

(defn- read-name
  "Read a descriptor name from the front of `chars`. Returns [name remaining]."
  [chars]
  (if (= \` (first chars))
    (loop [cs (rest chars) acc []]
      (cond
        (empty? cs)
        (throw (ex-info "unterminated backtick-escaped SCIP name"
                        {:error_code :scip_symbol_parse_error}))
        (= \` (first cs))
        (if (= \` (second cs))
          (recur (drop 2 cs) (conj acc \`))
          [(apply str acc) (rest cs)])
        :else (recur (rest cs) (conj acc (first cs)))))
    (let [[name-chars remaining]
          (split-with (fn [^Character c]
                        (or (Character/isLetterOrDigit c) (#{\_ \$ \+ \-} c)))
                      chars)]
      [(apply str name-chars) remaining])))

(defn- read-descriptor
  "Read one descriptor from the front of `chars`. Returns [descriptor remaining]."
  [chars]
  (case (first chars)
    \( (let [[name remaining] (read-name (rest chars))]
         (when-not (= \) (first remaining))
           (throw (ex-info "malformed SCIP parameter descriptor"
                           {:error_code :scip_symbol_parse_error})))
         [{:kind :parameter :name name} (rest remaining)])
    \[ (let [[name remaining] (read-name (rest chars))]
         (when-not (= \] (first remaining))
           (throw (ex-info "malformed SCIP type-parameter descriptor"
                           {:error_code :scip_symbol_parse_error})))
         [{:kind :type-parameter :name name} (rest remaining)])
    (let [[name remaining] (read-name chars)]
      (case (first remaining)
        \/ [{:kind :namespace :name name} (rest remaining)]
        \# [{:kind :type :name name} (rest remaining)]
        \: [{:kind :meta :name name} (rest remaining)]
        \! [{:kind :macro :name name} (rest remaining)]
        \( (let [[disamb after] (split-with #(not= \) %) (rest remaining))]
             (when-not (and (= \) (first after)) (= \. (second after)))
               (throw (ex-info "malformed SCIP method descriptor"
                               {:error_code :scip_symbol_parse_error})))
             [{:kind :method :name name :disambiguator (apply str disamb)}
              (drop 2 after)])
        \. [{:kind :term :name name} (rest remaining)]
        (throw (ex-info "unexpected SCIP descriptor suffix"
                        {:error_code :scip_symbol_parse_error
                         :at (apply str (take 12 remaining))}))))))

(defn parse-scip-symbol
  "Parse a SCIP symbol string. Returns
  `{:scheme :manager :package-name :version :descriptors [...]}` for a global
  symbol, `{:scheme \"local\" :local-id ...}` for a local one, or
  `{:error <keyword> ...}` when the string is empty, lacks the five
  space-separated fields, or carries a descriptor sequence the grammar reader
  rejects. This never throws: one malformed occurrence must become `:unmapped`,
  not abort a whole index."
  [sym]
  (cond
    (str/blank? sym)
    {:error :empty-symbol}

    (str/starts-with? sym "local ")
    {:scheme "local" :local-id (subs sym 6) :descriptors []}

    :else
    (let [parts (str/split sym #" " 5)]
      (if (< (count parts) 5)
        {:error :malformed-symbol :raw sym}
        (let [[scheme manager package-name version descriptor-str] parts]
          (try
            {:scheme scheme
             :manager manager
             :package-name package-name
             :version version
             :descriptors (loop [chars (seq descriptor-str) acc []]
                            (if (empty? chars)
                              acc
                              (let [[descriptor remaining] (read-descriptor chars)]
                                (recur remaining (conj acc descriptor)))))}
            (catch clojure.lang.ExceptionInfo e
              {:error :unparseable-descriptors
               :raw sym
               :message (ex-message e)})))))))

;; --- Shared bridge helpers -----------------------------------------------

(defn- local-project-symbol?
  "A global symbol defined by the project under index. Both indexers spell the
  local package as `.`; anything else (a JDK class, an npm dependency) is
  external and mints no unit."
  [parsed]
  (= "." (:package-name parsed)))

(defn- unmapped-reason
  "The `:unmapped` reason shared by every bridge for a symbol no bridge should
  see, or nil when the symbol is the bridge's to interpret."
  [parsed]
  (cond
    (:error parsed) (:error parsed)
    (= "local" (:scheme parsed)) :local-symbol
    (not (local-project-symbol? parsed)) :external-symbol))

;; --- EcmaScript bridge (TypeScript / JavaScript) -------------------------

(defn- source-file-namespace?
  "A namespace descriptor whose name looks like an ecmascript source file."
  [descriptor]
  (and (= :namespace (:kind descriptor))
       (some #(str/ends-with? (:name descriptor) %)
             language-registry/ecmascript-source-extensions)))

(defn- split-path-and-symbol
  "Split leading namespace descriptors that reconstruct the source file path from
  the descriptors that name the symbol. The file is the first namespace whose
  name has a source extension. Returns {:path <\"src/orders.ts\"> :descriptors [...]}
  or {:path nil} when no file namespace is present."
  [descriptors]
  (loop [remaining descriptors
         path-parts []]
    (cond
      (empty? remaining)
      {:path nil}

      (source-file-namespace? (first remaining))
      {:path (str/join "/" (map :name (conj path-parts (first remaining))))
       :descriptors (vec (rest remaining))}

      (= :namespace (:kind (first remaining)))
      (recur (rest remaining) (conj path-parts (first remaining)))

      :else
      {:path nil})))

(defn- ecmascript-descriptors->unit
  "Map the symbol-naming descriptors (everything after the file namespace) onto
  semidx's TypeScript spelling, given the file's `module` name. Returns a unit
  map or `{:unmapped <reason>}`.

  scip-typescript spells a `function foo()` declaration and a class method as a
  method descriptor (`foo().`), but every top-level `const`/`let` binding — arrow
  functions and function expressions included — as a term descriptor (`foo.`).
  So a top-level term cannot be filtered out (that would lose the arrow/function
  consts semidx does model) and cannot be proven callable from the symbol alone.
  It is emitted as a unit with `:kind \"term\"`: an exact-tier symbol semidx's
  regex lane only partly models. The arrow/function consts merge onto the
  regex-tier unit by canonical key; a plain value const becomes an exact-only
  `term` unit, which the Stage 6 authority review is the place to accept or gate."
  [descriptors module]
  (let [kinds (map :kind descriptors)]
    (cond
      (empty? descriptors)
      {:unmapped :module-symbol}

      (some #{:parameter :type-parameter :meta :macro} kinds)
      {:unmapped :non-unit-descriptor}

      (and (= 1 (count descriptors))
           (= :method (:kind (first descriptors))))
      {:owner module
       :symbol (str module "/" (:name (first descriptors)))
       :kind "function"}

      (and (= 1 (count descriptors))
           (= :term (:kind (first descriptors))))
      {:owner module
       :symbol (str module "/" (:name (first descriptors)))
       :kind "term"}

      (and (= 1 (count descriptors))
           (= :type (:kind (first descriptors))))
      {:unmapped :type-symbol}

      (and (= 2 (count descriptors))
           (= :type (:kind (first descriptors)))
           (= :method (:kind (second descriptors))))
      (if (= "<constructor>" (:name (second descriptors)))
        {:unmapped :constructor-symbol}
        (let [class-name (:name (first descriptors))]
          {:owner (str module "." class-name)
           :symbol (str module "." class-name "#" (:name (second descriptors)))
           :kind "function"}))

      (and (= 2 (count descriptors))
           (= :type (:kind (first descriptors)))
           (= :term (:kind (second descriptors))))
      {:unmapped :field-symbol}

      :else
      {:unmapped :unsupported-descriptor-shape})))

(defn- ecmascript-symbol->unit
  "Bridge a parsed scip-typescript symbol onto a semidx unit identity.

  Identity comes entirely from the moniker: the leading namespace descriptors up
  to and including the file reconstruct the path, `ts-module-name` turns that
  into the owner, and the trailing descriptors name the symbol. A cross-file
  reference therefore still keys on the defining file, not the referencing one."
  [parsed _context]
  (let [{:keys [path descriptors]} (split-path-and-symbol (:descriptors parsed))]
    (if-not path
      {:unmapped :no-source-file-namespace}
      (let [module (ts/ts-module-name path)
            mapped (ecmascript-descriptors->unit descriptors module)]
        (if (:unmapped mapped)
          mapped
          (assoc mapped :path path))))))

;; --- Java bridge ---------------------------------------------------------

(defn- identifier-char? [^Character c]
  (or (Character/isLetterOrDigit c) (= \_ c) (= \$ c)))

(defn- parameter-list-start
  "Index of the `(` that opens `declaration-name`'s parameter list in a Java
  signature text, or nil.

  Not simply the first `(`: scip-java prefixes the declaration with its
  annotations, and an annotation with arguments (`@Ann(x = 1)`) opens a paren
  group of its own. Reading that group instead of the parameter list produced a
  wrong arity in both directions — under-counting `f(String, int)` as 1 and
  over-counting `g(String)` as 2 — which would mint an exact fact on the wrong
  overload bucket.

  The name is matched at an identifier boundary and must be followed directly by
  `(`. The LAST such match wins, because everything that can repeat the name —
  annotations, a return type — precedes the declaration itself."
  [^String text declaration-name]
  (when-let [name (not-empty (str declaration-name))]
    (let [n (count name)
          len (count text)]
      (loop [from 0 found nil]
        (let [idx (str/index-of text name from)]
          (if (nil? idx)
            found
            (let [before-ok? (or (zero? idx)
                                 (not (identifier-char? (.charAt text (dec idx)))))
                  after (+ idx n)
                  after-ok? (and (< after len) (= \( (.charAt text after)))]
              (recur (inc idx)
                     (if (and before-ok? after-ok?) after found)))))))))

(defn- balanced-group
  "The text inside the paren group whose `(` sits at `open`, or nil when the
  group never closes."
  [^String text open]
  (loop [i (inc open) depth 0 acc []]
    (if (>= i (count text))
      nil
      (let [c (.charAt text i)]
        (cond
          (and (= \) c) (zero? depth)) (apply str acc)
          :else
          (recur (inc i)
                 (case c
                   (\< \( \[) (inc depth)
                   (\> \) \]) (dec depth)
                   depth)
                 (conj acc c)))))))

(defn- split-top-level
  "Split a parameter list on commas that are not nested inside `<>`, `()`, or
  `[]`, so `Map<String, List<Integer>> m` counts once."
  [params]
  (loop [cs (seq params) depth 0 current [] acc []]
    (cond
      (empty? cs) (conj acc (apply str current))
      (and (= \, (first cs)) (zero? depth))
      (recur (rest cs) depth [] (conj acc (apply str current)))
      :else
      (recur (rest cs)
             (case (first cs)
               (\< \( \[) (inc depth)
               (\> \) \]) (dec depth)
               depth)
             (conj current (first cs))
             acc))))

(defn signature-arity
  "Parameter count for `declaration-name` parsed from a Java
  `signature_documentation` text such as
  `\"public String handle(String order, int retries)\"`, or nil when the text is
  absent or its parameter list cannot be located unambiguously.

  scip-java puts no arity and no parameter types in the symbol — it disambiguates
  overloads with a source-order ordinal — so this text is the only place arity
  can be read from. `declaration-name` is required precisely because the text may
  be prefixed by annotations; see `parameter-list-start`. Returning nil is the
  intended outcome when the list cannot be found: the caller degrades to
  `:arity-unavailable` rather than minting an exact key from a guess."
  [signature-text declaration-name]
  (when-let [text (not-empty (str signature-text))]
    (when-let [open (parameter-list-start text declaration-name)]
      (when-let [params (balanced-group text open)]
        (if (str/blank? params)
          0
          (count (split-top-level params)))))))

(defn- java-declaration-name
  "The name that appears in a Java signature text for these descriptors: the
  method name, or the class name for a constructor — scip-java spells the symbol
  `<init>` while the signature text repeats the class name. Nil for anything that
  has no parameter list."
  [descriptors]
  (let [last-descriptor (last descriptors)]
    (when (= :method (:kind last-descriptor))
      (if (= "<init>" (:name last-descriptor))
        (:name (last (filter #(= :type (:kind %)) (butlast descriptors))))
        (:name last-descriptor)))))

(defn java-index-context
  "Per-index lookup the Java bridge needs, built in one pass over every
  document's `symbols`.

  A Java moniker carries the package, not the source file, so the defining path
  can only come from the document that declares the symbol. Arity likewise comes
  from that symbol's signature documentation, located by the declaration name so
  a leading annotation cannot be mistaken for the parameter list. Building this
  table up front is what lets a cross-file reference key on the *defining* file:
  the occurrence lives in one document, the identity is looked up from another."
  [scip-index]
  {:symbol->info
   (into {}
         (for [document (:documents scip-index)
               symbol-info (:symbols document)
               :let [signature (get-in symbol-info [:signature-documentation :text])
                     descriptors (:descriptors (parse-scip-symbol (:symbol symbol-info)))
                     declaration-name (java-declaration-name descriptors)]]
           [(:symbol symbol-info)
            {:path (:relative-path document)
             :kind (:kind symbol-info)
             :arity (signature-arity signature declaration-name)
             :signature_documentation signature}]))})

(defn- java-descriptors->unit
  "Map Java descriptors onto semidx's Java spelling.

  The regex and tree-sitter lanes spell a method `example.OrderService#handle`
  with owner `example.OrderService`, so the bridge reproduces exactly that and
  the tiers meet on one core key. scip-java spells a constructor `<init>`, while
  semidx repeats the class name, so constructors are translated rather than
  dropped.

  Anything whose owner cannot be derived without guessing is unmapped with a
  reason. Nested types are deliberately included there: joining an outer and
  inner type would invent an owner spelling that has not been verified against
  the heuristic lane, and an unverified owner silently splits identity instead of
  merging it."
  [descriptors]
  (let [kinds (map :kind descriptors)
        namespaces (take-while #(= :namespace (:kind %)) descriptors)
        rest-descriptors (drop (count namespaces) descriptors)
        package (str/join "." (map :name namespaces))
        types (filter #(= :type (:kind %)) rest-descriptors)]
    (cond
      (empty? rest-descriptors)
      {:unmapped :package-symbol}

      (some #{:parameter :type-parameter :meta :macro} kinds)
      {:unmapped :non-unit-descriptor}

      (> (count types) 1)
      {:unmapped :nested-type-symbol}

      (and (= 1 (count rest-descriptors))
           (= :type (:kind (first rest-descriptors))))
      {:unmapped :type-symbol}

      (and (= 2 (count rest-descriptors))
           (= :type (:kind (first rest-descriptors)))
           (= :method (:kind (second rest-descriptors))))
      (let [class-name (:name (first rest-descriptors))
            method-name (:name (second rest-descriptors))
            owner (if (str/blank? package) class-name (str package "." class-name))
            constructor? (= "<init>" method-name)]
        {:owner owner
         :symbol (str owner "#" (if constructor? class-name method-name))
         :kind (if constructor? "constructor" "method")
         :native_disambiguator (:disambiguator (second rest-descriptors))})

      (and (= 2 (count rest-descriptors))
           (= :type (:kind (first rest-descriptors)))
           (= :term (:kind (second rest-descriptors))))
      {:unmapped :field-symbol}

      :else
      {:unmapped :unsupported-descriptor-shape})))

(defn- java-symbol->unit
  "Bridge a parsed scip-java symbol onto a semidx unit identity.

  Unlike the ecmascript bridge, the path and the arity are looked up from the
  index context rather than read out of the moniker. A symbol the index does not
  declare (referenced but defined outside the compiled set) is unmapped, and so
  is a method whose arity cannot be read: the plan forbids guessing a canonical
  key, and an arity-less Java unit key would collide with every other overload."
  [parsed context]
  (let [mapped (java-descriptors->unit (:descriptors parsed))]
    (if (:unmapped mapped)
      mapped
      (let [info (get-in context [:symbol->info (:native_symbol parsed)])]
        (cond
          (nil? info) {:unmapped :symbol-not-declared-in-index}
          (nil? (:arity info)) {:unmapped :arity-unavailable}
          :else
          (assoc mapped
                 :path (:path info)
                 :native_signature_documentation (:signature_documentation info)
                 ;; Owner decision 2026-09-05: the Java exact tier commits arity
                 ;; only. scip-java carries no parameter types, and rebuilding
                 ;; them from occurrence layout would be inference. See
                 ;; fixtures/provider-authority/identity/java-overload-canonical-key.json.
                 :overload_identity {:arity (:arity info)
                                     :signature_precision "arity_only"
                                     :signature_key nil}))))))

;; --- Bridge registry ----------------------------------------------------

(def bridges
  "Language bridges as data, keyed by semidx language name.

  `:index-context` is called once per index and its result is handed to every
  `:symbol->unit` call, so a bridge that needs a cross-document lookup builds it
  once. `:default-provider-id` names the toolchain that produces the language's
  artifact."
  {"typescript" {:language "typescript"
                 :default-provider-id "scip-typescript"
                 :index-context (constantly {})
                 :symbol->unit ecmascript-symbol->unit}
   "java" {:language "java"
           :default-provider-id "scip-java"
           :index-context java-index-context
           :symbol->unit java-symbol->unit}})

(defn bridge-for
  "The bridge for `language`, or nil."
  [language]
  (get bridges language))

(defn scip-symbol->unit
  "Bridge a parsed SCIP symbol onto a semidx unit identity for `language`.
  Returns `{:owner :symbol :kind :path ...}` for a mappable unit, otherwise
  `{:unmapped <reason>}`.

  `context` is the bridge's index context (see `java-index-context`); pass `{}`
  for a bridge that needs none."
  ([language parsed] (scip-symbol->unit language parsed {}))
  ([language parsed context]
   (if-let [bridge (bridge-for language)]
     (if-let [reason (unmapped-reason parsed)]
       {:unmapped reason}
       ((:symbol->unit bridge) parsed context))
     {:unmapped :unsupported-language})))

;; --- Fact emission -------------------------------------------------------

(defn- occurrence->fact
  "One occurrence of a mapped symbol becomes one fact keyed on the symbol's
  canonical identity. `document` is where the occurrence physically is (which is
  the defining file for a definition, but another file for a cross-file
  reference); the symbol's own defining path lives in the key."
  [{:keys [provider-id provider-version source-identity language]} document occurrence unit]
  (let [definition? (contains? (:roles occurrence) :definition)]
    {:key {:fact_kind "unit"
           :language language
           :path (:path unit)
           :owner (:owner unit)
           :symbol (:symbol unit)
           :overload_identity (:overload_identity unit)
           :dispatch_identity nil}
     :evidence [{:provider_id provider-id
                 :provider_version provider-version
                 :authority "exact"
                 :operation (if definition? "definitions" "references")
                 ;; Freshness is the caller's to establish: this slice records
                 ;; the SCIP claim, the provider adapter runs the stale-artifact
                 ;; gate before the evidence is trusted at exact authority.
                 :freshness "exact"
                 :source_identity source-identity
                 :evidence_location {:path document
                                     :scip_range (:range occurrence)
                                     :scip_roles (vec (sort (map name (:roles occurrence))))}
                 :native_symbol (:symbol occurrence)
                 ;; Provider-native overload detail is evidence, never key
                 ;; material (owner decision 2026-09-05). scip-java's ordinal
                 ;; disambiguator and its signature text are the only way an
                 ;; overload can be told apart after the fact, so they must stay
                 ;; visible even though nothing may key on them.
                 :native_details (cond-> {}
                                   (:native_disambiguator unit)
                                   (assoc :disambiguator (:native_disambiguator unit))

                                   (:native_signature_documentation unit)
                                   (assoc :signature_documentation
                                          (:native_signature_documentation unit)))}]
     :value {:kind (:kind unit)}}))

(defn normalize-index
  "Normalize `semidx.runtime.scip/read-index` output into arbitration facts.

  `opts`:
  - `:language` — required; selects the bridge (`\"typescript\"`, `\"java\"`).
    Neither indexer populates `Document.language`, so the caller must say.
  - `:provider-id` / `:provider-version` — evidence provenance (defaults to the
    bridge's `:default-provider-id` and `\"1\"`).
  - `:source-identity` — either one identity map applied to every fact, or a
    function of a document's `relative-path` returning that document's identity
    map. ADR-046 requires an anchor (`content_digest` / `document_version` /
    `revision`) for the `exact` authority these facts carry; supplying an
    unanchored identity is a caller error that `fact-arbitration` will reject.

  Returns `{:facts [...] :unmapped [...]}`. `:unmapped` lists every SCIP symbol
  occurrence that was deliberately not turned into a fact, each with a `:reason`."
  [scip-index {:keys [language provider-id provider-version source-identity]
               :or {provider-version default-provider-version}}]
  (let [bridge (or (bridge-for language)
                   (throw (ex-info "No SCIP normalization bridge for language"
                                   {:error_code :unsupported_scip_language
                                    :language language
                                    :supported (vec (sort (keys bridges)))})))
        provider-id (or provider-id (:default-provider-id bridge))
        context ((:index-context bridge) scip-index)
        identity-fn (if (fn? source-identity)
                      source-identity
                      (constantly (or source-identity {})))]
    (reduce
     (fn [acc {:keys [relative-path occurrences]}]
       (let [fact-context {:provider-id provider-id
                           :provider-version provider-version
                           :language language
                           :source-identity (identity-fn relative-path)}]
         (reduce
          (fn [acc occurrence]
            (let [parsed (assoc (parse-scip-symbol (:symbol occurrence))
                                :native_symbol (:symbol occurrence))
                  unit (scip-symbol->unit language parsed context)]
              (if (:unmapped unit)
                (update acc :unmapped conj
                        {:native_symbol (:symbol occurrence)
                         :document relative-path
                         :range (:range occurrence)
                         :reason (:unmapped unit)})
                (update acc :facts conj
                        (occurrence->fact fact-context relative-path occurrence unit)))))
          acc
          occurrences)))
     {:facts [] :unmapped []}
     (:documents scip-index))))
