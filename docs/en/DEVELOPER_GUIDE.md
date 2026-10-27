# Taxonomy Architecture Analyzer — Developer Guide

This guide is intended for developers contributing to the Taxonomy Architecture Analyzer.

> **New to the codebase?**
> Start with the [task-oriented entry point](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/00-start-here.md)
> to find the smallest safe change area for your specific task.
> The present document is the **deep architecture reference**.

---

## Table of Contents

- [Task-Oriented Guide (docs/dev/)](#task-oriented-guide)
- [Extension Points](#extension-points)
- [Quick Start](#quick-start)
- [Module Architecture](#module-architecture)
- [Module Responsibilities](#module-responsibilities)
- [Where to Make Changes](#where-to-make-changes)
- [Running Tests](#running-tests)
- [Adding a New REST Endpoint](#adding-a-new-rest-endpoint)
- [Adding a New Export Format](#adding-a-new-export-format)
- [Adding a New LLM Provider](#adding-a-new-llm-provider)
- [DSL and JGit Storage](#dsl-and-jgit-storage)
- [Hibernate Search and Lucene](#hibernate-search-and-lucene)
- [Testing Conventions](#testing-conventions)
- [Common Pitfalls](#common-pitfalls)
- [Architecture Conventions](#architecture-conventions)
- [Definition of Done — User-Facing Features](#definition-of-done--user-facing-features)
- [Maintainability Matrix](#maintainability-matrix)

---

## Task-Oriented Guide

The `docs/dev/` directory provides a task-first map for developers who need to
modify one specific part of the system without reading the full architecture reference.

| File | Purpose |
|---|---|
| [`docs/dev/00-start-here.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/00-start-here.md) | How to approach a single-task change |
| [`docs/dev/01-change-map.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/01-change-map.md) | Table mapping tasks to packages, entry points, and tests |
| [`docs/dev/07-extension-points.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/07-extension-points.md) | Stable extension anchors for common feature additions |
| [`docs/dev/06-testing-by-change-type.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/06-testing-by-change-type.md) | Maven commands and test classes by change type |
| [`docs/dev/tasks/add-llm-provider.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-llm-provider.md) | Add a new LLM provider |
| [`docs/dev/tasks/add-export-format.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-export-format.md) | Add a new export format |
| [`docs/dev/tasks/add-relation-type.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-relation-type.md) | Add a new relation type |
| [`docs/dev/tasks/add-architecture-view-step.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-architecture-view-step.md) | Add an architecture view pipeline step |
| [`docs/dev/tasks/add-document-import-mapping.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-document-import-mapping.md) | Add a document import mapping |
| [`docs/dev/tasks/add-workspace-operation.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-workspace-operation.md) | Add a workspace operation |
| [`docs/dev/tasks/add-ui-panel.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-ui-panel.md) | Add a UI panel |
| [`docs/dev/tasks/add-dsl-property.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/tasks/add-dsl-property.md) | Add a DSL property or block type |

> The `docs/dev/` directory is the **task-oriented entry point**.
> The sections below remain the **deep reference** for modules, conventions, pitfalls,
> and architectural decisions.

---

## Extension Points

Before adding a provider, exporter, relation type, pipeline step, or DSL
surface area, check the dedicated extension guide first:

- [`docs/dev/07-extension-points.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/07-extension-points.md)

It distinguishes between:

- **explicit SPIs** (registries/interfaces already exist), and
- **implicit but documented anchors** (the current codebase has a stable facade,
  service, or file boundary, but not a registry).

Use the extension guide to find the smallest safe starting point before reading
the deeper architecture sections below.

---

## Quick Start

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# Bounded developer verification
./mvnw verify

# Canonical CI verification (see its documented runtime prerequisites)
./mvnw -B verify -Pci -DrunOnnxTests=true

# Package the application and needed libraries, then start from the repository root
./mvnw -pl taxonomy-app -am package -DskipTests
java -Dloader.path=taxonomy-app/target/features -jar taxonomy-app/target/taxonomy-app-*.jar --taxonomy.plugins.directory=taxonomy-app/target/plugins
```

Open <http://localhost:8080>. Catalogue browsing requires no LLM key.
Follow the [README bootstrap instructions](https://github.com/carstenartur/Taxonomy/blob/main/README.md#quick-start) for the
local administrator's initial sign-in.

---

## Module Architecture

The complete reactor inventory and ownership contracts are maintained in
[Module boundaries](MODULE_BOUNDARIES.md). [Architecture](ARCHITECTURE.md#module-architecture)
shows the feature dependency graph and separate runtime/persistence views.

The reactor separates framework-free contracts, runtime features, the plugin
runtime, an independent Mermaid plugin, application composition and build tooling.
Feature libraries do not depend back on `taxonomy-app`. Reporting models belong
to `taxonomy-reporting-api`, renderers to `taxonomy-reporting`, and generic template
contracts to `taxonomy-templates-api`.

`taxonomy-app` produces the executable application. The default local mode uses one
process; optional Artemis execution uses the same image for coordinator and worker
processes. See [analysis execution](ARCHITECTURE.md#analysis-execution-and-deployment).
This does not make every web/background feature cluster-safe. Build/release tooling,
aggregate coverage and repository-wide checks are not runtime feature services.

---

## Module Responsibilities

### taxonomy-domain

Pure data types shared across modules:

| Package | Contents |
|---|---|
| `com.taxonomy.dto` | DTOs (Data Transfer Objects) — `TaxonomyNodeDto`, `AnalysisResult`, `ArchitectureRecommendation`, `GapAnalysisView`, `SavedAnalysis`, etc. |
| `com.taxonomy.model` | Domain enums and scope values, including `RelationType`, `SeedType`, `HypothesisStatus`, `ProposalStatus`, `SourceType`, `LinkType` and `WorkspaceOverlayScope` |

### taxonomy-dsl

Framework-free Architecture DSL engine:

| Package | Contents |
|---|---|
| `com.taxonomy.dsl.ast` | Abstract syntax tree records — `BlockAst`, `PropertyAst`, `MetaAst`, `SourceLocation` |
| `com.taxonomy.dsl.model` | Canonical architecture model — `CanonicalArchitectureModel`, `ArchitectureElement`, `ArchitectureRelation`, `ArchitectureView`, etc. |
| `com.taxonomy.dsl.parser` | `TaxDslParser` (DSL text → AST → model), `DslTokenizer` (tokenises DSL text for indexing) |
| `com.taxonomy.dsl.serializer` | `TaxDslSerializer` (model → DSL text) |
| `com.taxonomy.dsl.mapper` | `AstToModelMapper`, `ModelToAstMapper` — bidirectional AST ↔ model conversion |
| `com.taxonomy.dsl.validation` | `DslValidator` — semantic validation including relation type-combination compatibility |
| `com.taxonomy.dsl.diff` | `ModelDiffer` — semantic diff between two `CanonicalArchitectureModel` instances |

### taxonomy-export

Export services — framework-free, wired as Spring beans by `ExportConfig`:

| Package | Contents |
|---|---|
| `com.taxonomy.export` | `ArchiMateDiagramService`, `VisioDiagramService`, `MermaidExportService`, `DiagramProjectionService` |
| `com.taxonomy.archimate` | ArchiMate model records — `ArchiMateModel`, `ArchiMateElement`, `ArchiMateRelationship`, etc. |
| `com.taxonomy.visio` | Visio document model + XStream converters |
| `com.taxonomy.diagram` | Neutral diagram model records — `DiagramModel`, `DiagramNode`, `DiagramEdge`, `DiagramLayout` |

### taxonomy-app

The sole executable composition and deployment root, not the owner of all
Spring-aware feature code. Feature controllers, services, entities, repositories
and owned tests belong to the feature library identified in
[Module boundaries](MODULE_BOUNDARIES.md). For example, catalogue/search belongs
to `taxonomy-knowledge`; prompts and LLM analysis to `taxonomy-analysis`;
versioning, editor history and JGit storage to `taxonomy-workspace`.

| Application-owned area | Responsibility |
|---|---|
| `com.taxonomy.composition` | Cross-context HTTP/UI orchestration and port adapters |
| `com.taxonomy.security`, `com.taxonomy.observability`, `com.taxonomy.shared` | Application-wide security, observability and shared composition |
| `com.taxonomy.provenance`, `com.taxonomy.preferences` | Supporting contexts deliberately retained in the application |
| `src/main/resources/static/`, `src/main/resources/templates/` | Browser assets and Thymeleaf pages |
| `src/main/resources/application.properties` | Application configuration |
| Application database migrations | Application-owned schema composition; storage-library schema contracts remain separately owned |

Java package names were generally retained during module extraction. A package
such as `com.taxonomy.versioning` does not imply physical ownership by
`taxonomy-app`; locate it in the owning Maven module before editing.

---

## Where to Make Changes

| I want to… | Where to look |
|---|---|
| Add a new taxonomy endpoint | `taxonomy-knowledge/.../catalog/` — create or extend the catalogue-owned controller; use application composition only for cross-context orchestration |
| Add a new service | The owning feature library from [Module boundaries](MODULE_BOUNDARIES.md); use a framework-free type when Spring is unnecessary |
| Add a new export format | [Export task](../dev/tasks/add-export-format.md): framework-free SPI, built-in adapter or independent plugin |
| Add a new JPA entity | The owning feature library; keep application-wide migration composition in `taxonomy-app` |
| Add a new DTO | `taxonomy-domain/.../dto/` — create a record or class |
| Change the DSL grammar | `taxonomy-dsl/.../parser/TaxDslParser.java` |
| Add a DSL validation rule | `taxonomy-dsl/.../validation/DslValidator.java` |
| Change the UI | `taxonomy-app/src/main/resources/templates/index.html` + `static/js/` |
| Add a new LLM prompt | `taxonomy-analysis/src/main/resources/prompts/` — create `XX.txt` |
| Change configuration | `taxonomy-app/src/main/resources/application.properties` |

---

## Running Tests

The checked-in Maven Wrapper is the only build authority. The default lifecycle
is intentionally bounded; the `ci` profile activates Docker-backed integration,
quality gates and the browser/accessibility matrix.

```bash
./mvnw verify
./mvnw test -pl taxonomy-dsl -am
./mvnw test -pl taxonomy-app -am -Dtest=TaxonomyApplicationTests -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B verify -Pcore-integration
./mvnw -B verify -Pdatabase-postgres
./mvnw -B verify -Pdatabase-mssql
./mvnw -B verify -Pdatabase-oracle
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true
./mvnw -B verify -Pci -DrunOnnxTests=true
```

The exact suite scope, prerequisites, browser-profile reproduction and remaining
workflow responsibilities are documented in
[`docs/dev/MAVEN_VERIFICATION.md`](../dev/MAVEN_VERIFICATION.md). Test selection
belongs in a POM profile; workflow YAML may select an environment/profile but
must not name Java tests or invoke Playwright/axe scripts directly.

## Adding a New REST Endpoint

1. Select the owning feature library using [Module boundaries](MODULE_BOUNDARIES.md), then create or extend its controller. Only cross-context HTTP orchestration belongs in `taxonomy-app/src/main/java/com/taxonomy/composition/`.
2. If the endpoint returns a new DTO, add it to `taxonomy-domain/src/main/java/com/taxonomy/dto/`.
3. Put the service and its owned tests in the same feature context; cross-context access uses its public API or an explicit port.
4. Keep service tests at the lowest useful JUnit layer; use HTTP/security integration tests for endpoint behavior and the relevant roles.
5. If the endpoint is admin-only, add it under `/api/admin/` (protected by `ROLE_ADMIN` in `SecurityConfig`).
6. If the endpoint modifies architecture data (relations, DSL), place it under `/api/relations/`, `/api/dsl/`, or `/api/git/` — write operations on these paths require `ROLE_ARCHITECT` or `ROLE_ADMIN`.

---

## Adding a New Export Format

For a built-in format, implement framework-free conversion in `taxonomy-export`
and register the application adapter through the existing export SPI. For an
independently installable format, implement `ExportFormatExtension` in a separate
JAR and expose it through a `TaxonomyPlugin` ServiceLoader entry. Both use the shared
catalog and `POST /api/diagram/export/{formatId}`.

Follow [the export task](../dev/tasks/add-export-format.md) for packaging, artifact
identity, lifecycle and HTTP verification. Report renderers and diagram export
formats have different contracts; do not route every report through a diagram codec.

---

## Adding a New LLM Provider

First check whether the existing `CUSTOM_OPENAI` configuration supports the endpoint.
For a new transport, follow [the provider task](../dev/tasks/add-llm-provider.md):
use an open `ProviderId`, implement `LlmTransportExtension` and expose it through a
startup-bound `TaxonomyPlugin` ServiceLoader entry in an independent Java 25 JAR.
Changing the built-in `LlmProvider` enum is not required for an external provider.

The host in `taxonomy-analysis` retains selection, admission, retries, cancellation,
metering and frozen provider identity. A contributed transport performs one attempt.
Document configuration, capabilities and recovery limitations; do not add protocol
branches directly to `LlmService` or infer token bounds from compatible HTTP syntax.

---

## DSL and JGit Storage

The Architecture DSL subsystem uses JGit DFS with Git objects and refs stored in
the configured relational database through `jgit-storage-hibernate`. HSQLDB is the
default, not the only backend. `taxonomy-workspace` owns the repository facade and
editor journal. Accepted semantic operations are durable working revisions; explicit
Git checkpoints record stable DSL versions. Immutable analysis snapshots have their
own identity and lifecycle. See [persistence responsibilities](ARCHITECTURE.md#dsl-storage-architecture).

**DSL module (`taxonomy-dsl`)** — Spring-independent library:

| Package | Role |
|---|---|
| `dsl.parser` | Brace-delimited parser: DSL text → `DocumentAst` |
| `dsl.serializer` | Deterministic serializer: `DocumentAst` → DSL text (sorted blocks, canonical property order, escape sequences) |
| `dsl.ast` | AST node types: `DocumentAst`, `BlockAst`, `PropertyAst`, `MetaAst` |
| `dsl.model` | Canonical model: `CanonicalArchitectureModel`, `ArchitectureElement`, `ArchitectureRelation`, etc. |
| `dsl.mapper` | Bidirectional mapping: AST ↔ canonical model |
| `dsl.validation` | Validation: duplicate IDs, reference integrity, relation type compatibility matrix |
| `dsl.diff` | Semantic diff: `ModelDiffer` compares two models; `SemanticDiffDescriber` generates human-readable descriptions |

**Serialization guarantees** (critical for Git-friendly diffs):

- **Block ordering**: Sorted by kind (element → relation → requirement → mapping → view → evidence), then by primary ID
- **Property ordering**: Canonical order per block kind (title → description → taxonomy for elements, etc.)
- **Extensions**: Sorted alphabetically after known properties
- **Escape sequences**: `\"` and `\\` in quoted values
- **Round-trip stability**: `parse → serialize → parse → serialize` always produces identical output

**JGit integration classes:**

| Class | Role |
|---|---|
| `DslGitRepository` | Facade for commit, read, branch, diff operations |
| `HibernateObjDatabase` | Stores Git pack data as BLOBs in `git_packs` table |
| `HibernateRefDatabase` | Stores Git refs as reftables in pack extensions |
| `HibernateRepository` | Extends JGit `DfsRepository` with database backends |
| `GitPackEntity` | JPA entity for the `git_packs` table |
| `GitReflogEntity` | JPA entity for the `git_reflog` table |

**Storage-library boundary:** `DfsBlockCache` is JVM-global. Repository/pack identities
must remain distinct, but their implementation and schema belong to the released
storage adapter. Do not copy an old counter implementation or recreate library
migrations in Taxonomy. See [JGit storage integration](JGIT_STORAGE_HIBERNATE.md).

### Source Provenance Architecture

The DSL supports **provenance blocks** that link requirements to their original
source materials.  The provenance model consists of three layers:

1. **DSL Layer** (`taxonomy-dsl`): Four new block types — `source`,
   `sourceVersion`, `sourceFragment`, `requirementSourceLink` — are parsed and
   serialized just like `element` or `relation` blocks.

2. **JSON Export Layer** (`taxonomy-domain`): `SavedAnalysis` (version 2) can
   optionally include `sources`, `sourceVersions`, `sourceFragments`, and
   `requirementSourceLinks`.

3. **Runtime/DB Layer** (`taxonomy-app`): JPA entities `SourceArtifact`,
   `SourceVersion`, `SourceFragment`, and `RequirementSourceLink` store
   provenance data in the database.

When adding new provenance features:

- Register new block types in `TaxDslParser.KNOWN_BLOCK_TYPES`
- Add model classes in `com.taxonomy.dsl.model`
- Extend `AstToModelMapper` and `ModelToAstMapper`
- Add property ordering in `TaxDslSerializer.PROPERTY_ORDER`
- Register tokens in `DslTokenizer.STRUCTURE_TOKENS`

---

## Hibernate Search and Lucene

The application uses Hibernate Search 8 with a Lucene 9 backend for full-text and KNN semantic search.

**Custom analysers** (registered in `HibernateSearchAnalysisConfigurer`):

| Analyser | Purpose |
|---|---|
| `dsl` | WhitespaceTokenizer + LowerCaseFilter — for DSL block keywords and property tokens |
| `csv-keyword` | PatternTokenizer splitting on comma/semicolon — for element and relation ID fields |
| `english` | Standard English analyser for commit messages |
| `german` | Standard German analyser for German-language fields |

**Indexed entities:** `TaxonomyNode` (full-text + KNN), `TaxonomyRelation` (full-text + KNN), `ArchitectureCommitIndex` (full-text).

---

## Testing Conventions

- Feature unit tests use JUnit without a Spring context when possible; HTTP/database acceptance uses the existing Spring/HSQLDB fixtures.
- DSL module tests (`taxonomy-dsl`) are pure JUnit 5 — no Spring context.
- JGit storage tests (`DslGitRepositoryTest`) are pure JUnit 5 with database-backed `HibernateRepository`.
- Surefire owns `*Test`/`*Tests`; Failsafe owns `*IT` in selected profiles. Tags and fixtures determine Docker/browser prerequisites, not the suffix alone.
- Default tests use deterministic doubles or authored HTTP playback. Live-model comparison requires the explicit opt-in `reformulation-real-llm` profile and its configured provider.

### Test Security Setup

Spring Boot 4 does **not** auto-apply `SecurityMockMvcConfigurers.springSecurity()` to MockMvc. The project provides `TestSecuritySupport` (in test sources) which registers a `MockMvcBuilderCustomizer` bean to:

1. Apply the Spring Security filter chain to all MockMvc requests.
2. Auto-apply CSRF tokens to test requests.

All MockMvc test classes must use `@WithMockUser(roles = "ADMIN")` (or a more restrictive role) to simulate an authenticated user. Without this annotation, requests will return HTTP 401/403.

```java
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class MyControllerTest {
    @Autowired
    private MockMvc mockMvc;
    // tests here
}
```

---

## Common Pitfalls

1. **HSQLDB NCLOB limitation:** `@Nationalized @Lob` fields are mapped to `NCLOB` on HSQLDB. JPQL functions like `LOWER()` cannot be applied to NCLOB columns. Use `@Column(length=N)` without `@Lob` for fields that need JPQL string functions.

2. **JGit DfsBlockCache collisions:** The cache is a JVM-global singleton keyed by `(repository name + pack name)`. If pack names collide (e.g., from static counters), stale cache entries cause `REJECTED_MISSING_OBJECT` errors. Always use per-instance counters.

3. **Spring test context caching:** Spring caches `@SpringBootTest` contexts for reuse. Combined with `ddl-auto=create`, table recreation can invalidate state in beans from older contexts. Beans holding internal state tied to DB content must handle this gracefully.

4. **SimpleDriverDataSource:** The application uses `SimpleDriverDataSource` instead of HikariCP for in-process HSQLDB. This means no connection pool — which is intentional for reduced memory usage. Do not switch to HikariCP without understanding the implications.

5. **Rate limiting in tests:** The `RateLimitFilter` is active during `@SpringBootTest` tests. If tests hit LLM-backed endpoints rapidly, they may be rate-limited. Consider disabling via `TAXONOMY_RATE_LIMIT_PER_MINUTE=0` in test configuration if needed.

---

## Architecture Conventions

These conventions ensure homogeneity (Deutschland-Stack Principle 4 — Modularity & Reuse) across the codebase.
See [Architecture Principles](ARCHITECTURE.md#architecture-principles) for the full principle mapping.

### Naming

| Scope | Convention | Example |
|---|---|---|
| REST controllers | `*Controller` or `*ApiController` | `ProposalApiController` |
| Services | `*Service` | `ArchitectureGapService` |
| JPA entities | Singular noun, PascalCase | `TaxonomyRelation` |
| DTOs | `*Dto`, `*View`, `*Result` | `GapAnalysisView` |
| Enums | PascalCase, in `taxonomy-domain` | `RelationType` |
| REST paths | `/api/{resource}` (plural, kebab-case) | `/api/relations/proposals` |

### Error Handling

- Controllers return `ResponseEntity` with appropriate HTTP status codes
- Service exceptions extend domain-specific exception classes
- LLM rate limits are caught as `LlmRateLimitException` → HTTP 429

### New Service Checklist

1. Place in the owning feature library from [Module boundaries](MODULE_BOUNDARIES.md), not automatically in `taxonomy-app`
2. Annotate with `@Service`
3. Accept `username` parameter if workspace-aware
4. Add unit test with `@SpringBootTest`
5. Document in ARCHITECTURE.md if it is a key component

### Data Ownership Model

| Entity | Owner | Mutability |
|---|---|---|
| `TaxonomyNode` | System (Excel import) | Read-only after startup |
| `TaxonomyRelation` | Workspace | Read-write, workspace-scoped |
| `RelationHypothesis` | Workspace | Transient or accepted |
| `RelationProposal` | Workspace | Lifecycle: proposed → accepted/rejected |
| DSL content | Workspace (JGit) | Versioned via Git commits |
| `ArchitectureCommitIndex` | Branch | Rebuilt on materialization |

---

## Definition of Done — User-Facing Features

> **Product Rule:** The Taxonomy Architecture Analyzer is a **GUI-first** product.
> REST/API endpoints support automation, CI integration, and admin tooling — they are
> **not** a substitute for GUI workflows intended for end users.

A user-facing feature is considered **complete** only when ALL of the following are true:

| Criterion | Required for end-user features | Required for admin/automation-only features |
|---|:---:|:---:|
| GUI flow exists in `index.html` + JS modules | ✅ | — |
| User Guide (`USER_GUIDE.md`) documents the workflow | ✅ | — |
| Screenshot(s) show healthy product state | ✅ | — |
| Embedded help / tooltip in the GUI | ✅ (where applicable) | — |
| REST endpoint exists | ✅ | ✅ |
| API_REFERENCE.md updated | ✅ | ✅ |
| Integration/unit tests exist | ✅ | ✅ |
| FEATURE_MATRIX.md row updated | ✅ | ✅ |

### What does NOT count as a completed feature:

- ❌ A REST endpoint exists, but no GUI button/dialog/panel references it
- ❌ A curl example is documented, but no UI walkthrough exists
- ❌ Swagger shows the endpoint, but the User Guide does not describe it
- ❌ A screenshot shows an error state instead of the expected healthy result

### Classification of features

| Category | Delivery expectation | Examples |
|---|---|---|
| **GUI-first (end-user)** | GUI + Docs + Help + Screenshot + REST | Analysis, Export, Tree exploration, Proposals, Compare, History |
| **API-first (automation)** | REST + API docs | Diagnostics, Embedding status, Admin user management, CI triggers |
| **Admin-only** | REST or GUI (admin panel) + API docs | User CRUD, Workspace eviction, Rate limit config |

### Bilingual UI rule (Deutsch / English)

The product UI supports both German and English users:
- All **UI labels, buttons, tooltips, and help texts** must exist in both languages
  (managed via Thymeleaf i18n or JS locale bundles)
- Documentation under `docs/en/` is written in English
- Translations are maintained in `docs/de/` (German)
- The README is English-only (international audience)
- **Validation:** When adding a new UI element, confirm both `messages.properties`
  and `messages_de.properties` (or equivalent i18n mechanism) contain the translation

### Terminology Rules

User-facing text must use domain-appropriate terms instead of raw Git terminology:

| Use this | Not this |
|----------|----------|
| "Shared Space" / "Gemeinsamer Bereich" | "central repository" |
| "My Workspace" / "Mein Arbeitsbereich" | "user repository" |
| "Variant" / "Variante" | "branch" (in user-facing contexts) |
| "Publish for Team" / "Für Team veröffentlichen" | "push" or "merge" |
| "Sync from Team" / "Vom Team synchronisieren" | "pull" or "fetch" |
| "Current Version" / "Aktuelle Version" | "HEAD" |
| "Apply Single Change" / "Einzeländerung übernehmen" | "cherry-pick" |
| "Integrate" / "Integrieren" | "merge" (in user-facing contexts) |

**Never** expose raw Git terms (`fork`, `clone`, `fetch`, `refs`, `rebase`) in the
standard user interface. These may appear in developer documentation and admin tools.

---

## Updating the README Showcase Diagram

The README Mermaid diagram in the "Architecture Impact Showcase" section is generated
deterministically by `ReadmeShowcaseTest` (no LLM call required).

After changing the analysis pipeline, diagram projection logic, or Mermaid export:

1. Run:
   ```
   ./mvnw test -pl taxonomy-app -Dtest=ReadmeShowcaseTest -Dsurefire.failIfNoSpecifiedTests=false
   ```
2. Open `taxonomy-app/target/readme-showcase.md` and copy the Mermaid block
   (everything between ` ```mermaid ` and ` ``` `) into `README.md`, replacing
   the existing block in the "Architecture Impact Showcase" section.
3. Also update the summary line above the Mermaid block (element count, layer count,
   relation count) to match the first line of `readme-showcase.md`.

`ReadmeShowcaseDriftTest` will fail in CI if the README diagram diverges from the
current `exportShowcase()` output, ensuring the diagram stays accurate.

---

## Screenshot Conventions

Screenshot ownership, current selectors, capture prerequisites and provenance rules
are maintained in [the screenshot reference](https://github.com/carstenartur/Taxonomy/blob/main/.github/copilot-ref-screenshots.md).
Use the owning scenario instead of constructing a second browser state:
`ScreenshotGeneratorIT` covers general UI, `ArchitectureWorkbenchUiIT` the persisted
workbench, and `CompleteCopilotSessionIT` the complete Copilot result.

```bash
./mvnw -B verify -Pscreenshots -DgenerateScreenshots=true
```

This full-application capture requires Docker. The existing local scenario path and
component illustrations have different prerequisites and evidence boundaries; see
the linked reference. Select representative successful or explicitly labelled
empty/error/recovery states as appropriate to the documented workflow. Never replace
an error-state illustration with a fabricated success or call a playback capture a
live-model evaluation. Inspect the affected language/viewport and update captions
and source/version evidence together with the image.

---

## Internationalization (i18n) — German / English

The product UI supports both German and English. The current i18n mechanism uses:
- Thymeleaf `th:text` with message keys for server-rendered HTML
- JavaScript locale bundles for client-side strings

### Adding a new UI string

1. Add the English string to `messages.properties` (or the JS locale bundle)
2. Add the German translation to `messages_de.properties`
3. Use the message key in the Thymeleaf template or JS module — never hard-code text
4. Verify both languages by switching the browser locale

> **User-facing features are not complete if they exist only via REST.**
> Every visible text in templates must use `th:text="#{...}"`. Every JavaScript-generated
> string must use `TaxonomyI18n.t('key')`. Hard-coded text literals are not acceptable.
> Existing tests `I18nApiControllerTest.englishAndGermanHaveSameKeys()` and
> `HelpControllerTest.everyRegisteredDocHasI18nKeys()` enforce this at CI level.

### Documentation language policy

| Content | Language |
|---|---|
| `README.md` | English |
| `docs/en/*` | English |
| `docs/de/*` | German |
| UI labels, buttons, tooltips | Both DE and EN (via i18n) |
| Error messages shown to users | Both DE and EN |
| Log messages / developer output | English only |
| Inline code comments | English only |

---

## Documentation Update Rule

> **Mandatory:** Documentation must be updated whenever any of the following change:
>
> - **User-visible behavior** — new or modified GUI flows, buttons, panels, dialogs
> - **Workspace semantics** — workspace lifecycle, provisioning, multi-user isolation, sync behavior
> - **Versioning behavior** — branching, merging, cherry-picking, conflict resolution, DSL format changes
> - **Help content** — embedded help topics, tooltips, onboarding flows
> - **REST API contracts** — new endpoints, changed request/response schemas, removed endpoints
> - **Configuration options** — new environment variables, changed defaults, deprecated settings

### What to update

| Change type | Documents to update |
|---|---|
| New GUI feature | `USER_GUIDE.md`, `FEATURE_MATRIX.md`, screenshot via `ScreenshotGeneratorIT` |
| New REST endpoint | `API_REFERENCE.md`, `CURL_EXAMPLES.md` |
| New DSL block/property | `CONCEPTS.md`, `GIT_INTEGRATION.md`, `DEVELOPER_GUIDE.md` |
| Workspace model change | `WORKSPACE_VERSIONING.md`, `CONCEPTS.md`, `REPOSITORY_TOPOLOGY.md` |
| New config variable | `CONFIGURATION_REFERENCE.md`, `DEPLOYMENT_GUIDE.md` |
| New help document | Register in `HelpController.DOC_METADATA`, add `help.toc.*` i18n keys, create `docs/en/` and `docs/de/` files |
| Any i18n-visible change | Both `messages.properties` and `messages_de.properties` |

### Enforcement

The following CI tests catch common documentation drift:

- `HelpControllerTest.everyEnglishDocFileIsRegistered()` — every `docs/en/*.md` must be in `HelpController`
- `HelpControllerTest.everyRegisteredDocHasI18nKeys()` — every registered doc must have EN + DE i18n keys
- `I18nApiControllerTest.englishAndGermanHaveSameKeys()` — EN and DE bundles must have identical key sets


---

## Maintainability Matrix

The [Maintainability Matrix](https://github.com/carstenartur/Taxonomy/blob/main/docs/internal/MAINTAINABILITY_MATRIX.md) lists every feature area with its primary backend package, frontend module, main controller and service, DTOs, test coverage flags, and cognitive-load rating. Unlike [`FEATURE_MATRIX.md`](FEATURE_MATRIX.md), it is about developer navigation and extension effort rather than product completeness.

Use it to locate the right entry points for a focused change and to understand which areas have known cross-cutting coupling.

**ArchUnit rules** — the architectural boundary rules are in
`taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java`.
Current temporary exceptions and removal conditions are documented in
[`docs/dev/08-archunit-exceptions.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/08-archunit-exceptions.md).
Rules currently enforced include:
- No circular dependencies between domain packages
- Controllers must not access repositories directly
- Services must not depend on controllers
- `taxonomy-domain`, `taxonomy-dsl` (framework-free packages), and `taxonomy-export` (framework-free packages) must be Spring-free
- `taxonomy-dsl` and `taxonomy-export` framework-free packages must not depend on `taxonomy-app` packages
- Workspace context must be resolved at request boundaries only

## Current QA entry points

Use [`docs/dev/06-testing-by-change-type.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/06-testing-by-change-type.md)
for standard, core-container, database, browser, accessibility, dependency, and
screenshot commands. CI permission boundaries are documented in
[`docs/dev/CI_SECURITY.md`](https://github.com/carstenartur/Taxonomy/blob/main/docs/dev/CI_SECURITY.md).
