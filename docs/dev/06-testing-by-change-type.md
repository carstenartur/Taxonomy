# Testing by Change Type

This page maps common changes to the smallest useful verification command and
explains how the standard, core-integration, and database-compatibility suites
fit together.

All commands below run from a normal Git checkout. GitHub Actions invokes the
same Maven/Failsafe/Testcontainers contracts; the tests do not depend on GitHub.

---

## Test layers

| Layer | Purpose | Default command |
|---|---|---|
| Standard lifecycle | Unit, Spring context, controller, architecture, contract, and module tests | `mvn verify` |
| Core integration | Production-like HSQLDB, diagnostics, real browser, and persistence-restart tests | `mvn verify -DskipITs=false -Dit.test=<class>` |
| Database compatibility | PostgreSQL, MSSQL, and Oracle diagnostics plus Selenium flows | `mvn verify -DskipITs=false -Dit.test=<class> -DexcludedGroups=real-llm` |
| Real LLM | Explicit provider integration against live credentials | Select the required test and remove `real-llm` from `excludedGroups` |
| Documentation screenshots | Deterministic documentation fixtures, not acceptance evidence | `mvn verify -DskipITs=false -DgenerateScreenshots -Dit.test=ScreenshotGeneratorIT` |

The root POM sets `skipITs=true` so `mvn verify` remains deterministic and
bounded. Failsafe/Testcontainers tests are enabled explicitly with
`-DskipITs=false`.

The heavyweight external-database tags are excluded by default:

```text
real-llm,db-postgres,db-mssql,db-oracle
```

Supplying `-DexcludedGroups=real-llm` includes the database tags while still
excluding live LLM calls.

---

## Quick reference

| Change type | Minimum command | Additional verification |
|---|---|---|
| Pure domain type, DTO, or enum | `mvn test -pl taxonomy-domain` | Run `mvn test -pl taxonomy-app` when used in API responses |
| DSL parser or serializer | `mvn test -pl taxonomy-dsl` | Run `mvn test -pl taxonomy-app` for controller/materialization changes |
| Export model or serializer | `mvn test -pl taxonomy-export` | Run app tests for endpoint or adapter changes |
| Extension contract | `mvn test -pl taxonomy-extension-api` | Run the affected registry/adapter tests in `taxonomy-app` |
| Spring service | `mvn test -pl taxonomy-app` | Standard lifecycle for configuration or persistence changes |
| REST controller | `mvn test -pl taxonomy-app` | Update API documentation and run `mvn verify` |
| UI template or JavaScript | `mvn verify` | Also run `CoreUiAcceptanceIT` and the Playwright/axe workflows |
| Security configuration | `mvn verify` | Also run `CoreUiAcceptanceIT` and security-focused MockMvc tests |
| HSQLDB or production persistence | `mvn verify` | Also run `ProductionPersistenceRestartIT` |
| External database mapping | `mvn verify` | Run both diagnostics and Selenium tests for that database family |
| LLM provider | `mvn test -pl taxonomy-app` | Run provider-specific record/replay tests; live calls remain opt-in |
| Documentation only | `python3 .github/scripts/check-doc-links.py` | Regenerate screenshots only when visible UI changed |

---

## Standard lifecycle

The standard pull-request build is:

```bash
mvn verify
```

It compiles all modules and runs deterministic tests without starting Docker
containers or contacting external LLM providers.

For a clean release-style build:

```bash
mvn clean verify
```

---

## Core Testcontainers integration

The `Core Integration` workflow runs these tests independently and in parallel,
with a timeout per test:

| Test class | Coverage |
|---|---|
| `DiagnosticsContainerIT` | Packaged application and diagnostics on embedded HSQLDB |
| `DiagnosticsWithApiKeyContainerIT` | Provider-key detection and masked diagnostics values |
| `CoreUiAcceptanceIT` | Real login, onboarding, local assets, and keyboard navigation through Selenium |
| `ProductionPersistenceRestartIT` | File HSQLDB and Lucene data survive application-container replacement |

Run one locally:

```bash
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test=ProductionPersistenceRestartIT \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle
```

Replace the class name to run another core scenario.

---

## Database compatibility matrix

The `Database Compatibility` workflow is scheduled weekly and can be started
manually for `all`, `postgres`, `mssql`, or `oracle`. It runs the same commands
available locally.

| Database | Diagnostics test | Browser test | Tag |
|---|---|---|---|
| PostgreSQL | `DiagnosticsPostgresContainerIT` | `SeleniumPostgresContainerIT` | `db-postgres` |
| Microsoft SQL Server | `DiagnosticsMssqlContainerIT` | `SeleniumMssqlContainerIT` | `db-mssql` |
| Oracle Database Free | `DiagnosticsOracleContainerIT` | `SeleniumOracleContainerIT` | `db-oracle` |

Run a complete family locally:

```bash
# PostgreSQL
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test='*Postgres*IT' \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm
```

Use `*Mssql*IT` or `*Oracle*IT` for the other families. Testcontainers starts
the database, application, and browser containers; no preinstalled database is
required.

---

## Documentation screenshots

Screenshot generation is opt-in and intentionally separate from acceptance
testing:

```bash
mvn -B verify \
  -DskipITs=false \
  -DgenerateScreenshots \
  -Dit.test=ScreenshotGeneratorIT \
  -DfailIfNoTests=false
```

Screenshot fixtures may use deterministic mock data. They do not prove that
live search backends, external AI providers, or production infrastructure are
healthy.

---

## Test annotations

| Annotation or property | Usage |
|---|---|
| `@SpringBootTest` + `@AutoConfigureMockMvc` | Spring integration without external containers |
| `@WithMockUser(...)` | Authenticated MockMvc request context |
| `@Testcontainers` | Docker-backed integration test |
| `@Tag("real-llm")` | Live provider test, excluded by default |
| `@Tag("db-postgres")` | PostgreSQL compatibility matrix |
| `@Tag("db-mssql")` | MSSQL compatibility matrix |
| `@Tag("db-oracle")` | Oracle compatibility matrix |
| `@EnabledIfSystemProperty` | Explicit opt-in tests such as screenshots or local ONNX |

All Spring MockMvc tests must establish an explicit security context. Browser
and container acceptance tests must use real application contracts and must not
inject result DOM or fake service-health state.
