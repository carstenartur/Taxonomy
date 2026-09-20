package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.sparx.SparxOslcAmCodec;
import com.taxonomy.exchange.sparx.SparxModelValidator;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.springframework.stereotype.Component;
import java.util.Set;

/** PCS read/pull profile; remote retrieval is orchestrated by the durable integration service. */
@Component
public final class SparxOslcAmConnector implements LifecycleIntegrationConnector {
    @Override public IntegrationDescriptor descriptor() {
        return new IntegrationDescriptor(SparxOslcAmCodec.PROFILE, SparxOslcAmCodec.VERSION,
                "Sparx PCS OSLC AM 2.0 read/pull (experimental)",
                Set.of(Capability.DISCOVERY, Capability.READ_LINK, Capability.ARCHITECTURE_MODEL),
                Set.of("application/rdf+xml"));
    }
    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        throw new UnsupportedOperationException("Use bounded remote AM collection retrieval");
    }
    @Override public void validateInboundSelection(OutboundRequest selection) {
        SparxModelValidator.validate(selection.document());
    }
    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        throw new UnsupportedOperationException("PCS AM profile has no publication capability");
    }
}
