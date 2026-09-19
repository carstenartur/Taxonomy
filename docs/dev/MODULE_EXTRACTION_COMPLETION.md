# Runtime module extraction — #628

## Bounded completion scope

All seven planned feature contexts have physical Maven owners: workspace, knowledge,
templates, interop, architecture, analysis and portfolio. The existing domain, DSL, export
and extension-API foundations remain framework-free. There is still one deployable application.
No catch-all adapters module or microservice was introduced.

The executable app owns composition, security, deployment configuration, schema migration,
HTTP aggregation and the deliberately deferred preferences/provenance supporting contexts.
This is the scope stated in #628, not a claim that the app contains only two Java classes.
Prompt behavior and prompt/mock-score resources now belong to analysis. Portfolio owns its
job/recovery state and automation defaults. Java package names and persisted names are retained
except for two previously shared helpers assigned to their actual feature owner.

## Last application seams

Analysis gateways read live integer settings through `AnalysisRuntimeSettings`; the existing
application preferences service implements this read-only port without exposing storage.
Architecture metadata uses `ArchitectureReportMetadataPort` and the existing application adapter.
Canonical diagram serialization is architecture-owned, allowing portfolio exports without an
application dependency. Workspace and repository identities remain explicit and unchanged.

## Exception ownership

The existing narrowly scoped package-cycle exceptions retain their scope and expiry; each
entry now names its physical `ownerModule` and this acceptance record. Knowledge owns its
catalogue/relations/search internals; workspace owns versioning/workspace internals. Framework-free
export contracts have no dependency on a runtime library; historical application export and DSL
materialization adapters are application composition. None of these is a Maven cycle.

## Verification and closure

`ArchitectureCompletionTest` requires every planned physical module, checks runtime class
ownership and rejects dependencies on any app-owned implementation. The existing exact dependency
ratchet, module DAG, cycle and selector tests remain mandatory. Focused tests are supplementary;
#628 can be closed only after canonical CI, database/security/recovery/product checks and merge.

## Foreign persistence boundaries

Analysis and portfolio consume the read-only `TaxonomyNodeLookup` API rather than catalogue
repositories. The existing Spring Data repository implements it, preserving the exact query.
Portfolio hypothesis linkage is a knowledge-owned operation through `AnalysisHypothesisLinkPort`;
the implementation retains the original exact repository/workspace/session query and mutations,
and joins the existing transaction. It does not add a commit boundary or a fallback lookup.
Analysis view provenance is read through `WorkspaceViewContextReadPort` rather than the concrete
versioning implementation. The obsolete architecture-controller/versioning exception is retired.

Portfolio Git projection also uses an explicit `WorkspacePortfolioDocumentPort`. Its handle
captures the repository exactly once, retaining the original branch reads, checkpoints, merge
behavior and error propagation. Neither analysis nor portfolio may import workspace storage
implementations. Existing projection/merge fixtures exercise the same underlying repositories
through the real adapter rather than losing those assertions.
