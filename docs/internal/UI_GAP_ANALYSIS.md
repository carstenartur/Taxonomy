# UI Gap Analysis

**Status:** In progress — updated 15 March 2026  
**Related Issues:** #180 (Multi-User Workspace Model), #182 (UI Refactor for Workspace)

---

## 1. Current UI State

The UI is a single-page Bootstrap 5 application rendered by Thymeleaf (`index.html`) with 27 JavaScript modules. The main layout consists of:

- **Navbar** — Application title, AI/embedding status badges, workspace user badge, admin lock
- **Git Status Bar** — Branch, HEAD SHA, projection/index freshness, variant/version counts, sync status
- **Context Navigation Bar** — Current mode, branch, commit, origin info, navigation buttons
- **Tab Navigation** — 10 main tabs: Analyze, Architecture, Graph, Relations, Coverage, Gap, Recommendations, Patterns, DSL, Versions
- **Versions Tab** — 4 sub-tabs: History, Variants, Save Version, Sync

---

## 2. Workspace-Aware UI Elements (Implemented)

| Element | Location | Module | Status |
|---|---|---|---|
| Workspace User Badge | Navbar | `taxonomy.js` | ✅ Shows `user@branch` |
| Context Navigation Bar | Below Git Status | `taxonomy-context-bar.js` | ✅ Mode, branch, commit, origin, navigation |
| Read-Only Mode Badge | Git Status Bar | `taxonomy-git-status.js` | ✅ READ-ONLY badge when applicable |
| Action Guards | All guarded buttons | `taxonomy-action-guards.js` | ✅ Disables buttons in read-only/operation-in-progress |
| Variant Creation Modal | Context Bar button | `taxonomy-context-bar.js` | ✅ Bootstrap modal for new variants |
| Variants Browser | Versions → Variants tab | `taxonomy-variants.js` | ✅ Branch list with switch/compare/merge |
| Compare Modal | Context Bar button | `taxonomy-context-compare.js` | ✅ Branch-to-branch comparison with semantic diff |
| Copy Back Button | Context Bar (read-only) | `taxonomy-context-bar.js` | ✅ Selective transfer from read-only to editable |
| Sync Status Indicator | Git Status Bar | `taxonomy-workspace-sync.js` | ✅ Shows sync state (synced/behind/ahead/diverged) |
| Dirty State Indicator | Workspace Badge | `taxonomy-workspace-sync.js` | ✅ Changes badge color when dirty |
| Sync Sub-Tab | Versions → Sync tab | `taxonomy-workspace-sync.js` | ✅ Sync from shared / Publish buttons |

---

## 3. Remaining Gaps

| Gap | Priority | Description |
|---|---|---|
| Compare Diff View Enhancement | Medium | Current compare shows summary + semantic changes. Could add inline diff highlighting. |
| Workspace Switcher | Low | Currently no way to switch between multiple workspaces from the UI (admin can view all). |
| Conflict Resolution UI | Low | Merge conflicts are reported but not visually resolved in the UI. |
| History Timeline Visualization | Low | Navigation history is available via API but not visualized as a timeline in the UI. |

---

## 4. JavaScript Module Inventory

| # | Module | Purpose | Workspace-Aware |
|---|---|---|---|
| 1 | `taxonomy.js` | Main entry, tab routing, status polling | ✅ |
| 2 | `taxonomy-git-status.js` | Git status bar polling/rendering | ✅ |
| 3 | `taxonomy-workspace-sync.js` | Sync status, dirty state, local changes | ✅ |
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

---

## 5. Data Requirements for UI

| UI Component | API Endpoint | Data Returned |
|---|---|---|
| Workspace Badge | `GET /api/workspace/current` | WorkspaceInfo (branch, user, context) |
| Git Status Bar | `GET /api/git/state` | RepositoryState (branch, HEAD, staleness, operations) |
| Sync Indicator | `GET /api/workspace/sync-state` | Sync status, unpublished count |
| Context Bar | `GET /api/context/current` | ContextRef (mode, branch, commit, origin, dirty) |
| Compare Dialog | `POST /api/workspace/compare` | ContextComparison (summary, changes, diff) |
| Variants Browser | `GET /api/git/state` | List of branches with commit info |
| Local Changes | `GET /api/workspace/local-changes` | Change count per branch |
| Dirty Check | `GET /api/workspace/dirty` | Boolean dirty flag |
| Projection State | `GET /api/workspace/projection` | Projection commit, staleness |
