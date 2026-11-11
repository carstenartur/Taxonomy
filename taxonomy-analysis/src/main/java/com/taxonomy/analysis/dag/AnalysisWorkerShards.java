package com.taxonomy.analysis.dag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed set of taxonomy roots one worker consumes, e.g. {@code CP,IP}.
 *
 * <p>Configuration is parsed into validated {@link TaxonomyShardRoot}s of the
 * supplied catalogue definition. A blank selection consumes every registered
 * root. Unknown and duplicate shard names are rejected.</p>
 */
public record AnalysisWorkerShards(List<TaxonomyShardRoot> roots, Set<TaxonomyShardRoot> available) {

    public AnalysisWorkerShards(List<TaxonomyShardRoot> roots) {
        this(roots, Set.copyOf(TaxonomyShardRoot.DEFAULT_ROOTS));
    }

    public AnalysisWorkerShards {
        if (roots == null || roots.isEmpty()) throw new IllegalArgumentException("At least one shard root is required");
        available = Set.copyOf(available);
        Set<TaxonomyShardRoot> distinct = new LinkedHashSet<>();
        for (TaxonomyShardRoot root : roots) {
            if (!available.contains(root) || !root.catalogueIdentifier()) {
                throw new IllegalArgumentException("Unsupported worker shard root: " + root);
            }
            if (!distinct.add(root)) throw new IllegalArgumentException("Duplicate worker shard root: " + root);
        }
        roots = List.copyOf(distinct);
    }

    /** All eight default catalogue roots. */
    public static AnalysisWorkerShards all() {
        return new AnalysisWorkerShards(TaxonomyShardRoot.DEFAULT_ROOTS);
    }

    /** Parse a comma-separated root list; blank selects {@link #all()}. */
    public static AnalysisWorkerShards parse(String value) {
        return parse(value, com.taxonomy.dsl.model.CatalogueDefinition.standard());
    }

    public static AnalysisWorkerShards parse(String value, com.taxonomy.dsl.model.CatalogueDefinition definition) {
        var allRoots = definition.rootCodes().stream().map(TaxonomyShardRoot::of).toList();
        var available = Set.copyOf(allRoots);
        if (value == null || value.isBlank()) return new AnalysisWorkerShards(allRoots, available);
        List<TaxonomyShardRoot> parsed = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            String code = token.strip();
            if (code.isEmpty()) throw new IllegalArgumentException("Empty worker shard root");
            parsed.add(TaxonomyShardRoot.of(code));
        }
        return new AnalysisWorkerShards(parsed, available);
    }

    public boolean contains(TaxonomyShardRoot root) {
        return roots.contains(root);
    }
}
