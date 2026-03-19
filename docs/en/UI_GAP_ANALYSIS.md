# UI Gap Analysis

Status of GUI dialogs and their implementation.

## Implemented Dialogs

| Dialog | Status | Phase | Files |
|---|---|---|---|
| Merge Preview Modal | ✅ Implemented | 1.6 | `index.html`, `taxonomy-action-guards.js` |
| Cherry-Pick Preview Modal | ✅ Implemented | 1.6 | `index.html`, `taxonomy-action-guards.js` |
| Merge Conflict Resolution Modal | ✅ Implemented | 1.1 | `index.html`, `taxonomy-merge-resolution.js` |
| Cherry-Pick Conflict Dialog | ✅ Implemented | 1.2 | `index.html`, `taxonomy-merge-resolution.js` |
| Sync Diverged Resolution Modal | ✅ Implemented | 1.3 | `index.html`, `taxonomy-merge-resolution.js` |
| Branch Delete (with confirmation) | ✅ Implemented | 1.4 | `taxonomy-variants.js` |
| Operation Result Toasts | ✅ Implemented | 1.5 | `taxonomy-operation-result.js` |
| Variant Creation Modal | ✅ Implemented | Pre-existing | `index.html`, `taxonomy-context-bar.js` |
| Context Compare Modal | ✅ Implemented | Pre-existing | `index.html`, `taxonomy-context-compare.js` |
| Context Transfer Modal | ✅ Implemented | Pre-existing | `index.html`, `taxonomy-context-transfer.js` |

## Backend Endpoints

| Endpoint | Status | Description |
|---|---|---|
| `GET /api/dsl/merge/preview` | ✅ Pre-existing | Preview merge result |
| `GET /api/dsl/cherry-pick/preview` | ✅ Pre-existing | Preview cherry-pick result |
| `GET /api/dsl/merge/conflicts` | ✅ Implemented | Merge conflict details |
| `POST /api/dsl/merge/resolve` | ✅ Implemented | Commit resolved merge content |
| `GET /api/dsl/cherry-pick/conflicts` | ✅ Implemented | Cherry-pick conflict details |
| `POST /api/dsl/cherry-pick/resolve` | ✅ Implemented | Commit resolved cherry-pick content |
| `DELETE /api/dsl/branch` | ✅ Implemented | Delete branch (with protection) |
| `POST /api/workspace/resolve-diverged` | ✅ Implemented | Resolve diverged sync state |

## JavaScript Modules

| Module | Lines | Purpose |
|---|---|---|
| `taxonomy-merge-resolution.js` | New | Conflict resolution UI, diverged state resolution |
| `taxonomy-operation-result.js` | New | Toast notification system for Git operations |
| `taxonomy-action-guards.js` | Updated | Bootstrap modals replace `alert()`/`confirm()` |
| `taxonomy-variants.js` | Updated | Delete button added for non-protected branches |
| `taxonomy-workspace-sync.js` | Updated | Resolve button for DIVERGED state, toast notifications |
| `taxonomy-dsl-editor.js` | Updated | Toast notifications for merge/cherry-pick results |

---

## Features Currently REST-Only (GUI Gap)

These features have REST endpoints but no corresponding GUI workflow.
They are tracked as open gaps per the [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--user-facing-features).

| Feature | REST Endpoint | GUI Status | Priority |
|---|---|---|---|
| Leaf justification | `POST /api/justify-leaf` | ❌ No GUI | High |
| Gap analysis | `POST /api/gap/analyze` | ❌ No GUI (or unclear) | High |
| Recommendations | `POST /api/recommend` | ❌ No GUI (or unclear) | Medium |
| Pattern detection | `GET/POST /api/patterns/detect` | ❌ No GUI (or unclear) | Medium |
| Reports (MD/HTML/DOCX) | `POST /api/report/*` | ❌ No GUI trigger | High |
| Requirement impact analysis | `POST /api/graph/impact` | ❌ No GUI (or unclear) | Medium |

## Documentation Gaps

| Feature area | GUI exists | User Guide section | Screenshot | Help text |
|---|---|---|---|---|
| Workspace sync/publish | ✅ | ❌ Missing | ❌ | ❌ |
| Version history browsing | ✅ | ❌ Missing | ❌ | ❌ |
| Branch compare | ✅ | ❌ Missing | ❌ | ❌ |
| Variant creation | ✅ | ❌ Missing | ❌ | ❌ |
| DSL editor | ✅ | ❌ Missing | ❌ | ❌ |
| Merge conflict resolution | ✅ | ❌ Missing | ❌ | ❌ |

> **Action:** Each row should be resolved before the feature is considered complete.
