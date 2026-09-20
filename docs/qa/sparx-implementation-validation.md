# Sparx XMI implementation validation

Date: 2026-09-19. Baseline: `0bd708eb965e630fa60218df0fce136958dea688`.
Implementation commit: `d083e8f0734557f6b018520c4298fa121a0badde`.
The implementation is an experimental delivery slice of #1075, based on the
synthetic fixture described in `taxonomy-export/src/test/resources/interoperability/sparx/ORIGIN.md`.

## Focused regression evidence

All **56 selected tests passed** with Maven 3.9.16 and Java 25.0.2, compiling for
Java 21. These comprise:

| Suite | Tests |
|---|---:|
| Sparx XMI codec | 11 |
| Sparx native projection and export binding | 6 |
| Sparx identity conflicts | 2 |
| Sparx persisted application workflow | 5 |
| Existing exchange standards, three-way diff, application, restart and architecture projection | 32 |

The independent review findings were reproduced and corrected: reverse direction,
historical UUID ownership, native export binding in the presence of loss reports,
canonical type-tag updates, and explicit connector-end loss reporting. The persisted
deletion regression verifies that tombstones retain identity ownership and that
`TAKE_EXTERNAL` cannot override another GUID's reserved UUID.

JavaScript syntax, compatibility JSON parsing and Git whitespace checks passed.
The focused run selects these nine suites; it does not replace the complete
repository gate.

## Historical complete repository gate (2026-09-19)

Required command: `./mvnw verify -DexcludedGroups="real-llm"`.
An initial run could not initialize Mockito's inline mock maker in the container
because external JVM self-attachment failed. Starting the installed Mockito 5.23.0
premain agent explicitly through `JAVA_TOOL_OPTIONS` resolved that environment
failure. No test selectors, exclusions or repository gates were weakened.

Final outcome: **BUILD FAILURE** after 13 minutes 48 seconds. Across the completed
unit-test phases, 4,778 tests ran: 4,769 passed, with 5 failures and 4 errors in
the two existing ONNX suites. All 24 new Sparx tests passed in this full run too.

`OnnxEmbeddingServiceTest` (4 errors) and `OnnxRestEndpointTest` (5 failures)
require the pinned local embedding model. It is absent in this environment, and
these tests explicitly disable downloading it (`embedding.allow-download=false`).
No `.onnx` model or tokenizer was found in the available scratch workspaces.
The later Failsafe/container and aggregate-quality phases were not reached.
The complete gate must run in CI with its pinned model preparation before merge;
the selected regression run is not a substitute for that gate.

## Product acceptance

No actual EA or PCS execution was available. Neither the passing fixture tests nor
the application tests establish real-product compatibility. The authoritative
unexecuted product matrix and remaining OSLC acceptance items are recorded in
[sparx-compatibility.json](sparx-compatibility.json).

## Historical semantic version 2 — Task 1 (2026-09-20)

Review base: `3ca66eed8cbc9a85b7151b4361b38f95bc6afc90`. This slice adds
shared AM/XMI feature semantics, frozen profile-version dispatch and bounded,
complete AM collection traversal. At that Task 1 checkpoint, native package editing
and requirement projection were subsequent work; the native entry below supersedes
that scope statement. The v2 fixtures are handwritten contracts;
real EA/PCS compatibility remains `NOT_EXECUTED`.

Final focused verification compiles for Java 21 on the local JDK 25 runtime:

```sh
TAXONOMY_EMBEDDING_MODEL_DIR=/workspace/scratch/dae1667028d4/civilian-models/bge-small-en-v1.5 TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false python /workspace/scratch/dae1667028d4/verify-repo.py -pl taxonomy-app -am test -Dtest=SparxXmiCodecTest,SparxOslcAmCodecTest,SparxOslcAmReaderTest,ExchangeConnectorRegistryTest,IntegrationRemoteRetryTest,SparxIntegrationFlowTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=real-llm
```

**BUILD SUCCESS: 72 selected tests passed** (19 XMI codec, 14 AM codec,
24 AM HTTP reader, 2 registry, 5 durable retry and 8 app flow cases).
The selection covers shared feature/connector identities, deterministic XMI
roundtrips, duplicate tags, owner/position/prefix validation, complete collection
sets, every collection's HTTP pagination and later-page failure, aggregate bounds,
credential/URI guards, exact profile versions and feature-only stale apply.

A clean owning-reactor attempt (`-pl taxonomy-app -am clean test`, no test selector)
passed all completed upstream modules, including 78 interop tests, then failed in
portfolio test discovery with
`Unable to create test class 'com.taxonomy.portfolio.service..rsync-tmp.SolutionCatalogPolicyContractTest'`.
Portfolio ran no tests and app was not reached. The earlier clean attempt exposed
old one-argument registry mocks in the retry suite; those Task 1 failures were
corrected and covered in the final focused selection. Both failed logs are retained
in the task workspace. The controller stopped further full-reactor retry loops
for the diagnosed transient discovery condition; the broader gate remains
incomplete for that attempt. The separately confirmed Word-foundation
dependency-ratchet failure was also historical; the later accepted Word base is
`085f620a5141de8fc26140137010b59414ddead1`. No baseline was changed in Task 1.

Detailed RED/GREEN logs, API changes and review notes are recorded in
`.superpowers/sdd/2026-09-20-sparx-semantic-completion/task-1-report.md`.


## Native version 2 and final corrective evidence (2026-09-20)

Native packages are now editable through the transport-neutral editor. V2 imports
project packages and explicitly reviewed requirement endpoints through the real
portfolio contribution and typed mapping commands. V1 connections keep their
frozen exchange behavior. The [English](../features/sparx-integration.md#native-version-2-review)
and [German](../features/sparx-integration-de.md#native-prüfung-mit-version-2) guides
describe the current review boundary.

Retained local evidence compiles for Java 21 using the verified JDK 25 wrapper:

| Accepted selection | Result | Retained receipt |
|---|---|---|
| Task 1 loss corrections: XMI, AM codec, HTTP reader, app flow | 20 + 16 + 24 + 8 passed | `logs/task-1-fix1-green.log` |
| Native authority and round trip: projection, actual app, dependency ratchet | 12 + 1 + 22 passed | `evidence/task-2/final-authority-green.log` |
| Native endpoint-selection/type corrections: projection, app flow, ratchet | 13 + 15 + 22 passed | `evidence/task-2/fix1-final-green.log` |
| Native API/browser-context fixture correction | 1 actual civilian HTTP test passed; browser fixtures compiled | `evidence/task-2/fix2-civilian-green.log` |
| Terminal Copilot result consistency and unchanged civilian API workflow | 26 selected tests passed | `evidence/task-2/fix3-green.log` |

The original native six-process `IntegrationRestartTest` proved package and element
membership recovery across fetch, preview, apply, Git, acknowledgement and delivery.
It did not prove a newly created typed requirement mapping across process restart.
The final corrective run adds that narrower requirement-specific case: **43 selected
tests passed** (26 XMI codec, 16 AM codec, 1 actual two-process application restart),
with no failures, errors or skips. Both processes retained one typed mapping, one
journal operation, eight integration identities, the same real requirement/version,
and the same two-commit Git head after retry and apply replay. A final codec rerun
after readability cleanup passed all 42 codec cases. Actual commands and child-process
receipts are recorded in `final-fix-report.md` and `evidence/final-fix/`.

Real civilian CI run **35511206032**, job **106079315207**, passed EN/DE native
package edit/reload and report QA on published source
`170f0aa71e46621040c2b6c4c8211dd0fab0d745`, tree
`070b8ffd3c3f7fe6fbc4579dbec5b5e9336e0392` (also the local corrective base
`52d2124cb0a8f6d4201d6aa115e7ddea2aca12f5`). Artifact **10606116288** was
hash-verified: SHA-256
`cc058af6b40619cde0394712740a8d405f3152ae6b4cc73d6ee9abdd60954b5d`.
`native-browser.json` records `requestInterception=false`. This is evidence for
that tree, before the final XMI corrections, and does not approve later source.
The artifact has the five general civilian screenshots; dedicated native package
review screenshots remain a separate controller acceptance item.

The final correction addresses v2 connector-detail/owned-end loss reporting and
feature-detail property preservation. Its regression RED/GREEN receipts and the
full-application requirement-mapping restart result are in `final-fix-report.md`
and `evidence/final-fix/`. All task-relative receipt paths in this entry are under
`.superpowers/sdd/2026-09-20-sparx-semantic-completion/`; prior implementation and
fix details are in `task-1-report.md`, `task-2-report.md`, and
`controller-ci-170f0aa.md` there.

Exact final-source Java 21 whole-root CI, supported-database lanes, browser/profile
shards, accessibility/reflow and visual acceptance remain controller-owned gates;
focused HSQL/application results do not establish those outcomes. Earlier missing
model, transient test discovery, Word ratchet and pre-fix pointer/browser failures
remain historical failed attempts, not current passes. Existing schema, deprecated
API, HSQL/Hibernate, Lucene/JDK and font warnings remain visible in retained logs.

Actual EA/PCS/SBPI acceptance remains **NOT_EXECUTED** in
[sparx-compatibility.json](sparx-compatibility.json). These contract fixtures and
native tests do not prove preservation by a proprietary tool. This entry makes no
publication or VSDX completion claim.

## Accepted foundations and conditional publication — 2026-09-20

The historical pending native/Word gate statements above are superseded by the
merged [Word PR 1088](https://github.com/carstenartur/Taxonomy/pull/1088) and
[native PR 1089](https://github.com/carstenartur/Taxonomy/pull/1089). Their exact
trees, actual final CI, complete Word visual acceptance and current accepted Visio
source are indexed in [completion evidence](1075-completion-evidence.md).

The test-only publication contract is `taxonomy-publication-contract-v1`, with
real HTTP CAS and atomic durable resources/idempotency receipts. It is not PCS.
Production Sparx writes still return `PUBLICATION_GUARANTEES_UNVERIFIED`.
The bounded schema-1 API exposes saved directed review/scope/revision/fingerprint
without exposing raw requests, credentials or leases. Missing exact state returns
428, explicit mismatched branch 409, and the aggregate evidence preflight rejects
oversized combinations with 422 before local/remote effects.

| Focused Task 3 evidence | Result |
|---|---|
| API routes/reload/retry, exact-state reconciliation and non-file delivery | 3 passed |
| Aggregate output budget, independent over-limit projection and zero effects | 1 passed |
| Actual HTTP status/redirect/oversize/foreign/reflection/URI and authorization guards | 3 passed |
| Existing actual native publication flow and partial-state regressions | 9 + 4 passed |
| Real authenticated civilian fixture: partial/unknown, retry, explicit SKIP/PARTIAL and linked reconciliation | 1 passed |
| Existing file delivery and checkpoint conflict | 1 + 1 passed |
| Exact reviewed controller→workspace dependency count 5→6 | 22 ratchet tests passed after the sole baseline correction |
| DOM review, default denial, failed direction change, predecessor link and connection reset | 7 passed |
| Separate client/provider process-boundary matrix | 2 tests passed, 12 steps; 12 provider kills, 10 client kills, 2 normal verified client replays |

The matrix uses actual independent application/provider JVMs with persistent HSQL,
real native editor/Git, real HTTP and persisted provider keys. It stops only after
observable real transactions or effects. Plan acceptance and local application
share one transaction; the Push-plan and Sync-local scenarios do not invent a
separate committed-plan/pre-local window. Receipt lookup after response loss,
partial acknowledgement, Git-before-journal, finalization and completed replay
retain identities and prevent duplicates/premature COMMON. See the
[versioned machine-readable evidence](conditional-publication-contract-v1.json).

Meaningful failures preceded the missing API/UI implementation, additive projection
budget guard and final exact-state/default-denial/reload corrections. A discarded
900-command setup and early matrix constructor/type/metadata-count mistakes were
fixture failures, not product RED. The first branch assertion exposed an ignored
explicit query parameter, not an unauthorized write. The initial final-covering
run had all 23 functional tests pass but one dependency ratchet failure; the sole
reviewed 5→6 update then passed all 22 ratchet tests. These outcomes are retained
separately rather than relabeled as a wholly green earlier run.

Actual civilian CI `35524367930` passed and produced ten new EN/DE images; nine were
visually accepted. Corrected CI `35525893725` then passed and all fourteen images
were visually accepted, including complete package controls and enabled actual
reconciliation. [Exact image bytes and provenance](conditional-publication-browser.md)
are committed. Document CI `35525893726` also passed with no CSS/layout fix and no
overflow diagnostics; the earlier EN mobile failure's cause remains unproved.
The final diagnostic guard asserts its frozen first measurement, preserving the
original failure rather than allowing diagnostic time to change it. The URI-search
review correction passed 35 policy/planner/receipt tests; the old 2,048-character
bound remains, and no actual performance failure was reproduced. Final
database/full-source CI and review remain mandatory. Actual EA/PCS/SBPI/MS Word/
MS Visio remain `NOT_EXECUTED`.


The accepted Task 2 correction's permanent [decision history](1075-completion-decisions.md#d44a--accepted-task-2-lookup-only-recovery-correction)
records lookup-only recovery after movement, non-spinning NOT_FOUND, the shared
wall-clock/monotonic budget, deterministic bounded Git with durable late completion
and phase-aware stale callbacks. Independent review accepted 34 final tests at
`55e21da4b2bf2021a2d87e43d6da0597c922ac73`; the first corrected R1 failure remains
unexplained despite stronger controlled-clock/persisted-state evidence. No
historical cause is inferred from a later pass. Task 3 fix review also preserves
inherited HSQL/Flyway/dialect/Lucene/JDK warnings as nonblocking, not absent.

The subsequent [C1/C2 service correction](1075-completion-decisions.md#d80--late-receipt-authority-and-joined-exact-checkpoint-proof)
passed seven focused and 41 final covering tests after four actual behavioral REDs
(the separate initial CLOB fixture error is not behavioral evidence). Real HTTP
late no-effect/terminal receipts and actual Git-write/ORM-rollback exercise the
existing journal and checkpoint guards; valid late terminal adoption remains
supported. No schema, public API, module edge or lock-order change was required.
Scoped correction review and final full-source CI, including PostgreSQL, Oracle
and SQL Server, remain required; earlier candidate CI is not evidence for these
new bytes. Actual EA/PCS/SBPI/MS Word/MS Visio remain `NOT_EXECUTED`.


The final [I1–I3 correction](1075-completion-decisions.md#d81--final-i1i3-preservation-and-recovery-correction)
preserves actual legacy-v1 nested occurrence export without granting v1 native
package editing; records full validated 64/65/100-character receipt codes through
real SEND and LOOKUP while bounding scalar diagnostics; and separates completed
no-effect SEND proof from an older timed-out SEND that may still commit. After
movement, the former permits an explicit successor and the latter retains its
reservation and read-only recovery. Repeated nonterminal lookup is bounded; later
terminal evidence yields one remote mutation without a stale-plan SEND or COMMON.

Final covering validation passed 60 tests (14 interop, 46 app), including v2 and
registered neutral package paths, with zero failures, errors or skips. Meaningful
REDs and the exact restored production hashes are retained in the correction
report. One intermediate strengthened run used stale test bytecode; it is excluded
from that claim, its cause remains unknown, and affected generated outputs were
cleared before confirmed RED and final GREEN. Existing warning debt remains.
No new browser/Office/proprietary-host execution is claimed. The controller still
owns fresh exact-source clean-root, required CI and the single scoped rereview.
