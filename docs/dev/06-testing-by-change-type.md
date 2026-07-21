# Testing by Change Type

All verification commands run from a normal Git checkout. GitHub Actions only
orchestrates the same Maven, Failsafe, Testcontainers, Playwright, and axe
contracts that developers can execute locally.

## Test layers

| Layer | Purpose | Default command |
|---|---|---|
| Standard lifecycle | Unit, Spring context, controller, architecture, contract, dependency-hygiene, and module tests | `mvn verify` |
| Core integration | HSQLDB diagnostics, real browser flow, and persistence restart | `mvn verify -DskipITs=false -Dit.test=<class>` |
| Database compatibility | PostgreSQL, MSSQL, and Oracle diagnostics plus Selenium flows | `mvn verify -DskipITs=false -Dit.test=<class> -DexcludedGroups=real-llm` |
| Browser UX | Desktop Chromium/Firefox plus tablet/mobile Chromium | `node .github/scripts/ui-acceptance.mjs` |
| Accessibility | Authenticated axe audit with checked moderate baseline | `node .github/scripts/accessibility-audit.mjs` |
| Real LLM | Explicit live-provider integration | Select the test and remove `real-llm` from excluded groups |
| Documentation screenshots | Deterministic visual fixtures, not acceptance evidence | `mvn verify -DskipITs=false -DgenerateScreenshots -Dit.test=ScreenshotGeneratorIT` |

The root POM sets `skipITs=true`; container tests are enabled explicitly with
`-DskipITs=false`. The default excluded tags are
`real-llm,db-postgres,db-mssql,db-oracle`.

## Quick reference

| Change | Minimum | Additional evidence |
|---|---|---|
| Domain DTO or enum | `mvn test -pl taxonomy-domain` | App tests when exposed through API |
| DSL parser/serializer | `mvn test -pl taxonomy-dsl` | App and editor tests for materialization/UI changes |
| Export model/serializer | `mvn test -pl taxonomy-export` | App tests for endpoint or adapter changes |
| Spring service/controller | `mvn test -pl taxonomy-app` | `mvn verify` for configuration, persistence, or API changes |
| Dependency or POM | `mvn verify` | SBOM hygiene command below |
| UI/CSS/JavaScript | `mvn verify` | Browser and accessibility commands below |
| Security | `mvn verify` | MockMvc security tests and `CoreUiAcceptanceIT` |
| HSQLDB/persistence | `mvn verify` | `ProductionPersistenceRestartIT` |
| External DB mapping | `mvn verify` | Both diagnostics and Selenium tests for that family |
| Documentation | `python3 .github/scripts/check-doc-links.py` | Screenshots only for visible UI changes |

## Standard lifecycle

```bash
mvn verify
mvn clean verify                 # release-style clean build
```

This lifecycle remains deterministic: it does not start Docker or contact a live
LLM. Maven Enforcer rejects prohibited packaged dependency chains.

### Dependency and SBOM evidence

```bash
PDFBOX_VERSION=$(mvn help:evaluate -Dexpression=pdfbox.version -q -DforceStdout)
python3 .github/scripts/check-dependency-hygiene.py \
  --sbom target/taxonomy-sbom.json \
  --expected-pdfbox-version "$PDFBOX_VERSION"

mvn -pl taxonomy-app dependency:tree \
  -Dscope=runtime \
  -Dincludes='org.apache.pdfbox:*,com.vladsch.flexmark:flexmark-pdf-converter,com.openhtmltopdf:*'
```

See [DEPENDENCY_HYGIENE.md](DEPENDENCY_HYGIENE.md) for the reviewed exception
process.

## Core Testcontainers integration

| Test | Coverage |
|---|---|
| `DiagnosticsContainerIT` | Packaged app and diagnostics on embedded HSQLDB |
| `DiagnosticsWithApiKeyContainerIT` | Provider-key detection and masking |
| `CoreUiAcceptanceIT` | Login, onboarding, local assets, and keyboard navigation |
| `ProductionPersistenceRestartIT` | File HSQLDB and Lucene data survive container replacement |

```bash
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test=ProductionPersistenceRestartIT \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle
```

## Database compatibility matrix

The scheduled/manual workflow runs these ordinary Testcontainers tests:

| Database | Diagnostics | Browser | Tag |
|---|---|---|---|
| PostgreSQL | `DiagnosticsPostgresContainerIT` | `SeleniumPostgresContainerIT` | `db-postgres` |
| MSSQL | `DiagnosticsMssqlContainerIT` | `SeleniumMssqlContainerIT` | `db-mssql` |
| Oracle Free | `DiagnosticsOracleContainerIT` | `SeleniumOracleContainerIT` | `db-oracle` |

Pull requests that change database configuration run the complete PostgreSQL
pair as a bounded compatibility smoke test. Weekly and manual runs cover all
selected families.

```bash
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test='*Postgres*IT' \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm
```

Use `*Mssql*IT` or `*Oracle*IT` for the other families.

## Browser UX matrix

Install the pinned test dependency and intended browser engines:

```bash
npm install --no-save --no-audit --no-fund @playwright/test@1.61.1
npx playwright install --with-deps chromium firefox
```

Example desktop Firefox run:

```bash
TAXONOMY_BASE_URL=http://127.0.0.1:8080 \
TAXONOMY_UI_USERNAME=admin \
TAXONOMY_UI_PASSWORD=ui-acceptance-password \
TAXONOMY_BROWSER=firefox \
TAXONOMY_UI_PROFILE=desktop-firefox \
TAXONOMY_VIEWPORT_WIDTH=1440 \
TAXONOMY_VIEWPORT_HEIGHT=1000 \
TAXONOMY_UI_MODE=full \
node .github/scripts/ui-acceptance.mjs
```

The CI matrix also runs tablet and mobile read/navigation flows. See
[BROWSER_QA.md](BROWSER_QA.md).

## Accessibility

```bash
npm install --no-save --no-audit --no-fund \
  @playwright/test@1.61.1 @axe-core/playwright@4.12.1
npx playwright install --with-deps chromium
node .github/scripts/accessibility-audit.mjs
```

Critical and serious findings always fail. Moderate findings must match the
reviewed signatures in `.github/accessibility-baseline.json`; new signatures
fail the build. The TaxDSL CodeMirror editor is audited and has a dedicated
keyboard focus-escape check.

## Documentation screenshots

```bash
mvn -B verify \
  -DskipITs=false \
  -DgenerateScreenshots \
  -Dit.test=ScreenshotGeneratorIT \
  -DfailIfNoTests=false
```

Screenshots may use deterministic mock data. They do not prove live backends or
external AI providers are healthy. Publication is isolated in the manually
triggered `Documentation Screenshots` workflow.

## Security context and annotations

| Annotation/property | Use |
|---|---|
| `@SpringBootTest` + `@AutoConfigureMockMvc` | Spring integration without containers |
| `@WithMockUser(...)` | Explicit authenticated MockMvc context |
| `@Testcontainers` | Docker-backed integration |
| `@Tag("real-llm")` | Live provider test, excluded by default |
| `@Tag("db-postgres")` | PostgreSQL matrix |
| `@Tag("db-mssql")` | MSSQL matrix |
| `@Tag("db-oracle")` | Oracle matrix |
| `@EnabledIfSystemProperty` | Explicit opt-in test such as screenshots |

Browser and container acceptance tests must exercise real application contracts;
they must not inject result DOM or fake service-health state.
