# Code Context: SemanticCodeIndexing

- fingerprint: 3757e24f6c5118ddb2646e3a86b4848000dc5410

## Tree
```text
SemanticCodeIndexing
├ deps.edn
├ src
│ └ semantic_code_indexing
│   ├ contracts
│   ├ core.clj
│   ├ mcp
│   ├ runtime
│   └ test_runner.clj
└ test
  └ semantic_code_indexing
    ├ compression_test.clj
    ├ evaluation_test.clj
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
- semantic-code-indexing.contracts.cli/-main
- semantic-code-indexing.runtime.cli/-main
- semantic-code-indexing.runtime.compression-cli/-main
- semantic-code-indexing.test-runner/-main

## Namespace Categories
### app/core
- semantic-code-indexing.core
- semantic-code-indexing.mcp.core
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
- semantic-code-indexing.compression-test
- semantic-code-indexing.evaluation-test
- semantic-code-indexing.mcp-http-server-test
- semantic-code-indexing.mcp-server-test
- semantic-code-indexing.policy-governance-test
- semantic-code-indexing.project-context-test
- semantic-code-indexing.runtime-grpc-test
- semantic-code-indexing.runtime-http-test
- ... +3 more
### other
- semantic-code-indexing.contracts.cli
- semantic-code-indexing.contracts.schemas
- semantic-code-indexing.contracts.validator
- semantic-code-indexing.mcp.http-server
- semantic-code-indexing.mcp.server
- semantic-code-indexing.mcp.session-registry
- semantic-code-indexing.runtime.adapters
- semantic-code-indexing.runtime.authz
- ... +23 more

## Domain Model
- IndexStorage [protocol]
- UsageMetricsSink [protocol]
- InMemoryStorage [record]
- InMemoryUsageMetrics [record]
- NoOpUsageMetrics [record]
- PostgresStorage [record]
- PostgresUsageMetrics [record]

## Dependency Graph
- semantic-code-indexing.compression-test -> clojure.java.io
- semantic-code-indexing.compression-test -> clojure.string
- semantic-code-indexing.compression-test -> clojure.test
- semantic-code-indexing.compression-test -> semantic-code-indexing.core
- semantic-code-indexing.compression-test -> semantic-code-indexing.runtime.compression
- semantic-code-indexing.contracts.cli -> semantic-code-indexing.contracts.validator
- semantic-code-indexing.contracts.validator -> clojure.data.json
- semantic-code-indexing.contracts.validator -> clojure.java.io
- semantic-code-indexing.contracts.validator -> clojure.string
- semantic-code-indexing.contracts.validator -> malli.core
- semantic-code-indexing.contracts.validator -> malli.error
- semantic-code-indexing.contracts.validator -> semantic-code-indexing.contracts.schemas
- semantic-code-indexing.core -> semantic-code-indexing.runtime.compression
- semantic-code-indexing.core -> semantic-code-indexing.runtime.errors
- semantic-code-indexing.core -> semantic-code-indexing.runtime.index
- semantic-code-indexing.core -> semantic-code-indexing.runtime.retrieval
- semantic-code-indexing.core -> semantic-code-indexing.runtime.retrieval-policy
- semantic-code-indexing.core -> semantic-code-indexing.runtime.storage
- semantic-code-indexing.core -> semantic-code-indexing.runtime.usage-metrics
- semantic-code-indexing.evaluation-test -> clojure.java.io
- ... +172 more

## Namespaces
### semantic-code-indexing.contracts.cli
- path: src/semantic_code_indexing/contracts/cli.clj
- requires: semantic-code-indexing.contracts.validator
- aliases: validator -> semantic-code-indexing.contracts.validator
- symbols:
- -main [function]
### semantic-code-indexing.contracts.schemas
- path: src/semantic_code_indexing/contracts/schemas.clj
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
### semantic-code-indexing.contracts.validator
- path: src/semantic_code_indexing/contracts/validator.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, malli.core, malli.error, semantic-code-indexing.contracts.schemas
- aliases: io -> clojure.java.io, json -> clojure.data.json, m -> malli.core, me -> malli.error, schemas -> semantic-code-indexing.contracts.schemas, str -> clojure.string
- symbols:
- example-root [section]
- fixture-root [section]
- ^:private [section]
- schema-root [section]
- read-json-file [function]
- json-files-under [function]
- rel-path [function]
- schema-key-for-path [function]
### semantic-code-indexing.core
- path: src/semantic_code_indexing/core.clj
- requires: semantic-code-indexing.runtime.compression, semantic-code-indexing.runtime.errors, semantic-code-indexing.runtime.index, semantic-code-indexing.runtime.retrieval, semantic-code-indexing.runtime.retrieval-policy, semantic-code-indexing.runtime.storage, semantic-code-indexing.runtime.usage-metrics
- aliases: compression -> semantic-code-indexing.runtime.compression, errors -> semantic-code-indexing.runtime.errors, idx -> semantic-code-indexing.runtime.index, retrieval -> semantic-code-indexing.runtime.retrieval, rp -> semantic-code-indexing.runtime.retrieval-policy, storage -> semantic-code-indexing.runtime.storage, usage -> semantic-code-indexing.runtime.usage-metrics
- symbols:
- now-ms [function]
- attach-runtime-context [function]
- resolve-usage-metrics [function]
- resolve-usage-context [function]
- resolve-policy-registry [function]
- should-record-usage? [function]
- request-trace-fields [function]
- error-payload [function]
### semantic-code-indexing.mcp.core
- path: src/semantic_code_indexing/mcp/core.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, semantic-code-indexing.core, semantic-code-indexing.runtime.errors, semantic-code-indexing.runtime.language-activation, semantic-code-indexing.runtime.retrieval-policy, semantic-code-indexing.runtime.usage-metrics
- aliases: activation -> semantic-code-indexing.runtime.language-activation, errors -> semantic-code-indexing.runtime.errors, io -> clojure.java.io, json -> clojure.data.json, rp -> semantic-code-indexing.runtime.retrieval-policy, sci -> semantic-code-indexing.core, str -> clojure.string, usage -> semantic-code-indexing.runtime.usage-metrics
- symbols:
- default-protocol-version [section]
- server-name [section]
- server-version [section]
- default-max-indexes [section]
- default-parser-opts [section]
- canonical-mcp-flow [section]
- mcp-first-usage-hint [section]
- mcp-retrieval-query-schema [section]
### semantic-code-indexing.mcp.http-server
- path: src/semantic_code_indexing/mcp/http_server.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, semantic-code-indexing.mcp.core, semantic-code-indexing.mcp.session-registry
- aliases: core -> semantic-code-indexing.mcp.core, io -> clojure.java.io, json -> clojure.data.json, sessions -> semantic-code-indexing.mcp.session-registry, str -> clojure.string
- symbols:
- default-host [section]
- ^:private [section]
- default-port [section]
- normalized-allowed-roots [function]
- parse-args [function]
- request-method [function]
- request-uri [function]
- request-path [function]
### semantic-code-indexing.mcp.server
- path: src/semantic_code_indexing/mcp/server.clj
- requires: clojure.java.io, clojure.string, semantic-code-indexing.core, semantic-code-indexing.mcp.core, semantic-code-indexing.runtime.retrieval-policy, semantic-code-indexing.runtime.usage-metrics
- aliases: core -> semantic-code-indexing.mcp.core, io -> clojure.java.io, rp -> semantic-code-indexing.runtime.retrieval-policy, sci -> semantic-code-indexing.core, str -> clojure.string, usage -> semantic-code-indexing.runtime.usage-metrics
- symbols:
- ^:private [section]
- default-max-indexes [section]
- resolve-allowed-roots [function]
- parse-args [function]
- handle-tools-call [function]
- headers-complete? [function]
- header-terminator-length [function]
- read-header-block [function]
### semantic-code-indexing.mcp.session-registry
- path: src/semantic_code_indexing/mcp/session_registry.clj
- requires: semantic-code-indexing.mcp.core
- aliases: core -> semantic-code-indexing.mcp.core
- symbols:
- default-session-ttl-ms [section]
- default-sse-poll-timeout-ms [section]
- close-sentinel [section]
- now-ms [function]
- queue [function]
- new-registry [function]
- expired-entry? [function]
- close-sse! [function]
### semantic-code-indexing.runtime.adapters
- path: src/semantic_code_indexing/runtime/adapters.clj
- requires: clojure.edn, clojure.java.io, clojure.java.shell, clojure.set, clojure.string, semantic-code-indexing.runtime.languages.typescript, semantic-code-indexing.runtime.semantic-ir
- aliases: edn -> clojure.edn, io -> clojure.java.io, semantic-ir -> semantic-code-indexing.runtime.semantic-ir, set -> clojure.set, sh -> clojure.java.shell, str -> clojure.string, ts-language -> semantic-code-indexing.runtime.languages.typescript
- symbols:
- clj-def-re [section]
- clj-call-re [section]
- clj-require-re [section]
- clj-require-alias-re [section]
- java-package-re [section]
- java-import-re [section]
- java-class-re [section]
- java-method-re [section]
### semantic-code-indexing.runtime.authz
- path: src/semantic_code_indexing/runtime/authz.clj
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
### semantic-code-indexing.runtime.benchmarks
- path: src/semantic_code_indexing/runtime/benchmarks.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, semantic-code-indexing.core
- aliases: checkout -> my.app.checkout, fulfillment -> my.app.fulfillment, io -> clojure.java.io, json -> clojure.data.json, order -> my.app.order, payments -> my.app.payments, sci -> semantic-code-indexing.core, str -> clojure.string
- symbols:
- read-json [function]
- write-file! [function]
- build-benchmark-repo! [function]
- parser-opts-for [function]
- confidence-rank [section]
- raw-rank [section]
- extract-codes [function]
- subset-check [function]
### semantic-code-indexing.runtime.cli
- path: src/semantic_code_indexing/runtime/cli.clj
- requires: clojure.data.json, clojure.java.io, semantic-code-indexing.core
- aliases: io -> clojure.java.io, json -> clojure.data.json, sci -> semantic-code-indexing.core
- symbols:
- parse-args [function]
- read-json [function]
- write-json [function]
- -main [function]
