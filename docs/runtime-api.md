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

### `repo-map`

Returns compact map-level view of indexed repository.

```clojure
(sci/repo-map index)
(sci/repo-map index {:max_files 20 :max_modules 20})
```

### `resolve-context`

Runs retrieval pipeline and returns:

- `:context_packet`
- `:guardrail_assessment`
- `:diagnostics_trace`
- `:stage_events`

```clojure
(def query
  {:schema_version "1.0"
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
             :allow_raw_code_escalation false
             :favor_compact_packet true
             :favor_higher_recall false}
   :trace {:trace_id "11111111-1111-4111-8111-111111111111"
           :request_id "req-example-001"
           :actor_id "planner_agent"}})

(def result
  (sci/resolve-context index query))
```

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

Endpoints:

- `GET /health`
- `POST /v1/index/create` with JSON body: `root_path`, optional `paths`, optional `parser_opts`
- `POST /v1/retrieval/resolve-context` with JSON body: `root_path`, optional `paths`, optional `parser_opts`, required `query`

## Minimal gRPC Edge

Run a minimal gRPC wrapper over the same library runtime semantics:

```bash
clojure -M:runtime-grpc --host 127.0.0.1 --port 8789
```

Optional auth boundary:

```bash
clojure -M:runtime-grpc --host 127.0.0.1 --port 8789 --api-key secret-token --require-tenant
```

Service: `semantic_code_indexing.RuntimeService`

Unary methods:

- `Health` (`google.protobuf.Struct` -> `google.protobuf.Struct`)
- `CreateIndex` (`google.protobuf.Struct` -> `google.protobuf.Struct`)
- `ResolveContext` (`google.protobuf.Struct` -> `google.protobuf.Struct`)

Current gRPC transport uses typed protobuf messages (`google.protobuf.Struct`) while preserving runtime payload semantics compatible with HTTP/library contracts.

When auth boundary is enabled:

- HTTP expects `x-api-key` and `x-tenant-id` headers for protected endpoints.
- gRPC expects `x-api-key` and `x-tenant-id` metadata for protected RPC methods.

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
