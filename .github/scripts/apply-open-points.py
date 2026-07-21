#!/usr/bin/env python3
"""One-shot branch-local migration for QA issues #410-#412 and PR #407.

The accompanying temporary workflow runs this script once, commits the resulting
repository changes, and removes both migration files from the final branch.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n", encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}: {old[:80]!r}")
    write(path, content.replace(old, new, 1))


def append_once(path: str, marker: str, addition: str) -> None:
    content = read(path)
    if marker in content:
        return
    write(path, content.rstrip() + "\n\n" + addition.strip() + "\n")


# ---------------------------------------------------------------------------
# #410: permanent dependency hygiene in the normal Maven lifecycle
# ---------------------------------------------------------------------------
replace_once(
    "pom.xml",
    "        <jacoco.version>0.8.15</jacoco.version>\n",
    "        <jacoco.version>0.8.15</jacoco.version>\n"
    "        <maven-enforcer.version>3.6.3</maven-enforcer.version>\n",
)
replace_once(
    "pom.xml",
    "            <!-- SBOM generation (CycloneDX) — required by BSI for software supply chain transparency -->",
    "            <!-- Dependency hygiene is a normal Maven invariant, not a GitHub-only check.\n"
    "                 Test-scoped fixtures are intentionally outside these packaged-runtime bans. -->\n"
    "            <plugin>\n"
    "                <groupId>org.apache.maven.plugins</groupId>\n"
    "                <artifactId>maven-enforcer-plugin</artifactId>\n"
    "                <version>${maven-enforcer.version}</version>\n"
    "                <executions>\n"
    "                    <execution>\n"
    "                        <id>enforce-packaged-dependency-hygiene</id>\n"
    "                        <phase>validate</phase>\n"
    "                        <goals><goal>enforce</goal></goals>\n"
    "                        <configuration>\n"
    "                            <rules>\n"
    "                                <bannedDependencies>\n"
    "                                    <searchTransitive>true</searchTransitive>\n"
    "                                    <excludes>\n"
    "                                        <exclude>org.apache.pdfbox:*:(,3.0.0):jar:compile</exclude>\n"
    "                                        <exclude>org.apache.pdfbox:*:(,3.0.0):jar:runtime</exclude>\n"
    "                                        <exclude>org.apache.pdfbox:xmpbox:*:jar:compile</exclude>\n"
    "                                        <exclude>org.apache.pdfbox:xmpbox:*:jar:runtime</exclude>\n"
    "                                        <exclude>com.vladsch.flexmark:flexmark-pdf-converter:*:jar:compile</exclude>\n"
    "                                        <exclude>com.vladsch.flexmark:flexmark-pdf-converter:*:jar:runtime</exclude>\n"
    "                                        <exclude>com.openhtmltopdf:*pdfbox*:*:jar:compile</exclude>\n"
    "                                        <exclude>com.openhtmltopdf:*pdfbox*:*:jar:runtime</exclude>\n"
    "                                    </excludes>\n"
    "                                    <message>Packaged dependency hygiene failed. See docs/dev/DEPENDENCY_HYGIENE.md before adding an exception.</message>\n"
    "                                </bannedDependencies>\n"
    "                            </rules>\n"
    "                        </configuration>\n"
    "                    </execution>\n"
    "                </executions>\n"
    "            </plugin>\n"
    "            <!-- SBOM generation (CycloneDX) — required by BSI for software supply chain transparency -->",
)
replace_once(
    "pom.xml",
    "            <!-- Pinned to 3.21.0 (Doxia Sitetools 2.x). Fluido skin bundled as\n"
    "                 plugin dependency so mvn site resolves it from Maven Central\n"
    "                 (cached in .m2) instead of downloading the default skin from\n"
    "                 www.apache.org, which is blocked in CI. -->",
    "            <!-- The site and skin versions are controlled by the properties above.\n"
    "                 Bundling the skin as a plugin dependency keeps site generation\n"
    "                 reproducible and avoids resolving an implicit remote default. -->",
)
replace_once(
    "pom.xml",
    "            <!-- Pin surefire-report (Doxia 2.x) to match maven-site-plugin:3.21.0.\n"
    "                 Fluido skin 2.0.0-M11 declared here so both plugins share the\n"
    "                 same skin version and Doxia generation. -->",
    "            <!-- Keep Surefire Report on the same managed Doxia/Fluido family as\n"
    "                 the site plugin; literal versions belong only in properties. -->",
)
replace_once(
    "pom.xml",
    "            <!-- Pin project-info-reports to Doxia 2.x to match maven-site-plugin:3.21.0.\n"
    "                 Without this pin, Spring Boot 4.0.4 manages version 3.4.5 (Doxia 1.x),\n"
    "                 which reads description/url from the effective POM inheritance chain\n"
    "                 (spring-boot-starter-parent) instead of the project's own values. -->",
    "            <!-- Keep Project Info Reports on the managed Doxia 2.x family. This\n"
    "                 prevents parent dependency management from selecting an older\n"
    "                 report stack that misreads project metadata. -->",
)

write(
    ".github/scripts/check-dependency-hygiene.py",
    r'''#!/usr/bin/env python3
"""Validate the packaged CycloneDX SBOM against Taxonomy dependency policy."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
from pathlib import Path


def component_key(component: dict) -> tuple[str, str, str]:
    return (
        str(component.get("group", "")),
        str(component.get("name", "")),
        str(component.get("version", "")),
    )


def version_major(version: str) -> int | None:
    match = re.match(r"^(\d+)", version)
    return int(match.group(1)) if match else None


def load_exceptions(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("dependency hygiene exceptions must be a JSON array")
    today = dt.date.today()
    valid = []
    for item in data:
        required = {"group", "name", "version", "owner", "rationale", "expires", "removalCondition"}
        missing = sorted(required - set(item))
        if missing:
            raise ValueError(f"exception is missing fields {missing}: {item}")
        expiry = dt.date.fromisoformat(item["expires"])
        if expiry < today:
            raise ValueError(f"expired dependency exception: {item['group']}:{item['name']}:{item['version']}")
        valid.append(item)
    return valid


def is_excepted(component: dict, exceptions: list[dict]) -> bool:
    group, name, version = component_key(component)
    return any(
        item["group"] == group and item["name"] == name and item["version"] == version
        for item in exceptions
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", default="target/taxonomy-sbom.json")
    parser.add_argument("--expected-pdfbox-version")
    parser.add_argument("--exceptions", default=".github/dependency-hygiene-exceptions.json")
    parser.add_argument("--report", default="target/dependency-hygiene-report.txt")
    args = parser.parse_args()

    sbom_path = Path(args.sbom)
    if not sbom_path.is_file():
        raise SystemExit(f"SBOM not found: {sbom_path}")
    exceptions = load_exceptions(Path(args.exceptions))
    components = json.loads(sbom_path.read_text(encoding="utf-8")).get("components", [])

    banned: list[dict] = []
    pdfbox_components: list[dict] = []
    for component in components:
        group, name, version = component_key(component)
        if group == "org.apache.pdfbox":
            pdfbox_components.append(component)
            major = version_major(version)
            if major is None or major < 3 or name == "xmpbox":
                banned.append(component)
        if group == "com.vladsch.flexmark" and name == "flexmark-pdf-converter":
            banned.append(component)
        if group.startswith("com.openhtmltopdf") and "pdfbox" in name.lower():
            banned.append(component)

    banned = [component for component in banned if not is_excepted(component, exceptions)]
    intended_names = {"pdfbox", "pdfbox-io", "fontbox"}
    actual_intended = {
        name: version
        for group, name, version in map(component_key, pdfbox_components)
        if group == "org.apache.pdfbox" and name in intended_names
    }
    missing = sorted(intended_names - set(actual_intended))
    versions = sorted(set(actual_intended.values()))
    expected_mismatch = (
        args.expected_pdfbox_version
        and any(version != args.expected_pdfbox_version for version in actual_intended.values())
    )

    lines = ["Taxonomy packaged dependency hygiene", ""]
    for group, name, version in sorted(map(component_key, pdfbox_components)):
        lines.append(f"PDFBox family: {group}:{name}:{version}")
    lines.append("")
    if exceptions:
        lines.append(f"Active reviewed exceptions: {len(exceptions)}")
    if banned:
        lines.append("BANNED packaged components:")
        lines.extend(f"- {':'.join(component_key(component))}" for component in banned)
    if missing:
        lines.append("Missing intended PDFBox components: " + ", ".join(missing))
    if len(versions) > 1:
        lines.append("PDFBox family versions are not aligned: " + ", ".join(versions))
    if expected_mismatch:
        lines.append(
            f"PDFBox family does not match expected version {args.expected_pdfbox_version}: "
            + ", ".join(versions)
        )

    failed = bool(banned or missing or len(versions) > 1 or expected_mismatch)
    lines.append("")
    lines.append("Result: FAIL" if failed else "Result: PASS")
    report = "\n".join(lines) + "\n"
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(report, encoding="utf-8")
    print(report, end="")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
''',
)
write(".github/dependency-hygiene-exceptions.json", "[]\n")
write(
    "docs/dev/DEPENDENCY_HYGIENE.md",
    r'''# Packaged Dependency Hygiene

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

1. the Maven Enforcer `includes` list for the exact coordinate; and
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
''',
)


# ---------------------------------------------------------------------------
# #412: read-only verification and isolated write-capable publication
# ---------------------------------------------------------------------------
write(
    ".github/workflows/ci-cd.yml",
    r'''name: CI / CD

on:
  push:
    branches: [ "**" ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

permissions:
  contents: read

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository_owner }}/taxonomy
  BGE_MODEL_REVISION: 5c38ec7c405ec4b44b94cc5a9bb96e735b38267a

jobs:
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest
    permissions:
      contents: read
      checks: write
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Download and verify pinned embedding model
        env:
          MODEL_REVISION: ${{ env.BGE_MODEL_REVISION }}
        run: bash .github/scripts/download-embedding-model.sh

      - name: Build and run tests
        run: mvn -q install -DexcludedGroups="real-llm"
        env:
          TAXONOMY_EMBEDDING_MODEL_DIR: ${{ github.workspace }}/models/bge-small-en-v1.5
          TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: 'false'

      - name: Verify packaged dependency hygiene
        run: |
          PDFBOX_VERSION=$(mvn help:evaluate -Dexpression=pdfbox.version -q -DforceStdout)
          mvn -q -pl taxonomy-app dependency:tree \
            -Dscope=runtime \
            -Dincludes='org.apache.pdfbox:*,com.vladsch.flexmark:flexmark-pdf-converter,com.openhtmltopdf:*' \
            -DoutputFile=../target/dependency-hygiene-tree.txt
          python3 .github/scripts/check-dependency-hygiene.py \
            --sbom target/taxonomy-sbom.json \
            --expected-pdfbox-version "$PDFBOX_VERSION" \
            --report target/dependency-hygiene-report.txt

      - name: Generate SBOM companion metadata
        if: always()
        run: |
          python3 .github/scripts/generate-vex.py \
            --sbom target/taxonomy-sbom.json \
            --output target/taxonomy-vex.json
          echo "### SBOM companion metadata" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          if [ -f target/taxonomy-vex.json ]; then
            echo "Generated: \`target/taxonomy-vex.json\`" >> "$GITHUB_STEP_SUMMARY"
            echo "Assessment status: **not-assessed** — this is not a vulnerability scan or exploitability statement." >> "$GITHUB_STEP_SUMMARY"
          else
            echo "SBOM was unavailable; companion metadata was not generated." >> "$GITHUB_STEP_SUMMARY"
          fi

      - name: Upload SBOM and dependency evidence
        uses: actions/upload-artifact@v7
        if: always()
        with:
          name: sbom-dependency-evidence
          path: |
            target/taxonomy-sbom.json
            target/taxonomy-sbom.xml
            target/taxonomy-vex.json
            target/dependency-hygiene-tree.txt
            target/dependency-hygiene-report.txt
            models/bge-small-en-v1.5/MODEL_PROVENANCE.txt
          if-no-files-found: warn
          retention-days: 90

      - name: Publish authoritative JUnit results
        uses: dorny/test-reporter@v3
        if: always()
        continue-on-error: true
        with:
          name: Maven Tests
          path: '**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml'
          reporter: java-junit
          fail-on-error: false
          max-annotations: '50'

      - name: Generate test HTML report
        if: always()
        run: |
          mkdir -p target/site
          mvn surefire-report:report --no-transfer-progress -DskipTests -Daggregate=true || true
          if [ ! -f target/site/surefire-report.html ]; then
            REPORT=$(find . -name 'surefire-report.html' -type f | head -n 1 || true)
            if [ -n "$REPORT" ]; then
              cp "$REPORT" target/site/surefire-report.html
            fi
          fi
          if [ -f target/site/surefire-report.html ]; then
            sed -i "s|</body>|<!-- Build: ${{ github.sha }} $(date -u +%Y-%m-%dT%H:%M:%SZ) --></body>|" \
              target/site/surefire-report.html
          else
            echo "::warning::HTML test report unavailable. GitHub Checks/JUnit XML remain authoritative."
          fi

      - name: Generate badge JSON files
        if: always()
        run: |
          python3 - <<'PY'
          import glob
          import json
          import os
          import xml.etree.ElementTree as ET

          covered = missed = 0
          try:
              for path in glob.glob('**/target/site/jacoco/jacoco.xml', recursive=True):
                  root = ET.parse(path).getroot()
                  for counter in root.iter('counter'):
                      if counter.get('type') == 'INSTRUCTION':
                          covered += int(counter.get('covered', 0))
                          missed += int(counter.get('missed', 0))
              total = covered + missed
              percentage = round(covered / total * 100) if total else 0
              color = 'brightgreen' if percentage >= 80 else 'yellow' if percentage >= 60 else 'red'
              coverage_badge = {
                  'schemaVersion': 1, 'label': 'coverage',
                  'message': f'{percentage}%', 'color': color,
              }
          except Exception as error:
              print(f'Coverage badge generation failed: {error}')
              coverage_badge = {
                  'schemaVersion': 1, 'label': 'coverage',
                  'message': 'unknown', 'color': 'lightgrey',
              }
          os.makedirs('taxonomy-app/target/site/jacoco', exist_ok=True)
          with open('taxonomy-app/target/site/jacoco/badge.json', 'w', encoding='utf-8') as handle:
              json.dump(coverage_badge, handle)

          try:
              files = (
                  glob.glob('**/target/surefire-reports/TEST-*.xml', recursive=True)
                  + glob.glob('**/target/failsafe-reports/TEST-*.xml', recursive=True)
              )
              tests = failures = errors = skipped = 0
              for path in files:
                  root = ET.parse(path).getroot()
                  tests += int(root.get('tests', 0))
                  failures += int(root.get('failures', 0))
                  errors += int(root.get('errors', 0))
                  skipped += int(root.get('skipped', 0))
              failed = failures + errors
              if not files:
                  message, color = 'no reports', 'lightgrey'
              elif failed:
                  message, color = f'{failed}/{tests} failed', 'red'
              else:
                  message, color = f'{tests - skipped} passed', 'brightgreen'
              tests_badge = {
                  'schemaVersion': 1, 'label': 'tests',
                  'message': message, 'color': color,
              }
          except Exception as error:
              print(f'Tests badge generation failed: {error}')
              tests_badge = {
                  'schemaVersion': 1, 'label': 'tests',
                  'message': 'unknown', 'color': 'lightgrey',
              }
          os.makedirs('target/site', exist_ok=True)
          with open('target/site/badge.json', 'w', encoding='utf-8') as handle:
              json.dump(tests_badge, handle)
          PY

      - name: Stage immutable report artifact
        if: always()
        run: |
          rm -rf target/quality-reports
          mkdir -p target/quality-reports/tests target/quality-reports/coverage
          if [ -d target/site ]; then cp -a target/site/. target/quality-reports/tests/; fi
          if [ -d taxonomy-app/target/site/jacoco ]; then
            cp -a taxonomy-app/target/site/jacoco/. target/quality-reports/coverage/
          fi

      - name: Upload report publication artifact
        uses: actions/upload-artifact@v7
        if: always()
        with:
          name: quality-reports
          path: target/quality-reports/**
          if-no-files-found: warn
          retention-days: 14

      - name: Report URLs
        if: always()
        run: |
          echo "### Reports" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo "[View Test Report](https://${{ github.repository_owner }}.github.io/${{ github.event.repository.name }}/tests/surefire-report.html)" >> "$GITHUB_STEP_SUMMARY"
          echo "[View Coverage Report](https://${{ github.repository_owner }}.github.io/${{ github.event.repository.name }}/coverage/)" >> "$GITHUB_STEP_SUMMARY"

      - name: Upload JAR artifact
        uses: actions/upload-artifact@v7
        with:
          name: app-jar
          path: taxonomy-app/target/taxonomy-app-*.jar
          retention-days: 1

  publish-reports:
    name: Publish reports
    needs: build-and-test
    if: >-
      github.event_name == 'push' &&
      github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
    runs-on: ubuntu-latest
    permissions:
      contents: write
    concurrency:
      group: taxonomy-quality-report-publication
      cancel-in-progress: false
    steps:
      - name: Download verified report artifact
        uses: actions/download-artifact@v7
        with:
          name: quality-reports
          path: quality-reports

      - name: Publish test report to GitHub Pages
        uses: peaceiris/actions-gh-pages@v4
        if: hashFiles('quality-reports/tests/surefire-report.html') != ''
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: quality-reports/tests
          destination_dir: tests
          keep_files: true
          user_name: github-actions[bot]
          user_email: github-actions[bot]@users.noreply.github.com
          commit_message: 'Deploy Surefire test report ${{ github.sha }}'

      - name: Publish coverage report to GitHub Pages
        uses: peaceiris/actions-gh-pages@v4
        if: hashFiles('quality-reports/coverage/**') != ''
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: quality-reports/coverage
          destination_dir: coverage
          keep_files: true
          user_name: github-actions[bot]
          user_email: github-actions[bot]@users.noreply.github.com
          commit_message: 'Deploy JaCoCo coverage report ${{ github.sha }}'

  publish-image:
    name: Publish Docker Image
    needs: build-and-test
    runs-on: ubuntu-latest
    if: >
      github.event_name == 'push' &&
      (
        github.ref == format('refs/heads/{0}', github.event.repository.default_branch) ||
        startsWith(github.ref, 'refs/tags/v')
      )
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v4
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract Docker metadata
        id: meta
        uses: docker/metadata-action@v6
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: |
            type=ref,event=branch
            type=ref,event=tag
            type=sha,prefix=sha-
            type=raw,value=latest,enable=${{ github.ref == format('refs/heads/{0}', github.event.repository.default_branch) }}
          annotations: |
            org.opencontainers.image.title=Taxonomy Architecture Analyzer
            org.opencontainers.image.description=Spring Boot web application for C3-taxonomy catalogue analysis
            org.opencontainers.image.licenses=MIT
            org.opencontainers.image.vendor=Carsten Hammer

      - name: Build and push Docker image
        uses: docker/build-push-action@v7
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          build-args: |
            BUILD_DATE=${{ fromJSON(steps.meta.outputs.json).labels['org.opencontainers.image.created'] }}
            VCS_REF=${{ github.sha }}
            VERSION=${{ steps.meta.outputs.version }}

  deploy-render:
    name: Deploy to Render
    needs: publish-image
    runs-on: ubuntu-latest
    if: >
      github.ref == format('refs/heads/{0}', github.event.repository.default_branch) &&
      github.event_name == 'push'
    steps:
      - name: Trigger Render deploy hook
        env:
          HOOK_URL: ${{ secrets.RENDER_DEPLOY_HOOK_URL }}
        if: ${{ env.HOOK_URL != '' }}
        run: |
          curl -fsSL --retry 3 "$HOOK_URL"
          echo "Render deployment triggered."
''',
)

write(
    ".github/workflows/documentation-screenshots.yml",
    r'''name: Documentation Screenshots

on:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: taxonomy-documentation-screenshots-main
  cancel-in-progress: false

env:
  BGE_MODEL_REVISION: 5c38ec7c405ec4b44b94cc5a9bb96e735b38267a

jobs:
  generate:
    name: Generate screenshot artifact
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - name: Validate trusted invocation
        run: |
          test "${GITHUB_EVENT_NAME}" = "workflow_dispatch"
          test "${GITHUB_REF}" = "refs/heads/main"

      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Download and verify pinned embedding model
        env:
          MODEL_REVISION: ${{ env.BGE_MODEL_REVISION }}
        run: bash .github/scripts/download-embedding-model.sh

      - name: Generate deterministic documentation screenshots
        env:
          TAXONOMY_EMBEDDING_MODEL_DIR: ${{ github.workspace }}/models/bge-small-en-v1.5
          TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: 'false'
          LLM_MOCK: 'true'
        run: >-
          mvn -B verify
          -DskipITs=false
          -DgenerateScreenshots
          -Dit.test=ScreenshotGeneratorIT
          -DfailIfNoTests=false
          -Dfailsafe.rerunFailingTestsCount=1
          -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle

      - name: Stage screenshot artifact
        run: |
          mkdir -p target/generated-screenshots
          cp docs/images/*.png target/generated-screenshots/
          test -n "$(find target/generated-screenshots -maxdepth 1 -name '*.png' -print -quit)"

      - name: Upload generated screenshots
        uses: actions/upload-artifact@v7
        with:
          name: generated-documentation-screenshots
          path: target/generated-screenshots/*.png
          if-no-files-found: error
          retention-days: 7

  publish:
    name: Publish screenshots to main
    needs: generate
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v7
        with:
          ref: main
          fetch-depth: 0

      - name: Validate publication target
        run: |
          test "${GITHUB_EVENT_NAME}" = "workflow_dispatch"
          test "${GITHUB_REF}" = "refs/heads/main"
          test "$(git branch --show-current)" = "main"

      - name: Download verified screenshots
        uses: actions/download-artifact@v7
        with:
          name: generated-documentation-screenshots
          path: docs/images

      - name: Commit changed screenshots only
        run: |
          git pull --ff-only origin main
          git config user.name 'github-actions[bot]'
          git config user.email 'github-actions[bot]@users.noreply.github.com'
          git add docs/images/*.png
          if git diff --staged --quiet; then
            echo "No screenshot changes to publish."
            exit 0
          fi
          git commit -m 'docs: regenerate documentation screenshots [skip ci]'
          git push origin HEAD:main
''',
)

write(
    "docs/dev/CI_SECURITY.md",
    r'''# CI Permission and Publication Boundaries

Verification and repository mutation are deliberately separated.

## Read-only verification

Ordinary pull-request, branch, and manual verification runs use
`contents: read`. The build job may additionally use `checks: write` to publish
JUnit annotations. Checkout credentials are not persisted. Compilation, tests,
SBOM creation, dependency validation, report generation, and artifact upload do
not have permission to change repository contents.

## Write-capable publication

Only narrowly scoped jobs can write:

- `publish-reports` runs after a successful default-branch push, downloads the
  immutable report artifact, and updates the GitHub Pages branch.
- `Documentation Screenshots` is a separate `workflow_dispatch` workflow. Its
  generation job is read-only; only the final main-branch publication job has
  `contents: write`.
- Container publication has `packages: write` and only `contents: read`.

Write-capable workflows validate event type and target ref, use concurrency
protection, and receive generated content through artifacts rather than sharing
a mutable build workspace.

## Action updates

Dependabot is the normal update mechanism for action major versions. A review
must confirm the action owner, required permissions, and release notes. Security
sensitive third-party actions may be pinned to an immutable commit SHA when the
maintenance cost is justified; the adjacent comment must identify the upstream
tag so Dependabot or a maintainer can update it deliberately.
''',
)


# ---------------------------------------------------------------------------
# #407: database compatibility workflow and accurate EN/DE documentation
# ---------------------------------------------------------------------------
write(
    ".github/workflows/database-compatibility.yml",
    r'''name: Database Compatibility

on:
  pull_request:
    branches: [ main ]
    paths:
      - 'pom.xml'
      - 'taxonomy-app/pom.xml'
      - 'taxonomy-app/src/main/resources/application-*.properties'
      - 'taxonomy-app/src/test/java/com/taxonomy/*Postgres*IT.java'
      - 'taxonomy-app/src/test/java/com/taxonomy/*Mssql*IT.java'
      - 'taxonomy-app/src/test/java/com/taxonomy/*Oracle*IT.java'
      - '.github/workflows/database-compatibility.yml'
  workflow_dispatch:
    inputs:
      database:
        description: Database family to test
        required: true
        type: choice
        default: all
        options: [all, postgres, mssql, oracle]
  schedule:
    - cron: '17 2 * * 0'

permissions:
  contents: read

concurrency:
  group: database-compatibility-${{ github.workflow }}-${{ github.ref }}-${{ inputs.database || 'pull-request' }}
  cancel-in-progress: false

env:
  BGE_MODEL_REVISION: 5c38ec7c405ec4b44b94cc5a9bb96e735b38267a

jobs:
  select-matrix:
    name: Select database tests
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.matrix.outputs.matrix }}
    steps:
      - id: matrix
        env:
          EVENT_NAME: ${{ github.event_name }}
          DATABASE: ${{ inputs.database || 'all' }}
        run: |
          python3 - <<'PY' >> "$GITHUB_OUTPUT"
          import json
          import os

          tests = {
              'postgres': ['DiagnosticsPostgresContainerIT', 'SeleniumPostgresContainerIT'],
              'mssql': ['DiagnosticsMssqlContainerIT', 'SeleniumMssqlContainerIT'],
              'oracle': ['DiagnosticsOracleContainerIT', 'SeleniumOracleContainerIT'],
          }
          event = os.environ['EVENT_NAME']
          selected = 'postgres' if event == 'pull_request' else os.environ.get('DATABASE', 'all')
          families = tests if selected == 'all' else {selected: tests[selected]}
          include = [
              {'database': database, 'test': test}
              for database, database_tests in families.items()
              for test in database_tests
          ]
          print('matrix=' + json.dumps({'include': include}, separators=(',', ':')))
          PY

  database-testcontainers:
    name: ${{ matrix.database }} / ${{ matrix.test }}
    needs: select-matrix
    runs-on: ubuntu-latest
    timeout-minutes: 45
    strategy:
      fail-fast: false
      matrix: ${{ fromJSON(needs.select-matrix.outputs.matrix) }}
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Download and verify pinned embedding model
        env:
          MODEL_REVISION: ${{ env.BGE_MODEL_REVISION }}
        run: bash .github/scripts/download-embedding-model.sh

      - name: Install reactor without running tests
        run: mvn -B -pl taxonomy-app -am install -DskipTests

      - name: Run database compatibility test
        env:
          TAXONOMY_EMBEDDING_MODEL_DIR: ${{ github.workspace }}/models/bge-small-en-v1.5
          TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: 'false'
        run: >-
          mvn -B -pl taxonomy-app
          failsafe:integration-test failsafe:verify
          -DskipITs=false
          -Dit.test=${{ matrix.test }}
          -DfailIfNoTests=false
          -DexcludedGroups=real-llm

      - name: Publish database evidence summary
        if: always()
        run: |
          echo "### Database compatibility" >> "$GITHUB_STEP_SUMMARY"
          echo "- Database: ${{ matrix.database }}" >> "$GITHUB_STEP_SUMMARY"
          echo "- Test: ${{ matrix.test }}" >> "$GITHUB_STEP_SUMMARY"
          echo "- Result: ${{ job.status }}" >> "$GITHUB_STEP_SUMMARY"

      - name: Upload database compatibility evidence
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: database-${{ matrix.database }}-${{ matrix.test }}
          path: taxonomy-app/target/failsafe-reports/**
          if-no-files-found: warn
          retention-days: 14
''',
)

write(
    "docs/dev/06-testing-by-change-type.md",
    r'''# Testing by Change Type

All verification commands run from a normal Git checkout. GitHub Actions only
orchestrates the same Maven, Failsafe, Testcontainers, Playwright, and axe
contracts that developers can execute locally.

## Test layers

| Layer | Purpose | Default command |
|---|---|---|
| Standard lifecycle | Unit, Spring context, controller, architecture, contract, dependency-hygiene, and module tests | `mvn verify` |
| Core integration | HSQLDB diagnostics, real browser flow, and persistence restart | `mvn verify -DskipITs=false -Dit.test=<class>` |
| Database compatibility | PostgreSQL, MSSQL, and Oracle diagnostics plus Selenium flows | `mvn verify -DskipITs=false -Dit.test=<class> -DexcludedGroups=real-llm` |
| Browser UX | Desktop Chromium/Firefox plus tablet/mobile Chromium | `node .github/scripts/ui-acceptance.mjs` |
| Accessibility | Authenticated axe audit with checked moderate baseline | `node .github/scripts/accessibility-audit.mjs` |
| Real LLM | Explicit live-provider integration | Select the test and remove `real-llm` from excluded groups |
| Documentation screenshots | Deterministic visual fixtures, not acceptance evidence | `mvn verify -DskipITs=false -DgenerateScreenshots -Dit.test=ScreenshotGeneratorIT` |

The root POM sets `skipITs=true`; container tests are enabled explicitly with
`-DskipITs=false`. The default excluded tags are
`real-llm,db-postgres,db-mssql,db-oracle`.

## Quick reference

| Change | Minimum | Additional evidence |
|---|---|---|
| Domain DTO or enum | `mvn test -pl taxonomy-domain` | App tests when exposed through API |
| DSL parser/serializer | `mvn test -pl taxonomy-dsl` | App and editor tests for materialization/UI changes |
| Export model/serializer | `mvn test -pl taxonomy-export` | App tests for endpoint or adapter changes |
| Spring service/controller | `mvn test -pl taxonomy-app` | `mvn verify` for configuration, persistence, or API changes |
| Dependency or POM | `mvn verify` | SBOM hygiene command below |
| UI/CSS/JavaScript | `mvn verify` | Browser and accessibility commands below |
| Security | `mvn verify` | MockMvc security tests and `CoreUiAcceptanceIT` |
| HSQLDB/persistence | `mvn verify` | `ProductionPersistenceRestartIT` |
| External DB mapping | `mvn verify` | Both diagnostics and Selenium tests for that family |
| Documentation | `python3 .github/scripts/check-doc-links.py` | Screenshots only for visible UI changes |

## Standard lifecycle

```bash
mvn verify
mvn clean verify                 # release-style clean build
```

This lifecycle remains deterministic: it does not start Docker or contact a live
LLM. Maven Enforcer rejects prohibited packaged dependency chains.

### Dependency and SBOM evidence

```bash
PDFBOX_VERSION=$(mvn help:evaluate -Dexpression=pdfbox.version -q -DforceStdout)
python3 .github/scripts/check-dependency-hygiene.py \
  --sbom target/taxonomy-sbom.json \
  --expected-pdfbox-version "$PDFBOX_VERSION"

mvn -pl taxonomy-app dependency:tree \
  -Dscope=runtime \
  -Dincludes='org.apache.pdfbox:*,com.vladsch.flexmark:flexmark-pdf-converter,com.openhtmltopdf:*'
```

See [DEPENDENCY_HYGIENE.md](DEPENDENCY_HYGIENE.md) for the reviewed exception
process.

## Core Testcontainers integration

| Test | Coverage |
|---|---|
| `DiagnosticsContainerIT` | Packaged app and diagnostics on embedded HSQLDB |
| `DiagnosticsWithApiKeyContainerIT` | Provider-key detection and masking |
| `CoreUiAcceptanceIT` | Login, onboarding, local assets, and keyboard navigation |
| `ProductionPersistenceRestartIT` | File HSQLDB and Lucene data survive container replacement |

```bash
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test=ProductionPersistenceRestartIT \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm,db-postgres,db-mssql,db-oracle
```

## Database compatibility matrix

The scheduled/manual workflow runs these ordinary Testcontainers tests:

| Database | Diagnostics | Browser | Tag |
|---|---|---|---|
| PostgreSQL | `DiagnosticsPostgresContainerIT` | `SeleniumPostgresContainerIT` | `db-postgres` |
| MSSQL | `DiagnosticsMssqlContainerIT` | `SeleniumMssqlContainerIT` | `db-mssql` |
| Oracle Free | `DiagnosticsOracleContainerIT` | `SeleniumOracleContainerIT` | `db-oracle` |

Pull requests that change database configuration run the complete PostgreSQL
pair as a bounded compatibility smoke test. Weekly and manual runs cover all
selected families.

```bash
mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \
  failsafe:integration-test failsafe:verify \
  -DskipITs=false \
  -Dit.test='*Postgres*IT' \
  -DfailIfNoTests=false \
  -DexcludedGroups=real-llm
```

Use `*Mssql*IT` or `*Oracle*IT` for the other families.

## Browser UX matrix

Install the pinned test dependency and intended browser engines:

```bash
npm install --no-save --no-audit --no-fund @playwright/test@1.61.1
npx playwright install --with-deps chromium firefox
```

Example desktop Firefox run:

```bash
TAXONOMY_BASE_URL=http://127.0.0.1:8080 \
TAXONOMY_UI_USERNAME=admin \
TAXONOMY_UI_PASSWORD=ui-acceptance-password \
TAXONOMY_BROWSER=firefox \
TAXONOMY_UI_PROFILE=desktop-firefox \
TAXONOMY_VIEWPORT_WIDTH=1440 \
TAXONOMY_VIEWPORT_HEIGHT=1000 \
TAXONOMY_UI_MODE=full \
node .github/scripts/ui-acceptance.mjs
```

The CI matrix also runs tablet and mobile read/navigation flows. See
[BROWSER_QA.md](BROWSER_QA.md).

## Accessibility

```bash
npm install --no-save --no-audit --no-fund \
  @playwright/test@1.61.1 @axe-core/playwright@4.12.1
npx playwright install --with-deps chromium
node .github/scripts/accessibility-audit.mjs
```

Critical and serious findings always fail. Moderate findings must match the
reviewed signatures in `.github/accessibility-baseline.json`; new signatures
fail the build. The TaxDSL CodeMirror editor is audited and has a dedicated
keyboard focus-escape check.

## Documentation screenshots

```bash
mvn -B verify \
  -DskipITs=false \
  -DgenerateScreenshots \
  -Dit.test=ScreenshotGeneratorIT \
  -DfailIfNoTests=false
```

Screenshots may use deterministic mock data. They do not prove live backends or
external AI providers are healthy. Publication is isolated in the manually
triggered `Documentation Screenshots` workflow.

## Security context and annotations

| Annotation/property | Use |
|---|---|
| `@SpringBootTest` + `@AutoConfigureMockMvc` | Spring integration without containers |
| `@WithMockUser(...)` | Explicit authenticated MockMvc context |
| `@Testcontainers` | Docker-backed integration |
| `@Tag("real-llm")` | Live provider test, excluded by default |
| `@Tag("db-postgres")` | PostgreSQL matrix |
| `@Tag("db-mssql")` | MSSQL matrix |
| `@Tag("db-oracle")` | Oracle matrix |
| `@EnabledIfSystemProperty` | Explicit opt-in test such as screenshots |

Browser and container acceptance tests must exercise real application contracts;
they must not inject result DOM or fake service-health state.
''',
)

# Correct stale HSQLDB lifecycle descriptions and local commands in both languages.
replace_once(
    "docs/en/DATABASE_SETUP.md",
    "**Key characteristics:**\n- Runs **in-process** (same JVM, no network hop)\n- Uses `SimpleDriverDataSource` instead of HikariCP to avoid connection pool overhead\n- All data is loaded from the bundled Excel workbook at startup\n- Data is **not persisted** between restarts (in-memory mode)\n\nThis is ideal for development, testing, and demo deployments.",
    "**Key characteristics:**\n- Runs **in-process** (same JVM, no network hop)\n- Uses a bounded HikariCP pool (`minimum-idle=1`, `maximum-pool-size=4`)\n- Defaults to an in-memory URL for zero-configuration development and tests\n- Supports a file URL for persistent deployments; the pool keeps a connection open so `shutdown=true` cannot close HSQLDB during Spring startup\n- Reuses an existing persisted catalogue on restart; `TAXONOMY_INIT_RELOAD_EXISTING=true` performs an intentional destructive reload\n\nThe in-memory default is ideal for development and tests. Production Docker defaults use file-backed HSQLDB and filesystem Lucene storage.",
)
replace_once(
    "docs/de/DATABASE_SETUP.md",
    "**Wesentliche Eigenschaften:**\n- Läuft **In-Process** (gleiche JVM, kein Netzwerk-Hop)\n- Verwendet `SimpleDriverDataSource` anstelle von HikariCP, um den Overhead eines Verbindungspools zu vermeiden\n- Alle Daten werden beim Start aus der mitgelieferten Excel-Arbeitsmappe geladen\n- Daten werden zwischen Neustarts **nicht persistiert** (In-Memory-Modus)\n\nDies ist ideal für Entwicklung, Tests und Demo-Bereitstellungen.",
    "**Wesentliche Eigenschaften:**\n- Läuft **In-Process** (gleiche JVM, kein Netzwerk-Hop)\n- Verwendet einen begrenzten HikariCP-Pool (`minimum-idle=1`, `maximum-pool-size=4`)\n- Nutzt standardmäßig eine In-Memory-URL für Entwicklung und Tests ohne Einrichtung\n- Unterstützt für persistente Installationen eine Datei-URL; der Pool hält eine Verbindung offen, damit `shutdown=true` HSQLDB nicht während des Spring-Starts beendet\n- Verwendet einen vorhandenen persistierten Katalog nach einem Neustart weiter; `TAXONOMY_INIT_RELOAD_EXISTING=true` löst bewusst ein destruktives Neuladen aus\n\nDer In-Memory-Standard eignet sich für Entwicklung und Tests. Die Produktions-Docker-Konfiguration verwendet dateibasierte HSQLDB- und Lucene-Speicherung.",
)
for path, family in [
    ("docs/en/DATABASE_SETUP.md", "Postgres"),
    ("docs/en/DATABASE_SETUP.md", "Mssql"),
    ("docs/en/DATABASE_SETUP.md", "Oracle"),
    ("docs/de/DATABASE_SETUP.md", "Postgres"),
    ("docs/de/DATABASE_SETUP.md", "Mssql"),
    ("docs/de/DATABASE_SETUP.md", "Oracle"),
]:
    content = read(path)
    old = f'''mvn package -DskipTests
mvn verify -pl taxonomy-app -DexcludedGroups=real-llm -Dit.test="*{family}*IT"'''
    new = f'''mvn -B -pl taxonomy-app -am install -DskipTests
mvn -B -pl taxonomy-app \\
  failsafe:integration-test failsafe:verify \\
  -DskipITs=false \\
  -Dit.test='*{family}*IT' \\
  -DfailIfNoTests=false \\
  -DexcludedGroups=real-llm'''
    if old not in content:
        raise RuntimeError(f"Missing integration command block in {path}: {family}")
    write(path, content.replace(old, new, 1))

append_once(
    "docs/en/DATABASE_SETUP.md",
    "## Test architecture and compatibility evidence",
    r'''## Test architecture and compatibility evidence

`mvn verify` is the bounded deterministic lifecycle and does not start external
databases. Core HSQLDB container tests and the PostgreSQL/MSSQL/Oracle matrix are
ordinary Failsafe/Testcontainers tests enabled with `-DskipITs=false`.

The `Database Compatibility` workflow runs PostgreSQL diagnostics and Selenium
on relevant pull requests, all six database scenarios weekly, and a selected
family on manual dispatch. Every command is documented in
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
)
append_once(
    "docs/de/DATABASE_SETUP.md",
    "## Testarchitektur und Kompatibilitätsnachweise",
    r'''## Testarchitektur und Kompatibilitätsnachweise

`mvn verify` ist der begrenzte deterministische Standard-Lebenszyklus und startet
keine externe Datenbank. Die zentralen HSQLDB-Container-Tests sowie die
PostgreSQL-/MSSQL-/Oracle-Matrix sind normale Failsafe-/Testcontainers-Tests und
werden mit `-DskipITs=false` aktiviert.

Der Workflow `Database Compatibility` führt bei relevanten Pull Requests die
PostgreSQL-Diagnose- und Selenium-Tests aus, wöchentlich alle sechs Szenarien und
bei manueller Ausführung die ausgewählte Datenbankfamilie. Die lokalen Befehle
stehen in [`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
)

replace_once(
    "docs/en/CONFIGURATION_REFERENCE.md",
    "| `spring.datasource.type` | `SimpleDriverDataSource` | Bypasses HikariCP — no pool needed for in-process HSQLDB. |\n"
    "| `spring.jpa.database-platform` | `org.hibernate.dialect.HSQLDialect` | Explicit dialect (required because `SimpleDriverDataSource` does not expose JDBC metadata). |",
    "| `spring.datasource.type` | `com.zaxxer.hikari.HikariDataSource` | Bounded pool used for both in-memory and file HSQLDB. |\n"
    "| `spring.datasource.hikari.minimum-idle` | `${TAXONOMY_DB_MIN_IDLE:1}` | Keeps one connection alive; required for file URLs using `shutdown=true`. |\n"
    "| `spring.datasource.hikari.maximum-pool-size` | `${TAXONOMY_DB_MAX_POOL_SIZE:4}` | Bounded maximum for embedded HSQLDB. |\n"
    "| `spring.datasource.hikari.connection-timeout` | `${TAXONOMY_DB_CONNECTION_TIMEOUT_MS:30000}` | Maximum connection wait in milliseconds. |\n"
    "| `spring.jpa.database-platform` | `org.hibernate.dialect.HSQLDialect` | Explicit dialect for deterministic startup. |",
)
replace_once(
    "docs/de/CONFIGURATION_REFERENCE.md",
    "| `spring.datasource.type` | `SimpleDriverDataSource` | Umgeht HikariCP — kein Pool für In-Process-HSQLDB erforderlich. |\n"
    "| `spring.jpa.database-platform` | `org.hibernate.dialect.HSQLDialect` | Expliziter Dialekt (erforderlich, da `SimpleDriverDataSource` keine JDBC-Metadaten bereitstellt). |",
    "| `spring.datasource.type` | `com.zaxxer.hikari.HikariDataSource` | Begrenzter Pool für In-Memory- und dateibasierte HSQLDB. |\n"
    "| `spring.datasource.hikari.minimum-idle` | `${TAXONOMY_DB_MIN_IDLE:1}` | Hält eine Verbindung offen; erforderlich für Datei-URLs mit `shutdown=true`. |\n"
    "| `spring.datasource.hikari.maximum-pool-size` | `${TAXONOMY_DB_MAX_POOL_SIZE:4}` | Begrenztes Maximum für eingebettete HSQLDB. |\n"
    "| `spring.datasource.hikari.connection-timeout` | `${TAXONOMY_DB_CONNECTION_TIMEOUT_MS:30000}` | Maximale Wartezeit auf eine Verbindung in Millisekunden. |\n"
    "| `spring.jpa.database-platform` | `org.hibernate.dialect.HSQLDialect` | Expliziter Dialekt für deterministischen Start. |",
)

for path, heading, text in [
    (
        "docs/en/CONFIGURATION_REFERENCE.md",
        "## Build and integration-test selection",
        r'''## Build and integration-test selection

These are Maven properties/tags, not runtime environment variables:

| Property/tag | Default | Effect |
|---|---|---|
| `skipITs` | `true` | Keeps the standard lifecycle bounded; set `-DskipITs=false` for Failsafe/Testcontainers tests. |
| `excludedGroups` | `real-llm,db-postgres,db-mssql,db-oracle` | Excludes live providers and heavyweight database families by default. |
| `db-postgres` | excluded | PostgreSQL compatibility tests. |
| `db-mssql` | excluded | Microsoft SQL Server compatibility tests. |
| `db-oracle` | excluded | Oracle compatibility tests. |
| `real-llm` | excluded | Live external LLM calls. |

Passing `-DexcludedGroups=real-llm` deliberately includes all database tags
while still excluding live LLM calls. See
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
    ),
    (
        "docs/de/CONFIGURATION_REFERENCE.md",
        "## Auswahl von Build- und Integrationstests",
        r'''## Auswahl von Build- und Integrationstests

Dies sind Maven-Eigenschaften beziehungsweise Tags, keine Laufzeitvariablen:

| Eigenschaft/Tag | Standard | Wirkung |
|---|---|---|
| `skipITs` | `true` | Hält den Standard-Lebenszyklus begrenzt; `-DskipITs=false` aktiviert Failsafe-/Testcontainers-Tests. |
| `excludedGroups` | `real-llm,db-postgres,db-mssql,db-oracle` | Schließt Live-Provider und schwere Datenbankfamilien standardmäßig aus. |
| `db-postgres` | ausgeschlossen | PostgreSQL-Kompatibilitätstests. |
| `db-mssql` | ausgeschlossen | Microsoft-SQL-Server-Kompatibilitätstests. |
| `db-oracle` | ausgeschlossen | Oracle-Kompatibilitätstests. |
| `real-llm` | ausgeschlossen | Live-Aufrufe externer LLMs. |

`-DexcludedGroups=real-llm` schließt nur Live-LLMs aus und aktiviert damit
bewusst die Datenbank-Tags. Siehe
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).''',
    ),
]:
    content = read(path)
    if heading not in content:
        marker = "\n---\n\n## Hibernate Search / Lucene" if "/en/" in path else "\n---\n\n## Hibernate Search / Lucene"
        if marker not in content:
            raise RuntimeError(f"Cannot place build/test section in {path}")
        write(path, content.replace(marker, "\n---\n\n" + text + marker, 1))

for path, section in [
    ("docs/en/ARCHITECTURE.md", r'''## Verification architecture

Verification is split into a deterministic standard Maven lifecycle, four core
Testcontainers scenarios, and an external database compatibility matrix. Tests
remain executable from a plain checkout; workflows only schedule them. See
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).'''),
    ("docs/de/ARCHITECTURE.md", r'''## Verifikationsarchitektur

Die Verifikation ist in einen deterministischen Maven-Standard-Lebenszyklus,
vier zentrale Testcontainers-Szenarien und eine externe Datenbank-
Kompatibilitätsmatrix aufgeteilt. Alle Tests bleiben aus einem normalen Checkout
ausführbar; Workflows planen sie lediglich ein. Siehe
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).'''),
    ("docs/en/DEVELOPER_GUIDE.md", r'''## Current QA entry points

Use [`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md)
for standard, core-container, database, browser, accessibility, dependency, and
screenshot commands. CI permission boundaries are documented in
[`docs/dev/CI_SECURITY.md`](../dev/CI_SECURITY.md).'''),
    ("docs/de/DEVELOPER_GUIDE.md", r'''## Aktuelle QA-Einstiegspunkte

Die Befehle für Standard-, Core-Container-, Datenbank-, Browser-,
Accessibility-, Abhängigkeits- und Screenshot-Prüfungen stehen in
[`docs/dev/06-testing-by-change-type.md`](../dev/06-testing-by-change-type.md).
Die CI-Berechtigungsgrenzen sind in
[`docs/dev/CI_SECURITY.md`](../dev/CI_SECURITY.md) beschrieben.'''),
]:
    append_once(path, section.splitlines()[0], section)


# ---------------------------------------------------------------------------
# #411: browser/viewports, moderate baseline, and CodeMirror keyboard contract
# ---------------------------------------------------------------------------
replace_once(
    "taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-codemirror.mjs",
    "    const view = new EditorView({\n"
    "        doc: '',\n"
    "        extensions: [\n"
    "            basicSetup,",
    "    container.setAttribute('tabindex', '-1');\n"
    "    let keyboardHelp = document.getElementById('dslEditorKeyboardHelp');\n"
    "    if (!keyboardHelp) {\n"
    "        keyboardHelp = document.createElement('p');\n"
    "        keyboardHelp.id = 'dslEditorKeyboardHelp';\n"
    "        keyboardHelp.className = 'visually-hidden';\n"
    "        keyboardHelp.textContent = 'TaxDSL editor. Press Control+Space for suggestions, Alt+Shift+F to format, and Escape to leave the editor.';\n"
    "        container.before(keyboardHelp);\n"
    "    }\n\n"
    "    const view = new EditorView({\n"
    "        doc: '',\n"
    "        extensions: [\n"
    "            basicSetup,\n"
    "            EditorView.contentAttributes.of({\n"
    "                'aria-label': 'TaxDSL editor',\n"
    "                'aria-describedby': 'dslEditorKeyboardHelp',\n"
    "                'aria-keyshortcuts': 'Control+Space Alt+Shift+F Escape'\n"
    "            }),",
)
replace_once(
    "taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-codemirror.mjs",
    "            keymap.of([{\n"
    "                key: 'Shift-Alt-f',\n"
    "                run: () => {\n"
    "                    if (typeof window.dslFormatContent === 'function') {\n"
    "                        window.dslFormatContent();\n"
    "                    }\n"
    "                    return true;\n"
    "                }\n"
    "            }])",
    "            keymap.of([\n"
    "                {\n"
    "                    key: 'Escape',\n"
    "                    run: view => {\n"
    "                        view.contentDOM.blur();\n"
    "                        container.focus({ preventScroll: true });\n"
    "                        return true;\n"
    "                    }\n"
    "                },\n"
    "                {\n"
    "                    key: 'Shift-Alt-f',\n"
    "                    run: () => {\n"
    "                        if (typeof window.dslFormatContent === 'function') {\n"
    "                            window.dslFormatContent();\n"
    "                        }\n"
    "                        return true;\n"
    "                    }\n"
    "                }\n"
    "            ])",
)

write(
    ".github/scripts/ui-acceptance.mjs",
    r'''import { chromium, firefox } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_UI_USERNAME || 'admin';
const password = process.env.TAXONOMY_UI_PASSWORD || 'ui-acceptance-password';
const browserName = process.env.TAXONOMY_BROWSER || 'chromium';
const profile = process.env.TAXONOMY_UI_PROFILE || 'desktop-chromium';
const mode = process.env.TAXONOMY_UI_MODE || 'full';
const viewport = {
  width: Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10),
  height: Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10)
};
const reportPath = process.env.TAXONOMY_UI_REPORT || `/tmp/taxonomy-ui-${profile}.json`;
const screenshotPath = process.env.TAXONOMY_UI_SCREENSHOT || `/tmp/taxonomy-ui-${profile}.png`;
const baseOrigin = new URL(baseUrl).origin;
const browserType = { chromium, firefox }[browserName];
if (!browserType) throw new Error(`Unsupported browser: ${browserName}`);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const browser = await browserType.launch({ headless: true });
const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
const page = await context.newPage();
const externalRequests = [];
const consoleErrors = [];
const checks = [];

function passed(name) { checks.push(name); }
page.on('request', request => {
  const url = request.url();
  if (url.startsWith('data:') || url.startsWith('blob:')) return;
  if (new URL(url).origin !== baseOrigin) externalRequests.push(url);
});
page.on('console', message => {
  if (message.type() === 'error') consoleErrors.push(message.text());
});
page.on('pageerror', error => consoleErrors.push(error.message));

let auditError = null;
try {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  const usernameInput = page.locator('input[name="username"]');
  const passwordInput = page.locator('input[name="password"]');
  await usernameInput.focus();
  await usernameInput.fill(username);
  await passwordInput.focus();
  await passwordInput.fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.keyboard.press('Enter')
  ]);
  passed('keyboard login');

  await page.locator('#taxonomyTree [role="treeitem"]').first()
    .waitFor({ state: 'visible', timeout: 90_000 });
  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.focus();
    await page.keyboard.press('Enter');
  }

  assert(await page.locator('#mainNavTabs').getAttribute('role') === 'tablist',
    'Main navigation lacks tablist semantics');
  const analyzeTab = page.locator('#mainNavTabs [data-page="analyze"]');
  await analyzeTab.focus();
  await page.keyboard.press('ArrowRight');
  assert(await page.locator('#mainNavTabs [data-page="architecture"]').getAttribute('aria-selected') === 'true',
    'Arrow-key navigation did not activate Architecture');
  await analyzeTab.click();
  passed('keyboard tab navigation');

  const firstTreeItem = page.locator('#taxonomyTree [role="treeitem"]').first();
  await firstTreeItem.focus();
  await page.keyboard.press('ArrowDown');
  assert(await page.evaluate(() => document.activeElement?.getAttribute('role') === 'treeitem'),
    'Taxonomy tree did not retain keyboard focus on a tree item');
  const focusStyle = await page.evaluate(() => {
    const style = getComputedStyle(document.activeElement);
    return { outline: style.outlineStyle, shadow: style.boxShadow };
  });
  assert(focusStyle.outline !== 'none' || focusStyle.shadow !== 'none',
    'Focused taxonomy item has no visible focus indicator');
  passed('tree keyboard and visible focus');

  assert(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches),
    'Reduced-motion browser preference was not applied');
  passed('reduced-motion preference');

  if (mode === 'full') {
    const interactive = page.locator('#interactiveMode');
    if (await interactive.isChecked()) await interactive.uncheck();
    await page.locator('#businessText').fill(
      'Provide secure hospital communications with traceable architecture decisions and resilient data exchange.');
    await page.locator('#analyzeBtn').focus();
    await page.keyboard.press('Enter');
    await page.locator('#statusArea').waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForFunction(() => {
      const text = document.querySelector('#statusArea')?.textContent?.toLowerCase() || '';
      return text.includes('complete') || text.includes('completed');
    }, null, { timeout: 120_000 });
    assert(await page.locator('.tax-pct').count() > 0,
      'Real UI analysis completed without rendering scored taxonomy nodes');
    assert(await page.locator('[role="treeitem"][aria-label*="Relevance"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');
    await page.locator('#businessText').fill(
      'Provide secure hospital communications with an additional emergency notification capability.');
    await page.locator('#businessText.stale-results').waitFor({ state: 'visible', timeout: 10_000 });
    passed('analysis, accessible scores, and stale state');
  }

  await page.locator('#mainNavTabs [data-page="versions"]').click();
  await page.locator('#tab-versions').waitFor({ state: 'visible' });
  passed('versions navigation');

  if (mode === 'full') {
    await page.locator('#mainNavTabs [data-page="dsl-editor"]').click();
    await page.locator('#tab-dsl-editor').waitFor({ state: 'visible' });
    const editor = page.locator('#dslEditorContainer .cm-content');
    await editor.waitFor({ state: 'visible', timeout: 30_000 });
    assert(await editor.getAttribute('aria-label') === 'TaxDSL editor',
      'TaxDSL CodeMirror surface lacks an accessible name');
    const shortcuts = await editor.getAttribute('aria-keyshortcuts') || '';
    assert(shortcuts.includes('Alt+Shift+F') && shortcuts.includes('Escape'),
      'TaxDSL editor does not expose command-discovery keyboard shortcuts');
    await editor.focus();
    await page.keyboard.press('Escape');
    assert(await page.evaluate(() => !document.activeElement?.closest('.cm-editor')),
      'Escape did not move focus out of the CodeMirror editor');
    passed('DSL editor accessible name, commands, and focus escape');

    await page.waitForFunction(() => {
      const tab = document.querySelector('#adminNavTab');
      return tab && getComputedStyle(tab).display !== 'none';
    }, null, { timeout: 20_000 });
    await page.locator('#mainNavTabs [data-page="admin"]').click();
    await page.locator('#tab-admin').waitFor({ state: 'visible' });
    passed('admin role surface');
  } else {
    await page.locator('#mainNavTabs [data-page="help"]').click();
    await page.locator('#tab-help').waitFor({ state: 'visible' });
    passed('responsive read-only navigation');
  }

  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth
  }));
  assert(overflow.scrollWidth <= overflow.clientWidth + 2,
    `Page overflows viewport: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
  passed('viewport reflow');

  assert(externalRequests.length === 0,
    `Application made external browser requests: ${externalRequests.join(', ')}`);
  assert(consoleErrors.length === 0,
    `Browser console errors: ${consoleErrors.join(' | ')}`);
  passed('local assets and clean console');
} catch (error) {
  auditError = error?.stack || String(error);
  process.exitCode = 1;
} finally {
  await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
  await writeFile(reportPath, JSON.stringify({
    profile, browser: browserName, mode, viewport, checks,
    externalRequests, consoleErrors, auditError
  }, null, 2) + '\n', 'utf8');
  await context.close();
  await browser.close();
}

if (auditError) throw new Error(auditError);
console.log(`UI acceptance passed for ${profile}: ${checks.join(', ')}`);
''',
)

write(
    ".github/workflows/ui-acceptance.yml",
    r'''name: UI Acceptance

on:
  pull_request:
    branches: [ main ]
    paths:
      - 'taxonomy-app/src/main/**'
      - 'taxonomy-app/pom.xml'
      - 'taxonomy-domain/**'
      - 'taxonomy-dsl/**'
      - 'taxonomy-export/**'
      - 'taxonomy-extension-api/**'
      - '.github/scripts/ui-acceptance.mjs'
      - '.github/workflows/ui-acceptance.yml'
  push:
    branches: [ main ]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  real-ui-flow:
    name: ${{ matrix.profile }}
    runs-on: ubuntu-latest
    timeout-minutes: 40
    strategy:
      fail-fast: false
      matrix:
        include:
          - profile: desktop-chromium
            browser: chromium
            width: 1440
            height: 1000
            mode: full
          - profile: desktop-firefox
            browser: firefox
            width: 1440
            height: 1000
            mode: full
          - profile: tablet-chromium
            browser: chromium
            width: 1024
            height: 768
            mode: responsive
          - profile: mobile-chromium
            browser: chromium
            width: 390
            height: 844
            mode: responsive
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Set up Node.js
        uses: actions/setup-node@v7
        with:
          node-version: '24'

      - name: Build application
        run: mvn -q -pl taxonomy-app -am package -DskipTests

      - name: Start application with deterministic mock LLM
        env:
          TAXONOMY_ADMIN_PASSWORD: ui-acceptance-password
          TAXONOMY_REQUIRE_PASSWORD_CHANGE: 'false'
          TAXONOMY_EMBEDDING_ENABLED: 'false'
          TAXONOMY_INIT_ASYNC: 'true'
          TAXONOMY_THYMELEAF_CACHE: 'false'
          LLM_MOCK: 'true'
        run: |
          JAR=$(find taxonomy-app/target -maxdepth 1 -name 'taxonomy-app-*.jar' ! -name 'original-*' | head -n 1)
          test -n "$JAR"
          java -jar "$JAR" > /tmp/taxonomy-ui.log 2>&1 &
          echo $! > /tmp/taxonomy-ui.pid
          for i in $(seq 1 90); do
            if curl -fsS http://127.0.0.1:8080/login >/dev/null; then exit 0; fi
            sleep 2
          done
          cat /tmp/taxonomy-ui.log
          exit 1

      - name: Install pinned Playwright browser engines
        run: |
          npm install --no-save --no-audit --no-fund @playwright/test@1.61.1
          npx playwright install --with-deps chromium firefox

      - name: Run real UI acceptance flow
        env:
          TAXONOMY_BASE_URL: http://127.0.0.1:8080
          TAXONOMY_UI_USERNAME: admin
          TAXONOMY_UI_PASSWORD: ui-acceptance-password
          TAXONOMY_BROWSER: ${{ matrix.browser }}
          TAXONOMY_UI_PROFILE: ${{ matrix.profile }}
          TAXONOMY_VIEWPORT_WIDTH: ${{ matrix.width }}
          TAXONOMY_VIEWPORT_HEIGHT: ${{ matrix.height }}
          TAXONOMY_UI_MODE: ${{ matrix.mode }}
          TAXONOMY_UI_REPORT: /tmp/taxonomy-ui-${{ matrix.profile }}.json
          TAXONOMY_UI_SCREENSHOT: /tmp/taxonomy-ui-${{ matrix.profile }}.png
        run: node .github/scripts/ui-acceptance.mjs

      - name: Upload UI acceptance evidence
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: ui-acceptance-${{ matrix.profile }}
          path: |
            /tmp/taxonomy-ui-${{ matrix.profile }}.json
            /tmp/taxonomy-ui-${{ matrix.profile }}.png
            /tmp/taxonomy-ui.log
          if-no-files-found: warn
          retention-days: 14
''',
)

write(".github/accessibility-baseline.json", json.dumps({"schemaVersion": 1, "allowedModerate": []}, indent=2) + "\n")
write(
    ".github/scripts/accessibility-audit.mjs",
    r'''import { chromium } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFile, writeFile } from 'node:fs/promises';

const baseUrl = process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080';
const username = process.env.TAXONOMY_A11Y_USERNAME || 'admin';
const password = process.env.TAXONOMY_A11Y_PASSWORD || 'a11y-test-password';
const profile = process.env.TAXONOMY_A11Y_PROFILE || 'desktop';
const viewport = {
  width: Number.parseInt(process.env.TAXONOMY_VIEWPORT_WIDTH || '1440', 10),
  height: Number.parseInt(process.env.TAXONOMY_VIEWPORT_HEIGHT || '1000', 10)
};
const reportPath = process.env.TAXONOMY_A11Y_REPORT || `/tmp/taxonomy-a11y-${profile}.json`;
const textReportPath = process.env.TAXONOMY_A11Y_TEXT_REPORT || `/tmp/taxonomy-a11y-${profile}.txt`;
const baselinePath = process.env.TAXONOMY_A11Y_BASELINE || '.github/accessibility-baseline.json';
const baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
if (baseline.schemaVersion !== 1 || !Array.isArray(baseline.allowedModerate)) {
  throw new Error(`Invalid accessibility baseline: ${baselinePath}`);
}
const allowedModerate = new Set(baseline.allowedModerate);

function signature(section, violation, node) {
  return `${profile}|${section}|${violation.id}|${node.target.join(' ')}`;
}

function formatReport(findings, unexpectedModerate, severe, auditError) {
  const lines = [`Accessibility audit profile: ${profile} (${viewport.width}x${viewport.height})`, ''];
  if (auditError) lines.push('Execution error:', auditError, '');
  for (const finding of findings) {
    lines.push(`Section #${finding.section}:`);
    if (!finding.violations.length) lines.push('- no axe violations');
    for (const violation of finding.violations) {
      lines.push(`- [${violation.impact || 'unknown'}] ${violation.id}: ${violation.help}`);
      for (const node of violation.nodes) lines.push(`  ${node.target.join(' ')}`);
    }
    lines.push('');
  }
  if (unexpectedModerate.length) {
    lines.push('Unexpected moderate signatures (review before baselining):');
    lines.push(...unexpectedModerate.map(item => `- ${item}`), '');
  }
  lines.push(`Severe findings: ${severe.length}`);
  lines.push(`Unexpected moderate findings: ${unexpectedModerate.length}`);
  lines.push(auditError || severe.length || unexpectedModerate.length ? 'Result: FAIL' : 'Result: PASS');
  return lines.join('\n') + '\n';
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
const page = await context.newPage();
const findings = [];
let auditError = null;

try {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.locator('button[type="submit"], input[type="submit"]').first().click()
  ]);
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });

  const sections = ['analyze', 'architecture', 'graph', 'versions', 'dsl-editor', 'help', 'admin', 'preferences'];
  for (const section of sections) {
    await page.evaluate(name => {
      if (typeof window.navigateToPage === 'function') window.navigateToPage(name);
      else window.location.hash = name;
    }, section);
    await page.waitForFunction(name => {
      const pane = document.getElementById(`tab-${name}`);
      return pane && !pane.classList.contains('d-none');
    }, section);
    if (section === 'dsl-editor') {
      await page.locator('#dslEditorContainer .cm-content').waitFor({ state: 'visible', timeout: 30_000 });
    }
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    findings.push({ section, violations: result.violations });
  }
} catch (error) {
  auditError = error?.stack || String(error);
}

const severe = [];
const moderateSignatures = [];
for (const finding of findings) {
  for (const violation of finding.violations) {
    for (const node of violation.nodes) {
      const item = { section: finding.section, id: violation.id, target: node.target };
      if (violation.impact === 'critical' || violation.impact === 'serious') severe.push(item);
      if (violation.impact === 'moderate') moderateSignatures.push(signature(finding.section, violation, node));
    }
  }
}
const unexpectedModerate = [...new Set(moderateSignatures.filter(item => !allowedModerate.has(item)))].sort();
const staleBaseline = baseline.allowedModerate.filter(item => item.startsWith(`${profile}|`) && !moderateSignatures.includes(item));
const textReport = formatReport(findings, unexpectedModerate, severe, auditError);
await Promise.all([
  writeFile(reportPath, JSON.stringify({
    profile, viewport, auditError, findings, severe, moderateSignatures,
    unexpectedModerate, staleBaseline
  }, null, 2) + '\n', 'utf8'),
  writeFile(textReportPath, textReport, 'utf8')
]);
console.log(textReport.trim());
await context.close();
await browser.close();

if (auditError || severe.length || unexpectedModerate.length) process.exitCode = 1;
''',
)

write(
    ".github/workflows/accessibility.yml",
    r'''name: Accessibility

on:
  pull_request:
    branches: [ main ]
    paths:
      - 'taxonomy-app/src/main/resources/templates/**'
      - 'taxonomy-app/src/main/resources/static/**'
      - 'taxonomy-app/src/main/java/com/taxonomy/**/controller/**'
      - 'taxonomy-app/src/main/java/com/taxonomy/security/**'
      - 'taxonomy-app/src/main/java/com/taxonomy/versioning/**'
      - 'taxonomy-app/pom.xml'
      - 'taxonomy-export/**'
      - 'taxonomy-extension-api/**'
      - '.github/accessibility-baseline.json'
      - '.github/scripts/accessibility-audit.mjs'
      - '.github/workflows/accessibility.yml'
  push:
    branches: [ main ]
    paths:
      - 'taxonomy-app/src/main/resources/templates/**'
      - 'taxonomy-app/src/main/resources/static/**'
      - '.github/accessibility-baseline.json'
  workflow_dispatch:

permissions:
  contents: read

jobs:
  axe-audit:
    name: axe / ${{ matrix.profile }}
    runs-on: ubuntu-latest
    timeout-minutes: 35
    strategy:
      fail-fast: false
      matrix:
        include:
          - profile: desktop
            width: 1440
            height: 1000
          - profile: tablet
            width: 1024
            height: 768
          - profile: mobile
            width: 390
            height: 844
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Set up Node.js
        uses: actions/setup-node@v7
        with:
          node-version: '24'

      - name: Build application
        shell: bash
        run: |
          set -o pipefail
          mvn -B -pl taxonomy-app -am package -DskipTests \
            2>&1 | tee /tmp/taxonomy-a11y-build.log

      - name: Start application
        env:
          TAXONOMY_ADMIN_PASSWORD: a11y-test-password
          TAXONOMY_REQUIRE_PASSWORD_CHANGE: 'false'
          TAXONOMY_EMBEDDING_ENABLED: 'false'
          TAXONOMY_INIT_ASYNC: 'true'
          TAXONOMY_THYMELEAF_CACHE: 'false'
        run: |
          JAR=$(find taxonomy-app/target -maxdepth 1 -name 'taxonomy-app-*.jar' ! -name 'original-*' | head -n 1)
          test -n "$JAR"
          java -jar "$JAR" > /tmp/taxonomy-a11y.log 2>&1 &
          echo $! > /tmp/taxonomy-a11y.pid
          for i in $(seq 1 90); do
            if curl -fsS http://127.0.0.1:8080/login >/dev/null; then exit 0; fi
            sleep 2
          done
          cat /tmp/taxonomy-a11y.log
          exit 1

      - name: Install pinned browser audit tools
        run: |
          npm install --no-save --no-audit --no-fund \
            @playwright/test@1.61.1 \
            @axe-core/playwright@4.12.1
          npx playwright install --with-deps chromium

      - name: Run axe audit with checked moderate baseline
        env:
          TAXONOMY_BASE_URL: http://127.0.0.1:8080
          TAXONOMY_A11Y_USERNAME: admin
          TAXONOMY_A11Y_PASSWORD: a11y-test-password
          TAXONOMY_A11Y_PROFILE: ${{ matrix.profile }}
          TAXONOMY_VIEWPORT_WIDTH: ${{ matrix.width }}
          TAXONOMY_VIEWPORT_HEIGHT: ${{ matrix.height }}
          TAXONOMY_A11Y_REPORT: /tmp/taxonomy-a11y-${{ matrix.profile }}.json
          TAXONOMY_A11Y_TEXT_REPORT: /tmp/taxonomy-a11y-${{ matrix.profile }}.txt
          TAXONOMY_A11Y_BASELINE: .github/accessibility-baseline.json
        run: node .github/scripts/accessibility-audit.mjs

      - name: Upload accessibility evidence
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: accessibility-${{ matrix.profile }}
          path: |
            /tmp/taxonomy-a11y-${{ matrix.profile }}.json
            /tmp/taxonomy-a11y-${{ matrix.profile }}.txt
            /tmp/taxonomy-a11y-build.log
            /tmp/taxonomy-a11y.log
          if-no-files-found: ignore
          retention-days: 14
''',
)

write(
    "docs/dev/BROWSER_QA.md",
    r'''# Browser, Responsive, and Accessibility QA

## Supported automated matrix

| Profile | Engine | Viewport | Journey |
|---|---|---:|---|
| desktop-chromium | Chromium | 1440×1000 | Full login, tree, analysis, versions, DSL editor, admin |
| desktop-firefox | Firefox | 1440×1000 | Same full journey on the second supported engine |
| tablet-chromium | Chromium | 1024×768 | Login, tree, keyboard navigation, versions/help, reflow |
| mobile-chromium | Chromium | 390×844 | Primary read/navigation flow and reflow |

Every profile uses `prefers-reduced-motion: reduce`, checks visible keyboard
focus, rejects external browser requests and console errors, and uploads a JSON
report plus screenshot.

## TaxDSL editor

The CodeMirror content surface has an accessible name, keyboard shortcut
metadata, and explicit help text. `Escape` moves focus to the editor container so
the next `Tab` continues through the application. `Control+Space` opens
completion and `Alt+Shift+F` formats the DSL.

The editor is included in axe scans; there is no blanket CodeMirror exclusion.

## Axe policy

Authenticated axe scans run against the eight main sections at desktop, tablet,
and mobile widths using WCAG 2.0/2.1 A and AA tags.

- Critical and serious findings fail immediately.
- Moderate findings are stored as exact profile/section/rule/target signatures.
- `.github/accessibility-baseline.json` contains reviewed existing signatures.
- Any new moderate signature fails; obsolete signatures are reported for removal.
- Minor findings remain visible in the machine-readable evidence.

A baseline update must link to a reviewed issue explaining user impact,
mitigation, owner, and removal condition. It must never be regenerated blindly.
''',
)
append_once(
    "docs/en/ACCESSIBILITY.md",
    "## Automated browser coverage matrix",
    r'''## Automated browser coverage matrix

The maintained browser, viewport, CodeMirror, keyboard, reduced-motion, and axe
coverage is documented in
[`docs/dev/BROWSER_QA.md`](../dev/BROWSER_QA.md). New moderate axe findings are
blocked against a reviewed baseline; CodeMirror is not excluded from the audit.''',
)
append_once(
    "docs/de/ACCESSIBILITY.md",
    "## Automatisierte Browser-Abdeckungsmatrix",
    r'''## Automatisierte Browser-Abdeckungsmatrix

Die gepflegte Browser-, Viewport-, CodeMirror-, Tastatur-, Reduced-Motion- und
axe-Abdeckung ist in
[`docs/dev/BROWSER_QA.md`](../dev/BROWSER_QA.md) beschrieben. Neue moderate
axe-Befunde werden gegen eine geprüfte Baseline blockiert; CodeMirror ist nicht
von der Prüfung ausgeschlossen.''',
)

# Remove this one-shot migration from the final branch.
(ROOT / ".github/scripts/apply-open-points.py").unlink()
(ROOT / ".github/workflows/apply-open-points.yml").unlink()
