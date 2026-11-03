# Unified Architecture Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** One coherent Taxonomy application with a shared architecture workspace,
source-aware lens and preserved feature behavior.

**Architecture:** Compose existing Thymeleaf editor/workbench content in the main
application. Share diagram rendering/interaction; retain exact source adapters and
existing mutation boundaries. Add read-only investigations of authorized saved
analysis evidence and drafts through the existing bounded hypothesis engine.

**Tech Stack:** Java 25, Spring Boot, Thymeleaf, existing modular JavaScript/D3,
Maven-owned JUnit/Node/Playwright verification.

**Spec:** `docs/superpowers/specs/2026-11-03-unified-architecture-workspace-design.md`

## Global Constraints

- Preserve existing features, immutable evidence, exact-context authorization,
  CSRF, provider budget/validation and explicit preview/adoption/acceptance.
- No iframe, new frontend framework, replacement persistence or invented catalogue
  identities. No paid/shared model endpoint calls in tests.
- Preserve DE/EN, keyboard/touch and narrow-screen use, contextual help, existing
  source/provenance/export/print and editor recovery/undo/checkpoint behavior.
- Do not weaken coverage, error auditing or tests to obtain a green run.
- Canonical command: `./mvnw -B verify -Pci -DrunOnnxTests=true`.

## Review Focus

- Delayed responses after a source/branch/requirement switch cannot update current
  selection, URL, context, hypotheses or drafts.
- Shared/private workspace pins and application base paths survive old and new
  deep links, navigation, export and source switching.
- Partial/stale/empty analyses stay honest; unavailable source has explicit empty
  state and never silently substitutes the editor model.
- Snapshot/historical inspection cannot mutate evidence or authorize a working
  model command without explicit target preview and acceptance.
- Hidden graph panes and 390px/400% layouts preserve fit/zoom/focus and reachability
  of all existing actions, including print/export and draft leave guards.

## Task 1: Authorized read-only architecture investigations

**Files:**
- Modify `taxonomy-app/src/main/java/com/taxonomy/composition/architecture/ArchitectureFocusSuggestionService.java`.
- Create source DTO/service/controller in the same composition package, with
  tests under `taxonomy-app/src/test/java/com/taxonomy/composition/architecture/`.
- Modify security matchers only if required to expose the same authenticated,
  source-authorized read-only operation; add real security regression coverage.

**Interfaces:** Keep the existing editor request/response/API backward compatible.
Add `POST /api/architecture/focus/suggestions` accepting `{source, sourceId,
candidateLimit, useAi, language}`. Source explicitly identifies `ANALYSIS_DRAFT`
(repositoryId, workspaceScopeKey, branch, version) or `ANALYSIS_SNAPSHOT`
(repositoryId, workspaceScopeKey, branch, projectId, requirementId,
requirementVersionId, snapshotId). Return `{source, sourceId, sourceHash, method,
outcome, modelCalls, candidateCount, suggestions}`; suggestions retain the existing
typed/evidence format. Source authorization and exact revision/version checks
precede all inference. No browser-supplied graph is trusted. Source response is
read-only and must not impersonate an editor semantic revision.

- [x] Add failing tests for inspecting the supplied retained source rather than
  the editor, stale draft version, wrong source pins/ownership, invalid element,
  partial analysis, immutable snapshot and no writes/provider calls on rejection.
- [x] Extract/reuse the bounded existing candidate/inference engine with a
  source-neutral internal model input; keep existing editor behavior and tests.
- [x] Implement authorized source loaders and DTO/controller; preserve catalogue
  identity and exact snapshot provenance. Limit payloads and graph size using
  existing bounded contracts.
- [x] Run affected Java/security tests and record evidence; commit only Task 1.

## Task 2: Shared diagram rendering and interaction

**Files:**
- Create `taxonomy-app/src/main/resources/static/js/shared/architecture-diagram.js`.
- Modify `static/js/shared/taxonomy-impact-map.js`, `static/js/architecture-workbench.js`
  (diagram portions only), `static/js/architecture-editor-renderer.js`.
- Add focused `.github/scripts/architecture-diagram*.test.mjs` tests through the
  existing Maven test discovery. Coordinate template script inclusion with Task 3.

**Interfaces:** Export `window.TaxonomyArchitectureDiagram` with shared scene,
selection/filter/viewport/rendering utilities. Existing public adapters remain
compatible. A renderer exposes render/select, focus/unfocus, fit/zoom, viewport
capture/restore, optional region/suggestion callbacks and destroy. Preserve exact
server scene positions for snapshot/editor; analysis retains its bounded layout.
Source adapters may retain metadata/detail formatting, not duplicate the shared
rendering or interaction algorithms. Document exact signatures in task report for
Task 3 integration.

- [x] Add failing behavior tests for identical focus/unfocus and selection across
  sources, retained viewport on refresh, hidden-pane fitting and keyboard/touch
  selection. Preserve source-specific node metadata/directions and server layout.
- [x] Extract the common engine and adapt the three active rendering paths. Keep
  existing styling/labels, detail actions, contextual nodes and source callbacks.
- [x] Ensure lens overlays and callbacks work for all adapters, with teardown and
  no duplicate event listeners.
- [x] Run relevant existing and new JS/browser tests; commit Task 2.

## Task 3: One navigation and one architecture workspace

**Files:**
- Modify `templates/index.html`, `templates/fragments/application-chrome.html`,
  `static/js/shared/application-chrome.js`, `static/js/shared/architecture-workflow.js`.
- Extract existing editor/workbench bodies to reusable template fragments and
  compose them in the Architecture section; preserve all existing control IDs.
- Create `static/js/shared/architecture-workspace.js` and scoped workspace CSS.
- Convert editor/workbench bootstrap into lazy, suspendable adapters without
  changing their business commands; keep legacy standalone compatibility.
- Update focused route/navigation/browser tests and relevant DE/EN strings.

**Interfaces:** `TaxonomyArchitectureWorkspace` owns current source and provides
`open(source, options)`, `getSource()`, `select(elementId)`, `openLens()` and
`canLeave()` (async when needed). Source mode URL is `architectureSource=analysis`,
`snapshot` or `working`, with existing exact context parameters; `#architecture`
is the single main Architecture destination. Existing `/architecture/editor` and
`/architecture/workbench` deep links resolve into the same surface retaining
selected element, snapshot, revision/commit, layout and provenance.

- [x] Add failing tests for one semantic Architecture entry, old deep-link
  compatibility, source switches without wrong model, base-path/private pins and
  guarded dirty forms.
- [x] Compose fragments with scoped styles and lazy lifecycle; hidden adapters
  cannot load/mutate current visible context or overwrite source URL state.
- [x] Consolidate main navigation, maintain all file/project/settings/help and
  source-specific commands; retain keyboard and responsive affordances.
- [x] Preserve selection/viewport per exact source and integrate shared renderer.
- [x] Verify navigation and existing editor/workbench workflows; commit Task 3.

## Task 4: Lens on displayed evidence and explicit adoption

**Files:**
- Modify `static/js/architecture-focus.js`, `static/js/core/taxonomy-analysis-guidance.js`
  and workspace/source adapters; create shared read-only focus API adapter.
- Reuse lens panel controls with scoped roots/IDs and read-only review capability.
- Integrate existing `analysis-snapshot-adoption.js` without bypassing preview.

**Interfaces:** The workspace invokes the Task 1 read API for retained analysis
sources and the unchanged editor API for current working revisions. Hypotheses
retain their source identity/hash. Read-only panels expose examine/dismiss/locate
and explicit adoption entry; only the editor can accept an architecture command.

- [x] Add failing tests that lens opens in place on selected analysis/snapshot,
  does not switch to editor, and ignores replies from departed source revisions.
- [x] Wire bounded queue, pause, budget, local/AI mode and suggestion overlays to
  the source adapter. Preserve existing writable accept/undo path.
- [x] Verify source/snapshot evidence remains unchanged through inspection and
  explicit adoption leads to the retained canonical source view.
- [x] Run focus/adoption/draft-navigation regression tests; commit Task 4.

## Task 5: Complete workflow acceptance, documentation and review

**Files:** Existing browser journeys and test catalogue registrations; current
DE/EN user/architecture editor documentation; concise QA findings and selected
real screenshots with exact source provenance.

- [ ] Execute complete deterministic analyze/inspect/snapshot/adopt/edit/undo/
  checkpoint/history/export/print/return workflows in DE/EN and narrow/keyboard.
- [ ] Update old route/selector assertions without weakening substantive tests;
  keep all feature parity checks and error audits.
- [ ] Run focused suites followed by canonical Maven verification and applicable
  CI gates. Separate any unavailable local prerequisites from actual passes.
- [ ] Independent final code/ergonomics review; fix concrete findings and rerun
  only affected verification plus required gates.
- [ ] Publish one reviewable PR with source-bound evidence and precise limitations.
