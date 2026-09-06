# Code Context: semidx

- fingerprint: 29460e7cec61abcffcf3b7be5a03bf2a38b977fc

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
    ├ integration
    ├ mcp
    ├ runtime
    ├ test_runner_test.clj
    └ test_support
```

## Entry Points
- semidx.contracts.cli/-main
- semidx.runtime.cli/-main
- semidx.runtime.compression-cli/-main
- semidx.runtime.grpc-launcher/-main
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
- semidx.integration.css-onboarding-test
- semidx.integration.freshness-baseline-test
- semidx.integration.freshness-regression-test
- semidx.integration.html-onboarding-test
- semidx.integration.javascript-onboarding-test
- semidx.integration.lua-onboarding-test
- semidx.integration.policy-governance-test
- semidx.integration.runtime-test
- ... +50 more
### other
- semidx.contracts.cli
- semidx.contracts.schemas
- semidx.contracts.validator
- semidx.mcp.http-server
- semidx.mcp.server
- semidx.mcp.session-registry
- semidx.runtime.adapters
- semidx.runtime.authz
- ... +74 more

## Domain Model
- ArmRunner [protocol]
- IndexStorage [protocol]
- UsageMetricsSink [protocol]
- InMemoryStorage [record]
- InMemoryUsageMetrics [record]
- LiveArmRunner [record]
- NoOpUsageMetrics [record]
- PostgresStorage [record]
- PostgresUsageMetrics [record]
- ProcessArmRunner [record]
- ScriptedArmRunner [record]

## Dependency Graph
- semidx.contracts.cli -> semidx.contracts.validator
- semidx.contracts.validator -> clojure.data.json
- semidx.contracts.validator -> clojure.java.io
- semidx.contracts.validator -> clojure.string
- semidx.contracts.validator -> malli.core
- semidx.contracts.validator -> malli.error
- semidx.contracts.validator -> semidx.contracts.schemas
- semidx.core -> semidx.runtime.capabilities
- semidx.core -> semidx.runtime.compression
- semidx.core -> semidx.runtime.errors
- semidx.core -> semidx.runtime.index
- semidx.core -> semidx.runtime.literal-slice
- semidx.core -> semidx.runtime.retrieval
- semidx.core -> semidx.runtime.retrieval-policy
- semidx.core -> semidx.runtime.semantic-quality
- semidx.core -> semidx.runtime.snapshot-diff
- semidx.core -> semidx.runtime.storage
- semidx.core -> semidx.runtime.usage-metrics
- semidx.integration.css-onboarding-test -> clojure.java.io
- semidx.integration.css-onboarding-test -> clojure.test
- ... +607 more

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
- unit-id [section]
### semidx.contracts.validator
- path: src/semidx/contracts/validator.clj
- requires: clojure.data.json, clojure.java.io, clojure.string, malli.core, malli.error, semidx.contracts.schemas
- aliases: io -> clojure.java.io, json -> clojure.data.json, m -> malli.core, me -> malli.error, schemas -> semidx.contracts.schemas, str -> clojure.string
- symbols:
- example-root [section]
- fixture-root [section]
- schema-root [section]
- read-json-file [function]
- json-files-under [function]
- rel-path [function]
- schema-key-for-path [function]
- validate-schema-json-file [function]
### semidx.core
- path: src/semidx/core.clj
- requires: semidx.runtime.capabilities, semidx.runtime.compression, semidx.runtime.errors, semidx.runtime.index, semidx.runtime.literal-slice, semidx.runtime.retrieval, semidx.runtime.retrieval-policy, semidx.runtime.semantic-quality, semidx.runtime.snapshot-diff, semidx.runtime.storage, semidx.runtime.usage-metrics
- aliases: capabilities -> semidx.runtime.capabilities, compression -> semidx.runtime.compression, errors -> semidx.runtime.errors, idx -> semidx.runtime.index, literal-slice -> semidx.runtime.literal-slice, retrieval -> semidx.runtime.retrieval, rp -> semidx.runtime.retrieval-policy, semantic-quality -> semidx.runtime.semantic-quality, snapshot-diff -> semidx.runtime.snapshot-diff, storage -> semidx.runtime.storage, usage -> semidx.runtime.usage-metrics
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
- requires: clojure.data.json, clojure.java.io, clojure.string, semidx.core, semidx.runtime.capabilities, semidx.runtime.errors, semidx.runtime.language-activation, semidx.runtime.language-registry, semidx.runtime.query-anchors, semidx.runtime.retrieval-policy, semidx.runtime.storage, semidx.runtime.usage-metrics
- aliases: activation -> semidx.runtime.language-activation, capabilities -> semidx.runtime.capabilities, errors -> semidx.runtime.errors, io -> clojure.java.io, json -> clojure.data.json, query-anchors -> semidx.runtime.query-anchors, registry -> semidx.runtime.language-registry, rp -> semidx.runtime.retrieval-policy, sci -> semidx.core, storage -> semidx.runtime.storage, str -> clojure.string, usage -> semidx.runtime.usage-metrics
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
- requires: clojure.data.json, clojure.java.io, clojure.string, semidx.core, semidx.mcp.core, semidx.mcp.session-registry, semidx.runtime.usage-metrics
- aliases: core -> semidx.mcp.core, io -> clojure.java.io, json -> clojure.data.json, sci -> semidx.core, sessions -> semidx.mcp.session-registry, str -> clojure.string, usage -> semidx.runtime.usage-metrics
- symbols:
- default-host [section]
- default-port [section]
- parse-args [function]
- request-method [function]
- request-uri [function]
- request-header [function]
- write-json! [function]
- read-json-body [function]
### semidx.mcp.server
- path: src/semidx/mcp/server.clj
- requires: clojure.data.json, clojure.string, semidx.core, semidx.mcp.core, semidx.runtime.retrieval-policy, semidx.runtime.usage-metrics
- aliases: core -> semidx.mcp.core, json -> clojure.data.json, rp -> semidx.runtime.retrieval-policy, sci -> semidx.core, str -> clojure.string, usage -> semidx.runtime.usage-metrics
- symbols:
- default-max-indexes [section]
- parse-args [function]
- headers-complete? [function]
- header-terminator-length [function]
- read-header-block [function]
- read-json-line-text [function]
- read-next-byte [function]
- parse-headers [function]
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
- requires: clojure.java.io, semidx.runtime.language-registry, semidx.runtime.languages.clojure, semidx.runtime.languages.css, semidx.runtime.languages.html, semidx.runtime.languages.java, semidx.runtime.languages.javascript, semidx.runtime.languages.lua, semidx.runtime.languages.python, semidx.runtime.languages.shared, semidx.runtime.languages.typescript, semidx.runtime.languages.zig, semidx.runtime.semantic-ir
- aliases: clj-language -> semidx.runtime.languages.clojure, css-language -> semidx.runtime.languages.css, html-language -> semidx.runtime.languages.html, io -> clojure.java.io, java-language -> semidx.runtime.languages.java, js-language -> semidx.runtime.languages.javascript, language-registry -> semidx.runtime.language-registry, lua-language -> semidx.runtime.languages.lua, py-language -> semidx.runtime.languages.python, semantic-ir -> semidx.runtime.semantic-ir, shared-language -> semidx.runtime.languages.shared, ts-language -> semidx.runtime.languages.typescript, zig-language -> semidx.runtime.languages.zig
- symbols:
- language-by-path [function]
- source-path? [function]
- with-parser-context [function]
- slurp-lines [function]
- trim-signature [function]
- fallback-unit [function]
- parse-elixir-language-file [function]
- parse-html [function]
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
### semidx.runtime.benchmark-agent
- path: src/semidx/runtime/benchmark_agent.clj
- requires: clojure.data.json, clojure.java.io, clojure.java.shell, clojure.string, semidx.core, semidx.runtime.benchmark-harness
- aliases: harness -> semidx.runtime.benchmark-harness, io -> clojure.java.io, json -> clojure.data.json, sci -> semidx.core, shell -> clojure.java.shell, str -> clojure.string
- symbols:
- agent-id [section]
- agent-build-id [section]
- adapter-id [section]
- api-base [section]
- limits [section]
- semidx-tools [section]
- lsp-tools [section]
- tool-declarations [section]
### semidx.runtime.benchmark-harness
- path: src/semidx/runtime/benchmark_harness.clj
- requires: clojure.data.json, clojure.java.io, clojure.java.shell, clojure.string, semidx.runtime.benchmark-suite, semidx.runtime.benchmark-usage, semidx.runtime.usage-metrics
- aliases: bu -> semidx.runtime.benchmark-usage, io -> clojure.java.io, json -> clojure.data.json, shell -> clojure.java.shell, str -> clojure.string, suite -> semidx.runtime.benchmark-suite, usage -> semidx.runtime.usage-metrics
- symbols:
- harness-version [section]
- task-prompt-policy-id [section]
- arm-policy-bundle-id [section]
- execution-budget-policy-id [section]
- cache-protocol-id [section]
- feedback-surface [section]
- feedback-operation [section]
- task-prompt-preamble [section]
