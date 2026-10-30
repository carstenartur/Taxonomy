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
- Fresh backend checks passed: realization/trace DSL 7, reconciliation validation 9, native history/transfer/monitor/ancestry 22, workspace reconciliation/idempotency 26, intake candidate/controller/matcher/service 29, lifecycle service 8, and the full twelve-requirement lifecycle integration 1.
- The lifecycle integration verified requirements 1–9, requirement 10 PARTIAL→COMPLETE, requirements 11/12 OPEN, then requirement 9/v2 requiring review while its v1 review remains readable. Source comments and earlier operation/checkpoint receipts are preserved after subsequent edits.
- Native browser round1: session 14 and lifecycle 8 passed. Shared chrome 24 exposed five failures; stand selection 28 exposed five timeouts. Diagnoses included context pins cleared during initial project loading, historical business-key fallback, mobile layout, unknown-response retries and a busy overlay blocking an explicit intake choice. Fixes and a more diagnostic mobile assertion are under round2 verification; these failures are not counted as passes.
- Remaining backend regression round exposed forged current/historical content hashes, moved history HEAD translated as 503, failed architecture creation recovery, and immutable portfolio collisions after Git refs moved. Source/DB preflight,409 translation and exact creation recovery are now implemented; fresh verification is pending. The sync fixture also required unique workspace names between parameter cases.
- Fresh JavaScript contracts: 1,630 passed. The source ownership gate caught the newly added lifecycle test before its owner registration was completed; registration has been added. Full rerun remains required. Independent lifecycle/API replay contracts passed 16 cases after demonstrating unknown-response and later-checkpoint retry failures.
- Boundary review rejected direct portfolio→workspace repository access. A workspace-owned discovery API now supplies immutable authorized source metadata;14 justified existing-direction package-edge counts were reviewed individually. Fresh ratchet verification is pending.
- Canonical Maven verification, broader native acceptance, final review and existing CI checks remain pending.

### Follow-up verification

- Native round2 passed 59 of 64 cases (lifecycle 8, shared chrome 23/24, stand selection 28/32), plus browser-session 14. All previously failing source pins, historical identity and lost-response retry cases passed. Four new stand failures covered incomplete creation recovery and a confirmed pending review after another checkpoint. Their fixes are awaiting native verification.
- The mobile diagnostic showed a missing AI-policy fixture triggering error focus and a 514 px page scroll. The fixture now supplies the real policy/latest-operation reads. Independently, measured 38 px requirement actions were raised to 44 px and added to the existing geometry assertion.
- A fresh 205-test workspace run passed, covering native synchronization, current source authority, merge preflight, provisioning, source discovery and history. The same reactor passed 1,650 JavaScript contracts and both JavaScript ownership checks.
- Creation recovery 14, canonical/portable reconciliation 6 (including forged hashes), lifecycle 8, twelve-requirement integration 1, history HTTP 4 and fresh persistence-context authorization 3 passed in the application regression run.
- Additional real regressions demonstrated five invalid import fields moving Git refs before rejection, loss of unknown managed-source properties, and an old checkpoint receipt falsely reporting publication after supported Undo. Shared input validation, source retention and actual checkpoint reachability fixes are now under verification. Two extra invalid new-identity metadata cases extend the preflight contract.
- Documentation 7 and mandatory module-graph 141, module-extraction 2, plugin-boundary 4 and selector-synchronization 77 checks passed (231 total). CI had caught a second module-shaped documentation table; the explanation now names concrete APIs, preserving the authoritative inventory and its gate.
- The independent review's five concrete findings are tracked through those regressions and the retained `RECONCILIATION_REQUIRED` frontend outcome. Unpublished changes must retain their exact request identity and drafts and offer the existing reconciliation dialog; they are not reported as completed saves.
- Draft [PR #1348](https://github.com/carstenartur/Taxonomy/pull/1348) starts the unchanged GitHub checks. On its first checkpoint, native JGit consumer, production Docker, security, UI contracts and interoperability checks passed. Open CI findings include the documented inventory issue, forced-colors menu contrast, workbench canvas allocation, a scenario driver missing the explicit NEW choice and a PostgreSQL recovery child-process failure whose phase log was absent from the artifact. The existing PostgreSQL job now retains those logs and bounded failure diagnostics; its underlying cause has not yet been established.

## Evidence limits

The full application browser scenario uses native browser forms and the initialized catalogue. Its explicit local mode restarts the Spring application context against the same file-backed HSQLDB/JGit data; this is not described as a separate operating-system process restart. Existing production persistence tests own their container/process coverage.

Configured-provider contracts are validated without paid live-provider calls. Docker, database-container, security, coverage and deployment checks remain owned by the existing repository gates; report their actual results before marking this work complete.
