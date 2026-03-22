# Workspace Design Document

**Status:** Implemented — updated 15 March 2026  
**Related Issues:** #180 (Multi-User Workspace Model)

---

## 1. Overview

The multi-user workspace model provides isolated editing environments for concurrent users. Each user gets an independent workspace with its own branch, navigation context, projection state, and sync tracking. In the default configuration (factory mode), each workspace gets a logically separate Git repository via `DslGitRepositoryFactory.getWorkspaceRepository(workspaceId)`. Branch-level isolation within a shared repository is only used in legacy/test mode. See [REPOSITORY_TOPOLOGY.md](../en/REPOSITORY_TOPOLOGY.md) for details.

---

## 2. Design Principles

1. **Repository-level isolation** — Each user works in their own logical Git repository (default). Branch-level isolation is supported as a legacy fallback.
2. **Lazy initialization** — Workspaces are created on first access, not pre-provisioned.
3. **Graceful degradation** — If persistence fails, the workspace still functions in-memory.
4. **Shared integration point** — The `draft` branch serves as the shared/canonical repository state.
5. **Explicit synchronization** — Users explicitly sync (pull) and publish (push) changes.

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Spring Security                       │
│                 (Authentication Context)                  │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │  WorkspaceResolver  │   ← Extracts username from
              │  (resolveUsername)   │     SecurityContextHolder
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │  WorkspaceManager   │   ← ConcurrentHashMap<String, UserWorkspaceState>
              │  (getOrCreate)      │     Lazy init + persistent metadata
              └──────────┬──────────┘
                         │
            ┌────────────┼────────────┐
            │            │            │
            ▼            ▼            ▼
   ┌────────────┐ ┌──────────┐ ┌──────────┐
   │  Context   │ │ Projection│ │  Sync    │
   │  History   │ │ Tracking  │ │ State    │
   │  Service   │ │ Service   │ │ Service  │
   └──────┬─────┘ └────┬─────┘ └────┬─────┘
          │             │            │
          ▼             ▼            ▼
   ┌────────────┐ ┌──────────┐ ┌──────────┐
   │ context_   │ │workspace_│ │sync_     │
   │ history_   │ │projection│ │state     │
   │ record     │ │          │ │          │
   └────────────┘ └──────────┘ └──────────┘
   (JPA tables)
```

---

## 4. Entity Model

### 4.1 UserWorkspace (existing)

Persistent workspace metadata. One record per user.

| Field | Type | Description |
|---|---|---|
| `workspaceId` | String | UUID, unique |
| `username` | String | Owner username |
| `currentBranch` | String | Active branch name |
| `baseBranch` | String | Branch this workspace was forked from |
| `shared` | boolean | True for the shared integration workspace |
| `createdAt` | Instant | Creation timestamp |
| `lastAccessedAt` | Instant | Last access timestamp |

### 4.2 WorkspaceProjection (new)

Per-user projection state tracking.

| Field | Type | Description |
|---|---|---|
| `username` | String | Owner |
| `workspaceId` | String | Links to UserWorkspace |
| `projectionCommitId` | String | SHA of materialized commit |
| `projectionBranch` | String | Branch that was materialized |
| `projectionTimestamp` | Instant | When projection was built |
| `indexCommitId` | String | SHA of indexed commit |
| `indexTimestamp` | Instant | When index was built |
| `stale` | boolean | Whether projection is stale |

### 4.3 ContextHistoryRecord (new)

Persistent navigation history with origin tracking.

| Field | Type | Description |
|---|---|---|
| `username` | String | Owner |
| `fromContextId` | String | Source context UUID |
| `toContextId` | String | Target context UUID |
| `fromBranch` / `toBranch` | String | Branch names |
| `fromCommitId` / `toCommitId` | String | Commit SHAs |
| `reason` | String | Navigation reason |
| `originContextId` | String | For return-to-origin support |

### 4.4 SyncState (new)

Tracks synchronization between workspace and shared repository.

| Field | Type | Description |
|---|---|---|
| `username` | String | Owner |
| `workspaceId` | String | Links to UserWorkspace |
| `lastSyncedCommitId` | String | Last synced shared commit |
| `lastPublishedCommitId` | String | Last published user commit |
| `syncStatus` | String | UP_TO_DATE / BEHIND / AHEAD / DIVERGED |
| `unpublishedCommitCount` | int | Commits not yet published |

---

## 5. In-Memory State (UserWorkspaceState)

Volatile per-user state held in `WorkspaceManager`'s ConcurrentHashMap:

| Field | Type | Description |
|---|---|---|
| `currentContext` | ContextRef | Active architecture context |
| `history` | Deque | Browser-like navigation history (bounded) |
| `lastProjectionCommit` | String | For staleness checks |
| `lastIndexCommit` | String | For staleness checks |
| `operationKind` | String | In-progress operation (null = idle) |

---

## 6. REST API Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/workspace/current` | Current user's workspace info |
| GET | `/api/workspace/active` | All active workspaces (admin) |
| GET | `/api/workspace/stats` | Active workspace count |
| POST | `/api/workspace/evict` | Remove workspace from cache |
| POST | `/api/workspace/compare` | Compare two branches/commits |
| POST | `/api/workspace/sync-from-shared` | Pull from shared branch |
| POST | `/api/workspace/publish` | Push to shared branch |
| GET | `/api/workspace/sync-state` | Current sync status |
| GET | `/api/workspace/history` | Navigation history |
| GET | `/api/workspace/local-changes` | Unpublished commit count |
| GET | `/api/workspace/dirty` | Dirty state check |
| GET | `/api/workspace/projection` | Projection state |

---

## 7. UI Integration

| Component | Module | Function |
|---|---|---|
| Sync Status Indicator | `taxonomy-workspace-sync.js` | Appends to Git Status Bar |
| Dirty State Indicator | `taxonomy-workspace-sync.js` | Changes workspace badge color |
| Sync Sub-Tab | `taxonomy-workspace-sync.js` | Sync from shared / Publish buttons |
| Local Changes Panel | `taxonomy-workspace-sync.js` | Shows unpublished commit count |
| Variant Browser | `taxonomy-variants.js` | Branch list with switch/compare/merge |
| Context Bar | `taxonomy-context-bar.js` | Mode, branch, origin, navigation |
| Action Guards | `taxonomy-action-guards.js` | Disables buttons based on state |

---

## 8. Sync Workflow

```
User Workspace                    Shared Repository
     │                                   │
     │  ── sync-from-shared ──────────►  │
     │  (merge shared → user branch)     │
     │                                   │
     │  ◄── publish ──────────────────   │
     │  (merge user branch → shared)     │
     │                                   │
```

The sync state machine:

```
                    ┌───────────┐
         ┌─────────│ UP_TO_DATE│◄────────┐
         │         └───────────┘         │
  user commits      shared commits    sync + publish
         │              │                │
         ▼              ▼                │
    ┌────────┐    ┌────────┐        ┌────┴────┐
    │ AHEAD  │    │ BEHIND │        │ resolved│
    └────┬───┘    └────┬───┘        └─────────┘
         │              │
    both change    both change
         │              │
         ▼              ▼
       ┌──────────────────┐
       │    DIVERGED      │
       └──────────────────┘
```

---

## 9. System Repository & Topology Modes

The system uses a **System-owned Central Repository** model with configurable topology:

### SystemRepository Entity

```
SystemRepository (JPA Entity)
├── repositoryId: UUID (unique)
├── topologyMode: INTERNAL_SHARED | EXTERNAL_CANONICAL
├── defaultBranch: "draft" (configurable)
├── externalUrl: optional URL for EXTERNAL_CANONICAL mode
├── primaryRepo: boolean (exactly one true)
└── createdAt: Instant
```

- Auto-created on startup by `SystemRepositoryService.@PostConstruct`
- Provides the shared branch name via `getSharedBranch()` (replaces hardcoded `SHARED_BRANCH`)
- `SyncIntegrationService` reads the shared branch from this service

### Workspace Provisioning

New workspaces are created with **lazy provisioning**:

1. **Metadata only** — `ensurePersistentWorkspace()` creates a `UserWorkspace` with status `NOT_PROVISIONED`
2. **Explicit provisioning** — `provisionWorkspaceRepository()` creates the Git branch when requested
3. **Status tracking** — `WorkspaceProvisioningStatus` enum tracks: `NOT_PROVISIONED → PROVISIONING → READY | FAILED`

### UserWorkspace Extended Fields

```
provisioningStatus: WorkspaceProvisioningStatus (default: READY for backward compat)
topologyMode: RepositoryTopologyMode (mirrors SystemRepository)
sourceRepositoryId: String (links to SystemRepository)
baseCommit / currentCommit: String (Git SHAs)
syncTargetBranch: String (configurable sync target)
provisionedAt: Instant
provisioningError: String
```

---

## 10. Data Isolation Model

Workspace isolation applies to **mutable, user-generated data**, not to the read-only taxonomy catalog:

### Scoping Strategy

| Data Type | Isolated? | Reason |
|---|---|---|
| **TaxonomyNode** (catalog) | ❌ No | Immutable nodes from Excel. Shared across all workspaces. |
| **TaxonomyRelation** (DSL-materialized) | ✅ Yes | Created per workspace via DSL materialization. `workspace_id` column. |
| **RelationHypothesis** (analysis) | ✅ Yes | Generated per workspace during analysis. `workspace_id` column. |
| **RelationProposal** (user) | ✅ Yes | User-generated proposals, scoped to workspace. `workspace_id` column. |
| **ArchitectureCommitIndex** | ✅ Repository-scoped | Commits are read from the correct workspace repository via explicit `WorkspaceContext` parameter. Branch filtering provides additional scoping within a repository. |
| **ArchitectureDslDocument** | ⚠️ Branch-filtered | Has `branch` and `commitId`. Workspace derived from branch. |

### Entity Extensions

Three entities carry `workspace_id` and `owner_username` columns:

```
TaxonomyRelation
  + workspace_id    VARCHAR  @KeywordField  (nullable – NULL = shared/legacy)
  + owner_username  VARCHAR  @KeywordField  (nullable)
  Unique constraint: (source_node_id, target_node_id, relation_type, workspace_id)
  Indexes: idx_rel_workspace, idx_rel_owner

RelationHypothesis
  + workspace_id    VARCHAR  (nullable)
  + owner_username  VARCHAR  (nullable)

RelationProposal
  + workspace_id    VARCHAR  (nullable)
  + owner_username  VARCHAR  (nullable)
```

### WorkspaceContext Resolution

```
WorkspaceContext record:
  username       – authenticated user
  workspaceId    – from UserWorkspace entity (null for SHARED/legacy)
  currentBranch  – active Git branch

WorkspaceContext.SHARED:
  username = "system"
  workspaceId = null        ← maps to workspace_id IS NULL (no filtering)
  currentBranch = "draft"   ← overridden by SystemRepositoryService.getSharedBranch()

WorkspaceContextResolver:
  1. Extract username from SecurityContextHolder
  2. Look up provisioned UserWorkspace via WorkspaceManager
  3. If provisioned → WorkspaceContext(user, wsId, branch)
  4. If not provisioned → WorkspaceContext.SHARED (workspaceId=null, no isolation)
  5. Branch fallback uses SystemRepositoryService.getSharedBranch() (configurable)
```

### Query Patterns

All workspace-scoped queries use the **OR-null** pattern for backward compatibility.
Services check `ctx.workspaceId() != null` to decide whether to apply workspace filtering:

```java
// Service-layer pattern:
if (ctx.workspaceId() != null) {
    // Provisioned workspace → workspace-scoped query
    repository.findByStatusAndWorkspace(status, ctx.workspaceId());
} else {
    // SHARED context → unscoped global query
    repository.findByStatus(status);
}
```

```sql
-- JPA: shared + workspace-specific relations
WHERE (workspace_id = :wsId OR workspace_id IS NULL)

-- Hibernate Search: workspace filter on KNN (only when workspaceId is non-null)
must(bool()
  .should(match().field("workspaceId").matching(wsId))
  .should(not(exists().field("workspaceId")))
)
```

### Acceptance Flows

When accepting proposals or hypotheses, the created relation uses the **entity's stored**
`workspaceId`/`ownerUsername` (not the current reviewer's context). This ensures the
relation is created in the correct workspace even when reviewed by a different user.

### Legacy Data

`NULL` workspace = shared/legacy data, visible to all workspaces. No migration needed — existing relations remain globally visible.
`WorkspaceContext.SHARED.workspaceId` is `null`, which naturally maps to the unscoped (global) query path.

---

## 11. Repository-Aware Services

### Topology Modes

| Mode | Description | Default? |
|---|---|---|
| **Factory mode** (per-workspace repos) | Each workspace gets its own Git repository via `DslGitRepositoryFactory.getWorkspaceRepository(workspaceId)`. Production default. | ✅ Yes |
| **Shared-repo + branch isolation** | All workspaces share one Git repository, isolated by branch names. Legacy/test mode. | ❌ No |

### How `resolveRepository(WorkspaceContext)` Works

The `DslGitRepositoryFactory.resolveRepository(WorkspaceContext ctx)` method routes to the correct repository:

1. If `ctx.workspaceId()` is null (SHARED context) → returns `getSystemRepository()` (the shared `"taxonomy-dsl"` repo)
2. If `ctx.workspaceId()` is set → returns `getWorkspaceRepository(ctx.workspaceId())` (e.g. `"ws-alice-ws"`)

### Repo-Aware Services

Backend services accept an **explicit `WorkspaceContext` parameter** on methods that need repository access. They do NOT call `resolveCurrentContext()` internally — that is reserved for the Facade/UI layer.

| Service | Repo-Aware Methods | Context Source |
|---|---|---|
| `CommitIndexService` | `indexBranch(branch, ctx)` | Explicit parameter |
| `RepositoryStateService` | `getState(user, branch, ctx)`, `isProjectionStale(user, branch, ctx)`, `getViewContext(user, branch, ctx)`, `getProjectionState(user, branch, ctx)` | Explicit parameter |
| `ContextNavigationService` | `switchContext(user, branch, commitId, ctx)`, `createVariantFromCurrent(user, name, ctx)` | Explicit parameter |
| `ConflictDetectionService` | `previewMerge(from, into, ctx)`, `previewCherryPick(commitId, branch, ctx)`, `getMergeConflictDetails(from, into, ctx)`, `getCherryPickConflictDetails(commitId, branch, ctx)` | Explicit parameter |
| `WorkspaceProjectionService` | `isProjectionStale(user, branch, ctx)` | Explicit parameter |
| **`DslOperationsFacade`** | All Git methods (`commitDsl`, `getDslHistory`, `merge`, `listBranches`, etc.) | `resolveCurrentContext()` — the **only** place that reads from SecurityContext |

All repo-aware methods also provide backward-compatible overloads without the `ctx` parameter that default to `WorkspaceContext.SHARED` (system repository).

---

## 12. Future Considerations

- **Keycloak OIDC** — Replace form-login with JWT-based auth; workspace ownership from token claims
- **Per-user projection tables** — Currently logical isolation; future: physical table-per-user or discriminator column
- **Workspace TTL** — Automatic eviction of inactive workspaces
- **Conflict resolution UI** — Visual merge conflict resolution in the browser
