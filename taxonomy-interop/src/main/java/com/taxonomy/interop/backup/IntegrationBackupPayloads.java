package com.taxonomy.interop.backup;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import com.taxonomy.backup.SourceRecordId;
import java.util.*;

/** History-free saved-work DTOs deliberately have no original/before/baseline fields. */
final class IntegrationBackupPayloads {
    private IntegrationBackupPayloads() { }
    static Record document(ExchangeDocument value, boolean history) {
        if (value == null || history) return value;
        return new CurrentDocument(value.profile(), value.profileVersion(), value.externalVersion(), value.completeScope(),
                value.artifacts(), value.relations(), value.placements(), value.metadata(), losses(value.losses()));
    }
    static Record change(IntegrationChange value, boolean history) {
        if (history) return value;
        return new CurrentChange(value.id(), value.externalId(), value.kind(), value.fields(), value.expectedInternalFingerprint(),
                value.expectedExternalFingerprint(), value.after(), value.conflicts());
    }
    static Record preview(PublicationPreviewEnvelope value, boolean history) {
        if (value == null || history) return value;
        var changes = value.changes().stream().map(c -> new CurrentPublicationChange(c.id(), c.externalId(), c.local(), c.remote(),
                c.merged(), c.localFields(), c.remoteFields(), c.conflicts(), c.dependencies())).toList();
        return new CurrentPreview(value.request(), value.context(), value.connectionRevision(), value.commonCheckpointId(),
                changes, losses(value.losses()), value.previewFingerprint());
    }
    static Record plan(PublicationPlan value, boolean history) {
        if (value == null || history) return value;
        return new CurrentPlan(value.operationId(), value.context(), value.mode(), value.commonCheckpointId(), value.connectionRevision(),
                value.requestFingerprint(), value.reviewFingerprint(), value.capabilities(), document(value.localTarget(), false),
                document(value.remoteTarget(), false), value.items(), losses(value.losses()), value.planFingerprint());
    }
    static Record binding(StagedBinding value, boolean history) {
        boolean payload = history || !value.removed();
        return new Binding(value.externalId(), value.businessIdentity(), value.requirementId() == null ? null
                : new SourceRecordId("portfolio.requirement", value.requirementId().toString()),
                payload ? value.externalArtifact() : null, payload ? value.internalArtifact() : null, value.removed());
    }
    private static List<CurrentLoss> losses(List<MappingLoss> values) {
        // Free-text diagnostics can quote older transport input. Current exports retain classification only.
        return values.stream().map(v -> new CurrentLoss(v.artifactId(), v.field(), v.code(), v.disposition())).toList();
    }
    public record CurrentLoss(String artifactId, String field, String code, LossDisposition disposition) { }
    public record CurrentDocument(String profile, String profileVersion, String sourceExternalVersion, boolean completeScope,
                                  List<Artifact> artifacts, List<Relation> relations, List<Placement> placements,
                                  Map<String, String> metadata, List<CurrentLoss> losses) { }
    public record CurrentChange(String id, String externalId, ChangeKind kind, Set<String> fields,
                                String sourceExpectedInternalFingerprint, String sourceExpectedExternalFingerprint,
                                Artifact after, List<String> conflicts) { }
    public record CurrentPublicationChange(String id, String externalId, Artifact localCandidate, Artifact remoteCandidate,
                                           Artifact mergedCandidate, Set<String> localFields, Set<String> remoteFields,
                                           List<String> conflicts, Set<String> dependencies) { }
    public record CurrentPreview(PublicationPreviewRequest sourceRequest, IntegrationContext sourceContext,
                                 long sourceConnectionRevision, UUID sourceCommonCheckpointId,
                                 List<CurrentPublicationChange> changes, List<CurrentLoss> losses, String sourcePreviewFingerprint) { }
    public record CurrentPlan(UUID sourceOperationId, IntegrationContext sourceContext, PublicationMode mode, UUID sourceCommonCheckpointId,
                              long sourceConnectionRevision, String sourceRequestFingerprint, String sourceReviewFingerprint,
                              PublicationCapabilities sourceCapabilities, Record localTarget, Record remoteTarget,
                              List<PublicationItemIntent> items, List<CurrentLoss> losses, String sourcePlanFingerprint) { }
    public record Binding(String externalId, String businessIdentity, SourceRecordId requirement, Artifact externalArtifact,
                          Artifact internalArtifact, boolean removed) { }
}
