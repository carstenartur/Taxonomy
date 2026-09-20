package com.taxonomy.interop.publication;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.extension.api.integration.PublicationBounds;
import com.taxonomy.interop.IntegrationProblem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.taxonomy.interop.publication.PublicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationPolicyTest {
    @Test void selfReportedGuaranteesNeverEnableWrites() {
        assertThrows(IntegrationProblem.class, () -> new PublicationPolicy(List.of()).requireVerified(CONTEXT, capabilities()));
        assertDoesNotThrow(() -> policy().requireVerified(CONTEXT, capabilities()));
        var c = capabilities(); var missing = new PublicationCapabilities(1, c.contractVersion(), c.provider(), c.scope(), Set.of(Guarantee.DURABLE_IDEMPOTENCY), c.mutations(), c.artifactKinds(), c.verificationReference(), c.capabilityFingerprint(), c.maxItems(), c.maxRequestBytes(), c.receiptRetentionSeconds());
        assertThrows(IntegrationProblem.class, () -> policy().requireVerified(CONTEXT, missing));
    }
    @Test void constructorsEnforceByteAttemptAndMutationBudgets() {
        assertDoesNotThrow(() -> PublicationBounds.attempt(100)); assertThrows(IllegalArgumentException.class, () -> PublicationBounds.attempt(101));
        assertThrows(IllegalArgumentException.class, () -> new ResourceState("a", true, "W/\"weak\"", "digest"));
        assertThrows(IllegalArgumentException.class, () -> new PublicationScope(EXTERNAL, "https://user:secret@host/path", "selector"));
        assertThrows(IllegalArgumentException.class, () -> DIGESTS.intent(UUID.randomUUID(), MutationKind.CREATE, "a", new ResourceState("a", false, null, null), artifact("a", "title", "x".repeat(1048576)), Set.of()));
    }

    @Test void registrationBindsExactConnectorVersionProviderScopeAndCapabilities() {
        var c = CONTEXT;
        for (var context : List.of(new com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationContext(c.connectionId(), c.authority(), c.externalScope(), c.internalState(), c.actor(), "other", "1"),
                new com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationContext(c.connectionId(), c.authority(), c.externalScope(), c.internalState(), c.actor(), c.profile(), "2")))
            assertThrows(IntegrationProblem.class, () -> policy().requireVerified(context, capabilities()));
        var cap = capabilities();
        var foreign = new PublicationCapabilities(1, cap.contractVersion(), new ProviderIdentity("other", "repository", "configuration"), cap.scope(), cap.guarantees(), cap.mutations(), cap.artifactKinds(), cap.verificationReference(), cap.capabilityFingerprint(), cap.maxItems(), cap.maxRequestBytes(), cap.receiptRetentionSeconds());
        assertThrows(IntegrationProblem.class, () -> policy().requireVerified(CONTEXT, foreign));
    }
    @Test void byteAndScopeCeilingsRejectOversizedValuesWithoutEchoingPayloads() {
        assertDoesNotThrow(() -> PublicationBounds.bounded(16 * 1024 * 1024, "a".repeat(16 * 1024 * 1024 - 8192)));
        assertThrows(IllegalArgumentException.class, () -> PublicationBounds.bounded(16 * 1024 * 1024, "a".repeat(16 * 1024 * 1024)));
        assertDoesNotThrow(() -> PublicationBounds.document(document(Collections.nCopies(10_000, artifact("a", "A", "")).toArray(com.taxonomy.extension.api.integration.IntegrationContracts.Artifact[]::new))));
        assertThrows(IllegalArgumentException.class, () -> PublicationBounds.document(document(Collections.nCopies(10_001, artifact("a", "A", "")).toArray(com.taxonomy.extension.api.integration.IntegrationContracts.Artifact[]::new))));
        for (String malicious : List.of("https://host/%2e%2e/secret", "https://host/path?token=secret", "https://host/%40secret", "https://host/path#secret")) {
            var error = assertThrows(IllegalArgumentException.class, () -> new PublicationScope(EXTERNAL, malicious, "selector"));
            assertFalse(error.getMessage().contains("secret"));
        }
    }
}
