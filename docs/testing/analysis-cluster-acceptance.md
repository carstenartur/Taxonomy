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

The live command was attempted locally and stopped at the missing Docker
prerequisite, without producing a success marker. The workflow is implemented,
but its live result, image startup and remote Jolokia authentication remain
unverified here. See [capacity and execution instructions](../../deploy/helm/taxonomy/CAPACITY.md).
This scenario does not claim broker HA, external-provider throughput, loss during
a physical provider call or a production memory/capacity baseline.

## Measured memory boundary

The retained nine-process [data/cache experiment](evidence/frozen-embedding-footprint-2026-10-04.json)
records CP frozen data/cache heap at **966,896 bytes** and all eight roots at
**10,141,400 bytes**. Vector payloads are **52,224** versus **3,950,592 bytes**.
These are fresh-JVM measurements of the frozen data stage. They exclude Spring,
native model memory, database connections and whole-pod RSS. They must not be
presented as a measured production pod-startup footprint or a capacity promise.

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
failover and whole-worker startup memory still require the existing CI/deployment
lanes. No thresholds, test exclusions or required checks were weakened.

## Review series

| Branch | Review base | Scope |
| --- | --- | --- |
| [#1170: `feat/artemis-provider-permits`](https://github.com/carstenartur/Taxonomy/pull/1170) | `main` at the baseline above | P08: physical HTTP attempt permits, bounded provider metrics, explicit queue provisioning and real-broker tests. |
| [#1171: `feat/artemis-frozen-catalogue`](https://github.com/carstenartur/Taxonomy/pull/1171) | `main` at the baseline above | P05: consistent frozen catalogue inputs, worker-scoped data/index lifecycle, native ONNX parity and cache isolation. |
| `feat/artemis-distributed-execution` | `feat/artemis-execution-base` | P04/P06/P07/P09/P10: durable root/relation execution, authorized observation, restore/schema integration, roles/deployment and failure acceptance. |

`feat/artemis-execution-base` contains only the first two component branches.
The execution review therefore excludes their implementation; it can target
`main` after those prerequisites are merged. The integration branch is not an
additional feature review, and no merge is authorized by this record.

Publication is authorized. The existing PR branch filters also admit
`feat/artemis-execution-base`, so the stacked execution review runs the same
applicable CI gates. Main/push triggers, path filters, jobs and thresholds are
unchanged. Current remote results belong to the exact PR heads; the historical
local evidence above is not a claim that remote CI or live acceptance has passed.
