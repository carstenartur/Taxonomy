package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

final class PublicationFixtures {
    static final IntegrationJson JSON = new IntegrationJson(JsonMapper.builder().build());
    static final PublicationDigests DIGESTS = new PublicationDigests(JSON);
    static final ProviderIdentity PROVIDER = new ProviderIdentity("provider", "repository", "configuration");
    static final ExternalScope EXTERNAL = new ExternalScope("contract", "repository", "configuration");
    static final PublicationScope SCOPE = new PublicationScope(EXTERNAL, "root", "selector");
    static final InternalState INTERNAL = new InternalState("repository", "workspace", "main", "commit", 1, null, null);
    static final IntegrationContext CONTEXT = new IntegrationContext(UUID.randomUUID(), AuthorityMode.BIDIRECTIONAL, EXTERNAL, INTERNAL, "actor", "contract", "1");
    static Artifact artifact(String id, String title, String text) { return new Artifact(id, ArtifactKind.ELEMENT, "component", title, text, Map.of(), Map.of()); }
    static ExchangeDocument document(Artifact... items) { return new ExchangeDocument("contract", "1", "transport-version", true, "transport source", List.of(items), List.of(), List.of(), Map.of(), List.of()); }
    static ScopeSnapshot snapshot(ExchangeDocument document) {
        Map<String, ResourceState> resources = new TreeMap<>();
        PublicationDigests.items(document).values().forEach(a -> resources.put(a.id(), new ResourceState(a.id(), true, "resource-1", DIGESTS.semantic(a))));
        return new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-1", DIGESTS.semantic(document), true, document, resources);
    }
    static PublicationCapabilities capabilities() { return new PublicationCapabilities(1, "taxonomy-publication-contract-v1", PROVIDER, SCOPE, Set.of(Guarantee.values()), Set.of(MutationKind.values()), Set.of(ArtifactKind.values()), "server-verification", "capability-digest", 1024, 1048576, 3600); }
    static CommonBaseline baseline(ExchangeDocument local, ExchangeDocument remote) { return new CommonBaseline(1, PROVIDER, SCOPE, "contract", "1", local, remote, DIGESTS.semantic(local)); }
    static PublicationPreviewEnvelope preview(CommonBaseline base, ExchangeDocument local, ExchangeDocument remote, PublicationMode mode) {
        ScopeSnapshot snapshot = snapshot(remote);
        var request = new PublicationPreviewRequest(UUID.randomUUID(), INTERNAL, mode, SCOPE, "scope-1");
        var changes = new PublicationDiff().compare(base, local, snapshot);
        var unsigned = new PublicationPreviewEnvelope(1, request, CONTEXT, 1, base == null ? null : UUID.randomUUID(), base, local, snapshot, changes, List.of(), "unsigned");
        return new PublicationPreviewEnvelope(1, request, CONTEXT, 1, unsigned.commonCheckpointId(), base, local, snapshot, changes, List.of(), DIGESTS.previewFingerprint(unsigned));
    }
    static PublicationReview review(PublicationPreviewEnvelope preview, PublicationResolution resolution) {
        Map<String, PublicationResolution> values = new TreeMap<>(); preview.changes().forEach(c -> values.put(c.id(), resolution));
        return new PublicationReview(new ReviewedChangeSet(preview.request().operationId(), preview.previewFingerprint(), Map.of(), "Reviewed explicit changes"), values);
    }
    static PublicationPolicy policy() { return new PublicationPolicy(List.of(new PublicationPolicy.VerifiedContract("contract", "1", capabilities()))); }
    static PublicationPlanner planner() { return new PublicationPlanner(DIGESTS, policy(), capabilities()); }
    static PublicationItemRequest request(MutationKind mutation) {
        Artifact target = mutation == MutationKind.DELETE ? null : artifact("a", "New", "Description");
        var intent = DIGESTS.intent(UUID.fromString("00000000-0000-0000-0000-000000000001"), mutation, "a", new ResourceState("a", mutation != MutationKind.CREATE, mutation == MutationKind.CREATE ? null : "resource-1", mutation == MutationKind.CREATE ? null : DIGESTS.semantic(artifact("a", "Old", "Description"))), target, Set.of());
        return DIGESTS.request(UUID.fromString("00000000-0000-0000-0000-000000000001"), "plan", PROVIDER, SCOPE, intent, "scope-1");
    }
}
