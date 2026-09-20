package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationPublicationPartialTest extends PublicationIntegrationFixture {

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
