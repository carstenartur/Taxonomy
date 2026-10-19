package com.taxonomy.dto;

import java.util.List;
import static com.taxonomy.dto.RelationSearchModel.Direction;

/** Fixed source/target-taxonomy tasks; provider calls are a separate execution budget. */
public record RelationSearchProgress(int totalSources, int assessedSources, int totalSearches,
        int completedSearches, int unresolvedSearches, int pendingSearches,
        int calls, int maxCalls, int verifiedRelations, Step step, Current current,
        List<Taxonomy> taxonomies, List<SourceTaxonomy> sourceTaxonomies, RelationSearchBudget budget) {
    public RelationSearchProgress(int totalSources, int assessedSources, int totalSearches,
            int completedSearches, int unresolvedSearches, int pendingSearches, int calls, int maxCalls,
            int verifiedRelations, Step step, Current current, List<Taxonomy> taxonomies, List<SourceTaxonomy> sourceTaxonomies) {
        this(totalSources, assessedSources, totalSearches, completedSearches, unresolvedSearches, pendingSearches,
                calls, maxCalls, verifiedRelations, step, current, taxonomies, sourceTaxonomies, null);
    }
    public RelationSearchProgress {
        taxonomies = List.copyOf(taxonomies);
        sourceTaxonomies = sourceTaxonomies == null ? List.of() : List.copyOf(sourceTaxonomies);
    }
    public RelationSearchProgress(int totalSources, int assessedSources, int totalSearches,
            int completedSearches, int unresolvedSearches, int pendingSearches, int calls, int maxCalls,
            int verifiedRelations, Step step, Current current, List<Taxonomy> taxonomies) {
        this(totalSources, assessedSources, totalSearches, completedSearches, unresolvedSearches, pendingSearches,
                calls, maxCalls, verifiedRelations, step, current, taxonomies, List.of());
    }
    public RelationSearchProgress withBudget(RelationSearchBudget usage) {
        return new RelationSearchProgress(totalSources, assessedSources, totalSearches, completedSearches,
                unresolvedSearches, pendingSearches, calls, maxCalls, verifiedRelations, step, current,
                taxonomies, sourceTaxonomies, usage);
    }
    public enum Step { SOURCES, NAVIGATE, VERIFY, FINISHED, PAUSED }
    public enum State { PENDING, RUNNING, COMPLETED, UNRESOLVED }
    public record Current(String sourceId, String targetRoot, String type, Direction direction,
                          int depth, List<String> candidates) {
        public Current { candidates = List.copyOf(candidates); }
    }
    public record Task(String sourceId, String targetRoot, State state) { }
    public record Taxonomy(String root, int total, int completed, int unresolved, int pending) { }
    /** A source completes only after its contribution and every target/type search are settled. */
    public record SourceTaxonomy(String root, int total, int completed, int unresolved) { }
}
