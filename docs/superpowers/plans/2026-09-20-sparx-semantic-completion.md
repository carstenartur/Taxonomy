# Sparx semantic read and native mapping implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the documented AM feature reads and faithful native package/requirement mappings for #1075 without a proprietary runtime.

**Architecture:** Immutable v2 profiles extend the shared canonical exchange model and connector registry. XMI and AM feed one semantic mapper, while reviewed changes enter generic package and requirement-mapping commands in the existing journal. V1 is retained and proprietary compatibility remains separately evidenced.

**Tech Stack:** Java 21 target (local verification JDK 25), Spring, existing RDF/XML parsers and HTTP transport, generic DSL/editor, JPA journal, JUnit and Maven.

**Spec:** `docs/superpowers/specs/2026-09-20-sparx-semantic-completion.md`

## Global Constraints

- Preserve the repository's Java 21 language/API target; local JDK 25 is only verification infrastructure.
- Registry identity is `(profile ID, version)`; existing `sparx-xmi-2.1@1` and `sparx-oslc-am-2.0@1` connections remain frozen, and omitted creation version means `1`.
- V2 AM and XMI share canonical identities, feature meanings, connector direction and loss reporting. Neither path invents another domain model.
- AM read budgets cover the whole operation: 250 enriched roots, 20 pages per chain, 1,024 responses, 16 MiB, 100,000 RDF statements, 10,000 objects, 128 properties per object, 32 stereotypes and 30 seconds.
- Failed, incomplete or non-atomic AM reads never authorize deletion; v2 `completeScope` stays false.
- Credentials exist only at the HTTP boundary; same-origin/path/DNS/redirect protections apply to every feature page. Persist only sanitized URI/version evidence.
- Native packages are organization boundaries, not architecture elements or `CONTAINS` relations. Maximum hierarchy depth is 80.
- Requirement connectors require explicit typed requirement-mapping projection. Existing architecture relation rules are not weakened.
- No new Python file in the repository. Only one Maven reactor at a time; use `/workspace/scratch/dae1667028d4/verify-repo.py` in this worktree.
- EA/PCS compatibility remains `NOT_EXECUTED`; AM descriptors stay read/pull-only. Test fixtures are identified as contract fixtures.

## Review Focus

- A feature-only remote edit with unchanged root resources invalidates a reviewed preview before local mutation.
- New profile deployment cannot reinterpret durable v1 mappings or route a v1 request to a v2 codec.
- Duplicate tag names and repeated connector representations preserve identities or reject contradictory values; response order never chooses the winner.
- A package rename/move and requirement relation re-import preserve business identity, semantic history and undo/redo behavior.
- Invalid endpoint/remap/dependency plans fail transactionally before requirement creation and never create fake native elements.

### Task 1: Versioned canonical features and complete bounded AM reads

**Files:**
- Modify: `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/integration/IntegrationContracts.java` (`FEATURE`).
- Modify/create beside: `taxonomy-export/src/main/java/com/taxonomy/exchange/sparx/SparxMappingProfile.java`, `SparxModelValidator.java`, `SparxXmiCodec.java`, `SparxXmiReader.java`, `SparxXmiWriter.java`, `SparxOslcAmCodec.java`, shared semantic assembler and bounded feature DTOs.
- Modify: `taxonomy-interop/src/main/java/com/taxonomy/interop/ExchangeConnectorRegistry.java`, `IntegrationService.java`, `sparx/SparxSnapshots.java`, `SparxIntegrationDescriptor.java`, `SparxXmiConnector.java`, `SparxOslcAmConnector.java`, `SparxOslcAmReader.java`; add cohesive v2 profile adapters/read-budget/evidence helpers in those owning packages.
- Modify: `taxonomy-app/src/main/resources/static/js/integrations.js`, `templates/integrations.html`, `i18n/messages_integrations.properties`, `i18n/messages_integrations_de.properties` for explicit version selection/notice.
- Test: `taxonomy-export/src/test/java/com/taxonomy/exchange/sparx/SparxXmiCodecTest.java`, `SparxOslcAmCodecTest.java`; interop `SparxOslcAmReaderTest`, registry/profile tests; app `SparxIntegrationFlowTest`; fixtures under `taxonomy-export/src/test/resources/interoperability/sparx/` with origin explanation.
- Docs: `docs/features/sparx-integration.md`, `sparx-integration-de.md`, `docs/qa/sparx-implementation-validation.md` and mapping tables.

**Interfaces:**
- Consumes existing `ExchangeDocument`, `Artifact`, `Relation`, `Placement`, `ExchangeItems`, scoped `OslcTransport.readPcs` and durable remote preview/revalidation.
- Produces `ArtifactKind.FEATURE`; types `tagged-value`, `attribute`, `operation`, `parameter`, `external-connector` with stable GUID and `extensions.owner`, optional bounded `extensions.position` and preserved scalar/structured fields from the spec.
- Produces `ExchangeConnectorRegistry.require(String id, String version)`. Retain `require(String id)` as legacy version-1 lookup. `CreateConnection` gains optional `profileVersion` with source-compatible constructors. All stored-operation dispatch uses the connection's exact version.
- Produces v2 codecs/readers selectable via the existing profile IDs and version `2`; default constructors/methods remain v1 where legacy callers depend on them. The AM public `read` returns the selected version's `ExchangeDocument` and hashes all successful collection evidence.

- [ ] **Step 1: Add RED fixtures and tests.** Build matching v2 AM/XMI fixtures with nested packages, two mapped elements, a directed connector, duplicate tag names, an attribute, operation and parameter. Assert semantic identity rather than bytes:

```java
assertThat(amDocument.profileVersion()).isEqualTo("2");
assertThat(semanticItems(amDocument)).isEqualTo(semanticItems(xmiDocument));
assertThat(amDocument.artifacts().stream().filter(a -> a.kind() == ArtifactKind.FEATURE))
    .extracting(Artifact::type).contains("tagged-value", "attribute", "operation", "parameter");
assertThat(amDocument.completeScope()).isFalse();
```

Use real local HTTP fixture responses for pagination on every collection kind, later-page failure, escaped next URI, credential reflection and unchanged-root/changed-feature stale apply. Add v1/v2 registry and JSON creation compatibility tests. Test aggregate bounds with injectable clock/budget seams, avoiding wall-clock sleeps.

- [ ] **Step 2: Run RED.** Run `python /workspace/scratch/dae1667028d4/verify-repo.py -pl taxonomy-interop -am test -Dtest=SparxXmiCodecTest,SparxOslcAmCodecTest,SparxOslcAmReaderTest,ExchangeConnectorRegistryTest -Dsurefire.failIfNoSpecifiedTests=false -DexcludedGroups=real-llm`; retain the failure that demonstrates unavailable features/version routing.

- [ ] **Step 3: Implement semantic v2 mapping.** Validate feature prefixes by kind and owner, then share canonical assembly between transport readers. Preserve duplicate tags as separate features; project unique reserved tags only after validation. In-scope connectors become canonical relations; out-of-scope endpoints remain explicit preserved features. Conflicting duplicate connector GUIDs fail. Write the supported bounded feature subset back to XMI and re-import it; reject unsupported lossy output unless represented as explicit preserved evidence. Add `(id,version)` registry entries without default version migration.

The feature traversal contract is deterministic:

```java
for (RootResource root : rootsInGuidOrder) {
    collectLinkedResources(root);
    collectTaggedValues(root);
    if (root.isElement()) {
        for (Feature attribute : collectAttributes(root)) collectTaggedValues(attribute);
        for (Feature operation : collectOperations(root)) {
            collectTaggedValues(operation);
            collectParameters(operation);
        }
    }
}
```

These traversal helpers share one budget and the existing secured transport. Construct fixed feature endpoints from validated IDs; never follow arbitrary RDF feature links. Collection pagination has an exact same-endpoint grammar. Hash endpoint kind/owner/sanitized URI/ETag/content hash across all pages in canonical order, and repeat the same read during reviewed apply. Any collection failure produces a durable fetch failure and no partial preview.

- [ ] **Step 4: Run GREEN and app contract.** Repeat focused tests, then run app `SparxIntegrationFlowTest` with `-pl taxonomy-app -am` and the same exclusion. Assert no native mutations on stale/failed feature reads, no token in journal/error/evidence and exact v1 compatibility. Update EN/DE profile selection and limitations with fixture origin.

- [ ] **Step 5: Self-review and commit.** Verify `git diff --check`, deterministic ordering, bounds, module direction and every registry dispatch. Record exact test evidence and commit; do not mark native package editing complete here.

### Task 2: Native packages and explicit requirement endpoint projection

**Files:**
- Create: `taxonomy-dsl/src/main/java/com/taxonomy/dsl/model/ArchitecturePackage.java` and cohesive package hierarchy helper if required.
- Modify: `taxonomy-dsl/src/main/java/com/taxonomy/dsl/model/CanonicalArchitectureModel.java`, `mapper/AstToModelMapper.java`, `mapper/ModelToAstMapper.java`, `command/ArchitectureCommand.java`, `command/ArchitectureDslCommands.java`, existing DSL validator/serializer/inverse handling.
- Modify: `taxonomy-workspace/src/main/java/com/taxonomy/editor/ArchitectureEditorProjection.java`, `ArchitectureEditorController.java` and existing editor UI/i18n assets for package controls.
- Modify: `taxonomy-extension-api/src/main/java/com/taxonomy/extension/api/integration/IntegrationContracts.java` (`EndpointOverride`, reviewed endpoint map); interop `IntegrationDomainAdapter.java`, `IntegrationPortfolioPort.java` only for portfolio-owned plan interfaces, `IntegrationService.java` and integration UI/API.
- Modify: `taxonomy-app/src/main/java/com/taxonomy/composition/interop/PortfolioInteropAdapter.java`, the portfolio contributor identity/lookup boundary in `taxonomy-portfolio/src/main/java/com/taxonomy/portfolio/service/PortfolioGitService.java`, and `taxonomy-workspace/src/main/java/com/taxonomy/editor/ArchitectureEditorService.java` only for the validated pre-command portfolio contribution ordering.
- Test: DSL command/validation/serialization tests, interop `SparxProjectionTest`, app `ArchitectureEditorControllerTest`, `SparxIntegrationFlowTest`, `IntegrationRestartTest` and civilian integration walkthrough.
- Docs: native editor and Sparx feature documentation EN/DE, typed operation/provenance examples.

**Interfaces:**
- Consumes Task 1's v2 `SPECIFICATION` packages, `PLACEMENT` hierarchy, `FEATURE` evidence, requirements and relations, exact `(id,version)` registry.
- Produces generic `CreateArchitecturePackage(String id, Map<String,String> properties)`, `UpdateArchitecturePackage(String id, Map<String,String> properties)`, `SetArchitecturePackagePlacements(List<PackagePlacement> placements, Set<String> completeParentScopes)`, `DeleteArchitecturePackage(String id)` commands. `PackagePlacement(PackageMemberKind kind,String memberId,String parentPackageId,int position)` uses `PACKAGE`/`ELEMENT` kinds and explicit root/detach representation.
- Produces `UpsertRequirementMapping(String requirementIdentity,String elementId,String rationale,Map<String,String> exchangeProperties)` and `DeleteRequirementMapping(String requirementIdentity,String elementId)` over existing `RequirementMapping` DSL meaning.
- Produces `RelationProjection { ARCHITECTURE_RELATION, REQUIREMENT_MAPPING, PRESERVE_ONLY }`, `EndpointOverride(String sourceInternalIdentity,String targetInternalIdentity,RelationProjection projection,String canonicalType)`, and `ReviewedChangeSet.endpoints()` with backward-compatible old constructors/JSON.
- Produces pure planned requirement business/canonical identities and an endpoint index before executing writes; resolve the real canonical requirement by exact `x-project-key`/`x-requirement-key`, preserving old IDs and rejecting collisions. Accepted mapping and package state participates in the same atomic local semantic journal and existing Git checkpoint policy.

- [ ] **Step 1: Add RED native behavior tests.** Cover package creation, rename, move, placement, delete dependencies, cycle/depth/position validation, round-trip through both AST mapper directions and undo/redo. Assert package identity stays out of semantic element lists and relation matrix. Add explicit requirement mapping and invalid endpoint integration tests:

```java
assertThat(model.getPackages()).extracting(ArchitecturePackage::id).contains(packageId);
assertThat(model.allElementIds()).doesNotContain(packageId);
assertThat(commands).anyMatch(c -> c instanceof UpsertRequirementMapping);
assertThat(commands).noneMatch(c -> c instanceof CreateArchitectureRelation);
assertThatThrownBy(() -> applyInvalidEndpointReview()).isInstanceOf(IntegrationProblem.class);
assertThat(requirementsAfter()).isEqualTo(requirementsBefore);
```

Fixture helper methods use the real scoped service/store/editor as in existing integration tests. Cover v1 connection retention, rejected/preserved-only relations, requirement-to-requirement, package endpoints, reverse direction and bidirectionality. Test planned new requirement identity is the identity later contributed to DSL, two projects with the same requirement key remain distinct, colliding sanitized IDs fail before mutation, and a portfolio-analysis mapping cannot be overwritten. Package tests must include sibling swaps, cross-parent complete-list moves, unchanged unmentioned siblings, explicit detach, and atomic rejection of a multi-move cycle.

- [ ] **Step 2: Run RED.** Run focused DSL and interop tests with `-pl taxonomy-interop -am`, then app endpoint/journal tests with `-pl taxonomy-app -am`; record actual failures before adding commands.

- [ ] **Step 3: Implement typed domain and projection.** Parse/serialize neutral packages independently of element type rules. Validate hierarchy and dependency-safe moves/deletion, add inverse semantics, expose native editor schema/controls under exact revision checks. Build requirement plans and complete endpoint/dependency validation before executing portfolio writes, then validate contributed DSL in the same transaction. Direction normalization precedes endpoint-kind validation. Require explicit review choices for requirement mappings; preserve unsupported package/requirement endpoints as evidence with precise loss codes. Native export maps package/placement/mapping edits through stable identities and v2 XMI.

The application ordering must retain this invariant:

```java
var requirementPlans = planRequirements(connection, selected, mappings, current);
var endpointIndex = indexEndpoints(selected, mappings, requirementPlans);
var commands = planArchitectureCommands(connection, dsl, selected, endpointIndex, review.endpoints());
validateCompletePlan(commands, requirementPlans);
executeRequirements(requirementPlans);
acceptTypedSemanticBatch(commands);
```

Use cohesive private methods/records with the named responsibilities rather than separate storage or an unrestricted relation fallback. `acceptTypedSemanticBatch` first applies the real portfolio contribution (after joined requirement writes) and then typed mappings, so the real canonical requirement exists. Final sibling state is applied atomically; moves are not a sequence of temporarily conflicting position assignments. V1 behavior stays unchanged; no automatic endpoint projection is inferred from display names. Preserve existing portfolio canonical IDs; collision rejection is deliberately safer than an unrelated global identity migration.

- [ ] **Step 4: Run GREEN through the application.** Verify create/rename/move/re-import twice and restart recovery retain native package IDs, EA GUID mapping and semantic operation counts. Invalid connector plans roll back requirements/packages/elements/mappings. Extend the civilian integration walkthrough to apply/edit/export/import a package and explicit requirement mapping through the actual APIs, retaining the real application and only replacing remote boundaries with documented contract fixtures.

- [ ] **Step 5: Self-review and commit.** Run affected native/editor/interop tests, `git diff --check`, review EN/DE controls/keyboard access, and commit. Report exact command/output and any final browser environment gate for controller follow-up.

## Self-review and execution decisions

Task 1 supplies canonical v2 features and exact version dispatch; Task 2 consumes those immutable meanings without altering the old profile. Both share `IntegrationContracts`, `IntegrationService`, the registry and integration UI sequentially. Public version/profile strings in both tasks match. Optional attributes/operations remain declared preserved exchange features, not counterfeit native UML authoring. Remote publication is owned by the separate publication plan, consuming the typed commands completed here.
