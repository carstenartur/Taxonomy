# Guardrails — Read Before Making Any Changes

## Preserve real catalogue identity and provenance

The eight catalogue roots are `BP`, `BR`, `CP`, `CI`, `CO`, `CR`, `IP`, `UA`.
Official concrete entries commonly use `XX-XXXX`; not every number exists and
local navigation additions are not official architecture concepts. A syntactic
prefix is not an identity or provenance check.

Never invent example identifiers for live requests. Discover existing entries
from `GET /api/taxonomy` or the exact recorded catalogue snapshot. Use the real
catalogue in application acceptance tests. Existing authored unit fixtures remain
fixtures and must not be advertised as evidence from real model inference.
Do not overwrite original Excel entries, source order or sensible parent links
merely to simplify a test or traversal.

## Model endpoints and credentials

Do not invoke analysis, streaming, node scoring or justification endpoints in
exploratory scripts against a remote provider. They can consume shared quota and
incur costs. Use the existing explicit provider test path for approved live tests;
no hidden fallback from a deterministic/local test to a remote service.

Never hardcode or disclose provider keys, session cookies, passwords or source
payloads. Keep secrets in the existing runtime/CI secret mechanism. Do not include
them in diagnostic artifacts. Provider limits depend on configuration and service
terms; do not treat historical quota numbers as universally applicable.

## Screenshot and help evidence

`ScreenshotGeneratorIT` is opt-in. Use the existing screenshot profile and its
activation contract rather than adding screenshots to the ordinary build.
Screenshots must identify the source/version they actually depict. Do not relabel
an old screenshot as fresh acceptance or mark absent evidence as passed.

Documentation changes need content, link and rendering checks. Help-controller,
HTML-processing or browser-navigation changes are runtime changes and need
behavioral regression tests as well as the normal code gates.

## Readiness checks

Do not use administrative `/api/diagnostics` as a generic health check.
Use the documented read-only health/readiness endpoint for the specific purpose,
respect its authentication and distinguish application readiness, catalogue
readiness, model availability and a usable semantic index. An AI-status badge
alone does not establish that every analysis capability can execute.

## Test failures and final verification

Reproduce failures before changing code. Do not extend timeouts, remove assertions,
change coverage baselines or hide warnings solely to obtain a green result.
Report missing Docker, browser, wrapper, dependency or model prerequisites as such.
They are not successful or zero-score test outcomes.

The required canonical local entry point is:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

The full Java/Maven-only equivalence requirement remains open; see the concrete
runtime and lifecycle gaps in `docs/dev/MAVEN_VERIFICATION.md`.

The exact selections and sharding are owned by the current POMs,
`.mvn/verification-suites.json`, `.github/workflows/ci-cd.yml` and
`.github/workflows/database-compatibility.yml`. A GitHub core job with UI skipped
is complete only together with its corresponding shard evidence and aggregate.
Do not call the older `verify -DexcludedGroups="real-llm"` command equivalent to
all current CI lanes.

Focused commands are for iteration, not substitutes for required broader checks.
The documented Docker-free `test-local` profile retains local scenarios; it does
not certify PostgreSQL, Oracle, SQL Server or container execution.

## Multi-module Maven

A fresh module selection must also build needed siblings:

```bash
./mvnw -pl taxonomy-app -am test
```

Use `-am` with `-pl` unless an explicit preceding step installed the exact sibling
artifacts. `verify` does not install sibling artifacts into the local repository.
Do not interpret an isolated partial compile as a full-reactor pass. Workflow
changes must retain Maven's ownership of test selection and evidence.
