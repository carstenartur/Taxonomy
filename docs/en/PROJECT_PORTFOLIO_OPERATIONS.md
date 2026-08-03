# Project portfolio operational limits and recovery

The project portfolio applies explicit server-side limits before persisting or starting expensive work.

| Property | Environment variable | Default | Purpose |
|---|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `100` | Maximum requirements accepted by one analysis job |
| `taxonomy.portfolio.max-import-requirements` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `100` | Maximum requirement candidates accepted by one import request |
| `taxonomy.portfolio.max-import-characters` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `500000` | Maximum combined requirement and original-source characters in one import |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `900` | Age after which a `RUNNING` item can be recovered |
| `taxonomy.portfolio.analysis-worker-core-pool-size` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CORE_POOL_SIZE` | `1` | Always available background analysis workers |
| `taxonomy.portfolio.analysis-worker-max-pool-size` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_MAX_POOL_SIZE` | `4` | Maximum concurrent background analysis workers |
| `taxonomy.portfolio.analysis-worker-queue-capacity` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY` | `100` | Persisted jobs that may wait in the in-process dispatch queue |
| `taxonomy.portfolio.analysis-worker-shutdown-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS` | `30` | Graceful shutdown period for active workers |

## HTTP job contract

Analysis submissions are persisted before provider work begins. `POST /api/projects/{projectId}/analyses`, the single-requirement analysis endpoint and retry requests return `202 Accepted` with an `AnalysisJobView` body and a canonical `Location` header:

```text
/api/projects/{projectId}/analysis-jobs/{jobId}
```

Clients poll that resource until the job reaches `SUCCESS`, `PARTIAL`, `FAILED` or `CANCELLED`. The portfolio browser performs this polling automatically. The original HTTP request therefore does not remain open for the duration of one or more LLM calls.

The executor is bounded. When all workers and queue slots are occupied, the API returns an RFC 9457 `503 Service Unavailable` problem. The job is already persisted and its identifier is included in the problem detail, so the same idempotent submission can be sent again instead of creating duplicate work.

## Claiming and recovery

An analysis item is claimed atomically from `PENDING` to `RUNNING` in a dedicated short transaction. The external LLM request starts only after that transaction commits.

Retry requests reset failed items and only those running items whose claim is older than the configured timeout. The reset itself uses status- and timestamp-guarded compare-and-set updates, so concurrent retry requests cannot recover the same item twice or increment its attempt counter twice. A persisted `PENDING` job whose in-process dispatch was lost during shutdown can also be dispatched again through the retry endpoint.

A low timeout can duplicate work when a provider legitimately needs longer than the timeout. Set it above the longest expected provider call plus deterministic post-processing time. Active workers are not interrupted by a retry request.

During graceful shutdown, active workers receive the configured completion period. If a process stops before an item finishes, its persisted `RUNNING` claim becomes eligible for recovery after the timeout; no in-memory queue is treated as the source of truth.

Oversized analysis or import requests fail before an analysis job or imported requirement is persisted. Operators should combine these limits with ingress request-body limits, provider rate limits and normal database backups.
