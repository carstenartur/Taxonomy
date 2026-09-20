# Module and Bounded-Context Boundaries

This is the **current implementation inventory**, not a list of planned extractions.
Taxonomy remains a modular monolith with one deployable Spring Boot application.
The seven feature libraries listed below have already been extracted. The former
step-by-step account is retained separately as [historical extraction evidence](MODULE_BOUNDARIES_HISTORY.md).
Its intermediate blockers, counts, and issue status describe past checkpoints, not today's backlog.

## Current Maven reactor

| Module | Group | Current responsibility |
|---|---|---|
| `taxonomy-domain` | Foundation | Framework-free shared architecture/analysis contracts |
| `taxonomy-dsl` | Foundation | Framework-free TaxDSL syntax, model, validation, mapping, diff and commands |
| `taxonomy-export` | Foundation | Framework-free export contracts, codecs and neutral rendering |
| `taxonomy-extension-api` | Foundation | Framework-free extension contracts and metadata |
| `taxonomy-workspace` | Feature | Workspace/repository identity, editor journal, undo/redo, Git checkpoints and storage |
| `taxonomy-knowledge` | Feature | Catalogue/seeds, relations/hypotheses, search, indexes and local embeddings |
| `taxonomy-templates` | Feature | Template versions, OOXML validation, materialization, WebDAV and administration |
| `taxonomy-interop` | Feature | Reviewed exchanges, mappings, connector orchestration and synchronization checkpoints |
| `taxonomy-architecture` | Feature | Derivation, scoring, gaps, patterns, recommendations, diagrams and reports |
| `taxonomy-analysis` | Feature | Requirement/LLM analysis, provider policy, prompts, parsing and sessions |
| `taxonomy-portfolio` | Feature | Projects, versioned requirements, jobs/results/reviews, recovery and snapshots |
| `taxonomy-app` | Composition | Only executable application: wiring, cross-context adapters, security, migrations and packaging |
| `taxonomy-tooling` | Build/tooling | Build and release utilities; not a runtime feature library |
| `taxonomy-coverage` | Build/tooling | Reactor-wide coverage aggregation; not a runtime feature library |
| `taxonomy-build` | Build/tooling | Whole-reactor quality gates and browser/verification contracts |

The root aggregator is not an additional child module. The fifteen children comprise
four foundations, seven features, the composition root, and three build/tooling modules.
Only `taxonomy-app` is an executable application. The build/tooling group is not a
set of runtime services. See [the verified feature dependency graph](ARCHITECTURE.md#module-architecture)
and [runtime/persistence views](ARCHITECTURE.md).

## Current ownership contracts

### `taxonomy-knowledge`

Owns catalogue, relations, and search, including seeds, search mappings/analyzers,
and the embedding lifecycle. Framework import parsers/registry are knowledge-owned;
cross-context materialization adapters live in application composition. Internal
package coupling is not an excuse for a reverse dependency on the executable application.

### `taxonomy-workspace`

Owns workspace, versioning, and editor state together, including the durable semantic
operation journal, undo/redo, checkpoint preparation/publication, repository context,
and JGit/Hibernate storage. **Accepted semantic operations are durable revisions;
Git commits are explicit stable checkpoints, not the operation log.**

### `taxonomy-architecture`

Owns architecture derivation, scores, gaps, patterns, recommendations, diagram
preparation, and reports. Live report preferences enter through `ArchitectureReportMetadataPort`.
Report HTTP composition and repository/workspace lookup remain application-owned;
this library must not acquire an application `WorkspaceResolver` dependency.

### `taxonomy-analysis`

Owns requirement/LLM analysis, prompts, provider policy, response parsing, sessions,
and analysis abstractions. Stateful cross-context access uses explicit ports. The
library, not `taxonomy-app`, owns its unit tests and feature resources.

### `taxonomy-portfolio`

Owns projects, versioned requirements, persisted analysis work/results/review state,
queues/recovery, and workbench snapshots. It coordinates feature capabilities through
their APIs, not their repositories. Cross-context acceptance remains application-owned.

### `taxonomy-interop`

Owns reviewed external-tool operations, mappings, checkpoints/events, and connector
orchestration. Portfolio access crosses an explicit port with an application adapter;
interop must not depend on the portfolio implementation or concrete editor services.

### `taxonomy-templates`

Owns the separate template Git repository, OOXML safety/validation, materialization,
WebDAV projection/locking, and administration/health contracts. It does not depend on
another Taxonomy feature library. Template storage is not the editor's semantic journal.

## Residual contexts that are deliberately not modules yet

Provenance/document ingestion and preferences remain application-local. Their
ownership is visible in the context map; no separate module is claimed. Transitional
DSL/export adapter contexts remain explicitly classified until their feature ports
settle. There is no generic catch-all adapters module.

## `taxonomy-app` target role

The application is the **current** composition/deployment root. It owns wiring,
security/request identity, observability, global configuration/errors, cross-context
HTTP/UI adapters, migration composition, and final packaging. Supporting provenance
and preferences still reside here; this is a documented boundary, not a claim that
the executable module already contains only wiring.

## Dependency fitness functions

The authoritative machine-readable inputs remain [the context map](../../.github/architecture-contexts.json),
[exception ledger](../../.github/architecture-exceptions.json), and
[dependency baseline](../../.github/architecture-dependency-baseline.json), together
with the actual POMs and production classes. The documents do not introduce another
architecture policy or regenerate the dependency baseline.

`taxonomy-build` owns the existing module graph/extraction checks; Maven boundary
rules and application packaging checks enforce dependency direction, unique runtime
classes/resources, and application-owned migrations. Owned unit tests reside with
features; cross-context, database, security, and recovery tests remain with the application.

`ArchitectureDocumentationTest` in the ordinary full-reactor suite reuses the module
gate's reactor discovery and production POM reader. It checks this inventory, the
German inventory, the README inventory, and both marked feature graphs. Scope is
structural consistency, not proof of every prose statement or runtime behavior.

## Historical extraction evidence

The [former detailed account](MODULE_BOUNDARIES_HISTORY.md) is preserved byte-for-byte
from the pre-reorganization version so that individual migration evidence is not lost.
Treat its “planned” headings, changing class-pair counts, and completion statements
as historical. The current ownership summary above takes precedence for orientation.
The [module extraction completion contract](../dev/MODULE_EXTRACTION_COMPLETION.md)
and [gate documentation](../dev/MODULE_EXTRACTION_GATE.md) retain their own detailed evidence.

[Architecture](ARCHITECTURE.md) · [Deutsch](../de/MODULE_BOUNDARIES.md)
