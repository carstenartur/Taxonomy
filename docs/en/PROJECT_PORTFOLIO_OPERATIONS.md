# Project portfolio operational limits and recovery

The project portfolio applies explicit server-side limits before persisting or starting expensive work.

| Property | Environment variable | Default | Purpose |
|---|---|---:|---|
| `taxonomy.portfolio.max-analysis-batch` | `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `100` | Maximum requirements accepted by one analysis job |
| `taxonomy.portfolio.max-import-requirements` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `100` | Maximum requirement candidates accepted by one import request |
| `taxonomy.portfolio.max-import-characters` | `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `500000` | Maximum combined requirement and original-source characters in one import |
| `taxonomy.portfolio.analysis-claim-timeout-seconds` | `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `900` | Age after which a `RUNNING` item can be recovered |

An analysis item is claimed atomically from `PENDING` to `RUNNING` in a dedicated short transaction. The external LLM request starts only after that transaction commits.

Retry requests reset failed items and only those running items whose claim is older than the configured timeout. The reset itself uses status- and timestamp-guarded compare-and-set updates, so concurrent retry requests cannot recover the same item twice or increment its attempt counter twice.

A low timeout can duplicate work when a provider legitimately needs longer than the timeout. Set it above the longest expected provider call plus deterministic post-processing time. Active workers are not interrupted by a retry request.

Oversized analysis or import requests fail before an analysis job or imported requirement is persisted. Operators should combine these limits with ingress request-body limits, provider rate limits and normal database backups.
