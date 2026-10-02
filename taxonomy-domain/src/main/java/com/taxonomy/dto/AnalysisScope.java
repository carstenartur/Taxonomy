package com.taxonomy.dto;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;

/** Immutable assessment scope. An empty selection means all available taxonomy roots. */
public record AnalysisScope(Set<String> taxonomyRoots, AnalysisMode mode) {
    public AnalysisScope {
        var ordered = new TreeSet<String>();
        if (taxonomyRoots != null) {
            for (String root : taxonomyRoots) {
                if (root == null || root.isBlank()) {
                    throw new IllegalArgumentException("Taxonomy root must not be null or blank");
                }
                ordered.add(root);
            }
        }
        taxonomyRoots = Collections.unmodifiableSet(ordered);
        mode = mode == null ? AnalysisMode.FULL : mode;
    }

    public static AnalysisScope full() { return new AnalysisScope(Set.of(), AnalysisMode.FULL); }
    public static AnalysisScope orDefault(AnalysisScope scope) { return scope == null ? full() : scope; }
    public boolean selects(String root) { return taxonomyRoots.isEmpty() || taxonomyRoots.contains(root); }
    public boolean includesRelations() { return mode == AnalysisMode.FULL; }
    public boolean legacyFull() { return taxonomyRoots.isEmpty() && includesRelations(); }

    /** Planning and coverage share the selection; display trees retain the full catalogue. */
    public List<TaxonomyNodeDto> selectedTree(List<TaxonomyNodeDto> tree) {
        return tree == null ? List.of() : tree.stream().filter(root -> selects(root.getCode())).toList();
    }

    /** Validate against catalogue identities before any operation is admitted. */
    public void validateRoots(Set<String> availableRoots) {
        var unknown = new TreeSet<>(taxonomyRoots);
        unknown.removeAll(availableRoots);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown taxonomy roots: " + String.join(", ", unknown));
    }
}
