# Alfabet Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deliver Alfabet as an optional independent inventory-source plugin.

**Architecture:** Move the existing bounded reader into the plugin. Add compatible
SDK configuration and principal grants, normalize report evidence and reuse the
startup-only catalog. Keep all domain decisions in the host.

**Tech Stack:** Java 25, Maven Wrapper, JUnit Jupiter, HTTP Client 5, Jackson.

**Spec:** `docs/superpowers/specs/2026-10-28-alfabet-plugin-design.md`

## Global Constraints

- Profile `alfabet-read@1`; host SPI >=1.2.0, <2.0.0; STARTUP mode.
- Provided SDK only; no interop/host/Spring dependency or bundled SDK classes.
- Explicit reads; preserve bounds, credential protection and field states.
- No changes to catalogue, canonical models, persistence or remote data.
- Existing canonical Maven verification remains required and unchanged.

## Review Focus

- Old Request constructors and credential-only lambdas continue to work.
- Invalid configuration or unsupported selection must fail before credential I/O.
- Multi-report object duplicates and relation rows sharing a RefStr retain correct identities.
- Token acquisition and separate object/relation reports share all budgets.
- Private literal IPs, TLS trust, redirected/oversized/stalled responses stay bounded.

### Task 1: Compatible source configuration

**Files:** `taxonomy-extension-api/.../inventory/InventoryReadContracts.java`,
`.../plugin/PluginDescriptor.java`, SDK contract tests.

**Interfaces:** Produce `Request.sourceOptions(): Map<String,String>` with old
constructor retained; `ReadAccess.principal(): char[]` defaults to null.

- [x] Add regression tests for immutable options, bounded metadata and old constructor/lambda behavior; run SDK tests and observe the missing contract.
- [x] Add the compatible contracts and SPI 1.2.0; run `./mvnw -B -pl taxonomy-extension-api -am install`.
- [x] Commit the SDK contract and design/plan.

### Task 2: Alfabet plugin and evidence mapping

**Files:** Move `taxonomy-interop/src/{main,test}/java/com/taxonomy/interop/alfabet/*`
to `plugins/taxonomy-alfabet-plugin/src/{main,test}/java/com/taxonomy/plugins/alfabet/`;
add `AlfabetInventorySource`, `AlfabetPlugin`, `AlfabetSourceConfiguration`, POM and
ServiceLoader resource; add source tests and a local HTTPS fixture.

**Interfaces:** Consume Task 1 Request/ReadAccess; produce the existing neutral
Observation via `InventorySourceExtension.read(Request, ReadAccess)`.

- [x] Add failing discovery and HTTPS observation tests; preserve all moved reader tests.
- [x] Implement config validation, mapping, shared limits, host credential/TLS binding and private transport policy.
- [x] Verify missing/null/empty, subtype fallback, repeated relation base IDs, duplicate objects, selectors, grant failures, request/row budgets, TLS and literal-IP policy.
- [x] Run `./mvnw -B -f plugins/taxonomy-alfabet-plugin/pom.xml verify` and existing LeanIX verify against the installed SDK; commit.

### Task 3: Packaged host, releases and documentation

**Files:** `taxonomy-build/.../ExternalPluginPackagedIT.java`,
`.../PackagedPluginSupport.java`, `.github/scripts/set-maven-versions.sh`,
`taxonomy-extension-api/README.md`, `docs/integrations/alfabet-read.md`, plugin README/examples.

**Interfaces:** Consume the independent plugin JAR; retain the existing Maven selectors.

- [x] Add packaged-host tests for independent build, private dependencies, STARTUP catalog entry, removal rejection and absence without installation.
- [x] Include the independent Alfabet POM in the existing release version transition and execute that script in a temporary checkout.
- [x] Update migration, options, evidence and installation docs; verify links and examples.
- [x] Run plugin packaging and canonical CI verification; record actual results and missing prerequisites.
- [x] Obtain a fresh whole-branch review, fix material findings with regression tests, commit and open a PR.

## Execution evidence (2026-10-28)

Implementation and independent review are complete. The review found no additional
material issues. The broader run exposed an omitted Alfabet fixture in the existing
release-transition test; both fixture creation and version assertions were corrected.

| Verification | Result |
|---|---|
| SDK/domain `-pl taxonomy-extension-api -am install` | PASS; 21 SDK tests, no failures/errors/skips |
| Independent Alfabet `-f plugins/taxonomy-alfabet-plugin/pom.xml verify` | PASS; 45 tests, no failures/errors/skips |
| Existing LeanIX `-f plugins/taxonomy-leanix-plugin/pom.xml verify` | PASS; 40 tests against the updated SDK |
| Release transition and reactor staging regressions | PASS; 3 tests |
| Real release-version script on a temporary copy | PASS; all 23 POMs transitioned, including both independent inventory plugins |
| Shaded JAR, ServiceLoader, relative documentation links and example JSON | PASS |
| Canonical `verify -Pci -DrunOnnxTests=true` | BLOCKED by native GPG environment failure in `taxonomy-workspace` |
| Maven-owned `verify -Pplugin-packaging-tests,ci-plugin-packaging` companion | BUILD SUCCESS; 4 external-plugin and 10 optional-feature integration tests, no failures/errors/skips |
| Live Alfabet acceptance | NOT_EXECUTED; actual installation, approved reports and credentials required |

The canonical run passed the tooling, domain, reporting API, DSL, extension API,
export, extension runtime and Mermaid modules. Workspace reported 1 failure in
1,152 tests: `GitCommitSignatureServiceTest.nativeGitVerifiesGeneratedCommitWhenGitAndGpgAvailable`.
An independent import of the operating system's public signing keys reproduced
exit 2 because `gpg-agent` cannot start. This is not a successful full-reactor result.
The tooling run also reported its existing 5 Helm-dependent skips. The successful
plugin companion reported 10 Helm-dependent build-guard skips; its 14 packaged
integration tests all executed. Helm and Docker are not installed in this
execution environment.

Java 25, task-local Maven proxy/CA settings and an explicit Mockito premain agent
were needed for local execution. No repository test assertions, deadlines,
selection rules or coverage thresholds were relaxed. The documented plugin
companion retains packaged-host checks but skips repeated upstream unit tests;
it does not replace the canonical full-reactor gate.

The isolated Alfabet build inside the packaging suite also executed all 45 tests.
The real packaged host recognized the installed source, rejected runtime
deactivation and remained ready without the plugin. Its interop feature JAR
contains no former `com.taxonomy.interop.alfabet` implementation classes.

Reviewable change: [PR #1324](https://github.com/carstenartur/Taxonomy/pull/1324).
The PR remains a draft pending broader verification.
