package com.taxonomy.interop.publication;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationPublicationConcurrencyTest extends PublicationIntegrationFixture {

    @Test
    void modelMovementDuringHttpRetainsReceiptAndStopsNextDispatch() throws Exception {
        edit(new CreateArchitectureElement("a", "System", Map.of("title", "A")), new CreateArchitectureElement("b", "System", Map.of("title", "B")));
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        provider.afterCommit = () -> {
            ready.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError();
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };
        var review = review(preview(PublicationMode.PUSH));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> publication.publish(context, connection, review));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            edit(new UpdateArchitectureElement("a", "System", Map.of("title", "Moved")));
            release.countDown();
            var operation = pending.get(15, TimeUnit.SECONDS);
            assertEquals(ItemState.ACKNOWLEDGED, operation.items().getFirst().state());
            assertEquals(PublicationPhase.RECONCILIATION_REQUIRED, operation.phase());
            assertEquals(1, provider.writes.get());
            assertNull(operation.commonCheckpointId());
        } finally {
            release.countDown();
        }
    }
}
