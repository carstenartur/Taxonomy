package com.taxonomy.interop.publication;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Journal-only immutable evidence. An observation checkpoint cannot be passed as a common baseline. */
public final class PublicationEvidence {
    private PublicationEvidence() {}
    public record CommonBaseline(int schemaVersion, ProviderIdentity provider, PublicationScope scope, String profile, String profileVersion,
            ExchangeDocument localDocument, ExchangeDocument remoteDocument, String semanticFingerprint) {
        public CommonBaseline { schema(schemaVersion); Objects.requireNonNull(provider); Objects.requireNonNull(scope); text(profile); text(profileVersion); document(localDocument); document(remoteDocument); text(semanticFingerprint);
            if (!profile.equals(localDocument.profile()) || !profile.equals(remoteDocument.profile()) || !profileVersion.equals(localDocument.profileVersion()) || !profileVersion.equals(remoteDocument.profileVersion())) throw new IllegalArgumentException("Common profile mismatch");
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, provider, scope, profile, profileVersion, localDocument, remoteDocument, semanticFingerprint); }
    }
    public record StagedBinding(String externalId, String businessIdentity, Long requirementId, Artifact externalArtifact, Artifact internalArtifact, boolean removed) {
        public StagedBinding { resource(externalId); if (businessIdentity != null) text(businessIdentity); bounded(MAX_ITEM_BYTES, externalId, businessIdentity, requirementId, externalArtifact, internalArtifact, removed); }
    }
    public record PublicationPreviewEnvelope(int schemaVersion, PublicationPreviewRequest request, IntegrationContext context, long connectionRevision,
            UUID commonCheckpointId, CommonBaseline commonBaseline, ExchangeDocument localDocument, ScopeSnapshot remoteSnapshot,
            List<PublicationChange> changes, List<MappingLoss> losses, String previewFingerprint) {
        public PublicationPreviewEnvelope { schema(schemaVersion); Objects.requireNonNull(request); Objects.requireNonNull(context); if (connectionRevision < 0 || (commonCheckpointId == null) != (commonBaseline == null)) throw new IllegalArgumentException("Invalid preview baseline");
            document(localDocument); Objects.requireNonNull(remoteSnapshot); changes = list(changes, MAX_SCOPE_ITEMS); losses = list(losses, MAX_SCOPE_ITEMS); text(previewFingerprint);
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, request, context, connectionRevision, commonCheckpointId, commonBaseline, localDocument, remoteSnapshot, changes, losses, previewFingerprint); }
        public PublicationPreview publicPreview() { return new PublicationPreview(request.operationId(), request.mode(), previewFingerprint, changes, losses); }
    }
    public record PublicationClaim(UUID operationId, String itemId, UUID attemptId, long leaseEpoch, AttemptKind attemptKind, PublicationItemRequest frozenRequest) {
        public PublicationClaim { Objects.requireNonNull(operationId); text(itemId); Objects.requireNonNull(attemptId); if (leaseEpoch < 1) throw new IllegalArgumentException("Invalid claim epoch"); Objects.requireNonNull(attemptKind); Objects.requireNonNull(frozenRequest);
            if (!operationId.equals(frozenRequest.operationId()) || !itemId.equals(frozenRequest.item().itemId())) throw new IllegalArgumentException("Claim identity mismatch"); }
    }
}
