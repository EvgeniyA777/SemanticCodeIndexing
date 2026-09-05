---
title: "Guard Denied Non-Code Files Under src/ While Letting Bare src Through, and repo_map's Envelope Outweighs Its Payload on Inventory Questions"
doc_type: "bug_report"
lifecycle: "active"
status: "in_progress"
agent_action: "reference_for_context"
updated: "2026-08-29"
---

# Guard denied non-code files under `src/` while letting bare `src` through, and `repo_map`'s envelope outweighs its payload on inventory questions

Session context: a **documentary intake task** in `/Users/ae/workspace/ReaderLens`
— reading a new coursework assignment (`intake/task-5-4-rest-api-and-search.md`)
and analysing it against the repository's own contracts to produce a progress
log. Roughly 85% of the reading was Markdown (`SPEC.md`, `AGENTS.md`,
`docs/status/current.md`, `RULES.md`), plus SQL (a Flyway migration), YAML
(runtime configuration), and XML (`pom.xml`). The only genuine code question was
an **absence** question: does any web layer exist yet?

Two separate targets. Items 1-2 are the `semidx-first` PreToolUse hook at
`/Users/ae/.claude/hooks/semidx-guard.py`, **not** the MCP server; they are
**fixed** (see Resolution). Item 3 is the server's `repo_map` response shape and
remains **open**. Items 4-5 are a task-fit observation and what worked.

## Item 1 (hook defect, fixed): `SRC_RE` was extension-blind, so `src/` denied SQL, YAML, and properties

The guard denied a `Bash` search when `has_code_ext(cmd)` **or**
`SRC_RE.search(cmd)` matched. `SRC_RE` was `(?:^|[\s/'\"])src/`, which fired on
any path under `src/` regardless of extension. In a Spring project
`src/main/resources/` holds Flyway migrations, YAML, and properties — none of
which semidx indexes. The guard therefore blocked raw searches on content it
cannot itself serve, the exact case its own docstring exempts ("intentionally
does NOT block grep over logs, config, JSON(L), docs").

### Repro

```sh
grep -n -i 'index' src/main/resources/db/migration/V1__create_reader_lens_schema.sql
```

- `GREP_RE` matches `grep`.
- `has_code_ext(cmd)` is **False** — the only extension is `sql`.
- `SRC_RE` matches ` src/` → `deny()`.

Expected: allowed. `.sql` is not a supported language; `active_languages` for
this index is `["java"]` only, and `create_index` reported 116 files / 721 units
with no SQL among them.
Actual: denied, recommending `resolve_context` / `impact_analysis` for a file
semidx does not index. Re-running with the `semidx-ok` marker succeeded and
returned the three index definitions at lines 84-86 of the migration.

The same denial applied to `grep -n 'ddl-auto' src/main/resources/application.yml`
and `grep -rn 'bookrec' src/test/resources/`, both equally non-indexed. The
`Grep`/`Glob` branch had the identical defect through `SRC_PATH_RE`: `path:
"src/main/resources"` with `glob: "*.sql"` was denied.

## Item 2 (hook defect, fixed): bare `src` escaped the guard entirely

Found while testing the fix for item 1, and it corrects a claim in this note's
first revision. Both patterns required a **trailing slash**, so a path written
without one never matched:

```sh
find src -type d      # allowed by the original guard
find src -type f      # allowed by the original guard
```

This is pure repository orientation — `repo_map`'s job — and it passed straight
through. It also means the denial I hit in this session did **not** come from
the `find src -type d` part of my compound command, as the first revision of
this note stated, but from `find src/main -name '*.java'` in the same command,
which matched `has_code_ext`. The corrected attribution matters for item 2b.

## Item 2b (usage limitation, not patched): a compound command is judged whole

```sh
sed -n '80,200p' docs/status/current.md; find src/main -name '*.java'
```

The `*.java` filter denies the whole command, including the Markdown read the
guard has no interest in. This is inherent to PreToolUse's all-or-nothing
decision and cannot be fixed inside the hook without parsing shell grammar.
Kept as a usage lesson — do not chain a doc read with a code search — and now
stated in the guard's `REASON` text so the next agent understands why an
apparently innocent read was refused.

## Resolution of items 1 and 2 (applied 2026-08-29)

Patched in place at `/Users/ae/.claude/hooks/semidx-guard.py`; the pre-patch
file is preserved as `semidx-guard.py.bak` (`~/.claude` is not version
controlled, so that copy is the only rollback). The fail-open design is intact:
garbage stdin, an empty payload, and a non-matching tool all still exit `0`.

Final rule set for the `Bash` branch, in order:

1. No search verb (`rg|grep|egrep|fgrep|ag|ack|find|fd`) → allow.
2. `semidx-ok` anywhere in the input → allow.
3. Any **code** extension named anywhere → deny.
4. An explicit name filter (`-name`, `-iname`, `--include`, `--glob`, `-g`)
   naming only non-code extensions → allow, even under `src`.
5. Otherwise, collect every whitespace/quote-separated token matching
   `(?:^|/)src\b` and deny unless **every** one of them is either under
   `src/{main,test}/resources` or ends in a non-code extension.

Step 5 is deliberately **per path**, not over the union of the command's
extensions. An earlier draft of the fix checked the whole command and thereby
introduced a new hole: `grep -rn foo src/main/java/ README.md` was allowed,
because the `.md` argument made every extension in the command non-code. Step 4
exists to keep `find src -name '*.md'` allowed, since a name filter states what
the search is for while a bare path argument does not.

`NON_CODE_EXTS` = `sql, yml, yaml, properties, xml, json, jsonl, md, txt, csv,
tsv, toml, ini, conf, sh`.

Verified with a 26-case behaviour matrix that runs the pre-patch and post-patch
hooks side by side and asserts the intended outcome for each; it is installed
next to the hook as `/Users/ae/.claude/hooks/semidx-guard-matrix.py` and exits
non-zero on any mismatch. All 26 pass. Eight cases changed behaviour: five
non-code searches under `src/` moved deny → allow, and three orientation
searches on bare `src` moved allow → deny. Every code-discovery case
(`*.java` glob, `type: java`, `path: src/main/java`, a bare repo-wide `Grep`,
an absolute path into `src/main/java`) still denies, and the `semidx-ok` escape
still works.

## Item 3 (cost, server, open): `repo_map`'s envelope repeats `create_index` and dominates a file-inventory answer

The one code question in this task — "is there any controller / DTO / exception
handler yet?" — is answered by the file inventory. `repo_map` supplies it, but
the response carries a fixed envelope that `create_index` already returned
verbatim in the same session, seconds earlier:

- `index_lifecycle` in full, including `repo_identity` (repo/workspace keys, git
  branch, commit, dirty flag), `lifecycle_diagnostics`, `provenance`,
  `rebuild_reason`
- `usage_hint` (a paragraph telling the caller to use semidx, inside a semidx
  response)
- `recommended_flow`, `recommended_next_step`, `projection_profile`,
  `recommended_projection_profile`, `snapshot_id`, `indexed_at`, `summary`

### Repro

```json
{"index_id":"01e67017-f7bb-4f46-8b73-0080f3cda1f0","max_files":40}
```
then, to get the rest of the inventory:
```json
{"index_id":"01e67017-f7bb-4f46-8b73-0080f3cda1f0","max_files":120,"max_modules":1}
```

`max_modules: 1` successfully shrank the `modules` array, and the envelope was
reproduced in full in both responses. There is no parameter that says "files
only". Two calls were needed because the first `max_files: 40` truncated a
116-file repository without flagging it in the `files` field; the `summary` line
("Indexed 116 files and 721 units") is what revealed it.

Expected: on a repeat call within a live `index_id`, the lifecycle/identity
block is either omitted or reduced to a changed/unchanged marker.
Actual: full envelope every time.

Measured effect: the answer needed was a flat path list that
`find src -name '*.java'` returns in roughly 1k tokens. Two `repo_map` calls
cost roughly 3k for the same inventory. That is the opposite direction from the
skill's headline "≈3.5k vs ≈40k" claim — a fair claim about *understanding*
code, but it does not hold for *listing* it.

### Suggested improvements

1. A projection parameter on `repo_map`, e.g. `include: ["files"]` or
   `projection_profile: "files_only"`, that returns paths and nothing else.
2. Emit the `index_lifecycle` / `repo_identity` block only when it changed since
   the last response for that `index_id`, or behind an opt-in flag; a
   `lifecycle: "unchanged"` scalar would carry the same information.
3. Drop `usage_hint` from responses after the first one in a session — the
   caller demonstrably already uses semidx.
4. State truncation explicitly (`files_truncated: true`, `files_total: 116`)
   so the caller does not have to infer it from `summary`.

## Item 4 (task fit, no server action)

For a documentary/intake task, semidx has nothing to offer on the material that
carries the analysis, and that is expected — Markdown, SQL, YAML, and XML are
outside its languages. The friction was not that semidx was unhelpful there; it
was that the guard, which exists to enforce semidx-first, stood between the
agent and non-indexed content. Items 1-2 remove that interaction.

## Item 5 (what worked, for balance)

`create_index` + `repo_map` settled an **absence** claim across the whole tree:
no controller, no DTO, no `@RestControllerAdvice` anywhere in 116 indexed files.
That claim now stands in a committed progress log
(`docs/reports/004_task_5_4_rest_api_and_search_progress_log.md` in ReaderLens,
commit `7bff5bd`). A complete inventory is a better basis for "X does not exist"
than any number of greps that failed to find it, and that is the one place
semidx was clearly the right tool in this session. `create_index` also correctly
reported `active_languages: ["java"]` and a clean `git_dirty: false` identity
for the analysed commit `0734998`.

## Environment

- semidx index: `01e67017-f7bb-4f46-8b73-0080f3cda1f0`, snapshot
  `7c31b494-aff3-46e0-96c0-8289e24d3ff7`, indexed 2026-08-29, 116 files / 721
  units, `active_languages: ["java"]`, `lifecycle_reason: "initial_build"`.
- Repo: `/Users/ae/workspace/ReaderLens` at `0734998` on `dev`.
- Java's confidence ceiling is `medium`; no `resolve_context` call was made in
  this session, so nothing here is a ranking or confidence complaint.
- Guard: `/Users/ae/.claude/hooks/semidx-guard.py`, wired in
  `/Users/ae/.claude/settings.json` under `PreToolUse` matcher `Grep|Glob|Bash`.
