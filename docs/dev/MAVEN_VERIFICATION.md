# Maven Verification Authority

Taxonomy has one build authority: the checked-in Maven Wrapper, `.mvn` configuration
and profiles in the root POM. GitHub Actions supplies machines, containers, external
scanners and artifact publication; it does not own a functional test or a hidden
test selection.

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
- role extraction from `realm_access.roles`;
- fail-closed behavior for missing, malformed and unsupported role claims;
- CSRF enforcement for browser sessions and exemption only for actual bearer requests;
- authorization parity for administration, architecture mutation, preview/import and document upload;
- RP-initiated logout and invalidation of the former application session;
- private and explicitly public Swagger modes;
- health behavior for reachable, erroneous, unavailable and invalid JWKS endpoints;
- absence of a local password fallback while the Keycloak profile is active.

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

## Workflow responsibilities

Only eight workflows remain:

| Workflow | Responsibility |
|---|---|
| `ci-cd.yml` | Call the canonical Maven command and publish its reports |
| `database-compatibility.yml` | Schedule/select database environments and call Maven profiles |
| `codeql.yml` | External CodeQL source analysis |
| `security-scan.yml` | External Trivy analysis |
| `documentation-screenshots.yml` | Invoke the Maven screenshot profile and publish reviewed output |
| `delivery.yml` | Publish reports/images and trigger deployment after verified CI |
| `deploy-release.yml` | Execute the repository release state machine |
| `cleanup-workflow-runs.yml` | Retain Actions history according to policy |

The build-policy module executes
`.github/scripts/check-workflow-test-authority.py`. It fails when a new workflow
runs a browser/a11y script directly, selects Java test classes, uses an unpinned
Maven executable, or introduces an unclassified workflow.

CodeQL, Trivy, publishing and deployment are deliberately not described as
locally equivalent tests. Their evidence remains separate from Maven test
reports.
