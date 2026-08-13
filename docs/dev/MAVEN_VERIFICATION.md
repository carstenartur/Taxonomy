# Maven Verification Authority

Taxonomy has one build authority: the checked-in Maven Wrapper, `.mvn`
configuration and profiles in the root POM. GitHub Actions supplies machines,
containers, external scanners and artifact publication; it does not own a
functional test or a hidden test selection.

## Toolchain

- Java 21
- Maven 3.9.16 through Maven Wrapper 3.3.4
- Docker for Testcontainers suites
- Bash, `curl`, Python 3 and internet access for the first model/browser download

Node.js and the Playwright/axe packages are installed in the build directory by
`frontend-maven-plugin`; a global Node installation is not part of the contract.
The exact browser package versions are declared in `.github/package-lock.json`.
Keycloak integration uses the image pinned in `.mvn/maven.config`; the test never
requires a pre-existing identity provider.

## Authoritative commands

| Scope | Command | Additional requirement |
|---|---|---|
| Compile | `./mvnw compile` | Java 21 |
| Normal developer verification | `./mvnw verify` | No Docker; unit, Spring, contract and architecture tests |
| Complete required verification | `./mvnw -B verify -Pci` | Docker and POSIX tools |
| Core container integration | `./mvnw -B verify -Pcore-integration` | Docker |
| PostgreSQL | `./mvnw -B verify -Pdatabase-postgres` | Docker |
| SQL Server | `./mvnw -B verify -Pdatabase-mssql` | Docker |
| Oracle | `./mvnw -B verify -Pdatabase-oracle` | Docker |
| Local ONNX pipeline | `./mvnw -B verify -Ponnx` | Docker |
| Keycloak security only | `./mvnw -B verify -pl taxonomy-app -am -DskipITs=false -Dit.test=KeycloakSecurityContainerIT` | Docker |
| Browser and accessibility only | `./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true` | permission to install browser OS libraries |
| Documentation screenshots | `./mvnw -B verify -Pscreenshots` | Docker |

`./mvnw -B verify -Pci` is the canonical pull-request command. It runs:

1. all normal unit, Spring, architecture and contract tests;
2. core, PostgreSQL, local ONNX and imported-realm Keycloak Testcontainers integration tests;
3. the reactor-wide JaCoCo threshold and dependency-policy gates;
4. documentation, frontend-boundary, dependency-alignment and supply-chain checks;
5. the role, workflow, state, browser, zoom, forced-colors and axe matrix.

It excludes real LLM calls and the scheduled SQL Server and Oracle suites.

## Keycloak security contract

`KeycloakSecurityContainerIT` starts an isolated Keycloak container, imports a
deterministic test-only realm, starts the packaged Taxonomy application with the
`keycloak` profile and drives both a real browser and real bearer tokens. It proves:

- authorization-code login and principal mapping for USER, ARCHITECT and ADMIN;
- explicit extraction of `preferred_username` and `realm_access.roles`;
- fail-closed behavior for missing, malformed and unsupported role claims;
- CSRF enforcement for browser sessions and exemption only for actual bearer requests;
- authorization parity for administration, architecture mutation, preview/import and document upload;
- RP-initiated logout, fresh OIDC challenge and invalidation of the former application session;
- private and explicitly public Swagger modes;
- health behavior for reachable, erroneous, unavailable and invalid JWKS endpoints;
- absence of local password and local-user-management fallbacks while the Keycloak profile is active.

Realm data, client secrets and users are located below
`taxonomy-app/src/test/resources/keycloak/` and are not deployment defaults.

## Focused Maven-owned suites

Test selection is stored in POM profiles or cataloged Maven commands, never in
workflow YAML:

```bash
./mvnw test -Parchitecture-tests -pl taxonomy-app
./mvnw test -Pdocument-import-tests -pl taxonomy-app
./mvnw test -Parchimate-import-tests -pl taxonomy-app
./mvnw -B verify -pl taxonomy-app -am \
  -DskipITs=false -Dit.test=KeycloakSecurityContainerIT
```

Browser failures can be reproduced without editing a workflow:

```bash
# One suite
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true \
  -Dtaxonomy.ui.suite=role-state

# One exact matrix profile
./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true \
  -Dtaxonomy.ui.suite=role-state \
  -Dtaxonomy.ui.profile.filter=zoom-400-user-chromium
```

Valid suite names, profiles and focused commands are defined by
`.github/ui-acceptance-matrix.json` and `.mvn/verification-suites.json`. Browser
evidence is written below `target/ui-verification/`; Java integration results are
written to the normal Failsafe report directory.

## UI process isolation and timings

The Maven-owned launcher executes every selected browser scenario but does not
restart the packaged application when scenarios have the same isolation needs:

- read-only `ui` and `accessibility` checks share one application;
- the special-modes suite keeps a fresh application because it performs a real
  analysis before testing partial-result, text-spacing and offline states;
- role/state profiles share one application only with profiles for the same role;
- each primary mutation workflow keeps its own fresh application.

The complete default matrix therefore retains all 18 scenarios while reducing
application starts from 18 to 8. Execution remains sequential. Scenarios that
mutate application state are not allowed to share with read-only browser checks,
primary mutation workflows remain isolated, and a role/state scenario can never
share an application with another role. These rules are executable contracts in
`.github/scripts/ui-suite-plan.test.mjs` rather than implicit CI behavior.

Each scenario contains `application-log.txt`, which points to the log of its
isolation group. The launcher also writes `target/ui-verification/timings.json`
with application startup duration, scenario duration, group duration, total
duration and pass/fail outcome. This report is produced even when a scenario
fails, so performance and startup regressions can be compared without reading
the full Maven log.

## UI evidence policy

Browser assertions, axe analysis, console checks, network checks and JSON summaries
run for every selected state. Successful verification uses compact evidence by
default: it retains the machine-readable reports and a small curated screenshot
baseline instead of storing screenshots, HTML and ARIA snapshots for every passing
state. A failing primary or role/state scenario always captures a viewport
screenshot, DOM snapshot and ARIA snapshot before the browser closes.

Full successful evidence remains locally reproducible when it is needed for a
manual audit:

```bash
TAXONOMY_UI_EVIDENCE_MODE=full \
  ./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true
```

The curated compact baseline can be changed without changing test selection:

```bash
TAXONOMY_UI_CURATED_STATES=analysis-success,dialog-open \
  ./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true
```

`TAXONOMY_UI_EVIDENCE_MODE` accepts only `compact` or `full`; invalid values fail
before browser scenarios execute.

## Workflow responsibilities

Every workflow is classified in `.mvn/verification-suites.json`; the table below
must remain synchronized with that executable catalogue.

| Workflow | Responsibility |
|---|---|
| `ci-cd.yml` | Call the canonical Maven command and publish its reports |
| `database-compatibility.yml` | Schedule/select database environments and call Maven profiles |
| `jgit-storage-hibernate-contract.yml` | Run the consumer-owned storage compatibility contract through catalogued Maven selectors |
| `codeql.yml` | External CodeQL source analysis |
| `security-scan.yml` | External Trivy analysis |
| `dependency-submission.yml` | Publish the Maven dependency graph |
| `documentation-screenshots.yml` | Invoke the Maven screenshot profile and publish reviewed output |
| `delivery.yml` | Publish reports/images and trigger deployment after verified CI |
| `deploy-release.yml` | Execute the repository release state machine |
| `protected-release-main-advance.yml` | Verify and hand off the protected next-development snapshot to `main` |
| `cleanup-workflow-runs.yml` | Retain Actions history according to policy |

`WorkflowTestAuthorityPolicyTest` is a JUnit contract in `taxonomy-app`. It fails
when a new workflow runs browser/a11y scripts directly, selects Java tests,
uses an unpinned Maven executable, bypasses the catalogued database profiles, or
introduces an unclassified or previously removed workflow. Positive and negative
filesystem fixtures exercise the policy without relying on GitHub Actions.

CodeQL, Trivy, publishing and deployment are deliberately not described as
locally equivalent tests. Their evidence remains separate from Maven test
reports.
