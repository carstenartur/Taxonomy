# Taxonomy 1.4.0

Taxonomy 1.4.0 is the first published release after 1.3.0. It combines the stabilization work prepared for the unpublished 1.3.1 line with a substantially stronger requirements and architecture workbench, versioned Word-template administration, deterministic architecture exports, local semantic-search readiness, constrained-cluster deployment profiles, and a fail-closed release pipeline.

## Important release-line note

The immutable `v1.3.1` Git tag remains only as release-ancestry evidence. No GitHub Release was published for 1.3.1, and 1.3.1 is not a supported deployment target. Existing installations should upgrade directly from the published 1.3.0 assets to 1.4.0. No tag or published history was rewritten.

## Product highlights

### Versioned Word templates and template-backed decision reports

Administrators can maintain DOTX templates through the browser or a virtual WebDAV collection while retaining precise Git history for the unpacked OOXML package contents. Taxonomy stores each template canonically as an unpacked tree in a dedicated Hibernate-backed JGit repository and materialises a valid `.dotx` package on demand. WebDAV exposes complete Office documents only; the unpacked representation remains an internal Git and inspection concern.

The template workspace provides:

- upload, download, history, per-part OOXML inspection and diff, conflict-protected restore, and test export;
- revocable, user-bound WebDAV application credentials with read/write scopes, expiry, hashed storage, and one-time secret display;
- ETags, lock tokens, conditional writes, stale-write rejection, and concurrent-create protection;
- fail-closed validation of XML parts, OPC relationships, ZIP paths, manifests, external links, dangerous field instructions, macros, ActiveX, OLE objects, signatures, comments, and tracked changes;
- deterministic package materialisation and semantic-validation caching by immutable template revision;
- direct Microsoft Word actions where a public HTTPS origin, or loopback development origin, makes those links safe to expose.

A valid macro-free decision-rationale template is bundled and seeded idempotently without overwriting organisation-specific changes. Generated reports inherit the chosen template's branding, page setup, styles, headers, footers, and static metadata while retaining the generated executive summary, decision chapters, diagrams, and appendix. The produced DOCX records the template identity, Git revision, and package checksum as provenance.

The browser/container acceptance path starts the packaged application with Testcontainers, signs in through Playwright, verifies first-start seeding and WebDAV discovery, downloads the generated DOCX, validates the package, and renders that exact document through LibreOffice. See the [English document-template guide](docs/en/DOCUMENT_TEMPLATES.md) and [German document-template guide](docs/de/DOCUMENT_TEMPLATES.md).

### Complete project and requirement portfolio workflow

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

### Server-authoritative architecture workbench

Requirement architecture is rendered from the persisted analysis snapshot through one neutral server-side diagram scene. The browser view, standalone SVG, and vector PDF use the same semantic nodes, relationships, layout, and source snapshot. Viewing or exporting an existing snapshot does not invoke the LLM and does not rebuild historical content from current preferences. See [ADR 0003](docs/adr/0003-server-authoritative-architecture-workbench.md).

### Deterministic local semantic-search readiness

The local ONNX path has an explicit, fail-closed lifecycle:

- the pinned embedding model is restored or downloaded deterministically;
- catalogue nodes are available before the semantic index is rebuilt;
- the application reports readiness instead of exposing a partially initialized index;
- stale or missing indexes are rebuilt through the controlled initializer;
- real-model and browser tests cover startup, readiness, rebuild, and interaction behaviour.

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

The German and English configuration references now form an executable inventory of deployment-facing settings. A contract test discovers effective Spring placeholders, direct bindings, feature switches, and `@ConfigurationProperties` fields and requires both language references to match. Profile-dependent database, Hibernate Search, LLM, Copilot/Autopilot, portfolio, document-import, security, repository, and lifecycle settings are identified explicitly rather than being presented as one global default.

The historical name `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` remains unchanged, but its actual scope is now explicit: it limits architecture views created by both manual Copilot and Autopilot. The effective ceiling is the lower of that value and `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES`. Production Compose forwards the operator-maintained `.env` file and no longer enables local embeddings implicitly; embedding inference and runtime model download remain separate opt-ins.

Interactive login and machine monitoring credentials are deliberately separated. `TAXONOMY_ADMIN_PASSWORD` bootstraps the local form-login administrator. The historic application variable `ADMIN_PASSWORD` remains the optional Actuator/admin token. In the supplied Helm chart, the existing Secret key `ADMIN_PASSWORD` continues to supply the login credential for upgrade compatibility, while a distinct optional `ADMIN_TOKEN` key supplies the machine token and protected ServiceMonitor. The chart rejects reuse of one Secret key for both purposes and rejects an application/ServiceMonitor token-key mismatch. Token-authenticated Actuator reads work behind a context path, missing or incorrect tokens are rejected, and CSRF protection remains enabled.

### Reliability and data integrity

Analysis-draft autosaves are serialized. Multiple browser changes that arrive during an active save are coalesced and persisted with the server-returned optimistic revision, preventing two local PUT requests from racing with the same version. Genuine cross-tab or cross-device conflicts remain visible and require an explicit user decision.

The PostgreSQL schema removes the redundant non-unique relation-projection checkpoint index through immutable migration V18 while preserving the equivalent constraint-owned unique index. The JPA mapping no longer recreates the removed index when schema update is enabled, and Testcontainers coverage proves the resulting index state.

### Integrated multi-repository technical foundation

Taxonomy 1.4.0 includes the repository-scoped storage, context, and service foundation needed for future multi-repository operation. This is not yet a generally supported public multi-repository product surface. The `/api/repositories/**` API remains disabled by default, and the broader tenancy, recovery, authority, cache, UX, and end-to-end isolation programme remains tracked separately. The established primary-repository/workspace behaviour is the supported default for 1.4.0.

## Release, security, and reproducibility

### One exact release candidate

Every release request is bound to one exact reviewed parent commit and may change only `.github/release-request.json`. Its revision must advance exactly once. Before immutable artifacts are published, the unchanged candidate must pass:

- canonical CI/CD and browser verification;
- PostgreSQL compatibility;
- Oracle compatibility;
- Microsoft SQL Server compatibility;
- CodeQL source analysis;
- Security Scan;
- relevant constrained-Kubernetes, document-template, and consumer contracts.

Missing, failed, cancelled, skipped, timed-out, mismatched, or unreliable exact-SHA evidence stops publication.

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

A bounded set of existing Python release adapters and evidence generators remains in 1.4.0 under Maven/JUnit-owned positive and negative contracts. Complete removal is explicitly deferred to issue #673 on the 1.4.1 development line. This release introduces no new Python tooling and does not represent retained adapters as product runtime dependencies.

## Compatibility and deliberate exclusions

- The public multi-repository API is disabled by default and is not a production-supported 1.4.0 capability.
- Federated authority and collaborative-editing documents are planning baselines, not claims that those future capabilities are delivered.
- WebDAV exposes valid packaged DOTX resources only; unpacked OOXML remains an internal Git and inspection concern.
- Direct Word links require public HTTPS except for loopback development. Deployments should use scoped application credentials rather than ordinary account passwords for desktop WebDAV clients.
- Direct desktop-Word editing is not certified as generally compatible across Microsoft 365 versions, operating systems, reverse proxies, credential managers, and recovery/autosave flows. Ordinary HTTPS download and upload remain the supported fallback.
- WebDAV lock coordination is currently process-local. Multi-replica direct editing requires shared lock coordination and remains follow-up work; Git/ETag preconditions still prevent silent lost updates.
- DOCX and FOP/PDF decision reports use separate rendering paths. Their end-to-end semantic parity is tracked separately and is not claimed by this release.
- Autosave-session grouping and long-history/template-count performance work remain follow-up items.
- `ADMIN_PASSWORD` is a separate machine token and is not the local form-login password; production installations that use both must configure distinct values.
- Local semantic embeddings and runtime model download are disabled unless explicitly enabled.
- Complete Python removal is deferred to #673 after 1.4.0.
- The small Kubernetes profile is not a measured production capacity envelope.
- The unpublished `v1.3.1` tag must not be used as a substitute for 1.4.0 release assets.

## Upgrade notes

1. Back up the application database and persistent storage using the normal operational procedure.
2. Upgrade directly from the published 1.3.0 assets to 1.4.0.
3. After the first 1.4.0 start, verify that `decision-rationale-report.dotx` is present in `/admin/document-templates`. Seeding is idempotent and does not replace an organisation-specific revision.
4. Configure a trusted external HTTPS origin before enabling direct Word/WebDAV actions, and create scoped WebDAV application credentials for users who require them.
5. For local form login, configure `TAXONOMY_ADMIN_PASSWORD`. When protected Actuator or ServiceMonitor access is used, configure a distinct machine token. With the supplied Helm chart, keep Secret key `ADMIN_PASSWORD` for the login credential and add `ADMIN_TOKEN` for the machine token; never reuse one value for both. Installations with `serviceMonitor.enabled=false` may omit `ADMIN_TOKEN`.
6. Review the bilingual configuration reference before carrying forward environment values. Production Compose now forwards `.env`, while local embeddings and runtime model download remain disabled until enabled explicitly.
7. Deploy the immutable 1.4.0 image digest or verified release tag; do not deploy `latest` or `v1.3.1`.
8. For Rancher/RKE2 sub-path deployments, start with `values-rancher-rke2.yaml` and verify `/taxonomy/actuator/health/readiness`.
9. Treat the reported semantic-search readiness state as authoritative while model/index initialization is in progress.
10. Contributors and downstream verifiers should use the repository-owned Maven wrapper and canonical verification lifecycle.

## Verification boundary

Taxonomy 1.4.0 is published only after the release request, final source commit, Git tag, GitHub Release, Maven artifacts, checksums, SBOM/VEX evidence, OCI digest, image scan, attestations, Helm package, Kubernetes manifests, deployment evidence, and post-release `1.4.1-SNAPSHOT` state agree with the same release transaction.
