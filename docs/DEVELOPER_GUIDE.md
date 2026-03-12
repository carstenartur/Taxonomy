# Taxonomy Architecture Analyzer — Developer Guide

This guide is intended for developers contributing to the Taxonomy Architecture Analyzer.

---

## Table of Contents

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

---

## Quick Start

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# Compile all 4 modules (~5 seconds)
mvn compile

# Run all tests (~60 seconds, 667 tests, no Docker needed)
mvn test

# Run including integration tests (requires Docker)
mvn verify

# Start locally (browse-only, no API key needed)
mvn -pl taxonomy-app spring-boot:run
```

Open <http://localhost:8080> to see the application.

---

## Module Architecture

The project is a multi-module Maven build with four modules:

```
Taxonomy/
├── taxonomy-domain/     Pure domain types (DTOs, enums) — no framework dependencies
├── taxonomy-dsl/        Architecture DSL (parser, model, validator, differ) — no framework dependencies
├── taxonomy-export/     Export services (ArchiMate, Visio, Mermaid) — no framework dependencies
└── taxonomy-app/        Spring Boot application (controllers, services, JPA, search, storage)
```

**Dependency graph:**

```
taxonomy-app  →  taxonomy-domain
taxonomy-app  →  taxonomy-dsl
taxonomy-app  →  taxonomy-export
taxonomy-export  →  taxonomy-domain
```

- `taxonomy-domain`, `taxonomy-dsl`, and `taxonomy-export` have **no Spring dependencies** and can be tested independently.
- The Spring Boot JAR is produced by `taxonomy-app`.

---

## Module Responsibilities

### taxonomy-domain

Pure data types shared across modules:

| Package | Contents |
|---|---|
| `com.taxonomy.dto` | 44 DTOs (Data Transfer Objects) — `TaxonomyNodeDto`, `AnalysisResult`, `ArchitectureRecommendation`, `GapAnalysisView`, `SavedAnalysis`, etc. |
| `com.taxonomy.model` | 3 domain enums — `RelationType` (10 values), `HypothesisStatus`, `ProposalStatus` |

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

The main Spring Boot application:

| Directory | Contents |
|---|---|
| `controller/` | 14 REST controllers (~74 endpoints) |
| `service/` | 38 service classes — LLM, search, architecture, graph, proposals, reports, etc. |
| `model/` | 9 JPA entities — `TaxonomyNode`, `TaxonomyRelation`, `RelationProposal`, `RelationHypothesis`, etc. |
| `repository/` | 8 Spring Data JPA repositories |
| `config/` | 11 configuration classes — rate limiting, Hibernate Search analysers, OpenAPI, actuator security |
| `search/` | Hibernate Search configuration |
| `dsl/storage/` | JGit DFS storage backed by Hibernate — `DslGitRepository`, `HibernateRepository`, etc. |
| `dsl/storage/jgit/` | JPA entities for Git storage — `GitPackEntity`, `GitReflogEntity` |
| `resources/data/` | Excel workbook, CSV fallback, JSON taxonomy |
| `resources/prompts/` | 10 LLM prompt templates |
| `resources/static/js/` | 11 JavaScript modules (6,600 lines) |
| `resources/templates/` | Single Thymeleaf template (`index.html`) |

---

## Where to Make Changes

| I want to… | Where to look |
|---|---|
| Add a new taxonomy endpoint | `taxonomy-app/.../controller/` — create or extend a `@RestController` |
| Add a new service | `taxonomy-app/.../service/` — create a `@Service` class |
| Add a new export format | `taxonomy-export/.../export/` — implement the exporter, register in `ExportConfig` |
| Add a new JPA entity | `taxonomy-app/.../model/` — annotate with `@Entity` |
| Add a new DTO | `taxonomy-domain/.../dto/` — create a record or class |
| Change the DSL grammar | `taxonomy-dsl/.../parser/TaxDslParser.java` |
| Add a DSL validation rule | `taxonomy-dsl/.../validation/DslValidator.java` |
| Change the UI | `taxonomy-app/src/main/resources/templates/index.html` + `static/js/` |
| Add a new LLM prompt | `taxonomy-app/src/main/resources/prompts/` — create `XX.txt` |
| Change configuration | `taxonomy-app/src/main/resources/application.properties` |

---

## Running Tests

```bash
# All unit + Spring context tests (no Docker needed)
mvn test

# Tests for a single module
mvn test -pl taxonomy-dsl
mvn test -pl taxonomy-app

# Tests for a single class
mvn test -pl taxonomy-app -Dtest=TaxonomyApplicationTests

# Integration tests (requires Docker for Testcontainers)
mvn verify

# Screenshot generation (requires Docker + optionally GEMINI_API_KEY)
mvn package -DskipTests
mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT
```

Test file naming conventions:

| Pattern | Runner | Description |
|---|---|---|
| `*Test.java`, `*Tests.java` | maven-surefire-plugin | Unit and Spring context tests |
| `*IT.java` | maven-failsafe-plugin | Integration tests (require Docker) |

---

## Adding a New REST Endpoint

1. Create or extend a controller in `taxonomy-app/src/main/java/com/taxonomy/controller/`.
2. If the endpoint returns a new DTO, add it to `taxonomy-domain/src/main/java/com/taxonomy/dto/`.
3. If the endpoint needs a new service, add it to `taxonomy-app/src/main/java/com/taxonomy/service/`.
4. Add tests (typically using `@SpringBootTest` + `@AutoConfigureMockMvc`).
5. If the endpoint is admin-only, check for the `X-Admin-Token` header (see `ApiController.getDiagnostics` for an example).

---

## Adding a New Export Format

1. Add the exporter class to `taxonomy-export/src/main/java/com/taxonomy/export/`.
2. Register it as a Spring bean in `taxonomy-app/src/main/java/com/taxonomy/config/ExportConfig.java`.
3. Add a controller endpoint in `ApiController` or create a new controller.
4. Add tests in `taxonomy-app/src/test/`.

---

## Adding a New LLM Provider

The `LlmService` supports multiple LLM providers. To add a new one:

1. Add the API key property to `application.properties` (e.g., `newprovider.api.key=${NEW_PROVIDER_API_KEY:}`).
2. Add the provider name to the auto-detection logic in `LlmService`.
3. Implement the HTTP call in `LlmService` following the pattern of existing providers.
4. Update `LlmResponseParser` if the response format differs.
5. Document the new provider in `CONFIGURATION_REFERENCE.md`.

---

## DSL and JGit Storage

The Architecture DSL subsystem uses JGit DFS (Distributed File System) with all Git objects stored in the HSQLDB database — no filesystem is used.

**Key classes:**

| Class | Role |
|---|---|
| `DslGitRepository` | Facade for commit, read, branch, diff operations |
| `HibernateObjDatabase` | Stores Git pack data as BLOBs in `git_packs` table |
| `HibernateRefDatabase` | Stores Git refs as reftables in pack extensions |
| `HibernateRepository` | Extends JGit `DfsRepository` with database backends |
| `GitPackEntity` | JPA entity for the `git_packs` table |
| `GitReflogEntity` | JPA entity for the `git_reflog` table |

**Important:** `DfsBlockCache` is a JVM-global singleton. Pack names **must** be unique across all `HibernateObjDatabase` instances in the same JVM. The `packIdCounter` is per-instance and initialised with `System.nanoTime()` to avoid collisions during test context restarts.

---

## Hibernate Search and Lucene

The application uses Hibernate Search 8 with a Lucene 9 backend for full-text and KNN semantic search.

**Custom analysers** (registered in `HibernateSearchAnalysisConfigurer`):

| Analyser | Purpose |
|---|---|
| `dsl` | WhitespaceTokenizer + LowerCaseFilter — for `STRUCT:` / `REL:` / `DOM:` prefixed DSL tokens |
| `csv-keyword` | PatternTokenizer splitting on comma/semicolon — for element and relation ID fields |
| `english` | Standard English analyser for commit messages |
| `german` | Standard German analyser for German-language fields |

**Indexed entities:** `TaxonomyNode` (full-text + KNN), `TaxonomyRelation` (full-text + KNN), `ArchitectureCommitIndex` (full-text).

---

## Testing Conventions

- Tests use `@SpringBootTest` with HSQLDB in-memory and `@AutoConfigureMockMvc`.
- DSL module tests (`taxonomy-dsl`) are pure JUnit 5 — no Spring context.
- JGit storage tests (`DslGitRepositoryTest`) are pure JUnit 5 with database-backed `HibernateRepository`.
- Integration tests requiring Docker follow the `*IT.java` naming pattern.
- Tests **never** call real LLM APIs — use `LlmService` mocking instead.

---

## Common Pitfalls

1. **HSQLDB NCLOB limitation:** `@Nationalized @Lob` fields are mapped to `NCLOB` on HSQLDB. JPQL functions like `LOWER()` cannot be applied to NCLOB columns. Use `@Column(length=N)` without `@Lob` for fields that need JPQL string functions.

2. **JGit DfsBlockCache collisions:** The cache is a JVM-global singleton keyed by `(repository name + pack name)`. If pack names collide (e.g., from static counters), stale cache entries cause `REJECTED_MISSING_OBJECT` errors. Always use per-instance counters.

3. **Spring test context caching:** Spring caches `@SpringBootTest` contexts for reuse. Combined with `ddl-auto=create`, table recreation can invalidate state in beans from older contexts. Beans holding internal state tied to DB content must handle this gracefully.

4. **SimpleDriverDataSource:** The application uses `SimpleDriverDataSource` instead of HikariCP for in-process HSQLDB. This means no connection pool — which is intentional for reduced memory usage. Do not switch to HikariCP without understanding the implications.

5. **Rate limiting in tests:** The `RateLimitFilter` is active during `@SpringBootTest` tests. If tests hit LLM-backed endpoints rapidly, they may be rate-limited. Consider disabling via `TAXONOMY_RATE_LIMIT_PER_MINUTE=0` in test configuration if needed.
