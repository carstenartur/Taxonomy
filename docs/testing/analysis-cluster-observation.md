# Durable cluster observation

`AnalysisProgressController` prefers durable cluster operations over process-local
telemetry. The authenticated owner and exact workspace, repository and branch are
resolved before observation or cancellation. A known operation outside that scope
returns the same 404 as an absent operation and cannot fall back to a local run.
Local installations retain their existing status polling and cancellation path.

| Endpoint | Behavior |
| --- | --- |
| `GET /api/analysis-runs` | Bounded recent visible operations; durable records take precedence over matching local IDs. |
| `GET /api/analysis-runs/{id}` | Compatible progress fields plus `transport: artemis`, immutable `scope` and typed task/root counts for cluster work. |
| `POST /api/analysis-runs/{id}/cancel` | Persists terminal cancellation before local control delivery and broker publication. |
| `GET /api/analysis-runs/{id}/events` | SSE observations with durable revision IDs; never starts work. |
| `GET /api/analysis-runs/{id}/result` | Retrieves the persisted result; 409 while not ready, 404 outside scope. |
| `GET /api/analysis-runs/{id}/request` | Retrieves the original requirement, provider, analysis scope and immutable observation scope for an explicit recovery choice; 404 outside scope. |

The event endpoint accepts `afterSequence` and the browser's `Last-Event-ID`, using
the greater nonnegative cursor. It subscribes before its first durable read. Broker
progress and reconnection signals schedule coalesced reads on an executor outside
producer transaction callbacks. No periodic database scan drives observation.

Replay converges to the **latest persisted full snapshot at its actual revision**.
The smaller persisted progress events are wake/recovery hints, not historical full
view payloads. Several intermediate hints may therefore appear as one SSE
snapshot. The adapter never combines current results with an older event ID.
Terminal streams and disconnected clients release their local subscriptions.
Architecture preparation remains `RUNNING` with phase `FINALIZING`; the result
endpoint returns 409 until the enriched terminal result has committed.

The browser switches to EventSource once `transport: artemis` is advertised. Native
EventSource reconnect retains its last event ID and the original workspace URL.
Duplicate, reverse, foreign-operation and changed-source events are rejected before
rendering. Leaving an observed cluster run closes the stream and issues at most one
pinned cancellation write, without restarting status polling. Shared relation
preparation has a null typed root and is labeled "Preparation" until target tasks
exist. Root completion counts describe settled work, including partial outcomes;
the operation status preserves partial/cancelled semantics.

Once the workspace is ready, a single recent-run read offers **Resume observation**
and **Retrieve result** controls, with active runs first. Selecting one explicitly
restores that run's original requirement and observes the existing operation. The
request DTO excludes credentials, usernames and internal execution snapshots. A
new workspace, changed source, edited requirement or newer analysis invalidates a
pending recovery before it can replace UI state. Discovery and recovery never POST
another analysis or start periodic database polling.

`TaxonomyScoring` applies the persisted terminal result through the same rendering
path as a normal analysis response. An accepted cluster run can therefore finish
through SSE after its original start request disconnects. A failed result read
offers a visible **Retry result** button; that action only repeats the authorized
result GET. `TaxonomyAnalysisProgress.start(id, onScores, onResult, expectedScope)`
and `monitor.recoverResult()` retain the original workspace/generation ownership.

Focused verification:

```sh
./mvnw -pl taxonomy-analysis -am \
  -Dtest=DurableClusterAnalysisObservationTest,AnalysisProgressControllerTest,ClusterAnalysisStoreTest,ClusterAnalysisSignalsTest,ClusterRelationCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
node --test .github/scripts/analysis-live-progress.test.mjs \
  .github/scripts/analysis-cluster-recovery.test.mjs \
  .github/scripts/analysis-session-api-routing.test.mjs \
  .github/scripts/analysis-queue.test.mjs
npm --prefix .github run test:analysis-session-startup
```

The Java tests use real Hibernate transactions and independent web-pod signal hubs.
They cover the subscription/read race, committed-state reads on the production
executor, missed wakeups, reverse/duplicate/foreign hints, durable result recovery,
and cancellation surviving a broker publication failure. Broker delivery itself is
covered separately by the Artemis transport acceptance tests.
The recovery DOM fixture executes the actual API, progress and scoring modules to
verify reload discovery, explicit input restoration, result rendering, read-only
retry, disconnected start responses and workspace/source/text/new-analysis races.

Native-browser acceptance is also available:

```sh
TAXONOMY_CHROME=/path/to/chrome-headless-shell \
  npm --prefix .github run verify:analysis-cluster-recovery-browser
```

The command uses the installed `@playwright/test` by default. Set
`TAXONOMY_PLAYWRIGHT_MODULE` to an absolute Playwright module path when using an
external test runtime. Its loopback server serves the unchanged production API
client, workspace routing, progress and scoring scripts with authored HTTP/SSE
responses. It exercises real DOM controls, reload discovery, original-input
restoration, terminal rendering, visible result retry, duplicate/reverse/foreign
event rejection and native EventSource reconnection with `Last-Event-ID`. A live
connection is held beyond the local polling interval to check that no status GET
is repeated. Held input/result responses verify that workspace or source changes
cannot repaint the current draft. Every scenario requires zero POST requests.

The report and screenshots are written to
`target/analysis-cluster-recovery-browser/` (or `TAXONOMY_QA_OUTPUT`). The report
records browser version, source commit, served-script hashes and observed HTTP
requests. This is component acceptance with fixture session state, translations,
rendering sinks and controller responses. It does **not** verify Spring login,
authorization, real workspace navigation, full diagram rendering, Artemis
delivery or provider execution; those need their separate integration gates.
