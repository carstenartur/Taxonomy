# Coherent architecture lifecycle verification

Status: reconstruction is under fresh verification. This report intentionally does not reuse successful results from the lost checkout.

## Acceptance obligations

| Scenario | Required observation | Owner |
| --- | --- | --- |
| Requirement 9 changes after 10 completes | Current v2, reviewed v1, REVIEW_REQUIRED; 10 remains COMPLETE | Lifecycle service and real browser acceptance |
| Requirements 11 and 12 | Visible as OPEN without invented realization evidence | Lifecycle integration and browser |
| Historical commit and semantic revision | Original source and versions, no current database overlay, read-only actions | Service and stand-selection browser contracts |
| Retry older accepted intent | Same immutable result and original checkpoint after a later change, no duplicate writes | Lifecycle service and editor idempotency |
| Pending checkpoint | Retained intent, exact command retry, no false success | Editor and browser dialog contracts |
| Incoming portfolio reconciliation | Canonical/portable consistency and transaction rollback on immutable collisions | Workspace and application reconciliation tests |
| Native history | Captured HEAD, actual ancestry/parents/signatures, bounded truthful coverage | JGit storage, history and sync tests |
| Intake outage or stale suggestion | Manual choice stays available; scope/input/version changes invalidate advice | Intake service and browser contracts |
| Shared presentation | Same entity/status vocabulary, readable business context, technical disclosure, contextual help | Six-view browser checks |
| Layout | Desktop first requirement remains reachable, mobile overflow controlled, 44px controls | Shared chrome and portfolio context browser checks |

## Fresh execution log

- First reconstructed backend run: DSL realization/trace 7 tests passed. Compilation then caught a missing exhaustive fingerprint arm for the newly reconstructed semantic review command. The arm was restored before repeating the run.
- Fresh backend checks passed: realization/trace DSL7, reconciliation validation9, native history/transfer/monitor/ancestry22, workspace reconciliation/idempotency26, intake candidate/controller/matcher/service29, lifecycle service8, and the full twelve-requirement lifecycle integration1.
- The lifecycle integration verified requirements1–9, requirement10 PARTIAL→COMPLETE, requirements11/12 OPEN, then requirement9/v2 requiring review while its v1 review remains readable. Source comments and earlier operation/checkpoint receipts are preserved after subsequent edits.
- Native browser round1: session14 and lifecycle8 passed. Shared chrome24 exposed five failures; stand selection28 exposed five timeouts. Diagnoses included context pins cleared during initial project loading, historical business-key fallback, mobile layout, unknown-response retries and a busy overlay blocking an explicit intake choice. Fixes and a more diagnostic mobile assertion are under round2 verification; these failures are not counted as passes.
- Remaining backend regression round exposed forged current/historical content hashes, moved history HEAD translated as503, failed architecture creation recovery, and immutable portfolio collisions after Git refs moved. Source/DB preflight,409 translation and exact creation recovery are now implemented; fresh verification is pending. The sync fixture also required unique workspace names between parameter cases.
- Fresh JavaScript contracts:1630 passed. The source ownership gate caught the newly added lifecycle test before its owner registration was completed; registration has been added. Full rerun remains required. Independent lifecycle/API replay contracts passed16 cases after demonstrating unknown-response and later-checkpoint retry failures.
- Boundary review rejected direct portfolio→workspace repository access. A workspace-owned discovery API now supplies immutable authorized source metadata;14 justified existing-direction package-edge counts were reviewed individually. Fresh ratchet verification is pending.
- Canonical Maven verification, broader native acceptance, final review and existing CI checks remain pending.

## Evidence limits

The full application browser scenario uses native browser forms and the initialized catalogue. Its explicit local mode restarts the Spring application context against the same file-backed HSQLDB/JGit data; this is not described as a separate operating-system process restart. Existing production persistence tests own their container/process coverage.

Configured-provider contracts are validated without paid live-provider calls. Docker, database-container, security, coverage and deployment checks remain owned by the existing repository gates; report their actual results before marking this work complete.
