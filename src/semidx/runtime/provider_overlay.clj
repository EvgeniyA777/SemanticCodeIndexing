(ns semidx.runtime.provider-overlay
  "Stage 5a of the Semantic Provider Authority Migration (plans/018, ADR-046):
  the live-overlay provider boundary. Opt-in, shadow, default-off.

  A language server answers about one document at a time, which is a third
  execution shape: neither `semidx.runtime.provider-execution`'s per-file parse
  nor `semidx.runtime.provider-batch`'s per-project index. What it adds over the
  batch tier is evidence about content that is *live* — the text a host is
  currently editing, which the batch artifact cannot describe.

  This namespace is language-neutral and must stay so: Stage 5b adds a Java
  provider over it unchanged. Nothing here may assume a node process, an npm
  install, or any other TypeScript-shaped lifecycle. A provider supplies four
  roles and this boundary supplies everything else:

    :status-fn      opts -> status, without starting anything
    :open-fn        opts -> session handle
    :close-fn       handle -> nil
    :fact-source-fn handle, document, opts -> {:facts :unmapped :diagnostics}

  What the boundary owns:

  - one session per overlay operation, closed on every path out (ADR-049);
  - source identity: the text actually sent to the server is what the evidence
    is anchored to, so a fact can never describe content that was not analysed;
  - the failure taxonomy below, applied per document so one bad file cannot take
    the operation down;
  - delivery into per-file arbitration through the injected `run-provider` role,
    the same way the batch tier delivers.

  Nothing here writes a snapshot or changes default extraction."
  (:require [clojure.java.io :as io]
            [semidx.runtime.provider-execution :as provider-execution]
            [semidx.runtime.providers :as providers]
            [semidx.runtime.providers.lsp-java :as lsp-java]
            [semidx.runtime.providers.lsp-typescript :as lsp-typescript])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]))

(def overlay-roles
  "Executable roles for the live-overlay providers, keyed by provider id.

  The catalog owns the descriptors; this map owns the lifecycle and fact
  functions. Stage 5b registers a Java entry here and changes nothing else."
  {"typescript-lsp" {:status-fn lsp-typescript/provider-status
                     :open-fn lsp-typescript/open-session
                     :close-fn lsp-typescript/close-session
                     :fact-source-fn lsp-typescript/document-facts}
   "java-lsp" {:status-fn lsp-java/provider-status
               :open-fn lsp-java/open-session
               :close-fn lsp-java/close-session
               :fact-source-fn lsp-java/document-facts}})

(def failure-kinds
  "Every way an overlay document can fail to produce facts.

  The six the plan names, plus `server_error`: a server that answers with a
  JSON-RPC error has neither crashed nor sent something malformed, and folding
  it into either would misreport what happened. An unrecognised exception is
  reported as `crash`, because after one the session is no longer trusted and is
  closed."
  #{"unavailable"
    "timeout"
    "crash"
    "stale_document"
    "version_mismatch"
    "malformed_response"
    "server_error"})

(def overlay-text-digest-basis
  "Digest of the exact text handed to the server, which for a live buffer is not
  the file on disk. Tagged so it is never compared with a file-bytes digest."
  "overlay_text_sha256")

(defn- now-iso [] (str (Instant/now)))

(defn- sha256 [^String text]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes text StandardCharsets/UTF_8))]
    (str "sha256:" (apply str (map #(format "%02x" %) bytes)))))

(defn classify-throwable
  "Map an exception raised by a provider role onto the failure taxonomy."
  [throwable]
  (case (:type (ex-data throwable))
    :lsp_request_timeout "timeout"
    :lsp_session_closed "crash"
    :lsp_malformed_response "malformed_response"
    :lsp_request_failed "server_error"
    :lsp_server_missing "unavailable"
    :lsp_tsserver_missing "unavailable"
    "crash"))

(defn- failure
  [kind provider-id path message]
  {:kind kind
   :diagnostic {:code (keyword (str "overlay_" kind))
                :provider_id provider-id
                :path path
                :message message}})

;; ---------------------------------------------------------------------------
;; Status
;; ---------------------------------------------------------------------------

(defn overlay-statuses
  "Observe every overlay provider eligible for `paths`, keyed by provider id.

  `semidx.runtime.providers/provider-status` refuses these ids: it cannot test a
  language server and would report `ready` for one. Each provider's own probe is
  called instead, and a probe that throws is reported rather than swallowed."
  ([paths] (overlay-statuses paths {} overlay-roles))
  ([paths opts] (overlay-statuses paths opts overlay-roles))
  ([paths opts roles]
   (into (sorted-map)
         (keep (fn [[provider-id role]]
                 (let [descriptor (providers/descriptor provider-id)]
                   (when (or (empty? paths)
                             (some #(providers/selects-path? descriptor %) paths))
                     [provider-id
                      (try
                        ((:status-fn role) (or opts {}))
                        (catch Throwable t
                          {:provider_id provider-id
                           :observed_at (now-iso)
                           :state "unavailable"
                           :reason_codes ["provider_status_probe_failed"]
                           :message (or (.getMessage t) (str (class t)))}))])))
               roles))))

;; ---------------------------------------------------------------------------
;; Source identity
;; ---------------------------------------------------------------------------

(defn resolve-document
  "Decide what text the server will see, and what that evidence may claim.

  Three outcomes:

  - a document whose text came from disk is `clean`, and its evidence is
    anchored on the file-bytes digest — the same anchor the regex and SCIP tiers
    use, so agreement between tiers is real agreement about one file;
  - a document whose text was supplied by the caller and differs from disk is
    `dirty`, and its evidence is anchored on a digest of that text under a
    distinct basis. It therefore cannot be mistaken for a claim about the file,
    which is what keeps live evidence inside the overlay scope;
  - a document that cannot be read at all, or whose text contradicts a digest
    the caller expected, produces no facts."
  [{:keys [root_path path text expected_content_digest document_version]}]
  (let [file (File. (str root_path) (str path))
        file-digest (providers/file-digest file)
        supplied? (some? text)
        text (or text (when (.isFile file) (slurp file)))]
    (cond
      (nil? text)
      {:failure "stale_document"
       :message (str path " could not be read and no overlay text was supplied")}

      (and expected_content_digest
           (not= expected_content_digest (if supplied? (sha256 text) file-digest)))
      {:failure "version_mismatch"
       :message (str path " does not match the expected content digest "
                     expected_content_digest)}

      :else
      (let [text-digest (sha256 text)
            dirty? (and supplied? (some? file-digest) (not= text-digest file-digest))]
        {:text text
         :dirty dirty?
         :source_identity (cond-> (if dirty?
                                    {:content_digest text-digest
                                     :digest_basis overlay-text-digest-basis}
                                    {:content_digest (or file-digest text-digest)
                                     :digest_basis (if file-digest
                                                     providers/file-digest-basis
                                                     overlay-text-digest-basis)})
                            document_version (assoc :document_version document_version))}))))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- run-document
  [role session provider-id opts document]
  (let [resolved (resolve-document document)]
    (if-let [kind (:failure resolved)]
      (failure kind provider-id (:path document) (:message resolved))
      (try
        (let [result ((:fact-source-fn role)
                      session
                      (assoc document
                             :text (:text resolved)
                             :source_identity (:source_identity resolved))
                      opts)]
          {:facts (vec (:facts result))
           :unmapped (vec (:unmapped result))
           :diagnostics (vec (:diagnostics result))
           :dirty (:dirty resolved)
           :source_identity (:source_identity resolved)})
        (catch Throwable t
          (failure (classify-throwable t) provider-id (:path document)
                   (or (.getMessage t) (str (class t)))))))))

(defn execute-overlay
  "Run one overlay provider over `documents`, inside one session.

  The session is opened once and closed on every path out, including a failing
  open. A document that fails is recorded with its taxonomy kind and does not
  stop the others; a failure to open at all fails every document with one
  reason, because none of them were analysed."
  [provider-id documents {:keys [overlay_roles] :as opts}]
  (let [roles (or overlay_roles overlay-roles)
        role (get roles provider-id)
        base {:provider_id provider-id
              :provider_version (:provider_version (providers/descriptor provider-id))}]
    (if-not role
      (assoc base
             :result "unavailable"
             :reason_codes ["no_overlay_role_registered"]
             :documents {}
             :diagnostics [{:code :overlay_unavailable
                            :provider_id provider-id
                            :message (str provider-id " has no registered overlay role")}])
      (let [opened (try
                     {:session ((:open-fn role) opts)}
                     (catch Throwable t
                       {:failure (classify-throwable t)
                        :message (or (.getMessage t) (str (class t)))}))]
        (if-let [kind (:failure opened)]
          (assoc base
                 :result (if (= "unavailable" kind) "unavailable" "failed")
                 :reason_codes [kind]
                 :documents {}
                 :diagnostics [{:code (keyword (str "overlay_" kind))
                                :provider_id provider-id
                                :message (str "overlay session could not start: "
                                              (:message opened))}])
          (try
            (let [results (into (sorted-map)
                                (map (fn [document]
                                       [(:path document)
                                        (run-document role (:session opened) provider-id
                                                      opts document)]))
                                documents)]
              (assoc base
                     :result "ready"
                     :reason_codes []
                     :documents results
                     :diagnostics (vec (concat
                                        (mapcat :diagnostics (vals results))
                                        (keep :diagnostic (vals results))))))
            (finally
              (try
                ((:close-fn role) (:session opened))
                (catch Throwable t
                  ;; Never mask a result with a shutdown problem, but never hide
                  ;; one either.
                  (println (str "overlay session close failed for " provider-id
                                ": " (or (.getMessage t) (str (class t))))))))))))))

;; ---------------------------------------------------------------------------
;; Delivery into per-file execution
;; ---------------------------------------------------------------------------

(defn overlay-coverage
  "`provider_id -> paths`, the input `provider-plan` takes as
  `:batch_coverage`.

  Only documents that actually produced a result count. A stale, mismatched, or
  failed document contributes nothing, so the per-file plan never admits the
  overlay for it and the file degrades to the tiers below on its own."
  [executions]
  (into (sorted-map)
        (keep (fn [[provider-id execution]]
                (when (= "ready" (:result execution))
                  (when-let [paths (seq (->> (:documents execution)
                                             (remove (fn [[_ result]] (:kind result)))
                                             (map key)
                                             sort))]
                    [provider-id (vec paths)]))))
        executions))

(defn- facts-index [executions]
  (into {}
        (map (fn [[provider-id execution]]
               [provider-id
                (->> (:documents execution)
                     vals
                     (mapcat :facts)
                     (group-by (fn [fact]
                                 [(get-in fact [:key :path])
                                  (-> fact :evidence first :operation)])))]))
        executions))

(defn overlay-run-provider
  "The `run-provider` role for per-file execution over completed overlay runs.

  An overlay provider does not parse the file: its facts came from a live
  session and are handed back for the (path, operation) the plan admitted it
  for. Everything else goes to `delegate`, or to the file catalog."
  ([executions] (overlay-run-provider executions nil))
  ([executions delegate]
   (let [index (facts-index executions)]
     (fn [provider-id {:keys [path operation] :as request}]
       (if-let [by-key (get index provider-id)]
         {:facts (vec (get by-key [path (name operation)] []))
          :diagnostics []
          :parser_mode nil}
         ((or delegate providers/run-provider) provider-id request))))))

(defn document-states
  "Per-provider document outcomes in one vocabulary for every language:
  which documents produced facts, which were dirty, and which failed with
  which taxonomy kind."
  [executions]
  (into (sorted-map)
        (map (fn [[provider-id execution]]
               (let [documents (:documents execution)]
                 [provider-id
                  {:result (:result execution)
                   :reason_codes (vec (:reason_codes execution))
                   :analysed (vec (sort (keep (fn [[path result]]
                                                (when-not (:kind result) path))
                                              documents)))
                   :dirty (vec (sort (keep (fn [[path result]]
                                             (when (:dirty result) path))
                                           documents)))
                   :failed (into (sorted-map)
                                 (keep (fn [[path result]]
                                         (when-let [kind (:kind result)]
                                           [path kind]))
                                       documents))}])))
        executions))

(defn statuses-for-path
  "Overlay statuses narrowed to one document.

  A provider's status is provider-level: it says the session started, not that
  this document was analysed. An overlay provider is file-scoped, so unlike the
  project batch tier it is a plan candidate by selector alone — which means a
  document that timed out, was stale, or mismatched would still admit the
  provider and report a gap for an operation nothing was going to answer.

  Coverage is the per-document authority, exactly as it is for the batch tier:
  outside it the provider is downgraded to unavailable, carrying the reason the
  document actually failed with."
  [statuses coverage document-states path]
  (reduce (fn [acc [provider-id status]]
            (let [covered? (contains? (set (get coverage provider-id)) path)
                  failure (get-in document-states [provider-id :failed path])]
              (assoc acc provider-id
                     (if (or covered? (not= "ready" (:state status)))
                       status
                       (assoc status
                              :state "unavailable"
                              :reason_codes [(str "overlay_" (or failure "document_not_analysed"))])))))
          {}
          statuses))

(defn- eligible-providers [paths roles]
  (->> roles
       keys
       (filter (fn [provider-id]
                 (let [descriptor (providers/descriptor provider-id)]
                   (some #(providers/selects-path? descriptor %) paths))))
       sort
       vec))

(defn shadow-facts-for-overlay
  "Run the overlay providers over `:documents`, then plan and execute each
  document's file with their facts available.

  This is the Stage 5a seam end to end, and it is a shadow artifact: no caller
  writes it into a snapshot, `adapters/parse-file` is untouched, and a document
  the overlay could not analyse is planned exactly as it was before this stage.

  Options:
  - `:root_path` (required);
  - `:documents` — `[{:path ... :text ... :document_version ...
    :expected_content_digest ...}]`; `:text` is the live buffer, and its absence
    means read the file;
  - `:overlay_statuses` — pre-observed statuses, mostly for tests;
  - `:overlay_roles` — role registry override, the substitution seam;
  - `:run-provider` — file-scoped execution role, forwarded to per-file runs;
  - toolchain keys are forwarded to each provider unchanged."
  [{:keys [root_path documents parser_opts mode denied_providers execution_policy
           overlay_statuses overlay_roles run-provider]
    :or {mode "shadow"}
    :as opts}]
  (when-not root_path
    (throw (ex-info "shadow-facts-for-overlay requires :root_path"
                    {:error_code :missing_root_path})))
  (let [roles (or overlay_roles overlay-roles)
        documents (mapv #(assoc % :root_path root_path) documents)
        paths (mapv :path documents)
        statuses (or overlay_statuses (overlay-statuses paths opts roles))
        ready (filterv #(= "ready" (get-in statuses [% :state]))
                       (eligible-providers paths roles))
        executions (into (sorted-map)
                         (map (fn [provider-id]
                                [provider-id
                                 (execute-overlay provider-id
                                                  (filterv #(providers/selects-path?
                                                             (providers/descriptor provider-id)
                                                             (:path %))
                                                           documents)
                                                  (assoc opts :overlay_roles roles))]))
                         ready)
        coverage (overlay-coverage executions)
        states (document-states executions)
        runner (overlay-run-provider executions run-provider)
        files (mapv (fn [{:keys [path]}]
                      (provider-execution/facts-for-file
                       {:root_path root_path
                        :path path
                        :parser_opts parser_opts
                        :mode mode
                        :denied_providers denied_providers
                        :execution_policy execution_policy
                        :batch_coverage coverage
                        :observed_statuses (statuses-for-path statuses coverage states path)
                        :run-provider runner}))
                    documents)]
    {:root_path root_path
     :mode mode
     :overlay_statuses statuses
     :planned_provider_ids ready
     :overlay_coverage coverage
     :documents states
     :files files
     :diagnostics (vec (concat (mapcat :diagnostics (vals executions))
                               (mapcat :diagnostics files)))}))
