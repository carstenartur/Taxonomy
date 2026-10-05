# Artemis cluster execution acceptance

Date: 2026-10-04. This record covers the implementation after merged PR #1169
against baseline `fb80aeff36d3bca22af9f477b566cd85a0210b95`. It distinguishes
executed tests from deployment acceptance that still requires its target environment.

## Executed evidence

| Boundary | Executed evidence |
| --- | --- |
| Durable delivery | `ArtemisAnalysisTransportTest`: persistent TCP broker, competing workers, actual JPA duplicate effects, crash after durable commit before acknowledgement, restart/reconnect, malformed messages, poison isolation, real expiry/DLQ settlement and cross-connection cancellation. |
| Parallel roots | Eight real-broker root workers overlap under deterministic delayed computation. `ArtemisProductionExecutionTest` separately exercises production `LlmService` MOCK scoring and frozen input through the broker, comparing root scores, context, fingerprints, trees and four logical calls with the direct reference. |
| Relation parity | Production relation preparation and target workers use the real relation engine and a fixed generative fixture. Prompt multisets, source assessments, edges and total provider-call count match the direct frozen reference; two relationships are verified. Unit fixtures additionally exercise reversed completion and saturated deterministic budgets. |
| Source isolation | Concurrent CP/IP runs use different repositories and overlapping child identifiers with different names and semantic roles. Frozen catalogue/model keys cover repository, workspace, branch, commit, root, model/config and input text. Missing/foreign shards fail closed. |
| Catalogue consistency | Database concurrency tests cover capture versus official catalogue import, overlay publication and derived metadata updates. All captured roots share one actual retained catalogue generation. |
| ONNX | `FrozenLocalOnnxModelTest` executed successfully using the pinned BAAI model and native ONNX Runtime on CPU. Frozen candidate scores match actual Lucene filtered-KNN scores. Model fingerprint, projection readiness and cache-isolation regressions pass separately. |
| Provider capacity | 100 focused tests on the independently composed provider-permit branch pass, including nine persistent TCP Artemis tests. Eight real HTTP requests through two clients peak at two physical requests with two permits; capacity returns to two. Retry, cancellation, broker-loss and quota-alias cases are included. |
| Observation | Durable snapshot/recent/request/result authorization, monotonic revisions, reconnection, cancelled operations and source changes have controller/store tests. Six real Chrome scenarios cover saved-run recovery, original-input restoration, live and terminal rendering, native EventSource reconnect, stale-scope rejection and retry, issuing 24 GETs and zero POSTs. |
| Final integrated regression | 130 executions pass (29 analysis, 100 application, one required architecture anchor), including all Artemis application suites, the database/schema contracts, 11 configuration-reference checks, four unchanged reformulation browser tests in supported local Chrome mode and the separate-JVM restart checkpoint. |
| Production composition | Spring context tests bind root/relation execution, finalization and observation in `all`, `coordinator` and `worker` roles. Local mode retains its existing path. Broker unavailability does not block context startup. |
| Restore | Actual store cancellation → portable export → restore → durable observation passes, including nullable historical completion time. Interrupted restored work uses canonical `STOPPED`; it never republishes dispatch or provider permits. |
| Metrics | Runtime instrumentation tests cover queue/execution timing, concurrency, duplicate replay counters, dispatch, permits and first cooperative cancellation observation with bounded labels. |
| Architecture | The unchanged root `architecture-tests` profile passes across all 16 modules: 513 executions, including the required repeated guards, with zero failures/errors/skips. The independent P05 and P08 branches also pass their respective architecture gates. |
| Deployment configuration | Helm rendering/lint, 118 security shell cases, broker XML schema validation and deployment/architecture documentation contracts pass. The final Kubernetes fixture gate adds 225 successful executions: 12 Helm/smoke contracts and 213 required architecture guards. Actual render-only and fail-fast script paths are exercised. These are configuration tests, not a live CNI/HA acceptance run. |

The browser fixture serves the unchanged production API, progress, workspace
routing and scoring scripts. HTTP/SSE responses and session state are fixtures;
it does not substitute for Spring authorization or a browser connected to a real
remote worker. See [metrics and browser reproduction](analysis-cluster-metrics.md).

## Runnable Kubernetes acceptance

The existing constrained-smoke workflow now runs an additive
[Artemis acceptance script](../../deploy/helm/taxonomy/artemis-constrained-smoke.sh)
after its unchanged original scenario, using the exact same built application
image. A separate quota-limited namespace contains external TLS Artemis,
PostgreSQL, a coordinator and independently scaled CP/IP workers.

The test admits two roots with workers absent, holds a test-owned operation row
lock, waits for an actual unacknowledged CP delivery, then removes that worker.
It waits for both broker and database disconnection before releasing the lock;
zero pre-release effects and a persisted redelivery count of at least two prove
that the replacement performed the work. Final assertions cover exactly one
durable effect per root, contiguous revisions, independent CP/IP replica counts,
allowed database/broker egress and denied broker-management egress. The script
records actual fixture heap/RSS/configuration, and cannot report success from
render-only, failed preflight or stale evidence. Existing namespaces are refused.

The local live command stops at the missing Docker prerequisite without producing
a success marker. The first [remote Artemis attempt](https://github.com/carstenartur/Taxonomy/actions/runs/37227702067)
started PostgreSQL and the TLS broker, authenticated broker management, and made
the coordinator ready. Both workers repeatedly exited; the 12-minute Helm wait
failed. Its atomic rollback removed the application pods before their logs could
be retained. A separate full-application run against that exact CI artifact
reproduced fatal `HSEARCH800001`: the commit-index startup runner accessed global
Hibernate Search after the worker role disabled it. The corrected lifecycle
passes seven Spring-context regressions and the six complete process measurements
below. The smoke now captures bounded current/previous logs and resources before
cleanup, redacting fixture credentials. Three failing-path regressions pass with
the original timeout and exit status retained.

The [second remote attempt](https://github.com/carstenartur/Taxonomy/actions/runs/37231204928)
on PR head `3c24016` retained those diagnostics. The disabled-search exception was
absent, but two pods failed while reading the same retired JGit reftable during
concurrent default-template bootstrap. After restart, both workers remained
unready: the default lazy application initialization had never started the
Artemis lifecycle beans. The IP worker also published a 73-character empty
default DSL before the coordinator loaded the 2,572-node catalogue. These are
separate startup defects, not evidence that a longer Helm timeout is needed.

The subsequent correction starts both Artemis lifecycle owners eagerly, reads
shared template heads under the storage library's existing database ref lock,
and prevents workers from publishing the global default DSL. Targeted regressions
reproduce the missing subscriptions, deleted reftable and premature empty draft.
The live scenario must pass again on the corrected revision before acceptance.

The [third remote attempt](https://github.com/carstenartur/Taxonomy/actions/runs/37234521014)
on `ff6532c` confirms ready coordinator/CP/IP pods, real permitted database/TLS
broker egress and denied broker-management egress. The coordinator publishes the
complete 1,427,610-character default DSL. Workspace creation and provisioning
succeed, but analysis admission returns HTTP 400: the authorized workspace uses
`main`, while legacy user navigation still names `draft`. POST, streaming, direct
analysis callers and the final source recheck now read the branch from the
immutable authorized workspace context. They still verify the current commit and
reject a changed source. Four red-to-green regressions cover this mismatch and a
concurrent change to another active workspace; all **23** focused tests pass.
Independent review found no important issue.

The [fourth remote attempt](https://github.com/carstenartur/Taxonomy/actions/runs/37236100813)
on `ae2c33b` confirms durable two-root admission and an actual unacknowledged CP
delivery. After force-deleting that worker, the unchanged 120-second joint
broker/database disconnection check fails while the acceptance row lock is held.
The artifact does not retain the final state of each resource, so it cannot
identify which half caused the timeout.

The smoke PostgreSQL instance now sets `client_connection_check_interval=1s`.
[PostgreSQL documents](https://www.postgresql.org/docs/16/runtime-config-connection.html#GUC-CLIENT-CONNECTION-CHECK-INTERVAL)
that its default zero disables dead-client checks during running queries; that
matters when this test deliberately blocks the worker on a row lock. Every loss
poll now samples both resources independently and retains their latest bounded,
allowlisted counters even if the other observation fails. Failed observations
replace previous evidence and cannot satisfy the predicate. Seven new behavioral
cases fail before this correction; all **10** smoke cases and the full Helm
verification pass afterward. Independent review found no important issue.
Production database defaults, the timeout, the held lock, zero pre-release
effects and real redelivery/exactly-one-effect assertions remain unchanged. The
next live run must establish whether this fixture correction resolves the timeout.

The [same-head reformulation run](https://github.com/carstenartur/Taxonomy/actions/runs/37236101044)
also reports exactly one new architecture edge introduced by the worker bootstrap
fix: composition DSL bootstrap now depends on the existing knowledge catalogue
runtime policy. Review confirms the intended composition-to-feature direction;
the baseline records this one class edge without changing any architecture rule
or selector. All **23** dependency-ratchet cases and **213** mandatory module
guards pass in the combined 16-module reactor (236 executions, no failures,
errors or skips, 1 minute 6 seconds).

The [fifth remote run](https://github.com/carstenartur/Taxonomy/actions/runs/37237916621)
on `55a7c55` passes both the original smoke and the complete Artemis scenario.
Its independently verified artifact `11316004841` has SHA-256
`186ccc6588e103215e45849aaa31681c05034fbb8bd68902dfb340d49cd8a26f`.
The tested merge commit `c12ddbda14a9ef7369203226cabd5d2dfa028391` has tree
`7fabb5547cb1078411398d8f9ea43c4e26752465`, exactly equal to the PR head.
About four seconds after force deletion, the retained broker consumers/deliveries
and lost-pod PostgreSQL sessions are zero while the acceptance lock remains held.
Zero effects precede its release. CP completes with **2** delivery attempts and IP
with **1**, each with exactly one durable effect. The CP broker records one added
and one acknowledged message, with none remaining. All **425** durable scores
equal the original response; replacement identities, contiguous events,
independent scaling, quotas and actual allowed/denied egress also pass.

See [capacity and execution instructions](../../deploy/helm/taxonomy/CAPACITY.md).
This scenario does not claim broker HA, external-provider throughput, loss during
a physical provider call or a production memory/capacity baseline.

## Measured memory boundary

The retained nine-process [data/cache experiment](evidence/frozen-embedding-footprint-2026-10-04.json),
refreshed by P05 review commit `31e1a03`, records CP frozen data/cache heap at
**957,168 bytes** and all eight roots at **10,166,168 bytes**. Vector payloads are
**52,224** versus **3,950,592 bytes**.
These are fresh-JVM measurements of the frozen data stage. They exclude Spring,
native model memory, database connections and whole-pod RSS. They must not be
presented as a measured production pod-startup footprint or a capacity promise.

The additional [whole-process experiment](worker-runtime-footprint.md) executes
six complete Spring/Hibernate application JVMs against a real external-process
TCP Artemis broker: CP worker, eight-root worker, and full-catalogue application,
each with embeddings disabled and with actual native ONNX inference. All six
reach readiness and verify their real queue consumers. Both worker variants have
zero global catalogue rows and zero index bytes; the full baseline loads and
indexes all 2,572 nodes. Native CP/eight-root workers use **96.99/97.05 MiB heap**
and **789.37/790.79 MiB RSS**; the native full baseline uses **203.29 MiB heap**,
**1,299.66 MiB RSS**, and **4,614,146 index bytes**. The Maven-owned test passes
with no failures/errors/skips (282.2 seconds for all six cases).

These are single sequential samples with fresh HSQLDB, fixed JVM flags and the
pinned model, including retained NMT reports and artifact/source checksums. Empty
CP and eight-root worker startup is intentionally similar: admitted task data is
loaded on demand. This does not establish Kubernetes limits, external-database
capacity, provider throughput or a repeated-sample memory budget.

## Review corrections

Focused red-to-green runs cover strict provisioner TLS parsing (28 tests),
duplicate-root and durable input-reference validation plus bounded detached
catalogue reads (78 tests), worker commit-index startup (seven tests), and
preservation of terminal archive provenance on restore (ten tests). Catalogue
race tests now cancel daemon workers and release their gates on failure; memory
probes clear all three Java-option injection variables and verify actual flags.

The first remote security scan rejected the smoke fixtures' missing non-root and
read-only filesystem settings. Both pinned images now run with their actual
image UIDs, dropped capabilities, read-only roots and explicit writable volumes.
A fresh local Trivy **0.70.0** configuration scan reports **23 successful checks,
zero failures**, with the same HIGH/CRITICAL threshold. The combined chart passes
the existing full Helm verification, including all three new diagnostics cases.
The complete [remote security workflow](https://github.com/carstenartur/Taxonomy/actions/runs/37231204907)
passes on published head `3c24016`; later source revisions still require their own
applicable checks.

The final combined focused reactor passes **357 executions, zero failures,
errors or skips**, across all 16 modules (2 minutes 38 seconds). It covers the
changed catalogue, concurrency, scoring/relation and restore boundaries, the
actual Artemis production execution path, strict provisioner CLI, startup
lifecycle, Helm rendering, workflow/Python policies and required module guards.
The separate six-process native matrix and full Helm/Trivy checks above also
pass. These are targeted revision checks, not a completed canonical remote gate.

The additional lazy-startup regression observes actual TCP broker consumers in
`all`, `coordinator` and `worker` roles without looking up beans to initialize
them. All three roles fail before the lifecycle correction; the combined
configuration tests pass **9/9** after it. Two independent Hibernate storage
handles reproduce the exact missing-reftable failure before the template fix;
the **13** targeted template tests pass afterward, including idempotent bootstrap
and rejection of stale updates. Separate JVM block caches are modelled explicitly
so a shared test-process cache cannot hide the production failure.
Review also identified the storage library's catalogue/database lock inversion.
A capped subprocess reproduces that deadlock between a real pack flush and a
simultaneous head read. Both paths now share the live repository monitor, including
different wrappers, while retaining cross-process CAS. The regression terminates
within a fixed deadline even when the broken locks cannot be interrupted; child
Java-option injection variables are cleared.
The worker/global-draft regression reproduces the same 73-character premature
export seen in Kubernetes. All **18** focused bootstrap/initialization tests pass:
worker readiness cannot create the branch or claim its one-shot guard, while
subsequent coordinator readiness still publishes the complete fixture DSL.

The final combined startup regression reactor passes **293 executions across all
16 modules, zero failures, errors or skips**, in 2 minutes 27 seconds. It includes
the bounded lock-order subprocess, shared-template bootstrap, production TCP
Artemis computation and lazy lifecycle configuration, worker/global-draft
bootstrap, full Spring template initialization, Helm contracts and all mandatory
architecture guards. This is a targeted local gate; the corrected revision still
requires its own canonical, vendor-database and live Kubernetes results.

The subsequent branch-authority correction passes **276 combined executions
across all 16 modules, zero failures, errors or skips**, in 1 minute 40 seconds.
This includes POST/SSE and direct-call regressions, source movement rejection,
durable observation, frozen ONNX/relation behavior, production TCP Artemis
computation and all required architecture guards. It does not substitute for the
next live provisioning/admission/worker-loss scenario.

## Database-lane fixture corrections

The [database matrix on `55a7c55`](https://github.com/carstenartur/Taxonomy/actions/runs/37237916641)
passes PostgreSQL with **52** Failsafe cases, including all three new cluster
contracts. Its independently verified artifact `11317577851` has SHA-256
`195272dd56eb95995e97f88cd454ccb17d7c8d6a82604e9764104058ee079df2`.
SQL Server completes **32** cases with one error in the duplicate-completion
fixture; its artifact `11317469011` hashes to
`8a24ff2ea095e8209b902faceb8cb81950a27636ef70afe1deb32533e18858ce`.
The fixture starts its second transaction after the first has inserted an
uncommitted completion, then holds the first transaction until the second inserts.
[SQL Server's default locking READ COMMITTED](https://learn.microsoft.com/en-us/sql/t-sql/statements/set-transaction-isolation-level-transact-sql)
can block the second initial read behind that uncommitted row. The worker stack
was not retained, so this blocked-read location is inferred from the explicit
ordering and database semantics. Both transactions must instead finish their
absent-row reads before either attempts its real unique-key insert.

The Oracle lane fails before vendor integration tests: one of **2,489** ordinary
application unit cases fails in the provider interruption test. Artifact
`11317183090`, SHA-256
`4aa2f19aba367dda58839a9baaaea323fba8a04f62f6686fa2e21a8ca6723df2`, records the
failure at the wait helper's final assertion after only 0.220 seconds of its
10-second deadline. That helper has already observed the transient thread state
as true, then incorrectly requires a second observation to remain true. The
helper must preserve its successful observation. Neither failed lane establishes
vendor acceptance; corrected-head runs remain required.

The corrected database fixture gates both transactions at the existing JDBC
boundary before either `executeUpdate`, then races both real unique-key inserts.
A passive trace regression fails on the old ordering and verifies both arrivals
before either insert, using two distinct connections. Rollback, losing-delivery
savepoint handling, exactly one result effect and concurrent coordinator
aggregation remain covered. Failure cleanup releases gates, cancels daemon
workers and avoids an unbounded executor close.

The provider helper now retains its successful observation. A deterministic
false/true/false condition fails before the correction and passes afterward;
the 10-second deadline and 10-millisecond polling interval are unchanged.
The combined Java 21 reactor passes **240 executions across all 16 modules**,
zero failures/errors/skips, in **1 minute 50 seconds**: four database contracts,
ten provider-permit cases, thirteen actual TCP transport/production cases and
213 mandatory architecture guards. Independent review found no important issue.
The separate direct transaction gate also passes all **8 dispatch insertion**
and **6 durable-effect** cases, with zero failures/errors/skips (10 seconds).
These fixture corrections leave production code, database isolation, workflow
selectors and thresholds unchanged.

## Canonical checkpoint and dependency refresh

The complete [canonical workflow on `55a7c55`](https://github.com/carstenartur/Taxonomy/actions/runs/37237916582)
passes, including its final aggregate. The full 16-module Maven verification takes
1 hour 28 minutes. Independently verified artifact `11318516035`, SHA-256
`04200055562c8742deaf3569f8a118899f2179e159113c183c0d0295bfca77c4`, retains
1,101 JUnit XML reports with **7,874 cases, zero failures/errors and 79 skips**.
Those skips are the opt-in mock-score generator (3), screenshot generator (75)
and document-template standalone E2E profile (1); the separate document workflow
passes. All Artemis and cluster contract cases, including the six-process native
worker measurement, run without skips. This is a completed remote canonical gate,
unlike the earlier local OOM attempt.

Before publication of the fixture corrections, P05 advanced independently to
`31e1a03ec222dfbec46dbf246791daf971f56b02`. Its review change requires non-null
frozen branch/commit authority, gives duplicate worker shards an explicit error,
and refreshes the scoped data/cache measurement. The execution dependency base
is refreshed with exactly that P05 commit; execution-specific changes remain
outside the base. Independent review found no important integration issue.
The final combined Java 21 reactor passes **336 executions across 16 modules**,
zero failures/errors/skips, in **2 minutes 9 seconds**. It includes frozen
authority/provenance and worker isolation, root/relation execution, both fixture
corrections, direct transaction races, real TCP Artemis and mandatory guards.
The final combined revision requires its own CI results.

## Completed vendor/live checkpoint and coordinator observation correction

On execution head `d2e42e283e2cd6aad976bd5570f08d5f3f720210`, the complete
[database matrix](https://github.com/carstenartur/Taxonomy/actions/runs/37244435764)
passes **52 PostgreSQL, 32 Oracle and 32 SQL Server cases**, with zero failures,
errors or skips. Each vendor's actual Failsafe XML contains successful executions
of all three cluster contracts: large-payload persistence, concurrent duplicate
completion with rollback and one effect, and exact authority scoping.
Independently downloaded artifact hashes match GitHub:

| Vendor | Artifact | ZIP SHA-256 |
| --- | --- | --- |
| PostgreSQL | `11319038279` | `65c1b68df1bbf998827d654471d5f07250be745359dc1f7c56b490431fd6aea8` |
| Oracle | `11318854625` | `0c0f96639889fc80fa96a62bc889795c52c0bb07ac0c3e163b401fc669845f91` |
| SQL Server | `11319926763` | `3e1a737517206e8e25102c3439543ac7265ae921bfa8d3632dd23d8f952bf588` |

The same head's [constrained live run](https://github.com/carstenartur/Taxonomy/actions/runs/37244435676)
also passes both scenarios. Artifact `11319215202` has verified SHA-256
`d21d43fd536a517b2b0c73ca737c46a4455433186122263f6700e012a4095627`.
Tested merge `984bf7ce6b1152c869f7db747b1d406a35f6d641` has exactly the head's
tree `99c5c37087b93349511e1339e70c321dca343c7b`. Retained counters again establish
zero broker/database connections before lock release, CP **2** / IP **1**
delivery attempts, one durable effect per root, and **425** durable scores equal
to the response. Scaling, replacement identities, progress and egress pass.

The [canonical core](https://github.com/carstenartur/Taxonomy/actions/runs/37244435801)
then exposes a separate observation race in the eight-root transport test:
**6,911 cases, one failure, no errors or skips** in the retained partial reports.
Artifact `11319833086` has verified SHA-256
`b20bedb6c31d56c43743bb6655c6d674c494156d904b611dcc03014aec906e07`.
The test sees seven local coordinator counters after observing the terminal live
event and committed database state. `ClusterAnalysisStore.accept()` publishes
that event in its synchronous `afterCommit`; only after it returns does the
coordinator increment its local counter. The event is therefore not a barrier
for the counter update, nor is this counter an acknowledgement-completion metric.

A deterministic regression now holds that existing event publisher after the
real JMS send. It observes eight durable roots while the local counters remain
at seven, reproducing the old assertion failure. After releasing the callback,
the test waits within its existing bound for exactly eight accepted completions.
All original result, overlap and exactly-eight-computation assertions remain.
The latch has a bounded wait and unconditional cleanup; no production hook,
timeout extension, weakened equality or excluded test is introduced. Independent
review found no important issue. This test correction requires a fresh canonical
gate; the failed run is not accepted as green.

The fresh combined Java 21 reactor passes **233 executions across all 16 modules**
with zero failures, errors or skips in **1 minute 45 seconds**: 11 transport,
two production-computation and seven metric cases plus all 213 mandatory module
guards. The deterministic single-case run failed before the observation fix.

P05 `31e1a03` now passes all applicable workflows, including its full canonical
gate, native ONNX and all vendor databases. Final canonical artifact `11319633502`
contains **7,691 cases, zero failures/errors and 79 optional-profile skips**;
its SHA-256 is `40f13114c51f73f0d3cd79207b51afad6d137df7b9fb9b499b45b47700a42f9a`.
The native run executes **7,129 cases with no failures, errors or skips**.

## Canonical completion and CodeQL follow-through

Execution checkpoint `8e817b6e2bb410f89748dd28c4deeae04c8e113d` passes all
**13 jobs** of [canonical run 37249070342](https://github.com/carstenartur/Taxonomy/actions/runs/37249070342).
Independently verified final artifact `11322456187` has SHA-256
`be75a2a16115554a86ad6d92a07b78f1d1a9728e57985d60318b04c1df27999c`.
Its tested merge `fcfa51d562ed8b6b27ad27f200ebbb420c1222f7` has exactly the
head's source tree `e3ed15d3b1691bc9734c8fffb9f8bdc7f03eafa8`.

The authoritative Maven/quality summary declares **7,877 cases in 1,101 XML
reports**, zero failures/errors and **79 optional-profile skips**: three mock
score generators, 75 screenshot generators and the document E2E that passes in
its separate workflow. Direct XML enumeration finds 7,880 testcase elements;
the sole difference is `LlmResponseParserTest`, whose header declares 46 but
contains 49 entries. None contains a failure/error/skip marker. The transport
suite (11), production execution (2), backup restore (10), performance (1) and
whole-worker runtime probe (1, all six cold/native measurements) execute without
skips. All 16 Maven modules succeed.

This checkpoint also passes the complete vendor matrix (52 PostgreSQL, 32
Oracle and 32 SQL Server cases, no failures/errors/skips) and the constrained
Kubernetes worker-loss/redelivery scenario on the exact source tree. Current
PR descriptions and the issue tracker retain the independently checked artifact
hashes and actual three-vendor cluster contract results.

The complete commit-check inventory additionally identifies failed **push**
[CodeQL run 37249067314](https://github.com/carstenartur/Taxonomy/actions/runs/37249067314).
It was absent from the limited recent-workflow listing; a separately skipped
pull-request CodeQL run is not a replacement for the push gate. Java artifact
`11320985366` has SHA-256
`c3e30ddc331cb2e5762d4317bd0f968b8767f02717047d8c4654233e4ad93c95`;
JavaScript artifact `11320257097` has SHA-256
`4fc8afe107886dd022804436cae4f1ade76c251075280327659c8cd370a0d517`.
Their unchanged severity gate reports three `java/user-controlled-bypass`
findings and one `js/file-system-race`. This checkpoint is therefore not a
fully green execution revision.

All three Java paths identify the immutable `AnalysisOperationContext.authority()`
record accessor inside the input-validation disjunction. Invalid requirement
text short-circuits those accessor calls, but also rejects the entire admission
before any persistence; it does not create an accepted authority-bypass path.
The correction captures the immutable authority once before request-dependent
validation, matching the existing durable-execution boundary. The JavaScript
finding concerns a separate `statSync(path)` and `readFileSync(path)` pair in the
smoke evidence reader; it now reads directly and only treats an actual `EISDIR`
as a directory traversal case. Other read errors remain visible.

The unchanged Java implementation passes the eight-case store baseline,
including two new tests covering eleven rejected identity variants with zero
database/publication effects and exact admission replay/source-change fencing.
After the refactor, the combined Java 21 reactor passes **271 executions across
all 16 modules**, zero failures/errors/skips, in **3 minutes 25 seconds**. It
includes those authority cases, restore/observation, direct transaction races,
actual TCP Artemis and all 213 mandatory module guards. A local deterministic
file-replacement reproducer fails one of two cases before the JavaScript fix
and passes both afterward; all ten existing smoke tests and full Helm
verification also pass. Independent review found no important issue. Fresh
CodeQL and current-head CI results remain required; no baseline, threshold,
workflow selector or test exclusion was changed.

## Remaining environment acceptance

The complete canonical gate is `./mvnw -B verify -Pci -DrunOnnxTests=true`.
Selected green reactors and browser fixtures are not a substitute for this gate.
The full clean CI attempt passed every reactor through `taxonomy-portfolio`, then
reached 435 application tests before its JVM was killed by the environment
(exit 137; the cgroup recorded an OOM kill). It also exposed one missing provider
configuration-reference row, now corrected in both languages, and four existing
browser tests selecting Docker by default. All five failures/errors and the
interrupted restart checkpoint pass in the focused integrated rerun, using the
project's supported local Chrome mode and bounded JVM heaps (Maven 640 MiB, test
JVM 1,536 MiB). The 130-execution rerun is green; the interrupted full attempt is
not a green canonical gate.

The current workspace has no Docker, Kubernetes or CNI runtime. Successful remote
PostgreSQL, Oracle, SQL Server and constrained multi-pod evidence is recorded
above with its exact source revision. A new revision still requires its own
applicable CI results. Broker HA failover and production capacity require their
target-environment acceptance.
The local whole-process measurement above is complete. No thresholds, test
exclusions or required checks were weakened.

## Review series

| Branch | Review base | Scope |
| --- | --- | --- |
| [#1170: `feat/artemis-provider-permits`](https://github.com/carstenartur/Taxonomy/pull/1170) | `main` at the baseline above | P08: physical HTTP attempt permits, bounded provider metrics, explicit queue provisioning and real-broker tests. |
| [#1171: `feat/artemis-frozen-catalogue`](https://github.com/carstenartur/Taxonomy/pull/1171) | `main` at the baseline above | P05: consistent frozen catalogue inputs, worker-scoped data/index lifecycle, native ONNX parity and cache isolation. |
| [#1172: `feat/artemis-distributed-execution`](https://github.com/carstenartur/Taxonomy/pull/1172) | `feat/artemis-execution-base` | P04/P06/P07/P09/P10: durable root/relation execution, authorized observation, restore/schema integration, roles/deployment and failure acceptance. |

`feat/artemis-execution-base` contains only the first two component branches.
The execution review therefore excludes their implementation; it can target
`main` after those prerequisites are merged. The integration branch is not an
additional feature review, and no merge is authorized by this record.

Publication is authorized. The existing PR branch filters also admit
`feat/artemis-execution-base`, so the stacked execution review runs the same
applicable CI gates. Main/push triggers, path filters, jobs and thresholds are
unchanged. Current remote results belong to the exact PR heads; the historical
local evidence above is not a claim that remote CI or live acceptance has passed.

The publication recheck passes all eight JavaScript recovery tests and shell
syntax validation. The protected-release contract's former exact `main` list
failed as expected after adding the stacked base; its two tests pass with the
new exact list and all existing protected-main/release assertions retained.
The issue [implementation tracker](https://github.com/carstenartur/Taxonomy/issues/1161#issuecomment-5978670496)
records the published PRs and outstanding exact-head acceptance.
