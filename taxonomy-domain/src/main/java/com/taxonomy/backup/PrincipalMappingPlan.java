package com.taxonomy.backup;

import java.util.*;

/** Read-only proposal until an authorized operator explicitly approves target assignments. */
public final class PrincipalMappingPlan {
    public enum Status { MATCHED, UNRESOLVED, CONFLICT }
    public record Entry(Status status, Set<PrincipalId> candidates) {
        public Entry { Objects.requireNonNull(status); candidates = Set.copyOf(candidates); }
    }
    private final Map<PrincipalId, Entry> entries;
    private final Set<PrincipalId> enabledTargets;
    private final PrincipalMapping mapping;

    PrincipalMappingPlan(Map<PrincipalId, Entry> entries, Set<PrincipalId> enabledTargets, PrincipalMapping mapping) {
        this.entries = Map.copyOf(entries); this.enabledTargets = Set.copyOf(enabledTargets); this.mapping = mapping;
    }
    public Map<PrincipalId, Entry> entries() { return entries; }
    public boolean approved() { return mapping != null; }
    public void requireResolved() {
        if (entries.values().stream().anyMatch(e -> e.status() != Status.MATCHED)) throw new IllegalStateException("Unresolved identity mappings");
    }
    /** Omitted source identities stay quarantined; names and suggested matches never auto-fill selections. */
    public PrincipalMappingPlan approve(Map<PrincipalId, PrincipalId> selections, PrincipalId actor, String rationale) {
        if (!entries.keySet().containsAll(selections.keySet()) || !enabledTargets.containsAll(selections.values())) {
            throw new IllegalArgumentException("Unknown source or unavailable target principal");
        }
        return new PrincipalMappingPlan(entries, enabledTargets, new PrincipalMapping(selections, actor, rationale));
    }
    public PrincipalMapping toApprovedMapping() {
        if (mapping == null) throw new IllegalStateException("Identity mapping requires explicit approval");
        return mapping;
    }
}
