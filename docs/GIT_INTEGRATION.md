# Git Integration

The Taxonomy Architecture Analyzer uses **JGit** to provide full Git version control for Architecture DSL documents. The Git repository is stored in the application database (no filesystem required), giving you branching, commit history, diff, merge, and cherry-pick capabilities for your architecture models.

## Table of Contents

- [Overview](#overview)
- [Repository Architecture](#repository-architecture)
- [Branching](#branching)
- [Commit History](#commit-history)
- [Diff and Comparison](#diff-and-comparison)
- [Cherry-Pick](#cherry-pick)
- [Merge](#merge)
- [Conflict Detection](#conflict-detection)
- [Materialization](#materialization)
- [Staleness Tracking](#staleness-tracking)
- [Hypotheses Lifecycle](#hypotheses-lifecycle)
- [Commit History Search](#commit-history-search)
- [REST API Endpoints](#rest-api-endpoints)
- [Related Documentation](#related-documentation)

---

## Overview

Architecture DSL documents (`.taxdsl` files) are stored in a JGit DFS (Distributed File System) repository backed by HSQLDB tables (`git_packs`, `git_reflog`). Every change to the DSL creates a Git commit with author, timestamp, and commit message — providing a complete audit trail.

The Git state is exposed through the UI status bar and REST API, allowing you to monitor repository health, detect stale projections, and preview merge/cherry-pick operations before executing them.

---

## Repository Architecture

```
┌─────────────────────┐
│  DslGitRepository    │   JGit DFS repository
│  (database-backed)   │   Tables: git_packs, git_reflog
├─────────────────────┤
│  Branches            │   draft (default), main, feature/*
│  Commits             │   Full SHA, author, message, timestamp
│  Objects             │   Git blobs, trees, commits stored in DB
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│  RepositoryStateService  │   Tracks projection/index staleness
│  ConflictDetectionService│   Previews merge/cherry-pick
│  GitStateController      │   REST API for state queries
└─────────────────────┘
```

Key methods on `DslGitRepository`:

| Method | Purpose |
|---|---|
| `commitDsl(content, message, author)` | Create a new commit |
| `getDslAtHead(branch)` | Read current DSL content |
| `getDslAtCommit(sha)` | Read DSL at a specific commit |
| `getDslHistory(branch, limit)` | List commit history |
| `listBranches()` | List all branch names |
| `createBranch(name, startPoint)` | Create a new branch |
| `diffBetween(fromSha, toSha)` | Semantic diff between commits |
| `diffBranches(from, to)` | Diff between branch HEADs |
| `textDiff(fromSha, toSha)` | Unified text diff |
| `cherryPick(commitId, targetBranch)` | Port a single commit |
| `merge(fromBranch, intoBranch)` | Three-way merge |

---

## Branching

The default branch is `draft`. You can create feature branches to experiment with architecture changes without affecting the main branch.

```
POST /api/dsl/branches
{
  "name": "feature/new-service",
  "startPoint": "draft"
}
```

The active branch for materialization is configured via the `dsl.default-branch` preference (see [Preferences](PREFERENCES.md)).

---

## Commit History

Every DSL change creates a Git commit with:

- **SHA** — Unique commit identifier
- **Author** — Authenticated user who made the change
- **Message** — Description of the change
- **Timestamp** — When the commit was created

View commit history:

```
GET /api/dsl/history?branch=draft&limit=20
```

---

## Diff and Comparison

Two diff modes are available:

| Mode | Endpoint | Output |
|---|---|---|
| **Semantic** | `GET /api/dsl/diff?from={sha}&to={sha}` | Structured JSON showing added, removed, and changed elements and relations |
| **Unified text** | `GET /api/dsl/text-diff?from={sha}&to={sha}` | Standard unified diff format (patch) |

You can also diff between branches:

```
GET /api/dsl/diff-branches?from=draft&to=main
```

---

## Cherry-Pick

Port a specific commit from one branch to another:

```
POST /api/dsl/cherry-pick
{
  "commitId": "abc1234...",
  "targetBranch": "draft"
}
```

The operation uses three-way merge logic internally. Use the preview endpoint first to check for conflicts (see [Conflict Detection](#conflict-detection)).

---

## Merge

Combine two branches using three-way merge:

```
POST /api/dsl/merge
{
  "fromBranch": "feature/new-service",
  "intoBranch": "draft"
}
```

The merge strategy is RECURSIVE (standard Git behaviour). Fast-forward merges are performed when the target branch is a direct ancestor of the source.

---

## Conflict Detection

Before executing a merge or cherry-pick, you can preview the operation to check for conflicts:

### Merge Preview

```
GET /api/dsl/merge/preview?from=feature/new-service&into=draft
```

Response:

```json
{
  "canMerge": true,
  "fromBranch": "feature/new-service",
  "intoBranch": "draft",
  "fromCommit": "abc1234...",
  "intoCommit": "def5678...",
  "alreadyMerged": false,
  "fastForwardable": true,
  "warnings": []
}
```

### Cherry-Pick Preview

```
GET /api/dsl/cherry-pick/preview?commitId=abc1234&branch=draft
```

Response:

```json
{
  "canCherryPick": true,
  "commitId": "abc1234...",
  "targetBranch": "draft",
  "targetCommit": "def5678...",
  "warnings": []
}
```

### Operation Safety Check

```
GET /api/dsl/operation/check?branch=draft
```

The `RepositoryStateGuard` checks whether a write operation is safe to proceed on the given branch.

---

## Materialization

DSL documents are **materialized** into the application database. This creates `TaxonomyRelation` entities from DSL relations, making them visible in the Graph Explorer, Relation Proposals, and Architecture View.

Two materialization modes are available:

| Mode | Endpoint | Description |
|---|---|---|
| **Full** | `POST /api/dsl/materialize` | Replaces all relations with DSL content |
| **Incremental** | `POST /api/dsl/materialize-incremental` | Applies only the delta between two versions |

After materialization, the `RepositoryStateService` records the projection commit to track whether the database is in sync with the Git HEAD.

---

## Staleness Tracking

The system tracks whether the database projection and search index are in sync with the Git HEAD:

| Field | Meaning |
|---|---|
| `projectionStale` | Database relations differ from Git HEAD |
| `indexStale` | Search index differs from Git HEAD |

**Staleness logic:** If the last materialized commit SHA matches the current HEAD commit SHA, the projection is **not stale**. Otherwise, it is stale and should be re-materialized.

Query staleness:

```
GET /api/git/stale?branch=draft
```

Response:

```json
{
  "projectionStale": false,
  "indexStale": false
}
```

The UI polls `/api/git/state` every 10 seconds to display a status indicator when the projection is stale.

---

## Hypotheses Lifecycle

Relations generated during LLM analysis are stored as **hypotheses** — provisional relations that require human review before becoming permanent:

```
PENDING  →  ACCEPTED  (creates TaxonomyRelation)
         →  REJECTED  (marked as rejected)
         →  APPLIED   (session-only, not persisted)
```

The Hypotheses API (`/api/dsl/hypotheses`) allows querying, accepting, and rejecting hypotheses, with supporting evidence available for each.

---

## Commit History Search

Commit history is indexed into Hibernate Search for full-text search. You can:

- Search across all commit messages and change content
- Find all commits that affected a specific element
- Find all commits that affected a specific relation
- View aggregated change history for an element

---

## REST API Endpoints

### Git State

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/git/state?branch=draft` | Full repository state snapshot |
| `GET` | `/api/git/projection?branch=draft` | Projection/index freshness |
| `GET` | `/api/git/branches?branch=draft` | All branches with HEAD commits |
| `GET` | `/api/git/stale?branch=draft` | Quick staleness check |

### DSL Operations

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/dsl/materialize` | Full materialization |
| `POST` | `/api/dsl/materialize-incremental` | Incremental materialization |
| `POST` | `/api/dsl/merge` | Merge branches |
| `POST` | `/api/dsl/cherry-pick` | Cherry-pick a commit |
| `GET` | `/api/dsl/merge/preview` | Preview merge result |
| `GET` | `/api/dsl/cherry-pick/preview` | Preview cherry-pick result |
| `GET` | `/api/dsl/operation/check` | Safety check for write operations |

See [API Reference](API_REFERENCE.md) for full request/response schemas.

---

## Related Documentation

- [User Guide](USER_GUIDE.md) — Architecture DSL section (§11g)
- [Architecture](ARCHITECTURE.md) — DSL storage architecture
- [Concepts](CONCEPTS.md) — DSL, hypotheses, and the canonical model
- [Preferences](PREFERENCES.md) — Configuring `dsl.default-branch` and remote push settings
- [Framework Import](FRAMEWORK_IMPORT.md) — How imported files are stored as DSL documents
