# Guided Database Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make database setup understandable and optionally create the PostgreSQL database and restricted application login needed by a fresh installation.

**Architecture:** Retain the existing administrator dialog, shared API client and deployment exporter. Add a separate PostgreSQL provisioning service and two explicit endpoints, reuse safe connection settings/diagnostics, and make the UI distinguish settings, checks, creation and deployment.

**Tech Stack:** Java 25, Spring Boot, pgJDBC, Thymeleaf, plain JavaScript, JUnit and the existing browser test harness.

**Spec:** `docs/superpowers/specs/2026-11-04-database-wizard-guided-setup-design.md`

## Global Constraints

- Existing routes remain ADMIN-only and session POSTs require CSRF; strict bounded JSON and no-store remain in force.
- No credentials or raw driver/SQL errors in responses, logs, toString, preview or downloads; passwords are cleared from the dialog on close.
- No automatic TLS downgrade, destructive cleanup, password rotation or owner changes.
- Create PostgreSQL resources only after explicit user confirmation; inspection and connection tests do not write.
- Preserve the deployment-owned datasource, profile selection, schema override checks and existing migration path.
- Complete English/German translations and keyboard/mobile usability.

## Review Focus

- TLS checkbox changes and second manual checks must not retain or display stale results.
- An interrupted create request may have changed PostgreSQL; the UI must offer inspection, not claim rollback.
- A conflicting role or database must stop all writes, including when only the other resource is absent.
- Reused roles must not silently acquire permissions/password changes; wrong passwords and privileged memberships block reuse.
- Admin credentials must never replace the application credentials in generated configuration.

### Task 1: Safe, actionable connection diagnosis

**Files:** `SetupProbe.java`, `DatabaseSetupProbeTest.java` in `taxonomy-app/src/{main,test}/java/com/taxonomy/setup/`.

**Interfaces:** Package-visible `SetupProbe.databaseFailure(Throwable)` returns a `SetupChecks.Finding`. Success and generic failure retain key `database.connection`; classified failures use `database.connection.dns`, `.refused`, `.timeout`, `.tls`, `.authentication`, `.missing-database`, `.permission`, `.interrupted`. All error messages are fixed. Preserve existing concurrency and driver timeout behavior; non-database probes remain unchanged.

- [x] Add tests using real Java exception types/SQLStates and a JDBC driver boundary for classified failures and failure-then-success; every secret sentinel must be absent from findings.
- [x] Run focused probe tests and observe missing behavior.
- [x] Implement bounded/cycle-safe cause traversal, semantic SQLState classification and distinct timeout/interruption results without exposing raw messages. TLS unsupported and trust failures should be actionable where provable.
- [x] Re-run probe tests including concurrency and connection-close tests.

### Task 2: Explicit PostgreSQL preparation

**Files:** Create `PostgresProvisioningService.java`, `PostgresProvisioningServiceTest.java`, and PostgreSQL integration coverage; modify only the PostgreSQL dependency scope in `taxonomy-app/pom.xml` if needed.

**Interfaces:** As specified in design: records/enums nested in `PostgresProvisioningService`; `inspect(ProvisionSettings)` and `provision(ProvisionSettings)` return `ProvisionResult(outcome, role, database, finding)`. Consumes package-visible `DatabaseSetupService.validate(ConnectionSettings)`, `jdbcUrl(ConnectionSettings,boolean)`, and safe `SetupProbe.databaseFailure(Throwable)`. Root integrates controllers and UI separately.

- [x] Add failing tests for no-write inspection, explicit confirmation, restricted role/database owner, SCRAM without plaintext SQL, existing-resource reuse/conflict, missing permissions, partial failure and uncertain timeout.
- [x] Implement bounded finite operations, catalog preflight, safe names and secret handling, session lock, explicit create/reuse and post-create app verification.
- [x] Run focused service tests, plus a real PostgreSQL integration test for generated SQL/password and role privileges when the runtime is available; record exact blockers otherwise.

### Task 3: Endpoint contracts and clear result state

**Files:** `DatabaseSetupController.java`, `DatabaseSetupService.java`, their existing tests, OpenAPI contract cases.

**Interfaces:** Add `/postgres/inspect` and `/postgres/provision`; parse the provisioning record with the same strict reader bounds. Expose shared validation/URL methods only within package. Place actual connection finding first in check results; TLS selection becomes NOT_CHECKED in previews, never a false successful handshake.

- [x] Add tests for the two routes' authorization, CSRF, strict/malformed/oversized input, redaction and no-store; verify secret-bearing input cannot leak.
- [x] Add service test for primary connection result order and TLS-selection status.
- [x] Implement constructor injection, typed strict reader and endpoint annotations; retain schema conflict behavior.
- [x] Run controller/service/OpenAPI focused tests.

### Task 4: Guided, accessible dialog

**Files:** `taxonomy-database-setup.js`, `taxonomy-database-setup.css`, `fragments/database-setup.html`, both `messages_database_setup*.properties`, `DatabaseSetupBrowserTest.java`; add a focused JavaScript browser test registered in the existing JUnit source manifest if useful.

**Interfaces:** Existing check/preview API plus Task 2/3 provisioning contracts. Primary result container must expose success/error separately from expandable scope details. PostgreSQL create mode uses separate admin fields and a review-confirm step; its result can advance to existing export with application credentials only.

- [x] Add failing behavior tests for successful plaintext check, failed TLS check, retry after failure, edits invalidating success, non-secret form recovery, explicit inspect/review/create order, and lost create-response recovery.
- [x] Implement two setup paths, compact primary outcome, actionable diagnosis, a single TLS warning, optional technical details and a clear deployment handoff. Disable controls/navigation throughout mutation; never automatically retry writes.
- [x] Preserve focus trapping, live announcements, password clearing, shared transport, and manual export without requiring a test for existing databases.
- [ ] Verify keyboard/mobile behavior and screenshots in the real browser harness; run the targeted tests and localization checks.

### Task 5: Documentation, integration review and delivery

**Files:** `docs/en/DATABASE_SETUP.md`, `docs/de/DATABASE_SETUP.md`, relevant API documentation if needed.

- [x] Explain the two workflows, fresh official PostgreSQL defaults, necessary Service, creation/reuse/partial outcomes and the Secret/restart handoff.
- [x] Run focused integrated tests and existing required gates that the environment supports, recording exact command/results.
- [x] Obtain independent whole-diff review with particular attention to privilege separation, partial side effects and actual UI outcomes; fix material findings with regression tests.
- [x] Create one reviewable feature PR against main, inspect CI, and report actual status and any material limitation. Do not merge or deploy without existing authorization for that operation.

## Verification evidence

The integrated Maven verification passed **64 tests**: 56 database-setup/OpenAPI tests and 8 tooling/localization tests, with no failures, errors or skips. The setup coverage includes 20 provisioning tests for reviewed-action drift, safe partial/unknown outcomes, cancellation and restricted-account preparation. A separate local DOM run passed 11 checks of the wizard flow, including three actual-transport response-body deadline checks. These DOM checks do not establish real-browser keyboard, mobile or screenshot acceptance.

Local native execution was blocked by browser process restrictions, absent Docker and the environment's root-only identity mapping; no alternative identity or sandbox workaround was attempted. CI on commit `3b7111056082e2aebe5e0f7e74afc32a35b528df` passed all seven initial native wizard callbacks and both real PostgreSQL 18 provisioning integration cases. See [native browser evidence](https://github.com/carstenartur/Taxonomy/actions/runs/41874483280/job/126732403311) and [PostgreSQL evidence](https://github.com/carstenartur/Taxonomy/actions/runs/41874483265/job/126731003521).

A subsequent review reproduced a response body that stalls after successful HTTP headers. The wizard now applies a complete-response deadline, does not retry writes and ignores late replies after an uncertain outcome. The focused regression was observed with two failing cases before the fix and all three passing afterward. An eighth native browser callback covers that recovery with the production API client and a stalled Response body; mobile captures now use the existing CI artifact directory. The final follow-up head's CI and retained screenshots are tracked in [PR #1374](https://github.com/carstenartur/Taxonomy/pull/1374).
