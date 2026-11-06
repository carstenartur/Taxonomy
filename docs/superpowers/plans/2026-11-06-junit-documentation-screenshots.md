# JUnit Documentation Screenshots Implementation Plan

> **For agentic workers:** Execute the authorized maintenance inline; delegate independent capture owners and localized documentation cleanup only.

**Goal:** Refresh retained documentation images through one existing Maven/JUnit entry, with complete current-run output validation.

**Architecture:** Keep `screenshots` as the entry point. Existing application ITs own real application captures, the existing report IT owns DOCX/report captures, and a final build IT invokes the existing export-component driver and verifies all active guide references. GitHub Actions runs the same command and publishes verified artifacts with provenance.

**Tech Stack:** Java 25, Maven Failsafe, JUnit 5, Selenium/Testcontainers, existing Playwright drivers.

**Spec:** User instruction: “Alles sollte über junit integrationtests startbar sein”; existing screenshot inventory in `.github/copilot-ref-screenshots.md`.

## Global Constraints

- Reuse existing capture owners; do not invent successful application states.
- Preserve the distinction between component examples and real persisted application journeys.
- Retire unsupported historical inline illustrations while preserving accurate functional documentation.
- All retained DE/EN guide images must be generated during the current run.
- Keep publication restricted to explicit main-branch dispatch; PR runs only produce review artifacts.

## Review Focus

- Profile properties must reach forked JUnit processes without extra CLI flags.
- Existing files and partially generated output must fail completeness validation.
- Language changes must preserve the real application context.
- Node/browser prerequisites must be provisioned by the Maven lifecycle.
- Publication must retain provenance and reject changed source revisions.

### Task 1: Complete capture ownership

- [x] Fix general-generator navigation/dropdown/legend regressions.
- [ ] Extend existing owners for bilingual database, graph, diagram, workbench/editor and root-relevance captures.
- [x] Remove 23 explicitly retired inline image names from active guides.
- [ ] Update existing central reference and captions to actual new scenes.

### Task 2: One Maven/JUnit entry and strict output

- [ ] Add focused regression tests for missing, stale, invalid and complete PNG inventories.
- [ ] Forward `generateScreenshots`, output directory and run timestamp from `screenshots`.
- [ ] Select report and component capture ITs, provision their existing browser runtime in Maven.
- [ ] Generate a commit-bound manifest only after all documented images pass validation.
- [ ] Simplify the workflow to the same Maven call; retain trusted publication restrictions.

### Task 3: Generate and review

- [ ] Compile and run focused JUnit regression checks.
- [ ] Execute the full screenshot profile in a Docker-capable environment.
- [ ] Inspect refreshed images and fix genuine capture failures.
- [ ] Publish current screenshots/provenance and verify the PR before integration.
