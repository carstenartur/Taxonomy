package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExternalExchangeConnectorTest {
    private final PluginCatalog catalog = new PluginCatalog();
    private final PluginDescriptor plugin = plugin("a");

    @Test void invokesTheExactLeasedArtifactAndOverwritesSpoofedProvenance() {
        catalog.publish(plugin, List.of(new Connector("example-model", "1") {
            @Override public ExchangeDocument previewInbound(InboundRequest request) {
                assertEquals(1, catalog.activeLeases(plugin.identity()));
                return document("example-model", "1", Map.of("taxonomy.plugin.id", "forged", "source", "fixture"));
            }
        }));
        var registry = new ExchangeConnectorRegistry(List.of(), catalog);
        var result = registry.require("example-model", "1").previewInbound(request());
        assertEquals("example.exchange", result.metadata().get("taxonomy.plugin.id"));
        assertEquals("1.0.0", result.metadata().get("taxonomy.plugin.version"));
        assertEquals("a".repeat(64), result.metadata().get("taxonomy.plugin.sha256"));
        assertEquals("fixture", result.metadata().get("source"));
        assertEquals(0, catalog.activeLeases(plugin.identity()));
        assertDoesNotThrow(() -> registry.requireCompatible(result));
    }

    @Test void changedOrMissingArtifactInvalidatesAnOutstandingPreview() throws Exception {
        catalog.publish(plugin, List.of(new Connector("example-model", "1")));
        var registry = new ExchangeConnectorRegistry(List.of(), catalog);
        var handle = registry.require("example-model", "1");
        var preview = handle.previewInbound(request());
        catalog.beginDraining(plugin.identity());
        assertTrue(catalog.awaitReleased(plugin.identity(), Duration.ZERO));
        catalog.remove(plugin.identity());
        assertEquals("INTEGRATION_PLUGIN_CHANGED", assertThrows(IntegrationProblem.class, () -> registry.requireCompatible(preview)).code());
        catalog.publish(plugin("b"), List.of(new Connector("example-model", "1")));
        assertEquals("INTEGRATION_PLUGIN_CHANGED", assertThrows(IntegrationProblem.class, () -> registry.requireCompatible(preview)).code());
        assertEquals("INTEGRATION_PLUGIN_CHANGED", assertThrows(IntegrationProblem.class, () -> handle.previewInbound(request())).code());
        var fresh = registry.require("example-model", "1").previewInbound(request());
        assertDoesNotThrow(() -> registry.requireCompatible(fresh));
    }

    @Test void versionsCoexistAndBuiltInProfilesCannotBeShadowed() {
        catalog.publish(plugin, List.of(new Connector("example-model", "1"), new Connector("example-model", "2")));
        var registry = new ExchangeConnectorRegistry(List.of(), catalog);
        assertEquals(List.of("1", "2"), registry.descriptors().stream().filter(d -> d.id().equals("example-model")).map(IntegrationDescriptor::version).toList());
        var other = new PluginCatalog();
        other.publish(plugin, List.of(new Connector("reqif-1.2", "1")));
        assertThrows(IllegalStateException.class, () -> new ExchangeConnectorRegistry(List.of(), other));
    }

    @Test void pluginBindingIsNotAnEditableModelChange() {
        catalog.publish(plugin, List.of(new Connector("example-model", "1")));
        var registry = new ExchangeConnectorRegistry(List.of(), catalog);
        var preview = registry.require("example-model").previewInbound(request());
        var items = new TreeMap<>(ExchangeItems.flatten(preview));
        var model = items.get("METADATA:package");
        assertFalse(model.attributes().containsKey("taxonomy.plugin.sha256"), "Artifact identity is a host precondition, not a user-remappable model field");
        var forged = new TreeMap<>(model.attributes());
        forged.put("taxonomy.plugin.sha256", "b".repeat(64));
        items.put("METADATA:package", new Artifact(model.id(), model.kind(), model.type(), model.title(), model.text(), forged, model.extensions()));
        var selected = ExchangeItems.expand(preview, items);
        assertEquals("a".repeat(64), selected.metadata().get("taxonomy.plugin.sha256"));
        assertDoesNotThrow(() -> registry.requireCompatible(selected));
    }

    @Test void leaseIsReleasedOnParserFailureAndWrongProfileIsRejected() {
        catalog.publish(plugin, List.of(new Connector("example-model", "1") {
            @Override public ExchangeDocument previewInbound(InboundRequest request) { throw new IllegalArgumentException("bad input"); }
        }));
        var registry = new ExchangeConnectorRegistry(List.of(), catalog);
        assertThrows(IllegalArgumentException.class, () -> registry.require("example-model").previewInbound(request()));
        assertEquals(0, catalog.activeLeases(plugin.identity()));
        var other = new PluginCatalog();
        other.publish(plugin, List.of(new Connector("example-model", "1") {
            @Override public ExchangeDocument previewInbound(InboundRequest request) { return document("different", "1", Map.of()); }
        }));
        var invalid = new ExchangeConnectorRegistry(List.of(), other);
        assertEquals("INVALID_CONNECTOR_DOCUMENT", assertThrows(IntegrationProblem.class,
                () -> invalid.require("example-model").previewInbound(request())).code());
        assertEquals(0, other.activeLeases(plugin.identity()));
    }

    @Test void basedFileCallbacksHoldTheExactLeaseAndCannotUseAReplacedArtifact() throws Exception {
        catalog.publish(plugin, List.of(new Connector("example-model", "1") {
            @Override public IntegrationDescriptor descriptor() {
                return new IntegrationDescriptor("example-model", "1", "Based fixture",
                        Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT, Capability.BASED_FILE_EXPORT_V1), Set.of("application/example"));
            }
            @Override public FileExportProfile fileExportProfile() {
                assertEquals(1, catalog.activeLeases(plugin.identity()));
                return new FileExportProfile(Set.of(FileExportMode.NEW_PROJECT), ExternalIdentityFormat.UUID_BRACED_UPPER,
                        Map.of("System", "Node"), Map.of("DEPENDS_ON", "Dependency"), "Requirement", "Package", "Diagram");
            }
            @Override public ExchangeFile previewOutbound(BasedOutboundRequest request) {
                assertEquals(1, catalog.activeLeases(plugin.identity()));
                return new ExchangeFile("application/example", "result.bin", new byte[]{7, 8}, List.of());
            }
        }));
        var registry = new ExchangeConnectorRegistry(List.of(), catalog);
        var connector = registry.require("example-model", "1");
        var request = request(); var preview = connector.previewInbound(request);
        assertEquals(Set.of(FileExportMode.NEW_PROJECT), connector.fileExportProfile().modes());
        var based = new BasedOutboundRequest(new OutboundRequest(request.context(), preview, preview.externalVersion()),
                FileExportMode.NEW_PROJECT, request.context().connectionId(), "a".repeat(64), "application/example", new byte[]{1});
        assertArrayEquals(new byte[]{7, 8}, connector.previewOutbound(based).content());
        assertEquals(0, catalog.activeLeases(plugin.identity()));
        catalog.beginDraining(plugin.identity()); assertTrue(catalog.awaitReleased(plugin.identity(), Duration.ZERO));
        catalog.remove(plugin.identity());
        assertEquals("INTEGRATION_PLUGIN_CHANGED", assertThrows(IntegrationProblem.class, connector::fileExportProfile).code());
        assertEquals("INTEGRATION_PLUGIN_CHANGED", assertThrows(IntegrationProblem.class, () -> connector.previewOutbound(based)).code());
    }

    private static PluginDescriptor plugin(String hash) {
        return new PluginDescriptor(new PluginIdentity("example.exchange", "1.0.0", hash.repeat(64)),
                ">=1.3.0 & <2.0.0", List.of(), Set.of("integration.read"), PluginMode.STARTUP);
    }
    private static InboundRequest request() {
        return new InboundRequest(new IntegrationContext(UUID.randomUUID(), AuthorityMode.IMPORT_COPY,
                new ExternalScope("EXAMPLE", "fixture", null), new InternalState("repo", "scope", "main", null, 0, null, null),
                "alice", "example-model", "1"), "application/example", new byte[]{1}, "snapshot", true);
    }
    private static ExchangeDocument document(String profile, String version, Map<String, String> metadata) {
        return new ExchangeDocument(profile, version, "snapshot", true, "", List.of(), List.of(), List.of(), metadata, List.of());
    }
    private static class Connector implements IntegrationConnectorExtension {
        private final String profile;
        private final String version;
        Connector(String profile, String version) { this.profile = profile; this.version = version; }
        @Override public IntegrationDescriptor descriptor() {
            return new IntegrationDescriptor(profile, version, "Example model", Set.of(Capability.FILE_IMPORT), Set.of("application/example"));
        }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { return document(profile, version, Map.of()); }
        @Override public void validateInboundSelection(OutboundRequest request) { }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { throw new UnsupportedOperationException(); }
    }
}
