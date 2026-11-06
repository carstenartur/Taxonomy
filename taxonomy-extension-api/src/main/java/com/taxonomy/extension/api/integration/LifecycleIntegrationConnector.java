package com.taxonomy.extension.api.integration;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;

/**
 * Standards/vendor boundary. The application owns authorization, reviewed apply, durable operations,
 * mappings and checkpoints. Connectors cannot replace canonical models or infer an authority mode.
 * File delivery is not acknowledgement by an external server. Capabilities must describe actual behavior.
 */
public interface LifecycleIntegrationConnector {
    IntegrationDescriptor descriptor();
    ExchangeDocument previewInbound(InboundRequest request);
    ExchangeFile previewOutbound(OutboundRequest request);

    /** Additive policy for BASED_FILE_EXPORT_V1; legacy connectors need not implement this callback. */
    default FileExportProfile fileExportProfile() {
        throw new UnsupportedOperationException("Based file export is not supported by this connector");
    }
    /** Serialize only the frozen reviewed document into a copy of the supplied basis. */
    default ExchangeFile previewOutbound(BasedOutboundRequest request) {
        throw new UnsupportedOperationException("Based file export is not supported by this connector");
    }

    /** Validate a reviewed inbound selection before any canonical changes. Read-only profiles
     * override this boundary without pretending to support file or remote publication. */
    default void validateInboundSelection(OutboundRequest selection) { previewOutbound(selection); }

    default DiscoveryResult discover(IntegrationContext context) {
        throw new UnsupportedOperationException("Discovery is not supported by this connector");
    }
    /** Legacy weak hook: cannot prove atomicity, durable receipts or recovery. The conditional
     * publication engine must use ConditionalPublicationConnector instead. */
    @Deprecated(since = "1.4", forRemoval = false)
    default PublishResult publish(ReviewedChangeSet review, OutboundRequest request) {
        throw new UnsupportedOperationException("Conditional remote publication is not supported by this connector");
    }
}
