# Guided installation validation — 2026-09-28

## Source and scope

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
