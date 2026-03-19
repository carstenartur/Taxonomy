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

## Resolved Gaps (March 2026)

All previously identified gaps have been resolved. See [FEATURE_MATRIX.md](FEATURE_MATRIX.md) for current status.

### Former REST-Only Features — Now Complete with GUI

| Feature | GUI Element | User Guide | Screenshot |
|---|---|---|---|
| Leaf justification | `leafJustificationModal` | §6 | #18 |
| Gap analysis | `gapAnalysisPanel` + `gapAnalyzeBtn` | §11e | #26, 27 |
| Recommendations | `recommendationPanel` + `recommendBtn` | §4 | ✅ |
| Pattern detection | `patternDetectionPanel` + `patternDetectBtn` | §11f | ✅ |
| Reports (MD/HTML/DOCX) | `exportReportMd/Html/Docx` buttons | §10a | #23 |
| Requirement impact analysis | `requirementImpactBtn` | §8 | ✅ |

### Former Documentation Gaps — Now Complete

| Feature area | User Guide | Screenshot | Help text |
|---|---|---|---|
| Workspace sync/publish | ✅ §12 | ✅ #55, 56, 63–65 | ✅ |
| Version history browsing | ✅ §12 | ✅ #41, 66–68 | ✅ |
| Branch compare | ✅ §12 | ✅ #48 | ✅ |
| Variant creation | ✅ §12 | ✅ #46, 47 | ✅ |
| DSL editor | ✅ §11g | ✅ #34, 40 | ✅ |
| Merge conflict resolution | ✅ §12 | ✅ #52, 53, 58, 60, 61 | ✅ |
