# Testing by Change Type

This page maps common change types to the Maven commands and likely test classes
you should run to verify your change.

---

## Quick reference

| Change type | Minimum command | Also run if… |
|---|---|---|
| Pure domain type (DTO, enum) | `mvn test -pl taxonomy-domain` | …it is used in a controller response → also run `mvn test -pl taxonomy-app` |
| DSL parser / serializer | `mvn test -pl taxonomy-dsl` | …you changed property ordering or block types → also run `mvn test -pl taxonomy-app` |
| Export service | `mvn test -pl taxonomy-export` | …you changed the controller or `ExportConfig` → also run `mvn test -pl taxonomy-app` |
| Spring service (no controller change) | `mvn test -pl taxonomy-app` | Always sufficient for service-only changes |
| REST controller | `mvn test -pl taxonomy-app` | …you added a new endpoint → also update `API_REFERENCE.md` |
| UI template or JS module | `mvn verify -DexcludedGroups=real-llm` | Always run full verify for any UI change |
| Spring configuration (`application.properties`, `@Configuration` class) | `mvn verify -DexcludedGroups=real-llm` | Always run full verify for config changes |
| JPA entity or repository | `mvn verify -DexcludedGroups=real-llm` | Always run full verify for schema changes |
| LLM provider addition | `mvn test -pl taxonomy-app` | …you changed `application.properties` → also run `mvn verify -DexcludedGroups=real-llm` |
| Workspace operation | `mvn test -pl taxonomy-app` | …you changed the controller or UI → also run `mvn verify -DexcludedGroups=real-llm` |
| Document import mapping | `mvn test -pl taxonomy-app` | …you changed the controller → also run `mvn verify -DexcludedGroups=real-llm` |

---

## Full CI command

The authoritative build command (identical to what GitHub Actions runs) is:

```bash
mvn -q verify -DexcludedGroups="real-llm"
```

Run this before opening a pull request if your change touches:
- A REST controller or API endpoint
- A UI template (`index.html`) or any JS module
- Application startup, configuration, or Spring context
- `pom.xml` or any dependency version
- A Dockerfile or container configuration

---

## Test class reference by area

### Analysis / LLM

| Test class | What it covers |
|---|---|
| `AnalysisApiControllerTest` | `/api/analysis` endpoint, mock LLM responses |
| `LlmServiceTest` | Provider selection, response parsing, rate-limit handling |
| `LlmRecordReplayServiceTest` | Record/replay mode for offline LLM testing |

### Taxonomy catalog

| Test class | What it covers |
|---|---|
| `TaxonomyServiceTest` | Node lookup, scoring, hierarchy traversal |
| `ApiControllerTest` | `/api/taxonomy` endpoint |
| `SearchApiControllerTest` | Full-text and KNN search endpoints |

### Relations

| Test class | What it covers |
|---|---|
| `RelationApiControllerTest` | CRUD for `TaxonomyRelation` |
| `ProposalApiControllerTest` | Proposal lifecycle (propose → accept/reject) |
| `RelationValidationServiceTest` | Type-combination compatibility rules |
| `CoverageApiControllerTest` | Requirement coverage calculations |

### Export

| Test class | What it covers |
|---|---|
| `ExportApiControllerTest` | Export endpoint routing |
| `ArchiMateRoundtripTest` | ArchiMate XML round-trip correctness |
| `MermaidExportServiceTest` | Mermaid output correctness |
| `VisioPackageBuilderTest` | Visio `.vsdx` package structure |

### Architecture / views

| Test class | What it covers |
|---|---|
| `GapAnalysisApiControllerTest` | Gap analysis computation |
| `ReportApiControllerTest` | Architecture report generation |
| `ArchitectureSummaryApiControllerTest` | Summary endpoint |
| `ReadmeShowcaseDriftTest` | README diagram stays in sync with code |

### DSL

| Test class | What it covers |
|---|---|
| `TaxDslParserTest` | Parser correctness for all block types |
| `TaxDslSerializerTest` | Serializer round-trip and canonical ordering |
| `DslValidatorTest` | Semantic validation rules |
| `ModelDifferTest` | Semantic diff between two models |
| `DslGitRepositoryTest` | JGit DFS storage commit/read/branch |

### Provenance / document import

| Test class | What it covers |
|---|---|
| `DocumentImportControllerTest` | Upload and parsing endpoint |
| `DocumentAnalysisServiceTest` | Chunk extraction and mapping logic |

### Workspace

| Test class | What it covers |
|---|---|
| `WorkspaceControllerTest` | Workspace CRUD, context switching |
| `DslApiControllerTest` | DSL commit, read, diff endpoints |

---

## Integration tests (require Docker)

Integration tests follow the `*IT.java` naming convention and are run by
`maven-failsafe-plugin` during `mvn verify`.

| Pattern | Coverage |
|---|---|
| `*PostgresContainerIT` | Full stack against PostgreSQL |
| `*MssqlContainerIT` | Full stack against Microsoft SQL Server |
| `*OracleContainerIT` | Full stack against Oracle (opt-in, tag `db-oracle`) |
| `ScreenshotGeneratorIT` | Selenium screenshot regeneration |

Run a specific integration test:

```bash
# Run only PostgreSQL integration tests
mvn verify -DexcludedGroups=real-llm -Dit.test="*Postgres*IT"

# Regenerate screenshots
mvn failsafe:integration-test -DgenerateScreenshots=true -Dit.test=ScreenshotGeneratorIT
```

---

## Test annotations reference

| Annotation | Usage |
|---|---|
| `@SpringBootTest` + `@AutoConfigureMockMvc` | Standard unit test with full Spring context |
| `@WithMockUser(roles = "ADMIN")` | Simulate an authenticated admin user |
| `@Tag("real-llm")` | LLM tests excluded from CI by default |
| `@Tag("db-oracle")` | Oracle tests excluded from CI by default |

All `@SpringBootTest` test classes **must** include `@WithMockUser(…)` or
`@WithAnonymousUser`; without it, MockMvc requests return HTTP 401.
