# Unified architecture workspace

## Intent and authorization

The user requested implementation of the five-part consolidation described in the
3 November code review: one meaningful navigation, shared diagram interaction,
explicit source state, a lens on the displayed architecture, and complete workflow
acceptance without lost or degraded functionality. This document records the
implementation decisions within that authorized scope.

The baseline is `44c62f543ef64a093eb60ec9f11c0bcda872a008`. PR #1348 introduced a
common header but retained conflicting global and local Architecture destinations.
Live analysis, frozen portfolio evidence and the editable DSL model also retain
separate diagram interaction implementations.

## Product decisions

1. The existing application owns one main navigation. Projects, Analysis,
   Architecture, Graph, Versions and DSL each have one unambiguous destination.
   Existing file/project actions, administration, preferences, language and help
   remain accessible. Source-specific controls are actions, not a second copy of
   main navigation. Keyboard, touch and narrow-screen access remain supported.
2. The main application's Architecture section contains three explicit source
   modes: current analysis, saved analysis, and working architecture. A frozen
   snapshot and a historical Git revision are visibly read-only. A model source is
   identified by the exact authorized repository/workspace/branch plus the
   relevant draft version, snapshot or semantic revision/commit.
3. Existing editor and workbench functionality is composed into this application
   with Thymeleaf fragments and lazily initialized JavaScript adapters. No iframe,
   copied application, new SPA framework, or replacement persistence layer.
   Existing deep links remain compatible and resolve into the unified surface.
4. Analysis, snapshot and working-model diagrams share scene rendering and
   interaction primitives. Adapters own their legitimate differences: live
   analysis layout and bounded rendering, frozen server scene and provenance,
   mutable model commands and schema-driven forms. Preserve colors, relation
   directions, context nodes, focus/unfocus, selection, search, zoom/pan, fit,
   keyboard, touch, details, warnings and accessible names.
5. Clicking the lens examines the displayed source and selected element in the
   same surface. It never silently switches to the current editor model. Read-only
   sources produce hypotheses without modifying source evidence. The existing
   validated preview/adoption/editor-command path remains the only route to apply
   changes to a writable architecture. Source, revision and selected element must
   be revalidated before provider use; stale results must not attach to a new
   source. Local fallback remains explicitly unassessed.
6. Switching source or application view preserves drafts and current selection
   where applicable. Abort or ignore delayed responses for departed contexts.
   Hidden adapters must not update visible context, run investigations, or
   overwrite another adapter's URL state. Unsaved editor form changes receive the
   existing leave guard; in-page source changes need the same protection.

## Existing capabilities to preserve

- Analysis/Copilot, partial progress, relation proposals, continuation,
  reformulation, existing taxonomy diagram types and Graph Explorer linkage.
- Snapshot source identity, requirement version, warnings, review metadata,
  provenance, contextual expansion, exact server layout, print and all existing
  SVG/PDF/Word/ArchiMate/Visio exports and interoperability manifests.
- Editor elements/relations/packages, planning metadata, semantic preview,
  acceptance, rejection, deletion, undo/redo, checkpoint/recovery, private
  workspace provisioning, historical versions, source DSL and view selection.
- Portfolio projects/requirements, intake/reuse decisions, analyses, product and
  solution linkage, matrices, reports, imports, versioning and integrations.
- Existing authorization, CSRF, exact-context validation, privacy, feature gates,
  German/English translation and contextual help.

## Approaches and choice

Merely renaming the duplicate links leaves separate interactions and the wrong
lens target. Rebuilding a new frontend would enlarge the regression surface and
discard established adapters. The chosen implementation composes the existing
views into one application and extracts shared rendering/interaction while
retaining source-specific commands and immutable evidence. Internal source modes
are not separate applications or automatic conversions between data states.

## Verification and acceptance

Meaningful regressions must fail on the baseline: duplicate semantic navigation;
lens on an analysis leaves for the editor; source selection silently changes the
model; selection/viewport is lost on redraw; stale investigations affect a newer
source. Add targeted tests using existing Maven-owned JS/browser and Java harnesses.

Acceptance journeys cover DE/EN and desktop/narrow/touch/keyboard: analyze and
inspect an architecture; open a saved snapshot; select a node and investigate it;
review source identity; explicitly preview/adopt; edit and undo/redo; checkpoint;
open earlier evidence; export/print; return to analysis and preserve draft.
Existing tests must retain substantive assertions when selectors/routes change.

No weakening of coverage, security, source-identity, export, error-audit or CI
gates. The canonical command remains `./mvnw -B verify -Pci -DrunOnnxTests=true`.
Focused verification and unavailable local prerequisites are reported separately
from complete CI. Use deterministic providers; no paid/shared provider calls.
