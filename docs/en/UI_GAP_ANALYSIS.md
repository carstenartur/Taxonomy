# UI Gap Analysis

Implementation notes for the listed GUI dialogs. The phase labels below describe earlier delivery work, not a current acceptance of every view or control. See the [view and control inventory](../testing/ui-view-control-inventory.md) for current templates, documentation, test owners and missing evidence.

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

## Evidence review (25 October 2026)

The earlier statement that all gaps were closed was too broad. Implemented dialogs, a screenshot file and a passing test of another entry path are different evidence. The [feature matrix](FEATURE_MATRIX.md) now records those limits; the [view and control inventory](../testing/ui-view-control-inventory.md) retains the remaining follow-up work.

### Features with GUI entry points

| Feature | GUI element | User guide | Screenshot evidence |
|---|---|---|---|
| Leaf justification | `leafJustificationModal` | §6 | Historical #18 |
| Requirement coverage | `coverageDashboard` + `coverageRecordBtn` | §11c | Historical #26, 27 show this dashboard |
| Gap analysis | `gapAnalysisPanel` + `gapAnalyzeBtn` | §11d | No dedicated current capture linked; #26/27 do not show gap analysis |
| Recommendations | `recommendationPanel` + `recommendBtn` | §11e | No dedicated current capture linked |
| Pattern detection | `patternDetectionPanel` + `patternDetectBtn` | §11f | No dedicated current capture linked |
| Reports (MD/HTML/DOCX) | `exportReportMd/Html/Docx` buttons | §10a | Historical #23 shows export controls, not successful downloads |
| Requirement impact analysis | `requirementImpactBtn` in Graph | §11b | No dedicated current capture linked; behavioral owner: `ux-state-recovery.cjs` |
| Graph entry from tree/impact map | Node action or element details → Graph | §8 | [Current DE capture](../images/graph-entry-current-de.png); real-server owner: `verifyGraphEntry` in `ui-primary-basic-workflows.mjs`; component regressions: `ux-state-recovery.cjs` / `analysis-ux.browser.test.mjs` |

### Documentation and visual evidence

Workspace, history, branch comparison, transfer, variants, DSL and merge workflows are explained in the [User Guide](USER_GUIDE.md#12-versions-tab). That does not close every visual or functional gap:

- #20, #37 and #38 depended on unsupported catalogue-relation seeding and are retired. Accepted workspace proposals belong to the workspace relation projection; catalogue Graph queries read a different scope. The current persisted-workbench captures remain valid for their selected example snapshots.
- #32 is a historical graph-semantic search illustration. Its owner did not establish the required workspace relation projection; a fresh workspace can return `409 NOT_BUILT`. The current Graph-entry capture tests a different operation and does not fill this search-evidence gap.
- #48–65 and #67–68 used injected presentation state. Their generators are retired; retained images are historical fixtures, not proof that a merge, transfer, sync, delete or restore succeeded.
- #41–47 and #66 remain historical history/workspace illustrations. A file's presence does not bind it to the current source revision.
- The database assistant, architecture editor and persisted workbench have separate guides and test owners. Capture provenance and scope are recorded in the [inventory](../testing/ui-view-control-inventory.md).
- Empty/error outcomes, permissions, keyboard interaction and changes made concurrently require behavioral assertions. A static count of buttons or screenshots is not such an assertion.

Follow the [screenshot provenance rules](../../.github/copilot-ref-screenshots.md) when refreshing images. Missing evidence stays explicit until the relevant application action is observed and recorded.
