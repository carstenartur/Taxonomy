# Packaged Dependency Hygiene

The normal Maven lifecycle enforces the runtime dependency boundary. This is not
a GitHub-specific policy: `./mvnw verify` executes Maven Enforcer in every module.

## Enforced invariants

- No Apache PDFBox component below major version 3 may be packaged.
- `org.apache.pdfbox:xmpbox` is prohibited unless a real product feature needs it.
- The unused Flexmark PDF converter is prohibited.
- OpenHTMLToPDF adapters whose artifact name contains `pdfbox` are prohibited.
- The CycloneDX SBOM must contain aligned `pdfbox`, `pdfbox-io`, and `fontbox`
  versions matching the centrally managed `pdfbox.version` property.

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

```bash
./mvnw verify

PDFBOX_VERSION=$(./mvnw help:evaluate \
  -Dexpression=pdfbox.version -q -DforceStdout)
python3 .github/scripts/check-dependency-hygiene.py \
  --sbom target/taxonomy-sbom.json \
  --expected-pdfbox-version "$PDFBOX_VERSION"

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

Expired or incomplete exception records fail SBOM validation. Broad wildcard
exceptions, ownerless records, and exceptions without a removal condition are
not accepted.
