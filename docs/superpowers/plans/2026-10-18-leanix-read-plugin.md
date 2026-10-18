# LeanIX read plugin implementation plan

**Goal:** Prepare a separately installable, read-only LeanIX adapter that returns
product-neutral inventory evidence for the agreed time-scoped partial-analysis use case.
**Architecture:** STARTUP TaxonomyPlugin; framework-free source SPI in the existing
extension SDK. Vendor protocol, queries and profile mapping belong to the plugin.
Authorization, frozen analysis/catalogue selection, evaluation and accepted decisions
remain host responsibilities. This package does not activate a new user-facing proxy.
**Spec:** ../specs/2026-10-18-inventory-planning-sources-design.md
**Execution:** Inline implementation requested by the user; no live credentials required.

## Constraints and scope

Java 21; ordinary JUnit Jupiter tests selected by Maven. No new workflow, database,
background job, remote mutation or changes to catalogue identities/analysis scores.
Keep external object, field and relationship evidence, explicit missing-value states
and non-atomic completeness. Limit the first wire profile to Application/ITComponent,
internal lifecycle phases and optionally application-to-component usage relationships.
Publisher support, initiatives, simulations, access entitlement and arbitrary custom
query languages are not supplied by this wire profile.

## Tasks

1. Add `InventorySourceExtension` and bounded immutable `InventoryReadContracts`
   to `taxonomy-extension-api`, plus the additive INVENTORY_SOURCE kind. Increase
   the host SPI minor version to 1.1.0 so old hosts reject the plugin before loading it.
   Test field states, source identity, immutable collections and STARTUP-only admission.
2. Add independent `plugins/taxonomy-leanix-plugin` POM, manifest and ServiceLoader
   entry. Use provided SDK only; shade third-party implementation libraries into
   the plugin namespace. Test construction/discovery without credentials or network.
3. Implement fixed OAuth and GraphQL read paths, bounded HTTPS with connection-time
   DNS policy, no redirects/retries, request-wide budget and sanitized errors.
   Test the actual HTTP path with an in-process HTTPS server and an ephemeral test CA.
4. Implement bounded object and separately paginated relation queries; preserve
   subtype mapping, lifecycle/source fields, revisions and unresolved references.
   Test HTTP-200 errors, null/absent data, repeated cursors/IDs, relation pagination,
   revision changes, source projection and partial-read failure.
5. Extend the existing independent-plugin JUnit packaging test to build this optional
   plugin and verify runtime loading. Do not add it to the standard distribution.
   Document configuration, exact sources, invocation, limitations and live acceptance.

## Verification

Focused SDK/runtime: `./mvnw -B -pl taxonomy-extension-runtime -am test`.
Independent plugin: `mvn -B -f plugins/taxonomy-leanix-plugin/pom.xml verify`
(after installing the exact SDK).
Existing packaged suite: `./mvnw -B verify -Pplugin-packaging-tests`.
Canonical repository: `./mvnw -B verify -Pci -DrunOnnxTests=true`.
Record what actually executed; compilation against retrieved dependencies does not
replace a fresh reactor/JUnit result or a LeanIX installation acceptance.

## Delivered implementation and outstanding verification

The source implementation follows the above bounded adapter/plugin scope. The new
`InventorySourceExtension` is a read-observation subset, not the full inventory
evaluator. The independent JAR is optional; the existing packaged-plugin JUnit
class owns its independent build and installation checks, without a new workflow.
Host SPI is 1.1.0 so old loaders reject an unsupported source family before loading.

Local product compilation with Java 21 and retrieved dependency JARs succeeds. This
is not a fresh reactor build. This environment has no Maven executable/wrapper or
JUnit runtime and cannot resolve the repository/dependency hosts; attempts to run
Maven or retrieve the missing prerequisites did not complete. No local JUnit, shaded
JAR or packaged-host pass is claimed. CI and real-workspace acceptance remain required.
