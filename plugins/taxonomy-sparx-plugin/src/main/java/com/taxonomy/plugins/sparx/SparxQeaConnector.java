package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.util.Set;

/** Read-only QEA boundary. The host owns authorization, reviewed mappings and canonical apply. */
public final class SparxQeaConnector implements IntegrationConnectorExtension {
    private static final long serialVersionUID = 1L;
    static final String PROFILE = "sparx-qea";
    static final String VERSION = "1";
    private static final IntegrationDescriptor DESCRIPTOR = new IntegrationDescriptor(PROFILE, VERSION,
            "Sparx QEA snapshot (experimental, read-only)",
            Set.of(Capability.FILE_IMPORT, Capability.ARCHITECTURE_MODEL, Capability.CANONICAL_PROJECTION_V1),
            Set.of("application/vnd.sparx.qea", "application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream"));

    @Override public IntegrationDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String description() { return "Bounded SQLite QEA preview with GUID identities and explicit mapping losses; no publication"; }
    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        if (request == null || request.context() == null || !PROFILE.equals(request.context().profile()) || !VERSION.equals(request.context().profileVersion()))
            throw QeaFailure.fail("QEA_PROFILE", "Select sparx-qea profile version 1");
        if (!DESCRIPTOR.mediaTypes().contains(request.mediaType())) throw QeaFailure.fail("QEA_MEDIA_TYPE", "Upload a SQLite QEA file with a supported binary media type");
        return new QeaReader().read(request.content(), request.externalVersion());
    }
    @Override public void validateInboundSelection(OutboundRequest selection) { QeaSelectionValidator.validate(selection); }
    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        throw new UnsupportedOperationException("The QEA snapshot profile has no writer or publication capability");
    }
}
