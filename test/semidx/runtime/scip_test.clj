(ns semidx.runtime.scip-test
  "Tests for the transport-level SCIP reader.

  Two layers of coverage:

  1. A protobuf round trip built with the generated `scip.Scip` builders, so the
     reader is checked against every field it translates without depending on an
     external toolchain.
  2. The committed real artifact `typescript-corpus.scrubbed.scip` (produced by
     the pinned `scip-typescript@0.4.0` over the protected corpus, see
     `fixtures/provider-authority/scip/README.md`), asserted field by field
     against the decoded JSON reference `typescript-corpus.observed.json`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [semidx.runtime.scip :as scip])
  (:import [scip Scip$Document Scip$Index Scip$Metadata Scip$Occurrence
            Scip$Relationship Scip$SymbolInformation Scip$SymbolInformation$Kind
            Scip$SymbolRole Scip$SyntaxKind Scip$TextEncoding]))

(def ^:private fixture-scip
  "fixtures/provider-authority/scip/typescript-corpus.scrubbed.scip")

(def ^:private fixture-json
  "fixtures/provider-authority/scip/typescript-corpus.observed.json")

;; --- decode-symbol-roles ---------------------------------------------------

(deftest decode-symbol-roles-covers-every-bit
  (is (= #{} (scip/decode-symbol-roles 0)))
  (is (= #{:definition} (scip/decode-symbol-roles 1)))
  (is (= #{:import} (scip/decode-symbol-roles 2)))
  (is (= #{:write-access} (scip/decode-symbol-roles 4)))
  (is (= #{:read-access} (scip/decode-symbol-roles 8)))
  (is (= #{:generated} (scip/decode-symbol-roles 16)))
  (is (= #{:test} (scip/decode-symbol-roles 32)))
  (is (= #{:forward-definition} (scip/decode-symbol-roles 64)))
  (testing "the field is a bitset — several roles at once"
    (is (= #{:definition :write-access} (scip/decode-symbol-roles 5)))
    (is (= #{:definition :import :read-access} (scip/decode-symbol-roles 11)))))

;; --- generated-builder round trip ---------------------------------------------

(defn- build-index ^bytes []
  (-> (Scip$Index/newBuilder)
      (.setMetadata (-> (Scip$Metadata/newBuilder)
                        (.setProjectRoot "file:///work")
                        (.setTextDocumentEncoding Scip$TextEncoding/UTF8)
                        (.setToolInfo (-> (scip.Scip$ToolInfo/newBuilder)
                                          (.setName "test-indexer")
                                          (.setVersion "9.9")
                                          (.addArguments "--flag")))))
      (.addDocuments
       (-> (Scip$Document/newBuilder)
           (.setRelativePath "src/a.ts")
           (.setLanguage "typescript")
           (.addSymbols (-> (Scip$SymbolInformation/newBuilder)
                            (.setSymbol "sym-class")
                            (.setDisplayName "AClass")
                            (.setKind Scip$SymbolInformation$Kind/Class)
                            (.addDocumentation "doc line")
                            (.setSignatureDocumentation
                             (-> (Scip$Document/newBuilder)
                                 (.setLanguage "typescript")
                                 (.setText "class AClass")))
                            (.addRelationships
                             (-> (Scip$Relationship/newBuilder)
                                 (.setSymbol "sym-iface")
                                 (.setIsImplementation true)))))
           (.addOccurrences (-> (Scip$Occurrence/newBuilder)
                                (.setSymbol "sym-class")
                                (.setSymbolRoles
                                 (bit-or (.getNumber Scip$SymbolRole/Definition)
                                         (.getNumber Scip$SymbolRole/WriteAccess)))
                                (.setSyntaxKind Scip$SyntaxKind/Identifier)
                                (.addAllRange (map int [3 6 12]))
                                (.addAllEnclosingRange (map int [3 0 9 1]))))
           (.addOccurrences (-> (Scip$Occurrence/newBuilder)
                                (.setSymbol "sym-iface")
                                ;; no roles -> a reference occurrence
                                (.addAllRange (map int [4 2 4 8]))))))
      (.addExternalSymbols (-> (Scip$SymbolInformation/newBuilder)
                               (.setSymbol "ext-sym")
                               (.setKind Scip$SymbolInformation$Kind/Function)))
      (.build)
      (.toByteArray)))

(deftest round-trips-a-generated-index
  (let [idx (scip/parse-index-bytes (build-index))]
    (is (= {:version :unspecified-protocol-version
            :tool-info {:name "test-indexer" :version "9.9" :arguments ["--flag"]}
            :project-root "file:///work"
            :text-document-encoding :utf8}
           (:metadata idx)))
    (is (= 1 (count (:documents idx))))
    (let [doc (first (:documents idx))]
      (is (= "src/a.ts" (:relative-path doc)))
      (is (= "typescript" (:language doc)))
      (is (= [{:symbol "sym-class"
               :kind :class
               :display-name "AClass"
               :documentation ["doc line"]
               :enclosing-symbol ""
               :signature-documentation {:relative-path ""
                                         :language "typescript"
                                         :position-encoding :unspecified-position-encoding
                                         :text "class AClass"
                                         :symbols []
                                         :occurrences []}
               :relationships [{:symbol "sym-iface"
                                :is-reference false
                                :is-implementation true
                                :is-type-definition false
                                :is-definition false}]}]
             (:symbols doc)))
      (testing "definition occurrence: three-element range, roles bitset, enclosing range"
        (is (= {:range [3 6 12]
                :symbol "sym-class"
                :symbol-roles 5
                :roles #{:definition :write-access}
                :override-documentation []
                :syntax-kind :identifier
                :diagnostics []
                :enclosing-range [3 0 9 1]}
               (first (:occurrences doc)))))
      (testing "reference occurrence: four-element range, no roles, empty enclosing range"
        (is (= {:range [4 2 4 8]
                :symbol "sym-iface"
                :symbol-roles 0
                :roles #{}
                :override-documentation []
                :syntax-kind :unspecified-syntax-kind
                :diagnostics []
                :enclosing-range []}
               (second (:occurrences doc))))))
    (is (= [{:symbol "ext-sym"
             :kind :function
             :display-name ""
             :documentation []
             :enclosing-symbol ""
             :signature-documentation nil
             :relationships []}]
           (:external-symbols idx)))))

(deftest read-index-accepts-a-file-path-and-a-stream
  (let [payload (build-index)
        tmp (java.io.File/createTempFile "scip-reader" ".scip")]
    (try
      (io/copy payload tmp)
      (is (= (scip/parse-index-bytes payload)
             (scip/read-index tmp)
             (scip/read-index (.getPath tmp))
             (with-open [in (io/input-stream payload)]
               (scip/parse-index-stream in))))
      (finally (.delete tmp)))))

;; --- real artifact vs decoded reference -------------------------------------

(def ^:private role-name->keyword
  {"Definition" :definition
   "Import" :import
   "WriteAccess" :write-access
   "ReadAccess" :read-access
   "Generated" :generated
   "Test" :test
   "ForwardDefinition" :forward-definition})

(defn- json-occurrence-projection [o]
  {:range (vec (get o "range"))
   :symbol (get o "symbol")
   :symbol-roles (get o "symbol_roles")
   :roles (set (map role-name->keyword (get o "role_names")))
   :enclosing-range (vec (get o "enclosing_range"))})

(defn- reader-occurrence-projection [o]
  (select-keys o [:range :symbol :symbol-roles :roles :enclosing-range]))

(deftest reads-the-committed-scip-typescript-artifact
  (let [idx (scip/read-index fixture-scip)
        observed (json/read-str (slurp fixture-json))]
    (testing "metadata survives scrubbing except project_root"
      (is (= "" (get-in idx [:metadata :project-root]))
          "the committed fixture must carry no absolute indexing-machine path")
      (is (= {:name "scip-typescript" :version "0.4.0" :arguments []}
             (get-in idx [:metadata :tool-info])))
      (is (= :utf8 (get-in idx [:metadata :text-document-encoding]))))
    (testing "documents line up with the decoded reference"
      (is (= (mapv #(get % "relative_path") (get observed "documents"))
             (mapv :relative-path (:documents idx))))
      (doseq [[obs-doc rdr-doc] (map vector (get observed "documents") (:documents idx))]
        (testing (:relative-path rdr-doc)
          (is (= (mapv #(get % "symbol") (get obs-doc "symbols"))
                 (mapv :symbol (:symbols rdr-doc))))
          (is (= (mapv json-occurrence-projection (get obs-doc "occurrences"))
                 (mapv reader-occurrence-projection (:occurrences rdr-doc)))))))
    (testing "the re-export surface: index.ts has only its module symbol, no relationships"
      (let [index-doc (first (filter #(= "src/index.ts" (:relative-path %)) (:documents idx)))]
        (is (= ["scip-typescript npm . . src/`index.ts`/"]
               (mapv :symbol (:symbols index-doc))))
        (is (every? (comp empty? :relationships) (:symbols index-doc)))
        (testing "the only definition is the module itself; the re-exported normalize/canonicalize
                  tokens resolve to the orders.ts origin as non-definition occurrences"
          (is (= [{:symbol "scip-typescript npm . . src/`index.ts`/" :roles #{:definition}}]
                 (->> (:occurrences index-doc)
                      (filter (comp seq :roles))
                      (mapv #(select-keys % [:symbol :roles])))))
          (is (every? #(re-find #"src/`orders\.ts`/" (:symbol %))
                      (remove (comp seq :roles) (:occurrences index-doc)))))))
    (testing "external_symbols is empty; stdlib refs are inline occurrences"
      (is (= [] (:external-symbols idx)))
      (is (some (fn [d]
                  (some #(re-find #"lib\.es5\.d\.ts.*String#trim" (:symbol %))
                        (:occurrences d)))
                (:documents idx))))))
