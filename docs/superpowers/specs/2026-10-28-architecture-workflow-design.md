# Connected architecture workflow: first corrective slice

The requested user journey is: enter a requirement, clarify actual missing
decisions, inspect its architecture, relate existing/planned portfolio items,
refine with mouse and AI, and retain understandable versions.

## Evidence and limits

Baseline: `ed60382a4b56629f2407848fd8d3330deeb7f198`. The exact CI application
artifact (run 40737095664, artifact 12837992085) was downloaded, its archive and
distribution checksums verified, and the application started successfully with
the mock provider and embeddings disabled. A personal browser walkthrough could
not run: the local browser's Unix socket was denied, sandbox escalation was
rejected, and the provided cloud browser blocked the local address. Existing
commit-bound CI screenshots were inspected; they are not a new manual test.

Code inspection establishes that the workbench presents immutable analysis
evidence, while the editor edits a DSL working revision. Its bare editor link
does not carry that provenance or explain the artifact change. The workbench
does not offer a direct return to its requirement. Standalone portfolio API
calls do not carry the explicit workspace pin from their page URL. Long element
and history lists dominate the editor page in the CI capture.

## Approach

Keep the current modular monolith and extend its existing Thymeleaf/JavaScript
views. A replacement frontend would enlarge the risk without resolving these
identity boundaries. Cosmetic consistency alone would leave misleading
handoffs intact. The first change therefore connects the existing views using
explicit source identities and makes their different mutation semantics clear.
This is an implementation choice, not a claim of an agreed complete redesign.

## Contract

- A loaded snapshot shows project, requirement and snapshot identity, with direct
  links to the exact requirement analysis, clarification and project portfolio.
- The editor action is named **Open architecture working state**. It states that
  the immutable analysis is not being adopted or edited. It passes the recorded
  repository/workspace/branch and selected element, never interprets an analysis
  snapshot ID as an editor revision or checkpoint, and retains a return link.
- An incomplete source identity disables that handoff; do not guess a repository
  or branch. Shared workspace is an explicit blank `workspaceId`, not omission.
- Standalone project API requests and application links preserve an explicit
  workspace pin. An explicitly different destination wins. External addresses
  and non-project APIs remain unchanged. The analysis page's live session router
  retains ownership of its own transport.
- All labels exist in English and German. Titles are rendered as text.
- The editor's element and history lists have bounded scrolling regions with
  accessible names and keyboard access. Mobile content remains reachable.
- Existing review/acceptance, operation history, checkpoints, and authorization
  stay authoritative; navigation never creates or approves domain data.

## Additional failure reproduced during the task

The default application disables the multi-repository catalogue API. The
editor's **Create working copy** command nevertheless called that API and
returned HTTP 404. The editor now has a dedicated command endpoint backed by
the existing repository working-copy service. It verifies the currently selected
central repository and branch plus repository CONTRIBUTOR permission before
creating a copy. A private workspace is never substituted with its central
source, and opening a link still performs no write. The catalogue feature flag
keeps its existing meaning.

## Still needed for the complete product goal

A real connected acceptance run, a deliberate snapshot-to-working-model adoption
workflow with semantic diff/provenance, unified task navigation across all views,
and domain evaluation of supplier questions and portfolio recommendations remain
separate work. Green navigation tests cannot establish those outcomes or a
world-class usability claim. PR #1334 already supplies analysis next steps and
selection synchronization and must be reviewed alongside this change.
