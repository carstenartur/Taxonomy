package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.shared.extension.ExtensionKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationConnectorCatalogTest {
    @Test void exactProfileVersionsAreIndependentLeasedStartupContributions() {
        var catalog = new PluginCatalog();
        var plugin = descriptor(PluginMode.STARTUP);
        catalog.publish(plugin, List.of(connector("1"), connector("2")));
        try (var lease = catalog.acquire(new ExtensionKey(ExtensionKind.INTEGRATION_CONNECTOR, "example-model@2"),
                IntegrationConnectorExtension.class)) {
            assertEquals("2", lease.extension().descriptor().version());
            assertEquals(plugin.identity(), lease.plugin());
            assertEquals(1, catalog.activeLeases(plugin.identity()));
        }
        assertEquals(0, catalog.activeLeases(plugin.identity()));
    }

    @Test void integrationConnectorsCannotBeDynamicallyUnloaded() {
        var catalog = new PluginCatalog();
        assertThrows(IllegalArgumentException.class, () -> catalog.publish(descriptor(PluginMode.DYNAMIC), List.of(connector("1"))));
        assertTrue(catalog.snapshot().extensions().isEmpty());
    }

    @Test void mismatchedContributionIdentityIsRejectedBeforePublication() {
        var catalog = new PluginCatalog();
        var invalid = new Connector("1") {
            @Override public String id() { return "some-other-id"; }
        };
        assertThrows(IllegalStateException.class, () -> catalog.publish(descriptor(PluginMode.STARTUP), List.of(invalid)));
        assertTrue(catalog.snapshot().extensions().isEmpty());
    }

    private static PluginDescriptor descriptor(PluginMode mode) {
        return new PluginDescriptor(new PluginIdentity("example.exchange", "1.0.0", "a".repeat(64)),
                ">=1.3.0 & <2.0.0", List.of(), Set.of("integration.read"), mode);
    }
    private static IntegrationConnectorExtension connector(String version) { return new Connector(version); }
    private static class Connector implements IntegrationConnectorExtension {
        private final String version;
        Connector(String version) { this.version = version; }
        @Override public IntegrationDescriptor descriptor() {
            return new IntegrationDescriptor("example-model", version, "Example model", Set.of(Capability.FILE_IMPORT), Set.of("application/example"));
        }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { throw new AssertionError("Registration must not parse"); }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { throw new UnsupportedOperationException(); }
    }
}
