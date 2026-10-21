# Content language and readable evidence implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent implementation tasks below. The user approved implementation in the conversation.

**Goal:** Generate new analysis explanations in a separately selected language and keep complete, readable explanations and percentages available in diagrams, print, Word and Visio.

**Architecture:** Capture a typed `analysisLanguage` (`de`/`en`) alongside the existing reason-length setting. Persist it through the existing analysis, recovery and snapshot paths. Keep report language separately selectable using existing report parameters. Repair layout and text-preservation at the existing renderers, without changing scoring or adding a report engine.

**Tech Stack:** Java 21, Maven/JUnit, Spring, Thymeleaf, existing browser JavaScript and SVG/DOCX/Visio renderers.

**Spec:** Accepted in-chat design and implementation request dated 2026-10-21; `docs/dev/ANALYSIS_LANGUAGE_POLICY.md` supplies the language contract. Historical translation is a separate follow-up; this change preserves original stored reasons.

## Global constraints

- AI language: follow UI (default), German, English. Document language: follow stored analysis (default), UI, German, English.
- Personal controls must not mutate installation-wide administrator preferences.
- Resolve new-run language once; queued work, parallel tasks and resume retain it.
- Missing historical language remains unknown; do not assign invented provenance.
- Preserve source requirements, identifiers, numeric score semantics and stored evidence.
- Keep complete important reasons visible in exported artifacts; compact diagrams may link to full readable evidence sections.
- Reuse Maven-owned JUnit and browser test fixtures; no new CI workflows or real-model test campaign.
- Work on one branch based on current main. Serialize Maven builds in the shared checkout.

## Review focus

1. UI English with German output and its inverse; user/account isolation.
2. Changed UI/preferences during queued or resumed analyses; historical null metadata.
3. Tall narrow and wide diagrams; full tree expansion and relevant-only selection.
4. Long multiline reasons with a unique final sentence; percentages including fractional values.
5. Print/export content independent of viewport clipping and transient tooltips.

## Task A: Frozen analysis language

**Files:** Domain DTOs, analysis commands/controllers/prompt/context/recovery/cluster, portfolio jobs and compatible migrations.

**Interface:** `AnalysisLanguage` enum serializes `de`/`en`; `AnalysisResult.getAnalysisLanguage()` is nullable and `.value()` returns the language tag. Browser request/result property is `analysisLanguage`.

- [x] Extend focused prompt and continuation/JUnit cases for explicit language and fixed resumed language.
- [x] Implement capture, propagation, persisted metadata and prompt instructions across scoring and relation text.
- [x] Verify compatibility and affected persisted job/migration contracts.

## Task B: Diagram controls and print

**Files:** `taxonomy-views.js`, diagram/print CSS and existing Maven-owned diagram fixtures.

- [x] Reproduce tall narrow print, missing scores/reasons and inaccessible expansion/filter controls.
- [x] Make full expansion and relevance selection available at the diagram; maintain measured geometry.
- [x] Produce readable print layout with complete values and evidence, including the single-large-page option.
- [x] Verify representative tall/wide trees and long reasons using the production renderer.

## Task C: Complete report and Visio evidence

**Files:** Reporting renderers/controllers, snapshot report adapters, Visio/export renderers and existing JUnit cases.

- [x] Identify actual truncation/geometry points and pin long-text preservation regressions.
- [x] Reuse language labels for relevant live/frozen exports and default to recorded analysis language.
- [x] Preserve full visible reasons with wrapping, layout or linked evidence sections as required.
- [x] Verify native DOCX/Visio contents and output layout.

## Task D: Browser integration and final verification

**Files:** Main template, language preferences helper, analysis state/session/save/load, export dialog and callers, bilingual help.

- [x] Add personally stored language controls; send selected language for each new analysis.
- [x] Preserve recorded language through browser drafts, saved analyses and report requests.
- [x] Add document-language selection to existing export flows with an honest source-language notice.
- [x] Run targeted integrated Maven/JUnit verification and review the complete diff.

**Delivery handoff:** Publish this branch as a reviewable PR against main and inspect existing CI. Report pending checks as pending; merging and deployment are separate actions.

## Verification results

- Final verified set: 1,057 tests in 66 existing Maven-owned suites, with no remaining failures, errors or skips. The broad focused reactor run was followed by a strict 58-test rerun of the eight affected report, Autopilot and browser suites after correcting two nested Mockito fixture stubs and the print continuation identity marker.
- The existing Decision and Tree Node drivers verify complete original explanations, selected area-caption evidence, fractional percentages, and exact source-node identities. Continuation text fragments remain visible without being counted as additional nodes.
- Chrome/ChromeDriver 149.0.7827.55 exercised the existing local-browser profile, including real PDF generation, A4 pagination, one large sheet, four ordinary hierarchy levels, both Tree renderers, shared relevance controls and accessible rationale actions.
- Word fixtures were rendered and visually inspected, including long compact-report explanations and separate saved relationship selection/review reasons. Visio package contracts and evidence pages were checked with the existing native consumer; Microsoft Visio Desktop was not available in this environment.
- HSQLDB restore migration and backup/restore tests passed. The PostgreSQL-specific migration integration test requires a configured PostgreSQL environment and was not run locally.
- No new test framework, CI workflow or external model call was introduced. Maven build caching was disabled for the verification runs; final whitespace checks passed.
