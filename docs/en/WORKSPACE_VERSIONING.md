# Workspace & Versioning Guide

## Overview

The **Workspace & Versioning** system provides a user-friendly interface for managing architecture variants, version history, and team synchronisation within the Taxonomy Architecture Analyzer.

All workspace and versioning features are accessible from the **Versions** tab in the main navigation. The system is built on top of Git for robust version control, but presents a simplified, non-technical interface suitable for architects, analysts, and domain experts.

---

## Table of Contents

1. [Workspace Context Bar](#1-workspace-context-bar)
2. [Version History](#2-version-history)
3. [Variants (Branches)](#3-variants-branches)
4. [Comparing Versions](#4-comparing-versions)
5. [Restore & Revert](#5-restore--revert)
6. [Saving Versions](#6-saving-versions)
7. [Team Synchronisation](#7-team-synchronisation)
8. [Keyboard & Accessibility](#8-keyboard--accessibility)

---

## 1. Workspace Context Bar

The **Workspace Context Bar** is displayed below the navigation bar whenever a context is active. It shows:

| Element | Description |
|---|---|
| **Mode badge** | 🟢 Working version (editable), 🟡 Read-only, ⚪ Draft |
| **Branch name** | The current variant name (or "Hauptversion" for the main branch) |
| **Relative timestamp** | How long ago the last save was made (e.g. "Saved 5 min ago") |
| **Breadcrumb** | Shows the navigation path when viewing a derived variant |
| **Unsaved changes** | A pulsing red badge when changes have not been committed |
| **Sync status** | Inline badge showing pending publishes or available updates |

When viewing a variant or historical version, the context bar includes an origin indicator showing where you navigated from:

![Context Bar with origin indicator](../images/51-context-bar-with-origin.png)

In read-only mode, the badge changes to indicate that the workspace is not editable:

![Read-only mode badge](../images/50-read-only-mode-badge.png)

### Actions in the Context Bar

- **↩ Back** — Return to the previous context
- **🏠 To Origin** — Return to the original context
- **📤 Copy Back** — Copy elements from a read-only view back to the working version
- **🌿 New Variant** — Create a new architecture variant
- **🔍 Compare** — Open the comparison dialog

---

## 2. Version History

Navigate to **Versions → Verlauf** (History) to see the complete timeline of all architecture changes.

![Version History Timeline](../images/66-versions-timeline.png)

Each timeline entry shows:
- The commit message
- Timestamp and author
- Abbreviated commit hash
- Special markers for restore and revert operations (🔄 icons)

### Timeline Actions

Each version entry has the following action buttons:

| Button | Action |
|---|---|
| **👁 View** | View the DSL content at this version |
| **🔍 Compare** | Compare this version with the current state |
| **↩ Restore** | Restore the architecture to this version (with preview) |
| **❌ Revert** | Undo the changes from this specific commit |
| **🌿 Variant** | Create a new variant from this version |

---

## 3. Variants (Branches)

Navigate to **Versions → Varianten** to see all architecture variants displayed as cards.

Each variant card shows:
- The variant name with a 🌿 icon
- Whether it is the active variant (✓ Active badge)
- Whether it is the main version (🏠 Main badge)
- Metadata about the variant's relationship to the main version

### Variant Actions

| Button | Action |
|---|---|
| **➡ Open** | Switch to this variant |
| **🔍 Compare** | Compare this variant with the current version |
| **🔀 Integrate** | Merge changes from this variant into the current version (with preview) |
| **🗑 Delete** | Delete this variant (with confirmation dialog) |

### Creating a Variant

Click **🌿 New Variant** in either the context bar or the variants panel. Enter a name using lowercase letters, numbers, and hyphens.

### Merging Variants

When integrating a variant, the system shows a preview of the changes that will be applied, including counts of added, changed, and removed elements. You must confirm the merge in a modal dialog before it is executed.

![Merge preview modal](../images/60-merge-preview-modal.png)

For fast-forward merges (where no conflicting changes exist), the preview is simpler:

![Fast-forward merge preview](../images/61-merge-preview-fast-forward.png)

After a successful merge, a confirmation toast is displayed:

![Merge success toast](../images/58-merge-success-toast.png)

If a merge conflict occurs, the conflict resolution modal is shown (see also [User Guide §12](USER_GUIDE.md#12-versions-tab)):

![Merge conflict resolved](../images/53-merge-conflict-resolved.png)

### Deleting a Variant

Clicking **🗑 Delete** on a variant card shows a confirmation dialog before the variant is removed:

![Variant delete confirmation](../images/57-variant-delete-confirm.png)

---

## 4. Comparing Versions

Navigate to **Versions → Verlauf** and click **🔍 Compare**, or use the context bar's compare button to open the comparison dialog.

![Compare modal — branch selection](../images/48-compare-modal-branches.png)

The comparison view has three levels:

### Level 1: Summary Card
Shows a high-level overview with counts:
- 🟢 Elements added
- 🔴 Elements removed
- 🟡 Elements changed
- Relation changes

### Level 2: Three-Column Grid
Changes are displayed in three colour-coded columns:
- **Green column** — Added elements
- **Yellow column** — Changed elements
- **Red column** — Removed elements

### Level 3: DSL Diff (Expert Mode)
A collapsible section showing the raw DSL text diff with colour-coded lines:
- Green lines for additions
- Red lines for removals
- Blue lines for diff headers

![DSL diff view](../images/68-diff-view.png)

---

## 5. Restore & Revert

### Restore
Restoring a version creates a new commit with the content from the selected version. The version history is preserved — no data is lost.

Before confirming, the system shows a **preview** of the changes that will be applied:
- Number of elements added, removed, and changed
- A confirmation dialog with detailed information

![Version restore confirmation](../images/67-version-restore-confirm.png)

### Revert
Reverting undoes the changes from a specific commit. Unlike restore, it only undoes the changes from that one commit, not all subsequent changes.

Both operations use modal confirmation dialogs instead of browser alerts for a better user experience.

### Undo
The **Undo** button at the top of the versions tab removes the last commit from the branch history. This action also requires confirmation.

---

## 6. Saving Versions

Navigate to **Versions → Version speichern** (Save Version) to create a named snapshot.

1. Enter a **title** for the version (required)
2. Optionally add a **description**
3. Click **💾 Save Version**

The version is saved as a Git commit and appears immediately in the timeline.

---

## 7. Team Synchronisation

Navigate to **Versions → Synchronisieren** (Sync) to manage synchronisation with the shared team repository.

### Sync Status

The sync status is shown in multiple places:
- In the **context bar** as an inline badge
- In the **Git status bar** at the top of the page
- In the **Sync tab** with detailed information

| Status | Meaning |
|---|---|
| **Up to date** | Your workspace matches the shared repository |
| **Updates available** | The team has published changes you haven't synced yet |
| **Unpublished changes** | You have local changes not yet shared with the team |
| **Diverged** | Both you and the team have made changes |

The Sync tab shows the current synchronisation state with visual indicators:

![Sync tab — up to date](../images/63-sync-tab-up-to-date.png)

![Sync tab — ahead (unpublished changes)](../images/64-sync-tab-ahead.png)

![Sync tab — behind (updates available)](../images/65-sync-tab-behind.png)

When both you and the team have made changes, the status shows "Diverged":

![Sync diverged state](../images/55-sync-diverged-state.png)

If a sync conflict occurs, a resolution modal is shown:

![Sync resolve modal](../images/56-sync-resolve-modal.png)

### Sync Actions

- **📥 Sync from Team** — Pull the latest changes from the shared repository
- **📤 Publish for Team** — Push your changes to the shared repository

---

## 8. Keyboard & Accessibility

All workspace and versioning features are keyboard-accessible:
- Modal dialogs can be dismissed with **Escape**
- Tab navigation works through all action buttons
- Status changes are announced via ARIA live regions
- Colour is never the sole indicator — text labels accompany all status badges

---

## Related Documentation

- [GIT_INTEGRATION](GIT_INTEGRATION.md) — technical details of the JGit-backed DSL storage, repository architecture, and conflict resolution
- [REPOSITORY_TOPOLOGY](REPOSITORY_TOPOLOGY.md) — workspace provisioning model, topology modes, and data isolation
- [CONCEPTS](CONCEPTS.md) — glossary of workspace, variant, and sync terminology
