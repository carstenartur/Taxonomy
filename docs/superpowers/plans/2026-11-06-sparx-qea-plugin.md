# Sparx QEA Plugin Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the shared host work; independent parser/projection work is delegated with explicit file ownership. Steps use checkbox syntax for tracking.

**Goal:** Deliver an optional QEA import JAR through the existing reviewed integration workflow.

**Architecture:** Add a framework-neutral connector contribution and catalog bridge. The plugin parses QEA to exchange values; the host owns canonical projection, authorization, review and durable operations. Exact plugin identity binds preview to apply.

**Tech Stack:** Java 25, Maven, JUnit, existing plugin runtime, SQLite JDBC.

**Spec:** `docs/superpowers/specs/2026-11-06-sparx-qea-plugin-design.md`

## Global Constraints

- Java 25; host SPI 1.3.0; backward compatibility for existing 1.x plugins.
- Profile `sparx-qea@1`; independent STARTUP plugin; provided SDK only.
- Existing integration UI and canonical model; synthetic fixtures and explicit losses.
- No external write, QEA writer, automatic semantic repair or unverified EA compatibility claims.

## Review Focus

- Repeated imports, renamed files and same-name objects retain distinct stable identities.
- Diagram occurrences never move an element out of its package.
- Unrecognized types, invalid endpoints and excluded dependencies cannot enter canonical state silently.
- Missing/replaced plugin artifacts cannot execute an outstanding review with new code.
- Independently packaged SQLite native resources and licenses work through the real plugin classloader.

### Task 1: Public connector contribution and leased registry

**Files:** SDK integration/plugin contracts, `ExtensionKind`, `PluginCatalog`,
`ExchangeConnectorRegistry`, new `IntegrationConnectorCatalogTest` and
`ExternalExchangeConnectorTest`.

**Interfaces:** `IntegrationConnectorExtension extends TaxonomyExtension,
LifecycleIntegrationConnector`, kind INTEGRATION_CONNECTOR, identity
`descriptor().id() + "@" + descriptor().version()`. Capability
CANONICAL_PROJECTION_V1 exposes the documented neutral projection contract.

- [x] Write failing tests for independent versions, duplicate profiles, STARTUP-only admission, lease release, unavailable artifacts and host-owned provenance.
- [x] Observe expected failure, then implement SDK and registry bridge.
- [x] Run API/runtime/interop focused tests and record evidence.

### Task 2: Canonical projection and exact-artifact review

**Files:** `IntegrationDomainAdapter`, `IntegrationPackageProjection`,
`IntegrationService`, projection and integration flow tests, integrations UI.

**Interfaces:** Exact profile descriptor capabilities select neutral projection.
Registry proxy stamps host-owned plugin ID/version/SHA metadata in preview and
rejects different artifacts on validation. Service checks binding before review.

- [x] Write failing tests for generic native packages, diagram membership, explicit endpoint mapping and stale artifact rejection.
- [x] Implement capability-driven projection without QEA-specific profile branches.
- [x] Update shared upload controls and DE/EN copy for binary exchange files.
- [x] Verify existing Sparx/ReqIF/ArchiMate behavior and new neutral projection.

### Task 3: Independent QEA reader plugin

**Files:** `plugins/taxonomy-sparx-plugin/pom.xml`, connector/reader/plugin Java,
ServiceLoader registration, synthetic JUnit fixtures, README and license notices.

**Interfaces:** Consume the Task 1 SDK; return ExchangeDocument with stable GUIDs,
raw source evidence, canonicalType where supported, package and view placements,
snapshot provenance and MappingLoss values. Override validateInboundSelection.

- [x] Write and run failing synthetic reader tests for supported content, repeat imports, rename/move, duplicates, malformed DB and unresolved dependencies.
- [x] Implement bounded read-only SQLite parsing and conservative mapping.
- [x] Build the standalone JAR against the installed SDK; inspect native resources and license notices.

### Task 4: Packaged integration, documentation and review

**Files:** `taxonomy-build/.../ExternalPluginPackagedIT.java` or a focused JUnit
packaged integration test selected by the same Maven profile, plugin authoring
and user documentation, release metadata routing if required by repository policy.

- [x] Add a real packaged-host test: upload/review/apply, identical reimport, changed snapshot, optional absence.
- [x] Run Maven verification at the required module and packaging boundaries; report infrastructure failures precisely.
- [x] Independent branch review, fix concrete findings and verify their regressions.
- [x] Prepare one coherent change and exact test evidence for the PR.

## Verification and delivery

The standalone reader passes 25 tests. The interop and extension-runtime suites
pass 255 and 42 tests respectively; JavaScript contracts pass 1,940 tests, and
the release-version transition test includes the independent Sparx project.
The existing `plugin-packaging-tests,ci-plugin-packaging` reactor passes with
the focused Sparx installed-JAR test, including its build-policy guards.

Independent review prompted the stronger same-database removal/restart check.
It exposed a connection overview that still required the removed plugin to
calculate publication availability. A narrow missing-profile fallback fixes
that read path after authorization; the installed-JAR test now confirms retained
architecture, identities, revision, checkpoint and history and rejects new imports.

The broader local workspace run has one native GPG failure reproduced on the
unchanged base: this environment prohibits the Unix sockets used by gpg-agent.
No production code or verification gate was changed for that restriction.

Commit, PR URL and subsequent CI outcomes are recorded in the pull request.
