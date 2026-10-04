package com.taxonomy.analysis.dag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed set of taxonomy roots one worker consumes, e.g. {@code CP,IP}.
 *
 * <p>Configuration is parsed into validated {@link TaxonomyShardRoot}s of the
 * default catalogue roots; unknown or duplicate roots fail closed instead of
 * silently creating a consumer for a destination nobody publishes to. An empty
 * value selects all default roots.</p>
 */
public record AnalysisWorkerShards(List<TaxonomyShardRoot> roots) {

    public AnalysisWorkerShards {
        if (roots == null || roots.isEmpty()) throw new IllegalArgumentException("At least one shard root is required");
        Set<TaxonomyShardRoot> distinct = new LinkedHashSet<>();
        for (TaxonomyShardRoot root : roots) {
            if (!root.defaultCatalogueRoot()) {
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
        if (value == null || value.isBlank()) return all();
        List<TaxonomyShardRoot> parsed = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            String code = token.strip();
            if (code.isEmpty()) throw new IllegalArgumentException("Empty worker shard root");
            parsed.add(TaxonomyShardRoot.of(code));
        }
        return new AnalysisWorkerShards(parsed);
    }

    public boolean contains(TaxonomyShardRoot root) {
        return roots.contains(root);
    }
}
