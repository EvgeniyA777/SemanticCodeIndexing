# Runtime API

This document describes the current MVP in-memory library API.

## Namespace

- `semantic-code-indexing.core`

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
                :tree_sitter_enabled false}})
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
  --out /tmp/sci.json
```

Smoke helper:

```bash
./scripts/run-mvp-smoke.sh . contracts/examples/queries/symbol-target.json /tmp/sci-smoke.json
```

## Validation and Gates

- Contracts only: `./scripts/validate-contracts.sh`
- Tests: `clojure -M:test`
- Full MVP gates: `./scripts/run-mvp-gates.sh`

## Current MVP Notes

- Clojure parser pipeline is `clj-kondo` first, with regex fallback.
- Java parser is lightweight regex-based in MVP.
- `tree-sitter` is an optional slot in parser options and not enabled in this MVP build.
- Default retrieval skips raw-code escalation stage and returns bounded context output.
- Ranking is structural-first and tiered, with hard ceilings when Tier1 evidence is missing.
- Output contracts are validated against local `malli` mirrors of JSON schemas.
