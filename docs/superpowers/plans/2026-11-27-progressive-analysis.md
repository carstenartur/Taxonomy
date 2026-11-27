# Progressive Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a stable provisional architecture beside the live catalogue tree and refine it from a deliberately started detailed analysis.

**Architecture:** Extend the existing admitted preview DTO with canonical component eligibility. A small client presenter consumes local preview and generation-bound accepted scoring events; existing managed analysis owns provider execution and cancellation.

**Tech Stack:** Java 25, Maven, Spring, Thymeleaf, modular JavaScript, existing local/Artemis run monitor.

**Spec:** docs/superpowers/specs/2026-11-27-progressive-analysis-design.md

## Global Constraints

* Existing source admission and permissions remain authoritative.
* Local predictions never become saved assessments, architecture relations or accepted user decisions.
* No generative request from typing or opening the preview. Detailed analysis is explicitly started through the existing managed flow.
* Preserve effective relevance semantics; raw product suitability is not a relevance score.
* Exact input/source/operation ownership must survive edit-and-revert and late terminal results.
* No new infrastructure, dependencies or workflow-side test selection. DE/EN, keyboard and mobile support.

## Review Focus

* Two nodes sharing vocabulary do not acquire a fabricated architectural relationship.
* Navigation wrappers remain excluded even when their parent is absent from a bounded response.
* An old result after A→B→A cannot update the new draft or preview.
* Manual/imported score and reason changes preserve the managed conflict/review path.
* A focused candidate stays focused and stationary when scores/progress change.

### Task 1: Provisional architecture and scoped refinement

**Files:**
- Modify: taxonomy-domain/src/main/java/com/taxonomy/dto/CatalogueAnalysisPreview.java
- Modify: taxonomy-knowledge preview projection classes as needed to derive full-hierarchy candidate eligibility
- Modify: taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-preview.js
- Create: small reusable preview presenter/ownership module if useful
- Modify: taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js and existing managed analysis/recovery hooks only where necessary
- Modify: taxonomy-app/src/main/resources/templates/index.html (preview block/script list only), relevant CSS and analysis.preview.* DE/EN translations
- Test: existing preview Java and registered JS/browser contracts; focused generation/terminal ownership and eligibility tests
- Create: concise docs/testing/progressive-analysis.md with observable behavior and limitations

**Interfaces:**
- Consumes: admitted CatalogueAnalysisPreview, existing startManagedAnalysis/startPlan and run monitor, effective scored state after applyLocalRawScores.
- Produces: read-only provisional component layout and source-bound refinement status; a narrow documented presentation event with captured operation and source identity.

- [x] Write failing tests for eligibility, no typing side effects, stale generations and stable progressive presentation.
- [x] Implement canonical candidate metadata and independent read-only preview presenter.
- [x] Reuse explicit managed start and add guarded accepted-progress/terminal events; fence and cancel stale preview-owned runs without clearing manual evidence.
- [x] Run targeted tests plus existing preview/scoring/recovery/browser contracts.
- [x] Perform DE/EN desktop/mobile visual checks and one actual local model workflow; report cold and warm behavior separately.
- [x] Commit locally and provide focused review evidence.

## Integrated verification

On 27 November 2026 the integrated Java source passed 105 selected knowledge, analysis and application cases. Ten additional real Spring/ONNX cases passed with the pinned local MiniLM bundle, including the actual 2,572-entry catalogue and changed-requirement preview/architecture checks. The separate [embedding comparison](../../testing/european-embedding-comparison.md) records corpus preparation and warm-query measurements; these are not an end-to-end UI latency guarantee.

The final Maven-owned JavaScript selection passed 530 callbacks plus eleven ownership/scope cases. The complete analysis-UX browser source passed all 91 callbacks with Chromium 149 and the pinned Bootstrap stylesheet. The About/navigation source selection separately passed 62 callbacks. Fresh EN/DE component captures at 390 and 1180 px were inspected. The browser uses the real markup, styles and message bundles with deterministic transport fixtures; native model execution is covered by the separate ONNX run.

Independent task and whole-branch reviews resolved the lost-response, cancellation, manual-evidence and preview-refresh findings. The full browser selection then exposed a 39 px shift caused by removing a refresh button above the results. Moving that control beside the detailed action below the results preserves the original card positions at start, first score and terminal partial-result display; all four original browser assertions and the added transition checks passed.

These local selections supplement the canonical repository CI and its database, browser, packaging and native-model lanes. Their exact-commit outcomes are recorded on the pull request. Detailed provider work remains explicitly started per confirmed input version; relationship rendering consumes the canonical accepted partial or complete result.
