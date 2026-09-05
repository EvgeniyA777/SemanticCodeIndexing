# Real SCIP reference artifacts (plans/018 Stages 3 and 4)

Committed artifacts produced by the pinned SCIP toolchains over the protected
corpora. Each replaces the *representative* SCIP spellings the Stage 0 identity
fixtures were seeded with (see `../identity/`), which the plan required each
stage to re-verify against real tool output.

## TypeScript (Stage 3)

Two artifacts, both produced by the pinned `@sourcegraph/scip-typescript`
over `fixtures/provider-authority/corpus/typescript/`:

- `typescript-corpus.observed.json` — the decoded index as reviewable JSON.
- `typescript-corpus.scrubbed.scip` — the raw protobuf index, with
  `metadata.project_root` cleared. This is the binary fixture the JVM SCIP
  reader tests (`semidx.runtime.scip-test`) read. `project_root` is an absolute
  `file://` path on the indexing machine, so it must not be committed verbatim;
  everything else (documents, symbols, occurrences) is byte-preserved.

### Regenerating

```sh
./scripts/setup-scip-typescript.sh           # npm ci from scripts/scip-toolchain/package-lock.json
./scripts/scip-typescript-corpus-snapshot.sh # writes both typescript-corpus.observed.json and .scrubbed.scip
```

The pin is the committed lockfile `scripts/scip-toolchain/package-lock.json`, so
both `scip-typescript` (0.4.0) and its transitive `typescript` (5.9.3) are fixed
on a clean checkout. Both scripts fail closed on version drift.

The decoder (`scripts/lib/decode-scip.js`) and the scrubber
(`scripts/lib/scrub-scip.js`) use scip-typescript's own bundled protobuf module,
so regenerating these artifacts needs no separate protobuf toolchain. The JVM
SCIP reader (`semidx.runtime.scip`) instead parses `.scip` with Java stubs
generated from `proto/scip/scip.proto` by the repo-managed protoc toolchain
(ADR-042); it does not depend on these scripts or on the bundled JS module.

### What real scip-typescript@0.4.0 does with the corpus

- **Symbol grammar**: `scip-typescript npm <name> <version> <descriptor>` where
  a local project has `.` `.` for name/version and file components are wrapped
  in backticks, e.g. `scip-typescript npm . . src/\`orders.ts\`/normalize().`.
  Params get their own symbol (`normalize().(value)`), constructors are
  `OrderService#\`<constructor>\`().`, fields are `OrderService#validator.`.
- **`src/index.ts` re-export surface**: the only symbol emitted for `index.ts`
  is the module itself. `export { normalize as canonicalize } from './orders'`
  produces **no** `canonicalize` symbol and **no** SCIP `Relationship`. Both the
  `normalize` and the `canonicalize` tokens are non-definition occurrences that
  resolve to the origin symbol `src/\`orders.ts\`/normalize().`. The re-export
  edge is recoverable only from occurrence resolution on the export statement.
- **External symbols**: `index.external_symbols` is empty; stdlib references
  appear inline as occurrences
  (`scip-typescript npm typescript 5.9.3 lib/\`lib.es5.d.ts\`/String#trim().`).

Field-level captures are host-stripped: `metadata.project_root` (an absolute
`file://` URI) is dropped by the decoder so the artifact is machine-independent.

## Java (Stage 4)

- `java-corpus.scrubbed.scip` — the raw protobuf index over
  `fixtures/provider-authority/corpus/java/`, with `metadata.project_root`
  cleared. Produced by `semanticdb-javac` 0.12.3 plus `scip-semanticdb` 0.12.3.

There is no `java-corpus.observed.json`: the JVM reader
(`semidx.runtime.scip/read-index`) reads the `.scip` directly, so a second
decoded copy would be a redundant artifact to keep in sync.

### Regenerating

```sh
./scripts/setup-scip-java.sh            # pinned jars + compiled driver into .scip-java-toolchain/
./scripts/scip-java-corpus-snapshot.sh  # writes java-corpus.scrubbed.scip
```

The pin is `scripts/scip-java-toolchain/dependencies.txt` (coordinates plus
sha256 per jar), verified on download and re-verified by the snapshot script;
both fail closed on drift. Unlike the TypeScript path there is no CLI: the
committed driver `scripts/scip-java-toolchain/ScipJavaIndexer.java` calls
`ScipSemanticdb.run`, because `scip-semanticdb` ships no entry point and the
full `scip-java` Scala CLI would drag in coursier and an embedded Kotlin
compiler. The driver also owns the `--scrub-project-root` step, so Java fixture
regeneration does not depend on the TypeScript toolchain's JS scrubber.

No build tool, `pom.xml`, or coursier is involved — a plain directory of
`.java` sources is compiled with `javac` and the SemanticDB plugin.

### What real scip-java 0.12.3 does with the corpus

This is the ground truth that corrected the Stage 0 Java identity fixture; see
`../identity/java-overload-canonical-key.json` → `scip_java_verified_contract`.

- **Symbol grammar**: the scheme is `semanticdb`, not `scip-java`, e.g.
  `semanticdb maven . . example/OrderService#handle().`. Descriptors carry the
  **package** path (`example/`), not the source file path — unlike TypeScript,
  where the moniker reconstructs the file. The file path must therefore come
  from `Document.relative_path`.
- **Overloads carry a source-order ordinal, not types**: the first declaration
  of a name is `handle().`, the second `handle(+1).`. The ordinal counts over
  the method name across all arities. Neither parameter types nor arity appear
  in the symbol.
- **Arity and signature come only from `signature_documentation.text`**, e.g.
  `public String handle(String order, int retries)` — simple type names, with
  parameter names included.
- **Fully-qualified parameter types** appear only as separate occurrences inside
  the method's declaration range (`semanticdb maven jdk 17 java/lang/String#`),
  so reconstructing a typed signature is inference, not a provider claim.
- **Constructors** are `` OrderService#`<init>`(). ``; fields are
  `OrderService#validator.`; method-local variables and parameters are
  `local N`.
- **External symbols**: `index.external_symbols` is empty; JDK references appear
  inline as occurrences with a non-`.` package name
  (`semanticdb maven jdk 17 java/lang/String#trim().`).
