package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.springframework.stereotype.Component;

@Component
public class SparxXmiConnector implements LifecycleIntegrationConnector {
    private final SparxXmiCodec codec;
    private final String version;
    public SparxXmiConnector() { this("1"); }
    protected SparxXmiConnector(String version) { this.version = version; this.codec = new SparxXmiCodec(version); }
    @Override public IntegrationDescriptor descriptor() { return SparxIntegrationDescriptor.xmi(version); }
    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        return SparxSnapshots.identify(codec.read(request.content(), request.externalVersion(), request.completeScope()), request.context().connectionId());
    }
    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        var document = SparxSnapshots.identify(request.document(), request.context().connectionId());
        return new ExchangeFile("application/xmi+xml", "taxonomy-sparx.xmi", codec.write(document), document.losses());
    }
}
