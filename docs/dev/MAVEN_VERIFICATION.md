# Maven Verification Authority

The required build contract is that a developer can reproduce every required
test through Maven with only Java and Maven installed. Test selection and
pass/fail rules belong in Maven; GitHub Actions should call those owners and
publish their results.

Test selection and case outcomes for the required reactor, script contracts,
browser scenarios, production image, real interoperability products,
observability benchmark and rendered document E2E now have JUnit/Maven owners.
The single canonical command below enables that complete local test scope.
A successful `test-local` run still proves only its documented smaller scope.

The separate requirement to provision every native prerequisite from only Java
and Maven remains open: Docker, Helm and the native document/product runtimes must
be available on the host. Remote deployment, publication and hosted security
services also require their corresponding endpoints and CI credentials.

## JUnit ownership of script contracts

`./mvnw -B verify -Pci -DrunOnnxTests=true` is the local entry point for the
required reactor, script contracts, browser scenarios, real product round trips,
production image assertions, observability benchmark and rendered document E2E. It requires the native
prerequisites below; the Java/Maven-only provisioning requirement remains open.
CI selects the same JUnit owners in the existing independent lanes.

`JavascriptContractTest` schedules every original callback from the explicit
`.mvn/javascript-junit-sources.txt` inventory. Node supplies JavaScript/DOM drivers;
`node:test` imports are redirected to callback registration and no Node test
runner is invoked. Async callbacks, cleanup hooks and nested outcomes are retained
as JUnit executions; unsupported APIs fail closed. The inventory checks `.github`
and every module's JavaScript test sources, including future CommonJS files.
The three legacy Python files retain all 22 assertion methods, called separately
by `PythonContractTest` without a Python test runner. Every npm test command points
to the JUnit owner.

In split CI, the existing `ui-contracts` job owns the nonbrowser JavaScript
contracts. The core companion excludes that class so the final report contains
one original execution of each contract. The standalone `ci` command retains
the class. Browser callbacks use their separate browser selection and remain
part of the UI shard evidence; Java class names and generated method indices
alone are not safe cross-report deduplication keys.

`UiScenarioIT` reports each selected browser scenario independently while sharing
the existing application groups. `InteroperabilityProductsIT` reports StrictDoc
and Archi independently. Driver failures retain observed per-case results;
unexecuted cases are explicit aborted JUnit cases and a separate integrity case
fails. `ProductionImageIT` builds the actual image and checks both OCI revision and
runtime identity directly in JUnit. The standalone lane owners are
`.github/ui-verification-pom.xml` profiles `contracts`, `shard`, `interoperability`
and `image`; none adds a workflow or reduces a required status.

In the unsharded reactor, `taxonomy-build` stages the packaged application's
commit-bound manifest during `pre-integration-test`, after `clean` and application
packaging. Split CI still downloads its independently packaged application.
Release verification binds `GITHUB_SHA` to the release version commit, which can
differ from the workflow's triggering snapshot commit. Staging rejects a source
commit that does not match the checkout; local runs without `GITHUB_SHA` use `HEAD`.

Published XML counters must match actual testcase occurrences and terminal
outcomes, including nested suite aggregates and parameterized/skipped cases.
Misplaced outcomes, ambiguous multiple outcomes, rerun/flaky evidence and nonzero
flake counters are rejected. Local and remote publication recount the raw XML
against its manifest and summary before accepting it; no XML counter is repaired.
Root `verify` independently invokes `check-junit-outcomes` after Failsafe to recount
every original reactor XML, so malformed evidence also fails locally.

### Opt-in suppression of inherited tests

App test classes may use `@IgnoreInheritedTests` when they reuse a concrete
fixture whose test methods already execute in the base class. The test-scoped
`PostDiscoveryFilter`, registered through Java's ServiceLoader, removes inherited
method descriptors from annotated classes before execution. It does not produce
disabled or skipped placeholders. Own ordinary and parameterized methods still
execute, and unannotated subclasses retain normal JUnit inheritance.

The initial opt-ins are `ReformulationListContractTest` and
`ReformulationBasePathContractTest`: the nine base cases execute once in
`ReformulationIsolationTest`, and each subclass contributes its own two cases.
Do not apply the annotation to suites that intentionally rerun a shared contract
against another configuration or implementation. This uses the public launcher
approach recommended in [JUnit discussion 4570](https://github.com/junit-team/junit-framework/discussions/4570).

## Toolchain

- Java 25
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

### GitHub UI system package cache

The single `requiresWebKit: true` shard restores or prepares its WebKit system
packages on its own runner, then installs them offline on that same runner.
Independent GitHub-hosted jobs may receive different image revisions during a
rollout; there is no cross-job package producer whose image must match by chance.
The exact cache key still includes the installer/shared APT helper/lockfile hash and that runner's
`ImageOS` and `ImageVersion` (there is no `runner.image` Actions context field).
An exact cache hit skips downloads; a cold miss resolves the missing dependency
closure with the pinned Playwright CLI and downloads the `.deb` files without
installing them. Partial-key hits and wrong-image cache keys are rejected.
Only after checksum-verified offline installation succeeds is a newly prepared
cache saved under that exact key. The five non-WebKit shards neither restore nor
prepare system packages. There is no online fallback from offline installation.

Package download and offline installation have separately labelled
**UI system packages / Paketinstallation** steps with an eight-minute budget.
The command timeout is 470 seconds so it can emit a specific error before the
step limit. Only the shard with `requiresWebKit: true` installs the cached system
packages and WebKit; the other shards install only Chromium and Firefox.
Maven still owns pinned Node/npm setup, browser binaries, scenario selection and
verification. The additional `process-resources -Pshard` invocation only prepares
the pinned CLI before the separate package step.

From `.github/`, `scripts/install-playwright-github.sh --only-download` prepares
the cache; `--offline-mode --system-packages-only` installs it without network
access. `--skip-webkit` omits WebKit and its system packages, and `--browsers-only`
leaves system packages to the preceding step. `TAXONOMY_PLAYWRIGHT_PACKAGES_DIR`
can override the default `.cache/playwright-system-packages` directory at the
repository root. The offline guarantee covers apt packages, not the separately
cached Playwright browser downloads.

### Document rendering package cache

Scenario acceptance, document E2E, documentation screenshots and release
verification use `.github/actions/document-rendering` before their long-running
verification steps. The action reuses `apt-packages.sh`, the same hosted mirror
preference, bounded APT retries and checksum-verified offline installation as
Playwright. This prevents direct `apt-get` calls in those workflows from bypassing
the existing native-package safeguards.

Document packages are cached separately from browser dependencies. The key binds
the installer/action hashes, runner OS, image revision, architecture and package
variant. Archives live in `runner.temp`, outside the checkout: APT's private
`partial/` directory must not enter repository-source inventories. Standalone
installer invocations must set `RUNNER_TEMP` or an explicit
`TAXONOMY_DOCUMENT_PACKAGES_DIR`. Only scenario acceptance needs `writer-draw`; the other consumers use
`writer` (LibreOffice Writer and Poppler). An exact hit skips both APT update and
downloads. A miss downloads the complete missing dependency closure once, then
installs it offline and saves the cache only after successful installation and
tool checks. Download and offline installation each have a 470-second command
budget. An offline failure never retries online. No cross-image or prefix cache
restores are accepted.

The two document-only browser preparations use the pinned installer's
`--chromium-only` mode. They retain Maven's prerequisite/test lifecycle, use the
hosted runner's Chromium system libraries and do not invoke `--with-deps` or
download Firefox/WebKit binaries for a Chromium-only check.

Validate a cold-cache and a cache-hit workflow run on hosted runners before
claiming download or installation timing improvements; local contract tests do
not establish those timings or a complete browser pass.

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
| Compile | `./mvnw compile` | Java 25 |
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

This companion skips repeated App and upstream-module Surefire executions already
owned by the complete core and isolated app-test union. It retains the
`taxonomy-build` Surefire guards, selected database Failsafe tests, their
missing-selector failure behavior, test compilation and packaging. Database profiles
already leave UI and build-policy integration tests disabled; the companion does
not change those settings or the database selectors. It disables the partial
aggregate coverage report, while JaCoCo instrumentation remains enabled. These
database lanes supplement the complete CI coverage aggregate and cannot replace
it. The standalone database commands in the table retain their ordinary App
tests and aggregate report; no companion is activated automatically.

The local ONNX reference workflow uses
`./mvnw -B verify -Ponnx,ci-onnx-reference -T 2`.
This explicit companion narrows only the App
default Surefire execution to `*Onnx*Test`, `*Onnx*Tests` and
`WorkerRuntimeFootprintTest`; it disables the unrelated isolated product demo.
It retains upstream unit tests, build-policy guards, model provisioning and both
Failsafe suites selected by `onnx`: `LocalOnnxPipelineIT` and `OnnxSeleniumIT`.
The workflow requires positive JUnit evidence for all seven selected unit suites
(39 cases) and both integration suites (nine cases each). It checks out the PR
head for reference measurements. No global tag filter is applied: reference unit
tests include untagged classes, and the integration suites also require Docker.
The companion disables partial aggregate coverage; complete coverage remains
owned by the canonical CI aggregate. Standalone `-Ponnx` and canonical `-Pci`
retain their full App test scope and product demo.

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
recovery/restart, browser acceptance and architecture scenario classes to isolated
Maven runners. Their
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
lifecycle. All isolated app runners also build `taxonomy-tooling` with
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

### Remaining gaps in the Java/Maven-only provisioning requirement

| Gap | Current source of the difference |
| --- | --- |
| Container and database runtimes | Testcontainers needs an available container engine; Maven does not provision one. `ci` excludes SQL Server and Oracle, whose separate profiles still need it. |
| Helm-dependent tests | `HelmArtemisContractTest`, `HelmConstrainedSmokeContractTest` and `SetupInfrastructureTest` depend on installed Helm. Some missing-tool decisions depend on `CI` or skip the test locally. |
| Native product prerequisites | The JUnit-owned interoperability round trips need pipx, StrictDoc dependencies, Xvfb and the pinned Archi runtime. Docker image and browser checks need their real native runtimes. |
| Native rendering prerequisites | Canonical `ci` enables observability and document E2E including real DOCX rendering. LibreOffice and Poppler must be installed; the document and release workflows provision them before verification. |
| Remote acceptance endpoints | Root `verify` recounts original reactor and native-browser XML after Failsafe. Remote publication and deployment checks additionally require the corresponding external endpoint and commit-bound CI artifacts. |
| Deployment and hosted security services | Live Kubernetes deployment/recovery and hosted CodeQL/Trivy publication still depend on the corresponding infrastructure and credentials. Production image build/identity assertions are included in canonical Maven. |

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
workflow YAML. The import profiles select named tests in their owning modules;
`-am` builds the required reactor dependencies.
`surefire.failIfNoSpecifiedTests=false` permits upstream modules without matching
selected tests; failures in the selected tests still fail the build:

```bash
./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Pdocument-import-tests -pl taxonomy-app -am -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -Parchimate-import-tests -pl taxonomy-app -am -Dsurefire.failIfNoSpecifiedTests=false
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

That Maven-owned companion skips repeated App and upstream-module Surefire tests
already covered by the core and isolated app-test union. It retains the
`taxonomy-build` Surefire guards, complete plugin build, SDK checks and both
selected packaged Failsafe tests. It excludes the partial-lane
coverage policy; only the separate full-reactor aggregate job can enforce
coverage. The standalone profile above remains unchanged and runs ordinary
application tests.

The parentless Mermaid plugin declares matching companion profiles explicitly.
Its independent SDK verification continues to use standalone `verify`, including
the plugin's unit tests.

The final Maven verification gate requires that job to succeed; failure,
cancellation, a skipped job or a missing result blocks completion. The job uses
the checked-in Maven profiles without a separate workflow-owned test selector.
Its `plugin-profile-verification` artifact retains the Maven log, Surefire and
Failsafe reports, independent SDK result and host/plugin identity evidence.
In split CI this lane is the sole owner of `ExternalPluginPackagedIT` and
`OptionalFeaturePackagedIT`; the core companion excludes them. Their original
JUnit reports enter the final quality inventory once with matching source/build
identity. Missing, failed, skipped or inconsistent required evidence prevents
publication. Supplemental repeated build-guard reports are not added to the
canonical test count. The unsliced `ci` command still runs both packaged suites.

### Document browser preparation

The document E2E workflow prepares its pinned browser runtime with
`pre-integration-test -DskipTests`. This preparation reaches the existing
frontend installation goal without executing the build guards a second time.
The subsequent `verify -Pdocument-template-report-e2e` runs those guards and the
selected document integration test with normal test execution enabled.

## UI process isolation and timings

The Maven-owned launcher executes every selected browser scenario but does not
restart the packaged application when scenarios have the same isolation needs:

- read-only `ui` and `accessibility` checks share one application at the same servlet context;
- `ui/login-context-chromium` uses a separate application mounted at `/taxonomy`
  and runs the DE/EN login flow with browser JavaScript disabled;
- the special-modes suite keeps a fresh application because it performs a real
  analysis before testing partial-result, text-spacing and offline states;
- role/state profiles share one application only with profiles for the same role;
- each primary mutation workflow keeps its own fresh application.

The complete default matrix contains 19 scenarios in 9 application starts: the
18 existing scenarios retain their eight groups, and the context-path login
profile adds one group. Execution remains sequential. Scenarios that
mutate application state are not allowed to share with read-only browser checks,
primary mutation workflows remain isolated, and a role/state scenario can never
share an application with another role. These rules are executable contracts in
`.github/scripts/ui-suite-plan.test.mjs` rather than implicit CI behavior.

Each scenario contains `application-log.txt`, which points to the log of its
isolation group. The launcher also writes `target/ui-verification/timings.json`
with the actual servlet context, application startup duration, scenario duration, group duration, total
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

### Measured critical-path remediation (17 October 2026)

The audited run spent 58m49s in core plus 8m22s aggregating coverage. Eleven
existing source suites moved, without changing any assertions, into the existing
browser (six suites, approximately 414 recorded suite seconds) and recovery
(five suites, approximately 288 seconds) owners. `MeasuredShardBalanceTest` and the
complete application inventory still require exactly one owner and matching
source tags. These are expected critical-path savings, pending a new CI timing.

The root POM no longer runs Boot's unconsumed duplicate aggregate SBOM; the package
aggregate and packaged module SBOMs remain. Coverage replay retains its exact
source/tree/archive/file/probe digest checks, then reuses the verified SBOM and
frontend runtime instead of regenerating/reinstalling them. It still merges all
shard probes, aggregates the full reactor and runs unchanged coverage thresholds.

The unchanged 76 nonbrowser sources contain 1,214 original Node cases; the JUnit
owner executes the same 1,214 cases with zero failures/skips. The three Python
files contain 22 original methods and retain all 22 JUnit cases. The source
inventory also owns the two native-browser files, whose actual execution requires
the browser lane. A sampled full 1,214-case JUnit run held at most one Node source
driver at once (16,892 KiB maximum combined sampled RSS, 100 ms sampling).

The parser report discrepancy was reproduced locally: 51 declared cases versus
54 actual cases. Surefire's enclosing-suite accounting collapsed three duplicated
method names across nested provider classes. Giving those three methods distinct
provider-specific names retains every assertion/invocation and produces 54/54;
the original inconsistent CI XML remains rejected rather than repaired.

Root npm provisioning is registered as `runAlways` because `node_modules` is not
an attached cache output. A real warm-root-cache replay with that directory
removed restored the root cache entry and still ran the pinned `npm ci`, restoring
all six locked packages. Tooling-module cache reuse is disabled because its JUnit
owners read repository and JavaScript inputs outside that module's cache key.
The auxiliary UI/image/product POM imports the pinned Boot dependency BOM and has
independent version `1`, so application release/next-development bumps cannot
leave a stale parent reference.
