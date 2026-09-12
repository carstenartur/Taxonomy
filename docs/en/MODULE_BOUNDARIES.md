# Module and Bounded-Context Boundaries

This document records the incremental target for decomposing `taxonomy-app` without changing Taxonomy's deployment model. Taxonomy remains a **modular monolith** and a single Spring Boot application. The goal is to make Maven boundaries reflect feature authority and dependency direction instead of splitting the system into services or creating one module per package.

The machine-readable source of truth for the planned extraction contexts is `.github/architecture-contexts.json`. Temporary architecture exceptions remain in `.github/architecture-exceptions.json`, and the reviewed current cross-context dependency counts are frozen in `.github/architecture-dependency-baseline.json`.

## Current Maven reactor

The root reactor currently contains eight modules with different roles:

| Module | Current role |
|---|---|
| `taxonomy-tooling` | Dependency-free build/repository tooling |
| `taxonomy-domain` | Framework-free shared domain contracts |
| `taxonomy-dsl` | Framework-free TaxDSL parser, model, validation, differ and command logic |
| `taxonomy-export` | Framework-free diagram/export contracts and implementations |
| `taxonomy-extension-api` | Framework-free common extension contracts |
| `taxonomy-app` | Executable Spring Boot application and, currently, most Spring-aware feature implementations |
| `taxonomy-coverage` | Reactor-wide coverage aggregation |
| `taxonomy-build` | Build policy and browser/verification contracts |

The shipped application modules are therefore only part of the reactor. `taxonomy-tooling`, `taxonomy-coverage`, and `taxonomy-build` exist for build and verification responsibilities and are not runtime bounded contexts.

## Why `taxonomy-app` is being decomposed

`taxonomy-app` has accumulated several independently coherent areas: catalogue/search, architecture generation, requirement analysis, workspace/version state, the semantic editor journal and Git checkpoints, project portfolio workflows, external-tool interoperability, document-template/WebDAV handling, provenance/document ingestion, preferences, security, and observability. Keeping all Spring-aware feature code in the executable module weakens dependency direction and makes Maven unable to prevent cross-feature coupling.

The decomposition therefore follows **authority and bounded contexts**, not the current package hierarchy mechanically.

## Planned bounded contexts

### `taxonomy-knowledge`

Owns `catalog`, `relations`, and `search`. Their current mutual dependencies are treated as internal implementation coupling of one knowledge context while their public contracts are narrowed. Search mappings/binders belong with the persistence side of this context rather than becoming a general application dependency.

### `taxonomy-workspace`

Owns `workspace`, `versioning`, and `editor`. These packages jointly control editable and versioned state, including the durable semantic-operation journal, undo/redo, checkpoint preparation/publication, repository context, and JGit/Hibernate-backed DSL storage. They are kept together initially so a Maven split does not merely turn their present package coupling into a module cycle.

The invariant introduced by the editor redesign remains unchanged: **accepted semantic operations are durable revisions; Git commits are explicit stable checkpoints, not the operation log.**

### `taxonomy-architecture`

Owns architecture derivation, scoring, gaps, patterns, recommendations, architecture view/domain models, and neutral diagram preparation. Repository/workspace lookup and cross-context HTTP orchestration do not belong in this module.

### `taxonomy-analysis`

Owns requirement and LLM analysis, provider/gateway selection, response parsing, prompt/policy logic, analysis sessions and local inference abstractions. Stateful repository or hypothesis access enters through explicit ports.

### `taxonomy-portfolio`

Owns project/portfolio state and orchestration: versioned requirements, persisted analysis work/results/review state, queues/recovery, workbench snapshots and project-level workflows. It coordinates analysis, architecture and workspace capabilities through their APIs rather than reaching into their repositories.

### `taxonomy-interop`

Owns reviewed external-tool interoperability, including durable integration operations, mappings/checkpoints/events, connector orchestration and OSLC/ReqIF/ArchiMate application integration. It consumes narrow ports and must not depend on concrete editor services.

### `taxonomy-templates`

Owns the document-template subsystem: the template Git repository, OOXML package codec and safety validation, materialization/cache, WebDAV projection/locking and template administration/health contracts.

## Residual contexts that are deliberately not modules yet

`provenance` and `preferences` are explicitly classified so their dependencies are visible to the ratchet, but they have no target Maven module yet.

- `provenance` is a real subsystem with document parsing/chunking, provenance persistence and AI-assisted document analysis. It currently crosses analysis, knowledge and shared application services, so extracting it now would freeze an unclear dependency direction.
- `preferences` remains application-local until its ownership and persistence dependencies justify a separate feature boundary.

Both are reassessed after the stronger context APIs exist. This avoids creating small modules merely to improve the module count.

## Transitional adapter contexts

`com.taxonomy.dsl.storage..` / `com.taxonomy.dsl.export..` and the Spring-aware `com.taxonomy.export.service..` / `com.taxonomy.export.controller..` packages remain explicit transitional contexts while their owning ports settle.

They are **not** the seed of a generic `taxonomy-adapters` module. Each adapter should ultimately live with the bounded context whose port it implements. The framework-free modules continue to reject Spring, JPA and application-module dependencies.

## `taxonomy-app` target role

After the feature contexts are extracted, `taxonomy-app` remains the executable composition root. The context map currently classifies `observability`, `security`, and `shared` as `app-composition`; the root `AppConfig` and `TaxonomyApplication` classes are composition classes as well.

The long-term application module should contain only genuinely application-wide responsibilities such as:

- Spring Boot assembly and cross-context wiring;
- global security/authentication/request identity;
- top-level configuration;
- global exception handling and observability/health aggregation;
- genuinely cross-context MVC orchestration that has no single feature owner;
- final packaging and application resources.

A package named `shared` is not automatically a module boundary. Shared classes must move to the lowest stable owner or remain composition concerns when they are truly application-wide.

## Dependency fitness functions

### Hypothesis authority (C2 of #1043)

Hypothesis lifecycle, Git-authoritative review, review-state storage and the
`/api/dsl/hypotheses/**` HTTP adapters are owned by `relations`. The remaining
`DslApiController` does not call hypothesis services. Legacy `WorkspaceContext`
arguments are translated by the workspace-owned `WorkspaceRepositoryContextPort`;
an explicitly selected repository is retained, workspace provenance mismatches
fail before review or branch access, and central contexts remain read-only.

Hypothesis services consume workspace APIs for repository context, exact Git
reads/commands and generated DSL publication. They do not depend on workspace
entities/repositories, concrete DSL storage or JGit. Semantic review and
transaction callbacks remain relation responsibilities; generated-snapshot
publication remains separate from expected-head commands and editor checkpoints.

The measured C2 baseline reduces cross-context class pairs from 559 to 540.
`versioning.service -> catalog/relations` and
`versioning.controller -> relations` are now zero. Remaining workspace-to-feature
coupling is still explicit: `DecisionRationaleReportController` coordinates
catalogue/architecture reports, while the DSL operations facades coordinate
architecture documents/history with workspace state. These require the separate
C2-following orchestration slice before physical extraction. No cycle exception
was added or expanded.

The migration uses two complementary protections:

1. `ArchitectureCycleBoundaryTest` rejects undocumented package cycles. Temporary exceptions must exist in `.github/architecture-exceptions.json` and expire.
2. `ArchitectureContextDependencyRatchetTest` records distinct direct class-to-class dependencies between planned, residual and transitional contexts per package pair. New edges or increased counts fail. When refactoring removes dependencies, the lower baseline must be committed in the same change so the improvement cannot regress silently.

The ratchet also walks `taxonomy-app/src/main/java/com/taxonomy`: every production Java package below the root package must be classified in `.github/architecture-contexts.json`. A new feature package therefore cannot evade the dependency guard merely by being created outside the existing context patterns. Root-level composition classes remain explicitly permitted.

The ratchet is intentionally a **current-state baseline, not an ideal-direction allowlist**. Architectural direction is improved by explicit port/refactoring PRs and then locked in monotonically.

## Migration order

Issue #628 is the implementation parent. The intended order is:

1. freeze the context map and dependency ratchet;
2. remove concrete editor/workspace/versioning coupling and expose narrow ports;
3. remove knowledge/search package cycles;
4. remove architecture/export/analysis cycles;
5. extract `taxonomy-workspace`;
6. extract `taxonomy-knowledge`;
7. extract `taxonomy-interop` and `taxonomy-templates` in independently reviewable changes;
8. extract architecture/analysis/portfolio only after their dependency direction is stable;
9. reassess `provenance` and `preferences` from the measured dependency graph rather than module-count aesthetics.

Physical Maven moves therefore come **after** logical boundaries are enforceable. This keeps each PR reviewable and avoids hiding existing coupling behind new POM dependencies.
