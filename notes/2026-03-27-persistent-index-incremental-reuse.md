---
title: "Persistent Index And Incremental Reuse"
doc_type: "architecture_note"
lifecycle: "superseded"
agent_action: "historical_reference_only"
updated: "2026-08-02"
superseded_by:
  - plans/007_semidx_extension_architecture_resolution_plan.md
  - plans/008_stage_0_1_workspace_freshness.md
file_type: working-note
topic: persistent-index-and-incremental-reuse
created_at: 2026-03-27T01:45:00-0700
author: claude
language: en
status: superseded
follows: notes/2026-03-27-agent-change-detection.md
---

# Working Note: Persistent Index and Incremental Reuse

Follows from the change detection note. That note asked how to avoid full rescans. This note asks a bigger question: why rebuild the index at all when most of it has not changed?

## The Current Waste

Every time an agent enters this repo, `create_index` runs. Without a storage adapter, it:

1. Discovers all source files
2. Parses every file from scratch
3. Builds the full unit index, caller graph, diagnostics
4. Returns the result in memory
5. Discards everything when the process exits

Next conversation: repeat from step 1.

For this repo (~57 files, ~1838 units), a full rebuild takes a few seconds. For a larger codebase (thousands of files), this becomes a real bottleneck. Even at current scale, it is pure waste when only 2–3 files changed between sessions.

## What Already Exists

The infrastructure for persistent incremental indexing is already implemented but underused.

### Storage layer (`runtime/storage.clj`)

Two backends behind one protocol (`IndexStorage`):

| Backend | Persistence | Used by |
|---|---|---|
| `InMemoryStorage` | None — lost on process exit | Tests, MCP default |
| `PostgresStorage` | Full — survives across sessions | Available but not wired into default MCP/agent flow |

PostgreSQL schema stores:
- `semantic_index_snapshots` — full index payload per root_path + snapshot_id
- `semantic_index_units` — individual units with path, module, symbol, kind, line range, JSONB payload
- `semantic_index_call_edges` — caller/callee pairs per snapshot

### Index lifecycle (`runtime/index.clj`)

Already supports:
- `load_latest` — load last saved snapshot from storage instead of rebuilding
- `pinned_snapshot_id` — load a specific snapshot
- `max_snapshot_age_seconds` — auto-rebuild if snapshot is older than threshold
- `stale?` check — triggers rebuild when snapshot exceeds age limit
- `update-index` with `changed_paths` — incremental update: remove old units for those paths, reparse only those files, merge with existing index

### What is missing

The gap is not in storage or indexing. The gap is in **change detection** — the system does not know which files changed since the last snapshot. The caller must provide `changed_paths` explicitly. Nobody does.

## Proposed Flow: Persistent Incremental Index

### First visit to a repo (no prior snapshot)

```
1. create-index (full build)
2. save-index! to PostgresStorage
3. store current git HEAD commit hash alongside snapshot
4. agent works with index
```

### Subsequent visit (prior snapshot exists)

```
1. load-latest-index from PostgresStorage
2. detect changes:
   a. if git repo → git diff --name-only <stored-commit>..<current-HEAD>
   b. if not git → filesystem scan: compare mtime/size against stored file metadata
3. if no changes → use loaded index as-is (zero rebuild cost)
4. if changes exist → update-index with changed_paths (incremental rebuild)
5. save updated index + new commit hash
6. agent works with index
```

### Cost comparison

| Scenario | Current cost | With persistent incremental |
|---|---|---|
| Re-enter unchanged repo | Full rebuild (~57 files) | Load from DB + zero reparse |
| Re-enter, 3 files changed | Full rebuild (~57 files) | Load from DB + reparse 3 files |
| Re-enter, major refactor (30+ files) | Full rebuild (~57 files) | Load from DB + reparse 30 files (or full rebuild if delta > threshold) |
| First visit ever | Full rebuild | Full rebuild + save |

## Change Detection Mechanisms

### For git repos (most common case)

Store the commit hash of HEAD at index time. On re-entry:

```bash
git diff --name-only <stored-commit>..HEAD
```

This returns exactly the files that changed. Feed them to `update-index` as `changed_paths`.

For uncommitted changes (agent wrote code but did not commit):

```bash
git diff --name-only HEAD
git ls-files --others --exclude-standard
```

Combine committed delta + uncommitted delta + untracked files = complete `changed_paths`.

Total cost: 1–2 shell commands, ~50–100 tokens.

### For non-git repos

Store per-file metadata at index time:
- path
- mtime (last modification timestamp)
- size (bytes or line count)
- content hash (optional, for accuracy)

On re-entry, scan the file tree and compare:
- new files (path exists in tree but not in stored metadata) → add to changed_paths
- deleted files (path in stored metadata but not in tree) → remove from index
- modified files (mtime or size differs) → add to changed_paths
- unchanged files (mtime + size match) → skip

This is essentially what git does internally, but without git's object store. The mtime+size heuristic is what `make` and most build systems use — fast, mostly correct.

Total cost: 1 filesystem scan + comparison against stored metadata. For small-to-medium repos, this is milliseconds.

### Hybrid: trust git for tracked files, scan for untracked

For repos that are git-managed but have generated or untracked files that matter:

```bash
# tracked file delta
git diff --name-only <stored-commit>..HEAD

# untracked files that match source extensions
git ls-files --others --exclude-standard -- '*.clj' '*.ex' '*.py' '*.ts' '*.java'
```

## Where This Fits in the SCI Architecture

### Storage protocol

No changes needed. `PostgresStorage` already implements `save-index!`, `load-latest-index`, `load-index-by-snapshot`.

### Index lifecycle

`update-index` already accepts `changed_paths` and does incremental merge. The only addition needed:

1. **Store commit hash (or file metadata snapshot) alongside the index.** Currently `semantic_index_snapshots` stores `root_path`, `snapshot_id`, `indexed_at`, and `payload`. Add a `change_ref` field (git commit hash, or a JSON blob of file metadata for non-git repos).

2. **A `detect-changes` function** that compares stored `change_ref` against current repo state and returns `changed_paths`. This is the missing link.

3. **Wire `detect-changes` into `create-index`** as an alternative to full rebuild when storage has a recent snapshot.

### MCP layer

`create_index` MCP tool currently always calls `idx/create-index` which rebuilds or loads from storage based on `load_latest`/`pinned_snapshot_id`. The MCP layer could:

1. Default to `load_latest: true` when `PostgresStorage` is configured
2. Run `detect-changes` against the loaded snapshot
3. Call `update-index` with the delta if changes exist
4. Return the updated index with `rebuild_reason: "incremental_update"` or `"no_changes"`

The agent would see `cache_hit: true, rebuild_reason: "no_changes"` and know the index is fresh without a full rebuild.

## What This Means for Agent Exploration

If persistent incremental indexing works, Tier 0 (`wc -l` scan) becomes less important:

- The index already knows file sizes (line counts are computed during parsing)
- `repo_map` could expose `line_count` per file for free
- Change detection tells the agent which files were recently modified (and might have grown)

The previous notes proposed `wc -l` and `git diff --stat` as agent-side workarounds for the lack of persistent state. Persistent indexing solves the root cause: the system itself maintains an up-to-date map of the repo, and the agent queries it instead of scanning the filesystem.

## SQLite as a Simplification Option

PostgreSQL requires a running server, connection config, credentials. For local agent use this is heavy. A lighter option:

- **SQLite** — single file, zero config, ships with most systems
- Same schema, same protocol implementation, just a different JDBC driver
- The index DB file lives in the repo (`.sci/index.db`) or in a user-level cache directory
- No server management, no credentials, no network

This would make persistent indexing the default for all local agent sessions, not just server deployments.

### Tradeoff: no vector search

PostgreSQL is not here just for key-value storage. It enables `pgvector` and vector similarity search for embedding-based semantic retrieval. SQLite has no native vector extension with equivalent capabilities. Switching to SQLite means giving up the vector search path entirely.

This makes SQLite viable only as a lightweight local cache for structural index data (units, call edges, file metadata), not as a full replacement for the PostgreSQL backend. If the project moves toward embedding-based retrieval, PostgreSQL remains mandatory for that surface.

## Open Questions

1. Should `change_ref` be stored in the existing `payload` JSONB or as a separate column?
2. For git repos, should the system store just HEAD commit hash, or also the tree hash (which captures exact file state regardless of commit history)?
3. Should incremental update have a threshold (e.g., if >50% of files changed, just do a full rebuild instead of incremental merge)?
4. Is SQLite worth adding as a third storage backend, or is the PostgresStorage + InMemoryStorage split sufficient?
5. Should the MCP `create_index` tool automatically use persistent storage when available, or should this remain opt-in?
6. For non-git repos, what is the right granularity for change detection — mtime+size (fast, sometimes wrong) or content hash (accurate, slower)?
