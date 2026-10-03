# GitHub Copilot Instructions — Taxonomy Architecture Analyzer

## Project Overview

Java 21 / Spring Boot application. Taxonomy data comes from the actual Excel
catalogue through Apache POI. Hibernate Search/Lucene provide search; the configured
provider gateway supplies model capabilities. The web interface uses Thymeleaf and
modular JavaScript. The checked-in POMs, profiles and workflows own dependency
versions and verification selection; do not infer them from old prose or badges.

## Build and Test

Use the checked-in Maven Wrapper from the repository root:

```bash
./mvnw compile
./mvnw test
./mvnw verify
```

The default lifecycle is a bounded developer check, not the complete CI run.
`*Test`/`*Tests` are selected by Surefire and `*IT` by Failsafe when the relevant
integration profiles are enabled. A naming suffix does not prove that a selected
test is Docker-free: application/browser scenarios have their own prerequisites.

### Authoritative verification

The complete local CI-equivalent entry point is:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Read `.github/workflows/ci-cd.yml`, the POM profiles and
`.mvn/verification-suites.json` for the exact current selection. The GitHub core
job supplies `-Dtaxonomy.ui.skip=true` because separate Maven-owned UI shards
produce commit-bound browser evidence, checked by the final aggregate. Do not copy
that skip flag into a standalone verification and call it a complete UI pass.

The separate `.github/workflows/database-compatibility.yml` runs PostgreSQL,
SQL Server and Oracle profiles on pull requests to main as well as the documented
tag, scheduled and manual triggers. They are not all implicit in the default
`verify` command, and SQL Server/Oracle are not merely optional scheduled checks.

### Iteration and completion

Run focused tests appropriate to the change, then the broader required gates.
Report exactly which command ran, its result and any missing prerequisites.
A selected module, a direct Java assertion runner or a classpath overlay does not
replace a fresh full-reactor, browser, database or coverage result.

On a fresh reactor, module selections need their dependencies:

```bash
./mvnw -pl taxonomy-app -am test
```

Use `-am` with `-pl` unless an explicitly documented earlier step has installed
the exact sibling artifacts. Do not invent additional exclusion tags or weaken
assertions, coverage, security rules or timeouts to make a failing run green.
Documentation-only edits still need link/rendering/content checks and the existing
repository gates; changes to help rendering or navigation are product-code changes.

For Docker-free verification of the existing supported local scenarios, follow
`docs/testing/docker-free-tests.md` and use its `test-local` profile with matching
local Chrome and ChromeDriver. This is not external-database/container acceptance.
Do not silently substitute it for a failed required container run.

When changing workflow shell commands, execute the changed commands and retain
their evidence before claiming the workflow verified. Keep functional verification
Maven-owned; a workflow must not grow a second independent test selector.

## Catalogue and model boundaries

1. Never invent catalogue codes. Discover official identifiers and hierarchy from
   the application's actual catalogue or its exact recorded snapshot. The eight
   roots are `BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA`. Original entries and local
   navigation additions have different provenance; neither a guessed code nor a
   prefix alone establishes identity or authority.
2. Do not call model endpoints unnecessarily. Tests and exploration must not spend
   the shared remote-provider quota or use paid services without authorization.
   Use existing deterministic fixtures and explicitly selected provider tests.
3. Keep API keys, credentials, cookies and personal source material out of code,
   logs and reports. No published reusable bootstrap password.
4. Local ONNX embeddings are not a generative relation/reformulation model.
   Partial, failed and unexecuted work must not become negative findings or a
   fabricated quality pass.

## Reference Files — Read When Relevant

| File | When to read |
|---|---|
| `.github/copilot-ref-guardrails.md` | Before changes: safety and verification constraints |
| `.github/copilot-ref-architecture.md` | Modules, services, data model and DSL architecture |
| `.github/copilot-ref-screenshots.md` | Screenshot generation and evidence |
| `.github/copilot-ref-llm.md` | Provider integration; verify current limits/configuration before relying on examples |
| `.github/copilot-ref-lessons.md` | Known JGit / DSL / Hibernate problems |
| `docs/dev/MAVEN_VERIFICATION.md` | Maven ownership and evidence aggregation |
| `docs/dev/06-testing-by-change-type.md` | Focused commands and their broader verification boundaries |
