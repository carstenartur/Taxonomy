package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.springframework.stereotype.Component;

@Component
public final class SparxXmiConnector implements LifecycleIntegrationConnector {
    private final SparxXmiCodec codec = new SparxXmiCodec();
    @Override public IntegrationDescriptor descriptor() { return SparxIntegrationDescriptor.xmi(); }
    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        return SparxSnapshots.identify(codec.read(request.content(), request.externalVersion(), request.completeScope()), request.context().connectionId());
    }
    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        var document = SparxSnapshots.identify(request.document(), request.context().connectionId());
        return new ExchangeFile("application/xmi+xml", "taxonomy-sparx.xmi", codec.write(document), document.losses());
    }
}
