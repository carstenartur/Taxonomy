package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.sparx.mapping.SparxSemanticTypes;
import java.util.Set;

/** Versioned native-file output. A supplied basis is mandatory; this never publishes to EA or PCS. */
public final class SparxQeaWriterConnector implements IntegrationConnectorExtension {
    private static final long serialVersionUID = 1L;
    static final String VERSION = "2";
    static final String MEDIA_TYPE = "application/vnd.sparx.qea";
    static final Set<String> MEDIA_TYPES = Set.of(MEDIA_TYPE, "application/vnd.sqlite3", "application/x-sqlite3", "application/octet-stream");
    private static final IntegrationDescriptor DESCRIPTOR = new IntegrationDescriptor(SparxQeaConnector.PROFILE, VERSION,
            "Sparx QEA files (experimental, reviewed copy writing)", Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT,
            Capability.BASED_FILE_EXPORT_V1, Capability.ARCHITECTURE_MODEL, Capability.CANONICAL_PROJECTION_V1), MEDIA_TYPES);
    private static final FileExportProfile EXPORT_PROFILE = new FileExportProfile(Set.of(FileExportMode.NEW_PROJECT, FileExportMode.UPDATE_COPY),
            ExternalIdentityFormat.UUID_BRACED_UPPER, SparxSemanticTypes.elementTypes(), SparxSemanticTypes.relationTypes(), "Requirement", "Package", "Diagram");

    @Override public IntegrationDescriptor descriptor() { return DESCRIPTOR; }
    @Override public String description() { return "Bounded QEA reads and reviewed native-file copies with explicit Taxonomy semantics; no remote publication"; }
    @Override public FileExportProfile fileExportProfile() { return EXPORT_PROFILE; }
    @Override public ExchangeDocument previewInbound(InboundRequest request) {
        if (request == null || request.context() == null || !SparxQeaConnector.PROFILE.equals(request.context().profile())
                || !VERSION.equals(request.context().profileVersion())) throw QeaFailure.fail("QEA_PROFILE", "Select sparx-qea profile version 2");
        if (!MEDIA_TYPES.contains(request.mediaType())) throw QeaFailure.fail("QEA_MEDIA_TYPE", "Upload a supported SQLite QEA file");
        return new QeaReader(VERSION).read(request.content(), request.externalVersion());
    }
    @Override public void validateInboundSelection(OutboundRequest selection) { QeaSelectionValidator.validate(selection, VERSION); }
    @Override public ExchangeFile previewOutbound(OutboundRequest request) {
        throw new UnsupportedOperationException("QEA writing requires an exact binary basis and an explicit NEW_PROJECT or UPDATE_COPY mode");
    }
    @Override public ExchangeFile previewOutbound(BasedOutboundRequest request) { return new QeaWriter().write(request); }
}
