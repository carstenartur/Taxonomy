package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.publication.PublicationEvidence.*;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Pure directed planning; every target and dependency is fixed before local or remote effects. */
public final class PublicationPlanner {
    private final PublicationDigests digests;
    private final PublicationPolicy policy;
    private final PublicationCapabilities capabilities;
    private final PublicationMappings mappings;
    public PublicationPlanner(PublicationDigests digests, PublicationPolicy policy, PublicationCapabilities capabilities) {
        this(digests, policy, capabilities, new PublicationMappings());
    }
    public PublicationPlanner(PublicationDigests digests, PublicationPolicy policy, PublicationCapabilities capabilities, PublicationMappings mappings) {
        this.digests = Objects.requireNonNull(digests); this.policy = Objects.requireNonNull(policy); this.capabilities = Objects.requireNonNull(capabilities); this.mappings = Objects.requireNonNull(mappings);
    }
    public PublicationPlan plan(PublicationPreviewEnvelope preview, PublicationReview review) {
        policy.requireVerified(preview.context(), capabilities); policy.requireAuthority(preview.context(), preview.request().mode());
        validatePreview(preview);
        if (!preview.request().operationId().equals(review.review().operationId()) || !preview.previewFingerprint().equals(review.review().previewFingerprint()))
            throw IntegrationProblem.conflict("PUBLICATION_REVIEW_MISMATCH");
        Set<String> ids = new TreeSet<>(); preview.changes().forEach(c -> ids.add(c.id()));
        if (!review.review().decisions().isEmpty() || !ids.equals(review.resolutions().keySet())) throw invalid("PUBLICATION_RESOLUTION_COVERAGE");
        Set<String> mappedIds = new TreeSet<>(review.review().mappings().keySet()); mappedIds.addAll(review.review().endpoints().keySet());
        if (!ids.containsAll(mappedIds) || mappedIds.stream().anyMatch(id -> review.resolutions().get(id) == PublicationResolution.SKIP)) throw invalid("PUBLICATION_MAPPING_SELECTION");
        List<MappingLoss> losses = allLosses(preview); Set<MappingLoss> resolvedLosses = new LinkedHashSet<>();
        Map<String, Artifact> local = new TreeMap<>(PublicationDigests.items(preview.localDocument()));
        Map<String, Artifact> remote = new TreeMap<>(PublicationDigests.items(preview.remoteSnapshot().document()));
        for (PublicationChange change : preview.changes()) {
            PublicationResolution resolution = review.resolutions().get(change.id());
            if (change.conflicts().contains("identity")) throw invalid("PUBLICATION_IDENTITY_REUSED");
            if (resolution == PublicationResolution.SKIP) continue;
            Artifact target = switch (resolution) {
                case MERGE -> { if (!change.conflicts().isEmpty()) throw invalid("PUBLICATION_FIELD_CONFLICT"); yield change.merged(); }
                case KEEP_LOCAL -> change.local();
                case TAKE_REMOTE -> {
                    if (preview.request().mode() == PublicationMode.PUSH) throw IntegrationProblem.conflict("RECONCILIATION_REQUIRED"); yield change.remote();
                }
                case SKIP -> throw new IllegalStateException("Skipped above");
            };
            var mapped = mappings.apply(preview, change.id(), change.local(), target, review.review().mappings().get(change.id()), review.review().endpoints().get(change.id()), losses);
            target = mapped.target(); resolvedLosses.addAll(mapped.resolvedLosses()); rejectUnsupportedLoss(losses, change, mapped.resolvedLosses());
            if (target == null && (change.local() != null || change.remote() != null)
                    && (change.baseLocal() == null || change.baseRemote() == null || preview.commonBaseline() == null)) throw invalid("PUBLICATION_DELETE_REQUIRES_COMMON");
            if (preview.request().mode() == PublicationMode.PUSH && !PublicationDiff.equivalent(target, change.local())) throw IntegrationProblem.conflict("RECONCILIATION_REQUIRED");
            if (preview.request().mode() == PublicationMode.SYNCHRONIZE) put(local, change.id(), target);
            put(remote, change.id(), target);
        }
        validateClosure(local, preview); validateClosure(remote, preview);
        ExchangeDocument localTarget = expand(preview.localDocument(), local, resolvedLosses), remoteTarget = expand(preview.remoteSnapshot().document(), remote, resolvedLosses);
        List<MappingLoss> reviewedLosses = resolved(losses, resolvedLosses);
        List<PublicationItemIntent> items = mutations(preview, remote);
        var unsigned = new PublicationPlan(SCHEMA_VERSION, preview.request().operationId(), preview.context(), preview.request().mode(), preview.commonCheckpointId(), preview.connectionRevision(),
                digests.fingerprint(preview.request()), digests.fingerprint(review), capabilities, preview.remoteSnapshot(), preview.localDocument(), localTarget, remoteTarget, items, reviewedLosses, "unsigned");
        var plan = new PublicationPlan(unsigned.schemaVersion(), unsigned.operationId(), unsigned.context(), unsigned.mode(), unsigned.commonCheckpointId(), unsigned.connectionRevision(), unsigned.requestFingerprint(), unsigned.reviewFingerprint(), unsigned.capabilities(), unsigned.remoteBefore(), unsigned.localBefore(), unsigned.localTarget(), unsigned.remoteTarget(), unsigned.items(), unsigned.losses(), digests.planFingerprint(unsigned));
        for (PublicationItemIntent item : items) {
            try { bounded(capabilities.maxRequestBytes(), digests.request(plan.operationId(), plan.planFingerprint(), capabilities.provider(), capabilities.scope(), item, preview.remoteSnapshot().revision())); }
            catch (IllegalArgumentException tooLarge) { throw invalid("PUBLICATION_REQUEST_LIMIT"); }
        }
        return plan;
    }
    private void validatePreview(PublicationPreviewEnvelope preview) {
        if (!preview.previewFingerprint().equals(digests.previewFingerprint(preview))) throw IntegrationProblem.conflict("PUBLICATION_PREVIEW_CHANGED");
        if (!preview.request().expected().equals(preview.context().internalState()) || !preview.context().externalScope().equals(preview.request().scope().externalScope())
                || !preview.context().profile().equals(preview.localDocument().profile()) || !preview.context().profileVersion().equals(preview.localDocument().profileVersion())) throw IntegrationProblem.conflict("PUBLICATION_CONTEXT_MISMATCH");
        if (!capabilities.provider().equals(preview.remoteSnapshot().provider()) || !capabilities.scope().equals(preview.request().scope())
                || !preview.remoteSnapshot().scope().equals(preview.request().scope()) || !preview.request().expectedExternalRevision().equals(preview.remoteSnapshot().revision())) throw IntegrationProblem.conflict("PUBLICATION_SCOPE_MISMATCH");
        if (!preview.localDocument().completeScope()) throw invalid("PUBLICATION_INCOMPLETE_SCOPE");
        new PublicationReceiptValidator(digests).validateSnapshot(preview.remoteSnapshot());
        if (!new PublicationDiff().compare(preview.commonBaseline(), preview.localDocument(), preview.remoteSnapshot()).equals(preview.changes())) throw IntegrationProblem.conflict("PUBLICATION_PREVIEW_CHANGED");
    }
    private static List<MappingLoss> allLosses(PublicationPreviewEnvelope preview) {
        Set<MappingLoss> losses = new LinkedHashSet<>(preview.losses()); losses.addAll(preview.localDocument().losses()); losses.addAll(preview.remoteSnapshot().document().losses());
        return List.copyOf(losses);
    }
    private void rejectUnsupportedLoss(List<MappingLoss> losses, PublicationChange change, Set<MappingLoss> resolved) {
        if (losses.stream().anyMatch(loss -> loss.disposition() == LossDisposition.UNSUPPORTED && !resolved.contains(loss)
                && (loss.artifactId() == null || loss.artifactId().equals(change.externalId()) || loss.artifactId().equals(change.id()) || loss.artifactId().equals("package")))) throw invalid("PUBLICATION_MAPPING_LOSS");
    }
    private static List<MappingLoss> resolved(List<MappingLoss> losses, Set<MappingLoss> resolved) {
        return losses.stream().map(loss -> resolved.contains(loss) ? new MappingLoss(loss.artifactId(), loss.field(), loss.code(), LossDisposition.TRANSFORMED, "Resolved by the frozen explicit field mapping") : loss).toList();
    }
    private void validateClosure(Map<String, Artifact> values, PublicationPreviewEnvelope preview) {
        Set<String> resources = new HashSet<>(); values.values().forEach(a -> resources.add(a.id()));
        resources.add(preview.request().scope().rootResource());
        // Native exchange roots are explicit scope metadata, never arbitrary missing endpoint exemptions.
        String identifier = preview.remoteSnapshot().document().metadata().get("identifier");
        if (identifier != null && identifier.equals(preview.request().scope().rootResource())) resources.add(identifier);
        Map<String, Integer> degrees = new HashMap<>(); Map<String, List<String>> dependents = new HashMap<>();
        Set<String> itemIds = new HashSet<>(); values.values().forEach(item -> itemIds.add(item.id()));
        for (Artifact item : values.values()) {
            int degree = 0;
            for (String dependency : PublicationDiff.dependencies(item)) {
                if (!resources.contains(dependency) || dependency.equals(item.id())) throw invalid("PUBLICATION_DEPENDENCY_MISSING");
                if (itemIds.contains(dependency)) { degree++; dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(item.id()); }
            }
            degrees.put(item.id(), degree);
        }
        Deque<String> ready = new ArrayDeque<>(); degrees.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst(); visited++;
            for (String dependent : dependents.getOrDefault(id, List.of())) if (degrees.compute(dependent, (key, count) -> count - 1) == 0) ready.add(dependent);
        }
        if (visited != values.size()) throw invalid("PUBLICATION_DEPENDENCY_CYCLE");
    }
    private List<PublicationItemIntent> mutations(PublicationPreviewEnvelope preview, Map<String, Artifact> targets) {
        Map<String, Artifact> before = PublicationDigests.items(preview.remoteSnapshot().document());
        Set<String> keys = new TreeSet<>(before.keySet()); keys.addAll(targets.keySet());
        Map<String, PublicationItemIntent> intents = new TreeMap<>();
        for (String key : keys) {
            Artifact previous = before.get(key), target = targets.get(key);
            if (PublicationDiff.equivalent(previous, target)) continue;
            MutationKind mutation = previous == null ? MutationKind.CREATE : target == null ? MutationKind.DELETE : MutationKind.UPDATE;
            Artifact value = target == null ? previous : target;
            if (!capabilities.mutations().contains(mutation) || !capabilities.artifactKinds().contains(value.kind())) throw invalid("PUBLICATION_MUTATION_UNSUPPORTED");
            ResourceState expected = preview.remoteSnapshot().resources().get(value.id());
            if (expected == null) expected = new ResourceState(value.id(), false, null, null);
            PublicationItemIntent intent = digests.intent(preview.request().operationId(), mutation, value.id(), expected, target, Set.of());
            intents.put(value.id(), intent);
            if (intents.size() > Math.min(MAX_MUTATIONS, capabilities.maxItems())) throw invalid("PUBLICATION_MUTATION_LIMIT");
        }
        Map<String, Set<String>> dependencies = new TreeMap<>(); intents.keySet().forEach(id -> dependencies.put(id, new TreeSet<>()));
        for (PublicationItemIntent intent : intents.values()) {
            if (intent.mutation() != MutationKind.DELETE) for (String dependency : PublicationDiff.dependencies(intent.target())) {
                PublicationItemIntent parent = intents.get(dependency);
                if (parent != null) dependencies.get(intent.resourceId()).add(parent.resourceId());
            }
            // Remove/update dependent items before deleting the objects they previously referenced.
            for (Artifact old : before.values()) if (PublicationDiff.dependencies(old).contains(intent.resourceId()) && intent.mutation() == MutationKind.DELETE) {
                PublicationItemIntent dependent = intents.get(old.id());
                if (dependent == null) throw invalid("PUBLICATION_DEPENDENCY_MISSING");
                dependencies.get(intent.resourceId()).add(dependent.resourceId());
            }
        }
        List<PublicationItemIntent> result = new ArrayList<>(); Set<String> done = new HashSet<>();
        while (done.size() < intents.size()) {
            String ready = intents.keySet().stream().filter(id -> !done.contains(id) && done.containsAll(dependencies.get(id))).findFirst().orElseThrow(() -> invalid("PUBLICATION_DEPENDENCY_CYCLE"));
            PublicationItemIntent item = intents.get(ready);
            Set<String> itemDependencies = new TreeSet<>(); dependencies.get(ready).forEach(id -> itemDependencies.add(intents.get(id).itemId()));
            PublicationItemIntent frozen = digests.intent(preview.request().operationId(), item.mutation(), item.resourceId(), item.expectedResource(), item.target(), itemDependencies);
            bounded(capabilities.maxRequestBytes(), frozen); result.add(frozen); done.add(ready);
        }
        return List.copyOf(result);
    }
    private static ExchangeDocument expand(ExchangeDocument template, Map<String, Artifact> values, Set<MappingLoss> resolved) {
        var emptyMetadata = new ExchangeDocument(template.profile(), template.profileVersion(), template.externalVersion(), template.completeScope(), template.source(), template.artifacts(), template.relations(), template.placements(), Map.of(), resolved(template.losses(), resolved));
        return ExchangeItems.expand(emptyMetadata, values);
    }
    private static void put(Map<String, Artifact> values, String key, Artifact value) { if (value == null) values.remove(key); else values.put(key, value); }
    private static IntegrationProblem invalid(String code) { return new IntegrationProblem(code, 422, "Publication selection cannot be represented safely"); }
}
