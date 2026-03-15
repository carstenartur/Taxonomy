# Workspace Design Document

**Status:** Implemented — updated 15 March 2026  
**Related Issues:** #180 (Multi-User Workspace Model)

---

## 1. Overview

The multi-user workspace model provides isolated editing environments for concurrent users. Each user gets an independent workspace with its own branch, navigation context, projection state, and sync tracking. The underlying Git repository (`DslGitRepository`) is shared; isolation is achieved through branches and per-user state management.

---

## 2. Design Principles

1. **Branch-level isolation** — Each user works on their own Git branch. No two users accidentally overwrite each other's work.
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

## 9. Future Considerations

- **Keycloak OIDC** — Replace form-login with JWT-based auth; workspace ownership from token claims
- **Per-user projection tables** — Currently logical isolation; future: physical table-per-user or discriminator column
- **Workspace TTL** — Automatic eviction of inactive workspaces
- **Conflict resolution UI** — Visual merge conflict resolution in the browser
