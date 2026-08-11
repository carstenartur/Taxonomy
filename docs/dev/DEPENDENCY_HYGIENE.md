# Packaged Dependency Hygiene

The normal Maven lifecycle enforces the runtime dependency boundary. This is not
a GitHub-specific policy: `./mvnw verify` executes Maven Enforcer in every module
and evaluates the packaged CycloneDX component set through JUnit/Failsafe in the
final `taxonomy-build` reactor module.

## Enforced invariants

- No Apache PDFBox component below major version 3 may be packaged.
- `org.apache.pdfbox:xmpbox` is prohibited unless a reviewed exception exists.
- The unused Flexmark PDF converter is prohibited.
- OpenHTMLToPDF adapters whose artifact name contains `pdfbox` are prohibited.
- The CycloneDX SBOM must contain aligned `pdfbox`, `pdfbox-io`, and `fontbox`
  versions matching the centrally managed `pdfbox.version` property.
- Exception records must be complete, exact-coordinate, owner-assigned,
  unexpired, and have an objective removal condition.

Test-scoped fixtures are not matched by the compile/runtime Maven bans. They must
still be justified in the test that introduces them and must never enter the
packaged application.

## Distribution integrity

Taxonomy consumes `jgit-storage-hibernate` from its anonymous, immutable release
repository. A version is eligible for use only when the upstream release contains:

- Maven POM and binary/source/Javadoc artifacts for every public module;
- SHA-1 sidecars required by Maven Resolver compatibility;
- canonical SHA-256 and SHA-512 sidecars;
- a `releases/<version>.json` manifest recording every file, size and digest;
- successful anonymous local and remote resolution in the upstream release state
  machine before the tag is created.

Do not republish or mutate an already consumed version to repair missing metadata.
Publish a new release and update the centrally managed
`jgit-storage-hibernate.version` property instead. Taxonomy CI resolves the release
on a clean hosted runner, so missing POMs, binaries or checksum metadata are visible
in the canonical Maven evidence rather than hidden by a developer cache.

Generated WebJar metadata is managed explicitly when an aggregate package points to
an incomplete artifact version. The root `dependencyManagement` section must pin a
compatible release with complete metadata and explain the mediation; application
code must not add duplicate JavaScript packages to work around a Maven warning.

## Verification and diagnostics

The authoritative command is:

```bash
./mvnw -B verify -Pci
```

`DependencyHygienePolicyIT` runs after the shipped modules and the CycloneDX
aggregate have been packaged. When CycloneDX emitted the usable JSON/XML pair in a
module target directory, the test materializes it at the canonical root paths:

```text
target/taxonomy-sbom.json
target/taxonomy-sbom.xml
```

It then writes the stable human-readable decision to:

```text
target/dependency-hygiene-report.txt
```

Positive and negative JUnit fixtures cover legacy PDFBox versions, `xmpbox`, the
Flexmark and OpenHTMLToPDF adapters, missing and version-skewed intended artifacts,
exact reviewed exceptions, expired or incomplete ledgers, malformed/empty SBOMs,
and deterministic fallback materialization.

For dependency-tree diagnostics, run:

```bash
./mvnw -pl taxonomy-app dependency:tree \
  -Dscope=runtime \
  -Dincludes='org.apache.pdfbox:*,com.vladsch.flexmark:flexmark-pdf-converter,com.openhtmltopdf:*'

./mvnw -pl taxonomy-app dependency:tree \
  -Dincludes='io.github.carstenartur:*,org.webjars.npm:d3-hierarchy'
```

The CI build archives both the focused dependency tree and the SBOM validation
report as review evidence.

## Exception process

An exception is a last resort. A change must update both:

1. the Maven Enforcer `bannedDependencies/excludes` list for the exact coordinate; and
2. `.github/dependency-hygiene-exceptions.json` with the exact group, artifact,
   version, owner, rationale, ISO expiry date, and objective removal condition.

Example:

```json
{
  "group": "example.group",
  "name": "example-artifact",
  "version": "1.2.3",
  "owner": "github-login",
  "rationale": "Required temporarily for issue #123",
  "expires": "2026-12-31",
  "removalCondition": "Remove after upstream release 2.0 is adopted"
}
```

Expired, duplicate, incomplete, broad, or ownerless exception records fail the
JUnit-owned SBOM policy. An exception suppresses only its exact coordinate.
