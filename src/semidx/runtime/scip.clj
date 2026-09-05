(ns semidx.runtime.scip
  "Reader for SCIP (SCIP Code Intelligence Protocol) index artifacts.

  Translates a `.scip` protobuf payload into plain, serializable Clojure data.
  This is a transport-level reader only: it performs no normalization into the
  semidx Semantic IR, typed relations, or `CanonicalFactKey`. Mapping SCIP
  symbols and occurrences onto canonical facts belongs to the SCIP provider
  adapter (plans/018 Stage 3), which consumes the data returned here.

  Schema: `proto/scip/scip.proto`, vendored verbatim from `sourcegraph/scip`
  `v0.5.2` (see `proto/scip/PROVENANCE.md`). Generated Java stubs live under
  `src-generated/java/scip/Scip.java` and are produced by the repo-managed
  protobuf toolchain (ADR-042). As with `semidx.runtime.grpc-proto`, the
  generated classes must already be compiled onto the classpath before this
  namespace loads; the `:test` alias and `semidx.runtime.grpc-prep` handle that
  for ordinary test runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]
           [scip Scip$Diagnostic Scip$Document Scip$Index Scip$Metadata
            Scip$Occurrence Scip$Relationship Scip$SymbolInformation
            Scip$ToolInfo]))

(defn- enum->keyword
  "Render a protobuf enum constant as a lower-kebab keyword, e.g.
  `UnspecifiedSyntaxKind` -> :unspecified-syntax-kind, `UTF8` -> :utf8."
  [^Enum e]
  (-> (.name e)
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      str/lower-case
      keyword))

;; SymbolRole bit positions (scip.proto `enum SymbolRole`). The field is a
;; bitset, so a single occurrence can carry several roles.
(def ^:private symbol-role-bits
  [[0 :definition]
   [1 :import]
   [2 :write-access]
   [3 :read-access]
   [4 :generated]
   [5 :test]
   [6 :forward-definition]])

(defn decode-symbol-roles
  "Decode a SCIP `symbol_roles` bitset into a set of role keywords."
  [mask]
  (into #{}
        (keep (fn [[bit kw]] (when (bit-test mask bit) kw)))
        symbol-role-bits))

(defn- int-range
  "SCIP packs source ranges as `repeated int32` — `[startLine startChar endChar]`
  (same line) or `[startLine startChar endLine endChar]`. Return the raw vector;
  an empty list stays an empty vector."
  [^Iterable ints]
  (mapv int ints))

;; `read-symbol-information` and `read-document` are mutually recursive:
;; a `SymbolInformation` may carry a `signature_documentation` `Document`, and a
;; `Document` carries `SymbolInformation`s. Recursion terminates because a
;; signature `Document` has no further nested signatures in practice and the
;; protobuf payload is finite.
(declare read-document)

(defn- read-relationship [^Scip$Relationship r]
  {:symbol (.getSymbol r)
   :is-reference (.getIsReference r)
   :is-implementation (.getIsImplementation r)
   :is-type-definition (.getIsTypeDefinition r)
   :is-definition (.getIsDefinition r)})

(defn- read-symbol-information [^Scip$SymbolInformation s]
  {:symbol (.getSymbol s)
   :kind (enum->keyword (.getKind s))
   :display-name (.getDisplayName s)
   :documentation (vec (.getDocumentationList s))
   :enclosing-symbol (.getEnclosingSymbol s)
   :signature-documentation (when (.hasSignatureDocumentation s)
                              (read-document (.getSignatureDocumentation s)))
   :relationships (mapv read-relationship (.getRelationshipsList s))})

(defn- read-diagnostic [^Scip$Diagnostic d]
  {:severity (enum->keyword (.getSeverity d))
   :code (.getCode d)
   :message (.getMessage d)
   :source (.getSource d)
   :tags (mapv enum->keyword (.getTagsList d))})

(defn- read-occurrence [^Scip$Occurrence o]
  (let [roles (.getSymbolRoles o)]
    {:range (int-range (.getRangeList o))
     :symbol (.getSymbol o)
     :symbol-roles roles
     :roles (decode-symbol-roles roles)
     :override-documentation (vec (.getOverrideDocumentationList o))
     :syntax-kind (enum->keyword (.getSyntaxKind o))
     :diagnostics (mapv read-diagnostic (.getDiagnosticsList o))
     :enclosing-range (int-range (.getEnclosingRangeList o))}))

(defn- read-document [^Scip$Document d]
  {:relative-path (.getRelativePath d)
   :language (.getLanguage d)
   :position-encoding (enum->keyword (.getPositionEncoding d))
   :text (.getText d)
   :symbols (mapv read-symbol-information (.getSymbolsList d))
   :occurrences (mapv read-occurrence (.getOccurrencesList d))})

(defn- read-tool-info [^Scip$ToolInfo t]
  {:name (.getName t)
   :version (.getVersion t)
   :arguments (vec (.getArgumentsList t))})

(defn- read-metadata [^Scip$Metadata m]
  {:version (enum->keyword (.getVersion m))
   :tool-info (when (.hasToolInfo m) (read-tool-info (.getToolInfo m)))
   :project-root (.getProjectRoot m)
   :text-document-encoding (enum->keyword (.getTextDocumentEncoding m))})

(defn- index->map [^Scip$Index index]
  {:metadata (when (.hasMetadata index) (read-metadata (.getMetadata index)))
   :documents (mapv read-document (.getDocumentsList index))
   :external-symbols (mapv read-symbol-information (.getExternalSymbolsList index))})

(defn parse-index-bytes
  "Parse an in-memory SCIP payload into Clojure data. See `read-index` for the
  returned shape."
  [^bytes payload]
  (index->map (Scip$Index/parseFrom payload)))

(defn parse-index-stream
  "Parse a SCIP payload from an already-open `InputStream`. The caller owns the
  stream and is responsible for closing it."
  [^InputStream in]
  (index->map (Scip$Index/parseFrom in)))

(defn read-index
  "Read a `.scip` artifact from `source` (anything `clojure.java.io/input-stream`
  accepts — a path string, `File`, or `URL`) and return:

  ```
  {:metadata {:version :unspecified-protocol-version
              :tool-info {:name \"scip-typescript\" :version \"0.4.0\" :arguments []}
              :project-root \"file://...\"        ; raw; caller decides whether to keep it
              :text-document-encoding :utf8}
   :documents [{:relative-path \"src/orders.ts\"
                :language \"\"
                :position-encoding :unspecified-position-encoding
                :text \"\"
                :symbols [{:symbol \"...\" :kind :unspecified-kind :display-name \"\"
                           :documentation [] :enclosing-symbol \"\"
                           :signature-documentation nil ; a nested document map when present
                           :relationships [{:symbol \"...\" :is-reference false
                                            :is-implementation false
                                            :is-type-definition false
                                            :is-definition false}]}]
                :occurrences [{:range [5 13 22]
                               :symbol \"...\"
                               :symbol-roles 1
                               :roles #{:definition}
                               :override-documentation []
                               :syntax-kind :unspecified-syntax-kind
                               :diagnostics []
                               :enclosing-range [5 0 9 1]}]}]
   :external-symbols [ ...symbol-information maps... ]}
  ```

  The reader does not validate source identity or artifact freshness; that is
  the provider adapter's responsibility before any fact may claim exact
  authority (ADR-046)."
  [source]
  (with-open [in (io/input-stream source)]
    (parse-index-stream in)))
