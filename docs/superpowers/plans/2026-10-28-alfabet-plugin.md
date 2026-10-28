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

- [ ] Add packaged-host tests for independent build, private dependencies, STARTUP catalog entry, removal rejection and absence without installation.
- [ ] Include the independent Alfabet POM in the existing release version transition and execute that script in a temporary checkout.
- [ ] Update migration, options, evidence and installation docs; verify links and examples.
- [ ] Run plugin packaging and canonical CI verification; record actual results and missing prerequisites.
- [ ] Obtain a fresh whole-branch review, fix material findings with regression tests, commit and open a PR.
