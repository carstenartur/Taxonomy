# Bounded multi-user analysis

The existing analysis registry now distinguishes an accepted waiting operation
(`QUEUED`) from a worker that owns an execution permit (`RUNNING`). Full synchronous,
streaming and portfolio analyses that enter this registry share its process-local
capacity. A queued cancellation releases its waiting place immediately and prevents
a late worker from sending a provider request. User, workspace, repository and branch
checks still apply to observation, details and cancellation.

## Operator configuration

| Property | Default | Purpose |
|---|---:|---|
| `taxonomy.analysis.max-concurrent-jobs` | 4 | Active full-analysis permits, 1–64 |
| `taxonomy.analysis.queue-capacity` | 16 | Live waiting places, 1–10000 |
| `taxonomy.analysis.max-concurrent-jobs-per-user` | min(2, global) | Active permits per username |
| `taxonomy.analysis.queue-capacity-per-user` | min(8, global queue) | Live waiting places per username |
| `taxonomy.analysis.maximum-queue-wait-seconds` | 1800 | Maximum live-admission wait, 1–86400 |
| `taxonomy.llm.max-concurrent-requests` | 4 | Simultaneous HTTP exchanges per provider, 1–64 |
| `taxonomy.llm.request-queue-capacity` | 64 | Waiting HTTP attempts per provider, 1–10000 |
| `taxonomy.llm.maximum-queue-wait-seconds` | 120 | Maximum provider-admission wait, 1–86400 |

Owner limits cannot exceed their corresponding global limits. Invalid startup
limits fail validation. Provider-specific overrides use
`taxonomy.llm.providers.<lowercase-provider>.<property-suffix>`, for example
`taxonomy.llm.providers.custom_openai.max-concurrent-requests=2`.

The optional Spring profile `multiuser-analysis` raises the existing portfolio
executor default from one to four workers and supplies the limits above. Add it
to the deployment's existing storage/security profiles; do not replace them:

```text
SPRING_PROFILES_ACTIVE=hsqldb-file,multiuser-analysis
TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY=4
TAXONOMY_ANALYSIS_MAX_CONCURRENT_JOBS=4
TAXONOMY_ANALYSIS_MAX_CONCURRENT_JOBS_PER_USER=2
```

Without this profile or an explicit portfolio-worker override, existing deployments
retain one portfolio worker. Increasing Copilot coordinators alone does not increase
analysis parallelism. Available worker threads and provider limits may reduce actual
parallelism below the configured full-analysis maximum.

## Provider calls and waiting

Every HTTP attempt in the Gemini and OpenAI-compatible gateways, including retries,
passes through the provider limiter. Interactive node/justification and reformulation
calls using these gateways therefore share the HTTP limit even where they do not
enter the full-analysis registry. Replay consumes no HTTP permit. Each gateway has
its own limit; aliases or other applications sharing an upstream API quota are not
automatically aggregated.

Existing RPM preferences remain: `llm.rpm` for Gemini and
`llm.rpm.<lowercase-provider>` for the other gateways. These are operator settings,
not statements about the account's provider entitlement. RPM is separate from
concurrency. This change does not add a token-per-minute or daily-token limiter;
existing prompt budgets and upstream token quotas still apply.

Transient HTTP 429 responses have a bounded retry budget. `Retry-After` seconds
and HTTP dates are respected and shared across that provider's local callers.
Automatic waits longer than 60 seconds are not attempted, but the longer provider
cooldown is retained; it is never shortened to permit an early retry. Known permanent
billing/quota error codes are not automatically retried. Provider admission also
has its own deadline when no full-analysis observer is installed. In-flight blocking
HTTP may still take until its transport timeout to finish after cancellation.

The progress view distinguishes waiting for an analysis slot, provider capacity,
provider rate limits, a retry and a provider response. It shows queue time separately
from execution time. No guessed queue rank, estimated start time, foreign username
or foreign requirement title is exposed. The existing overall analysis deadline
still includes queue time; separate timing fields do not extend that deadline.

## Deliberate scope

This is a bounded extension of existing execution paths, not a new durable job
system. Portfolio jobs retain their existing persisted `PENDING` state, dispatch
queue, 202 API and claim/recovery rules. The legacy direct POST remains synchronous;
its live reservation is not a new restart-safe 202 job. SSE remains connection-bound.
The live registry cannot replace the existing durable portfolio job view.

Ready workers are selected round-robin between eligible usernames; tasks still in
an existing executor's FIFO are not yet ready and are outside that fairness policy.
The per-user live backlog limit does not bound the number of persisted portfolio
jobs. A single large batch still processes its items sequentially. Do not describe
these rules as a globally fair persistent dispatcher.

**Use one application/analysis-executing instance for deployment-wide limits.**
Admission, live status and HTTP quotas are process-local, not shared leases. Multiple
pods would multiply capacity and may route live status to the wrong instance.
A shared database alone does not turn these limits into cluster-wide limits.
The profile documents this boundary; it does not detect or prohibit extra pods.

See [verification evidence](../testing/multiuser-analysis-admission.md) and
[existing portfolio operations](PROJECT_PORTFOLIO_OPERATIONS.md).
