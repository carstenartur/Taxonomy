package com.taxonomy.dto;

import java.util.List;
import static com.taxonomy.dto.RelationSearchModel.Direction;

/** Fixed source/target-taxonomy tasks; provider calls are a separate execution budget. */
public record RelationSearchProgress(int totalSources, int assessedSources, int totalSearches,
        int completedSearches, int unresolvedSearches, int pendingSearches,
        int calls, int maxCalls, int verifiedRelations, Step step, Current current,
        List<Taxonomy> taxonomies) {
    public RelationSearchProgress { taxonomies = List.copyOf(taxonomies); }
    public enum Step { SOURCES, NAVIGATE, VERIFY, FINISHED, PAUSED }
    public enum State { PENDING, RUNNING, COMPLETED, UNRESOLVED }
    public record Current(String sourceId, String targetRoot, String type, Direction direction,
                          int depth, List<String> candidates) {
        public Current { candidates = List.copyOf(candidates); }
    }
    public record Task(String sourceId, String targetRoot, State state) { }
    public record Taxonomy(String root, int total, int completed, int unresolved, int pending) { }
}
