package com.taxonomy.interop.publication;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.taxonomy.interop.publication.PublicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationPlannerTest {
    @Test void pushRefusesRemoteOnlyFieldsInsteadOfOverwriting() {
        var base = document(artifact("a", "A", "base")); var p = preview(baseline(base, base), base, document(artifact("a", "A", "remote")), PublicationMode.PUSH);
        assertEquals("RECONCILIATION_REQUIRED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, review(p, PublicationResolution.MERGE))).code());
        assertThrows(IntegrationProblem.class, () -> planner().plan(p, review(p, PublicationResolution.TAKE_REMOTE)));
    }
    @Test void synchronizeFreezesBothTargetsAndSkipsPreserveDivergence() {
        var base = document(artifact("a", "A", "base")); var p = preview(baseline(base, base), document(artifact("a", "Local", "base")), document(artifact("a", "A", "remote")), PublicationMode.SYNCHRONIZE);
        var plan = planner().plan(p, review(p, PublicationResolution.MERGE)); assertEquals(1, plan.items().size());
        assertEquals(DIGESTS.semantic(plan.localTarget()), DIGESTS.semantic(plan.remoteTarget()));
        var skipped = planner().plan(p, review(p, PublicationResolution.SKIP)); assertTrue(skipped.items().isEmpty()); assertFalse(DIGESTS.converged(skipped));
    }
    @Test void rejectsMissingResolutionWithAnOtherwiseValidFrozenReview() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var r = new PublicationReview(new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Review"), Map.of());
        assertEquals("PUBLICATION_RESOLUTION_COVERAGE", assertThrows(IntegrationProblem.class, () -> planner().plan(p, r)).code());
    }
    @Test void rejectsUnknownResolutionWithoutOmittingTheKnownResolution() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var resolutions = new TreeMap<>(review(p, PublicationResolution.MERGE).resolutions()); resolutions.put("unknown", PublicationResolution.SKIP);
        var r = new PublicationReview(new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Review"), resolutions);
        assertEquals("PUBLICATION_RESOLUTION_COVERAGE", assertThrows(IntegrationProblem.class, () -> planner().plan(p, r)).code());
    }
    @Test void rejectsParallelLegacyDecisionsWithExactPublicationCoverage() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var legacy = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(p.changes().getFirst().id(), Decision.ACCEPT), "Review");
        var r = new PublicationReview(legacy, review(p, PublicationResolution.MERGE).resolutions());
        assertEquals("PUBLICATION_RESOLUTION_COVERAGE", assertThrows(IntegrationProblem.class, () -> planner().plan(p, r)).code());
    }
    @Test void rejectsUnsupportedTypeMappingForTheKnownSelectedItem() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var remap = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Review", Map.of(p.changes().getFirst().id(), new MappingOverride("invented", null, null, null)));
        var r = new PublicationReview(remap, review(p, PublicationResolution.MERGE).resolutions());
        assertEquals("PUBLICATION_REMAP_UNSUPPORTED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, r)).code());
    }
    @Test void rejectsEndpointMappingForAnElementWithValidReviewCoverage() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var remap = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Review", Map.of(), Map.of(p.changes().getFirst().id(), new EndpointOverride("source", "target", RelationProjection.ARCHITECTURE_RELATION, "DEPENDS_ON")));
        var r = new PublicationReview(remap, review(p, PublicationResolution.MERGE).resolutions());
        assertEquals("PUBLICATION_REMAP_UNSUPPORTED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, r)).code());
    }
    @Test void deletionRequiresCommonEvidenceAndEmptyPlanNeedsFullEquality() {
        var base = document(artifact("a", "A", "")); var p = preview(baseline(base, base), document(), base, PublicationMode.PUSH);
        assertEquals(MutationKind.DELETE, planner().plan(p, review(p, PublicationResolution.KEEP_LOCAL)).items().getFirst().mutation());
        var bootstrap = preview(null, document(), base, PublicationMode.SYNCHRONIZE);
        assertThrows(IntegrationProblem.class, () -> planner().plan(bootstrap, review(bootstrap, PublicationResolution.KEEP_LOCAL)));
        var equal = preview(null, base, base, PublicationMode.PUSH); var plan = planner().plan(equal, review(equal, PublicationResolution.MERGE));
        assertTrue(plan.items().isEmpty()); assertTrue(DIGESTS.converged(plan));
    }
    @Test void durablePlanAndReviewRoundTripAndFingerprintsAreStable() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH); var review = review(p, PublicationResolution.MERGE);
        var plan = planner().plan(p, review);
        assertEquals(plan, JSON.read(JSON.write(plan), PublicationPlan.class));
        assertEquals(review, JSON.read(JSON.write(review), PublicationReview.class));
        assertEquals(plan, planner().plan(p, review));
        assertThrows(UnsupportedOperationException.class, () -> plan.items().clear());
    }

    @Test void ordersCreateDependenciesAndReversesDeleteDependencies() {
        Artifact parent = artifact("z-parent", "Parent", ""), child = artifact("y-child", "Child", "");
        var relation = new Relation("a-relation", "depends", child.id(), parent.id(), Map.of(), Map.of("direction", "Source -> Destination"));
        var full = new ExchangeDocument("contract", "1", "v", true, "", List.of(parent, child), List.of(relation), List.of(), Map.of(), List.of());
        var create = preview(null, full, document(), PublicationMode.PUSH);
        var plan = planner().plan(create, review(create, PublicationResolution.MERGE));
        assertEquals("a-relation", plan.items().getLast().resourceId());
        assertEquals(Set.of(plan.items().get(0).itemId(), plan.items().get(1).itemId()), plan.items().getLast().dependencies());
        var delete = preview(baseline(full, full), document(), full, PublicationMode.PUSH);
        var deletion = planner().plan(delete, review(delete, PublicationResolution.KEEP_LOCAL));
        assertEquals("a-relation", deletion.items().getFirst().resourceId());
    }
    @Test void rejectsDanglingDependenciesCyclesAndAcceptedUnsupportedLosses() {
        var missing = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("parent", "missing"));
        var p = preview(null, document(missing), document(), PublicationMode.PUSH);
        assertEquals("PUBLICATION_DEPENDENCY_MISSING", assertThrows(IntegrationProblem.class, () -> planner().plan(p, review(p, PublicationResolution.MERGE))).code());
        var a = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("parent", "b"));
        var b = new Artifact("b", ArtifactKind.ELEMENT, "component", "B", "", Map.of(), Map.of("parent", "a"));
        var cycle = preview(null, document(a, b), document(), PublicationMode.PUSH);
        assertEquals("PUBLICATION_DEPENDENCY_CYCLE", assertThrows(IntegrationProblem.class, () -> planner().plan(cycle, review(cycle, PublicationResolution.MERGE))).code());
        var doc = document(artifact("a", "A", ""));
        var loss = new ExchangeDocument(doc.profile(), doc.profileVersion(), "v", true, "", doc.artifacts(), List.of(), List.of(), Map.of(), List.of(new MappingLoss("a", "title", "UNMAPPED", LossDisposition.UNSUPPORTED, "Unmapped field")));
        var unsupported = preview(null, loss, document(), PublicationMode.PUSH);
        assertEquals("PUBLICATION_MAPPING_LOSS", assertThrows(IntegrationProblem.class, () -> planner().plan(unsupported, review(unsupported, PublicationResolution.MERGE))).code());
    }
    @Test void metadataRemovalProducesTheReviewedTarget() {
        var withMetadata = new ExchangeDocument("contract", "1", "v", true, "", List.of(), List.of(), List.of(), Map.of("mapped", "value"), List.of());
        var p = preview(baseline(withMetadata, withMetadata), document(), withMetadata, PublicationMode.PUSH);
        var plan = planner().plan(p, review(p, PublicationResolution.KEEP_LOCAL));
        assertEquals(MutationKind.DELETE, plan.items().getFirst().mutation());
        assertEquals(Map.of(), plan.remoteTarget().metadata()); assertTrue(DIGESTS.converged(plan));
    }
    @Test void boundsMutationCountBeforeAllocatingAnOversizedPlan() {
        var allowed = java.util.stream.IntStream.range(0, 1024).mapToObj(i -> artifact("a" + i, "A", "")).toArray(Artifact[]::new);
        var p = preview(null, document(allowed), document(), PublicationMode.PUSH);
        assertEquals(1024, planner().plan(p, review(p, PublicationResolution.MERGE)).items().size());
        var over = java.util.stream.IntStream.range(0, 1025).mapToObj(i -> artifact("a" + i, "A", "")).toArray(Artifact[]::new);
        var tooMany = preview(null, document(over), document(), PublicationMode.PUSH);
        assertEquals("PUBLICATION_MUTATION_LIMIT", assertThrows(IntegrationProblem.class, () -> planner().plan(tooMany, review(tooMany, PublicationResolution.MERGE))).code());
    }

    @Test void everyDurableValueRoundTripsIncludingTemporalReceiptAndInternalEvidence() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var review = review(p, PublicationResolution.MERGE); var plan = planner().plan(p, review); var item = plan.items().getFirst();
        var request = DIGESTS.request(plan.operationId(), plan.planFingerprint(), PROVIDER, SCOPE, item, "scope-1");
        var receipt = PublicationReceiptValidatorTest.receipt(request, request.requestFingerprint(), new ResourceState("a", true, "resource-2", DIGESTS.semantic(item.target())));
        var outcome = new PublicationItemOutcome(item.itemId(), "a", MutationKind.CREATE, ItemState.ACKNOWLEDGED, 1, null, receipt);
        var completion = new PublicationCompletion(1, plan.operationId(), plan.planFingerprint(), List.of(receipt), snapshot(plan.remoteTarget()), INTERNAL, DIGESTS.semantic(plan.localTarget()));
        var operation = new PublicationOperation(1, plan.operationId(), CONTEXT.connectionId(), null, PublicationMode.PUSH, PublicationPhase.VERIFY_PENDING, OperationStatus.APPLYING, plan.planFingerprint(), plan.reviewFingerprint(), p.publicPreview(), List.of(outcome), null, null, null, INTERNAL, 1, 0, 0, Set.of(PublicationAction.RETRY), null);
        List<Object> values = List.of(PROVIDER, SCOPE, capabilities(), p, p.request(), p.remoteSnapshot(), p.changes().getFirst(), p.publicPreview(),
                baseline(plan.localTarget(), plan.remoteTarget()), plan, review, item, request, receipt, receipt.resultingResource(), outcome, completion, operation,
                new PublicationReceiptQuery(PROVIDER, SCOPE, plan.operationId(), item.itemId(), item.idempotencyKey(), request.requestFingerprint()),
                new PublicationReceiptLookup(LookupState.FOUND, receipt), new PublicationAvailability(true, Set.of(PublicationMode.PUSH), Set.of(MutationKind.CREATE), null),
                new ReconciliationPreviewRequest(UUID.randomUUID(), p.request(), "Reviewed successor"),
                new com.taxonomy.interop.publication.PublicationEvidence.StagedBinding("a", "native-a", null, item.target(), item.target(), false),
                new com.taxonomy.interop.publication.PublicationEvidence.PublicationClaim(plan.operationId(), item.itemId(), UUID.randomUUID(), 1, AttemptKind.SEND, request));
        for (Object value : values) { Object restored = JSON.read(JSON.write(value), value.getClass()); assertEquals(value, restored, value.getClass().getSimpleName()); assertEquals(DIGESTS.fingerprint(value), DIGESTS.fingerprint(restored)); }
    }

    @Test void rejectsCyclesInLocalOnlySynchronizeTargetBeforeAnyTypedApply() {
        var a = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("parent", "b"));
        var b = new Artifact("b", ArtifactKind.ELEMENT, "component", "B", "", Map.of(), Map.of("parent", "a"));
        var p = preview(null, document(), document(a, b), PublicationMode.SYNCHRONIZE);
        assertEquals("PUBLICATION_DEPENDENCY_CYCLE", assertThrows(IntegrationProblem.class, () -> planner().plan(p, review(p, PublicationResolution.TAKE_REMOTE))).code());
    }

    @Test void identityReplacementCannotBecomeAnEmptyOrSkippedCommonPlan() {
        var old = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "old"));
        var replacement = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "new"));
        var p = preview(baseline(document(old), document(old)), document(replacement), document(replacement), PublicationMode.SYNCHRONIZE);
        var empty = new PublicationReview(new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Review"), Map.of());
        assertEquals("PUBLICATION_RESOLUTION_COVERAGE", assertThrows(IntegrationProblem.class, () -> planner().plan(p, empty)).code());
        for (var resolution : PublicationResolution.values())
            assertEquals("PUBLICATION_IDENTITY_REUSED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, review(p, resolution))).code());
    }
    @Test void oneSidedIdentityReplacementCannotBeSkippedPastValidation() {
        var old = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "old"));
        var replacement = new Artifact("a", ArtifactKind.ELEMENT, "component", "A", "", Map.of(), Map.of("internalIdentity", "new"));
        var p = preview(baseline(document(old), document(old)), document(replacement), document(old), PublicationMode.SYNCHRONIZE);
        assertEquals("PUBLICATION_IDENTITY_REUSED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, review(p, PublicationResolution.SKIP))).code());
    }
    @Test void namedRequirementMappingIsFrozenWithoutChangingExternalIdentity() {
        var requirement = new Artifact("r", ArtifactKind.REQUIREMENT, "requirement", "Raw", "", Map.of("name", "Reviewed name", "description", "Reviewed body"), Map.of());
        var doc = new ExchangeDocument("contract", "1", "v", true, "", List.of(requirement), List.of(), List.of(), Map.of(), List.of(new MappingLoss("r", "text", "EMPTY_REQUIREMENT_TEXT", LossDisposition.UNSUPPORTED, "Empty body")));
        var p = preview(null, document(), doc, PublicationMode.SYNCHRONIZE);
        var legacy = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Map existing attributes", Map.of(p.changes().getFirst().id(), new MappingOverride(null, "name", "description", null)));
        var r = new PublicationReview(legacy, review(p, PublicationResolution.TAKE_REMOTE).resolutions());
        var plan = planner().plan(p, r); var mapped = plan.localTarget().artifacts().getFirst();
        assertEquals("r", mapped.id()); assertEquals("Reviewed name", mapped.title()); assertEquals("Reviewed body", mapped.text());
        assertEquals(requirement.attributes(), mapped.attributes()); assertEquals("description", mapped.extensions().get("textAttribute"));
        assertEquals(mapped, plan.remoteTarget().artifacts().getFirst()); assertEquals(DIGESTS.fingerprint(r), plan.reviewFingerprint());
        assertTrue(plan.losses().stream().anyMatch(loss -> loss.code().equals("EMPTY_REQUIREMENT_TEXT") && loss.disposition() == LossDisposition.TRANSFORMED));
        assertEquals(plan, JSON.read(JSON.write(plan), PublicationPlan.class));
        assertEquals(plan, planner().plan(p, r));
    }
    @Test void namedMappingDoesNotResolveUnrelatedUnsupportedContent() {
        var requirement = new Artifact("r", ArtifactKind.REQUIREMENT, "requirement", "Raw", "", Map.of("name", "Reviewed name", "description", "Reviewed body"), Map.of());
        var doc = new ExchangeDocument("contract", "1", "v", true, "", List.of(requirement), List.of(), List.of(), Map.of(), List.of(new MappingLoss("r", "text", "EMPTY_REQUIREMENT_TEXT", LossDisposition.UNSUPPORTED, "Empty body"), new MappingLoss("r", "attachment", "ATTACHMENT_NOT_FETCHED", LossDisposition.UNSUPPORTED, "Unfetched content")));
        var p = preview(null, document(), doc, PublicationMode.SYNCHRONIZE);
        var legacy = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Map existing attributes", Map.of(p.changes().getFirst().id(), new MappingOverride(null, "name", "description", null)));
        var r = new PublicationReview(legacy, review(p, PublicationResolution.TAKE_REMOTE).resolutions());
        assertEquals("PUBLICATION_MAPPING_LOSS", assertThrows(IntegrationProblem.class, () -> planner().plan(p, r)).code());
    }

    @Test void supportedEndpointReviewUsesTheScopedIndexAndPreservesNormalizedExternalEnds() {
        var p = endpointPreview();
        var endpoint = new EndpointOverride("requirement:r", "component:e", RelationProjection.REQUIREMENT_MAPPING, null);
        var reviewed = endpointReview(p, endpoint);
        var scoped = new PublicationMappings(CONTEXT, SCOPE, endpointIndex());
        var plan = new PublicationPlanner(DIGESTS, policy(), capabilities(), scoped).plan(p, reviewed);
        var target = com.taxonomy.interop.ExchangeItems.flatten(plan.localTarget()).get("RELATION:rel");
        assertEquals("rel", target.id()); assertEquals("e", target.extensions().get("source")); assertEquals("r", target.extensions().get("target"));
        assertEquals("Destination -> Source", target.extensions().get("direction"));
        assertEquals("REQUIREMENT_MAPPING", target.extensions().get("nativeProjection"));
        assertEquals("requirement:r", target.extensions().get("nativeSource")); assertEquals("component:e", target.extensions().get("nativeTarget"));
        assertFalse(target.extensions().containsKey("nativeType")); assertEquals(DIGESTS.fingerprint(reviewed), plan.reviewFingerprint());
    }
    @Test void importedEndpointAuthorityCannotAuthorizeLocalProjection() {
        var p = endpointPreview(); var plan = planner().plan(p, review(p, PublicationResolution.TAKE_REMOTE));
        var target = com.taxonomy.interop.ExchangeItems.flatten(plan.localTarget()).get("RELATION:rel");
        for (String field : List.of("nativeProjection", "nativeSource", "nativeTarget", "nativeType")) assertFalse(target.extensions().containsKey(field), field);
    }
    @Test void endpointReviewRequiresExactServerContextAndRejectsRetargeting() {
        var p = endpointPreview();
        var endpoint = new EndpointOverride("requirement:r", "component:e", RelationProjection.REQUIREMENT_MAPPING, null);
        assertEquals("PUBLICATION_ENDPOINT_CONTEXT_REQUIRED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, endpointReview(p, endpoint))).code());
        var wrongActor = PublicationReceiptValidatorTest.copy(CONTEXT, "actor", "other");
        var foreign = new PublicationPlanner(DIGESTS, policy(), capabilities(), new PublicationMappings(wrongActor, SCOPE, endpointIndex()));
        assertEquals("PUBLICATION_ENDPOINT_CONTEXT_MISMATCH", assertThrows(IntegrationProblem.class, () -> foreign.plan(p, endpointReview(p, endpoint))).code());
        var wrongScope = new PublicationScope(EXTERNAL, "different-root", "different-selector");
        var foreignScope = new PublicationPlanner(DIGESTS, policy(), capabilities(), new PublicationMappings(CONTEXT, wrongScope, endpointIndex()));
        assertEquals("PUBLICATION_ENDPOINT_CONTEXT_MISMATCH", assertThrows(IntegrationProblem.class, () -> foreignScope.plan(p, endpointReview(p, endpoint))).code());
        var scoped = new PublicationPlanner(DIGESTS, policy(), capabilities(), new PublicationMappings(CONTEXT, SCOPE, endpointIndex()));
        var retarget = new EndpointOverride("component:e", "requirement:r", RelationProjection.REQUIREMENT_MAPPING, null);
        assertEquals("SPARX_ENDPOINT_KIND_UNMAPPED", assertThrows(IntegrationProblem.class, () -> scoped.plan(p, endpointReview(p, retarget))).code());
    }
    @Test void mappingsRejectUnknownAttributesIdentityChangesAndUnselectedItemKeys() {
        var requirement = new Artifact("r", ArtifactKind.REQUIREMENT, "requirement", "Raw", "Existing body", Map.of("name", "Reviewed name", "description", "Reviewed body"), Map.of());
        var p = preview(null, document(), document(requirement), PublicationMode.SYNCHRONIZE);
        for (var mapping : List.of(new MappingOverride(null, "missing", "description", null), new MappingOverride(null, "name", "name", null), new MappingOverride(null, "name", "description", "retarget"), new MappingOverride(null, " ", "description", null))) {
            var legacy = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Explicit mapping", Map.of(p.changes().getFirst().id(), mapping));
            assertEquals("PUBLICATION_REMAP_UNSUPPORTED", assertThrows(IntegrationProblem.class, () -> planner().plan(p, new PublicationReview(legacy, review(p, PublicationResolution.TAKE_REMOTE).resolutions()))).code());
        }
        var unknown = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Explicit mapping", Map.of("unknown", new MappingOverride(null, "name", "description", null)));
        assertEquals("PUBLICATION_MAPPING_SELECTION", assertThrows(IntegrationProblem.class, () -> planner().plan(p, new PublicationReview(unknown, review(p, PublicationResolution.TAKE_REMOTE).resolutions()))).code());
    }
    private static com.taxonomy.interop.publication.PublicationEvidence.PublicationPreviewEnvelope endpointPreview() {
        var e = artifact("e", "Element", ""); var r = new Artifact("r", ArtifactKind.REQUIREMENT, "requirement", "Requirement", "Body", Map.of(), Map.of());
        var relation = new Relation("rel", "dependency", "e", "r", Map.of(), Map.of("direction", "Destination -> Source", "nativeProjection", "ARCHITECTURE_RELATION", "nativeSource", "imported-source", "nativeTarget", "imported-target", "nativeType", "ENABLES"));
        var remote = new ExchangeDocument("contract", "1", "v", true, "", List.of(e, r), List.of(relation), List.of(), Map.of(), List.of());
        return preview(null, document(e, r), remote, PublicationMode.SYNCHRONIZE);
    }
    private static com.taxonomy.interop.IntegrationDomainAdapter.EndpointIndex endpointIndex() {
        return new com.taxonomy.interop.IntegrationDomainAdapter.EndpointIndex(Map.of(
                "e", new com.taxonomy.interop.IntegrationDomainAdapter.EndpointRef(com.taxonomy.interop.IntegrationDomainAdapter.EndpointKind.ARCHITECTURE_ELEMENT, "component:e", null),
                "r", new com.taxonomy.interop.IntegrationDomainAdapter.EndpointRef(com.taxonomy.interop.IntegrationDomainAdapter.EndpointKind.REQUIREMENT, "requirement:r", 1L)));
    }
    private static PublicationReview endpointReview(com.taxonomy.interop.publication.PublicationEvidence.PublicationPreviewEnvelope p, EndpointOverride endpoint) {
        var legacy = new ReviewedChangeSet(p.request().operationId(), p.previewFingerprint(), Map.of(), "Scoped normalized endpoint review", Map.of(), Map.of("RELATION:rel", endpoint));
        return new PublicationReview(legacy, review(p, PublicationResolution.TAKE_REMOTE).resolutions());
    }
}
