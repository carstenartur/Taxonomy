# Product Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete the remaining retained architecture decision workflow and deliver a verified integrated version.

**Architecture:** Extend existing portfolio solutions and immutable inventory evidence; preserve existing module, history and authorization boundaries. Recovery and exchange keep explicit compatibility profiles.

**Tech Stack:** Java 25, Maven Wrapper, Spring Boot, JPA/HSQL/PostgreSQL, Thymeleaf and modular JavaScript, existing native browser/test owners.

**Spec:** [Product completion design](../specs/2026-10-29-product-completion-design.md)

## Global constraints

- One application; use existing feature ownership and persisted identities.
- No invented catalogue identities, silent evidence inference, lowered test thresholds, removed assertions or extended timeouts to obtain green checks.
- Use the checked-in Maven Wrapper; canonical CI is `./mvnw -B verify -Pci -DrunOnnxTests=true` plus existing applicable supplemental owners.
- No fabricated live-provider, vendor, licensed-consumer or human acceptance. Their documented access and review prerequisites remain explicit.
- Publish isolated main-based changes; revalidate exact heads before updates and merges.
- Preserve actual XML bytes, including the returned Archi file's line endings.

## Review focus

- Old retained JSON must remain readable with its recorded fingerprint and evaluator version.
- Reversed/unmapped relation endpoints and incomplete time/context evidence must never authorize a positive finding.
- Concurrent solution/coverage/link changes must share review locks, including Git materialization.
- Hard exclusions must persist and remain effective after decision edit, replay and Git import, with their matching comparison provenance.
- Restored identities, changed export types and declared data loss must never silently acquire authority.

## Task 1: Repair integrated browser CI

- [x] Diagnose cross-class static Git bootstrap state and apply the existing Surefire process-isolation policy to the Failsafe browser owner, preserving the intentional in-test restart and all assertions.
- [x] Independently review and integrate [PR #1344](https://github.com/carstenartur/Taxonomy/pull/1344) after canonical run [40912055998](https://github.com/carstenartur/Taxonomy/actions/runs/40912055998) passed. The browser shard executed 104 tests without failures, errors or skips.
- [ ] Finish verification of the final integrated source before release.

## Task 2: Finish existing dependency changes

- [x] Independently review the Boot standalone verification-POM alignment and CodeQL quality-metadata pin alignment.
- [x] Integrate [POI PR #1327](https://github.com/carstenartur/Taxonomy/pull/1327), merged by existing automation as `ef0484d64087d4decaee1fa609aaa2626d0dc11d` after its cancelled supplemental job passed on retry.
- [x] Integrate [Boot PR #1330](https://github.com/carstenartur/Taxonomy/pull/1330), merged by existing automation as `ab826ed43c6da80dd8291636be6ce8f5e307d55c` after current-head checks passed.
- [ ] Integrate reviewed [CodeQL PR #1332](https://github.com/carstenartur/Taxonomy/pull/1332); current-head canonical run [40923735665](https://github.com/carstenartur/Taxonomy/actions/runs/40923735665) and applicable checks have passed.

The first Boot-integrated main performance job failed before any measurement because Maven could not resolve a core-extension dependency descriptor. A parallel clean job on the same SHA resolved that dependency successfully. One targeted infrastructure retry was initiated; its outcome and the final main gate still require verification. No performance budget or test was weakened.

## Task 3: Complete relationship and domain-variant evaluation

**Owners:** Existing inventory contracts, source projections, host/journal, portfolio evaluators/service/controller, solution writers and Git contributor, existing inventory UI and localization.

- [ ] Freeze additive bounded DTOs for relationship requirements/evidence/findings, variant inputs/assessments and explicit decisions; preserve old constructors, canonical fingerprints and retained JSON.
- [ ] Implement exact directed endpoint/kind/qualifier/context/time evaluation and explicit configured source projections with provenance.
- [ ] Freeze actual project-solution state, prerequisites, risks, cited costs, links and membership. Additional candidates add evidence and never remove existing solution coverage or exclusions. Cost amounts stay validated decimal strings.
- [ ] Implement deliberate review through existing portfolio operations and exact input/mapping fingerprints. Scope anchors precede comparison/project/sorted-solution/sorted-product locks. Mapping/observation writers share the scope fence; Git acquires sorted aggregate fences before outer materialization and does not acquire scope locks.
- [ ] Preserve selection exclusions and their matching provenance through edits, replay, Git and backup. Keep full immutable review/exclusion text with bounded mutable references. Support PostgreSQL V45, HSQL restore V15 and pre-JPA initialization for Flyway-enabled and Flyway-disabled existing profiles.
- [ ] Extend the native project/requirement UI and EN/DE help with relationship inputs, cited assumptions, side-by-side variants and reasoned review/reopen.
- [ ] Run semantic negative and compatibility tests, actual HSQL/JPA concurrency tests, schema startup/restore, Git roundtrips and native browser acceptance. Independently review the final reconstructed commit and require published-head CI before integration.

### Reconstruction checkpoint — 29 October 2026

The first local implementation, `4cd8cd3337ba6982ce7ea7f006414b729239770c`, had independent review and focused local regression evidence. During publication, a hanging GitHub tree-write request was interrupted and the transient workspace was reset before that feature tree reached GitHub. The original commit, source files and local reports are no longer available; summaries are not byte-exact backups or transferable verification evidence.

The same approved scope is being reconstructed on the restored current-main checkout, with small committed checkpoints published early. Task 3 is therefore open again until the reconstructed source is freshly tested and independently reviewed. All five earlier review findings remain required regressions: Flyway-disabled startup, same-target Git provenance, long rationale bounds, outer Git lock ordering and additive-candidate coverage exclusions. This recovery changes neither the requested behavior nor the acceptance gates.

## Task 4: Complete supported Archi return workflow

- [x] Implement and independently review the bounded `taxonomy-archi-5.10-return-v1` return profile, with exact identities/provenance/geometry/view order, explicit representation losses, a review-before-apply catalogue path and canonical writable-context guards. The strict ordinary reader remains unchanged.
- [x] Run installed Archi 5.10 import/export evidence through the existing owner and native UI review/apply/reopen checks. Initial installed-product run [40924217787](https://github.com/carstenartur/Taxonomy/actions/runs/40924217787) is bound to its recorded feature tree.
- [x] Review the two necessary public workspace-service dependency edges; update only their existing baseline count, with all 32 existing architecture checks passing.
- [x] Correct the existing positive endpoint smoke-test prerequisite without weakening the central-read guard. Independent review of local `e673dd4b6d4bfa0a331b9bafcb869523d021f8de` is clear; 29 focused tests passed without errors or skips.
- [ ] Require all current-head checks for [PR #1345](https://github.com/carstenartur/Taxonomy/pull/1345), now at `e538532af0dfc3cad3ccb62b373eea8ebaee76a3`, then integrate and reverify the combined source. Fresh canonical run: [40948068916](https://github.com/carstenartur/Taxonomy/actions/runs/40948068916).

## Task 5: Complete supported restoration path

- [x] Keep the incomplete generic portable registry closed and implement the bounded same-application-version native PostgreSQL 16 custom-dump/stopped-filesystem path.
- [x] Verify source/target physical identity, empty isolated target, in-transaction guards, atomic SQL/receipt handling, large objects, late-error rollback, fresh-target retry, application reopen and undo/redo. Populated-target database replay is rejected; exact filesystem-only resume has its separately documented custody boundary.
- [x] Independently review and fix large-object/collation occupancy and non-root host ownership of client output files. The focused selection passed 38 tests, including three real PostgreSQL rehearsals.
- [x] Confirm the existing Docker PostgreSQL owner executed all three `NativeInstallationPostgresRecoveryIT` cases successfully in [run 40927316484, job 123636173406](https://github.com/carstenartur/Taxonomy/actions/runs/40927316484/job/123636173406).
- [x] Require current-head canonical CI and applicable checks for [PR #1346](https://github.com/carstenartur/Taxonomy/pull/1346): run [40927316571](https://github.com/carstenartur/Taxonomy/actions/runs/40927316571) passed, including reactor coverage and final Maven verification.
- [ ] Integrate the reviewed PR and reverify the combined source. Native recovery preserves stored application identities, grants and pending work; external access must be isolated before startup.

## Task 6: Real task qualification and release

- [ ] Execute the deterministic application journey on the final integrated source and retain source-bound results.
- [ ] Execute configured live provider/vendor/native Visio acceptance when credentials/runtime are available; otherwise preserve exact access prerequisites and unexecuted evidence. Human quality review requires an actual human reviewer.
- [x] Independently review v1.5.3 release notes with immutable documentation links and explicit qualification limits. Their recovered bytes match SHA-256 `70bfbb12e9a9dff57deff2b5c26721c8e431664255e67fcaccb20637c94f24ae` and are saved on the release-preparation branch.
- [ ] Recheck all integration and exact-main gates, then create a separate single-file `.github/release-request.json` PR for 1.5.3 / 1.5.4-SNAPSHOT, with tests enabled, no dry run, revision increased by one and an exact first-parent anchor.
- [ ] Follow the existing release workflow through immutable tag, protected snapshot advancement, exact final gates, security scans, asset/source/digest checks and actual publication/delivery evidence. No ordinary feature merges during that release sequence.

This is a preparation checkpoint, not a published-release claim. Pull requests, workflow results and final release assets provide the authoritative later evidence.
