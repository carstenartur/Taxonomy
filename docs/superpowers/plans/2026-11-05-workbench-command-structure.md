# Workbench Command Structure Implementation Plan

> **For agentic workers:** Use executing-plans to implement inline, with one fresh whole-branch review.

**Goal:** Apply the agreed separation of commands, work navigation, views and status.
**Architecture:** The common Thymeleaf header owns five command menus and a separate work navigation. A shared command adapter invokes existing feature controls and observes their availability; features retain state and business operations. Contextual actions remain below the common navigation.
**Tech Stack:** Thymeleaf, browser JavaScript/CSS, Java 25, Maven, Selenium and Playwright.
**Spec:** `docs/superpowers/specs/2026-11-05-workbench-command-structure-design.md`

## Global Constraints

Preserve source scope, return URLs, permissions, drafts, existing page aliases and normal CI gates. Delete obsolete production paths and corresponding obsolete structural assertions. Do not publish without the outstanding direct push approval.

## Review Focus

Unavailable or readonly source commands must stay disabled; changing source must refresh availability. Declining draft departure must preserve selection and URL. Nested menus must close one level at a time and restore visible focus. Standalone and mounted pages must retain scope and authority. Progress and failures must remain discoverable with menus closed.

### Task 1: Shared command adaptation and native menu lifecycle

Files: new `static/js/shared/application-commands.js`, common chrome JS/HTML, owned native browser tests.
Interface: `TaxonomyAppCommands.refresh()` and `execute(id)` adapt `data-app-target` controls; page/source modules remain the state owners.
- [x] Add failing native cases for five menu categories, separate work navigation, proxy availability and single execution.
- [x] Implement shared markup, command adapters, native keyboard/menu lifecycle and responsive layout.
- [x] Verify inactive/readonly controls, source transitions, mounted routes and context retention.

### Task 2: Feature integration and deletion

Files: index page, taxonomy-utils/onboarding/browse, role surface, contextual chrome, native/Selenium helpers.
- [x] Move analysis commands into File, administration/appearance/language into Tools, history/source into View.
- [x] Route menu and toolbar calls through existing feature controls; move recovery/status out of Settings.
- [x] Remove mainNavTabs, its parallel keyboard/ARIA machinery, duplicated navigation bindings and theme handling.
- [x] Adapt the existing behavioral checks to the new user paths; retain their functional assertions.

### Task 3: End-to-end validation and visual inspection

Files: shared browser tests, existing Selenium views, bilingual user guides and QA captures.
- [x] Run owned JS contracts and native browser cases through Maven.
- [x] Run rendered-page navigation, document/admin and architecture workbench checks on Java 25.
- [x] Inspect desktop, tablet/mobile, dark/custom/forced colors and actual page screenshots.
- [x] Review the complete change independently; fix important findings with reproductions.
- [x] Commit the concrete result and report verification and the publication boundary.

## Final validation record

- Java 25 / Maven Wrapper: full application reactor compiles; 187 targeted cases pass across the nine owners listed in the QA record. The final corrective confirmation contains 83 passing cases.
- Maven-owned JavaScript: 1,936 passing nonbrowser contracts and 92 passing native browser cases.
- Independent whole-change review: three reproduced P2 findings corrected and independently rechecked (12 focused cases pass).
- Actual Thymeleaf screenshots inspected at desktop/phone sizes and in dark appearance; artifacts and provenance are in `docs/qa/2026-11-05-workbench-command-structure.md`.
- Removed production selectors/controllers and obsolete test references checked; `git diff --check` passes.
- Origin main was fetched and remains the branch base; publication requires the outstanding direct approval.
