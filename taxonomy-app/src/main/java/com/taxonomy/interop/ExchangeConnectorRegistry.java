package com.taxonomy.interop;

import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One registry and capability contract for standards profiles and optional injected vendor adapters. */
@Component
public class ExchangeConnectorRegistry {
    private final Map<String, LifecycleIntegrationConnector> connectors;
    public ExchangeConnectorRegistry(List<LifecycleIntegrationConnector> extensions) {
        var values = new java.util.TreeMap<String, LifecycleIntegrationConnector>();
        for (LifecycleIntegrationConnector connector : List.of(new Reqif(), new ArchiMate())) values.put(connector.descriptor().id(), connector);
        for (LifecycleIntegrationConnector extension : extensions) if (values.putIfAbsent(extension.descriptor().id(), extension) != null)
            throw new IllegalStateException("Duplicate integration connector id");
        connectors = Map.copyOf(values);
    }
    public List<IntegrationDescriptor> descriptors() { return connectors.values().stream().map(LifecycleIntegrationConnector::descriptor).sorted(java.util.Comparator.comparing(IntegrationDescriptor::id)).toList(); }
    public LifecycleIntegrationConnector require(String id) {
        var connector = connectors.get(id); if (connector == null) throw new IntegrationProblem("UNKNOWN_CONNECTOR", 400, "Choose a supported integration profile"); return connector;
    }
    private static final class Reqif implements LifecycleIntegrationConnector {
        private final ReqifExchangeCodec codec = new ReqifExchangeCodec();
        @Override public IntegrationDescriptor descriptor() { return new IntegrationDescriptor(ReqifExchangeCodec.PROFILE, ReqifExchangeCodec.VERSION, "ReqIF 1.2", Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT), Set.of("application/reqif+xml", "application/xml")); }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { return codec.read(request.content(), request.externalVersion(), request.completeScope()); }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { return new ExchangeFile("application/reqif+xml", "requirements.reqif", codec.write(request.document()), request.document().losses()); }
    }
    private static final class ArchiMate implements LifecycleIntegrationConnector {
        private final ArchiMateExchangeCodec codec = new ArchiMateExchangeCodec();
        @Override public IntegrationDescriptor descriptor() { return new IntegrationDescriptor(ArchiMateExchangeCodec.PROFILE, ArchiMateExchangeCodec.VERSION, "ArchiMate Exchange 3.1", Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT), Set.of("application/archimate+xml", "application/xml")); }
        @Override public ExchangeDocument previewInbound(InboundRequest request) { return codec.read(request.content(), request.externalVersion(), request.completeScope()); }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { return new ExchangeFile("application/archimate+xml", "architecture.xml", codec.write(request.document()), request.document().losses()); }
    }
}
