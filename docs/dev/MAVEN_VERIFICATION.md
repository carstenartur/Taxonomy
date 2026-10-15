# Maven Verification Authority

The required build contract is that a developer can reproduce every required
test through Maven with only Java and Maven installed. Test selection and
pass/fail rules belong in Maven; GitHub Actions should call those owners and
publish their results.

**This requirement is not yet fulfilled.** The current commands below expose
the existing Maven suites, but the combined `ci` profile does not reproduce
every required Actions check. In particular, a successful `test-local` run is
not proof of full CI parity. See the concrete remaining gaps below.

## Toolchain

- Java 21
- Maven 3.9.16 through Maven Wrapper 3.3.4
- Docker for Testcontainers suites
- Bash, `curl` and Python 3 for remaining legacy checks/model provisioning
- Internet access for the first dependency, model and browser downloads

The root `frontend-maven-plugin` installs the pinned Node into
`target/test-runtime/frontend` before Surefire. Existing JavaScript-consuming
JUnit tests and the later Playwright/axe phase use that executable; a global
Node installation is not needed by those Maven tests. Local Selenium sessions
provision the Chrome/ChromeDriver version pinned in the root POM, while the
Playwright package versions are declared in `.github/package-lock.json`.
Keycloak integration uses the image pinned in `.mvn/maven.config`; the test never
requires a pre-existing identity provider.

## Authoritative commands

### CI Command

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

This remains the requested canonical entry point. Its current dependencies and
coverage limits are documented here so that partial execution cannot be
mistaken for completion of the Java/Maven-only requirement.

| Scope | Command | Additional requirement |
|---|---|---|
| Compile | `./mvnw compile` | Java 21 |
| Normal developer verification | `./mvnw verify` | Unit, Spring, contract, architecture and browser tests; browser sessions default to Docker unless local Chrome is explicitly configured |
| Docker-free developer verification | `./mvnw verify -Ptest-local` | Maven provisions Node and the pinned local browser; browser OS libraries remain necessary. See [scope and prerequisites](../testing/docker-free-tests.md). Container, database, ONNX and external-LLM suites remain separate. |
| Local combined CI-profile verification | `./mvnw -B verify -Pci -DrunOnnxTests=true` | Docker, browser/model prerequisites and POSIX tools; CI itself splits UI into shards |
| Core container integration | `./mvnw -B verify -Pcore-integration` | Docker |
| PostgreSQL | `./mvnw -B verify -Pdatabase-postgres` | Docker |
| SQL Server | `./mvnw -B verify -Pdatabase-mssql` | Docker |
| Oracle | `./mvnw -B verify -Pdatabase-oracle` | Docker |
| Local ONNX pipeline | `./mvnw -B verify -Ponnx` | Docker |
| Keycloak security only | `./mvnw -B verify -pl taxonomy-app -am -DskipITs=false -Dit.test=KeycloakSecurityContainerIT` | Docker |
| Browser and accessibility only | `./mvnw -B verify -Pui-tests -DskipTests -DskipITs=true` | permission to install browser OS libraries |
| Documentation screenshots | `./mvnw -B verify -Pscreenshots` | Docker |

The database workflow adds the explicit `ci-database-compatibility` companion:

```bash
./mvnw -B verify -Pdatabase-postgres,ci-database-compatibility
./mvnw -B verify -Pdatabase-mssql,ci-database-compatibility
./mvnw -B verify -Pdatabase-oracle,ci-database-compatibility
```

This companion skips only repeated taxonomy-app Surefire executions already owned
by the core and isolated app-test union. It preserves the selected database
Failsafe tests, their missing-selector failure behavior, test compilation,
packaging and the other modules' Surefire/build-policy guards. Database profiles
already leave UI and build-policy integration tests disabled; the companion does
not change those settings or the database selectors. It disables the partial
aggregate coverage report, while JaCoCo instrumentation remains enabled. These
database lanes supplement the complete CI coverage aggregate and cannot replace
it. The standalone database commands in the table retain their ordinary App
tests and aggregate report; no companion is activated automatically.

`./mvnw -B verify -Pci -DrunOnnxTests=true` is the local combined lifecycle
when the reference model is provisioned. The pull-request
workflow runs this profile in the core reactor with `-DrunOnnxTests=true`
and `-Dtaxonomy.ui.skip=true`, then independently requires the commit-bound UI
application, UI contracts, Maven-owned UI shard matrix, observability and
interoperability lanes. The final `verify` job checks every lane and verifies
digest-bound shard evidence. Together they form the canonical pull-request
gate; no single core Maven log proves the browser matrix passed. The core
profile runs:

1. all normal unit, Spring, architecture and contract tests;
2. core, PostgreSQL and imported-realm Keycloak Testcontainers integration tests;
   local ONNX also requires `-DrunOnnxTests=true` and the pinned model;
3. the reactor-wide JaCoCo threshold and dependency-policy gates;
4. documentation, frontend-boundary, dependency-alignment and supply-chain checks;
5. when run locally without `taxonomy.ui.skip=true`, the browser matrix;
   in GitHub Actions that matrix runs through `.github/ui-shards.json` and
   `.github/ui-verification-pom.xml -Pshard` on the same packaged application.

It excludes real LLM calls and the SQL Server and Oracle suites. The latter also
run on pull requests through the separate database workflow; they are not merely
optional scheduled checks.

### Application test shards and CI critical path

The 14 October 2026 comparison run (`38635506309`) took 68m41s for the core
job and 55m23s for the plugin job. The plugin artifact repeated 1,157 JUnit
suites / 8,439 cases already present in core, totaling 3,105 seconds of reported
suite time. This duplication is the reason for the CI-only packaging companion
below. These are baseline measurements, not a promised duration for the new
parallel jobs; compare their published timings after a complete successful run.

The standalone `./mvnw -B verify -Pci -DrunOnnxTests=true` command does not activate
sharding and continues to run the complete suite. In GitHub Actions, the
`.mvn/verification-suites.json` `applicationTestShards` plan assigns measured
recovery/restart and browser acceptance classes to isolated Maven runners. Their
JUnit tags and Maven profiles are checked against the catalogue; app tests not
listed for a shard—including newly added test classes—remain in the core group.
The product-demo isolated Surefire execution and the required architecture gates
remain in the core reactor with their existing fail-on-empty behavior.

Each app runner stages its test reports and JaCoCo execution data with SHA-256
digests and the exact source commit/tree and run identity. The core job publishes
its test evidence independently of the isolated app runners. The separate
coverage-aggregate job waits for both producers, checks the source-bound
full-reactor class outputs and complete class inventory, and validates every
artifact before JaCoCo merges all isolated execution data into a separate
application execution file. Its full-reactor aggregate report consumes that file
together with core module execution data before unchanged coverage policies run.
Missing, duplicate, wrong-source or wrong-tree evidence fails the required
coverage-aggregate job.
Legitimate skips and profile exclusions are counted separately from missing
reports. Machine timings and a concise slow-suite inventory are retained in
`target/app-test-shards/timings.json` and the CI summary.

The inventory verifier is the Java `taxonomy-tooling` command
`check-app-test-shards`; its source discovery, evidence validation and actual
archive/staging shell contracts are tested with JUnit in the normal Maven
lifecycle. Both isolated app runners also build `taxonomy-tooling` with
`-pl taxonomy-tooling,taxonomy-app -am`. No separate Node test runner is needed
for this application-test partitioning.

All groups run on each CI commit and have individually visible GitHub checks.
The required `Maven verification` gate rejects failed, cancelled or skipped
groups and missing aggregate coverage. This is parallel execution of the same
verification scope, not a nightly-only slow-test profile. The single unsharded
local command above remains the way to execute all of these JUnit tests together.

Production Docker image verification runs as a separate job and remains a
required final-gate prerequisite. Shard and CI packaging profiles disable Maven
module build-cache reuse so a warm result from another test scope cannot satisfy
their evidence checks. GitHub's Maven dependency-download cache remains enabled;
the standalone unsharded lifecycle retains its configured module cache behavior.

### Remaining gaps in the Java/Maven-only requirement

| Gap | Current source of the difference |
| --- | --- |
| Container and database runtimes | Testcontainers needs an available container engine; Maven does not provision one. `ci` excludes SQL Server and Oracle, whose separate profiles still need it. |
| Helm-dependent tests | `HelmArtemisContractTest`, `HelmConstrainedSmokeContractTest` and `SetupInfrastructureTest` depend on installed Helm. Some missing-tool decisions depend on `CI` or skip the test locally. |
| Checks outside the root lifecycle | `ci-core-contracts.sh` executes additional Python, Node and shell checks. `verify-interoperability-products.mjs` verifies real external products and needs further native tools. |
| Conditional integration tests | Actions separately enables the observability performance budget and document report E2E. The canonical `ci` profile does not enable both. |
| Post-test acceptance rules | Several workflows separately validate required JUnit suite counts, rendered scenario documents and commit-bound browser artifacts. These rules are not all bound to root `verify`. |
| Deployment and security execution | Production container validation, live Kubernetes/Artemis recovery, CodeQL and Trivy are additional required executions outside this Maven command. |

Closing these gaps must retain the actual tests, thresholds and positive-count
checks. Adding exclusions, accepting missing tools as successful tests or
classifying a required check as external does not establish local parity.
New test coverage must use the existing Maven owners; another Actions workflow
does not repair any of these gaps.

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
./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Pdocument-import-tests -pl taxonomy-app
./mvnw test -Parchimate-import-tests -pl taxonomy-app
./mvnw -B verify -pl taxonomy-app -am \
  -DskipITs=false -Dit.test=KeycloakSecurityContainerIT

# Draft payload persistence and responsive-navigation source contracts
./mvnw -pl taxonomy-app -am \
  -Dtest=AnalysisWorkingDraftMvcContractTest,AnalysisWorkingDraftServiceTest,TaxonomyResponsiveNavigationContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The architecture suite needs the complete reactor's production outputs for its
module-extraction inventory. `ArchitectureModuleExtractionTest` is owned by
`taxonomy-build`, downstream of the application, coverage aggregator, and
tooling modules. Run the suite from the repository root; modules without
matching test classes use `surefire.failIfNoSpecifiedTests=false` while their
production code is compiled. The report is written to
`taxonomy-build/target/architecture-module-graph.txt`.

App-only selections do not execute this whole-repository gate. In particular,
the Keycloak-only command above ends at `taxonomy-app` and remains a supported
focused integration lane; ordinary full-reactor verification and the complete
architecture profile own module-graph enforcement.

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

The role-state browser suite exercises Administration and Preferences through the
Settings menu. Those destinations are not entries in the primary responsive
selector; selector comparisons must ignore its empty disabled placeholder. At
narrow widths, task reachability is established by the visible task progression
or primary analysis action, not the current-task jump that the compact header
intentionally hides. These focused checks do not replace the full browser matrix
or the canonical verification command.

## Complete plugin profile

```bash
./mvnw -B verify -Pplugin-packaging-tests
```

This command runs the full reactor lifecycle, including ordinary unit tests,
before the independent SDK build and packaged plugin/optional-feature integration
tests. It therefore also needs Docker for the existing container-backed browser
tests and Helm for the build contracts. A focused Failsafe invocation proves the
selected packaging cases but does not prove this standalone lifecycle.

In CI, the `plugin-profile` job combines this profile with
`ci-plugin-packaging`:

```bash
./mvnw -B verify -Pplugin-packaging-tests,ci-plugin-packaging
```

That Maven-owned companion skips only taxonomy-app Surefire tests already covered
by the core and isolated app-test union. It preserves the complete plugin build,
SDK checks and both selected packaged Failsafe tests. It excludes the partial-lane
coverage policy; only the separate full-reactor aggregate job can enforce
coverage. The standalone profile above remains unchanged and runs ordinary
application tests.

The final Maven verification gate requires that job to succeed; failure,
cancellation, a skipped job or a missing result blocks completion. The job uses
the checked-in Maven profiles without a separate workflow-owned test selector.
Its `plugin-profile-verification` artifact retains the Maven log, Surefire and
Failsafe reports, independent SDK result and host/plugin identity evidence.
These results supplement the canonical `ci` profile and digest-bound UI shards;
overlapping test executions must not be added to the canonical test count.

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
| `ci-cd.yml` | Build one commit-bound application, run catalogued Maven app-test shards alongside core tests, aggregate full-reactor coverage after both, separate required production-image verification, CI plugin packaging checks, UI shards and other lanes, then gate all results and digest-bound evidence |
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
