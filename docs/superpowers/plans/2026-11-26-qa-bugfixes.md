# Confirmed QA Bugfixes Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` for the independent packages below. Root owns integration, commits and publication.

**Goal:** Fix the ten functional defects from the QA report against commit `89c32b5adba6d319c04352a412d2bc9c257aedb2` and preserve each verified package on the authorized remote branch.

**Architecture:** Keep the existing UI, server contracts and standalone Sparx plugin. Correct event boundaries and asynchronous ownership in the UI; preserve native reference semantics and explicitly validate unsupported evidence changes in the plugin.

**Tech Stack:** Java 25, Maven, vanilla JavaScript, Node test runner, SQLite/JDBC, JUnit.

**Spec:** User-supplied report “Taxonomy QA bis zum QEA Export”, 2026-11-26, findings UX-01–06 and QEA-1–4. Original reproduction programs remain separate QA evidence, not production source.

## Global Constraints

- Preserve current validation, tenant authorization, review requirements and strict compiler diagnostics.
- Use Java 25 and UTF-8 through `StandardCharsets`; do not change production worker sizing.
- Add regression tests that exercise production behavior and demonstrate the original failure.
- Do not mistake previous lost-run evidence for validation of reconstructed code.
- Use one dedicated checkout on `fix/qa-export-workflow-20261126`; no main-branch changes or force pushes.
- Root commits and pushes each tested package using the existing user authorization.

## Review Focus

- Field clicks, Enter, double submission and disabled/read-only actions must respect one mutation boundary.
- A rendered snapshot must supply the mapping identity and decision expected by the real server.
- Manual input, project changes and dialog closure must invalidate obsolete asynchronous suggestions.
- Referenced but absent native IDs must remain absent rather than acquire accidental meaning after export.
- Unsupported supplied native edits and memo-tag source evidence must not be silently discarded.

## Task 1: Portfolio actions (UX-01, UX-02, UX-03, UX-05)

**Files:** `taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio.js`, `taxonomy-portfolio-async.js` in the same directory; `taxonomy-app/src/main/resources/templates/projects.html` if needed; new `.github/scripts/portfolio-form-actions.test.mjs`.

**Interfaces:** Existing portfolio API and review dialog, real rendered forms and snapshot DTOs. Verify actual controller paths and JSON fields before writing assertions.

- [ ] Write regressions for Confirm/Reject, solution/product coverage, candidate form and empty-project analysis.
- [ ] Run `node --test .github/scripts/portfolio-form-actions.test.mjs` and record the expected baseline failures.
- [ ] Read `data-status` from the clicked mapping action, route form mutations through valid submit events, reject missing candidates and show empty-project feedback.
- [ ] Test input clicks cause zero mutations, valid Enter/button submit causes exactly one mutation, invalid percentage/identity is rejected, and read-only/busy states remain effective.
- [ ] Run the new tests plus affected existing JavaScript suites; review backend compatibility.
- [ ] Commit and push the verified package; verify the remote head.

## Task 2: Requirement intake and suggestion ownership (UX-04, UX-06)

**Files:** `taxonomy-app/src/main/resources/static/js/shared/requirement-intake.js`; `taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-projects.js`; `.github/scripts/requirement-intake.test.mjs`; new focused key-suggestion regression test if needed.

**Interfaces:** Existing intake bindings and project requirement dialog; no changes to the portfolio action files owned by Task 1.

- [ ] Add regressions: whitespace-only text reports a visible field error and focus without mutation; late suggestion cannot replace user input after project change or dialog dismissal.
- [ ] Run the regressions against the baseline and record the intended failures.
- [ ] Add field validation and guard asynchronous success/error by the current project, dialog lifetime, request generation and unchanged input.
- [ ] Verify valid text and a current suggestion still work, validation clears on correction, and obsolete failures remain silent.
- [ ] Run the affected Node suites, review, commit and push this independent package.

## Task 3: Native QEA integrity (QEA-1, QEA-2, QEA-3, QEA-4)

**Files:** Standalone production and test sources under `plugins/taxonomy-sparx-plugin/src/{main,test}/java/com/taxonomy/plugins/sparx/`, following current database, reference and writer boundaries.

**Interfaces:** Existing export SPI, generic QEA writer and guided ADMBw writer, real SQLite fixtures.

- [ ] Reproduce object-ID capture, package-ID capture, unsupported Alias edits and memo Source/Quelle evidence in focused JUnit regressions.
- [ ] Run those regressions against the baseline and record the intended failures.
- [ ] Reserve native IDs already referenced by preserved base rows, including absent IDs, in automatic and explicit/frozen allocation paths shared by both writers.
- [ ] Reject supplied changed unsupported native evidence before unchanged-output fast paths; omitted evidence does not imply deletion.
- [ ] Resolve Source/Quelle memo values from their actual Notes, retaining existing ambiguity and size checks.
- [ ] Run the complete standalone plugin suite against actual SQLite files; review existing-row and unknown-schema preservation.
- [ ] Commit and push the verified package; verify the remote head.

## Task 4: Integration and closure

- [ ] Register new Node suites in `.mvn/javascript-junit-sources.txt` and verify the shared handler/API contracts.
- [ ] Run the combined affected JavaScript suites and relevant Java tests on the final source.
- [ ] Review the complete branch independently, resolve material findings and record exact test scope.
- [ ] Publish remaining changes and report the remote branch, commits, ten findings and any unperformed browser/Enterprise Architect acceptance honestly.
