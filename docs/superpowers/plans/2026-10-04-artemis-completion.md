# Artemis distributed analysis implementation plan

> **For agentic workers:** Use superpowers:executing-plans for coordinator work and superpowers:dispatching-parallel-agents for independent packages. Preserve the original package boundaries when publishing PRs.

**Goal:** Complete the production execution path behind the transport merged in #1169, satisfying #1161 P04–P10.

**Architecture:** The database owns immutable input, expected tasks, cancellation, results and event revision. Root and relation workers use the existing prepared-result/completion transaction. Artemis owns durable delivery; coordinator completion effects are idempotent, progress is multicast and replay comes from durable state. No regular database polling.

**Tech Stack:** Java 21, Spring Boot, Hibernate, Jakarta JMS/Artemis, Maven/JUnit, Helm.

**Spec:** `docs/superpowers/specs/2026-10-04-artemis-completion.md` (the accepted issue #1161).

## Global constraints

- Local remains the default; no embedded broker in production, Hazelcast, Redis or XA requirement.
- No credentials or prompts in broker messages; persisted immutable references only.
- Exact repository/workspace/branch/source and requirement authority must match before computation and again before effects.
- Cancellation is durable and terminal. No missing or failed shard becomes a zero score.
- Keep root scoring and relation prompt/call semantics; frozen data reads never fall back to the current catalogue.
- Execute focused Maven tests and preserve CI, browser, database and constrained-cluster gates.

## Review focus

- A stale or foreign completion must not mutate another operation, including overlapping user-supplied IDs.
- A crash between result commit and broker acknowledgement must replay one durable effect.
- Cancellation during computation or permit wait must remain terminal after late delivery.
- Reconnecting a web pod must read a durable snapshot without restarting analysis or losing events.
- Partial input and bounded relation budgets must remain explicit and deterministic under reverse completion order.

### Task 1: Durable root coordinator and production handlers (P04)

**Files:** new `taxonomy-analysis/.../cluster/` operation, result, event store and coordinator; `AnalyzeRequirementUseCase`; Artemis completion consumer/configuration; V32 migration and backup inventory; cluster store and real broker tests.

**Interfaces:** `ClusterAnalysisStore` persists immutable command/context, graph, root snapshots, results and monotonic events. `ClusterAnalysisService` admits one task per selected root and implements typed preparation callbacks. Existing `AnalysisDispatchService` writes intents in the operation transaction. Completion handling aggregates only persisted results.

- [x] Add failing real-Hibernate tests for authority, cancellation, duplicate/reverse completions and result persistence.
- [x] Run focused tests and observe the missing behavior.
- [x] Implement short transactional admission/result/aggregation boundaries and bind the production root handler.
- [x] Add real Artemis tests with independent consumers and delayed MOCK scoring that proves overlap and bounded call counts.
- [x] Run focused Maven tests and commit the root package.

### Task 2: Frozen root data and worker data sharding (P05)

**Files:** `taxonomy-knowledge/.../catalog/service/` snapshot/read binding, TaxonomyService and overlay read boundaries; worker startup/search configuration and tests.

**Interfaces:** Immutable scalar root snapshots captured at admission and bound during computation; only the required roots are loaded per task, with exact source/context keys and fail-closed missing roots. No analysis/JMS dependencies in knowledge.

- [x] Prove missing/foreign shards fail and CP/IP snapshots cannot contaminate each other.
- [x] Implement scoped snapshot reads and worker-only startup behavior without unrelated indexes.
- [x] Measure snapshot node/heap footprint and verify unchanged local behavior; commit the shard package.

### Task 3: Independent relation work (P06)

**Files:** relation planning/evaluation service and tests, coordinator relation task/result integration.

**Interfaces:** Persist shared source assessments once; deterministic typed target work refers to them and to exact frozen target data. Parallel evaluation reuses existing downwalk prompts and enforces a total operation budget.

- [x] Test sequential/distributed equivalence, partial inputs, no-relations scope and bounded fan-out/call budget.
- [x] Extract preparation and target evaluation without duplicated extraction calls.
- [x] Dispatch ready work after prerequisites; combine persisted results deterministically; commit.

### Task 4: Durable progress, cancellation and SSE (P07)

**Files:** cluster operation endpoints, existing progress/controller/UI bridge, Artemis event subscriptions and tests.

**Interfaces:** Persist cancellation before multicast. Event revisions wake operation-specific listeners; readers authorize against the durable operation and replay after a supplied revision. Worker cancellation binds the existing AnalysisRunControl.

- [x] Test queued/running/late cancellation, reverse/foreign events and reconnect to another listener.
- [x] Implement progress/cancellation multicast and durable replay with no periodic DB scheduler.
- [x] Verify existing user flows and operation result recovery; commit.

### Task 5: Cluster provider permits (P08)

**Files:** neutral provider-permit port, gateway physical HTTP boundary, Artemis permit implementation/configuration and broker tests.

**Interfaces:** Acquire immediately before each physical HTTP attempt, including retries; return in one local JMS transaction. Explicit provider-to-quota-group configuration. Preserve existing RPM/backoff limiter.

- [x] Test N permits across two clients, cancellation while waiting, loss of holder and duplicate initialization.
- [x] Implement permit lifecycle and gateway wiring, no secret-bearing messages; commit.

### Task 6: Runtime roles and deployment (P09)

**Files:** Helm values/templates/network policy, broker example/operations documentation and template tests.

**Interfaces:** `taxonomy.analysis.runtime-role=all|coordinator|worker`; existing worker shards and external TLS broker properties. Worker role loads only configured shard data. No public administrative broker ingress.

- [x] Add rendering checks for local compatibility, coordinator, CP/IP workers, credentials and restricted egress.
- [x] Implement role-aware deployments/readiness and scaling documentation; commit.

### Task 7: Failure and portability acceptance (P10)

**Files:** real broker, multi-database and browser/cluster acceptance suites, metrics/evidence and implementation tracker.

- [x] Exercise worker/coordinator/broker loss, duplicate deliveries, cross-user authority and bounded saturation.
- [x] Run the canonical Maven and available Docker-free browser checks; report unavailable external prerequisites accurately.
- [x] Review the complete change with a fresh reviewer; fix important findings with regression tests.
- [x] Publish independently reviewable follow-up PRs and update #1161 with exact evidence and remaining gates. PRs #1170, #1171 and #1172 are open; tracker comment 5978670496 records the executed evidence and remaining gates. No follow-up merge is claimed.

### Publication decisions

- Ruling: extend only the existing PR branch filters to `feat/artemis-execution-base`, because the execution PR depends on the two separately reviewed component branches. Main/push behavior, paths, jobs, selectors and thresholds remain unchanged; the additional cost is running the same gates for the stacked review.
- Final publication review: the legacy exact-text branch expectation in `ProtectedReleaseMainContractTest` failed after that addition (two executions, one failure). Updating the expected list to exactly `main` and the stacked base preserves every protected-main/release safeguard; the same two tests now pass. The reviewer found no other important publication blockers.
- Recovery: the complete production/test/deployment source was recovered from retained original files and patches. The intermediate integrated tree matches `ce959f3c05f8cad7e3b476df784aa2e097358cf3` exactly. Two lost DE/EN provider-enable description cells use the independently tested P08 wording; their property, environment variable and default are unchanged. The restored final source tree is `6481ad9e8f79bacc87b9a6481495d7843248f33e`, before this publication-only workflow/documentation change.

## Verification status

See [executed acceptance evidence and remaining target-environment gates](../../testing/analysis-cluster-acceptance.md). Checked implementation steps do not imply a successful canonical CI run or live Kubernetes acceptance.
