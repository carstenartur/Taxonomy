package com.taxonomy.interop;

import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import com.taxonomy.extension.api.plugin.*;

import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Host-owned lease and provenance boundary. No plugin callback runs outside a lease. */
final class PluginExchangeConnector implements LifecycleIntegrationConnector {
    private static final String ID = "taxonomy.plugin.id";
    private static final String VERSION = "taxonomy.plugin.version";
    private static final String SHA = "taxonomy.plugin.sha256";
    static final Set<String> BINDING_KEYS = Set.of(ID, VERSION, SHA);
    private final ExtensionCatalog catalog;
    private final ExtensionKey key;
    private final PluginIdentity plugin;
    private final IntegrationDescriptor descriptor;

    PluginExchangeConnector(ExtensionCatalog catalog, ExtensionKey key, PluginIdentity plugin, IntegrationDescriptor descriptor) {
        this.catalog = catalog; this.key = key; this.plugin = plugin; this.descriptor = descriptor;
    }

    @Override public IntegrationDescriptor descriptor() { return descriptor; }

    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        requireContext(request.context());
        return invoke(connector -> {
            ExchangeDocument result = connector.previewInbound(request);
            requireProfile(result);
            if (result.completeScope() && !request.completeScope()
                    || !Objects.equals(result.externalVersion(), request.externalVersion()))
                throw new IntegrationProblem("INVALID_CONNECTOR_DOCUMENT", 422, "Connector changed snapshot identity or completeness");
            return bind(result);
        });
    }

    @Override public void validateInboundSelection(OutboundRequest request) {
        requireContext(request.context());
        requireCompatible(request.document());
        invoke(connector -> { connector.validateInboundSelection(request); return null; });
    }

    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        requireContext(request.context());
        requireCompatible(request.document());
        return invoke(connector -> connector.previewOutbound(request));
    }

    @Override public DiscoveryResult discover(IntegrationContext context) {
        requireContext(context);
        return invoke(connector -> connector.discover(context));
    }

    ExchangeDocument bind(ExchangeDocument document) {
        requireProfile(document);
        var metadata = new TreeMap<>(document.metadata());
        metadata.put(ID, plugin.id()); metadata.put(VERSION, plugin.version()); metadata.put(SHA, plugin.artifactSha256());
        return new ExchangeDocument(document.profile(), document.profileVersion(), document.externalVersion(),
                document.completeScope(), document.source(), document.artifacts(), document.relations(), document.placements(), metadata, document.losses());
    }

    static boolean hasBinding(ExchangeDocument document) {
        return document.metadata().containsKey(ID) || document.metadata().containsKey(VERSION) || document.metadata().containsKey(SHA);
    }

    void requireCompatible(ExchangeDocument document) {
        requireProfile(document);
        if (!plugin.id().equals(document.metadata().get(ID)) || !plugin.version().equals(document.metadata().get(VERSION))
                || !plugin.artifactSha256().equals(document.metadata().get(SHA)))
            throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
    }

    private void requireProfile(ExchangeDocument document) {
        if (document == null || !descriptor.id().equals(document.profile()) || !descriptor.version().equals(document.profileVersion()))
            throw new IntegrationProblem("INVALID_CONNECTOR_DOCUMENT", 422, "Connector returned a different profile or version");
    }

    private void requireContext(IntegrationContext context) {
        if (!descriptor.id().equals(context.profile()) || !descriptor.version().equals(context.profileVersion()))
            throw new IntegrationProblem("INVALID_CONNECTOR_CONTEXT", 422, "Connector requires the exact reviewed profile");
    }

    private <T> T invoke(Function<IntegrationConnectorExtension, T> action) {
        try (var lease = catalog.acquire(key, IntegrationConnectorExtension.class)) {
            if (!plugin.equals(lease.plugin()) || !descriptor.equals(lease.extension().descriptor()))
                throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
            return action.apply(lease.extension());
        } catch (ExtensionUnavailableException unavailable) {
            throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
        }
    }
}
