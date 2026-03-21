# UI Gap Analysis

**Status:** Updated — 19 March 2026  
**Related Issues:** #180 (Multi-User Workspace Model), #182 (UI Refactor for Workspace)

---

## 1. Current UI State

The UI is a single-page Bootstrap 5 application rendered by Thymeleaf (`index.html`) with 33 JavaScript files across five directories. The main layout consists of:

- **Navbar** — Application title, AI/embedding status badges, workspace user badge, admin lock
- **Git Status Bar** — Branch, HEAD SHA, projection/index freshness, variant/version counts, sync status
- **Context Navigation Bar** — Current mode, branch, commit, origin info, navigation buttons
- **Tab Navigation** — 10 main tabs: Analyze, Architecture, Graph, Relations, Coverage, Gap, Recommendations, Patterns, DSL, Versions
- **Versions Tab** — 4 sub-tabs: History, Variants, Save Version, Sync

---

## 2. Workspace-Aware UI Elements (Implemented)

| Element | Location | Module | Status |
|---|---|---|---|
| Workspace User Badge | Navbar | `taxonomy.js` | ✅ Shows `user@branch`, yellow when dirty |
| Context Navigation Bar | Below Git Status | `taxonomy-context-bar.js` | ✅ Mode, branch, commit, origin, navigation |
| Read-Only Mode Badge | Git Status Bar | `taxonomy-git-status.js` | ✅ READ-ONLY badge when applicable |
| Action Guards | All guarded buttons | `taxonomy-action-guards.js` | ✅ Disables buttons in read-only/operation-in-progress |
| Variant Creation Modal | Context Bar button | `taxonomy-context-bar.js` | ✅ Bootstrap modal for new variants |
| Variants Browser | Versions → Variants tab | `taxonomy-variants.js` | ✅ Branch list with switch/compare/merge |
| Compare Modal | Context Bar button | `taxonomy-context-compare.js` | ✅ Branch-to-branch comparison with semantic diff |
| Copy Back Button | Context Bar (read-only) | `taxonomy-context-bar.js` | ✅ Selective transfer from read-only to editable |
| Sync Status Indicator | Git Status Bar | `taxonomy-workspace-sync.js` | ✅ Shows sync state (synced/behind/ahead/diverged) |
| Dirty State Indicator | Workspace Badge | `taxonomy-workspace-sync.js` | ✅ Changes badge color when dirty |
| Sync Sub-Tab | Versions → Sync tab | `taxonomy-workspace-sync.js` | ✅ Sync state panel + sync/publish buttons |
| Sync State Panel | Versions → Sync → detail panel | `taxonomy-workspace-sync.js` | ✅ Shows status badge, timestamps, commit SHAs |
| Local Changes Panel | Versions → Sync → below state | `taxonomy-workspace-sync.js` | ✅ Unpublished commit count + action buttons |

---

## 3. Remaining Gaps

| Gap | Priority | Description |
|---|---|---|
| Compare Diff View Enhancement | Medium | Current compare shows summary + semantic changes. Could add inline diff highlighting. |
| Workspace Switcher | Low | Currently no way to switch between multiple workspaces from the UI (admin can view all). |
| History Timeline Visualization | Low | Navigation history is available via API but not visualized as a timeline in the UI. |

---

## 4. JavaScript Module Inventory

| # | Module | Purpose | Workspace-Aware |
|---|---|---|---|
| 1 | `taxonomy.js` | Main entry, tab routing, status polling | ✅ |
| 2 | `taxonomy-git-status.js` | Git status bar polling/rendering | ✅ |
| 3 | `taxonomy-workspace-sync.js` | Sync status, dirty state, local changes, sync state panel | ✅ |
| 4 | `taxonomy-context-bar.js` | Context navigation bar | ✅ |
| 5 | `taxonomy-context-compare.js` | Compare dialog | ✅ |
| 6 | `taxonomy-context-transfer.js` | Copy-back transfer dialog | ✅ |
| 7 | `taxonomy-action-guards.js` | Button state management | ✅ |
| 8 | `taxonomy-versions.js` | Version history, save version | ✅ |
| 9 | `taxonomy-variants.js` | Variant browser | ✅ |
| 10 | `taxonomy-viewcontext.js` | View context state | ✅ |
| 11 | `taxonomy-analysis.js` | Analysis panel | Via username |
| 12 | `taxonomy-graph.js` | Graph explorer | Via username |
| 13 | `taxonomy-search.js` | Search panel | Via username |
| 14 | `taxonomy-history-search.js` | History search | Via username |
| 15 | `taxonomy-relations.js` | Relations management | Via username |
| 16 | `taxonomy-coverage.js` | Coverage dashboard | Via username |
| 17 | `taxonomy-quality.js` | Quality metrics | Via username |
| 18 | `taxonomy-export.js` | Export panel | Via username |
| 19 | `taxonomy-import.js` | Import panel | Via username |
| 20 | `taxonomy-dsl-editor.js` | DSL editor | Via username |
| 21 | `taxonomy-dsl-codemirror.mjs` | CodeMirror 6 integration | Via username |
| 22 | `taxonomy-views.js` | View mode switching | N/A |
| 23 | `taxonomy-onboarding.js` | First-use onboarding | N/A |
| 24 | `taxonomy-help.js` | Help panel | N/A |
| 25 | `taxonomy-about.js` | About dialog | N/A |
| 26 | `taxonomy-merge-resolution.js` | Merge conflict resolution UI | ✅ |
| 27 | `taxonomy-operation-result.js` | Toast notification system for Git operations | ✅ |
| 28 | `taxonomy-browse.js` | Main browse panel, report export logic | Via username |
| 29 | `taxonomy-i18n.js` | Internationalization — loads locale from `/api/i18n/{locale}` | N/A |
| 30 | `taxonomy-scoring.js` | Score rendering, layer configuration | Via username |
| 31 | `taxonomy-state.js` | Application state management | ✅ |
| 32 | `taxonomy-document-import.js` | Document import (PDF/DOCX) and provenance | Via username |
| 33 | `taxonomy-utils.js` | Shared utility functions | N/A |
| 34 | `taxonomy-workspace-provisioning.js` | Workspace provisioning flow | ✅ |

---

## 5. Data Requirements for UI

| UI Component | API Endpoint | Data Returned |
|---|---|---|
| Workspace Badge | `GET /api/workspace/current` | WorkspaceInfo (branch, user, context) |
| Git Status Bar | `GET /api/git/state` | RepositoryState (branch, HEAD, staleness, operations) |
| Sync Indicator | `GET /api/workspace/sync-state` | Sync status, unpublished count, timestamps |
| Sync State Panel | `GET /api/workspace/sync-state` | Full sync state with timestamps and commit SHAs |
| Context Bar | `GET /api/context/current` | ContextRef (mode, branch, commit, origin, dirty) |
| Compare Dialog | `POST /api/workspace/compare` | ContextComparison (summary, changes, diff) |
| Variants Browser | `GET /api/git/state` | List of branches with commit info |
| Local Changes | `GET /api/workspace/local-changes` | Change count per branch |
| Dirty Check | `GET /api/workspace/dirty` | Boolean dirty flag |
| Projection State | `GET /api/workspace/projection` | Projection commit, staleness |

---

## 6. Screenshot Coverage

| # | Screenshot | Test | Status |
|---|---|---|---|
| 45 | `45-workspace-user-badge.png` | `captureWorkspaceUserBadge()` | ✅ Test added |
| 46 | `46-variant-creation-modal.png` | `captureVariantCreationModal()` | ✅ Test added |
| 47 | `47-variants-browser-tab.png` | `captureVariantsBrowserTab()` | ✅ Test added |
| 48 | `48-compare-modal-branches.png` | `captureCompareModalBranches()` | ✅ Test added |
| 49 | `49-copy-back-button.png` | `captureCopyBackButton()` | ✅ Test added |
| 50 | `50-read-only-mode-badge.png` | `captureReadOnlyModeBadge()` | ✅ Test added |
| 51 | `51-context-bar-with-origin.png` | `captureContextBarWithOrigin()` | ✅ Test added |
| 52 | `52-merge-conflict-modal.png` | `captureMergeConflictModal()` | ✅ Test added |
| 53 | `53-merge-conflict-resolved.png` | `captureMergeConflictResolved()` | ✅ Test added |
| 54 | `54-cherry-pick-conflict-modal.png` | `captureCherryPickConflictModal()` | ✅ Test added |
| 55 | `55-sync-diverged-state.png` | `captureSyncDivergedState()` | ✅ Test added |
| 56 | `56-sync-resolve-modal.png` | `captureSyncResolveModal()` | ✅ Test added |
| 57 | `57-variant-delete-confirm.png` | `captureVariantDeleteConfirm()` | ✅ Test added |
| 58 | `58-merge-success-toast.png` | `captureMergeSuccessToast()` | ✅ Test added |
| 59 | `59-cherry-pick-success-toast.png` | `captureCherryPickSuccessToast()` | ✅ Test added |
| 60 | `60-merge-preview-modal.png` | `captureMergePreviewModal()` | ✅ Test added |
| 61 | `61-merge-preview-fast-forward.png` | `captureMergePreviewFastForward()` | ✅ Test added |
| 62 | `62-cherry-pick-preview-modal.png` | `captureCherryPickPreviewModal()` | ✅ Test added |
| 63 | `63-sync-tab-up-to-date.png` | `captureSyncTabUpToDate()` | ✅ Test added |
| 64 | `64-sync-tab-ahead.png` | `captureSyncTabAhead()` | ✅ Test added |
| 65 | `65-sync-tab-behind.png` | `captureSyncTabBehind()` | ✅ Test added |
| 66 | `66-versions-timeline.png` | `captureVersionsTimeline()` | ✅ Test added |
| 67 | `67-version-restore-confirm.png` | `captureVersionRestoreConfirm()` | ✅ Test added |
| 68 | `68-diff-view.png` | `captureDiffView()` | ✅ Test added |
| 69 | `69-decision-map-scored.png` | `captureDecisionMapScored()` | ✅ Test added |
