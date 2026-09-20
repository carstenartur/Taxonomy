package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Canonical semantic and immutable request identities; provider CAS tokens are never inferred here. */
public final class PublicationDigests {
    private final IntegrationJson json;
    public PublicationDigests(IntegrationJson json) { this.json = Objects.requireNonNull(json); }
    public String fingerprint(Object value) { bounded(MAX_DOCUMENT_BYTES, value); return json.fingerprint(value); }
    public static Map<String, Artifact> items(ExchangeDocument document) {
        document(document);
        var values = new TreeMap<>(ExchangeItems.flatten(document));
        values.entrySet().removeIf(e -> e.getValue().kind() == ArtifactKind.METADATA && e.getValue().attributes().isEmpty() && e.getValue().extensions().isEmpty());
        Set<String> identities = new HashSet<>();
        for (Artifact value : values.values()) if (!identities.add(value.id())) throw IntegrationProblem.conflict("PUBLICATION_IDENTITY_REUSED");
        return Collections.unmodifiableMap(values);
    }
    public String semantic(Artifact artifact) { return fingerprint(artifact == null ? null : Map.of("id", artifact.id(), "fields", ExchangeItems.fields(artifact))); }
    public String semantic(ExchangeDocument document) {
        Map<String, Object> values = new TreeMap<>(); items(document).forEach((key, value) -> values.put(key, ExchangeItems.fields(value)));
        return fingerprint(Map.of("profile", document.profile(), "profileVersion", document.profileVersion(), "items", values));
    }
    public boolean converged(PublicationPlan plan) { return semantic(plan.localTarget()).equals(semantic(plan.remoteTarget())); }
    public PublicationItemIntent intent(UUID operationId, MutationKind mutation, String resourceId, ResourceState expected, Artifact target, Set<String> dependencies) {
        String itemId = fingerprint(List.of(operationId, resourceId));
        String key = fingerprint(List.of("publication-key-v1", operationId, itemId));
        var unsigned = new PublicationItemIntent(itemId, key, mutation, resourceId, expected, target, dependencies, "unsigned");
        return new PublicationItemIntent(itemId, key, mutation, resourceId, expected, target, dependencies, intentFingerprint(unsigned));
    }
    public String intentFingerprint(PublicationItemIntent item) {
        return fingerprint(Arrays.asList(item.itemId(), item.idempotencyKey(), item.mutation(), item.resourceId(), item.expectedResource(), item.target(), item.dependencies()));
    }
    public PublicationItemRequest request(UUID operationId, String planFingerprint, ProviderIdentity provider, PublicationScope scope, PublicationItemIntent item, String revision) {
        var unsigned = new PublicationItemRequest(SCHEMA_VERSION, operationId, planFingerprint, provider, scope, item, revision, "unsigned");
        return new PublicationItemRequest(SCHEMA_VERSION, operationId, planFingerprint, provider, scope, item, revision, requestFingerprint(unsigned));
    }
    public String requestFingerprint(PublicationItemRequest request) {
        return fingerprint(List.of(request.schemaVersion(), request.operationId(), request.planFingerprint(), request.provider(), request.scope(), request.item(), request.expectedScopeRevision()));
    }
    public String previewFingerprint(PublicationEvidence.PublicationPreviewEnvelope preview) {
        return fingerprint(Arrays.asList(preview.schemaVersion(), preview.request(), preview.context(), preview.connectionRevision(), preview.commonCheckpointId(), preview.commonBaseline(), preview.localDocument(), preview.remoteSnapshot(), preview.changes(), preview.losses()));
    }
    public String planFingerprint(PublicationPlan plan) {
        return fingerprint(Arrays.asList(plan.schemaVersion(), plan.operationId(), plan.context(), plan.mode(), plan.commonCheckpointId(), plan.connectionRevision(), plan.requestFingerprint(), plan.reviewFingerprint(), plan.capabilities(), plan.remoteBefore(), plan.localBefore(), plan.localTarget(), plan.remoteTarget(), plan.items(), plan.losses()));
    }
}
