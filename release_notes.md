# Taxonomy 1.4.0

Taxonomy 1.4.0 is the first published release after 1.3.0. It combines the stabilization work that had been prepared for 1.3.1 with a substantially stronger project-portfolio workbench, deterministic architecture exports, local semantic-search readiness, deployment profiles, and a fail-closed release and quality pipeline.

## Important release-line note

The immutable `v1.3.1` Git tag exists only to preserve release ancestry. **No GitHub Release was published for 1.3.1, and 1.3.1 is not a supported deployment target.** Taxonomy 1.4.0 supersedes that incomplete publication attempt. Users of the latest published release should upgrade from 1.3.0 directly to 1.4.0.

The 1.3.1 release commit remains reachable from the 1.4.0 history and from the maintenance line; no tag or published history was rewritten.

## Product highlights

### Complete project and requirement portfolio workflow

The project portfolio now supports a complete traceable workflow rather than a collection of isolated screens:

- project creation, selection, and independent requirements;
- reviewed PDF and DOCX import with an atomic apply step;
- immutable requirement versions, snapshots, history, and diffs;
- persisted asynchronous analysis jobs, reload recovery, retry, and job status;
- evidence-backed taxonomy mapping review;
- solution and product catalogues with requirement links and comparisons;
- conflict detection with guided decisions;
- interactive coverage and decision matrices with drill-down plus filtered CSV and JSON export;
- portfolio Git preview, commit, materialisation preview/apply, ordinary merge, and semantic merge;
- project and requirement reports in HTML, DOCX, Markdown, JSON, and CSV.

The browser acceptance suite exercises this as one vertical process and treats serious accessibility findings as release failures. See the [Project Portfolio Guide](docs/en/PROJECT_REQUIREMENT_PORTFOLIO.md) and [feature matrix](docs/en/PROJECT_PORTFOLIO_FEATURE_MATRIX.md).

### Server-authoritative architecture workbench

Requirement architecture is now rendered from the persisted analysis snapshot through one neutral server-side diagram scene. The browser view, standalone SVG, and vector PDF therefore use the same semantic nodes, relationships, layout, and source snapshot.

Exports no longer print the current browser page or silently fall back to an unrelated taxonomy graph. Viewing or exporting an existing snapshot does not invoke the LLM and does not rebuild historical content from current preferences. See [ADR 0003](docs/adr/0003-server-authoritative-architecture-workbench.md).

### Deterministic local semantic-search readiness

The local ONNX path now has an explicit, fail-closed lifecycle:

- the pinned embedding model is restored or downloaded deterministically;
- catalogue nodes are available before the semantic index is rebuilt;
- the application reports whether semantic search is ready instead of exposing a partially initialized index;
- stale or missing indexes are rebuilt through the controlled initializer;
- real-model and browser integration tests cover startup, readiness, rebuild, and interaction behavior.

Custom OpenAI-compatible provider configuration and diagnostics also provide clearer validation and failure reporting.

### Responsive and accessible task interaction

The interface has stronger keyboard, zoom, mobile, and disclosure behavior. Navigation and task controls remain discoverable at narrow widths and 200–400% zoom, overlays cannot silently hide the required action surface, native disclosures use collision-safe identities, and shared semantic helpers keep role and accessibility state consistent.

### Rancher, RKE2, and constrained-cluster deployment

The Helm chart now includes:

- a Rancher/RKE2 profile for ingress-nginx under `/taxonomy/`;
- a generic small evaluation profile with a 500-mCPU ceiling;
- forwarded-prefix and browser base-path handling that prevents sub-path 404 responses;
- explicit immutable-image requirements, readiness checks, resource-quota diagnostics, and NetworkPolicy guidance.

See the [Rancher/RKE2 deployment guide](deploy/helm/taxonomy/RANCHER.md). The small profile is intended for evaluation and functional validation; it is not a capacity claim for bulk imports, local model download, or high-concurrency analysis.

## Release, security, and reproducibility

### One exact release candidate

A publishing request is now bound to one exact reviewed `main` parent and may change only `.github/release-request.json`. The request revision must advance exactly once, preventing a stale or fabricated release request from borrowing evidence from another commit.

Before immutable artifacts are built, the release workflow requires the same unchanged final `main` SHA to pass:

- canonical CI/CD and browser verification;
- PostgreSQL compatibility;
- Oracle compatibility;
- Microsoft SQL Server compatibility;
- CodeQL source analysis;
- Security Scan.

Missing exact-SHA evidence is dispatched explicitly on `main`; failed, cancelled, skipped, timed-out, mismatched, or unreliable workflow results stop publication. The already completed non-publishing `1.4.0 -> 1.4.1-SNAPSHOT` dry run proved the version transition without creating a tag, public release, image, or deployment side effect.

### Immutable, digest-bound delivery

The publishing transaction keeps source and deployment evidence aligned:

- the release tag and packaged source are verified before use;
- Maven module JARs, checksums, SBOM, VEX, Helm assets, and Kubernetes manifests are archived as release evidence;
- the OCI image is referenced by digest rather than only by a mutable tag;
- provenance and SBOM attestations are enabled;
- the exact digest is vulnerability-scanned before the draft release becomes public;
- Helm deployment evidence records that same image digest.

### Maven/JUnit-owned quality contracts

Deterministic repository policy is now owned by the normal Maven/JUnit lifecycle instead of parallel Python pass/fail implementations. Executable contracts cover:

- workflow test authority;
- repository documentation links;
- aggregate reactor JaCoCo coverage, including branch coverage;
- Hibernate Search, Hibernate ORM, and Lucene dependency alignment;
- immutable GitHub Action and production-image pins;
- packaged dependency hygiene and reviewed exceptions;
- frontend API-boundary debt and direct-transport ratchets;
- release version-state and request ancestry.

Standalone Python remains only where it is useful as a bounded release adapter or evidence generator; JUnit owns the positive and negative contracts around those retained boundaries.

## Compatibility and deliberate exclusions

- The unfinished multi-repository and federated-authority implementation tracked by #609/#610 is **not included** in Taxonomy 1.4.0. It remains on its isolated integration line until its tenancy, recovery, authority, cache, UX, and end-to-end isolation boundaries are complete.
- The federated authority and collaborative editing document included in this release is a planning baseline, not a claim that those future capabilities are already delivered.
- The existing published primary-repository/workspace behavior remains the supported product boundary for 1.4.0.
- No deployment should use the unpublished `v1.3.1` tag as a substitute for the 1.4.0 release assets.

## Upgrade notes

1. Back up the application database and persistent storage using the normal operational procedure.
2. Upgrade directly from the published 1.3.0 assets to the 1.4.0 release.
3. Use the immutable 1.4.0 image digest or verified release tag; do not deploy `latest` or `v1.3.1`.
4. For Rancher/RKE2 sub-path deployments, start with `values-rancher-rke2.yaml` and verify `/taxonomy/actuator/health/readiness`.
5. Local semantic search can remain unavailable until its controlled model/index initialization completes; use the reported readiness state rather than assuming an empty result means a ready index.
6. Contributors and downstream verifiers should use the repository-owned Maven wrapper and canonical verification lifecycle rather than invoking internal test selectors directly.

## Verification boundary

Taxonomy 1.4.0 is published only after the release request, final source commit, Git tag, GitHub Release, Maven artifacts, SBOM/VEX evidence, OCI digest, image scan, Helm package, Kubernetes manifests, and post-release `1.4.1-SNAPSHOT` state agree with the release transaction.
