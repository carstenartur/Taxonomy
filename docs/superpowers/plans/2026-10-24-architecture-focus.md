# Architecture Focus Lens Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans or
> superpowers:subagent-driven-development. Each owner changes only its assigned
> files; the coordinator integrates and commits the reviewed result.

**Goal:** Add the complete focus, investigation, review, accept/dismiss and undo
workflow to the existing architecture editor, with selectable stable/optimized
server layout.

**Architecture:** Suggestions are read-only projections of an exact durable
workspace revision. The app composition layer owns provider integration, the
existing command journal owns acceptance, and a presentation controller owns the
bounded queue and ghost overlays.

**Tech Stack:** Java 21, Spring Boot, existing DSL/journal, Thymeleaf, D3 7.9.0,
plain JavaScript, JUnit and existing Maven-owned browser verification.

**Spec:** `docs/superpowers/specs/2026-10-24-architecture-focus-design.md`

## Global Constraints

- No second application, invented catalogue identities, paid/live provider tests,
  added CI workflows, new frontend framework or lowered verification gates.
- Editor authority is WORKSPACE_REVISION; never accept through the Git proposal API.
- Scope/revision validation precedes model calls; results never silently rebase.
- Persist accepted provenance and full bounded rationale; preserve operation UUID undo.
- Renderer limits remain 200 nodes/400 edges including overlays.
- EN/DE, keyboard/touch, safe text rendering and context paths are required.

## Review Focus

- A slow result arriving after workspace/revision change must not become actionable.
- An ambiguous accept failure must retain the command UUID for idempotent retry.
- A local fallback or model failure must not be labeled as a validated finding.
- Relation provenance must survive reload, checkpoint and semantic undo/redo.
- Dense graphs, narrow screens and keyboard focus must remain usable.

## Task 1: Relation provenance through semantic commands

Files: `ArchitectureCommand`, `ArchitectureDslCommands`,
`ArchitectureEditorController`, `ArchitectureEditorService`, and their existing
DSL/workspace tests.

- [x] Extend `CreateArchitectureRelation` with immutable bounded properties while
  preserving its two-argument constructor. Allowed keys: `provenance`,
  `x-focus-rationale`, `x-focus-evidence`, `x-focus-revision`, `x-focus-method`,
  `x-focus-source-hash`. Values are at most 8,000 characters; reject controls,
  unsupported keys and nulls. Existing `WireCommand.properties` carries them.
- [x] Serialize properties atomically in the created relation block and include
  them in the command fingerprint. Empty properties preserve existing retry
  fingerprints and behavior.
- [x] Extend existing tests for roundtrip, invalid properties, changed-payload
  retry conflict and provenance-preserving undo/redo.

## Task 2: Exact-revision focus investigations

Files: new `ArchitectureFocusController` and
`ArchitectureFocusSuggestionService` in app composition architecture, plus focused
JUnit tests there. No changes to editor commands or frontend files by this owner.

Interface: POST `/api/architecture/editor/focus/suggestions`, body
`{context, sourceId, candidateLimit, useAi, language}` and matching If-Match.
`candidateLimit` defaults to 8 and is bounded 1..24. Language is `en` or `de`.
Response: `{context, sourceId, sourceHash, method, outcome, modelCalls,
candidateCount, suggestions}`. Outcomes: `ASSESSED`, `LOCAL_CANDIDATES`,
`UNASSESSED`, `NO_CANDIDATES`, `FAILED`. Method describes actual provenance.
Suggestion: `{id, sourceId, targetId, relationType, rationale, evidence,
properties}`. Evidence entries: `{elementId, field, quote}`; properties use Task 1
keys. All responses carry the captured exact context. Unknown/out-of-scope input
fails before generation; stale current state returns a conflict.

- [x] Write tests first for scope/revision, custom editor IDs, incompatible and
  duplicate relations, malformed response, unsupported quotations and local-only mode.
- [x] Read the exact current DSL through `ArchitectureEditorService`, resolve
  candidates from canonical elements and allowed directed triples, and include
  the selected requirement context when present without claiming it proves reality.
- [x] Use at most one bounded structured completion through existing LLM/prompt
  budget/control APIs. Validate response identities and exact source excerpts.
  Local candidates are explicitly unassessed. No model call in local-only mode.
- [x] Return full bounded rationale/evidence and persistence properties. No stored
  global jobs, no cross-user mutable cache and no automatic semantic writes.

## Task 3: Focus controller and graph interaction

Files: new `architecture-focus.js`; editor session, renderer, API client,
template, CSS, EN/DE bundles; existing browser tests or JUnit-owned Node harness.

- [x] Add deterministic queue tests for dedup, limits, pause, late responses and
  revision invalidation before implementation.
- [x] Add explicit focus activation, pointer/touch region intents, keyboard and
  selected-element equivalents, pinned review and bounded ghost overlays.
- [x] Use a per-context/revision sequential queue; default investigation budget
  16, range 1..128. Dwell 350ms. No background calls until the tool is activated.
- [x] Show actual state and method. Accept calls existing preview/execute with
  status accepted, properties and stable UUID; no extra modal. Dismiss stays local.
  Undo uses the accepted operation UUID. Errors remain actionable, including retry.
- [x] Preserve positions/camera on investigation and relation acceptance. Add
  semantic help for local candidates and pause behavior.

## Task 4: Explicit server layout strategy and verification

Files: layered layout service/projection/API/export strategy adapters and tests;
editor layout controls; concise existing user help documentation.

- [x] Add deterministic crossing-reduction ordering without new dependencies;
  stable identity/layer order remains the default.
- [x] Apply the selected strategy consistently to editor scene and exact-source
  exports, without changing architecture meaning or storing client graph state.
- [x] Exercise the real editor focus flow through existing browser infrastructure.
- [x] Run affected Maven tests and boundary checks, complete independent review,
  and prepare the feature PR against current main.
- [ ] Require canonical CI (`verify -Pci -DrunOnnxTests=true`) on the PR before
  merge; local focused verification does not replace Docker/ONNX/full browser gates.

## Verification evidence

The existing Maven-owned `role-state/forced-colors-architect-chromium` scenario
passed, including real focus suggestions, one-step journal acceptance, a lost
success-response/idempotent-retry fixture, operation undo, unchanged optimized
positions/camera, keyboard review, SVG/PDF and axe checks. Renderer fixtures cover
2/309/1,000/5,000 elements at narrow and wide sizes with ghost edges included in
the rendering limits. Existing reflow checks passed at 360 px and 720 px.

The focused service, DSL, journal, controller, layout, export, JS session and
architecture-boundary suites run separately from the full canonical CI gate.
Tests use deterministic local/mock provider boundaries; no paid model calls occur.
