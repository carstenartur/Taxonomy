# Taxonomy 1.4.1

These are the unpublished source-line notes for Taxonomy 1.4.1, not evidence of a completed release. The source combines the stabilization prepared for the unpublished 1.3.1 line with a substantially stronger requirements and architecture workbench, an authoritative recoverable Copilot session, a versioned Information Product catalogue overlay, bounded concrete-product analysis, versioned Word-template administration, deterministic architecture exports, local semantic-search readiness, constrained-cluster deployment profiles, bounded authentication controls, and a fail-closed release pipeline.

## Important release-line note

Publication status checked on **8 October 2026**: the latest stable GitHub Release is **1.3.0**, published on **3 August 2026**. The `v1.4.1` tag exists, but the release transaction stopped before publication; a tag or green component build is not an installable, verified release. Earlier unpublished `v1.3.1` and `v1.4.0` tags likewise remain historical attempts. Do not deploy any of these tags as a substitute for verified release assets.

The owner has authorized **1.5.0** as an alternative next release. That requires a coherent `1.5.0-SNAPSHOT` preparation, version-aligned notes, a separately anchored release request and the normal release gates. This notes correction does not perform that transition, restart 1.4.1 or establish 1.5.0 readiness. The heading still names the existing 1.4.1 source line; the immutable tags and their historical notes remain unchanged.

Feature descriptions below retain their documented scope. A merged implementation slice, a closed foundation issue or a passing selected test must not be read as completion of its broader programme. Publication, feature acceptance and target-product compatibility are separate claims.

## Product highlights

### Bounded multi-user analysis and visible waiting

Full analyses share bounded execution and waiting capacity, including per-user
limits. Gemini and OpenAI-compatible HTTP calls have separate provider concurrency
and waiting limits. The UI distinguishes waiting for an analysis slot, provider
capacity, rate limits and a provider response, and reports queue time separately
from execution time. Cancellation removes queued work before it can call a provider.
The optional `multiuser-analysis` profile increases portfolio worker concurrency;
existing deployments otherwise retain their configured worker count.

These controls are process-local. They do not provide a cluster-wide dispatcher,
global upstream-account quota or a new durable queue for direct/SSE requests.
See [multi-user operation and limits](docs/en/MULTIUSER_ANALYSIS.md).

### Complete progress totals and resumable relationship work

The analysis fixes the node total before scoring and distinguishes directly assessed
nodes, descendants excluded by successful zero assessments, and open nodes, overall
and per taxonomy. Relationship work has its own complete source/target-taxonomy
plan, with completed, unresolved and pending tasks and the currently evaluated
source, target, relation type, direction and search step.

Call and source budgets limit new work in each execution without hiding the rest
of the plan. Continuation reuses validated saved relationship responses, preserves
earlier evidence and does not charge replay against the new provider-call budget.
Invalid answers and exhausted limits remain explicit unfinished work. Saved reports
without the new progress fields remain readable. See
[progress and recovery scope](docs/implementation/analysis-work-progress.md).

### Reviewed requirement reformulation

The workbench can prepare a reformulation offer from a frozen requirement version,
analysis snapshot and decision context. Bottom-up node reasoning and cross-taxonomy
reconciliation retain original constraints, expose open questions and preserve
answers, deferrals and manual edits. Adoption is explicit and creates a traceable
new requirement version; preparing or exporting an offer does not replace the source.
Historical revisions remain exportable in JSON, Markdown, HTML and DOCX without
new inference. See [reformulation behavior and verification](docs/testing/requirement-reformulation.md).

### Versioned planning information

The architecture editor supports bounded requirement planning profiles for a target
go-live year/date and references to standards or internal guidance. These reuse the
existing workspace revision, review, undo and history contracts. ReqIF carries the
versioned planning envelope; a standard reference is not a compliance certification,
and a planned date is not evidence that a system is operational. See
[planning profiles](docs/features/planning-profiles.md).

### Local account administration and guided installation

The opt-in `local-user-management` profile adds administrator forms for the existing
local account service, with role checks, password replacement and last-administrator
protection. Keycloak deployments continue to manage identities through their identity
provider; disabling an account does not revoke already established browser sessions.
See [local user management](docs/LOCAL_USER_MANAGEMENT.md).

Guided Rancher/Helm values and a native installation/preflight foundation reuse the
existing Spring configuration, deployment secrets and migration safeguards. Database,
authentication and custom-provider settings are validated without adding a public
first-run setup service. Native package production and target-platform acceptance
remain distinct from successful configuration validation. See
[installation scope and limits](docs/INSTALLATION_SETUP.md).

### Independent root relevance and explicit provider limits

Root assessments now retain their independent 0–100 relevance instead of converting
every positive root to 100. Child categories still distribute their parent's budget,
including a group with one child; concrete products retain their separate suitability
semantics. Saved historical results are not rewritten.

`LOCAL_ONNX` performs local embedding search and scoring. It cannot generate relation
assessments: that part of an analysis remains explicitly unassessed/partial, with
no unsupported completion call, repeated retry or inferred confirmed absence of
relationships. A generative provider is required for generated relation evidence.

### Experimental Sparx EA XMI exchange

The tool integration workflow now offers an experimental `sparx-xmi-2.1@1`
profile for reviewed file import and export. It reuses the existing durable
preview, identity, three-way conflict and semantic journal services. The semantic
subset covers packages, mapped elements, project requirements, tags and supported
connectors; layout remains excluded with an explicit loss report.

This is partial delivery of [#1075](https://github.com/carstenartur/Taxonomy/issues/1075).
Real Enterprise Architect compatibility has not been executed. OSLC AM live
synchronization and conditional publication remain unimplemented. See the
[English](docs/features/sparx-integration.md) or
[German](docs/features/sparx-integration-de.md) guide before evaluating the profile.

### Project and requirement portfolio workflows

The portfolio workbench supports a traceable end-to-end process rather than isolated screens:

- project creation, selection, and independent requirements;
- reviewed PDF and DOCX import with an atomic apply step;
- immutable requirement versions, snapshots, history, and diffs;
- persisted asynchronous analysis jobs with reload recovery and retry;
- evidence-backed taxonomy mapping review;
- solution and product catalogues with comparisons and requirement links;
- conflict detection with guided decisions;
- interactive coverage and decision matrices with drill-down and filtered CSV/JSON export;
- portfolio Git preview, commit, materialisation preview/apply, ordinary merge, and semantic merge;
- project and requirement reports in HTML, DOCX, Markdown, JSON, and CSV.

The browser acceptance suite exercises this as a vertical workflow and treats serious accessibility findings as release failures. See the [Project Portfolio Guide](docs/en/PROJECT_REQUIREMENT_PORTFOLIO.md) and [feature matrix](docs/en/PROJECT_PORTFOLIO_FEATURE_MATRIX.md).

### Authoritative and recoverable requirement Copilot sessions

The requirement Copilot no longer treats one browser request or one polling connection as the authority for a longer analysis session. The persisted server operation exposes explicit `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, and `CANCELLED` states and remains observable across transient status failures, navigation and page reloads.

The session workflow includes:

- explicit `RECONNECTING` state instead of declaring a still-running server operation failed;
- indeterminate progress for opaque LLM work and determinate progress only for completed passes;
- cancellation that waits for the authoritative terminal state;
- visible running, success, and failure states for DOCX, HTML, and JSON decision-report exports;
- validation of non-empty export bodies, media types, and filenames;
- non-secret AI target descriptors covering provider, model, operating mode, health, configuration fingerprint, and prompt budget;
- fail-closed prompt-size enforcement immediately before every productive HTTP LLM request;
- persisted, addressable failure evidence when a saved requirement exceeds the configured prompt budget.

A PostgreSQL/Testcontainers browser acceptance performs one coherent session with cancellation, forced restart, a transient poll failure, reload recovery, result navigation, real exports, OOXML validation, cross-format evidence checks, responsive controls, and a server-owned oversized-prompt failure.

This Copilot-session scope does not establish completion of the separate durable ad-hoc synchronous/SSE operation and replay work in [#808](https://github.com/carstenartur/Taxonomy/issues/808). Likewise, the portfolio Git operations above are not a claim that every portable installation backup, database-to-database restore or external Git reintegration scenario in [#1146](https://github.com/carstenartur/Taxonomy/issues/1146) is accepted.

### Versioned Information Product overlay and bounded concrete-product analysis

The checked-in C3 Excel workbook remains the upstream catalogue baseline. A versioned JSON overlay applies explicit structural corrections and draft Information Product classifications without rewriting that workbook.

The overlay contract:

- rejects unknown parents, self-parenting, cycles, cross-root edges, unresolved strict-coverage entries, and source-title or source-state drift;
- recalculates effective hierarchy levels after validated corrections;
- retains source identity and provenance;
- records a proposed primary family, optional secondary candidates, confidence, `reviewRequired`, and written justification for every draft mapping;
- incorporates the Excel digest, overlay digest, and mapping version into the effective catalogue fingerprint.

Concrete `PRODUCT` leaves are evaluated independently from taxonomy categories in deterministic batches of at most ten. All products may score zero. When a relevant family has no suitable catalogued product above the configured threshold, Taxonomy emits a structured product-coverage gap instead of inventing a taxonomy node or a winning product. Failed or incomplete product batches remain `PARTIAL` and never become confirmed gaps. Completed product evidence and an already established gap remain available when a separate category call fails.

Every overlay mapping delivered in 1.4.1 is provisional and requires expert review. The dedicated review queue and runtime Git-promotion workflow are not part of this release. General two-stage analysis for arbitrary taxonomy category nodes with more than ten children also remains follow-up work; the bounded concrete-product path must not be presented as completion of that broader high-fan-out programme.

### Versioned Word templates and template-backed decision reports

Administrators can maintain DOTX templates through the browser or a virtual WebDAV collection while retaining precise Git history for the unpacked OOXML package contents. Taxonomy stores each template canonically as an unpacked tree in a dedicated Hibernate-backed JGit repository and materialises a valid `.dotx` package on demand. WebDAV exposes complete Office documents only; the unpacked representation remains an internal Git and inspection concern.

The browser administration surface provides upload, current and historical download, version history, and test export. The underlying service and Git model also support per-part inspection, comparison, and conflict-protected restore. A fully guided compare-and-restore workflow in the administrator UI remains follow-up work and is not claimed as complete in 1.4.1.

The template boundary includes:

- revocable, user-bound WebDAV application credentials with read/write scopes, expiry, hashed storage, one-time secret display, exact token parsing, and bounded fail-closed authentication lockout;
- ETags, lock tokens, conditional writes, stale-write rejection, and concurrent-create protection;
- rejection of unsafe ZIP paths, malformed XML, invalid manifests and relationships, unsafe external links, dangerous Word field instructions, macros, ActiveX, OLE objects, and signatures;
- rejection of comments and reviewer identity, tracked insert/delete/move/cell revisions, tracked-revision mode, hidden or web-hidden text in document stories, custom XML, custom document properties, printer settings, stale thumbnails, and uncontrolled personal or workstation metadata;
- a fail-closed placeholder contract that permits known Taxonomy tokens only in supported body/table paragraphs, headers, and footers and rejects unknown, malformed, text-box, content-control, footnote, metadata, attributes, and other unsupported placements before activation;
- deterministic package materialisation and semantic-validation caching by immutable template revision.

Taxonomy rejects unsafe or privacy-bearing templates; it does not silently sanitize and activate them. A downloadable sanitization report and optional deterministic cleanup remain post-1.4.1 administration enhancements.

A valid macro-free decision-rationale template is bundled and seeded idempotently without overwriting organisation-specific changes. Generated reports inherit the selected template's branding, page setup, styles, headers, footers, and static metadata while retaining the generated executive summary, decision chapters, diagrams, and appendix. Every emitted XML part is checked for unresolved Taxonomy placeholders.

The produced DOCX records the full template ID, full 40-character Git revision, and canonical package SHA-256 in custom document properties independently of any visible template fields. The browser/container acceptance path starts the packaged application with Testcontainers, signs in through Playwright, verifies first-start seeding and WebDAV discovery, downloads the generated DOCX, validates the package, and renders that exact document through LibreOffice. See the [English document-template guide](docs/en/DOCUMENT_TEMPLATES.md) and [German document-template guide](docs/de/DOCUMENT_TEMPLATES.md).

### Server-authoritative architecture workbench

Requirement architecture is rendered from the persisted analysis snapshot through one neutral server-side diagram scene. The browser view, standalone SVG, and vector PDF use the same semantic nodes, relationships, layout, and source snapshot. Viewing or exporting an existing snapshot does not invoke the LLM and does not rebuild historical content from current preferences. See [ADR 0003](docs/adr/0003-server-authoritative-architecture-workbench.md).

### Deterministic local semantic-search readiness

The local ONNX path has an explicit, fail-closed lifecycle:

- the pinned embedding model is restored or downloaded deterministically;
- catalogue nodes are available before the semantic index is rebuilt;
- the application reports readiness instead of exposing a partially initialised index;
- stale or missing indexes are rebuilt through the controlled initializer;
- real-model and browser tests cover startup, readiness, rebuild, and interaction behaviour;
- graph and similar-node result counts are bounded before Hibernate Search or candidate arithmetic, and non-positive graph limits perform no embedding or search work.

BGE embeddings explicitly use normalized CLS pooling. Semantic search separates
bounded ANN candidate exploration from the number of returned hits. Existing vectors
from the earlier pooling behavior require rebuilding through the LOCAL_ONNX
initializer. The paired DE/EN reference evaluation keeps German reference misses
visible; English-model success is not evidence of equivalent German retrieval quality.

Custom OpenAI-compatible provider configuration and diagnostics also provide clearer validation and failure reporting.

### Responsive and accessible task interaction

Navigation and task controls remain discoverable at narrow widths and 200–400% zoom. Overlays cannot silently hide the required action surface, disclosures use collision-safe identities, keyboard order follows visual order, and shared semantic helpers keep role and accessibility state consistent.

### Rancher, RKE2, and constrained-cluster deployment

The Helm chart includes:

- a Rancher/RKE2 profile for ingress-nginx under `/taxonomy/`;
- a generic small evaluation profile with a 500-mCPU ceiling;
- forwarded-prefix and browser base-path handling that prevents sub-path 404 responses;
- explicit immutable-image requirements, readiness checks, ResourceQuota and LimitRange validation, restricted-egress coverage, secret-safe diagnostics, and NetworkPolicy guidance.

See the [Rancher/RKE2 deployment guide](deploy/helm/taxonomy/RANCHER.md). The small profile is an evaluation and functional-validation floor, not a measured capacity claim for bulk imports, local model download, or high-concurrency analysis.

### Audited runtime configuration and safer operator defaults

The German and English configuration references form an executable inventory of deployment-facing settings. A contract test discovers effective Spring placeholders, direct bindings, feature switches, and `@ConfigurationProperties` fields and requires both language references to match. Profile-dependent database, Hibernate Search, LLM, Copilot/Autopilot, portfolio, document-import, security, repository, and lifecycle settings are identified explicitly rather than being presented as one global default.

The historical name `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` remains unchanged, but its actual scope is explicit: it limits architecture views created by both manual Copilot and Autopilot. The effective ceiling is the lower of that value and `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES`. Production Compose forwards the operator-maintained `.env` file and no longer enables local embeddings implicitly; embedding inference and runtime model download remain separate opt-ins.

Interactive login and machine monitoring credentials are deliberately separated. `TAXONOMY_ADMIN_PASSWORD` bootstraps the local form-login administrator. The historic application variable `ADMIN_PASSWORD` remains the optional Actuator/admin token. In the supplied Helm chart, the existing Secret key `ADMIN_PASSWORD` continues to supply the login credential for upgrade compatibility, while a distinct optional `ADMIN_TOKEN` key supplies the machine token and protected ServiceMonitor. The chart rejects reuse of one Secret key for both purposes and rejects an application/ServiceMonitor token-key mismatch. Token-authenticated Actuator reads work behind a context path, missing or incorrect tokens are rejected, and CSRF protection remains enabled.

### Reliability and data integrity

Analysis-draft autosaves are serialized. Multiple browser changes that arrive during an active save are coalesced and persisted with the server-returned optimistic revision, preventing two local PUT requests from racing with the same version. Genuine cross-tab or cross-device conflicts remain visible and require an explicit user decision.

The PostgreSQL schema removes the redundant non-unique relation-projection checkpoint index through immutable migration V18 while preserving the equivalent constraint-owned unique index. The JPA mapping no longer recreates the removed index when schema update is enabled, and Testcontainers coverage proves the resulting index state.

### Integrated multi-repository technical foundation

Taxonomy 1.4.1 includes the repository-scoped storage, context, and service foundation needed for future multi-repository operation. This is not yet a generally supported public multi-repository product surface. The `/api/repositories/**` API remains disabled by default, and the broader tenancy, recovery, authority, cache, UX, and end-to-end isolation programme remains tracked separately. The established primary-repository/workspace behaviour is the supported default for 1.4.1.

## Security and bounded runtime state

Both managed Jackson lines include the Databind fixes for CVE-2026-91776 and
CVE-2026-91777: Jackson 2.22.3 and 3.1.7. The fixes bound polymorphic type-id caching
and address quadratic forward-reference resolution. Maven Enforcer rejects the
affected compile/runtime dependencies even when a BOM version is overridden.
See the upstream [2.22.3](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.22.3)
and [3.1.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7) release notes.

### Stable LLM quotas after authorization

LLM-backed operations consume quota only after Spring Security has authenticated and authorized the request. Local accounts are keyed by a digest of the canonical authenticated username. Keycloak browser OIDC and bearer JWT access for the same account share the exact immutable issuer/subject identity; editable display names, forwarding headers, and peer addresses do not create fresh budgets.

The in-memory principal table is capped at 10,000 entries per running application instance, inactive entries expire, and identities above the cap share a fail-closed overflow budget. Exactly `0` disables the LLM quota; a negative value fails closed to one request per minute. HTTP 429 responses are UTF-8 JSON with `Retry-After` and `Cache-Control: no-store`. The same route contract applies at root and below a servlet context such as `/taxonomy`.

### Authoritative login lockout

The local-user login limiter is registered exactly once inside Spring Security, after trusted session restoration and before form-login and HTTP-Basic authentication. It counts only genuine downstream credential failures: a form-login error redirect or HTTP 401 after an explicit Basic API attempt. Missing credentials, bearer credentials, unrelated authorization failures, and an already authenticated session do not allocate or increment peer state.

Peer identities use fixed-size SHA-256 digests of the framework-resolved remote address. The filter never parses client-controlled forwarding headers itself. State uses monotonic time, global expiry, a hard 10,000-peer cap per running instance, and a shared fail-closed overflow budget that cannot erase existing lockouts. Blocked attempts receive non-cacheable UTF-8 JSON HTTP 423 with `Retry-After`. Deployments must enable forwarded-address processing only behind a trusted ingress and prevent direct access to the application port.

### Context-path-safe required password replacement

Bootstrap and administrator-reset local credentials can be marked for mandatory replacement. The enforcement filter is registered only once in the local Spring Security chain. It compares application paths after removing the servlet context, so `/taxonomy/change-password`, its API, and required CSS, JavaScript, image, and webjar resources remain reachable instead of entering a redirect loop.

Browser redirects stay inside the active context. Restricted API calls return non-cacheable UTF-8 JSON HTTP 428 with the stable `PASSWORD_CHANGE_REQUIRED` code and a context-aware replacement endpoint. The existing root deployment and real HTTP-Basic password-replacement flow remain unchanged. Keycloak deployments continue to delegate password lifecycle policy to the identity provider.

### Bounded WebDAV application-credential authentication

The WebDAV credential boundary rejects oversized Basic headers before Base64 decoding, bounds decoded credential material, requires strict UTF-8, limits username and password code points, and invokes the credential service only for the exact productive 71-character `taxdav_…` syntax. Malformed or oversized application-token candidates receive the same generic Basic challenge without reaching BCrypt. Ordinary local-account Basic authentication still falls through to Spring Security.

Failure state stores only a digest of the framework-resolved peer and normalized supplied username. Admission, cleanup, and overflow selection are serialized; the regular table is capped at 10,000 identities per running application instance; inactive entries expire; and excess identities share one fail-closed overflow tracker instead of clearing active lockouts. After ten failures, the next attempt receives non-cacheable UTF-8 JSON HTTP 429 with `Retry-After`. Read/write scope enforcement and conditional WebDAV operations are unchanged.

### Sanitized expected failures

Repairable decision-report template unavailability has a stable, non-sensitive HTTP 503 contract. Framework-level and generic HTTP 5xx responses no longer copy arbitrary exception messages to clients; complete exceptions remain available in server logs. The release candidate also retains sanitized diagnostics, secret-safe health checks, and explicit fail-closed startup validation for unsafe production credentials.

All LLM quota, login-lockout, and WebDAV credential-failure counters are process-local. Multi-replica deployments multiply aggregate allowance and keep separate lockout tables unless an outer distributed control is supplied.

## Modular runtime architecture (#628)

Taxonomy remains one deployable Spring Boot application, with seven physical Maven feature
libraries: `taxonomy-workspace`, `taxonomy-knowledge`, `taxonomy-templates`, `taxonomy-interop`,
`taxonomy-architecture`, `taxonomy-analysis`, and `taxonomy-portfolio`. They own workspace/versioning/editor
state, knowledge/search, document templates, interoperability, architecture derivation,
requirement/LLM analysis, and the project portfolio respectively.

The application is the composition/deployment root rather than the implementation container
for those seven features. Enforced module boundaries prevent feature libraries from depending
back on the application and verify an acyclic module graph. Domain, DSL, export, and extension-API
foundations remain framework-free; database migrations and cross-context security/recovery tests
stay application-owned. Preferences and provenance remain supporting application contexts.

Unit tests and feature resources follow their owning modules. Executable-JAR checks verify
unique class/resource ownership without changing the deployment model. See the
[module boundaries](docs/en/MODULE_BOUNDARIES.md) and
[completion scope](docs/dev/MODULE_EXTRACTION_COMPLETION.md).

## Release and reproducibility

### One exact release candidate

Every release request is bound to one exact reviewed parent commit and may change only `.github/release-request.json`. Its revision must advance exactly once. Before immutable artifacts are published, the unchanged candidate must pass:

- canonical CI/CD and browser verification;
- PostgreSQL compatibility;
- Oracle compatibility;
- Microsoft SQL Server compatibility;
- CodeQL source analysis;
- Security Scan;
- relevant constrained-Kubernetes, document-template, and consumer contracts.

Missing, failed, cancelled, unexpectedly skipped, timed-out, mismatched, or unreliable exact-SHA evidence stops publication.

### Exact-fingerprint CodeQL migration boundary

The 1.4.1 release train removes six previously baselined findings: the WebDAV write-scope authorization dataflow, two unbounded semantic-search arithmetic paths, and three predictable temporary-evidence paths in JavaScript tooling.

At the reviewed source `f47c0497dfee663de6a0e865ecfd99bc2151b8b8`, the checked-in [CodeQL baseline](.github/codeql-sarif-baseline.json) contains **3 entries**, all for `java/user-controlled-bypass` in `ProposalApiController`. Each records an exact primary-location fingerprint and a rationale referring to service-side context, authorization and expected-head checks. A retained entry is neither proof of an exploitable bypass nor a claim that the finding disappeared. Further work is tracked in [#857](https://github.com/carstenartur/Taxonomy/issues/857).

The earlier count of eight and its list of remaining logging/bootstrap-password findings are not the current baseline inventory. No complete rule class, severity or path is excluded. New occurrences still require the normal exact-fingerprint review and release gates; the eventual release source must be checked again rather than inheriting this count from prose.

### Immutable, digest-bound delivery

The release transaction aligns source and deployment evidence:

- the release tag and packaged source are verified before use;
- Maven module JARs, checksums, SBOM/VEX companion, Helm assets, and Kubernetes manifests are archived;
- the OCI image carries source/version labels and is referenced by immutable digest;
- provenance and SBOM attestations are enabled;
- the immutable digest is vulnerability-scanned before the draft release becomes public;
- Helm deployment evidence records the same image digest and source commit.

### Maven/JUnit-owned quality contracts

Maven remains the canonical verification entry point. Deterministic repository policy is owned by JUnit/Failsafe or dependency-free Java tooling, including workflow test authority, documentation links, aggregate reactor coverage, dependency alignment, immutable supply-chain references, packaged dependency hygiene, frontend API boundaries, release version state, request ancestry, CodeQL SARIF enforcement, and SBOM/VEX companion generation.

A bounded set of existing Python release adapters and evidence generators remains in 1.4.1 under Maven/JUnit-owned positive and negative contracts. Remaining adapter migration and release-manifest work is tracked in [#629](https://github.com/carstenartur/Taxonomy/issues/629); [#673](https://github.com/carstenartur/Taxonomy/issues/673) was closed for delivered foundations only, not for complete Python removal. This release introduces no new Python tooling and does not represent retained adapters as product runtime dependencies.

The Java/Maven-only reproducibility requirement is **not yet fulfilled**. The current [Maven verification inventory](docs/dev/MAVEN_VERIFICATION.md) lists remaining container/Helm prerequisites, checks outside root `verify`, conditional integration lanes and post-test evidence rules. Neither the `ci` profile alone nor `test-local` proves all Actions checks were reproduced. This is a known delivery gap, not a passed release criterion.

## Compatibility and deliberate exclusions

- AI-generated mappings, architectures, gaps, solutions, products, scores, and rationales remain proposals requiring qualified human review. Taxonomy 1.4.1 does not claim independently benchmarked architecture accuracy or calibrated decision probability.
- Provisional Information Product mappings are not external approvals. The dedicated expert review queue and runtime Git-promotion workflow remain follow-up work.
- General two-stage high-fan-out category analysis is not complete; the bounded concrete-product path covers product leaves, not every taxonomy node with a large child set.
- The public multi-repository API is disabled by default and is not a production-supported 1.4.1 capability.
- Federated authority and collaborative-editing documents are planning baselines, not claims that those future capabilities are delivered.
- WebDAV exposes valid packaged DOTX resources only; unpacked OOXML remains an internal Git and inspection concern.
- The administrator UI does not yet provide the complete guided template compare-and-restore journey, although the versioned backend capabilities exist.
- Raw Taxonomy placeholders inside Word text boxes, content controls, footnotes, metadata, attributes, and other unsupported stories are rejected rather than silently exported. Full content-control-driven chapter and appendix templating remains follow-up work.
- Direct Word links require public HTTPS except for loopback development. Deployments should use scoped application credentials rather than ordinary account passwords for desktop WebDAV clients.
- Direct desktop-Word editing is not certified as generally compatible across Microsoft 365 versions, operating systems, reverse proxies, credential managers, and recovery/autosave flows. Ordinary HTTPS download and upload remain the supported fallback.
- WebDAV lock coordination is process-local. Multi-replica direct editing requires shared lock coordination and remains follow-up work; Git/ETag preconditions still prevent silent lost updates.
- LLM quota, login-lockout, and WebDAV authentication-failure state is process-local rather than cluster-global.
- DOCX and FOP/PDF decision reports use separate rendering paths. Their end-to-end semantic parity is tracked separately and is not claimed by this release.
- Autosave-session grouping and long-history/template-count performance work remain follow-up items.
- `ADMIN_PASSWORD` is a separate machine token and is not the local form-login password; production installations that use both must configure distinct values.
- Local semantic embeddings and runtime model download are disabled unless explicitly enabled.
- Complete Python removal and the remaining release-manifest implementation are open in #629; the closed foundation issue #673 must not be used as proof of completion.
- The small Kubernetes profile is not a measured production capacity envelope.
- The unpublished `v1.3.1` and `v1.4.0` tags must not be used as substitutes for 1.4.1 release assets.

## Upgrade notes

These are preparation requirements for an eventual verified release, not an instruction to install the unpublished 1.4.1 tag. A 1.5.0 release must first align and verify these version-specific instructions against its own source, artifacts and upgrade tests.

1. Back up the application database and persistent storage using the normal operational procedure.
2. Keep existing installations on their supported, verified release until a successor is actually published. Validate the intended upgrade from 1.3.0 on a restorable copy; do not infer upgrade acceptance from a successful fresh install.
3. After the first 1.4.1 start, verify that `decision-rationale-report.dotx` is present in `/admin/document-templates`. Seeding is idempotent and does not replace an organisation-specific revision.
4. Existing or newly uploaded Word templates containing comments, tracked changes, hidden text, custom XML, personal/workstation properties, unsupported Taxonomy token placements, or other rejected constructs must be cleaned in Word before Taxonomy will activate them.
5. Configure a trusted external HTTPS origin before enabling direct Word/WebDAV actions, and create scoped WebDAV application credentials for users who require them.
6. For local form login, configure `TAXONOMY_ADMIN_PASSWORD`. Review `TAXONOMY_REQUIRE_PASSWORD_CHANGE`, `TAXONOMY_LOGIN_RATE_LIMIT`, `TAXONOMY_LOGIN_MAX_ATTEMPTS`, and `TAXONOMY_LOGIN_LOCKOUT_SECONDS` before production rollout. Forwarded peer addresses are trustworthy only behind a controlled ingress.
7. When protected Actuator or ServiceMonitor access is used, configure a distinct machine token. With the supplied Helm chart, keep Secret key `ADMIN_PASSWORD` for the login credential and add `ADMIN_TOKEN` for the machine token; never reuse one value for both. Installations with `serviceMonitor.enabled=false` may omit `ADMIN_TOKEN`.
8. Review the bilingual configuration reference before carrying forward environment values. Production Compose forwards `.env`, while local embeddings and runtime model download remain disabled until enabled explicitly.
9. Review every provisional Information Product mapping and every AI-generated decision before treating it as organisational or procurement authority.
10. Deploy only an immutable digest whose source, version and scan evidence match an actually published GitHub Release. Do not deploy `latest` or an unpublished attempt tag, including `v1.4.1`.
11. For Rancher/RKE2 sub-path deployments, start with `values-rancher-rke2.yaml`, verify `/taxonomy/actuator/health/readiness`, and exercise the prefixed login/password-replacement path.
12. Treat the reported semantic-search readiness state as authoritative while model/index initialization is in progress.
13. Contributors and downstream verifiers should use the repository-owned Maven wrapper and canonical verification lifecycle.
14. Follow the corrected [backup/restore procedure](docs/en/CONTAINER_IMAGE.md#5-persistence-and-backup), which resolves the actual Compose service volume. Restore acceptance must include users, workspace state, search and Git history.
15. Use PostgreSQL's managed migration path for external production databases. MSSQL and Oracle remain compatibility profiles pending complete production qualification; switching a profile is not a cross-database data migration. Never use `ddl-auto=create` to upgrade an existing database.
16. File-backed HSQLDB upgrades from 1.3 prepare required repository and portfolio tenant fields before Hibernate starts. Keep schema migration enabled and resolve reported provenance/key conflicts from a restored stopped-writer backup; do not bypass the checks or reassign historical data to the current branch. See the [database upgrade guidance](docs/en/DATABASE_SETUP.md#hsqldb-default).

## Verification boundary

Taxonomy 1.4.1 is published only after the release request, final source commit, Git tag, GitHub Release, Maven artifacts, checksums, SBOM/VEX evidence, OCI digest, image scan, attestations, Helm package, Kubernetes manifests, deployment evidence, and post-release `1.4.2-SNAPSHOT` state agree with the same release transaction.
