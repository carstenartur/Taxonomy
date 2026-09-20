package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import com.taxonomy.interop.publication.PublicationEvidence.CommonBaseline;
import java.util.*;

/** Directed comparison against paired COMMON projections, never the inbound observation journal. */
public final class PublicationDiff {
    public List<PublicationChange> compare(CommonBaseline baseline, ExchangeDocument local, ScopeSnapshot remote) {
        if (!local.profile().equals(remote.document().profile()) || !local.profileVersion().equals(remote.document().profileVersion())
                || baseline != null && (!baseline.provider().equals(remote.provider()) || !baseline.scope().equals(remote.scope())
                || !baseline.profile().equals(local.profile()) || !baseline.profileVersion().equals(local.profileVersion())))
            throw IntegrationProblem.conflict("PUBLICATION_BASELINE_MISMATCH");
        Map<String, Artifact> locals = PublicationDigests.items(local), remotes = PublicationDigests.items(remote.document());
        Map<String, Artifact> baseLocals = baseline == null ? Map.of() : PublicationDigests.items(baseline.localDocument());
        Map<String, Artifact> baseRemotes = baseline == null ? Map.of() : PublicationDigests.items(baseline.remoteDocument());
        Set<String> keys = new TreeSet<>(); keys.addAll(locals.keySet()); keys.addAll(remotes.keySet()); keys.addAll(baseLocals.keySet()); keys.addAll(baseRemotes.keySet());
        Map<String, ArtifactKind> kinds = new HashMap<>();
        for (Map<String, Artifact> values : List.of(baseLocals, baseRemotes, locals, remotes)) for (Artifact item : values.values()) {
            ArtifactKind previous = kinds.putIfAbsent(item.id(), item.kind());
            if (previous != null && previous != item.kind()) throw IntegrationProblem.conflict("PUBLICATION_IDENTITY_REUSED");
        }
        List<PublicationChange> changes = new ArrayList<>();
        for (String key : keys) {
            Artifact l = locals.get(key), r = remotes.get(key), bl = baseLocals.get(key), br = baseRemotes.get(key);
            boolean replacedIdentity = identityChanged(bl, l) || identityChanged(br, r);
            if (equivalent(l, r) && !replacedIdentity) continue;
            Set<String> lf = changed(bl, l), rf = changed(br, r);
            List<String> conflicts = new ArrayList<>(); Artifact merged;
            if (baseline == null || bl == null && br == null) {
                merged = l == null ? r : l;
                if (l != null && r != null) conflicts.addAll(changed(l, r));
            } else if (l == null || r == null) {
                boolean oppositeChanged = l == null ? !rf.isEmpty() : !lf.isEmpty();
                if (oppositeChanged) conflicts.add("existence");
                merged = null;
            } else {
                Map<String, String> left = ExchangeItems.fields(l), right = ExchangeItems.fields(r);
                for (String field : rf) if (lf.contains(field) && !Objects.equals(left.get(field), right.get(field))) conflicts.add(field);
                if (lf.isEmpty() && rf.isEmpty()) conflicts.add("baseline");
                merged = ExchangeItems.merge(br, l, r);
            }
            if (replacedIdentity) conflicts.add("identity");
            Set<String> dependencies = new TreeSet<>(); dependencies.addAll(dependencies(l)); dependencies.addAll(dependencies(r)); dependencies.addAll(dependencies(merged));
            changes.add(new PublicationChange(key, l != null ? l.id() : r != null ? r.id() : bl.id(), bl, br, l, r, merged, lf, rf, conflicts.stream().distinct().sorted().toList(), dependencies));
        }
        return List.copyOf(changes);
    }
    public static boolean equivalent(Artifact left, Artifact right) {
        if (left == null || right == null) return left == right;
        return left.id().equals(right.id()) && ExchangeItems.fields(left).equals(ExchangeItems.fields(right));
    }
    private static boolean identityChanged(Artifact before, Artifact after) {
        return before != null && after != null && !Objects.equals(before.extensions().get("internalIdentity"), after.extensions().get("internalIdentity"));
    }
    private static Set<String> changed(Artifact before, Artifact after) {
        Set<String> result = new TreeSet<>(IntegrationDiff.changed(before, after));
        if ((before == null) != (after == null)) result.add("existence"); return Collections.unmodifiableSet(result);
    }
    /** Resource identities, not display names. The planner converts mutation dependencies to item IDs. */
    public static Set<String> dependencies(Artifact artifact) {
        if (artifact == null) return Set.of();
        Set<String> result = new TreeSet<>();
        List<String> fields = switch (artifact.kind()) {
            case RELATION -> List.of("source", "target");
            case PLACEMENT -> List.of("container", "parent", "artifact");
            default -> List.of("parent", "parentPackage", "package");
        };
        for (String field : fields) { String id = artifact.extensions().get(field); if (id != null && !id.isBlank()) result.add(id); }
        return Collections.unmodifiableSet(result);
    }
}
