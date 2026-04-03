# Code Context: semidx

- fingerprint: 455b974ed1d7366d857422759ed328e0b9d8819e

## Tree
```text
semidx
├ deps.edn
├ src
│ └ semidx
│   ├ contracts
│   ├ core.clj
│   ├ mcp
│   ├ runtime
│   └ test_runner.clj
└ test
  └ semidx
    ├ compression_test.clj
    ├ evaluation_test.clj
    ├ lua_onboarding_test.clj
    ├ mcp_http_server_test.clj
    ├ mcp_server_test.clj
    ├ policy_governance_test.clj
    ├ project_context_test.clj
    ├ runtime_grpc_test.clj
    ├ runtime_http_test.clj
    ├ runtime_test.clj
    ├ typescript_onboarding_test.clj
    └ usage_metrics_test.clj
```

## Entry Points
- semidx.contracts.cli/-main
- semidx.runtime.cli/-main
- semidx.runtime.compression-cli/-main
- semidx.test-runner/-main

## Namespace Categories
### app/core
- semidx.core
- semidx.mcp.core
### api
- none
### service
- none
### domain
- none
### db
- none
### infra
- none
### util
- none
### test
- semidx.compression-test
- semidx.evaluation-test
- semidx.lua-onboarding-test
- semidx.mcp-http-server-test
- semidx.mcp-server-test
- semidx.policy-governance-test
- semidx.project-context-test
- semidx.runtime-grpc-test
- ... +4 more
### other
- semidx.contracts.cli
- semidx.contracts.schemas
- semidx.contracts.validator
- semidx.mcp.http-server
- semidx.mcp.server
- semidx.mcp.session-registry
- semidx.runtime.adapters
- semidx.runtime.authz
- ... +32 more

## Domain Model
- IndexStorage [protocol]
- UsageMetricsSink [protocol]
- InMemoryStorage [record]
- InMemoryUsageMetrics [record]
- NoOpUsageMetrics [record]
- PostgresStorage [record]
- PostgresUsageMetrics [record]

## Dependency Graph
- semidx.compression-test -> clojure.java.io
- semidx.compression-test -> clojure.string
- semidx.compression-test -> clojure.test
- semidx.compression-test -> semidx.core
- semidx.contracts.cli -> semidx.contracts.validator
- semidx.contracts.validator -> clojure.data.json
- semidx.contracts.validator -> clojure.java.io
- semidx.contracts.validator -> clojure.string
- semidx.contracts.validator -> malli.core
- semidx.contracts.validator -> malli.error
- semidx.contracts.validator -> semidx.contracts.schemas
- semidx.core -> semidx.runtime.compression
- semidx.core -> semidx.runtime.errors
- semidx.core -> semidx.runtime.index
- semidx.core -> semidx.runtime.literal-slice
- semidx.core -> semidx.runtime.retrieval
- semidx.core -> semidx.runtime.retrieval-policy
- semidx.core -> semidx.runtime.semantic-quality
- semidx.core -> semidx.runtime.snapshot-diff
- semidx.core -> semidx.runtime.storage
- ... +211 more

## Namespaces
### semidx.contracts.cli
- path: src/semidx/contracts/cli.clj
- requires: semidx.contracts.validator
- aliases: validator -> semidx.contracts.validator
- symbols:
- -main [function]
### semidx.contracts.schemas
- path: src/semidx/contracts/schemas.clj
- requires: none
- aliases: none
- symbols:
- schema-version [section]
- uuid-str [section]
- timestamp [section]
- code [section]
- code-key [section]
- bounded-string [section]
- bounded-long-string [section]
- string-array [section]
### semidx.contracts.validator
- path: src/semidx/contracts/validator.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, malli.core, malli.error, semidx.contracts.schemas
- aliases: io -> clojure.java.io, json -> clojure.data.json, m -> malli.core, me -> malli.error, schemas -> semidx.contracts.schemas, str -> clojure.string
- symbols:
- example-root [section]
- fixture-root [section]
- ^:private [section]
- schema-root [section]
- read-json-file [function]
- json-files-under [function]
- rel-path [function]
- schema-key-for-path [function]
### semidx.core
- path: src/semidx/core.clj
- requires: semidx.runtime.compression, semidx.runtime.errors, semidx.runtime.index, semidx.runtime.literal-slice, semidx.runtime.retrieval, semidx.runtime.retrieval-policy, semidx.runtime.semantic-quality, semidx.runtime.snapshot-diff, semidx.runtime.storage, semidx.runtime.usage-metrics
- aliases: compression -> semidx.runtime.compression, errors -> semidx.runtime.errors, idx -> semidx.runtime.index, literal-slice -> semidx.runtime.literal-slice, retrieval -> semidx.runtime.retrieval, rp -> semidx.runtime.retrieval-policy, semantic-quality -> semidx.runtime.semantic-quality, snapshot-diff -> semidx.runtime.snapshot-diff, storage -> semidx.runtime.storage, usage -> semidx.runtime.usage-metrics
- symbols:
- now-ms [function]
- attach-runtime-context [function]
- resolve-usage-metrics [function]
- resolve-usage-context [function]
- resolve-policy-registry [function]
- should-record-usage? [function]
- request-trace-fields [function]
- error-payload [function]
### semidx.mcp.core
- path: src/semidx/mcp/core.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, semidx.core, semidx.runtime.errors, semidx.runtime.language-activation, semidx.runtime.retrieval-policy, semidx.runtime.storage, semidx.runtime.usage-metrics
- aliases: activation -> semidx.runtime.language-activation, errors -> semidx.runtime.errors, io -> clojure.java.io, json -> clojure.data.json, rp -> semidx.runtime.retrieval-policy, sci -> semidx.core, storage -> semidx.runtime.storage, str -> clojure.string, usage -> semidx.runtime.usage-metrics
- symbols:
- default-protocol-version [section]
- server-name [section]
- server-version [section]
- default-max-indexes [section]
- default-parser-opts [section]
- canonical-mcp-flow [section]
- mcp-first-usage-hint [section]
- mcp-retrieval-query-schema [section]
### semidx.mcp.http-server
- path: src/semidx/mcp/http_server.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, semidx.mcp.core, semidx.mcp.session-registry
- aliases: core -> semidx.mcp.core, io -> clojure.java.io, json -> clojure.data.json, sessions -> semidx.mcp.session-registry, str -> clojure.string
- symbols:
- default-host [section]
- ^:private [section]
- default-port [section]
- parse-args [function]
- request-method [function]
- request-uri [function]
- request-header [function]
- write-json! [function]
### semidx.mcp.server
- path: src/semidx/mcp/server.clj
- requires: clojure.data.json, clojure.string, semidx.core, semidx.mcp.core, semidx.runtime.retrieval-policy, semidx.runtime.usage-metrics
- aliases: core -> semidx.mcp.core, json -> clojure.data.json, rp -> semidx.runtime.retrieval-policy, sci -> semidx.core, str -> clojure.string, usage -> semidx.runtime.usage-metrics
- symbols:
- ^:private [section]
- default-max-indexes [section]
- parse-args [function]
- headers-complete? [function]
- header-terminator-length [function]
- read-header-block [function]
- read-json-line-text [function]
- read-next-byte [function]
### semidx.mcp.session-registry
- path: src/semidx/mcp/session_registry.clj
- requires: semidx.mcp.core
- aliases: core -> semidx.mcp.core
- symbols:
- default-session-ttl-ms [section]
- default-sse-poll-timeout-ms [section]
- close-sentinel [section]
- now-ms [function]
- queue [function]
- new-registry [function]
- expired-entry? [function]
- close-sse! [function]
### semidx.runtime.adapters
- path: src/semidx/runtime/adapters.clj
- requires: clojure.edn, clojure.java.io, clojure.java.shell, clojure.set, clojure.string, semidx.runtime.languages.typescript, semidx.runtime.semantic-ir
- aliases: edn -> clojure.edn, io -> clojure.java.io, semantic-ir -> semidx.runtime.semantic-ir, set -> clojure.set, sh -> clojure.java.shell, str -> clojure.string, ts-language -> semidx.runtime.languages.typescript
- symbols:
- clj-def-re [section]
- clj-call-re [section]
- clj-require-re [section]
- clj-require-alias-re [section]
- java-package-re [section]
- java-import-re [section]
- java-class-re [section]
- java-method-re [section]
### semidx.runtime.authz
- path: src/semidx/runtime/authz.clj
- requires: clojure.edn, clojure.java.io, clojure.string
- aliases: edn -> clojure.edn, io -> clojure.java.io, str -> clojure.string
- symbols:
- map-get [function]
- operation-name [function]
- canonical-path [function]
- path-prefix? [function]
- normalize-relative-path [function]
- normalize-path-prefix [function]
- path-allowed? [function]
- tenant-rules [function]
### semidx.runtime.benchmarks
- path: src/semidx/runtime/benchmarks.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, semidx.core
- aliases: checkout -> my.app.checkout, fulfillment -> my.app.fulfillment, io -> clojure.java.io, json -> clojure.data.json, order -> my.app.order, payments -> my.app.payments, sci -> semidx.core, str -> clojure.string
- symbols:
- read-json [function]
- write-file! [function]
- build-benchmark-repo! [function]
- parse-engine [function]
- parse-args [function]
- parser-opts-for [function]
- confidence-rank [section]
- raw-rank [section]
### semidx.runtime.cli
- path: src/semidx/runtime/cli.clj
- requires: clojure.data.json, clojure.java.io, semidx.core
- aliases: io -> clojure.java.io, json -> clojure.data.json, sci -> semidx.core
- symbols:
- parse-args [function]
- read-json [function]
- write-json [function]
- -main [function]
