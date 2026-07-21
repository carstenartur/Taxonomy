# Packaged Dependency Hygiene

The normal Maven lifecycle enforces the runtime dependency boundary. This is not
a GitHub-specific policy: `mvn verify` executes Maven Enforcer in every module.

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

## Verification and diagnostics

```bash
mvn verify

PDFBOX_VERSION=$(mvn help:evaluate \
  -Dexpression=pdfbox.version -q -DforceStdout)
python3 .github/scripts/check-dependency-hygiene.py \
  --sbom target/taxonomy-sbom.json \
  --expected-pdfbox-version "$PDFBOX_VERSION"

mvn -pl taxonomy-app dependency:tree \
  -Dscope=runtime \
  -Dincludes='org.apache.pdfbox:*,com.vladsch.flexmark:flexmark-pdf-converter,com.openhtmltopdf:*'
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
