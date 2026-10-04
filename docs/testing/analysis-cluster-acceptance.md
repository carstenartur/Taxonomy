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
See [capacity and execution instructions](../../deploy/helm/taxonomy/CAPACITY.md).
This scenario does not claim broker HA, external-provider throughput, loss during
a physical provider call or a production memory/capacity baseline.

## Measured memory boundary

The retained nine-process [data/cache experiment](evidence/frozen-embedding-footprint-2026-10-04.json)
records CP frozen data/cache heap at **966,896 bytes** and all eight roots at
**10,141,400 bytes**. Vector payloads are **52,224** versus **3,950,592 bytes**.
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

The current workspace has no Docker, Kubernetes or CNI runtime. PostgreSQL,
Oracle and SQL Server execution, real constrained multi-pod operation, broker HA
failover and production capacity still require the existing CI/deployment lanes.
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
