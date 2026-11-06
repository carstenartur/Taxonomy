package com.taxonomy.interop;

import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.extension.api.plugin.ExtensionKey;
import com.taxonomy.extension.api.plugin.ExtensionUnavailableException;
import com.taxonomy.shared.extension.ExtensionKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One registry and capability contract for standards profiles and optional injected vendor adapters. */
@Component
public class ExchangeConnectorRegistry {
    private final Map<ProfileKey, LifecycleIntegrationConnector> connectors;
    private final ExtensionCatalog catalog;
    private record ProfileKey(String id, String version) {}
    public ExchangeConnectorRegistry(List<LifecycleIntegrationConnector> extensions) {
        this(extensions, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ExchangeConnectorRegistry(List<LifecycleIntegrationConnector> extensions, ExtensionCatalog catalog) {
        this.catalog = catalog;
        var values = new java.util.HashMap<ProfileKey, LifecycleIntegrationConnector>();
        for (LifecycleIntegrationConnector connector : List.of(new Reqif(), new ArchiMate())) values.put(new ProfileKey(connector.descriptor().id(), connector.descriptor().version()), connector);
        for (LifecycleIntegrationConnector extension : extensions) if (values.putIfAbsent(new ProfileKey(extension.descriptor().id(), extension.descriptor().version()), extension) != null)
            throw new IllegalStateException("Duplicate integration connector id and version");
        connectors = Map.copyOf(values);
        descriptors(); // Reject installed contributions that shadow a built-in profile at startup.
    }
    public List<IntegrationDescriptor> descriptors() {
        var values = new java.util.HashMap<ProfileKey, IntegrationDescriptor>();
        connectors.forEach((key, connector) -> values.put(key, connector.descriptor()));
        if (catalog != null) {
            var leases = catalog.acquireAll(ExtensionKind.INTEGRATION_CONNECTOR, IntegrationConnectorExtension.class);
            try {
                for (var lease : leases) {
                    var descriptor = lease.extension().descriptor();
                    if (values.putIfAbsent(new ProfileKey(descriptor.id(), descriptor.version()), descriptor) != null)
                        throw new IllegalStateException("Duplicate integration connector id and version");
                }
            } finally { leases.forEach(lease -> lease.close()); }
        }
        return values.values().stream().sorted(java.util.Comparator.comparing(IntegrationDescriptor::id).thenComparing(IntegrationDescriptor::version)).toList();
    }
    public LifecycleIntegrationConnector require(String id) {
        return require(id, "1");
    }
    public LifecycleIntegrationConnector require(String id, String version) {
        var connector = connectors.get(new ProfileKey(id, version));
        if (connector != null) return connector;
        if (catalog != null) {
            var key = new ExtensionKey(ExtensionKind.INTEGRATION_CONNECTOR, id + "@" + version);
            try (var lease = catalog.acquire(key, IntegrationConnectorExtension.class)) {
                var descriptor = lease.extension().descriptor();
                if (!descriptor.id().equals(id) || !descriptor.version().equals(version))
                    throw new IntegrationProblem("UNKNOWN_CONNECTOR", 400, "Choose an exact integration profile");
                return new PluginExchangeConnector(catalog, key, lease.plugin(), descriptor);
            } catch (ExtensionUnavailableException unavailable) {
                throw new IntegrationProblem("UNKNOWN_CONNECTOR", 400, "Choose an installed integration profile");
            }
        }
        throw new IntegrationProblem("UNKNOWN_CONNECTOR", 400, "Choose a supported integration profile");
    }
    /** Bind host-generated previews as well as parser results to the exact installed artifact. */
    public ExchangeDocument bind(ExchangeDocument document) {
        var connector = require(document.profile(), document.profileVersion());
        return connector instanceof PluginExchangeConnector external ? external.bind(document) : document;
    }
    /** A pending review cannot silently switch parser or mapping implementations after restart. */
    public void requireCompatible(ExchangeDocument document) {
        if (connectors.containsKey(new ProfileKey(document.profile(), document.profileVersion()))) {
            if (PluginExchangeConnector.hasBinding(document)) throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
            return;
        }
        try {
            var connector = require(document.profile(), document.profileVersion());
            if (!(connector instanceof PluginExchangeConnector external)) throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
            external.requireCompatible(document);
        } catch (IntegrationProblem unavailable) {
            throw IntegrationProblem.conflict("INTEGRATION_PLUGIN_CHANGED");
        }
    }
    private static final class Reqif implements LifecycleIntegrationConnector {
        private final ReqifExchangeCodec codec = new ReqifExchangeCodec();
        @Override public IntegrationDescriptor descriptor() { return new IntegrationDescriptor(ReqifExchangeCodec.PROFILE, ReqifExchangeCodec.VERSION, "ReqIF 1.2", Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT), Set.of("application/reqif+xml", "application/xml"),
                List.of(new PlanningSupport("go-live", "1", Set.of("precision", "value"), true, true, "REQIF_STRING_ENVELOPE", "TAXONOMY_PROFILE_ONLY", false),
                        new PlanningSupport("standard-reference", "1", Set.of("identifier", "edition", "section"), true, true, "REQIF_STRING_ENVELOPE", "TAXONOMY_PROFILE_ONLY", false))); }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { return codec.read(request.content(), request.externalVersion(), request.completeScope()); }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { return new ExchangeFile("application/reqif+xml", "requirements.reqif", codec.write(request.document()), com.taxonomy.exchange.PlanningEnvelope.report(request.document())); }
    }
    private static final class ArchiMate implements LifecycleIntegrationConnector {
        private final ArchiMateExchangeCodec codec = new ArchiMateExchangeCodec();
        @Override public IntegrationDescriptor descriptor() { return new IntegrationDescriptor(ArchiMateExchangeCodec.PROFILE, ArchiMateExchangeCodec.VERSION, "ArchiMate Exchange 3.1", Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT, Capability.ARCHITECTURE_MODEL), Set.of("application/archimate+xml", "application/xml")); }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { return codec.read(request.content(), request.externalVersion(), request.completeScope()); }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { return new ExchangeFile("application/archimate+xml", "architecture.xml", codec.write(request.document()), request.document().losses()); }
    }
}
