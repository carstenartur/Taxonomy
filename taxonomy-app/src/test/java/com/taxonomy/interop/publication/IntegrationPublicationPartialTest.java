package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationPublicationPartialTest extends PublicationIntegrationFixture {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"64,false", "65,false", "100,false", "64,true", "65,true", "100,true"})
    void validTerminalFailureCodeIsDurableAndCanReconcile(int length, boolean lostResponse) throws Exception {
        edit(new CreateArchitectureElement("bounded-code", "System", Map.of("title", "Rejected")));
        provider.failureCode = "R".repeat(length);
        provider.staleAt = 1;
        provider.closeAt = lostResponse ? 1 : 0;
        var rejected = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        if (lostResponse) {
            assertEquals(ItemState.UNKNOWN, rejected.items().getFirst().state());
            publication.retryPublication(context, connection, rejected.operationId());
        }
        var durable = publication.publication(context, connection, rejected.operationId());
        assertEquals(ItemState.REJECTED_STALE, durable.items().getFirst().state(), "Contract-valid code length " + length);
        assertEquals(PublicationPhase.PARTIAL, durable.phase());
        assertEquals(provider.failureCode, durable.items().getFirst().receipt().failureCode());
        var providerEvidence = json.read(java.nio.file.Files.readString(provider.file), PublicationContractProvider.Durable.class);
        assertEquals(providerEvidence.receipts().get(provider.requests.getFirst().item().idempotencyKey()), durable.items().getFirst().receipt(), "Preserve the complete validated receipt");
        String diagnostic = length == 64 ? provider.failureCode : "REJECTED_STALE";
        assertEquals(diagnostic, durable.items().getFirst().failureCode());
        assertEquals(diagnostic, durable.failureCode());
        assertTrue(store.events(context, connection, durable.operationId()).stream().anyMatch(e -> diagnostic.equals(e.failureCode())));
        assertEquals(lostResponse, store.events(context, connection, durable.operationId()).stream().anyMatch(e -> e.type().contains("UNKNOWN")));
        assertTrue(durable.allowedActions().contains(PublicationAction.RECONCILE));
        assertNull(durable.commonCheckpointId());
        assertEquals(durable, publication.retryPublication(context, connection, durable.operationId()));
        var request = new PublicationPreviewRequest(java.util.UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        var successor = publication.previewPublicationReconciliation(context, connection, durable.operationId(), new ReconciliationPreviewRequest(durable.operationId(), request, "Publish corrected state"));
        var completed = publication.publish(context, connection, review(successor));
        assertEquals(PublicationPhase.COMPLETED, completed.phase());
        assertEquals(durable.operationId(), completed.predecessorOperationId());
        assertEquals(2, provider.writes.get());
        assertEquals(lostResponse ? 1 : 0, provider.lookups.get());
        assertEquals(1, provider.mutationCount(completed.items().getFirst().resourceId()));
        assertEquals(provider.failureCode, publication.publication(context, connection, durable.operationId()).items().getFirst().receipt().failureCode());
    }

    @Test
    void completedNoEffectThenLocalMovementAllowsExplicitSuccessor() throws Exception {
        edit(new CreateArchitectureElement("no-effect", "System", Map.of("title", "Before")));
        provider.noEffectAt = 1;
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.RETRYABLE_NO_EFFECT, operation.items().getFirst().state());
        assertEquals(0, provider.mutationCount(operation.items().getFirst().resourceId()));
        edit(new UpdateArchitectureElement("no-effect", "System", Map.of("title", "After")));
        var moved = publication.retryPublication(context, connection, operation.operationId());
        assertEquals(PublicationPhase.RECONCILIATION_REQUIRED, moved.phase());
        assertTrue(moved.allowedActions().contains(PublicationAction.RECONCILE), "The only SEND completed with proven no effect");
        assertFalse(moved.allowedActions().contains(PublicationAction.RETRY));
        assertEquals(operation.operationId(), store.read(context, connection).activeOperationId());
        assertNull(moved.commonCheckpointId());
        assertEquals(1, provider.writes.get(), "Movement cannot dispatch the stale plan");
        assertEquals(0, provider.lookups.get());
        var request = new PublicationPreviewRequest(java.util.UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        var successor = publication.previewPublicationReconciliation(context, connection, moved.operationId(), new ReconciliationPreviewRequest(moved.operationId(), request, "Publish moved local state"));
        assertEquals(moved.operationId(), store.read(context, connection).activeOperationId(), "Preview retains the old reservation");
        var completed = publication.publish(context, connection, review(successor));
        assertEquals(PublicationPhase.COMPLETED, completed.phase());
        assertEquals(moved.operationId(), completed.predecessorOperationId());
        assertEquals(2, provider.writes.get());
        assertEquals("After", provider.snapshot().document().artifacts().getFirst().title());
        assertEquals(1, provider.mutationCount(completed.items().getFirst().resourceId()));
        assertNull(store.read(context, connection).activeOperationId());
        assertNotEquals(provider.requests.getFirst().item().idempotencyKey(), provider.requests.getLast().item().idempotencyKey());
    }

    @Test
    void firstAppliedSecondStaleRetainsReservationAndNeverCompletes() throws Exception {
        edit(new CreateArchitectureElement("a", "System", Map.of("title", "A")), new CreateArchitectureElement("b", "System", Map.of("title", "B")), new CreateArchitectureElement("c", "System", Map.of("title", "C")));
        provider.staleAt = 2;
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.ACKNOWLEDGED, operation.items().getFirst().state());
        assertEquals(ItemState.REJECTED_STALE, operation.items().get(1).state());
        assertEquals(ItemState.READY, operation.items().get(2).state());
        assertEquals(2, provider.writes.get());
        assertNull(operation.commonCheckpointId());
        assertNotNull(store.read(context, connection).activeOperationId());
    }

    @Test
    void commitThenResponseLossRecoversByLookupWithoutDuplicateMutation() throws Exception {
        edit(new CreateArchitectureElement("a", "System", Map.of("title", "A")));
        provider.closeAt = 1;
        var operation = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.UNKNOWN, operation.items().getFirst().state());
        assertNull(operation.commonCheckpointId());
        assertTrue(store.events(context, connection, operation.operationId()).stream().anyMatch(e -> e.type().contains("UNKNOWN")));
        var recovered = publication.retryPublication(context, connection, operation.operationId());
        assertEquals(PublicationPhase.COMPLETED, recovered.phase());
        assertEquals(1, provider.writes.get());
        assertEquals(1, provider.lookups.get());
        assertEquals(1, provider.mutationCount(recovered.items().getFirst().resourceId()));
    }

    @Test
    void skippedDivergenceKeepsCommonAndLinkedSuccessorUsesHistoricalIdentity() throws Exception {
        edit(new CreateArchitectureElement("a", "System", Map.of("title", "A")), new CreateArchitectureElement("b", "System", Map.of("title", "B")));
        var first = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        var common = first.commonCheckpointId();
        var originalIds = provider.snapshot().resources().keySet();
        edit(new UpdateArchitectureElement("a", "System", Map.of("title", "A changed")), new UpdateArchitectureElement("b", "System", Map.of("title", "B changed")));
        var draft = preview(PublicationMode.PUSH);
        var choices = new java.util.TreeMap<>(review(draft).resolutions());
        choices.put(choices.firstKey(), PublicationResolution.SKIP);
        var selected = new PublicationReview(new com.taxonomy.extension.api.integration.IntegrationContracts.ReviewedChangeSet(draft.operationId(), draft.preview().fingerprint(), Map.of(), "Publish selected changes"), choices);
        var partial = publication.publish(context, connection, selected);
        assertEquals(PublicationPhase.PARTIAL, partial.phase());
        assertEquals(common, partial.commonCheckpointId());
        assertEquals(1, partial.acknowledgedCount());
        assertTrue(partial.allowedActions().contains(PublicationAction.RECONCILE));
        var nextRequest = new PublicationPreviewRequest(java.util.UUID.randomUUID(), state(), PublicationMode.PUSH, PublicationContractProvider.SCOPE, provider.snapshot().revision());
        var successor = publication.previewPublicationReconciliation(context, connection, partial.operationId(), new ReconciliationPreviewRequest(partial.operationId(), nextRequest, "Reconcile remaining divergence"));
        var completed = publication.publish(context, connection, review(successor));
        assertEquals(PublicationPhase.COMPLETED, completed.phase(), completed.failureCode());
        assertEquals(partial.operationId(), completed.predecessorOperationId());
        assertEquals(originalIds, provider.snapshot().resources().keySet());
        assertNotEquals(common, completed.commonCheckpointId());
        assertFalse(publication.publication(context, connection, partial.operationId()).allowedActions().contains(PublicationAction.RETRY));
    }

    @Test
    void rejectedDeleteRetainsPreviousCommonAndExplicitRemoteExistence() throws Exception {
        edit(new CreateArchitectureElement("delete", "System", Map.of("title", "Delete")));
        var first = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        String resource = first.items().getFirst().resourceId();
        edit(new DeleteArchitectureElement("delete"));
        provider.staleAt = 2;
        var rejected = publication.publish(context, connection, review(preview(PublicationMode.PUSH)));
        assertEquals(ItemState.REJECTED_STALE, rejected.items().getFirst().state());
        assertEquals(MutationKind.DELETE, rejected.items().getFirst().mutation());
        assertEquals(first.commonCheckpointId(), rejected.commonCheckpointId());
        assertTrue(provider.snapshot().resources().get(resource).exists());
        assertFalse(store.identities(context, connection).getFirst().removed());
    }
}
