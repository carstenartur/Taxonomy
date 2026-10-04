## Development objective

Replace the process-local analysis scheduling boundary with an **event-driven, cluster-capable execution model** based on Apache ActiveMQ Artemis / Jakarta JMS, while keeping Taxonomy's durable operation/result authority in the database.

The design must make it possible to execute one requirement's independent sub-taxonomy work in parallel across pods — up to the eight roots `BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA` — and later shard the taxonomy/search/embedding data held by each worker so those tasks do not require eight complete copies of all analysis data.

**Normal execution must not depend on periodic database polling.** Work is pushed through durable broker queues and progress/completion is driven by events. Database scans are permitted only as bounded recovery actions at startup, explicit repair/resume, or broker reconnection after a proven dispatch gap.

Baseline when this issue was written: `main` at `abb03b410efe6ad6f145199fcc799cfba4fbf49a`.

This issue builds on, but does not replace:

- #808 — durable ad-hoc analysis operation identity, lifecycle, replay and cancellation.
- #741 — exact repository/workspace/branch/source-commit read authority and shard-safe projections.
- #742 — durable lease/recovery conventions for repository operations.
- #638 — constrained Kubernetes/NetworkPolicy/deployment verification.
- merged #1145 — bounded multi-user admission, visible `QUEUED` state and per-provider request limiting.

## Architectural decision

### 1. Use Artemis/Jakarta JMS as the work transport, not distributed shared memory

Do **not** introduce Hazelcast as a required distributed cache/lock layer for this work.

Use an external Artemis broker for clustered deployments. The production application must not start one embedded broker per Taxonomy pod.

Keep transport behind framework-neutral ports so the analysis domain does not depend on `jakarta.jms.Message`, Artemis classes or broker destination names.

Single-node/development installations keep an in-process transport implementation. Existing installations must therefore remain usable without Artemis.

### 2. Separate authority from delivery

The database remains authoritative for:

- operation identity and exact tenant/repository/workspace/branch/source commit;
- selected sub-taxonomies and expected task graph;
- cancellation and terminal state;
- persisted sub-taxonomy/relation results;
- monotonic operation revision/event sequence required by #808;
- immutable provenance and stale detection.

Artemis is authoritative for **pending delivery**, redelivery and live work distribution.

Messages carry stable identifiers and immutable source references, not credentials and not a serialized Java object graph. Prefer identifiers over requirement/prompt payloads; workers load the authoritative requirement/result data for the exact recorded scope and commit.

### 3. At-least-once delivery + idempotent effects; no XA requirement in the first implementation

Every executable unit has a deterministic stable `taskId`.

A task may be delivered more than once. Persisting a result is compare-and-set/idempotent by `taskId`, operation revision and source authority.

A worker must persist its result **before** acknowledging the task. If it crashes after database commit and before JMS acknowledgement, redelivery observes the already persisted result, republishes the completion event if necessary and acknowledges without repeating durable effects.

Do not make distributed XA/JTA a prerequisite for the first implementation.

For the DB-commit → JMS-send gap, use a persisted dispatch intent plus immediate after-commit publish. A bounded recovery scan may run on application startup, explicit resume/repair, or broker reconnection. **There must be no fixed-rate “find pending rows” scheduler in the normal execution path.**

### 4. Decompose analysis into a DAG of typed tasks/events

Initial message contracts:

- `SubtaxonomyAnalysisTask`
- `SubtaxonomyAnalysisCompleted`
- `RelationAnalysisTask`
- `RelationAnalysisCompleted`
- `AnalysisProgressEvent`
- `AnalysisCancellationEvent`

Each envelope includes at least:

- `schemaVersion`
- `operationId`
- `taskId`
- `taskType`
- exact repository/workspace/branch/source commit identity
- requirement/version or snapshot identity
- sub-taxonomy root(s), when applicable
- attempt/redelivery metadata
- causation/correlation IDs
- created/deadline timestamps

Do not use native Java serialization or polymorphic class-name metadata. Use an explicitly versioned JSON contract.

## Broker topology

Use Artemis addresses/queues deliberately instead of one global queue.

### Sub-taxonomy work

Anycast queues:

- `taxonomy.analysis.subtaxonomy.BP`
- `taxonomy.analysis.subtaxonomy.BR`
- `taxonomy.analysis.subtaxonomy.CP`
- `taxonomy.analysis.subtaxonomy.CI`
- `taxonomy.analysis.subtaxonomy.CO`
- `taxonomy.analysis.subtaxonomy.CR`
- `taxonomy.analysis.subtaxonomy.IP`
- `taxonomy.analysis.subtaxonomy.UA`

A pod may consume one or several configured roots. Multiple consumers on one root queue provide horizontal scaling for that shard.

Do **not** group all tasks of one requirement with one `JMSXGroupID`: that would serialize the eight independent root tasks and defeat the intended parallelism.

### Relation work

Relations are routed by the shard whose target taxonomy data is needed, e.g.

- `taxonomy.analysis.relation.BP`
- …
- `taxonomy.analysis.relation.UA`

A relation message references persisted source-shard results instead of copying complete source taxonomies or prompts.

Relation tasks may be emitted as soon as their prerequisites are available; do not require a global “wait for all eight” barrier when a pair can already be evaluated safely.

### Results and progress

- one durable anycast result/coordinator queue for exactly-once *processing effect* through idempotent consumers;
- one multicast progress address for live web/SSE fan-out;
- per-web-pod transient subscriptions for live delivery;
- #808's persisted event/revision stream remains the replay authority after reconnect/reload.

### Failure destinations

Configure bounded redelivery, expiry and DLQ behavior per task family. A poison task must not block unrelated work on the same shard.

## Sharding model

Support two independent dimensions:

1. **execution sharding** — route tasks by sub-taxonomy root;
2. **data sharding** — load only the root-specific taxonomy/search/embedding state required by a configured worker.

Introduce a typed worker-shard configuration, for example:

`taxonomy.analysis.worker.shards=CP,IP`

Do not encode roots as arbitrary free-form strings internally; map to the existing taxonomy root type/validated root identifiers.

The first transport PR may still run workers with the full catalogue. Data sharding follows only after distributed execution semantics are proven, so transport bugs and projection bugs are not introduced simultaneously.

A shard worker must still receive exact immutable source authority from #741. It must never silently fall back to another branch, commit, workspace or global “current” taxonomy.

## Consistency and cancellation

### Dispatch

1. Coordinator persists operation/task graph and `DISPATCH_PENDING` intent.
2. Database transaction commits.
3. Immediate after-commit publisher sends the JMS task and marks the intent dispatched.
4. A crash in steps 2–3 is recovered by a bounded startup/reconnect/explicit-repair scan.
5. Duplicate sends are harmless because `taskId` is stable; use Artemis duplicate detection as an additional optimization, never as the only idempotency guarantee.

### Worker completion

1. Receive task in a transacted/client-acknowledged JMS session.
2. Validate exact authority and current operation state.
3. Execute the shard work.
4. Persist result idempotently.
5. Publish completion/progress.
6. Acknowledge/commit the JMS delivery.

Crash after step 4 must redeliver and complete without repeating durable effects.

### Cancellation

Cancellation is persisted first under #808's operation authority and then published as a multicast control event.

A connected worker cooperatively stops through the existing `AnalysisRunControl` path.

Every worker also checks the durable operation state before starting a redelivered/queued task. A cancelled operation therefore cannot be resurrected merely because a cancel event was missed.

A late successful provider response may be recorded as bounded diagnostic evidence, but CAS terminality prevents it from overwriting cancellation.

## Provider capacity

Do not solve cluster job distribution and provider quota coordination by adding Redis/Hazelcast in the first PRs.

Keep current provider limiting while the work/task model is introduced, then add a broker-backed cluster permit mechanism as a separate bounded package:

- one permit queue per provider for maximum concurrent HTTP calls;
- `N` durable permit messages represent `N` cluster-wide concurrent requests;
- a worker acquires a permit before the physical HTTP exchange;
- permit return and acknowledgement happen in one local JMS transaction;
- worker death returns an unacknowledged permit through redelivery;
- cancellation while waiting for a permit remains cooperative.

RPM/TPM/RPD policy is a separate concern. Do not pretend a concurrency permit queue is a token-rate limiter. Preserve current RPM behavior until a tested broker-backed rate-token/scheduler design is added.

Provider aliases that share one upstream quota must be explicitly configurable into the same quota group; never infer that from display names.

## Security

- Production Artemis connections use TLS and credentials from Kubernetes/secret configuration.
- Broker messages contain no LLM API keys, access tokens, passwords or session cookies.
- Prefer IDs/source references over requirement or prompt payloads.
- Hawtio/Jolokia/management endpoints are administrative surfaces and must not be exposed publicly by the normal Taxonomy ingress.
- Broker diagnostics must not retain prompt/response content by default.
- Tenant identity in a message is routing/provenance data, not authorization. The consumer revalidates exact scope/authority before effects.

## Observability

Artemis/Hawtio becomes the broker-level operational view for:

- queue depth by sub-taxonomy/relation shard;
- active consumers;
- delivering/redelivering messages;
- expiry/DLQ counts;
- broker/connection health.

Taxonomy exposes application-level metrics for:

- operation/task counts by typed phase/status;
- queue-to-start latency;
- shard execution duration;
- per-root concurrency;
- redelivery/idempotent-replay counts;
- cancellation latency;
- LLM wait/execution time;
- relation fan-out count;
- completion-to-next-task dispatch latency.

Do not expose other users' requirement text or identities in ordinary user-visible queue status.

## Kubernetes model

Use the same Taxonomy application artifact initially, selected by runtime role/profile rather than creating separate products immediately.

Suggested roles:

- `web/coordinator`
- `analysis-worker`
- `all` for local/single-node compatibility

Worker pods receive shard configuration independently. Examples:

- deployment `taxonomy-worker-cp`: `CP`
- deployment `taxonomy-worker-ip`: `IP`
- deployment `taxonomy-worker-general`: several roots during migration

Scale a hot shard independently without multiplying every other shard.

The Helm chart must support an **external broker endpoint first**. Bundling and owning a production Artemis cluster inside the Taxonomy chart is not required for the first implementation.

Integrate broker egress/TLS/secrets with #638's NetworkPolicy and constrained-cluster tests.

## Implementation packages

### P01 — Typed analysis DAG contracts and transport ports

**Goal:** Extract transport-neutral task/event contracts without changing execution behavior.

- [ ] Introduce typed task IDs, task types, shard/root identity and schema-versioned envelopes under `taxonomy-analysis`.
- [ ] Add ports such as `AnalysisTaskPublisher`, `AnalysisCompletionPublisher` and task handler interfaces.
- [ ] Model selected roots and prerequisite graph explicitly; support “all roots”, selected subset and “no relations”.
- [ ] Keep `AnalyzeRequirementUseCase` behavior equivalent through an in-process adapter.
- [ ] Ensure broker metadata never enters LLM prompts.
- [ ] Add architecture tests preventing `jakarta.jms`/Artemis dependencies from leaking into the analysis-domain contracts.

**Acceptance:** existing single-node analysis produces semantically equivalent results/call counts through the new in-process task contracts.

### P02 — Artemis adapter and opt-in cluster profile

**Goal:** Add JMS transport without yet sharding the catalogue.

- [ ] Add Spring Boot Artemis/Jakarta JMS dependency only in the adapter/application layer.
- [ ] Add `local` and `artemis` transport selection; local remains the compatibility default.
- [ ] Define destination configuration, JSON conversion, schema validation, bounded message sizes and broker connection health.
- [ ] Use Testcontainers or an equivalent disposable real Artemis broker in integration tests; no mocked JMS API as acceptance evidence.
- [ ] Test reconnect, broker restart, malformed/unknown schema version and poison message → DLQ.
- [ ] Document TLS/credentials/external broker configuration in DE/EN.

**Acceptance:** two application instances consume the same durable queue, one task has one durable effect, and broker restart does not lose accepted queued work.

### P03 — Durable dispatch intent without normal DB polling

**Goal:** close DB→broker crash windows without making polling the scheduler.

- [ ] Persist deterministic task graph/dispatch intent alongside #808 operation authority.
- [ ] Publish immediately after commit.
- [ ] Record dispatch acknowledgement/idempotency.
- [ ] Add bounded recovery triggers only at startup, explicit resume/repair and broker reconnection.
- [ ] Prove there is no fixed-rate pending-work DB scheduler in clustered normal operation.
- [ ] Inject crashes before/after DB commit, send and dispatch acknowledgement.

**Acceptance:** every accepted task is either delivered, explicitly `DISPATCH_FAILED/WAITING_FOR_BROKER`, or recoverable after restart/reconnect; no silent stuck operation and no periodic DB polling requirement.

### P04 — Parallel sub-taxonomy fan-out and aggregation

**Goal:** execute independent root scoring concurrently across pods.

- [ ] Split root scoring out of the monolithic run into one task per selected root.
- [ ] Route the eight default roots to their queues.
- [ ] Persist root result keyed by `operationId + taskId + source authority`.
- [ ] Aggregate out-of-order completions with CAS/idempotency.
- [ ] Publish known-total progress (`x/y roots complete`) from durable task state.
- [ ] Keep partial/failed root semantics explicit; do not silently treat a missing root as score zero.
- [ ] Use a deterministic delayed MOCK provider to prove actual overlap rather than merely multiple executor threads.

**Acceptance:** with eight available consumers and a non-limiting mock provider, eight root tasks overlap in execution and total wall-clock behavior follows the slowest shards rather than their serial sum; LLM prompt/call semantics do not grow merely because transport is distributed.

### P05 — Worker data sharding

**Goal:** allow a worker to hold only configured taxonomy/search/embedding shards.

- [ ] Introduce a root-scoped catalogue/projection view instead of teaching callers to filter a complete tree ad hoc.
- [ ] Load/index only configured roots in shard-worker mode.
- [ ] Partition embedding/search cache keys by exact source authority + root + model/config version.
- [ ] Fail closed if a task reaches a worker without its required shard.
- [ ] Preserve full-catalogue mode for web/general/local installations.
- [ ] Measure startup heap/index footprint per one-root worker versus full worker and retain the result as evidence, not as a hard-coded production promise.

**Acceptance:** a CP-only worker can score CP without loading unrelated root indexes; concurrent CP/IP workers cannot reuse one another's cache/index entries.

### P06 — Relation DAG fan-out

**Goal:** replace the current slow monolithic relation phase with independent relation work.

- [ ] Derive relation tasks only from persisted non-zero/otherwise-required root results.
- [ ] Route by target shard so the target worker owns the taxonomy data needed for hierarchical evaluation.
- [ ] Reference source result IDs rather than copying complete source trees.
- [ ] Emit pair work as soon as prerequisites exist where safe; do not impose an unnecessary all-roots barrier.
- [ ] Bound per-operation relation fan-out and expose the known task total once derivable.
- [ ] Preserve result quality/ordering contracts and compare distributed results with the current sequential implementation on fixed fixtures.

**Acceptance:** relation wall-clock time decreases under available parallel capacity without increasing LLM prompt content/call count solely due to sharding; distributed and sequential fixtures produce equivalent relation decisions.

### P07 — Cancellation, progress, replay and UI event bridge

**Goal:** make clustered execution observable without browser polling as the primary path.

- [ ] Persist cancel under #808 then publish control event.
- [ ] Bridge live progress multicast events to locally connected SSE clients.
- [ ] Reconnect/reload uses #808's durable event/revision replay; live broker delivery is an acceleration, not replay authority.
- [ ] Preserve monotonic sequence and reject duplicate/out-of-order/foreign events before DOM state mutation.
- [ ] Show task family/root, queued/running counts and partial completion without exposing foreign-user details.
- [ ] Verify cancellation before dispatch, while queued, waiting for provider, during provider call and after provider response.

**Acceptance:** a browser connected to any web pod receives live progress for work executed on other pods; reload/reconnect resumes from durable state without starting another analysis.

### P08 — Cluster-wide provider concurrency permits

**Goal:** make maximum concurrent external HTTP calls a deployment-wide property.

- [ ] Implement provider/quota-group permit queues with durable permit messages.
- [ ] Acquire immediately before each physical HTTP attempt, including retries.
- [ ] Return permits transactionally on completion; broker redelivery restores permits after worker death.
- [ ] Preserve cancellation while waiting.
- [ ] Keep RPM/TPM/RPD separate and documented; do not weaken existing limiter behavior.
- [ ] Add provider quota-group configuration for aliases sharing an upstream account.

**Acceptance:** N permits cap physical concurrent requests at N across multiple pods; killing a permit holder cannot permanently reduce capacity or create an extra permit.

### P09 — Deployment, HA and administration

**Goal:** provide an operable clustered profile without making Taxonomy own the broker.

- [ ] Helm values for external Artemis URL/TLS/credentials and worker roles/shards.
- [ ] NetworkPolicy egress through #638.
- [ ] Readiness distinguishes “web usable” from “worker can reach broker/provider”.
- [ ] Document supported external Artemis HA expectations and Hawtio/Jolokia access as admin-only.
- [ ] Expose queue/shard metrics to the existing observability pipeline.
- [ ] Provide scaling examples per shard; do not require all eight deployments when a smaller topology is appropriate.

**Acceptance:** a disposable multi-pod cluster can scale one hot shard independently, survive one worker-pod loss and keep unrelated shards progressing.

### P10 — Failure, portability and performance acceptance

**Goal:** prove this is a cluster architecture, not merely a JMS integration.

Run through Maven/JUnit/Failsafe and the existing CI evidence model:

- [ ] PostgreSQL, Oracle and SQL Server durable task/result mappings.
- [ ] two users + overlapping IDs + different workspaces/repos/branches; reverse completion order; no context leakage.
- [ ] coordinator pod loss before/after dispatch.
- [ ] worker loss before provider call, during call, after DB result commit and before JMS acknowledgement.
- [ ] broker restart and temporary disconnect.
- [ ] duplicate/redelivered task and completion events.
- [ ] queued and running cancellation races.
- [ ] poison message, max redeliveries and DLQ.
- [ ] selected-root run and no-relations run.
- [ ] eight-root concurrent run.
- [ ] CP/IP data-sharded workers with overlapping cache/search identifiers.
- [ ] relation fan-out saturation with bounded queue growth.
- [ ] provider 429/`Retry-After` and permit recovery.
- [ ] browser reconnect to another web pod.
- [ ] no LLM credentials/prompts in broker management evidence.
- [ ] constrained Kubernetes resource/egress scenario via #638.

The authoritative comparison must report:

- total provider calls;
- total prompt characters/tokens where known;
- root/relation task counts;
- queue wait and execution time;
- peak per-root/provider concurrency;
- redeliveries;
- memory/index footprint by worker role;
- final semantic result equivalence against the non-distributed reference fixtures.

## Migration and compatibility

1. Ship task contracts + in-process adapter first.
2. Add Artemis as opt-in; default behavior remains local.
3. Run full workers through Artemis before introducing data sharding.
4. Enable root fan-out.
5. Enable relation fan-out.
6. Add root-scoped data loading.
7. Add broker-backed provider concurrency permits.
8. Only after equivalent CI/quality evidence, deprecate redundant process-local executor queues that no longer own work.

Do not remove the existing process-local path until the clustered path has restart/redelivery/cancellation/database/browser evidence.

## Explicit non-goals

This issue does **not**:

- replace #808's operation persistence/replay design;
- replace #741's exact-context authority;
- use Hazelcast as distributed shared memory;
- introduce Redis merely to implement locks/semaphores;
- require Kafka;
- require XA/JTA in the first implementation;
- make database polling the scheduler;
- put full prompts/API credentials in broker messages;
- guarantee exactly-once external LLM billing;
- bundle and operate a production Artemis cluster inside the Taxonomy application;
- claim cluster-wide RPM/TPM/RPD until those mechanisms have their own executable evidence.

## Completion criteria

The issue is complete only when:

- [ ] one requirement can fan out across independent sub-taxonomy consumers on multiple pods;
- [ ] configured shard workers can run without loading unrelated shard data;
- [ ] relation work can be distributed by shard;
- [ ] normal progress is driven by JMS events rather than periodic DB polling;
- [ ] accepted queued work survives worker/web pod restarts and broker restart;
- [ ] duplicate/redelivered messages have idempotent effects;
- [ ] cancellation and tenant/source authority remain deterministic across pods;
- [ ] user-visible progress/reconnect works across web pods;
- [ ] provider concurrency can be bounded cluster-wide without Hazelcast/Redis;
- [ ] Hawtio/Artemis plus Taxonomy metrics provide operational queue/consumer/DLQ visibility;
- [ ] canonical CI, database compatibility, security, browser and constrained-cluster checks are green.

## Suggested PR order

Keep each PR independently mergeable and green:

1. **Contracts + in-process DAG adapter**
2. **Artemis transport/profile + real-broker tests**
3. **Dispatch intent + event-driven recovery triggers**
4. **Sub-taxonomy fan-out/aggregation**
5. **Root-scoped catalogue/search/embedding data**
6. **Relation fan-out**
7. **Cancellation/progress/replay bridge**
8. **Provider permit queues**
9. **Helm/NetworkPolicy/observability**
10. **Cluster failure/performance acceptance and removal of obsolete local scheduling paths**

Do not combine transport, data sharding, relation redesign and provider permits into one PR. Each step must have a red/green regression that proves the behavior it changes.
