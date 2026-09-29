# Guided installation validation — 2026-09-28

## Third review: configuration paths and verified non-reproductions — 2026-09-29

Baseline: `2bc9426f2427f099ba9283a506a0afa39f83eb14`, source tree
`295e83f7bfef0b427dff51af47655da56c44f35b`, exported from the exact commit by
GitHub diagnostic run `36515861593`. The local source tree matches that tree hash.

Two new findings were reproduced and fixed without relaxing existing policies:

- A guided Keycloak installation could restore the removed local bootstrap
  environment variable through an additional Secret reference. Helm now rejects
  that name explicitly; unrelated Secret-backed additions remain supported.
- Java preflight skipped custom URL validation when provider selection was blank
  or a different provider was selected. Configured custom endpoints are now checked
  independently of provider selection. A URL supplied for automatic custom selection
  also requires its model; no cloud-provider selection priority or API-key policy
  has been changed.

Observed local red/green verification with Java 21, UTF-8 and real Helm 3.21.0:

| Existing Maven-owned suite | Same final tests on baseline | Corrected source |
| --- | --- | --- |
| SetupReviewRegressionCases | 107 cases, 17 failures | 107 cases, 0 failures |
| SetupStartupRegressionCases | 31 cases, 3 failures | 31 cases, 0 failures |
| verify-setup-security.sh | 118 cases, 2 failures | 118 cases, 0 failures |

Existing 54 setup contracts, 15 Spring contracts, 10 native packaging command
contracts, Helm lint and existing render contracts also passed. The startup
regressions exercise actual ConfigData and command exit codes, including automatic
provider selection and diagnostics that do not expose fixture credentials.

Two other review statements were checked rather than blindly applied:

- The database matrix uses **file-backed** HSQLDB, not `mem:`. All original 67
  regression cases pass on the unchanged baseline, including all four HSQLDB alias
  combinations with `production`. Removing `production` is unnecessary and would
  reduce coverage. A clarifying comment was added; the independent volatile-memory
  production rejection remains unchanged.
- The earlier unconditional local-password guard has already been corrected in
  the baseline. Its real Spring ApplicationRunner startup succeeds without a local
  password in Keycloak mode and still rejects missing central configuration and a
  weak machine token. This was rerun, including the real rendered Helm environment;
  no further weakening or disabling of the guard is warranted.

These focused runs compile the actual changed sources against dependency JARs from
an existing, checksummed CI application artifact (provenance in the previous section).
They are not a replacement for the complete PR-head Maven, integration and security
checks. Both local full-build commands (`./mvnw verify -DexcludedGroups=real-llm`
and `./mvnw -B verify -Pci`) were attempted and stopped because the wrapper could
not download Maven 3.9.16 in this network-restricted container. No reduced Maven
command was substituted. Full CI and the maintainer's review decision remain required before merge;
no installer qualification, real OIDC login, or production MSSQL acceptance is
inferred from these tests. No extra product workflow or verification authority is added.

## Second review: production login, custom endpoints and native startup

Regression baseline: `f5993eba133cd16b006f4548ae4f66bd54708f4a`.
A full tracked-source archive was exported by diagnostic run `36485033050`.
The matching CI application artifact identifies the PR merge-test commit
`29ab9f81d57c10316cd33c7460e1bc7d414cfdd8` and JAR SHA-256
`74a1f9587664cf650f2e879c4044d2b2fcfec82fbb7a920e1ae6cd67b02f33b5`.
Its dependency JARs were used to compile the actual changed setup/guard sources;
this is **not** a new complete application build after the fixes.

Observed locally with JDK 21, a UTF-8 locale and real Helm 3.21.0:

- The final 27-case `SetupStartupRegressionCases` fails **15 cases** with the
  unchanged production sources and passes all 27 with the fixes. It exercises
  actual Spring ConfigData/constructor injection and the ApplicationRunner
  lifecycle, including Keycloak without a local password, continued machine-token
  checks, rejected central-login conflicts, mandatory native config with optional
  overlays, CLI/JVM/JSON precedence, and generated model metadata.
- The real rendered Keycloak Deployment environment, with its central Secret
  resolved to a test-only fixture and no local bootstrap mapping, fails startup
  against the old guard and passes against the corrected guard. A short machine
  token still fails at the **machine-token** check. The test starts only the
  production guard in Spring, not the full application, database or identity
  provider. It does not prove SSO/login, role mapping or cluster TLS acceptance.
- The expanded security render suite reports **114 cases / 50 failures** on the
  old chart and **114 cases / 0 failures** on the corrected chart. New cases cover
  custom endpoints in existing/local/Keycloak modes, with and without Schema,
  exact-loopback HTTP and refusal of credentials/query/fragment/backslash input.
  Existing setup render contracts and Helm lint also pass.
- Existing Java suites also pass: 54 setup contracts, 67 first-review regressions,
  15 Spring contracts and 10 native package-command contracts. A first local run
  in the POSIX locale could not encode the existing Unicode path fixture; rerunning
  with `LC_ALL=C.UTF-8` passed without modifying that test or weakening its assertion.
- Both the documented `./mvnw verify -DexcludedGroups=real-llm` and the current
  catalogue command `./mvnw -B verify -Pci` were attempted. This container could not
  download Maven 3.9.16 due to unavailable network access. Neither command completed
  locally. The full new-head CI remains required, independently of these diagnostics.

`SetupInfrastructureTest` owns both the new startup cases and the rendered-startup
contract. No additional product CI authority, disabled tests, weaker thresholds,
production MSSQL qualification, or native installer release approval is introduced.
Installation, native packaging, Helm and English/German Keycloak documentation
have been aligned with the actual configuration rules.

## Initial implementation evidence (historical)

### Source and scope

Implementation base: `6b6394b3013843f1186166b3313ffe54d538663c` on Taxonomy main.
The local workspace is a partial export, not a full Maven checkout. No merge,
release publication or deployment is justified by the checks below alone.

## Executed checks

- 54 dependency-free `SetupContractCases` checks passed with Java 21. These cover
  persistent-schema destruction, authentication/provider requirements, fixed-message
  diagnostics, explicit bounded probes, local-only configuration, private secrets,
  atomic config publication, Unicode/space paths and refusal to overwrite.
- 15 `SetupSpringCases` checks passed with real Spring Boot 4.1.1 dependencies:
  ConfigData profiles/configtree, file/JSON/CLI precedence, no DB creation during
  preflight, unchanged ordinary startup, native missing-config protection and
  rejection of destructive native overrides.
- 10 `PackageTaxonomyCases` checks passed for package-command construction,
  platform restrictions, numeric versions, stable Windows upgrade identity,
  argument boundaries and a native JVM marker that survives user arguments.
- JSON Schema Draft 7 validation and question-field uniqueness passed. Invalid
  authentication modes, raw config credentials and an out-of-range port were rejected.
  `bash -n deploy/helm/taxonomy/verify-setup.sh` passed; actual Helm rendering was
  not available locally. Canonical CI installs Helm before the Maven-owned test.
- All new production Java classes, including the Spring adapter and application
  entry point, compiled against the real artifact dependency set. This caught and
  corrected the Boot 4 package move to `org.springframework.boot.support`.
  A failing Unicode properties round-trip test caught and corrected UTF-8 writer
  output by using Java properties' escaped byte-stream representation.

## Linux native runtime diagnostic

GitHub artifact `10959860682` from workflow run `36398957809` supplied the executable
application/dependencies. Its own manifest identifies source commit
`3e115e3d8043299d76b110e379d1f662b8d6a6bc` and input JAR SHA-256
`5808408b2d699b22d5e6e9cef1bdf027068f28f8f5cee7c90ea8fc276e8fc996`.
This is **not the new PR's exact source build**. Newly compiled setup/entry-point
classes were overlaid solely for a local diagnostic, not a release artifact.

The actual jpackage app image, with its bundled runtime, passed:

1. `--setup-help` through the native launcher.
2. Unattended local configuration (exit 0), refusal to configure the same directory
   again (exit 2), static checking (exit 0), and no DB files created by the check.
3. Actual application startup and administrator login on loopback.
4. Graceful shutdown with HSQLDB files retained, startup using the same data, and
   successful login with the original administrator password after changing only
   the bootstrap-secret file. The replacement bootstrap password did not authenticate.
5. Graceful shutdown and cleanup of the test server; no application was left running.

This is evidence for launcher/configuration wiring and initial account persistence,
not a full migration, architecture-history, signed-installer or Windows test.

## Still required before completion/release

The full Maven verification and exact-head Helm render checks must pass remotely.
Review new branch coverage rather than lowering any existing quality gate.
Run the manual native-package workflow on supported Linux and Windows runners,
then verify real installation, previous-release upgrade, uninstall/data retention,
service integration and signing separately. The first PR does not implement an
all-parameter graphical local wizard or automatic OS service registration.
