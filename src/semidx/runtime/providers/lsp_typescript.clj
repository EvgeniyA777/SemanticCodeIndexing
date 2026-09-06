(ns semidx.runtime.providers.lsp-typescript
  "Stage 5a of the Semantic Provider Authority Migration (plans/018, ADR-046):
  the TypeScript live-overlay provider. Opt-in, shadow, default-off.

  Deliberately thin. Everything that is not TypeScript-specific — session
  lifecycle, the failure taxonomy, source-identity gating, and FactBatch
  assembly — belongs to `semidx.runtime.provider-overlay`, which is the boundary
  the Java provider will reuse unchanged in Stage 5b. What remains here is how
  this server is resolved and started, and how its symbols become facts.

  Two properties of the real server drive the toolchain design, both verified
  against `typescript-language-server` 6.0.0 rather than assumed:

  - it locates TypeScript from the **workspace**, not from beside itself, so
    indexing a workspace with no `node_modules/typescript` fails the initialize
    handshake outright unless `tsserver.path` is supplied in
    `initializationOptions`;
  - it drives a classic tsserver, and TypeScript 7 ships a native compiler with
    no `lib/tsserver.js`, so the repo-managed toolchain pins TypeScript to 5.x
    as a direct dependency.

  `tsserver` is **not** an LSP server and is never reused as one. The copy
  vendored under `.scip-toolchain` for the SCIP provider speaks its own
  protocol; only `typescript-language-server` is spoken to here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [semidx.runtime.languages.typescript :as ts]
            [semidx.runtime.lsp-client :as lsp]
            [semidx.runtime.providers :as providers]))

(def provider-id "typescript-lsp")
(def provider-version "1")
(def language "typescript")

(def descriptor
  "Catalog descriptor for the TypeScript LSP provider, re-exported from
  `semidx.runtime.providers` so the catalog stays the single source of truth."
  (providers/descriptor provider-id))

;; ---------------------------------------------------------------------------
;; Toolchain resolution (ADR-047-style chain)
;; ---------------------------------------------------------------------------

(def toolchain-dir-name ".lsp-toolchain")

(defn- present [value]
  (let [value (str value)]
    (when-not (str/blank? value) value)))

;; Both resolvers return absolute paths. The session is started with the
;; workspace as its working directory, and ProcessBuilder resolves a relative
;; program against that directory rather than against the JVM's own — so a
;; repo-managed path like `.lsp-toolchain/...` is not found. The SCIP adapter
;; records the same trap for `clojure.java.shell/sh`.
(defn- existing-file [path]
  (when-let [path (present path)]
    (let [file (io/file path)]
      (when (.isFile file) (.getAbsolutePath file)))))

(defn- executable [path]
  (when-let [path (present path)]
    (let [file (io/file path)]
      (when (and (.isFile file) (.canExecute file)) (.getAbsolutePath file)))))

(defn- managed-dir [opts]
  (or (present (:lsp_toolchain_dir opts))
      (present (System/getenv "SEMIDX_LSP_TOOLCHAIN_DIR"))
      toolchain-dir-name))

(defn resolve-command
  "The server command, resolved through the ADR-047 chain: explicit option,
  environment, repo-managed install, then ambient `PATH` as a developer
  convenience. Returns nil rather than throwing when nothing resolves."
  [opts]
  (or (executable (:typescript_lsp_command opts))
      (executable (System/getenv "SEMIDX_TYPESCRIPT_LSP_COMMAND"))
      (executable (str (managed-dir opts)
                       "/node_modules/.bin/typescript-language-server"))
      (some-> (System/getenv "PATH")
              (str/split (re-pattern java.io.File/pathSeparator))
              (->> (map #(executable (str % "/typescript-language-server")))
                   (remove nil?)
                   first))))

(defn resolve-tsserver
  "Path to the classic `tsserver.js` the server must drive. Resolved separately
  from the server itself because the server cannot find it from a workspace that
  has no TypeScript of its own."
  [opts]
  (or (existing-file (:typescript_lsp_tsserver_path opts))
      (existing-file (System/getenv "SEMIDX_TYPESCRIPT_LSP_TSSERVER_PATH"))
      (existing-file (str (managed-dir opts)
                          "/node_modules/typescript/lib/tsserver.js"))))

(defn provider-status
  "Observe whether the TypeScript language server can run right now.

  Both halves are required: a server with no tsserver to drive fails inside the
  initialize handshake, which is a worse place to discover it than here. This
  never starts a process."
  ([] (provider-status {}))
  ([opts]
   (let [command (resolve-command opts)
         tsserver (resolve-tsserver opts)
         base {:provider_id provider-id
               :observed_at (str (java.time.Instant/now))}
         reasons (cond-> []
                   (nil? command) (conj "typescript_lsp_server_missing")
                   (nil? tsserver) (conj "typescript_lsp_tsserver_missing"))]
     (if (seq reasons)
       (assoc base :state "unavailable" :reason_codes reasons)
       (assoc base :state "ready" :reason_codes []
              :command command
              :tsserver_path tsserver)))))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn open-session
  "Start one language server session for a whole overlay operation.

  Throws when the toolchain does not resolve or the handshake fails; the overlay
  boundary turns that into an `unavailable` or `crash` result."
  [{:keys [root_path timeout_ms] :as opts}]
  (let [command (or (resolve-command opts)
                    (throw (ex-info "No typescript-language-server resolved"
                                    {:type :lsp_server_missing
                                     :provider_id provider-id})))
        tsserver (or (resolve-tsserver opts)
                     (throw (ex-info "No tsserver.js resolved for typescript-language-server"
                                     {:type :lsp_tsserver_missing
                                      :provider_id provider-id})))]
    (lsp/start-session! {:root_path root_path
                         :command [command "--stdio"]
                         :timeout_ms (or timeout_ms 20000)
                         :initialization_options {:tsserver {:path tsserver}}})))

(defn close-session [session]
  (when session (lsp/stop-session! session)))

;; ---------------------------------------------------------------------------
;; Symbols -> facts
;; ---------------------------------------------------------------------------

;; LSP SymbolKind values this provider models. Anything else is recorded as
;; unmapped with a reason rather than guessed into a unit: the tier must agree
;; with the existing regex/SCIP spelling of the same definition, not invent a
;; broader one.
(def ^:private symbol-kinds
  {12 :function
   6 :method
   9 :constructor
   5 :container
   11 :container
   10 :container
   23 :container
   13 :term
   14 :term})

(defn- line-of [range edge]
  (some-> range edge :line inc))

(defn- unit-symbol
  "The canonical symbol spelling, matching what the regex and SCIP tiers produce
  for the same definition: `module/name` for a free function or top-level term,
  `module.Owner#name` for a member."
  [module owners name]
  (if (seq owners)
    (str module "." (str/join "." owners) "#" name)
    (str module "/" name)))

(defn- unit-owner [module owners]
  (if (seq owners)
    (str module "." (str/join "." owners))
    module))

(defn flatten-symbols
  "Walk the hierarchical documentSymbol tree into flat definition records.

  Containers (classes, interfaces, enums, namespaces) contribute ownership, not
  units: the regex tier mints no unit for a TypeScript class either, and a tier
  that invented one would show up as a permanent `exact_only` difference rather
  than as agreement."
  ([symbols module] (flatten-symbols symbols module []))
  ([symbols module owners]
   (vec
    (mapcat
     (fn [symbol*]
       (let [name (:name symbol*)
             kind (get symbol-kinds (:kind symbol*))
             children (:children symbol*)
             start (line-of (:range symbol*) :start)
             end (line-of (:range symbol*) :end)
             here (case kind
                    (:function :method :term)
                    [{:name name
                      :kind (clojure.core/name kind)
                      :symbol (unit-symbol module owners name)
                      :owner (unit-owner module owners)
                      :start_line (or start 1)
                      :end_line (max (or end 1) (or start 1))
                      :selection_position (get-in symbol* [:selectionRange :start])
                      :detail (some-> (:detail symbol*) str/trim not-empty)
                      :lsp_kind (:kind symbol*)}]

                    (:container :constructor)
                    []

                    [{:unmapped name :reason (str "unmodelled_lsp_symbol_kind_" (:kind symbol*))}])
             ;; Only a container passes its name down as an owner; a method's
             ;; nested function is not a member of that method.
             next-owners (if (= :container kind) (conj owners name) owners)]
         (into here (flatten-symbols children module next-owners))))
     symbols))))

(defn- evidence [{:keys [source_identity operation location detail lsp-kind]}]
  {:provider_id provider-id
   :provider_version provider-version
   :authority "exact"
   :operation operation
   :freshness "exact"
   :source_identity source_identity
   :evidence_location location
   :native_details (cond-> {}
                     lsp-kind (assoc :lsp_symbol_kind lsp-kind)
                     detail (assoc :lsp_detail detail))})

(defn- definition-fact [{:keys [path source_identity]} definition]
  {:key {:fact_kind "unit"
         :language language
         :path path
         :owner (:owner definition)
         :symbol (:symbol definition)
         ;; TypeScript exposes no arity-based overload identity in this tier,
         ;; exactly as the regex tier records for the same definitions.
         :overload_identity nil}
   :evidence [(evidence {:source_identity source_identity
                         :operation "definitions"
                         :location {:path path
                                    :start_line (:start_line definition)
                                    :end_line (:end_line definition)}
                         :detail (:detail definition)
                         :lsp-kind (:lsp_kind definition)})]
   :value {:kind (:kind definition)
           :signature (:detail definition)}})

(defn- reference-fact [{:keys [path source_identity]} definition location]
  {:key {:fact_kind "unit"
         :language language
         :path path
         :owner (:owner definition)
         :symbol (:symbol definition)
         :overload_identity nil}
   :evidence [(evidence {:source_identity source_identity
                         :operation "references"
                         :location location
                         :lsp-kind (:lsp_kind definition)})]})

(defn- uri->path
  "Workspace-relative path for a location URI, or nil when it points outside the
  root (a reference into `node_modules`, for instance).

  Compared as decoded filesystem paths, never as URI strings. `File.toURI`
  produces `file:/Users/...` while the server answers with the normalized
  `file:///Users/...`, so a string prefix test silently discards every location
  the server returns."
  [root-path uri]
  (when uri
    (let [root (.getCanonicalFile (io/file (str root-path)))
          root-path* (str (.getPath root) "/")
          located (try
                    (.getCanonicalPath (io/file (java.net.URI. (str uri))))
                    (catch Exception _ nil))]
      (when (and located (str/starts-with? located root-path*))
        (subs located (count root-path*))))))

(defn- reference-locations
  "`textDocument/references` for one definition, mapped to evidence locations
  inside the workspace. A reference outside the root is dropped: this provider
  describes documents, and a location it cannot express as a workspace path
  cannot anchor a fact."
  [session root-path uri definition]
  (when-let [position (:selection_position definition)]
    (->> (lsp/request! session "textDocument/references"
                       {:textDocument {:uri uri}
                        :position position
                        :context {:includeDeclaration false}})
         (keep (fn [location]
                 (when-let [path (uri->path root-path (:uri location))]
                   {:path path
                    :start_line (line-of (:range location) :start)
                    :end_line (line-of (:range location) :end)})))
         vec)))

(def default-max-reference-requests
  "References cost one request per definition, so the count is bounded and the
  truncation is reported rather than silently applied."
  32)

(defn document-facts
  "Facts for one document from one live server session.

  The document is opened once and stays open until every request about it has
  been answered. `lsp-client/text-document-symbols!` is deliberately not reused
  here: it closes the document as soon as the symbols come back, and a
  `textDocument/references` request afterwards then resolves against a document
  the server no longer holds and returns nothing.

  The text sent through `didOpen` is the text the caller is indexing, so the
  evidence describes exactly what was analysed rather than whatever is on disk.
  Returns `{:facts [...] :unmapped [...] :diagnostics [...]}`; failures are
  raised and classified by the overlay boundary."
  [session {:keys [root_path path text source_identity document_version]}
   {:keys [max_reference_requests include_references]
    :or {max_reference_requests default-max-reference-requests
         include_references true}}]
  (let [module (ts/ts-module-name path)
        uri (str (.toURI (.getCanonicalFile (io/file (str root_path) (str path)))))
        version (or document_version 1)]
    (lsp/notify! session "textDocument/didOpen"
                 {:textDocument {:uri uri
                                 :languageId language
                                 :version version
                                 :text text}})
    (try
      (let [raw (lsp/request! session "textDocument/documentSymbol"
                              {:textDocument {:uri uri}})
            _ (when-not (or (nil? raw) (sequential? raw))
                (throw (ex-info "documentSymbol returned a non-sequential result"
                                {:type :lsp_malformed_response
                                 :provider_id provider-id
                                 :path path})))
            walked (flatten-symbols raw module)
            definitions (filterv :symbol walked)
            unmapped (filterv :unmapped walked)
            context {:path path :source_identity source_identity}
            reference-targets (if include_references
                                (take max_reference_requests definitions)
                                [])
            truncated? (and include_references
                            (> (count definitions) max_reference_requests))]
        {:facts (into (mapv #(definition-fact context %) definitions)
                      (for [definition reference-targets
                            location (reference-locations session root_path uri definition)]
                        (reference-fact context definition location)))
         :unmapped unmapped
         :diagnostics (cond-> []
                        truncated?
                        (conj {:code :lsp_reference_requests_truncated
                               :provider_id provider-id
                               :path path
                               :limit max_reference_requests
                               :definition_count (count definitions)
                               :message (str "reference lookup was bounded to "
                                             max_reference_requests " of "
                                             (count definitions) " definitions")}))})
      (finally
        (lsp/notify! session "textDocument/didClose"
                     {:textDocument {:uri uri}})))))
