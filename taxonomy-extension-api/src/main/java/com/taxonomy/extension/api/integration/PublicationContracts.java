package com.taxonomy.extension.api.integration;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import java.time.Instant;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Immutable, bounded conditional publication wire contract. No credentials or raw responses belong here. */
public final class PublicationContracts {
    private PublicationContracts() {}
    public enum PublicationMode { PUSH, SYNCHRONIZE }
    public enum MutationKind { CREATE, UPDATE, DELETE }
    public enum ItemState { READY, IN_FLIGHT, UNKNOWN, ACKNOWLEDGED, REJECTED_STALE, REJECTED_PERMANENT, RETRYABLE_NO_EFFECT, BLOCKED }
    public enum ReceiptState { APPLIED, REJECTED_STALE, REJECTED_PERMANENT, RETRYABLE_NO_EFFECT }
    public enum LookupState { FOUND, IN_PROGRESS, NOT_FOUND, UNAVAILABLE, EXPIRED }
    public enum Guarantee { ATOMIC_SCOPE_COMPARE_AND_MUTATE, ATOMIC_RESOURCE_ABSENCE, DURABLE_IDEMPOTENCY, DURABLE_RECEIPT_LOOKUP, COMPLETE_VERSIONED_SCOPE_READ }
    public enum PublicationResolution { MERGE, KEEP_LOCAL, TAKE_REMOTE, SKIP }
    public enum PublicationPhase { PREVIEWED, LOCAL_APPLY_PENDING, LOCAL_CHECKPOINT_PENDING, PUBLISH_PENDING, RECOVERY_REQUIRED, VERIFY_PENDING, COMPLETED, PARTIAL, RECONCILIATION_REQUIRED, CANCELLED }
    public enum PublicationAction { REVIEW, PUBLISH, RETRY, RECONCILE, CANCEL }
    public enum AttemptKind { SEND, LOOKUP }

    public record PublicationScope(ExternalScope externalScope, String rootResource, String selectorFingerprint) {
        public PublicationScope { Objects.requireNonNull(externalScope); resource(rootResource); text(selectorFingerprint); resource(externalScope.repository()); if (externalScope.configuration() != null) resource(externalScope.configuration()); }
    }
    public record ProviderIdentity(String providerId, String repositoryId, String configurationId) {
        public ProviderIdentity { resource(providerId); resource(repositoryId); resource(configurationId); }
    }
    public record PublicationCapabilities(int schemaVersion, String contractVersion, ProviderIdentity provider, PublicationScope scope,
            Set<Guarantee> guarantees, Set<MutationKind> mutations, Set<ArtifactKind> artifactKinds, String verificationReference,
            String capabilityFingerprint, int maxItems, int maxRequestBytes, long receiptRetentionSeconds) {
        public PublicationCapabilities { schema(schemaVersion); text(contractVersion); Objects.requireNonNull(provider); Objects.requireNonNull(scope);
            guarantees = set(guarantees, Guarantee.values().length); mutations = set(mutations, MutationKind.values().length); artifactKinds = set(artifactKinds, ArtifactKind.values().length);
            text(verificationReference); text(capabilityFingerprint); if (maxItems < 1 || maxItems > MAX_MUTATIONS || maxRequestBytes < 1 || maxRequestBytes > MAX_ITEM_BYTES || receiptRetentionSeconds < 1) throw new IllegalArgumentException("Invalid publication limits"); }
    }
    public record ScopeSnapshot(int schemaVersion, ProviderIdentity provider, PublicationScope scope, String revision, String semanticFingerprint,
            boolean complete, ExchangeDocument document, Map<String, ResourceState> resources) {
        public ScopeSnapshot { schema(schemaVersion); Objects.requireNonNull(provider); Objects.requireNonNull(scope); token(revision); text(semanticFingerprint);
            PublicationBounds.document(document); resources = map(resources, MAX_SCOPE_ITEMS); resources.forEach((id, state) -> { resource(id); if (!id.equals(state.resourceId())) throw new IllegalArgumentException("Resource identity mismatch"); });
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, provider, scope, revision, semanticFingerprint, complete, document, resources); }
    }
    public record ResourceState(String resourceId, boolean exists, String version, String semanticFingerprint) {
        public ResourceState { resource(resourceId); if (exists) { token(version); text(semanticFingerprint); } else if (version != null || semanticFingerprint != null) throw new IllegalArgumentException("Absent resource has no version or semantics"); }
    }
    public record PublicationPreviewRequest(UUID operationId, InternalState expected, PublicationMode mode, PublicationScope scope, String expectedExternalRevision) {
        public PublicationPreviewRequest { Objects.requireNonNull(operationId); Objects.requireNonNull(expected); Objects.requireNonNull(mode); Objects.requireNonNull(scope); token(expectedExternalRevision); bounded(MAX_DOCUMENT_BYTES, expected); }
    }
    public record PublicationItemIntent(String itemId, String idempotencyKey, MutationKind mutation, String resourceId, ResourceState expectedResource,
            Artifact target, Set<String> dependencies, String intentFingerprint) {
        public PublicationItemIntent { text(itemId); text(idempotencyKey); Objects.requireNonNull(mutation); resource(resourceId); Objects.requireNonNull(expectedResource); text(intentFingerprint);
            if (!resourceId.equals(expectedResource.resourceId()) || (mutation == MutationKind.DELETE) != (target == null)
                    || (target != null && !resourceId.equals(target.id())) || (mutation == MutationKind.CREATE) == expectedResource.exists()) throw new IllegalArgumentException("Inconsistent publication intent");
            dependencies = set(dependencies, MAX_MUTATIONS); dependencies.forEach(PublicationBounds::text);
            bounded(MAX_ITEM_BYTES, itemId, idempotencyKey, mutation, resourceId, expectedResource, target, dependencies, intentFingerprint); }
    }
    public record PublicationPlan(int schemaVersion, UUID operationId, IntegrationContext context, PublicationMode mode, UUID commonCheckpointId,
            long connectionRevision, String requestFingerprint, String reviewFingerprint, PublicationCapabilities capabilities, ScopeSnapshot remoteBefore,
            ExchangeDocument localBefore, ExchangeDocument localTarget, ExchangeDocument remoteTarget, List<PublicationItemIntent> items,
            List<MappingLoss> losses, String planFingerprint) {
        public PublicationPlan { schema(schemaVersion); Objects.requireNonNull(operationId); Objects.requireNonNull(context); Objects.requireNonNull(mode); if (connectionRevision < 0) throw new IllegalArgumentException("Invalid connection revision");
            text(requestFingerprint); text(reviewFingerprint); Objects.requireNonNull(capabilities); Objects.requireNonNull(remoteBefore); document(localBefore); document(localTarget); document(remoteTarget);
            items = list(items, Math.min(MAX_MUTATIONS, capabilities.maxItems())); losses = list(losses, MAX_SCOPE_ITEMS); text(planFingerprint);
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, operationId, context, mode, commonCheckpointId, connectionRevision, requestFingerprint, reviewFingerprint, capabilities, remoteBefore, localBefore, localTarget, remoteTarget, items, losses, planFingerprint); }
    }
    public record PublicationItemRequest(int schemaVersion, UUID operationId, String planFingerprint, ProviderIdentity provider, PublicationScope scope,
            PublicationItemIntent item, String expectedScopeRevision, String requestFingerprint) {
        public PublicationItemRequest { schema(schemaVersion); Objects.requireNonNull(operationId); text(planFingerprint); Objects.requireNonNull(provider); Objects.requireNonNull(scope); Objects.requireNonNull(item); token(expectedScopeRevision); text(requestFingerprint);
            bounded(MAX_ITEM_BYTES, schemaVersion, operationId, planFingerprint, provider, scope, item, expectedScopeRevision, requestFingerprint); }
    }
    public record PublicationReceipt(int schemaVersion, String contractVersion, ProviderIdentity provider, PublicationScope scope, UUID operationId,
            String itemId, String idempotencyKey, String planFingerprint, String requestFingerprint, ReceiptState state, long receiptSequence, boolean terminal,
            String beforeScopeRevision, String afterScopeRevision, ResourceState resultingResource, String failureCode, Instant recordedAt) {
        public PublicationReceipt { schema(schemaVersion); text(contractVersion); Objects.requireNonNull(provider); Objects.requireNonNull(scope); Objects.requireNonNull(operationId); text(itemId); text(idempotencyKey); text(planFingerprint); text(requestFingerprint); Objects.requireNonNull(state);
            if (receiptSequence < 1) throw new IllegalArgumentException("Invalid receipt sequence"); token(beforeScopeRevision); token(afterScopeRevision); Objects.requireNonNull(resultingResource); safeCode(failureCode); Objects.requireNonNull(recordedAt);
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, contractVersion, provider, scope, operationId, itemId, idempotencyKey, planFingerprint, requestFingerprint, state, receiptSequence, terminal, beforeScopeRevision, afterScopeRevision, resultingResource, failureCode, recordedAt); }
    }
    public record PublicationReceiptQuery(ProviderIdentity provider, PublicationScope scope, UUID operationId, String itemId, String idempotencyKey, String requestFingerprint) {
        public PublicationReceiptQuery { Objects.requireNonNull(provider); Objects.requireNonNull(scope); Objects.requireNonNull(operationId); text(itemId); text(idempotencyKey); text(requestFingerprint); }
    }
    public record PublicationReceiptLookup(LookupState state, PublicationReceipt receipt) {
        public PublicationReceiptLookup { Objects.requireNonNull(state); if ((state == LookupState.FOUND) != (receipt != null)) throw new IllegalArgumentException("Invalid receipt lookup"); }
    }
    public record PublicationCompletion(int schemaVersion, UUID operationId, String planFingerprint, List<PublicationReceipt> receipts,
            ScopeSnapshot remoteAfter, InternalState localAfter, String commonSemanticFingerprint) {
        public PublicationCompletion { schema(schemaVersion); Objects.requireNonNull(operationId); text(planFingerprint); receipts = list(receipts, MAX_MUTATIONS); Objects.requireNonNull(remoteAfter); Objects.requireNonNull(localAfter); text(commonSemanticFingerprint);
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, operationId, planFingerprint, receipts, remoteAfter, localAfter, commonSemanticFingerprint); }
    }
    public record PublicationChange(String id, String externalId, Artifact baseLocal, Artifact baseRemote, Artifact local, Artifact remote, Artifact merged,
            Set<String> localFields, Set<String> remoteFields, List<String> conflicts, Set<String> dependencies) {
        public PublicationChange { text(id); resource(externalId); localFields = set(localFields, MAX_SCOPE_ITEMS); remoteFields = set(remoteFields, MAX_SCOPE_ITEMS); conflicts = list(conflicts, MAX_SCOPE_ITEMS); dependencies = set(dependencies, MAX_SCOPE_ITEMS);
            bounded(MAX_DOCUMENT_BYTES, id, externalId, baseLocal, baseRemote, local, remote, merged, localFields, remoteFields, conflicts, dependencies); }
    }
    public record PublicationReview(ReviewedChangeSet review, Map<String, PublicationResolution> resolutions) {
        public PublicationReview { Objects.requireNonNull(review); resolutions = map(resolutions, MAX_SCOPE_ITEMS); bounded(MAX_DOCUMENT_BYTES, review, resolutions); }
    }
    public record ReconciliationPreviewRequest(UUID predecessorOperationId, PublicationPreviewRequest request, String rationale) {
        public ReconciliationPreviewRequest { Objects.requireNonNull(predecessorOperationId); Objects.requireNonNull(request); text(rationale); if (predecessorOperationId.equals(request.operationId())) throw new IllegalArgumentException("Successor must have a new identity"); }
    }
    public record PublicationAvailability(boolean available, Set<PublicationMode> modes, Set<MutationKind> mutations, String reasonCode) {
        public PublicationAvailability { modes = set(modes, PublicationMode.values().length); mutations = set(mutations, MutationKind.values().length); safeCode(reasonCode); }
    }
    /** Public preview deliberately omits internal baseline, lease and frozen dispatch request. */
    public record PublicationPreview(UUID operationId, PublicationMode mode, String fingerprint, List<PublicationChange> changes, List<MappingLoss> losses) {
        public PublicationPreview { Objects.requireNonNull(operationId); Objects.requireNonNull(mode); text(fingerprint); changes = list(changes, MAX_SCOPE_ITEMS); losses = list(losses, MAX_SCOPE_ITEMS); bounded(MAX_DOCUMENT_BYTES, operationId, mode, fingerprint, changes, losses); }
    }
    public record PublicationItemOutcome(String itemId, String resourceId, MutationKind mutation, ItemState state, int attempts, String failureCode, PublicationReceipt receipt) {
        public PublicationItemOutcome { text(itemId); resource(resourceId); Objects.requireNonNull(mutation); Objects.requireNonNull(state); if (attempts < 0 || attempts > MAX_ATTEMPTS) throw new IllegalArgumentException("Invalid attempt count"); safeCode(failureCode); }
    }
    public record PublicationOperation(int schemaVersion, UUID operationId, UUID connectionId, UUID predecessorOperationId, PublicationMode mode,
            PublicationPhase phase, OperationStatus status, String planFingerprint, String reviewFingerprint, PublicationPreview preview,
            List<PublicationItemOutcome> items, PublicationCompletion completion, UUID observationCheckpointId, UUID commonCheckpointId,
            InternalState localCheckpoint, int acknowledgedCount, int remainingCount, int unknownCount, Set<PublicationAction> allowedActions, String failureCode, PublicationReview review, PublicationScope scope, String expectedExternalRevision, String requestFingerprint) {
        /** Schema 1 additive read projection; old constructors retain their original meaning. */
        public PublicationOperation(int schemaVersion, UUID operationId, UUID connectionId, UUID predecessorOperationId, PublicationMode mode, PublicationPhase phase, OperationStatus status, String planFingerprint, String reviewFingerprint, PublicationPreview preview, List<PublicationItemOutcome> items, PublicationCompletion completion, UUID observationCheckpointId, UUID commonCheckpointId, InternalState localCheckpoint, int acknowledgedCount, int remainingCount, int unknownCount, Set<PublicationAction> allowedActions, String failureCode) {
            this(schemaVersion, operationId, connectionId, predecessorOperationId, mode, phase, status, planFingerprint, reviewFingerprint, preview, items, completion, observationCheckpointId, commonCheckpointId, localCheckpoint, acknowledgedCount, remainingCount, unknownCount, allowedActions, failureCode, null, null, null, null);
        }
        public PublicationOperation { schema(schemaVersion); Objects.requireNonNull(operationId); Objects.requireNonNull(connectionId); Objects.requireNonNull(mode); Objects.requireNonNull(phase); Objects.requireNonNull(status);
            if (expectedExternalRevision != null) token(expectedExternalRevision); if (requestFingerprint != null) text(requestFingerprint);
            if (planFingerprint != null) text(planFingerprint); if (reviewFingerprint != null) text(reviewFingerprint); items = list(items, MAX_MUTATIONS); allowedActions = set(allowedActions, PublicationAction.values().length); safeCode(failureCode);
            if (acknowledgedCount < 0 || remainingCount < 0 || unknownCount < 0 || acknowledgedCount + remainingCount != items.size() || unknownCount > remainingCount) throw new IllegalArgumentException("Invalid publication counts");
            bounded(MAX_DOCUMENT_BYTES, schemaVersion, operationId, connectionId, predecessorOperationId, mode, phase, status, planFingerprint, reviewFingerprint, preview, items, completion, observationCheckpointId, commonCheckpointId, localCheckpoint, acknowledgedCount, remainingCount, unknownCount, allowedActions, failureCode, review, scope, expectedExternalRevision, requestFingerprint); }
    }
    private static void safeCode(String code) { if (code != null && !code.matches("[A-Z][A-Z0-9_]{0,99}")) throw new IllegalArgumentException("Invalid publication outcome code"); }
}
