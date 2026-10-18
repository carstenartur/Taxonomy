# Architecture documentation correction plan

> **For agentic workers:** Use superpowers:executing-plans for coordinated edits and superpowers:dispatching-parallel-agents for independent documentation and screenshot inspection.

**Goal:** Correct obsolete descriptions of Taxonomy's own architecture and verify the illustrations used to explain it.

**Architecture:** Use production POMs, feature ownership, runtime composition and existing screenshot owners as evidence. Link the canonical module inventory and distinguish local execution from optional distributed analysis using the same application image.

**Tech Stack:** Markdown, Mermaid, Java/JUnit, Maven Wrapper, existing browser screenshot infrastructure.

**Spec:** User request of 2026-10-18: correct outdated and false documentation, including affected screenshots. Baseline: `7700192dffd88ebc04b5381fb28b9902cddbd0fb`.

## Global constraints

- Preserve production behavior, test selection, catalogue identity and verification thresholds.
- Correct English and German architecture/developer guides together.
- Retain historical evidence with its actual source; never relabel an old screenshot as a new capture.
- Keep generated logs and temporary rendering output outside tracked documentation.

## Review focus

- Module summaries or ownership contradicting production POMs.
- Confusing one application artifact with one process or general web-cluster safety.
- Treating report models, rendering and template lifecycle as one owner.
- Sending plugin authors to old enum edits or application implementation packages.
- Screenshot captions or instructions describing a different UI or capture source.

### Task 1: Architecture and operating modes

Files: `README.md`, `docs/{en,de}/ARCHITECTURE.md`, `docs/{en,de}/MULTIUSER_ANALYSIS.md`.

- [x] Correct duplicated module summaries and reporting ownership.
- [x] Describe local execution and Artemis coordinator/worker roles with persistence and verification boundaries.
- [x] Link existing plugin lifecycle and cluster operation contracts.

### Task 2: Developer navigation and extensions

Files: `docs/{en,de}/DEVELOPER_GUIDE.md`, `docs/dev/00-start-here.md`, `docs/dev/01-change-map.md`, `.github/copilot-ref-architecture.md`.

- [x] Correct feature owners and source paths; link the canonical inventory.
- [x] Reconcile provider/export instructions and distinguish editor revisions from Git checkpoints.
- [x] Use existing Maven authority and supported profiles for verification commands.

### Task 3: Screenshot evidence

Files: `.github/copilot-ref-screenshots.md` and affected illustration references found during inspection.

- [x] Compare representative images with current templates and capture owners.
- [x] Correct capture instructions and misleading illustration uses; preserve historical source identity.

### Task 4: Verification and delivery

- [x] Check changed Markdown links, explicit source paths and Markdown rendering; preserve the checked module graphs.
- [x] Run existing architecture documentation, naming and Help JUnit checks through Maven; report unavailable prerequisites accurately.
- [x] Review the complete diff and prepare a documentation PR with actual verification results.

## Verification record

- All existing `taxonomy-build` unit classes, selected documentation/Help checks and mandatory module gates: 716 JUnit executions, 706 passed, 10 Helm prerequisite skips, zero failures/errors. Maven `test` through `taxonomy-build -am`, offline with cache reuse disabled, JDK 25 compiling for Java 21.
- Markdown rendering and local file/fragment checks: 28 documents, 779 targets, no errors.
- Corrected import profiles: document import passed 34 selected application tests; ArchiMate import passed 17 selected tests across Knowledge, Portfolio and Application. Each run also passed 5 existing Mermaid checks. The local ArchiMate run used Mockito's startup agent after dynamic attachment failed to locate the namespaced JVM PID. Actual HelpController output: 14 localized documents, 91 absolute repository references, no unserved repository-only relative targets.
- Independent source review: export/load/theme navigation findings corrected against the current template.
- New full-application screenshots were not generated: Docker/browser unavailable. Packaging was additionally blocked by an uncached Maven Antrun plugin in offline mode. Historical image bytes and their earlier manifest were preserved; their mismatch is explicit.
- This is focused documentation verification, not a full `ci`/browser/database acceptance claim.
