# Testing by Change Type

All functional verification is Maven-owned. Start with the smallest applicable
profile and finish a pull request with the canonical command documented in
[Maven Verification Authority](MAVEN_VERIFICATION.md). Release preparation and
publication are documented separately in
[Release Verification and Publication](RELEASE_PROCESS.md).

## Commands by change type

| Change | First command | Required broader check |
|---|---|---|
| Domain DTO or enum | `./mvnw test -pl taxonomy-domain` | `./mvnw verify` |
| DSL parser/serializer | `./mvnw test -pl taxonomy-dsl` | App/editor tests when materialization changes |
| Export model/serializer | `./mvnw test -pl taxonomy-export` | App endpoint tests when adapters change |
| Spring service/controller | `./mvnw test -pl taxonomy-app` | `./mvnw verify` |
| Architecture boundary | `./mvnw test -Parchitecture-tests -pl taxonomy-app` | `./mvnw -B verify -Pci` |
| Document import | `./mvnw test -Pdocument-import-tests -pl taxonomy-app` | `./mvnw -B verify -Pci` |
| ArchiMate import | `./mvnw test -Parchimate-import-tests -pl taxonomy-app` | `./mvnw -B verify -Pci` |
| Persistence/core containers | `./mvnw -B verify -Pcore-integration` | `./mvnw -B verify -Pci` |
| PostgreSQL mapping | `./mvnw -B verify -Pdatabase-postgres` | scheduled extended matrix |
| SQL Server mapping | `./mvnw -B verify -Pdatabase-mssql` | scheduled extended matrix |
| Oracle mapping | `./mvnw -B verify -Pdatabase-oracle` | scheduled extended matrix |
| Local ONNX | `./mvnw -B verify -Ponnx` | `./mvnw -B verify -Pci` |
| UI/CSS/JavaScript | `./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true` | `./mvnw -B verify -Pci` |
| Dependency or workflow policy | `./mvnw -B verify -Pquality -DskipTests -DskipITs=true` | `./mvnw -B verify -Pci` |
| Release plan or release code | `./mvnw -B -Prelease-check validate -DreleaseVersion=X.Y.Z -DnextDevelopmentVersion=X.Y.Z-SNAPSHOT` | `./mvnw -B -Prelease-check,ci clean verify` with the same versions |
| Documentation screenshots | `./mvnw -B verify -Pscreenshots` | manual visual review before publication |

## Stable lifecycle scopes

`./mvnw verify` is the bounded developer lifecycle. Failsafe is skipped and the
three external database tags plus real LLM tests are excluded.

`./mvnw -B verify -Pci` is the complete required pull-request lifecycle. It
activates core/PostgreSQL integration, quality gates and browser/accessibility
verification. SQL Server and Oracle remain scheduled/manual because their
container cost is materially higher. Real LLM tests always remain opt-in.

## Browser and accessibility reproduction

The Java application lifecycle, pinned Node installation, npm dependency lock,
Playwright browser installation, app startup, scenario selection and pass/fail
rules are all reached through Maven. GitHub Actions does not start the app or
invoke a Node test script directly.

```bash
# Complete browser matrix
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true

# Only the accessibility suite
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true \
  -Dtaxonomy.ui.suite=accessibility

# Exact failing role/state profile
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true \
  -Dtaxonomy.ui.suite=role-state \
  -Dtaxonomy.ui.profile.filter=mobile-admin-webkit
```

Reports, screenshots, DOM evidence and application logs are stored under
`target/ui-verification/`.

## Test naming and execution

| Pattern/tag | Runner | Default `verify` | `-Pci` |
|---|---|---|---|
| `*Test.java`, `*Tests.java` | Surefire | Included | Included |
| `*IT.java` | Failsafe | Skipped | Included unless excluded |
| `db-postgres` | Failsafe/Testcontainers | Excluded | Included |
| `db-mssql` | Failsafe/Testcontainers | Excluded | Excluded; scheduled profile |
| `db-oracle` | Failsafe/Testcontainers | Excluded | Excluded; scheduled profile |
| `real-llm` | Surefire/Failsafe | Excluded | Excluded |

Do not add `-Dtest`, `-Dit.test`, direct Playwright/axe commands or local quality
scripts to workflow YAML. Add or adjust a Maven profile and update
`.mvn/verification-suites.json` instead.
