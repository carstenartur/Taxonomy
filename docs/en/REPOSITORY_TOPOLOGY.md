# Repository Topology & Workspace Provisioning

## Overview

The Taxonomy Architecture Analyzer uses a **system-owned central repository** model
with **lazy workspace provisioning** for multi-user collaboration. This design
separates the shared team-wide architecture state from individual user workspaces,
enabling safe parallel editing without interference.

## Topology Modes

The system supports two topology modes that define how the central repository
relates to external sources:

### INTERNAL_SHARED (Default)

The application hosts the shared integration repository internally. All users
synchronize with this internal repository. This is the default mode and requires
no external Git setup.

When `DslGitRepositoryFactory` is configured (default), each provisioned workspace
gets its own logically separate Git repository in the same database, identified by
a unique `repositoryName` prefix (`ws-{workspaceId}`). The system repository uses
the well-known name `taxonomy-dsl`.

```
┌─────────────────────────────────────────────────────┐
│                    HSQLDB (git_packs table)           │
│                                                       │
│  ┌───────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ System Repo   │  │ Alice Repo   │  │ Bob Repo   │ │
│  │ "taxonomy-dsl"│  │ "ws-abc-123" │  │ "ws-def-456│ │
│  │ Branch: draft │  │ Branch: main │  │ Branch: main│ │
│  └───────────────┘  └──────────────┘  └────────────┘ │
└──────────────────────────────────────────────────────┘
```

### EXTERNAL_CANONICAL

An external Git repository acts as the canonical central source. The application
synchronizes with this external repository via `ExternalGitSyncService` using
JGit's `Transport.open()` for fetch/push operations. This mode is designed for
integration with existing enterprise Git infrastructure (Gitea, GitHub, GitLab).

```
┌──────────────────────────────────────────────────────┐
│                    HSQLDB (git_packs table)            │
│                                                        │
│  ┌───────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ System Repo   │  │ Alice Repo   │  │ Bob Repo   │  │
│  │ "taxonomy-dsl"│  │ "ws-abc-123" │  │ "ws-def-456│  │
│  │ Branch: draft │  │ Branch: main │  │ Branch: main│  │
│  └───────┬───────┘  └──────────────┘  └────────────┘  │
└──────────┼────────────────────────────────────────────┘
           │ fetch/push
           ▼
┌──────────────────────┐
│   Gitea / GitHub     │
│   Remote Repository  │
└──────────────────────┘
```

#### External Sync REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/workspace/external/fetch` | POST | Fetch all branches from the external remote |
| `/api/workspace/external/push` | POST | Push shared branch to the external remote |
| `/api/workspace/external/full-sync` | POST | Fetch + merge into shared branch |
| `/api/workspace/external/status` | GET | Current sync status and timestamps |
| `/api/workspace/external/configure` | PUT | Set external URL and topology mode |

## Workspace Provisioning Lifecycle

When a new user accesses the application, their workspace goes through a
provisioning lifecycle:

```
Login / First Access
    │
    ▼
Workspace metadata created
    status: NOT_PROVISIONED
    │
    ▼
User triggers "Prepare Workspace"
    status: PROVISIONING
    │
    ├── Success → status: READY
    │     └── Personal branch created (e.g., alice/workspace)
    │
    └── Failure → status: FAILED
          └── Error message stored for retry
```

### Provisioning States

| Status | Description |
|--------|-------------|
| `NOT_PROVISIONED` | Workspace metadata exists, but no Git branch has been created yet |
| `PROVISIONING` | Branch creation is in progress |
| `READY` | Workspace is fully provisioned and ready for use |
| `FAILED` | Provisioning failed; see error message for details |

## System Repository

The system automatically creates a primary repository record on startup with:
- **Topology mode**: `INTERNAL_SHARED`
- **Default branch**: `draft`
- **Display name**: "Shared Architecture Repository"

This record is managed by `SystemRepositoryService` and provides the configurable
shared branch name used by all synchronization operations.

## REST API Endpoints

### GET /api/workspace/provisioning-status

Returns the current provisioning state of the user's workspace.

**Response:**
```json
{
  "status": "READY",
  "topologyMode": "INTERNAL_SHARED",
  "sourceRepository": "uuid-of-system-repo",
  "error": null
}
```

### POST /api/workspace/provision

Creates the user's personal branch from the shared repository.

**Response:**
```json
{
  "status": "READY",
  "branch": "alice/workspace",
  "baseBranch": "draft"
}
```

### GET /api/workspace/topology

Returns the repository topology mode and shared source information.

**Response:**
```json
{
  "mode": "INTERNAL_SHARED",
  "sharedBranch": "draft",
  "systemRepositoryId": "uuid-of-system-repo",
  "displayName": "Shared Architecture Repository"
}
```

## User-Facing Terminology

The following terminology is used in the user interface to avoid exposing
raw Git concepts:

| Technical Term | English (UI) | German (UI) |
|---------------|-------------|-------------|
| Central Repository | Shared Space | Gemeinsamer Bereich |
| User Workspace | My Workspace | Mein Arbeitsbereich |
| Branch / Variant | Variant | Variante |
| Provision Workspace | Prepare Workspace | Arbeitsbereich vorbereiten |
| Sync from Shared | Sync from Team | Vom Team synchronisieren |
| Publish Changes | Publish for Team | Für Team veröffentlichen |
| Compare | Compare | Vergleichen |
| Merge | Integrate | Integrieren |
| Cherry-pick | Apply Single Change | Einzeländerung übernehmen |
| HEAD | Current Version | Aktuelle Version |

Terms like `fork`, `fetch`, `refs`, `rebase` are **never** shown in the
standard user interface.

## Architecture

```
SystemRepository (Entity)
  ├── repositoryId: UUID
  ├── topologyMode: INTERNAL_SHARED | EXTERNAL_CANONICAL
  ├── defaultBranch: "draft"
  ├── externalUrl: URL for EXTERNAL_CANONICAL mode
  ├── externalAuthToken: optional authentication token for EXTERNAL_CANONICAL
  ├── lastFetchAt / lastPushAt: sync timestamps
  ├── lastFetchCommit: SHA of last fetched remote HEAD
  └── primaryRepo: true

UserWorkspace (Entity)
  ├── provisioningStatus: NOT_PROVISIONED | PROVISIONING | READY | FAILED
  ├── topologyMode: mirrors SystemRepository
  ├── sourceRepositoryId: links to SystemRepository
  ├── baseCommit / currentCommit: Git SHAs
  ├── syncTargetBranch: configurable sync target
  └── provisionedAt / provisioningError: audit data

DslGitRepositoryFactory
  ├── getSystemRepository() → shared system repo ("taxonomy-dsl")
  ├── getWorkspaceRepository(workspaceId) → per-workspace repo ("ws-{id}")
  ├── resolveRepository(WorkspaceContext) → context-based resolution
  └── evict(workspaceId) → cache cleanup on deletion

ExternalGitSyncService
  ├── fetchFromExternal() → JGit Transport.fetch() from remote
  ├── pushToExternal(branch) → JGit Transport.push() to remote
  ├── fullSync(username) → fetch + merge into shared branch
  └── getStatus() → external sync configuration and timestamps

SystemRepositoryService
  ├── @PostConstruct → ensures primary repo exists
  ├── getPrimaryRepository() → returns SystemRepository
  └── getSharedBranch() → configurable branch name

WorkspaceManager
  ├── getOrCreateWorkspace() → in-memory state (unchanged)
  ├── findUserWorkspace() → persistent entity lookup
  └── provisionWorkspaceRepository() → creates per-workspace repo (factory) or branch (legacy)
```
---

## Related Documentation

- [Workspace & Versioning Guide](WORKSPACE_VERSIONING.md) — user-facing guide for the workspace UI (context bar, history, variants, sync)
- [GIT_INTEGRATION](GIT_INTEGRATION.md) — technical details of the JGit-backed DSL storage, branching, and merge operations
- [Architecture](ARCHITECTURE.md) — DSL storage architecture and module overview
