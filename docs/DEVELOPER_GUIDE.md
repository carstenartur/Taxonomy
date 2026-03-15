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
- [Frontend Development](#frontend-development)
- [Testing Conventions](#testing-conventions)
- [Common Pitfalls](#common-pitfalls)

---

## Quick Start

```bash
git clone https://github.com/carstenartur/Taxonomy.git
cd Taxonomy

# Compile all 4 modules (~5 seconds)
mvn compile

# Run all tests (~60 seconds, no Docker needed)
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
| `com.taxonomy.dto` | DTOs (Data Transfer Objects) — `TaxonomyNodeDto`, `AnalysisResult`, `ArchitectureRecommendation`, `GapAnalysisView`, `SavedAnalysis`, etc. |
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
| `controller/` | REST controllers |
| `service/` | Service classes — LLM, search, architecture, graph, proposals, reports, etc. |
| `model/` | JPA entities — `TaxonomyNode`, `TaxonomyRelation`, `RelationProposal`, `RelationHypothesis`, `ArchitectureCommitIndex`, etc. |
| `repository/` | Spring Data JPA repositories |
| `config/` | Configuration classes — security, rate limiting, Hibernate Search analysers, OpenAPI, actuator |
| `search/` | Hibernate Search configuration |
| `dsl/storage/` | JGit DFS storage backed by Hibernate — `DslGitRepository`, `HibernateRepository`, etc. |
| `dsl/storage/jgit/` | JPA entities for Git storage — `GitPackEntity`, `GitReflogEntity` |
| `resources/data/` | Excel workbook, CSV fallback, JSON taxonomy |
| `resources/prompts/` | LLM prompt templates (one per taxonomy sheet + defaults) |
| `resources/static/js/` | JavaScript modules (UI logic) |
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

### External Database Integration Tests

The project includes integration tests that verify the application works correctly against PostgreSQL, Microsoft SQL Server, and Oracle databases. PostgreSQL and MSSQL tests run as part of the default `mvn verify` build (requires Docker). Oracle tests are **opt-in** — tagged with `db-oracle` and excluded by default. To run specific database tests:

```bash
# Run only PostgreSQL integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"

# Run only MSSQL integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Mssql*IT"

# Run only Oracle integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Oracle*IT"

# Run ALL external database tests
mvn verify -DexcludedGroups=real-llm

# Run all Selenium + external-db tests
mvn verify -DexcludedGroups=real-llm -Dit.test="Selenium*ContainerIT"
```

**Architecture:** Each external-database test class inherits from `AbstractDatabaseContainerIT` (REST/diagnostics tests) or `AbstractSeleniumContainerIT` (Selenium UI tests). The base classes hold all test logic; DB-specific subclasses are ~30 lines of configuration that specify the database container and the JDBC env vars to pass to the app container.

**How it works:** The application JAR is built once and runs in a Docker container (`eclipse-temurin:17-jre-alpine`). A database container (PostgreSQL, MSSQL, or Oracle) runs on the same Docker network. The app container receives `SPRING_PROFILES_ACTIVE` (e.g. `mssql` or `postgres`) to activate the database-specific Spring profile, plus env vars like `TAXONOMY_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, etc. to override specific connection settings.

Test file naming conventions:

| Pattern | Runner | Description |
|---|---|---|
| `*Test.java`, `*Tests.java` | maven-surefire-plugin | Unit and Spring context tests |
| `*IT.java` | maven-failsafe-plugin | Integration tests (require Docker) |
| `@Tag("db-postgres")` | maven-failsafe-plugin | PostgreSQL tests (included by default) |
| `@Tag("db-mssql")` | maven-failsafe-plugin | MSSQL tests (included by default) |
| `@Tag("db-oracle")` | maven-failsafe-plugin | Oracle tests (excluded by default) |

---

## Adding a New REST Endpoint

1. Create or extend a controller in `taxonomy-app/src/main/java/com/taxonomy/controller/`.
2. If the endpoint returns a new DTO, add it to `taxonomy-domain/src/main/java/com/taxonomy/dto/`.
3. If the endpoint needs a new service, add it to `taxonomy-app/src/main/java/com/taxonomy/service/`.
4. Add tests (typically using `@SpringBootTest` + `@AutoConfigureMockMvc` + `@WithMockUser(roles = "ADMIN")`).
5. If the endpoint is admin-only, add it under `/api/admin/` (protected by `ROLE_ADMIN` in `SecurityConfig`).
6. If the endpoint modifies architecture data (relations, DSL), place it under `/api/relations/`, `/api/dsl/`, or `/api/git/` — write operations on these paths require `ROLE_ARCHITECT` or `ROLE_ADMIN`.

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

The Architecture DSL subsystem uses JGit DFS (Distributed File System) with all Git objects stored in the HSQLDB database — no filesystem is used. The DSL serves as the **single source of truth** for architecture definitions.

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

**Important:** `DfsBlockCache` is a JVM-global singleton. Pack names **must** be unique across all `HibernateObjDatabase` instances in the same JVM. The `packIdCounter` is per-instance and initialised with `System.nanoTime()` to avoid collisions during test context restarts.

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

## Frontend Development

The frontend is a single-page application built with plain JavaScript (no build tool, no npm). All files are served as static resources from `taxonomy-app/src/main/resources/static/`.

### File Structure

```
static/
├── js/
│   ├── taxonomy.js                    Main entry point (tab switching, navigation)
│   ├── taxonomy-analysis.js           AI analysis workflow
│   ├── taxonomy-views.js              Tree/sunburst/tabs/decision/summary views
│   ├── taxonomy-graph.js              D3 force-directed graph explorer
│   ├── taxonomy-dsl-editor.js         DSL editor (CodeMirror 6 integration)
│   ├── taxonomy-dsl-codemirror.mjs    ES module for CodeMirror 6 setup
│   ├── taxonomy-versions.js           Version history timeline
│   ├── taxonomy-search.js             Full-text/semantic/hybrid search
│   ├── taxonomy-relations.js          Relations browser + proposal queue
│   ├── taxonomy-export.js             Export buttons (SVG, PNG, Visio, etc.)
│   ├── taxonomy-coverage.js           Requirement coverage panel
│   ├── taxonomy-quality.js            Quality dashboard
│   ├── taxonomy-import.js             Framework import
│   ├── taxonomy-help.js               In-app help browser
│   ├── taxonomy-git-status.js         Repository state polling
│   ├── taxonomy-action-guards.js      Write-operation safety guards
│   ├── taxonomy-viewcontext.js        Commit provenance display
│   ├── taxonomy-context-bar.js        Context navigation bar
│   ├── taxonomy-context-compare.js    Context comparison view
│   ├── taxonomy-context-transfer.js   Selective transfer UI
│   ├── taxonomy-history-search.js     Versioned commit search
│   └── taxonomy-onboarding.js         First-time user experience
├── css/
│   ├── taxonomy.css                   Main stylesheet
│   └── git-status.css                 Git status indicator styles
└── images/                            Static images
```

### Adding a New UI Module

1. Create `static/js/taxonomy-myfeature.js` following the IIFE pattern:

```javascript
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', init);

    function init() {
        // Initialise DOM references and event listeners
    }

    // Public API — exposed for other modules
    window.TaxonomyMyFeature = {
        refresh: refresh
    };
})();
```

2. Add a `<script>` tag in `templates/index.html`.
3. If the feature needs a tab, add it to the `PAGES` array (and `FULL_WIDTH_PAGES` if appropriate).
4. Call backend APIs with `fetch()` — the browser session handles authentication.

### Adding a New Tab

The tab system is driven by the `PAGES` array in the main JavaScript. To add a new tab:

1. Add the tab button in `index.html` (within the right-panel tab bar):
   ```html
   <button class="btn btn-outline-secondary btn-sm" onclick="showPage('mytab')">🔧 My Tab</button>
   ```

2. Add the content container:
   ```html
   <div id="mytab-content" class="page-content" style="display:none;">
     <!-- Tab content here -->
   </div>
   ```

3. Register the page in the JavaScript PAGES array.

### API Communication

All API calls use the Fetch API:

```javascript
fetch('/api/my-endpoint', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key: 'value' })
})
.then(response => response.json())
.then(data => { /* update UI */ })
.catch(error => { console.error('Error:', error); });
```

Authentication is handled automatically by the browser session cookie. CSRF tokens from Thymeleaf meta tags are included for browser-based requests.

### CodeMirror 6 (DSL Editor)

The DSL editor uses CodeMirror 6 loaded as an ES module from `esm.sh` CDN. The configuration is in `taxonomy-dsl-codemirror.mjs`:

- **Syntax highlighting:** Custom `StreamLanguage` for TaxDSL keywords, types, relations, IDs, strings, and comments
- **Autocompletion:** Context-aware completions for block keywords, domain types, relation types, and status values
- **Live validation:** Debounced lint via `POST /api/dsl/validate` with gutter icons for errors/warnings
- **Dark mode:** Automatic theme switching via `Compartment` and `matchMedia('(prefers-color-scheme: dark)')`
- **Format shortcut:** `Shift+Alt+F` delegates to `window.dslFormatContent()`

The CodeMirror `EditorView` is exposed as `window.dslCmView` and a `'cm-ready'` custom event is dispatched on `#dslEditorContainer` once initialised.

> For the full frontend architecture with module interaction diagrams, see [Architecture](ARCHITECTURE.md#frontend-architecture).

---

## Testing Conventions

- Tests use `@SpringBootTest` with HSQLDB in-memory and `@AutoConfigureMockMvc`.
- DSL module tests (`taxonomy-dsl`) are pure JUnit 5 — no Spring context.
- JGit storage tests (`DslGitRepositoryTest`) are pure JUnit 5 with database-backed `HibernateRepository`.
- Integration tests requiring Docker follow the `*IT.java` naming pattern.
- Tests **never** call real LLM APIs — use `LlmService` mocking instead.

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
