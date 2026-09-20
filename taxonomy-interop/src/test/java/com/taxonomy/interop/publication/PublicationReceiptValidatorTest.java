package com.taxonomy.interop.publication;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static com.taxonomy.interop.publication.PublicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationReceiptValidatorTest {
    static PublicationReceipt receipt(PublicationItemRequest r, String digest, ResourceState result) {
        return new PublicationReceipt(1, "taxonomy-publication-contract-v1", r.provider(), r.scope(), r.operationId(), r.item().itemId(), r.item().idempotencyKey(), r.planFingerprint(), digest, ReceiptState.APPLIED, 1, true, r.expectedScopeRevision(), "scope-2", result, null, Instant.EPOCH);
    }
    @Test void acknowledgmentBindsDigestAndExactResultSemantics() {
        var r = request(MutationKind.UPDATE); var result = new ResourceState("a", true, "resource-2", DIGESTS.semantic(r.item().target()));
        var validator = new PublicationReceiptValidator(DIGESTS);
        assertDoesNotThrow(() -> validator.validate(r, receipt(r, r.requestFingerprint(), result)));
        assertThrows(IntegrationProblem.class, () -> validator.validate(r, receipt(r, "different", result)));
        assertThrows(IntegrationProblem.class, () -> validator.validate(r, receipt(r, r.requestFingerprint(), new ResourceState("foreign", true, "resource-2", result.semanticFingerprint()))));
        assertThrows(IntegrationProblem.class, () -> validator.validate(r, receipt(r, r.requestFingerprint(), new ResourceState("a", true, "resource-2", "wrong"))));
    }
    @Test void deletionMustProveAbsence() {
        var r = request(MutationKind.DELETE); var validator = new PublicationReceiptValidator(DIGESTS);
        assertDoesNotThrow(() -> validator.validate(r, receipt(r, r.requestFingerprint(), new ResourceState("a", false, null, null))));
        assertThrows(IntegrationProblem.class, () -> validator.validate(r, receipt(r, r.requestFingerprint(), r.item().expectedResource())));
    }

    @Test void rejectsEveryForeignReceiptBindingAndNonterminalAppliedResult() {
        var r = request(MutationKind.CREATE); var valid = receipt(r, r.requestFingerprint(), new ResourceState("a", true, "resource-2", DIGESTS.semantic(r.item().target())));
        var validator = new PublicationReceiptValidator(DIGESTS);
        java.util.Map<String, Object> replacements = java.util.Map.ofEntries(
                java.util.Map.entry("contractVersion", "other"), java.util.Map.entry("provider", new ProviderIdentity("foreign", "repository", "configuration")),
                java.util.Map.entry("scope", new PublicationScope(EXTERNAL, "other", "selector")), java.util.Map.entry("operationId", java.util.UUID.randomUUID()),
                java.util.Map.entry("itemId", "other"), java.util.Map.entry("idempotencyKey", "other"), java.util.Map.entry("planFingerprint", "other"),
                java.util.Map.entry("beforeScopeRevision", "scope-other"), java.util.Map.entry("afterScopeRevision", "scope-1"), java.util.Map.entry("terminal", false));
        replacements.forEach((field, value) -> assertThrows(IntegrationProblem.class, () -> validator.validate(r, copy(valid, field, value)), field));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, "schemaVersion", 2));
    }
    @Test void rejectsIncompleteSnapshotsAndSemanticNormalization() {
        var doc = document(artifact("a", "A", "")); var good = snapshot(doc); var validator = new PublicationReceiptValidator(DIGESTS);
        assertDoesNotThrow(() -> validator.validateSnapshot(good));
        assertThrows(IntegrationProblem.class, () -> validator.validateSnapshot(new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-1", good.semanticFingerprint(), false, doc, good.resources())));
        assertThrows(IntegrationProblem.class, () -> validator.validateSnapshot(new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-1", good.semanticFingerprint(), true, doc, java.util.Map.of())));
        assertThrows(IntegrationProblem.class, () -> validator.validateSnapshot(new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-1", "different", true, doc, good.resources())));
    }
    @Test void retryableNoEffectIsNonterminalAndKeepsItsImmutableRequest() {
        var r = request(MutationKind.UPDATE); var validator = new PublicationReceiptValidator(DIGESTS);
        var noEffect = new PublicationReceipt(1, "taxonomy-publication-contract-v1", PROVIDER, SCOPE, r.operationId(), r.item().itemId(), r.item().idempotencyKey(), r.planFingerprint(), r.requestFingerprint(), ReceiptState.RETRYABLE_NO_EFFECT, 1, false, "scope-1", "scope-1", r.item().expectedResource(), "RETRY_LATER", Instant.EPOCH);
        assertDoesNotThrow(() -> validator.validate(r, noEffect));
        assertThrows(IntegrationProblem.class, () -> validator.validate(r, copy(noEffect, "terminal", true)));
        assertThrows(IntegrationProblem.class, () -> validator.validate(r, copy(noEffect, "afterScopeRevision", "scope-2")));
    }
    static <T> T copy(T record, String name, Object value) {
        try {
            var fields = record.getClass().getRecordComponents(); var types = new Class<?>[fields.length]; var args = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) { types[i] = fields[i].getType(); args[i] = fields[i].getName().equals(name) ? value : fields[i].getAccessor().invoke(record); }
            @SuppressWarnings("unchecked") T result = (T) record.getClass().getDeclaredConstructor(types).newInstance(args); return result;
        } catch (java.lang.reflect.InvocationTargetException failure) { throw (RuntimeException) failure.getCause(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @Test void completionRequiresAllUniqueReceiptsAndExactFullScopeConvergence() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var plan = planner().plan(p, review(p, PublicationResolution.MERGE));
        var request = DIGESTS.request(plan.operationId(), plan.planFingerprint(), PROVIDER, SCOPE, plan.items().getFirst(), "scope-1");
        var receipt = receipt(request, request.requestFingerprint(), new ResourceState("a", true, "resource-2", DIGESTS.semantic(request.item().target())));
        var snapshot = snapshot(plan.remoteTarget());
        var after = new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-2", snapshot.semanticFingerprint(), true, snapshot.document(), java.util.Map.of("a", receipt.resultingResource()));
        var completed = new PublicationCompletion(1, plan.operationId(), plan.planFingerprint(), java.util.List.of(receipt), after, INTERNAL, DIGESTS.semantic(plan.localTarget()));
        var validator = new PublicationReceiptValidator(DIGESTS);
        assertDoesNotThrow(() -> validator.validateCompletion(plan, completed));
        assertThrows(IntegrationProblem.class, () -> validator.validateCompletion(plan, copy(completed, "receipts", java.util.List.of())));
        assertThrows(IntegrationProblem.class, () -> validator.validateCompletion(plan, copy(completed, "receipts", java.util.List.of(receipt, receipt))));
        assertThrows(IntegrationProblem.class, () -> validator.validateCompletion(plan, copy(completed, "remoteAfter", snapshot)));
        assertThrows(IntegrationProblem.class, () -> validator.validateCompletion(plan, copy(completed, "commonSemanticFingerprint", "other")));
        var skipped = planner().plan(p, review(p, PublicationResolution.SKIP));
        var incomplete = new PublicationCompletion(1, skipped.operationId(), skipped.planFingerprint(), java.util.List.of(), skipped.remoteBefore(), INTERNAL, DIGESTS.semantic(skipped.localTarget()));
        assertThrows(IntegrationProblem.class, () -> validator.validateCompletion(skipped, incomplete));
    }

    @Test void requestValidationBindsPlanAndEnforcesProviderCeiling() {
        var p = preview(null, document(artifact("a", "A", "")), document(), PublicationMode.PUSH);
        var plan = planner().plan(p, review(p, PublicationResolution.MERGE));
        var request = DIGESTS.request(plan.operationId(), plan.planFingerprint(), PROVIDER, SCOPE, plan.items().getFirst(), "scope-1");
        var validator = new PublicationReceiptValidator(DIGESTS);
        assertDoesNotThrow(() -> validator.validateRequest(plan, request));
        assertThrows(IntegrationProblem.class, () -> validator.validateRequest(plan, copy(request, "planFingerprint", "foreign")));
        assertThrows(IntegrationProblem.class, () -> validator.validateRequest(plan, copy(request, "provider", new ProviderIdentity("other", "repository", "configuration"))));
        var cap = plan.capabilities();
        var smallCap = new PublicationCapabilities(1, cap.contractVersion(), cap.provider(), cap.scope(), cap.guarantees(), cap.mutations(), cap.artifactKinds(), cap.verificationReference(), cap.capabilityFingerprint(), cap.maxItems(), 3000, cap.receiptRetentionSeconds());
        var unsigned = copy(plan, "capabilities", smallCap); var smallPlan = copy(unsigned, "planFingerprint", DIGESTS.planFingerprint(unsigned));
        var longRevisionRequest = DIGESTS.request(plan.operationId(), smallPlan.planFingerprint(), PROVIDER, SCOPE, plan.items().getFirst(), "r".repeat(2048));
        assertEquals("PUBLICATION_REQUEST_LIMIT", assertThrows(IntegrationProblem.class, () -> validator.validateRequest(smallPlan, longRevisionRequest)).code());
    }
}
