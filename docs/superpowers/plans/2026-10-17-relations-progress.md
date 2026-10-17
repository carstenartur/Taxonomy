# Supplementary Relations and Progress Implementation Plan

> **For agentic workers:** Use executing-plans for coordination and dispatching-parallel-agents for the independent server, durable telemetry and presentation domains.

**Goal:** Complete relations after a retained node-only analysis and make local/distributed remaining work visible as honest progress bars.
**Architecture:** Extend the existing analysis continuation, frozen operation and telemetry paths; consume the existing progress DTOs in the existing UI panel. Keep each domain in an isolated branch and integrate through focused commits.
**Tech Stack:** Java 21, Spring, Hibernate/JGit, Artemis, vanilla JavaScript, Maven/JUnit/browser drivers.
**Spec:** docs/superpowers/specs/2026-10-17-relations-progress-design.md

## Global Constraints
- Existing Maven/JUnit and required CI; no new workflows or lowered checks.
- Do not rerun node scoring or trust client-supplied evidence for relations extensions.
- Preserve exact requirement/catalogue/provider/owner/workspace and cancellation/retry authority.
- DE/EN, keyboard, mobile, forced colors and no live paid LLM calls.

## Review Focus
- A stale or foreign source must not authorize new provider calls.
- Retries/reload/duplicate worker delivery must not lose evidence or inflate progress.
- Partial/cancelled work must not look like a successful 100 percent.
- Unknown/expanding totals and explicit zero work must be distinguishable.
- Changing preferences must not silently change the saved operation/provider decision.

### Task 1: Stabilize explanation-length PR
- [x] Reproduce migration/restore failures from current-head CI; preserve existing concurrent changes.
- [x] Fix missing version/schema contracts and verify focused Maven/JUnit plus relevant CI.

### Task 2: Relations-only extension (server and explicit UI action)
- [x] Follow the persisted continuation and exact-authority paths; finalize the smallest compatible API.
- [x] Write and run failing JUnit tests for node-only extension, no scoring calls, owner/version/source rejection, idempotency and partial cancellation.
- [x] Implement through the existing local/distributed execution path, retaining accepted scores and provider policy.
- [x] Add a context-aware DE/EN action and explain stale/unsupported legacy evidence.
- [x] Run targeted Maven/JUnit tests and commit.

### Task 3: Durable detailed Artemis progress
- [x] Reproduce absent node/relation DTOs in the observation snapshot.
- [x] Add JUnit regressions for live persisted work, reload, duplicate/stale claims and terminal partial state.
- [x] Record and aggregate bounded authorized telemetry using existing frozen task authority; retain wait reasons.
- [x] Run affected Maven/JUnit tests and commit.

### Task 4: Bars per taxonomy and relation target
- [x] Add failing browser/DOM assertions via the existing JUnit owner.
- [x] Extend taxonomy-analysis-progress.js and focused CSS; use existing DTOs for local and distributed runs.
- [x] Label all bars with units/counts, keep unknown totals honest and preserve focus/disclosure state.
- [x] Run Maven/JUnit UI contracts and a real browser scenario, then commit.

### Task 5: Integrate and finish
- [x] Review/cherry-pick independent commits, resolve interface differences and document usage.
- [x] Run affected complete reactor verification, native browser checks and branch review.
- [ ] Push a focused PR; handle current-head reviews/CI and merge only with authorization and successful required checks.


## Verified implementation checkpoint

- Explanation-length prerequisite #1249 merged after all 38 current-head checks and its relevant workflows completed successfully. Its squash tree exactly matches the prerequisite already integrated here.
- Full affected clean analysis reactor: 4,331 tests passed, including all 1,324 analysis tests. Full tooling: 1,626 passed, including 1,240 JavaScript callbacks.
- Final clean application/reactor/native-browser verification: 215 passed, including all 41 archive cases, retained-source HTTP dispatch, reason-policy preservation, strict architecture boundaries, 14 browser-runtime cases and 15 native UI cases.
- Independent review is clear after reproducing and fixing accepted relation loss on retry cancellation, retained node diagnostic loss on interrupted restore, and stale nested architecture report/status. The new authority, retention and nested-view regressions failed before each corresponding fix.
- PostgreSQL V40 and separately versioned HSQLDB restore V10 retain the exact migration chains. Local tests use the existing test-local profile; the required current-head CI still owns real database, container and full aggregate verification before merge. Publication/merge status is tracked in the pull request rather than asserted before those checks finish.
