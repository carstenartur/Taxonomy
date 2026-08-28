# Taxonomy Architecture Analyzer — Configuration Reference

This document is the canonical inventory of deployment-facing environment variables recognised by the Taxonomy application. It is generated conceptually from the effective Spring property files, direct `@Value` bindings, feature flags and `@ConfigurationProperties` classes; a contract test keeps this table aligned with those sources.

Environment variables have higher precedence than `application*.properties`. Standard Spring variables such as `SPRING_DATASOURCE_URL` therefore override the same property populated through a Taxonomy-specific placeholder such as `TAXONOMY_DATASOURCE_URL`. Do not set both aliases for one deployment.

The values shown below are application defaults. Profile-specific files may deliberately override them. In particular:

| Active profile | Important effective defaults |
|---|---|
| default / `hsqldb` | in-memory HSQLDB, `ddl-auto=create`, heap Lucene, synchronous indexing, SpringDoc enabled |
| `production` | `ddl-auto=update`, filesystem Lucene, `write-sync`, audit logging and first-login password change enabled, SpringDoc disabled |
| `kubernetes` | `ddl-auto=validate`, heap Lucene by default, `write-sync`, audit logging enabled, SpringDoc disabled |
| `keycloak` | local account management and local password changes disabled; direct Word links disabled |

Several preferences (`taxonomy.llm.*`, `taxonomy.analysis.min-score`, `taxonomy.dsl.*`, `taxonomy.limits.max-*`, `taxonomy.diagram.policy` and the request-rate limit) are committed into the repository-backed preferences store when it is first initialised. After that point, a value changed through the administration UI/API can supersede a changed process environment until the stored preference is changed again.

`DOMAIN`, `JAVA_OPTS`, OpenTelemetry agent variables and other Docker/Helm wrapper settings are not Taxonomy application variables. They remain documented in the corresponding deployment guide. Secrets must be supplied through the platform secret store and must never be committed.

## Startup, profiles, catalogue and lifecycle

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `PORT` | `server.port` | `8080` | HTTP listener port. |
| `SPRING_PROFILES_ACTIVE` | Spring profile selection | default profile `hsqldb` | Comma-separated profiles, for example `production,postgres` or `postgres,kubernetes`. |
| `TAXONOMY_INIT_ASYNC` | `taxonomy.init.async` | `false` | Opens the HTTP port before loading the catalogue. Useful on PaaS platforms; readiness remains false until loading completes. |
| `TAXONOMY_INIT_RELOAD_EXISTING` | `taxonomy.init.reload-existing` | `false` | Destructively reloads the configured catalogue even when persisted nodes already exist. Use only after a restorable backup. |
| `TAXONOMY_LAZY_INIT` | `spring.main.lazy-initialization` | `true` | Defers most Spring beans. `TaxonomyService` remains eager so catalogue readiness is authoritative. |
| `TAXONOMY_THYMELEAF_CACHE` | `spring.thymeleaf.cache` | `true` | Caches compiled templates; normally disable only during local UI development. |
| `TAXONOMY_SPRINGDOC_ENABLED` | SpringDoc API and UI switches | base `true`; production/Kubernetes `false` | Creates or suppresses `/v3/api-docs` and Swagger UI. This is independent of the authorization switch below. |
| `TAXONOMY_FORWARD_HEADERS_STRATEGY` | `server.forward-headers-strategy` in Kubernetes profile | `framework` | Controls interpretation of trusted ingress `Forwarded`/`X-Forwarded-*` headers. |
| `TAXONOMY_SHUTDOWN_TIMEOUT` | `spring.lifecycle.timeout-per-shutdown-phase` in Kubernetes profile | `30s` | Maximum graceful shutdown phase duration. |
| `TAXONOMY_LOG_FILE` | `logging.file.name` in Kubernetes profile | empty | Optional log file. Keep empty with a read-only container filesystem and collect stdout instead. |
| `TAXONOMY_CATALOGUE_RESOURCE` | `taxonomy.catalogue.resource` | bundled `C3_Taxonomy_Catalogue_25AUG2025.xlsx` | Excel baseline used for catalogue loading and report provenance. |
| `TAXONOMY_CATALOGUE_OVERLAY_ENABLED` | `taxonomy.catalogue.overlay.enabled` | `true` | Applies the versioned, fail-closed JSON overlay to the Excel baseline and reconciles persisted rows idempotently. |
| `TAXONOMY_CATALOGUE_OVERLAY_RESOURCE` | `taxonomy.catalogue.overlay-resource` | `classpath:data/nato-taxonomy.json` | Overlay resource containing explicit parent corrections, product roles, secondary classifications and review metadata. |
| `TAXONOMY_REPORT_TIME_ZONE` | `taxonomy.report.time-zone` | `Europe/Berlin` | Zone identifier used when rendering decision-report timestamps. |
| `GIT_COMMIT` | `git.commit.id` | unset | Preferred build/source commit recorded in decision-report provenance. |
| `GITHUB_SHA` | fallback for `git.commit.id` | `unknown` | Used only when `GIT_COMMIT` is absent. |
| `TAXONOMY_SCHEMA_MIGRATION_ENABLED` | `taxonomy.schema-migration.enabled` | `true` | Runs idempotent portable schema-contract migrations. Disable only for controlled diagnostics. |
| `TAXONOMY_COMMIT_INDEX_SEARCH_REBUILD_EMPTY` | `taxonomy.commit-index.search-rebuild-empty` | `true` | Rebuilds/purges the commit search index when the relational projection is empty. |
| `TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION` | `taxonomy.jgit-storage.legacy-adoption` | `false` | One-start opt-in for the fail-closed legacy JGit schema adoption path. Requires backup and preflight; reset to `false` afterwards. |
| `TAXONOMY_GIT_BOOTSTRAP` | `taxonomy.git.bootstrap` | `true` | Creates the initial `draft` commit after catalogue readiness when the system repository is empty. |
| `TAXONOMY_FEATURES_MULTI_REPOSITORY_API_ENABLED` | `taxonomy.features.multi-repository-api.enabled` | `false` | Enables the currently opt-in `/api/repositories` management surface. It does not weaken repository membership checks. |

## Database and Hibernate Search

`TAXONOMY_DATASOURCE_URL` is the placeholder used by the supplied database profiles. `SPRING_DATASOURCE_URL` is Spring Boot's direct binding for the same `spring.datasource.url` property and has higher precedence. Choose one. The HSQLDB-specific pool variables below do not currently alter the fixed pool settings in the PostgreSQL, MSSQL or Oracle profile.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_DATASOURCE_URL` | `spring.datasource.url` through supplied DB profiles | profile-dependent | Taxonomy alias for the JDBC URL. |
| `SPRING_DATASOURCE_URL` | direct Spring binding to `spring.datasource.url` | unset | JDBC URL used by the Helm chart; overrides the Taxonomy alias when both are present. |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | HSQLDB `sa`; PostgreSQL/Oracle `taxonomy`; MSSQL `sa` | Database account. Always use a secret-backed production value. |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | profile development defaults | Database password. Production must use a secret. |
| `TAXONOMY_DB_MIN_IDLE` | HSQLDB Hikari `minimum-idle` | `1` | Minimum idle connections for the HSQLDB profile only. |
| `TAXONOMY_DB_MAX_POOL_SIZE` | HSQLDB Hikari `maximum-pool-size` | `4` | Maximum HSQLDB pool size. |
| `TAXONOMY_DB_CONNECTION_TIMEOUT_MS` | HSQLDB Hikari connection timeout | `30000` | HSQLDB connection-acquisition timeout in milliseconds. |
| `TAXONOMY_DDL_AUTO` | `spring.jpa.hibernate.ddl-auto` | base `create`; production `update`; Kubernetes `validate` | Hibernate schema action. Never use `create` against persistent data. PostgreSQL/Flyway deployments should normally use `validate`. |
| `TAXONOMY_SEARCH_DIRECTORY_TYPE` | Hibernate Search Lucene directory type | base/Kubernetes `local-heap`; production `local-filesystem` | Selects in-memory or filesystem Lucene storage. Multi-replica coordination is not provided by these local modes. |
| `TAXONOMY_SEARCH_DIRECTORY_ROOT` | Hibernate Search directory root | `/app/data/lucene-index` | Root used by `local-filesystem`; ignored by `local-heap`. |
| `TAXONOMY_SEARCH_SYNC_STRATEGY` | Hibernate Search indexing-plan synchronization | base `sync`; production/Kubernetes `write-sync` | `sync` waits for reader refresh; `write-sync` waits for writes but improves throughput. |

## Generative LLM providers and analysis preferences

A full Copilot run needs a configured generative provider. `LOCAL_ONNX` supplies limited local semantic scoring only; it is not a generative chat model. When `LLM_PROVIDER` is empty, complete providers are detected in this order: Gemini, OpenAI, DeepSeek, Qwen, Llama, Mistral, then `CUSTOM_OPENAI`.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `LLM_PROVIDER` | `llm.provider` | empty / auto-detect | `GEMINI`, `OPENAI`, `DEEPSEEK`, `QWEN`, `LLAMA`, `MISTRAL`, `CUSTOM_OPENAI` or `LOCAL_ONNX`. |
| `LLM_MOCK` | `llm.mock` | `false` | Uses deterministic fixture analysis. Intended for CI, screenshots and offline tests, not real architecture decisions. |
| `GEMINI_API_KEY` | `gemini.api.key` | empty | Gemini credential. |
| `OPENAI_API_KEY` | `openai.api.key` | empty | OpenAI credential. |
| `DEEPSEEK_API_KEY` | `deepseek.api.key` | empty | DeepSeek credential. |
| `DASHSCOPE_API_KEY` | `qwen.api.key` | empty | Alibaba DashScope/Qwen credential. |
| `LLAMA_API_KEY` | `llama.api.key` | empty | Llama API credential. |
| `MISTRAL_API_KEY` | `mistral.api.key` | empty | Mistral credential. |
| `CUSTOM_LLM_URL` | `custom.llm.url` | empty | Full HTTP(S) OpenAI-compatible Chat Completions URL. It must contain a host, no embedded credentials, and end in `/chat/completions`. |
| `CUSTOM_LLM_MODEL` | `custom.llm.model` | empty | Model identifier sent unchanged to the custom endpoint; required with `CUSTOM_OPENAI`. |
| `CUSTOM_LLM_API_KEY` | `custom.llm.api.key` | empty | Optional bearer token for the custom endpoint. Empty means no `Authorization` header. |
| `TAXONOMY_LLM_RPM` | repository-backed preference `taxonomy.llm.rpm` | `5` | Outbound per-provider request budget per minute. |
| `TAXONOMY_LLM_TIMEOUT_SECONDS` | repository-backed preference `taxonomy.llm.timeout-seconds` | `30` | HTTP timeout for an individual LLM call. |
| `TAXONOMY_ANALYSIS_MIN_SCORE` | repository-backed preference `taxonomy.analysis.min-score` | `70` | Minimum 0–100 relevance used by ordinary architecture-view selection. |
| `TAXONOMY_ANALYSIS_PRODUCT_BATCH_SIZE` | `taxonomy.analysis.product.batch-size` | `10` | Concrete Information Products in one independent suitability request (1–10); values are sorted deterministically before batching. Higher values fail startup closed. |
| `TAXONOMY_ANALYSIS_PRODUCT_MIN_SCORE` | `taxonomy.analysis.product.min-score` | `50` | Independent 0–100 suitability threshold for concrete products. Lower values become explicit zeroes and can create a structured product-coverage gap. |
| `TAXONOMY_RATE_LIMIT_PER_MINUTE` | repository-backed preference `taxonomy.rate-limit.per-minute` | `10` | Admitted LLM requests per stable authenticated identity and minute; exactly `0` disables, negative values fail closed to `1`. |

The incoming quota runs after authorization. Local users are keyed by canonical username; Keycloak browser and bearer access use the immutable `iss`/`sub` pair and therefore share one budget even when `preferred_username` changes. Forwarding headers and peer addresses are not quota identities. Rejected requests do not allocate state. The bounded in-memory counters expire after inactivity and return HTTP `429` with `Retry-After` and `Cache-Control: no-store`. They are scoped to one application instance, so multi-replica deployments require an outer distributed quota if a cluster-wide budget is required. The same matching contract applies at the root context and below a prefix such as `/taxonomy`.

## LLM record/replay tooling

These switches are for deterministic test evidence. A production process should normally leave all of them disabled.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `LLM_RECORD` | `llm.record` | `false` | Records prompts and raw live responses. |
| `LLM_REPLAY` | `llm.replay` | `false` | Replays a response whose prompt hash is already recorded. |
| `LLM_REPLAY_FALLBACK` | `llm.replay.fallback` | `error` | `live` permits a missing recording to call the real provider and record it; any other value remains fail-closed. |
| `LLM_PRUNE` | `llm.prune` | `false` | Marks manifest entries not replayed in the current JVM as stale. |
| `LLM_PRUNE_DELETE` | `llm.prune.delete` | `false` | Deletes stale recording files when pruning runs. |
| `LLM_RECORDINGS_DIR` | `llm.recordings.dir` | auto-detected test resource directory | Explicit recording directory. Its manifest is mutable; do not point production at a committed source tree. |

## Local embeddings and vector indexing

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_EMBEDDING_ENABLED` | `embedding.enabled` | `false` | Enables local embedding inference and semantic search. Independent of the selected chat provider. |
| `TAXONOMY_EMBEDDING_MODEL_DIR` | `embedding.model.dir` | empty | Mounted, pre-downloaded model directory. Preferred for offline and Kubernetes deployments. |
| `TAXONOMY_EMBEDDING_MODEL_NAME` | `embedding.model.name` | BAAI `bge-small-en-v1.5` Hugging Face URL | Remote model reference or local model path used when no directory is supplied. |
| `TAXONOMY_EMBEDDING_QUERY_PREFIX` | `embedding.query.prefix` | BGE retrieval prefix | Text prepended to queries for asymmetric retrieval. Set empty only for a model that does not require it. |
| `TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD` | `embedding.allow-download` | `false` | Explicitly permits runtime model download. Also requires suitable network policy/egress. |
| `TAXONOMY_EMBEDDING_INDEX_LOADER_THREADS` | `embedding.index.loader-threads` | `2`, clamped to at least `1` | Object-loader threads for each bounded mass-indexing phase. More threads also mean more simultaneous local inference. |
| `TAXONOMY_EMBEDDING_INDEX_BATCH_SIZE` | `embedding.index.batch-size` | `16`, clamped to at least `1` | Number of entities loaded per indexing batch. |

## Copilot and Autopilot

The historical variable name `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` is retained unchanged. Its property is `taxonomy.ai.max-architecture-nodes`, and it applies to both the manual Copilot and Autopilot. A manual request cannot exceed this operator ceiling. The portfolio analysis service also enforces `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES`, so the effective ceiling is the lower of the two; keep the AI limit less than or equal to the general limit.

Profiles accept `STANDARD`, `FULL` and `EXHAUSTIVE`. Verification-pass counts must be between 1 and 3; `EXHAUSTIVE` always performs at least two passes. Passes inside one operation run sequentially. Coordinator concurrency controls different operations, not passes within one operation.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_AI_COST_POLICY` | `taxonomy.ai.cost-policy` | `METERED` | Operator declaration. Unattended Autopilot requires `UNMETERED`; manual Copilot works with a configured metered provider. |
| `TAXONOMY_AI_COPILOT_PROFILE` | `taxonomy.ai.copilot.profile` | `FULL` | Default profile for manually initiated runs. |
| `TAXONOMY_AI_AUTOPILOT_PROFILE` | `taxonomy.ai.autopilot.profile` | `EXHAUSTIVE` | Default profile for unattended runs. |
| `TAXONOMY_AI_COPILOT_VERIFICATION_PASSES` | `taxonomy.ai.copilot.verification-passes` | `1` | Manual default, constrained to 1–3 and adjusted upward when required by the profile. |
| `TAXONOMY_AI_AUTOPILOT_VERIFICATION_PASSES` | `taxonomy.ai.autopilot.verification-passes` | `2` | Autopilot default, constrained to 1–3 and at least 2 for `EXHAUSTIVE`. |
| `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` | `taxonomy.ai.max-architecture-nodes` | `50`, minimum `1` | Operator ceiling for nodes in architecture views created by manual Copilot and Autopilot. Higher values increase processing, snapshot and diagram size; they do not increase project requirement batches. |
| `TAXONOMY_AI_AUTOPILOT_ENABLED` | `taxonomy.ai.autopilot.enabled` | `false` | Explicit opt-in to unattended execution. It is insufficient without the cost policy and provider settings. |
| `TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE` | `taxonomy.ai.autopilot.on-requirement-save` | `true` | Starts Autopilot after saving a new immutable requirement version, but only when Autopilot is otherwise ready. |
| `TAXONOMY_AI_AUTOPILOT_PROVIDER` | `taxonomy.ai.autopilot.provider` | empty | Explicit provider for unattended work. It must be configured and is never inferred to be unmetered. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_SOLUTIONS` | `taxonomy.ai.autopilot.propose-solutions` | `true` | Creates deterministic `PROPOSED` solution links for non-`STANDARD` Autopilot profiles. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_PRODUCTS` | `taxonomy.ai.autopilot.propose-products` | `true` | Creates `CANDIDATE` product proposals for non-`STANDARD` Autopilot profiles. |
| `TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS` | `taxonomy.ai.autopilot.max-project-requirements` | `50`; valid 1–500 | Maximum explicit project-level Autopilot batch. Oversized selections are rejected, never silently truncated. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_COVERAGE` | `taxonomy.ai.product-proposals.minimum-coverage` | `25`, clamped to 0–100 | Minimum overlapping confirmed catalogue coverage for deterministic product proposals. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_CONFIDENCE` | `taxonomy.ai.product-proposals.minimum-confidence` | `0.25`, clamped to 0–1 | Minimum fraction of a solution's confirmed nodes covered by a candidate product. |
| `TAXONOMY_AI_MAXIMUM_RUNTIME_SECONDS` | `taxonomy.ai.maximum-runtime-seconds` | `1800`, minimum effective `60` | How long the coordinator waits for a pass. Persisted jobs remain recoverable after the wait ends. |
| `TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS` | `taxonomy.ai.coordinator.max-concurrent-operations` | `4`; valid 1–64 | Number of Copilot/Autopilot operations coordinated in parallel. Does not parallelise one operation's verification passes. |
| `TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY` | `taxonomy.ai.coordinator.queue-capacity` | `100`; valid 1–10000 | In-memory coordinator queue; rejected operations remain persisted and can be resumed. |

Effective policy and readiness can be inspected at `GET /api/ai-automation`. Generated mappings, responsibilities, products, procurement decisions and branch merges still require human approval.

## Portfolio, imports, workers and working state

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_ANALYSIS_DRAFT_MAX_CHARACTERS` | `taxonomy.analysis-draft.max-characters` | `2000000`; minimum effective `10000` | Maximum serialized JSON characters in one workspace/repository/branch-scoped analysis draft. |
| `TAXONOMY_CONTEXT_MAX_HISTORY` | `taxonomy.context.max-history` | `50` | In-memory navigation-history entries retained for each active workspace state. |
| `TAXONOMY_PORTFOLIO_MAX_IMPORT_REQUIREMENTS` | `taxonomy.portfolio.max-import-requirements` | `100`, minimum `1` | Maximum requirements accepted by one reviewed import. |
| `TAXONOMY_PORTFOLIO_MAX_IMPORT_CHARACTERS` | `taxonomy.portfolio.max-import-characters` | `500000`, minimum `1` | Maximum total text characters in one reviewed import. |
| `TAXONOMY_PORTFOLIO_MAX_ANALYSIS_BATCH` | `taxonomy.portfolio.max-analysis-batch` | `100`, minimum `1` | Maximum requirements in one persisted portfolio analysis job. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_CLAIM_TIMEOUT_SECONDS` | `taxonomy.portfolio.analysis-claim-timeout-seconds` | `900`, minimum effective `60` | Age after which an unfinished item claim can be recovered/retried. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY` | `taxonomy.portfolio.analysis-worker-concurrency` | `1`, minimum `1` | Parallel portfolio analysis worker threads. Provider quotas usually limit safe growth. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY` | `taxonomy.portfolio.analysis-worker-queue-capacity` | `100`, minimum effective `0` | In-memory dispatch queue. Persisted jobs survive rejection and can be resubmitted. |
| `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS` | `taxonomy.portfolio.analysis-worker-shutdown-seconds` | `30`, minimum effective `0` | Grace period for worker termination. |
| `TAXONOMY_PORTFOLIO_SNAPSHOT_STALE_AFTER_DAYS` | `taxonomy.portfolio.snapshot-stale-after-days` | `30`, minimum effective `1` | Age threshold used by portfolio stale-snapshot metrics; a snapshot for an old requirement version is stale regardless of age. |

## Local security, administration and Keycloak

`ADMIN_PASSWORD` and `TAXONOMY_ADMIN_PASSWORD` are deliberately separate. The former is an additional token for sensitive Actuator/admin-token checks. The latter bootstraps the local `admin` account. When local authentication starts outside the production profile without `TAXONOMY_ADMIN_PASSWORD`, the application generates and logs a one-time random bootstrap password and requires it to be changed. The production profile fails startup unless the configured password is non-placeholder and at least 16 characters.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `ADMIN_PASSWORD` | `admin.token` | empty | Optional `X-Admin-Token`/Bearer value for sensitive Actuator and legacy admin-token checks. Empty disables this extra token layer; it does not replace role-based login authorization. |
| `TAXONOMY_ADMIN_PASSWORD` | `taxonomy.admin-password` | empty outside production; required in production | Initial local administrator credential. Empty non-production starts use a one-time random bootstrap password. |
| `TAXONOMY_LOGIN_RATE_LIMIT` | `taxonomy.security.login-rate-limit.enabled` | `true` | Enables failed-login rate limiting by client address. |
| `TAXONOMY_LOGIN_MAX_ATTEMPTS` | `taxonomy.security.login-rate-limit.max-attempts` | `5` | Failed attempts allowed before lockout. |
| `TAXONOMY_LOGIN_LOCKOUT_SECONDS` | `taxonomy.security.login-rate-limit.lockout-seconds` | `300` | Lockout duration. |
| `TAXONOMY_REQUIRE_PASSWORD_CHANGE` | `taxonomy.security.require-password-change` | base `false`; production `true` | Marks new/reset local passwords as temporary and enforces the change page. |
| `TAXONOMY_SWAGGER_PUBLIC` | `taxonomy.security.swagger-public` | base `true`; production/Kubernetes `false` | When SpringDoc exists, controls whether its UI/API are public or require authentication. |
| `TAXONOMY_AUDIT_LOGGING` | `taxonomy.security.audit-logging` | base `false`; production/Kubernetes `true` | Logs authentication success/failure events. |
| `TAXONOMY_SECURITY_LOCAL_USERS_ENABLED` | `taxonomy.security.local-users-enabled` | base `true`; Keycloak `false` | Enables local user/role services. Do not override to true in Keycloak mode without a deliberate mixed-identity design. |
| `TAXONOMY_SECURITY_CHANGE_PASSWORD_ENABLED` | `taxonomy.security.change-password-enabled` | base `true`; Keycloak `false` | Enables the local password-change surface. Keycloak normally owns credentials. |
| `TAXONOMY_DIRECT_WORD_ENABLED` | `taxonomy.document-templates.direct-word-enabled` | base `true`; Keycloak `false` | Shows `ms-word:` direct-edit links. Enable only when Word can authenticate to the WebDAV endpoint. |
| `KEYCLOAK_CLIENT_ID` | OAuth2 client registration | `taxonomy-app` | OIDC browser client ID. |
| `KEYCLOAK_CLIENT_SECRET` | OAuth2 client registration | empty | Confidential-client secret; provide through a secret store. |
| `KEYCLOAK_ISSUER_URI` | OAuth2 client and resource-server issuer | local realm URI | Public issuer URI used for discovery and token validation. |
| `KEYCLOAK_JWK_SET_URI` | resource-server JWK endpoint | local realm certificates URI | Explicit key endpoint, useful when internal and public Keycloak routes differ. |
| `KEYCLOAK_ADMIN_URL` | `taxonomy.keycloak.admin-console-url` | `http://localhost:8180` | Base URL used for account-console redirects. |
| `KEYCLOAK_REALM` | `taxonomy.keycloak.realm` | `taxonomy` | Realm segment used by account-console redirects. |
| `TAXONOMY_KEYCLOAK_ROLE_CLAIM_PATH` | `taxonomy.keycloak.role-claim-path` | `realm_access.roles` | Dot-separated JWT claim path. Values are filtered to the fixed application roles `ROLE_USER`, `ROLE_ARCHITECT` and `ROLE_ADMIN`; no configurable prefix transformation exists. |

## DSL, repositories and external Git

The first six settings below initialise repository-backed preferences. The `taxonomy.dsl.remote-*` connection is the historic DSL replication mechanism. External canonical repository credentials are deployment-only secrets read at call time and are not persisted in the Taxonomy database.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_DSL_DEFAULT_BRANCH` | `taxonomy.dsl.default-branch` | `draft` | Initial branch preference for DSL work. |
| `TAXONOMY_DSL_PROJECT_NAME` | `taxonomy.dsl.project-name` | `Taxonomy Architecture` | Project name in DSL/export metadata. |
| `TAXONOMY_DSL_AUTO_SAVE_INTERVAL` | `taxonomy.dsl.auto-save-interval` | `0` | Auto-commit interval in seconds; `0` disables it. |
| `TAXONOMY_DSL_REMOTE_URL` | `taxonomy.dsl.remote-url` | empty | Optional remote used by the historic DSL replication settings. |
| `TAXONOMY_DSL_REMOTE_TOKEN` | `taxonomy.dsl.remote-token` | empty | Token for the DSL remote. Treat as a secret even though the preferences API masks it. |
| `TAXONOMY_DSL_REMOTE_PUSH_ON_COMMIT` | `taxonomy.dsl.remote-push-on-commit` | `false` | Pushes after each DSL commit when a remote is configured. |
| `TAXONOMY_EXTERNAL_GIT_USERNAME` | direct deployment credential | `oauth2` | Username supplied to the administrator-configured canonical external repository. |
| `TAXONOMY_EXTERNAL_GIT_TOKEN` | direct deployment credential | empty | Write-only token for fetch/push; never persist or log it. |

## Input, architecture, export and document limits

The general architecture limit below applies to all persisted portfolio analyses. The Copilot/Autopilot-specific limit cannot effectively exceed it. Byte values use binary multiples.

| Variable | Spring property / scope | Default | Meaning |
|---|---|---|---|
| `TAXONOMY_LIMITS_MAX_BUSINESS_TEXT` | repository-backed preference `taxonomy.limits.max-business-text` | `5000` characters | Maximum ordinary business-requirement input length. |
| `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES` | repository-backed preference and portfolio operator limit `taxonomy.limits.max-architecture-nodes` | `50` | General maximum architecture-view node count and upper bound accepted by portfolio analysis requests. |
| `TAXONOMY_LIMITS_MAX_EXPORT_NODES` | repository-backed preference `taxonomy.limits.max-export-nodes` | `200` | Maximum node count in bounded exports. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_UPLOAD_BYTES` | `taxonomy.limits.document.max-upload-bytes` | `52428800` (50 MiB) | Maximum uploaded document size. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_PDF_PAGES` | `taxonomy.limits.document.max-pdf-pages` | `500` | Maximum PDF pages processed. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_EXTRACTED_CHARACTERS` | `taxonomy.limits.document.max-extracted-characters` | `1000000` | Maximum characters retained after extraction. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_CANDIDATES` | `taxonomy.limits.document.max-candidates` | `2000` | Maximum provenance/candidate records produced from one document. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_LLM_CHARACTERS` | `taxonomy.limits.document.max-llm-characters` | `200000` | Maximum extracted characters sent to the LLM stage. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_ENTRY_BYTES` | `taxonomy.limits.document.max-docx-entry-bytes` | `67108864` (64 MiB) | Maximum expanded size of one DOCX ZIP entry. |
| `TAXONOMY_LIMITS_DOCUMENT_MAX_DOCX_TEXT_BYTES` | `taxonomy.limits.document.max-docx-text-bytes` | `134217728` (128 MiB) | Maximum aggregate expanded DOCX text/XML bytes. |
| `TAXONOMY_LIMITS_DOCUMENT_MIN_DOCX_INFLATE_RATIO` | `taxonomy.limits.document.min-docx-inflate-ratio` | `0.01` | Minimum compressed-to-expanded ratio accepted by the DOCX zip-bomb guard. |
| `TAXONOMY_DIAGRAM_POLICY` | repository-backed preference `taxonomy.diagram.policy` | `defaultImpact` | Diagram selection policy: `defaultImpact`, `leafOnly`, `clustering` or `trace`. |

## Deployment examples

### Manual Copilot with a cloud provider

```bash
LLM_PROVIDER=GEMINI
GEMINI_API_KEY=secret
TAXONOMY_AI_COPILOT_PROFILE=FULL
TAXONOMY_AI_COPILOT_VERIFICATION_PASSES=1
TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50
TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES=50
```

### Unattended Autopilot with an explicitly unmetered custom endpoint

```bash
LLM_PROVIDER=CUSTOM_OPENAI
CUSTOM_LLM_URL=http://llm-server:8000/v1/chat/completions
CUSTOM_LLM_MODEL=architecture-model
TAXONOMY_AI_COST_POLICY=UNMETERED
TAXONOMY_AI_AUTOPILOT_ENABLED=true
TAXONOMY_AI_AUTOPILOT_PROVIDER=CUSTOM_OPENAI
```

For Docker Compose, copy `.env.example` to `.env`; the production Compose service forwards that file into the application container. For Helm, put non-secret values under `config`, credentials in the referenced Secret, and use `extraEnv` only for settings not promoted into the chart's default values.
