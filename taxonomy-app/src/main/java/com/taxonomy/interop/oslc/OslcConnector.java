package com.taxonomy.interop.oslc;

import com.taxonomy.exchange.OslcRequirementsCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;

@Component
public class OslcConnector implements LifecycleIntegrationConnector {
    private final OslcRequirementsCodec codec = new OslcRequirementsCodec();
    @Override public IntegrationDescriptor descriptor() { return new IntegrationDescriptor(OslcRequirementsCodec.PROFILE, "1", "OSLC RM 2.1 / Core 3.0", Set.of(Capability.DISCOVERY, Capability.READ_LINK, Capability.FILE_EXPORT), Set.of("application/rdf+xml")); }
    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        return codec.read(request.content(), URI.create(request.context().externalScope().repository()), request.externalVersion(), request.context().externalScope().configuration());
    }
    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        return new ExchangeFile("application/rdf+xml", "requirements.rdf", codec.write(request.document()), request.document().losses());
    }
}
