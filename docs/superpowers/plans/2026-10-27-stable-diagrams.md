# Stable Diagrams Implementation Plan

> **For agentic workers:** Use the existing implementation and review workflow.
> Steps use checkbox syntax to track observable deliverables.

**Goal:** Preserve analysis reading position, unify relevance controls and provide
continuous large-format diagram printing with the recorded reasoning.

**Architecture:** Reuse the browser's relevance projection and measured tree
layout. Retain view state separately from score evidence. Carry existing reasons
through live progress and completion envelopes.

**Tech Stack:** Java 25, Maven, Spring, browser JavaScript, D3, Selenium/Chromium,
Playwright and PDFBox already present in this repository.

**Spec:** `docs/superpowers/specs/2026-10-27-stable-diagrams-design.md`

## Global constraints

- No new dependencies, workflows, remote model calls or catalogue identities.
- Tests remain Maven-owned; focused runs do not replace the canonical CI gate.
- Work directly from main on one independent branch.

## Review focus

- Filtered-out selected nodes fall back to a surviving ancestor.
- SVG/Canvas transitions and view switches preserve the reader's position.
- Progress reasons can arrive after a score; the UI must still refresh.
- Print selection excludes zero/unknown siblings and their saved reasons.
- Large sheets retain all text and use actual millimetre paper dimensions.

## Tasks

1. **Viewport state** — `core/taxonomy-views.js` and the existing diagram browser
   workflow. Add a failing repeated-render test; retain transform, scroll,
   expansion and Sunburst focus. Expose `prepareDiagramContext(container,
   {analysisKey, view, treeRoot})` for the dispatcher. Verify actual browser state.
2. **Common selection and controls** — `core/taxonomy-browse.js`, common controls
   in `core/taxonomy-views.js`, localized messages and existing browser/list
   contracts. Apply the common projection consistently and preserve source data.
3. **Available reasons** — analysis progress registry, stream callback/event
   mapping and `core/taxonomy-scoring.js`. First reproduce missing reasons in
   running snapshots and terminal events, then carry complete bounded preview
   reasons and authoritative final reasons. Verify reason-only updates.
4. **Complete print sheets** — print functions in `core/taxonomy-views.js`,
   existing `TaxonomyDecisionDiagramUiIT` and `TaxonomyTreeLayoutIT`. Add separate
   paper/layout controls, continuous SVG sheets and per-taxonomy grouping.
   Assert PDF dimensions/counts and selection with real Chromium/PDFBox.
5. **Integration** — run focused and broader relevant Maven checks, inspect
   actual captures, update German/English guides and request an independent
   review of the complete change. Check the required canonical gate's prerequisites
   and report any missing runtime precisely. Publish a reviewable independent PR.

## Execution record

- Baseline: 102 selected JavaScript contract cases passed through Maven.
- Root causes confirmed: renderer recreation loses view state; current large
  sheet assembles already fragmented A4 sections; progress snapshots omit reasons.
- [x] Tasks 1–4 verified together: 1,489 Maven-owned JavaScript callbacks,
  273 focused backend cases and 72 browser cases passed. Verification was
  repeated after integrating main's root-clarification changes from #1318.
- [x] German/English documentation and source-identified toolbar illustrations
  updated; independent review findings reproduced, corrected and closed.
- [x] Independent main-based change prepared for PR publication; verification
  commands and scope are recorded in `docs/testing/stable-diagrams-and-print.md`.
- Canonical CI remains required. The local prerequisite check found neither
  the Docker executable nor a Docker socket, so the full gate was not run here.
- Integration base: `908b5aee022cb93377d01fa01fb534842ff95aad`. Completion events
  retain reasons and assessment coverage together; coverage-only changes update
  existing filtered list/tab headings without losing focus. The additional
  independent integration review found no remaining correctness issue.
