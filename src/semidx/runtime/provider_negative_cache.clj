(ns semidx.runtime.provider-negative-cache
  "Stage 6c of the Semantic Provider Authority Migration (plans/018, ADR-046):
  a process-local negative cache for project-scoped providers.

  The problem it solves was measured in Stage 6b: a project provider that cannot
  index a workspace is retried on every build. On this repository — neither a
  TypeScript nor a Java project — the two SCIP providers failed on each run and
  those failed attempts cost about 5.3 s of the 6.1 s that shadow mode adds to a
  14 s build.

  The rule this namespace exists to enforce is that remembering a failure must
  never silently switch a semantic tier off. Three guards keep it honest:

  1. **Default deny.** `cacheable-negative-result?` answers false for anything it
     cannot classify. Only failures that are a property of the workspace are
     remembered; a timeout, an unbuilt classpath, a contract violation, or an
     unknown throw is retried, because none of them says anything about whether
     the workspace is indexable.
  2. **A short TTL.** Even a correctly classified negative expires, so an
     `npm install` or a new `pom.xml` costs one stale build at most.
  3. **The provider never disappears.** A hit produces a `skipped` result in the
     same shape a run would have returned, carrying `cached_negative_result` and
     the original failure code, so `provider_summary` still accounts for the
     provider instead of losing a tier without a trace.

  In-memory only, on purpose. A disk-backed cache needs schema versioning,
  cleanup, corrupted-file handling, and cross-branch behaviour, none of which the
  measured problem — repeated builds inside one long-lived MCP process — calls
  for. `plans/018` records the disk tier as a later slice, to be justified by
  measurement rather than by symmetry."
  (:require [clojure.java.io :as io]
            [semidx.runtime.providers :as providers])
  (:import [java.time Instant]))

(def default-ttl-ms
  "How long one negative result stays believable, in milliseconds.

  Five minutes is chosen against both failure modes: long enough that a
  rebuild loop stops paying for a doomed indexer run, short enough that a
  workspace which just became indexable is retried while the person who changed
  it is still working on it."
  (* 5 60 1000))

(def cacheable-failure-codes
  "Failure codes whose negative result is a property of the workspace rather than
  of one attempt.

  `:scip_index_failed` is the only one the SCIP adapters emit today, and it is
  deliberately generic: both adapters report every failure mode under it,
  including a caught exception. It is therefore admitted only together with the
  eligibility gate in `cacheable-negative-result?` — never on the code alone.

  The remaining codes are reserved for providers that report *why* a workspace
  is not indexable. Nothing emits them yet; they are listed so a provider that
  learns to distinguish `this project has no manifest` from `this run broke` has
  a code that is cacheable on its own terms.

  Everything absent from this set is retried. That includes `:timeout`, an OOM
  or killed process, `scip_runtime_classes_unavailable`,
  `:project_provider_failed`, `:project_provider_contract_violation`, and any
  failure with no code at all."
  #{:scip_index_failed
    :provider_project_not_applicable
    :missing_project_manifest
    :unsupported_project_layout
    :no_supported_sources_for_provider})

(defonce ^:private process-cache
  ;; defonce, not def: a namespace reload during development must not discard
  ;; what the running process already learned about its workspaces.
  (atom {}))

(defn default-cache
  "The process-local cache atom. Injected callers pass their own instead."
  []
  process-cache)

(defn context
  "Build the cache context from caller options, or nil when caching is off.

  - `:provider_negative_cache` — an atom to use, or `false` to disable entirely;
    absent means the process-local cache;
  - `:provider_negative_cache_ttl_ms` — TTL override;
  - `:provider_negative_cache_now_fn` — clock override, so a TTL test does not
    have to sleep."
  [opts]
  (let [requested (get opts :provider_negative_cache ::default)]
    (when-not (false? requested)
      {:cache (if (or (nil? requested) (= ::default requested))
                (default-cache)
                requested)
       :ttl-ms (or (:provider_negative_cache_ttl_ms opts) default-ttl-ms)
       :now-fn (or (:provider_negative_cache_now_fn opts)
                   #(System/currentTimeMillis))})))

(defn- canonical-root
  "An absolute, normalized root path, so `.` and `./` and an absolute spelling of
  the same workspace share one entry. Path normalization only: no filesystem
  access, so a root that does not exist is still a stable key."
  [root-path]
  (-> (io/file (str root-path)) .toPath .toAbsolutePath .normalize .toString))

(defn cache-key
  "Identity of one negative result: the workspace, the provider, and the provider
  version. The fingerprint is compared on lookup rather than folded in here, so
  a changed workspace replaces its entry instead of accumulating one per state."
  [provider-id root-path]
  [(canonical-root root-path)
   provider-id
   (:provider_version (providers/descriptor provider-id))])

(defn- manifest-signal [root-path manifest-name]
  (let [file (io/file (str root-path) manifest-name)]
    (if (.isFile file)
      {:name manifest-name
       :present true
       :modified_at (.lastModified file)
       :size (.length file)}
      {:name manifest-name :present false})))

(defn fingerprint
  "The cheap signals that decide whether a workspace could be indexable at all.

  Three inputs, none of which costs an indexer run:

  - the provider's declared project manifests (`:project_manifests` on the
    catalog descriptor), by presence, size, and mtime — adding a `package.json`
    or a `pom.xml` changes this, which is exactly when a negative result stops
    being true;
  - the observed provider status, minus `:observed_at`. The adapters put the
    resolved toolchain identity on it (`:cli_path`, `:toolchain_dir`), so
    installing a toolchain invalidates the entry without this namespace
    duplicating any resolution logic. `:observed_at` is wall-clock and would
    make every fingerprint differ;
  - the options actually forwarded to the provider, minus
    `:expected_document_digests`. Those digests are per-file content, which
    changes on every edit and says nothing about whether the project can be
    indexed; anything else in that map is an input the caller chose."
  [provider-id {:keys [root-path provider-opts status]}]
  (let [descriptor (providers/descriptor provider-id)
        declared (vec (:project_manifests descriptor))
        manifests (mapv #(manifest-signal root-path %) declared)]
    {:provider_version (:provider_version descriptor)
     :manifests_declared (boolean (seq declared))
     :manifest_present (boolean (some :present manifests))
     :manifests manifests
     :status (dissoc status :observed_at)
     :provider_opts (dissoc provider-opts :expected_document_digests)}))

(defn- failure-codes [result]
  (into #{}
        (keep (fn [diagnostic]
                (when-let [code (:code diagnostic)]
                  (cond-> code (string? code) keyword))))
        (:diagnostics result)))

(defn cacheable-negative-result?
  "Whether this failed run may be remembered. False for everything it cannot
  positively classify — that default is the main safety rail of this stage.

  A result qualifies only when it failed, when it named at least one code, when
  *every* code it named is cacheable, and when the generic `:scip_index_failed`
  is backed by eligibility evidence: the provider declares project manifests and
  none of them is present in the workspace. Without that gate the code would also
  cover a TypeScript project whose compile broke this morning, and one such run
  would suppress the exact tier for the whole TTL."
  [result fingerprint]
  (let [codes (failure-codes result)]
    (boolean
     (and (= "failed" (:result result))
          (seq codes)
          (every? cacheable-failure-codes codes)
          (or (not (contains? codes :scip_index_failed))
              (and (:manifests_declared fingerprint)
                   (not (:manifest_present fingerprint))))))))

(defn entry
  "One remembered negative result. Flat and serializable on purpose: the disk
  tier, if it is ever justified, stores exactly this."
  [{:keys [provider-id root-path fingerprint result now-ms ttl-ms]}]
  (let [diagnostic (first (:diagnostics result))]
    {:provider_id provider-id
     :provider_version (:provider_version result)
     :root_path (canonical-root root-path)
     :fingerprint fingerprint
     :failure_code (let [code (:code diagnostic)]
                     (cond-> code (string? code) keyword))
     :message (:message diagnostic)
     :recorded_at_ms now-ms
     :ttl_ms ttl-ms}))

(defn lookup
  "A still-believable negative result for this key, or nil.

  Three ways to miss, and each one means a real run: no entry, a workspace whose
  fingerprint moved, or an entry past its TTL."
  [{:keys [cache now-fn]} key fingerprint]
  (when cache
    (when-let [found (get @cache key)]
      (when (and (= fingerprint (:fingerprint found))
                 (< (long (now-fn))
                    (+ (long (:recorded_at_ms found)) (long (:ttl_ms found)))))
        found))))

(defn remember!
  "Record a negative result. Returns the entry, so a caller can report what it
  stored without reaching back into the cache."
  [{:keys [cache]} key entry]
  (when cache
    (swap! cache assoc key entry))
  entry)

(defn skipped-result
  "The result a cache hit returns, in the shape every project provider returns.

  `skipped` rather than `failed`: nothing failed in this run, the run was not
  attempted. The distinction matters to a reader of `provider_summary`, where a
  count of failures is evidence about a toolchain and a count of skips is
  evidence about a policy.

  Contributes no coverage and no facts, exactly like the failure it stands for,
  so no per-file plan admits the provider."
  [{:keys [provider_id provider_version failure_code message fingerprint
           recorded_at_ms ttl_ms]}]
  {:provider_id provider_id
   :provider_version provider_version
   :result "skipped"
   :facts []
   :raw_facts []
   :batches []
   :errors []
   :diagnostics [{:code :cached_negative_result
                  :provider_id provider_id
                  :original_code failure_code
                  :cached_at (str (Instant/ofEpochMilli (long recorded_at_ms)))
                  :expires_at (str (Instant/ofEpochMilli (+ (long recorded_at_ms)
                                                            (long ttl_ms))))
                  :fingerprint fingerprint
                  :message (str "Skipped the " provider_id
                                " project run: the same negative result ("
                                failure_code ") is still fresh for this"
                                " workspace"
                                (when message (str " — " message)))}]
   :coverage {:covered_paths []
              :stale_documents []
              :invalid_documents []
              :withheld_fact_count 0
              :complete false}
   :unmapped []})
