# Contextual Exports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Correct scoped decision evidence and offer configurable compact reports through one contextual export dialog.

**Architecture:** Extend the existing immutable decision report and renderer registry with typed options and source/scope evidence. Resolve selection in the existing report generator, then let DOCX and HTML render the same selected content. Reuse a single browser dialog across current and saved contexts.

**Tech Stack:** Java 21, Spring Boot, Apache POI, Java2D/SVG, vanilla JavaScript, JUnit, existing browser contracts.

**Spec:** `docs/superpowers/specs/2026-10-03-contextual-exports.md`

## Global Constraints

- Java 21 and the existing Maven reactor; no new production dependencies.
- No LLM calls, analysis mutations, invented scope, silent node loss or labels below 8pt.
- FULL and existing routes/constructors retain backward compatibility.
- Frozen reports resolve membership and evidence exclusively from the saved source.
- German and English copy; accessible keyboard and responsive interaction.

## Review Focus

- A selected successful root with failures elsewhere must retain an incomplete source status.
- Codes whose prefixes disagree with catalogue ancestry must follow actual ancestry.
- Zero-only roots, missing alternatives and long Unicode labels must survive tree export.
- A failed export must retain settings, restore controls and never download an error page.
- Template cover removal and landscape transitions must preserve source metadata and later portrait content.

### Task 1: Scoped report evidence and typed selection

**Files:** Add `DecisionReportOptions`, `DecisionReportScope` beside `DecisionRationaleReport`; modify `DecisionRationaleReportService`, `DecisionRationaleReport`, `DecisionRationaleScoreSemanticsAdapter`, `DecisionTreeOverview`; add `DecisionExportScopeTest` in architecture tests.

**Interfaces:** `DecisionReportOptions` owns `Profile`, `Contents`, `TreeLayout`, `Section`; `DecisionAnalysisInput.withScope(AnalysisScope, AnalysisCoverage)` carries frozen evidence. Existing `generate(...)` delegates to a new overload accepting options. `DecisionRationaleReport.scope()` carries available/included roots, original scope/coverage, complete tree and effective options.

- [x] Add regression tests for scoped completeness, selected summaries, out-of-scope selection, misleading codes and zero/missing alternatives.
- [x] Run `./mvnw -pl taxonomy-architecture -am test -Dtest=DecisionExportScopeTest -Dsurefire.failIfNoSpecifiedTests=false`; observe the missing behavior.
- [x] Implement types, real hierarchy membership and selection without changing source fingerprints or scores.
- [x] Run the new tests and existing `DecisionRationale*Test`/`DecisionTreeOverviewTest`; expect zero failures.
- [x] Commit the scoped evidence change.

### Task 2: Compact content and readable complete tree figures

**Files:** Add `DecisionTreeFigureRenderer` and a landscape Word section helper under `architecture/report`; modify `DecisionRationaleDocxRenderer`, `DecisionRationaleHtmlRenderer`, `DecisionRationaleTemplateRenderer`, `WordDocumentWriter`; add compact document/layout tests.

**Interfaces:** Figure renderer consumes `DecisionTreeOverview`, language and page budget and returns panels with SVG, PNG, covered node IDs, dimensions and accessible description. Renderers consume `report.scope().options()` and the same ordered tree evidence.

- [x] Add failing assertions for profile sections, short/no contents, portrait/landscape/portrait transitions, root isolation, every-node coverage and unreadable strict pages.
- [x] Run focused architecture report tests and inspect the expected failures.
- [x] Implement complete figures, profile composition, pagination and validated template-cover omission.
- [x] Run focused tests; render example DOCX files with LibreOffice and inspect pages and text.
- [x] Commit the document changes.

### Task 3: Saved and ad-hoc export contracts

**Files:** Modify `DecisionRationaleReportController`, `DecisionRationaleSnapshotReportService`, `DecisionRationaleSnapshotReportController`, `SnapshotWordReportService`; add API/snapshot regressions.

**Interfaces:** Ad-hoc input accepts `analysisScope` and `exportOptions`. Saved routes accept typed query options; a lightweight `.../decision-report/options` response exposes root names and recorded scope. Old Java overloads delegate to FULL. Snapshot graph context remains explicit.

- [x] Add failing tests for frozen scope/coverage, invalid root/enum input, unchanged source identity and options across DOCX/HTML/JSON.
- [x] Run the tests and verify the missing behavior.
- [x] Wire options and frozen evidence through existing services; filter optional graph content with explicit boundary context.
- [x] Run snapshot and HTTP report tests; expect zero failures.
- [x] Commit the API integration.

### Task 4: Shared contextual dialog and format audit

**Files:** Add `static/js/shared/decision-export-dialog.js`; modify `index.html`, requirement/workbench templates and download adapters, `taxonomy-browse.js`, `requirement-detail.js`, `requirement-copilot.js`, `portfolio-api.js`, `architecture-workbench-export.js`, and `taxonomy-export.js`; add Node/browser contracts and EN/DE documentation.

**Interfaces:** `TaxonomyDecisionExport.open({source, roots, analysisScope, selectedRoots, format, submit})` resolves a selection only on submit; `submit({format, options})` owns the existing authorized source request. Dialog options serialize through a shared query helper.

- [x] Add failing behavior tests for defaults, selection, format-specific options, Escape/focus, empty selection, download failure and retry.
- [x] Run the existing Node contract runner for the new tests and observe failures.
- [x] Replace decision format button groups with one action; reuse the dialog at each source context, preserve loading/error handling and baseline checks.
- [x] Add recorded scope/mode to CSV and record the other export contracts and limitations.
- [x] Run Node contracts and browser checks at desktop and 390px, including successful and failed downloads.
- [x] Commit the interaction and documentation.

### Task 5: Integrated verification and review

**Files:** Update `docs/testing/contextual-exports.md` with exact evidence and limitations.

**Interfaces:** All previous task contracts must remain consistent; use the same snapshot in source, report and UI checks.

- [x] Run focused Java report/HTTP/snapshot suites, the full relevant UI contracts and the existing architecture boundary checks.
- [x] Inspect generated Word pages, section transitions, complete trees and source identifiers.
- [x] Request one independent whole-branch review and fix material findings with regressions.
- [x] Commit verification evidence and create a reviewable feature PR without changing the shared main branch.
