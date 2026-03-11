# Runtime API

This document describes the current MVP in-memory library API.

## Namespace

- `semantic-code-indexing.core`

## Language Coverage (MVP)

- Clojure (`.clj/.cljc/.cljs`) via `clj-kondo` primary path and regex fallback
- Java (`.java`) via lightweight regex parser
- Elixir (`.ex/.exs`) via lightweight regex parser
- Python (`.py`) via lightweight regex parser
- TypeScript (`.ts/.tsx`) via lightweight regex parser
- Java/Elixir/Python/TypeScript call tokens are normalized for module/class-aware call graph linking

## Public Functions

### `create-index`

Creates a new in-memory index.

```clojure
(require '[semantic-code-indexing.core :as sci])

(def index
  (sci/create-index {:root_path "."}))
```

Options:

- `:root_path` string, default `"."`
- `:paths` vector of relative source paths (optional subset indexing)
- `:parser_opts` parser options map (default uses `clj-kondo` for Clojure)
- `:storage` optional storage adapter
- `:load_latest` if `true`, attempts to load latest snapshot from storage before rebuilding
- `:pinned_snapshot_id` optional exact snapshot id to reuse from storage
- `:max_snapshot_age_seconds` optional TTL for stale detection when reusing a stored snapshot

Example with parser options:

```clojure
(sci/create-index
 {:root_path "."
  :parser_opts {:clojure_engine :clj-kondo
                :java_engine :regex
                :typescript_engine :regex
                :tree_sitter_enabled false}})
```

Tree-sitter extraction path (optional):

```clojure
(sci/create-index
 {:root_path "."
  :parser_opts {:clojure_engine :tree-sitter
                :java_engine :tree-sitter
                :typescript_engine :tree-sitter
                :tree_sitter_enabled true
                :tree_sitter_grammars {:clojure ".tree-sitter-grammars/tree-sitter-clojure"
                                       :java ".tree-sitter-grammars/tree-sitter-java"
                                       :typescript ".tree-sitter-grammars/tree-sitter-typescript/typescript"}}})
```

Bootstrap pinned grammar checkouts under `.tree-sitter-grammars/`:

```bash
./scripts/setup-tree-sitter-grammars.sh
```

Returned index maps and `repo-map` outputs now carry `:index_lifecycle`, which includes:

- `:reused_snapshot`
- `:snapshot_pinned`
- `:stale`
- `:age_seconds`
- `:max_snapshot_age_seconds`
- `:rebuild_reason`
- `:provenance`

Current lifecycle behavior:

- `:load_latest true` reuses the newest stored snapshot when it is within TTL
- if that stored snapshot is older than `:max_snapshot_age_seconds`, the runtime rebuilds and reports `:rebuild_reason "snapshot_stale"`
- `:pinned_snapshot_id` reuses the exact stored snapshot even if it is stale, and marks it via `:snapshot_pinned true`

### `update-index`

Incrementally updates index for changed paths.

```clojure
(def updated
  (sci/update-index index {:changed_paths ["src/my/app/order.clj"]}))
```

Options:

- `:changed_paths` vector of relative source paths
- `:parser_opts` parser options map
- `:storage` optional storage adapter for snapshot persistence

`update-index` now also emits `:index_lifecycle` provenance, including `:parent_snapshot_id` and `:rebuild_reason "changed_paths_update"` for incremental rebuilds.

### `repo-map`

Returns compact map-level view of indexed repository.

```clojure
(sci/repo-map index)
(sci/repo-map index {:max_files 20 :max_modules 20})
```

### `resolve-context`

Canonical public retrieval starts here.

`resolve-context` returns a compact selection artifact, not the rich detail payload. The intended public flow is:

1. `resolve-context`
2. optional `expand-context`
3. optional `fetch-context-detail`

Optional opts:

- `:api_version` - optional client-facing request version switch; defaults to `"1.0"` and rejects unsupported values with `unsupported_api_version`
- `:retrieval_policy` - versioned ranking policy override map for replay/tuning
- `:policy_registry` - optional registry map for active-policy defaults or selector-based lookup
- `:policy_registry_path` - optional EDN registry file path

```clojure
(def query
  {:api_version "1.0"
   :schema_version "1.0"
   :intent {:purpose "code_understanding"
            :details "Locate authority implementation for process-order."}
   :targets {:symbols ["my.app.order/process-order"]
             :paths ["src/my/app/order.clj"]}
   :constraints {:token_budget 1800
                 :max_raw_code_level "enclosing_unit"
                 :freshness "current_snapshot"}
   :hints {:focus_on_tests true
           :prefer_definitions_over_callers true}
   :options {:include_tests true
             :include_impact_hints true
             :allow_raw_code_escalation false}
   :trace {:trace_id "11111111-1111-4111-8111-111111111111"
           :request_id "req-example-001"
           :actor_id "planner_agent"}})

(def result
  (sci/resolve-context index query))
```

Returned keys:

- `:api_version`
- `:selection_id`
- `:snapshot_id`
- `:result_status`
- `:confidence_level`
- `:budget_summary`
- `:focus`
- `:next_step`

Example with policy override:

```clojure
(sci/resolve-context
 index
 query
 {:retrieval_policy
  {:policy_id "heuristic_v1_strict_top"
   :version "2026-03-10"
   :thresholds {:top_authority_min 160}}})
```

Example with registry-backed selection:

```clojure
(def registry
  {:schema_version "1.0"
   :policies
   [{:policy_id "heuristic_v1"
     :version "2026-03-10"
     :state "active"
     :policy {:policy_id "heuristic_v1"
              :version "2026-03-10"}}
    {:policy_id "heuristic_v1_strict_top"
     :version "2026-03-11"
     :state "shadow"
     :policy {:policy_id "heuristic_v1_strict_top"
              :version "2026-03-11"
              :thresholds {:top_authority_min 160}}}]})

(def index
  (sci/create-index {:root_path "."
                     :policy_registry registry}))

;; Uses the active registry policy when no override is passed.
(sci/resolve-context index query)

;; Resolves a specific registry entry by policy_id/version.
(sci/resolve-context
 index
 query
 {:retrieval_policy {:policy_id "heuristic_v1_strict_top"
                     :version "2026-03-11"}})
```

`result_status` is one of:

- `completed`
- `insufficient_evidence`
- `budget_exhausted_at_selection`

`next_step` is the canonical hand-off contract for later stages. It includes:

- `:recommended_action`
- `:available_actions`
- `:reason`
- `:target_unit_ids`

Example follow-up:

```clojure
(def selection
  (sci/resolve-context index query))

(def expansion
  (sci/expand-context index {:selection_id (:selection_id selection)
                             :snapshot_id (:snapshot_id selection)
                             :include_impact_hints true}))

(def detail
  (sci/fetch-context-detail index {:selection_id (:selection_id selection)
                                   :snapshot_id (:snapshot_id selection)
                                   :detail_level "enclosing_unit"}))
```

Selection artifacts are snapshot-bound. Reusing a `selection_id` with the wrong `:snapshot_id` fails with `snapshot_mismatch`. Missing or evicted selections fail with `selection_not_found` or `selection_evicted`.

### `expand-context`

Expands a prior compact selection with bounded structural detail.

Selector keys:

- `:selection_id` - required
- `:snapshot_id` - required
- `:unit_ids` - optional subset of the selected units
- `:include_impact_hints` - optional boolean, defaults to `true`

Returns:

- `:api_version`
- `:selection_id`
- `:snapshot_id`
- `:result_status`
- `:budget_summary`
- `:skeletons`
- optional `:impact_hints`

`budget_summary` includes:

- `:reserved_tokens`
- `:estimated_tokens`
- `:returned_tokens`
- `:within_budget`
- `:truncation_flags`

### `fetch-context-detail`

Fetches the rich retrieval result for a retained selection artifact.

Selector keys:

- `:selection_id` - required
- `:snapshot_id` - required
- `:detail_level` - optional raw-code escalation bound; defaults to the query constraint
- `:unit_ids` - optional subset of the selected units

Returns:

- `:api_version`
- `:selection_id`
- `:snapshot_id`
- `:raw_context`
- `:context_packet`
- `:guardrail_assessment`
- `:diagnostics_trace`
- `:stage_events`

Both `:context_packet` and `:diagnostics_trace` include:

- `:retrieval_policy` - `{ :policy_id ... :version ... }`
- `:capabilities` - selected-language and parser-coverage summary, including per-language strength and a derived `:confidence_ceiling`

While the selection artifact is retained, repeated `fetch-context-detail` calls with the same selector are idempotent.

### `resolve-context-detail`

Convenience helper for internal callers that still need the old one-shot rich retrieval behavior.

```clojure
(sci/resolve-context-detail index query)
```

It is intentionally not the canonical public flow. External integrations should prefer the explicit staged API so selection stability and later-stage budgets remain visible.

The capability layer is now language-strength-aware. Runtime retrieval emits `:selected_language_strengths` and `:confidence_ceiling` inside `:capabilities`, then caps the final confidence level to that ceiling after late raw-fetch upgrades. This means:

- Clojure can still reach `high` when structural evidence is strong
- Elixir, Java, and Python currently top out at `medium`
- TypeScript remains compatibility-grade and currently tops out at `low`

Guardrails now also surface `capability_ceiling` risk/blocking signals when language strength prevents a higher-autonomy posture.

The same capability summary now also surfaces index lifecycle state:

- `:index_age_seconds`
- `:index_stale`
- `:snapshot_pinned`
- `:index_provenance_source`

If a query asks for `:freshness "current_snapshot"` and retrieval is running against a stale pinned/reused snapshot, guardrails now emit `stale_index` and block autonomous drafting until the host rebuilds or explicitly allows stale usage.

## Offline Policy Governance CLI

The existing replay/evaluation surface under `clojure -M:eval` now supports governed policy workflows without changing the runtime request contract.

Subcommands:

- `score-policy`
- `compare-policies`
- `shadow-review`
- `promote-policy`

Examples:

```bash
clojure -M:eval score-policy \
  --root . \
  --dataset fixtures/retrieval/corpus.json \
  --policy-file /tmp/policy.edn
```

```bash
clojure -M:eval compare-policies \
  --root . \
  --dataset /tmp/replay.json \
  --baseline-policy-file /tmp/baseline.edn \
  --candidate-policy-file /tmp/candidate.edn
```

```bash
clojure -M:eval shadow-review \
  --root . \
  --dataset /tmp/replay.json \
  --registry /tmp/policy-registry.edn \
  --write-registry
```

```bash
clojure -M:eval promote-policy \
  --root . \
  --dataset /tmp/replay.json \
  --registry /tmp/policy-registry.edn \
  --candidate-policy-id heuristic_v1_candidate \
  --candidate-version 2026-03-11 \
  --manual-approval \
  --dry-run
```

Registry shape (EDN):

```clojure
{:schema_version "1.0"
 :policies
 [{:policy_id "heuristic_v1"
   :version "2026-03-10"
   :state "active"
   :governance {:promotion_mode "auto_promotable"
                :approval_tier "standard"}
   :policy {:policy_id "heuristic_v1"
            :version "2026-03-10"}}
  {:policy_id "heuristic_v1_candidate"
   :version "2026-03-11"
   :state "shadow"
   :governance {:promotion_mode "manual_approval_required"
                :approval_tier "restricted"}
   :policy {:policy_id "heuristic_v1_candidate"
            :version "2026-03-11"
            :thresholds {:top_authority_min 140}}}]}
```

Fixed scorecard metrics currently emitted for governed replay:

- `top_authority_hit_rate`
- `required_path_hit_rate`
- `minimum_confidence_pass_rate`
- `degraded_rate`
- `fallback_rate`
- `confidence_calibration.mean_absolute_error`

Governed replay outputs now also include `confidence_ceiling_distribution`, so policy review can see how often retrievals were capability-capped by language strength even when the fixed protected metrics remain unchanged.

Promotion gates currently reject activation when any protected metric regresses versus the baseline policy.

`promote-policy` now enforces the same governance-tier rules as the scheduled cadence. `blocked` candidates are never promoted, `manual_approval_required` candidates stay replay-eligible but require explicit `--manual-approval` for direct promotion, and replay-gate failures still deny promotion even when manual approval is supplied.

`shadow-review` treats the current `active` registry entry as the baseline, evaluates every `shadow` policy against it, reports replay-gate readiness separately from governance-tier auto-promotion readiness, and can persist per-policy `:shadow_review` metadata such as `:reviewed_at`, `:eligible_for_promotion`, `:eligible_for_auto_promotion`, governance reasons, and protected-case summaries.

### `impact-analysis`

Returns `impact_hints` only for the same query semantics.

```clojure
(sci/impact-analysis index query)
```

### `query-units`

Query persisted graph units from a storage adapter.

```clojure
(sci/query-units pg-store "." {:module "my.app.order" :limit 50})
```

### `query-callers`

Query persisted callers of a specific `unit_id`.

```clojure
(sci/query-callers pg-store "." "src/my/app/order.clj::my.app.order/validate-order" {:limit 50})
```

### `query-callees`

Query persisted callees of a specific `unit_id`.

```clojure
(sci/query-callees pg-store "." "src/my/app/order.clj::my.app.order/process-order" {:limit 50})
```

## Storage Adapters

### In-memory storage

```clojure
(def mem-store (sci/in-memory-storage))

(def index
  (sci/create-index {:root_path "."
                     :storage mem-store}))
```

### PostgreSQL storage (optional adapter)

```clojure
(def pg-store
  (sci/postgres-storage
   {:jdbc-url "jdbc:postgresql://localhost:5432/semantic_index"
    :user "semantic_user"
    :password "change-me"}))

(def index
  (sci/create-index
   {:root_path "."
    :storage pg-store}))
```

You can load latest stored snapshot:

```clojure
(sci/create-index {:root_path "."
                   :storage pg-store
                   :load_latest true})
```

PostgreSQL adapter persists:

- index snapshots (`semantic_index_snapshots`)
- unit projections (`semantic_index_units`)
- call edge projections (`semantic_index_call_edges`)

## Usage Metrics Sinks

Usage metrics are optional and separate from index persistence.

### In-memory usage metrics

```clojure
(def metrics (sci/in-memory-usage-metrics))

(def index
  (sci/create-index
   {:root_path "."
    :usage_metrics metrics
    :usage_context {:session_id "session-001"
                    :task_id "task-001"
                    :actor_id "planner-agent"}}))

(sci/resolve-context index query)
(sci/record-feedback! index {:trace_id "11111111-1111-4111-8111-111111111111"
                             :request_id "req-example-001"
                             :feedback_outcome "helpful"
                             :followup_action "planned"
                             :confidence_level "high"
                             :retrieval_issue_codes ["resolved_target_correct"]
                             :ground_truth_unit_ids ["src/my/app/order.clj::my.app.order/process-order"]
                             :ground_truth_paths ["src/my/app/order.clj"]})

(sci/slo-report metrics)
```

### PostgreSQL usage metrics

```clojure
(def metrics
  (sci/postgres-usage-metrics
   {:jdbc-url "jdbc:postgresql://localhost:5432/semantic_index"
    :user "semantic_user"
    :password "change-me"}))

(def index
  (sci/create-index
   {:root_path "."
    :usage_metrics metrics}))
```

PostgreSQL usage metrics persist:

- raw events in `semantic_usage_events`
- explicit feedback in `semantic_usage_feedback`
- daily aggregates in `semantic_usage_daily_rollups`

`sci/slo-report` now provides a compact operational summary over either in-memory or PostgreSQL-backed events.

When HTTP/gRPC edges are started with `SCI_USAGE_METRICS_JDBC_URL`, those surfaces emit the same normalized usage events as library/MCP, including tenant and correlation fields from headers/metadata plus query `:trace` where applicable.

`resolve-context` usage events now also retain a bounded query/outcome snapshot inside their payload, including the structured query, selected unit/path summaries, top-authority ids, and outcome summary. That snapshot is the foundation for Phase 5 replay harvesting.

Current SLO-facing metrics include:

- `index_latency_ms`
- `retrieval_latency_ms`
- `cache_hit_ratio`
- `degraded_rate`
- `fallback_rate`
- `policy_version_distribution`

Optional filters:

- `:surface`
- `:operation`
- `:tenant_id`
- `:since`
- `:root_path`

### Replay harvesting

You can now build a replay dataset directly from usage events plus structured feedback.

Library API:

```clojure
(sci/harvest-replay-dataset metrics)
(sci/harvest-replay-dataset metrics {:surface "http"
                                     :tenant_id "tenant-001"
                                     :root_path "/srv/repos/app"})
```

CLI:

```bash
clojure -M:eval harvest-replay-dataset \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --surface http \
  --tenant-id tenant-001 \
  --out "${TMPDIR:-.tmp}/sci-harvest.json"
```

Current harvested dataset behavior:

- only `resolve_context` usage events are harvested
- the original structured query is preserved under each dataset entry
- feedback `ground_truth_unit_ids` and `ground_truth_paths` are translated into the existing `expected` replay shape
- difficult or failed retrievals are automatically marked as `protected_case` when feedback or retrieval telemetry indicates a hard case (`not_helpful`, `abandoned`, major issue codes, degraded result, low confidence, or fallback-heavy retrieval)
- `:root_path` filtering is applied via the recorded `root_path_hash`, so one shared metrics sink can be partitioned back into per-repository replay corpora

### Calibration reports

You can also compute confidence calibration directly against real feedback outcomes.

Library API:

```clojure
(sci/calibration-report metrics)
(sci/calibration-report metrics {:surface "mcp"
                                 :tenant_id "tenant-001"
                                 :root_path "/srv/repos/app"})
```

CLI:

```bash
clojure -M:eval calibration-report \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --surface mcp \
  --tenant-id tenant-001 \
  --out "${TMPDIR:-.tmp}/sci-calibration.json"
```

Current report shape includes:

- `:totals.events`
- `:totals.feedback_records`
- `:totals.correlated_queries`
- `:calibration.mean_absolute_error`
- `:calibration.by_confidence_level`

The current calibration logic correlates `resolve_context` usage events with explicit feedback by `trace_id` + `request_id` (or `session_id` + `task_id` fallback), then compares predicted confidence against observed feedback score bands derived from `helpful`, `partially_helpful`, `not_helpful`, and `abandoned`.

### Weekly review artifacts

You can also build a review-oriented artifact that links the full lifecycle of a retrieval case.

Library API:

```clojure
(sci/weekly-review-report metrics)
(sci/weekly-review-report metrics {:surface "grpc"
                                   :tenant_id "tenant-001"
                                   :root_path "/srv/repos/app"})
```

CLI:

```bash
clojure -M:eval weekly-review-report \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --surface grpc \
  --tenant-id tenant-001 \
  --out "${TMPDIR:-.tmp}/sci-weekly-review.json"
```

Current report shape includes:

- `:summary.total_queries`
- `:summary.correlated_queries`
- `:summary.protected_cases`
- `:summary.feedback_outcome_counts`
- `:calibration`
- `:entries`

Each entry currently links:

- the original structured `query`
- `selected_context` (`selected_unit_ids`, `selected_paths`, `top_authority_unit_ids`)
- explicit `feedback` (`feedback_outcomes`, issue codes, ground truth ids/paths)
- `outcome_summary`
- `protected_case`

### Protected replay dataset builder

You can now turn a weekly review artifact back into a governed replay dataset.

Library API:

```clojure
(def review-report (sci/weekly-review-report metrics))
(sci/review-report->protected-replay-dataset review-report)
```

CLI:

```bash
clojure -M:eval protected-replay-dataset \
  --weekly-review "${TMPDIR:-.tmp}/sci-weekly-review.json" \
  --out "${TMPDIR:-.tmp}/sci-protected-replay.json"
```

Current conversion behavior:

- only `protected_case` weekly review entries are included
- output is compatible with the existing replay dataset shape used by `compare-policies` and `promote-policy`
- `expected.top_authority_unit_ids` comes from review feedback ground truth when available
- `expected.required_paths` comes from review feedback ground truth plus query target paths
- `expected.min_confidence_level` uses the strongest feedback confidence level when present, otherwise defaults to `"medium"`
- `source_review` is retained on each query entry for auditability

### Batch policy review pipeline

You can also run the current Phase 5 operational loop as one batch artifact.

Library API:

```clojure
(require '[semantic-code-indexing.runtime.evaluation :as eval]
         '[semantic-code-indexing.runtime.retrieval-policy :as rp])

(eval/policy-review-pipeline
 {:root_path "."
  :usage_metrics metrics
  :registry (rp/load-registry "policy-registry.edn")
  :surface "http"
  :write_registry true})
```

CLI:

```bash
clojure -M:eval policy-review-pipeline \
  --root . \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --registry policy-registry.edn \
  --surface http \
  --write-registry \
  --out "${TMPDIR:-.tmp}/sci-policy-review-pipeline.json"
```

Current pipeline behavior:

- builds `weekly_review_report` from usage metrics using optional `surface`, `tenant_id`, `since`, and `root_path` filters
- converts protected weekly review entries into `protected_replay_dataset`
- runs `shadow-review` against the current registry `active` policy when protected queries and `shadow` policies exist
- returns `shadow_review_report.skipped` with a machine-readable reason when there are no protected queries, no active policy, or no shadow policies
- optionally persists `:shadow_review` metadata back into the registry when `:write_registry true` or `--write-registry` is used

### Scheduled policy review cycle

You can also regularize the Phase 5 loop into a retained artifact stream.

CLI:

```bash
clojure -M:eval scheduled-policy-review \
  --root . \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --registry policy-registry.edn \
  --artifacts-dir "${TMPDIR:-.tmp}/policy-review" \
  --retention-runs 8 \
  --lookback-days 7 \
  --write-registry \
  --out "${TMPDIR:-.tmp}/sci-scheduled-policy-review.json"
```

Current scheduling/retention behavior:

- on the first run, if `--since` is omitted, the command uses `--lookback-days` to derive the review window
- on later runs, if `--since` is omitted, the command reuses `last_run_at` from `policy-review-manifest.json`
- each run writes standalone retained `weekly-review-<instant>.json`, `protected-replay-dataset-<instant>.json`, and `shadow-review-<instant>.json` artifacts in addition to the bundle
- each run writes a timestamped `policy-review-<instant>.json` bundle under `--artifacts-dir`
- a rolling `policy-review-manifest.json` is updated with `last_run_at`, `latest_artifact_path`, direct pointers to the latest standalone component artifacts, retention settings, and scope
- old timestamped bundles and their matching standalone component artifacts are pruned to `--retention-runs`
- when `--write-registry` is set, the persisted registry is updated from the bundled `shadow-review` result during the same run

### Scheduled governance cadence

You can also close the loop one step further and emit retained promotion decisions.

CLI:

```bash
clojure -M:eval scheduled-governance-cycle \
  --root . \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --registry policy-registry.edn \
  --artifacts-dir "${TMPDIR:-.tmp}/policy-review" \
  --retention-runs 8 \
  --lookback-days 7 \
  --history-aware-selection \
  --required-candidate-streak-runs 2 \
  --promotion-cooldown-runs 1 \
  --write-registry \
  --auto-promote \
  --select-best-candidate \
  --out "${TMPDIR:-.tmp}/sci-scheduled-governance-cycle.json"
```

Current cadence behavior:

- runs `scheduled-policy-review` first and reuses its protected replay dataset plus `shadow-review` result
- extracts eligible shadow candidates from the bundled review output and emits deterministic `candidate_ranking`
- if `--auto-promote` is not set, returns retained promotion candidates without activating them
- if `--auto-promote` is set and exactly one eligible shadow candidate exists, runs `promote-policy` automatically against the bundled protected replay dataset
- registry governance metadata can mark candidates as `auto_promotable`, `manual_approval_required`, or `blocked`; only `auto_promotable` candidates can be auto-promoted, and replay gate failures still block all of them
- if replay-eligible candidates exist but all require manual approval, the run is skipped with `no_auto_promotable_candidates`
- if replay-eligible candidates exist but all are governance-blocked, the run is skipped with `all_candidates_blocked_by_governance`
- if multiple eligible candidates exist and `--select-best-candidate` is not set, promotion is skipped with machine-readable reason `multiple_eligible_candidates`
- if multiple eligible candidates exist and `--select-best-candidate` is set, the cadence promotes the highest-ranked candidate using scorecard order plus deterministic policy-id/version tie-breaks
- if `--history-aware-selection` is set, candidate ordering is reweighted by prior governance history before the current deterministic ranking, using prior promoted/selected counts as a stability signal
- `--required-candidate-streak-runs` can require the same selected candidate to appear across consecutive governance runs before promotion is allowed; otherwise the run is skipped with `insufficient_candidate_streak`
- `--promotion-cooldown-runs` can hold auto-promotion for N subsequent governance runs after a successful promotion; otherwise the run is skipped with `promotion_cooldown_active`
- writes a retained `governance-cycle-<instant>.json` artifact plus `governance-cycle-manifest.json`

### Governance history report

You can also summarize retained governance artifacts over time.

CLI:

```bash
clojure -M:eval governance-history-report \
  --artifacts-dir "${TMPDIR:-.tmp}/policy-review" \
  --limit 20 \
  --out "${TMPDIR:-.tmp}/sci-governance-history.json"
```

Current report behavior:

- reads retained `governance-cycle-*.json` artifacts from `--artifacts-dir`
- uses `governance-run-index.json` as the primary retained run source when present and falls back to raw artifact scanning for backward compatibility
- uses `governance-cycle-manifest.json` as the current scope/retention pointer when present
- aggregates `promoted_runs`, `skipped_runs`, `selection_mode_counts`, `promotion_reason_counts`, `selected_policy_counts`, and selected governance-tier summaries
- returns recent `runs` entries with `generated_at`, `artifact_path`, source `review_run_ref`, `selection_mode`, `promotion_reason`, selected policy identity, and selected governance metadata when one exists

### Phase 5 review queue

You can also derive the current operator follow-up queue from retained Phase 5 artifacts.

CLI:

```bash
clojure -M:eval phase5-review-queue \
  --artifacts-dir "${TMPDIR:-.tmp}/policy-review" \
  --limit 20 \
  --out "${TMPDIR:-.tmp}/sci-phase5-review-queue.json"
```

Current queue behavior:

- derives pending operator actions from retained review and governance artifacts instead of storing mutable queue state
- emits queue items for `no_protected_queries`, `manual_approval_required`, `multiple_eligible_candidates`, `insufficient_candidate_streak`, `promotion_cooldown_active`, and `all_candidates_blocked_by_governance`
- includes direct artifact pointers so the operator can jump straight to the relevant retained review/governance files
- latest retained run supersedes older queue items for the same candidate or scope key

### Phase 5 status report

You can also aggregate retained review runs, governance runs, and the derived operator queue into one compact status report.

CLI:

```bash
clojure -M:eval phase5-status-report \
  --artifacts-dir "${TMPDIR:-.tmp}/policy-review" \
  --limit 20 \
  --out "${TMPDIR:-.tmp}/sci-phase5-status-report.json"
```

Current status behavior:

- summarizes retained review runs, governance runs, pending queue items, and protected-query volume
- includes pending reason counts so operators can see whether the loop is blocked by feedback gaps, manual approval, or promotion gating
- reuses retained review and governance indexes rather than recomputing governance logic from scratch

### Scheduled Phase 5 cycle

You can also run the whole retained Phase 5 loop as one top-level orchestration artifact.

CLI:

```bash
clojure -M:eval scheduled-phase5-cycle \
  --root . \
  --usage-metrics-jdbc-url jdbc:postgresql://localhost:5432/semantic_index \
  --registry path/to/policy-registry.edn \
  --artifacts-dir "${TMPDIR:-.tmp}/policy-review" \
  --retention-runs 8 \
  --auto-promote \
  --limit 20 \
  --out "${TMPDIR:-.tmp}/sci-scheduled-phase5-cycle.json"
```

Current orchestration behavior:

- runs `scheduled-governance-cycle`, which itself runs `scheduled-policy-review` first
- snapshots the current derived queue items for the same retained run id
- snapshots the aggregate Phase 5 status summary after that run lands
- writes a retained `phase5-cycle-<instant>.json` artifact plus `phase5-cycle-manifest.json`
- maintains a `phase5-run-index.json` retained index so operators can trace the top-level orchestration cycle directly

## Offline Replay Evaluation

Replay a dataset of structured queries against the current runtime:

```bash
clojure -M:eval --root . --dataset path/to/dataset.json --out "${TMPDIR:-.tmp}/sci-eval.json"
```

Dataset shape:

```json
{
  "queries": [
    {
      "query_id": "order-authority",
      "protected_case": true,
      "query": { "...": "retrieval query contract" },
      "expected": {
        "top_authority_unit_ids": ["src/my/app/order.clj::my.app.order/process-order"],
        "required_paths": ["src/my/app/order.clj"],
        "min_confidence_level": "medium"
      }
    }
  ]
}
```

`protected_case` is optional. When present and `true`, governed comparison and promotion gates treat that replay case as protected and reject candidate policies that introduce newly failed protected queries.

### `skeletons`

Returns skeletons for specific units or paths.

```clojure
(sci/skeletons index {:paths ["src/my/app/order.clj"]})
;; or
(sci/skeletons index {:unit_ids ["src/my/app/order.clj::my.app.order/process-order"]})
```

## Runtime CLI

Use CLI alias to run query directly from JSON:

```bash
clojure -M:runtime \
  --root . \
  --query contracts/examples/queries/symbol-target.json \
  --out "${TMPDIR:-.tmp}/sci.json"
```

Smoke helper:

```bash
./scripts/run-mvp-smoke.sh . contracts/examples/queries/symbol-target.json "${TMPDIR:-.tmp}/sci-smoke.json"
```

## Minimal HTTP Edge

Run a minimal production-boundary HTTP wrapper over the same library runtime:

```bash
clojure -M:runtime-http --host 127.0.0.1 --port 8787
```

Optional auth boundary:

```bash
clojure -M:runtime-http --host 127.0.0.1 --port 8787 --api-key secret-token --require-tenant
```

Optional runtime policy registry:

```bash
clojure -M:runtime-http --policy-registry-file /path/to/policy-registry.edn
```

Optional usage metrics persistence:

```bash
SCI_USAGE_METRICS_JDBC_URL=jdbc:postgresql://localhost:5432/semantic_index \
clojure -M:runtime-http --host 127.0.0.1 --port 8787
```

Endpoints:

- `GET /health`
- `POST /v1/index/create` with JSON body: `root_path`, optional `paths`, optional `parser_opts`
- `POST /v1/retrieval/resolve-context` with JSON body: `root_path`, optional `paths`, optional `parser_opts`, required `query`, optional `retrieval_policy`

## Minimal gRPC Edge

Run a minimal gRPC wrapper over the same library runtime semantics:

```bash
clojure -M:runtime-grpc --host 127.0.0.1 --port 8789
```

Optional auth boundary:

```bash
clojure -M:runtime-grpc --host 127.0.0.1 --port 8789 --api-key secret-token --require-tenant
```

Optional runtime policy registry:

```bash
clojure -M:runtime-grpc --policy-registry-file /path/to/policy-registry.edn
```

Optional usage metrics persistence:

```bash
SCI_USAGE_METRICS_JDBC_URL=jdbc:postgresql://localhost:5432/semantic_index \
clojure -M:runtime-grpc --host 127.0.0.1 --port 8789
```

Service: `semantic_code_indexing.RuntimeService`

Unary methods:

- `Health` (`HealthRequest` -> `HealthResponse`)
- `CreateIndex` (`CreateIndexRequest` -> `CreateIndexResponse`)
- `ResolveContext` (`ResolveContextRequest` -> `ResolveContextResponse`)

Proto schema source: `proto/semantic_code_indexing/runtime/grpc/v1/runtime.proto`

Current gRPC transport uses dedicated runtime protobuf envelope messages while preserving HTTP/library semantics:

- request scalar fields stay typed (`root_path`, `paths`, counters)
- complex nested runtime payloads are carried in explicit `*_json` string fields during this migration step
- the server currently materializes these messages from protobuf descriptors at runtime rather than generated Java classes

When auth boundary is enabled:

- HTTP expects `x-api-key` and `x-tenant-id` headers for protected endpoints.
- gRPC expects `x-api-key` and `x-tenant-id` metadata for protected RPC methods.

Operational correlation:

- HTTP and gRPC also accept optional `x-trace-id`, `x-request-id`, `x-session-id`, `x-task-id`, and `x-actor-id`.
- For `resolve-context`, query `:trace` remains the canonical request-level source and overrides header/metadata fallbacks for those same fields.
- HTTP echoes the effective correlation values back as `x-sci-trace-id`, `x-sci-request-id`, `x-sci-session-id`, `x-sci-task-id`, `x-sci-actor-id`, and `x-sci-tenant-id`.
- gRPC attaches the same `x-sci-*` correlation markers on error trailers alongside `x-sci-error-code` and `x-sci-error-category`.

## Error Taxonomy

Library, HTTP, gRPC, and MCP now share the same canonical taxonomy fields:

- `error_code`
- `error_category`

Current stable codes include:

- `invalid_request`
- `invalid_query`
- `unauthorized`
- `forbidden`
- `forbidden_root`
- `index_not_found`
- `protocol_error`
- `internal_contract_error`
- `invalid_storage_config`
- `internal_error`

Current categories include:

- `client`
- `auth`
- `not_found`
- `protocol`
- `internal`

Transport mapping:

- library: public API functions now rethrow normalized `ExceptionInfo` values with `:error_code` and `:error_category` in `ex-data`
- HTTP: error payloads now emit both legacy `error` and canonical `error_code` / `error_category`
- gRPC: error status remains transport-native, and canonical taxonomy is attached in trailers via `x-sci-error-code` and `x-sci-error-category`
- MCP: tool errors now include canonical `details.code` and `details.category`

Optional host-integrated authz policy:

- CLI flag: `--authz-policy-file /path/to/authz-policy.edn`
- env fallback: `SCI_RUNTIME_AUTHZ_POLICY_FILE=/path/to/authz-policy.edn`
- file format (EDN):

```clojure
{:tenants
 {"tenant-001" {:allowed_roots ["<repo-a-root>"]
                :allowed_path_prefixes ["src/my/app" "test/my/app"]}}}
```

Policy semantics:

- `allowed_roots`: required per tenant; request `root_path` must be inside one of these roots.
- `allowed_path_prefixes`: optional per tenant.
- if `allowed_path_prefixes` is configured, request `paths` must be provided and every path must match an allowed prefix.
- path checks require relative paths and reject traversal (`..`) segments.

Host callback contract (for embedded usage):

- both `runtime.http/start-server` and `runtime.grpc/start-server` accept `:authz_check`.
- callback input map: `:operation`, `:tenant_id`, `:root_path`, `:paths`.
- callback output:
  - allow: `true` or `{:allowed? true}`
  - deny: `false` or `{:allowed? false :code :forbidden|:invalid_request|:internal_error :message "..."}`

Transport mapping for authz denials:

- HTTP: `403` (`:forbidden`), `400` (`:invalid_request`), `500` (`:internal_error`)
- gRPC: `PERMISSION_DENIED`, `INVALID_ARGUMENT`, `INTERNAL`

## Validation and Gates

- Contracts only: `./scripts/validate-contracts.sh`
- Tests: `clojure -M:test`
- Benchmarks: `./scripts/run-benchmarks.sh`
- Full MVP gates: `./scripts/run-mvp-gates.sh`
- Language onboarding scaffold: `./scripts/new-language-adapter.sh <language> --ext .ext1,.ext2`
- Language onboarding validator: `./scripts/validate-language-onboarding.sh <language>` (use `--skip-gates` for structural checks only)

## Current MVP Notes

- Clojure parser pipeline is `clj-kondo` first, with regex fallback.
- Java, Elixir, Python, and TypeScript parsers are lightweight regex-based in MVP.
- `tree-sitter` extraction is implemented for Clojure and Java when corresponding grammar paths are configured.
- If tree-sitter is requested but unavailable/misconfigured, runtime falls back with diagnostics (`tree_sitter_*` codes).
- Raw-code escalation stage is late and opt-in via query options (`allow_raw_code_escalation`) and bounded by `constraints.max_raw_code_level`.
- Ranking is structural-first and tiered, with hard ceilings when Tier1 evidence is missing.
- Output contracts are validated against local `malli` mirrors of JSON schemas.

### Optional PostgreSQL Test Smoke

Set env var and run tests:

```bash
SCI_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/semantic_index_test?user=semantic_user&password=semantic_pass' \
clojure -M:test
```
