# Project portfolio operational limits and recovery

The project portfolio applies explicit server-side limits before persisting or starting expensive work.

| Property | Environment variable | Default | Purpose |
|---|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `100` | Maximum requirements accepted by one analysis job |
| `taxonomy.portfolio.max-import-requirements` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `100` | Maximum requirement candidates accepted by one import request |
| `taxonomy.portfolio.max-import-characters` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `500000` | Maximum combined requirement and original-source characters in one import |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `900` | Age after which a `RUNNING` item can be recovered |
| `taxonomy.portfolio.analysis-worker-concurrency` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY` | `1` | Fixed number of concurrently dispatched analysis jobs |
| `taxonomy.portfolio.analysis-worker-queue-capacity` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY` | `100` | Persisted jobs that may wait in the in-process dispatch queue |
| `taxonomy.portfolio.analysis-worker-shutdown-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS` | `30` | Graceful shutdown period for active workers |

The default concurrency of one is deliberately conservative because one requirement analysis can issue several provider calls while traversing the taxonomy. Increase it only together with provider rate limits, database capacity and an observed latency/error budget. Unlike a core/max pool with a large queue, this fixed value is the actual steady-state concurrency.

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

## Verified scale contract

The 100, 1,000 and 10,000 sizes deliberately cover different risks. They must not be interpreted as identical load tests.

| Size | Evidence | Enforced statement |
|---:|---|---|
| 100 requirements | `ProjectRequirementListQueryCountTest` creates a real project with 100 requirements and versions through the public service | Listing all requirements with their current versions uses at most three SQL statements; the query count does not grow with the requirement count |
| 1,000 requirements | `ProjectRequirementThousandScaleContractTest` persists 1,000 real requirements and immutable versions | The read path, measured separately from fixture creation, uses at most three SQL statements and must complete within 30 seconds on the CI in-memory database |
| 10,000 requirements | `ProjectPortfolioViewCountTest` simulates the aggregate counts of a large project | A project summary never loads complete requirement, solution or conflict collections and uses scalar database counts only |

The 10,000-case evidence is a **controlled summary boundary contract**, not a claim that an end-to-end request fully materializes 10,000 requirements at a particular throughput. Requirement-list transfer and object materialization remain proportional to the result size: the SQL statement count is constant, while response size and memory consumption remain `O(n)`.

The default limit of 100 requirements applies to one **analysis job**. Projects may contain more requirements; large portfolios are analysed through multiple jobs. The 1,000- and 10,000-case contracts therefore do not override provider budgets or batch limits.

A production capacity commitment additionally requires environment-specific measurements with the deployed PostgreSQL version, representative text and snapshot sizes, network latency, heap limits and concurrent users. Repository tests secure the algorithmic and query-level lower bound; they do not promise one universal response time for every infrastructure configuration.

## Schema migration

The portfolio schema is managed by the versioned Flyway migration stream under `db/migration/taxonomy/postgresql/`.

| Script | Purpose |
|---|---|
| `V1__taxonomy_application_baseline.sql` | Core taxonomy tables (nodes, relations, workspaces, …) |
| `V2__project_portfolio.sql` | Portfolio tables (projects, requirements, versions, analysis jobs, solutions, products, conflicts, …) |

All `CREATE TABLE` and `CREATE INDEX` statements use `IF NOT EXISTS` guards so that the migration is safe to apply on installations that previously used `hibernate.ddl-auto=update`.

### Fresh install (Kubernetes / Helm)

1. Deploy the new image with `SPRING_PROFILES_ACTIVE=postgres,kubernetes` and `TAXONOMY_DDL_AUTO=validate`.
2. Flyway runs automatically on startup and applies `V1` and `V2` in order.
3. Hibernate validates the schema produced by Flyway; startup fails fast if any column or constraint is missing.

### Upgrade from a pre-portfolio release

1. Ensure a database backup exists before starting.
2. Deploy the new image. Flyway detects that `V1` is already applied and applies only `V2`.
3. The application starts with `ddl-auto=validate`; existing data in the baseline tables is unaffected.
4. Create a test project, add a requirement, start an analysis job and verify the response is `202 Accepted`.

### Rollback

Flyway does not automatically reverse an applied migration. To roll back:

1. Stop the application.
2. Run the reverse DDL statements from `V2__project_portfolio.sql` (drop the portfolio tables in reverse dependency order: `project_conflict`, `solution_product`, `product_taxonomy`, `solution_taxonomy`, `req_solution_link`, `project_solution`, `req_relation_mapping`, `req_element_mapping`, `req_analysis_snapshot`, `req_analysis_item`, `req_analysis_job`, `project_req_version`, `project_requirement`, `product_catalog`, `solution_definition`, `arch_project`).
3. Remove the `flyway_schema_history` row for V2.
4. Deploy the previous image. It starts without portfolio tables and without the `V2` migration record.

### Restart / pod recovery

If a pod stops while analysis items are `RUNNING`, those items remain in `RUNNING` state with their original `claimedAt` timestamp. After the configured `analysis-claim-timeout-seconds` (default: 900 s), the next `retry-failed` call (or the next analysis dispatch) will find them stale and reset them to `PENDING`. No manual database intervention is required.

To force immediate recovery after a planned maintenance window shorter than the lease timeout, call:

```bash
curl -u alice:password -X POST \
  /api/projects/{projectId}/analysis-jobs/{jobId}/retry-failed
```

### Backup

Include the following in your database backup:

- All portfolio tables (prefix `arch_project`, `project_*`, `req_*`, `solution_*`, `product_*`)
- The `flyway_schema_history` table
- The JGit/DSL repository storage path (`TAXONOMY_SEARCH_DIRECTORY_ROOT` / `/app/data`)

Portfolio snapshots reference requirement versions by ID; both must be present in the same backup for a consistent restore.
