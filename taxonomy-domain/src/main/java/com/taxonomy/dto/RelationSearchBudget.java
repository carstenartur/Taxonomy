package com.taxonomy.dto;

/** New relation evaluations are distinct from replayed scheduler grants and completed work. */
public record RelationSearchBudget(int callsPerSlice, int maxTotalCalls, int newCalls, int spentCalls,
        boolean automatic, int slices, int sourcesPerSlice, int workItemsPerSlice, int sourceCohortSize) {
    public RelationSearchBudget {
        if (callsPerSlice < 0 || callsPerSlice > 10_000 || maxTotalCalls < 0 || maxTotalCalls > 10_000
                || newCalls < 0 || spentCalls < newCalls || slices < 0
                || sourcesPerSlice < 1 || sourcesPerSlice > 256
                || workItemsPerSlice < 1 || workItemsPerSlice > 100_000
                || sourceCohortSize < 1 || sourceCohortSize > 256)
            throw new IllegalArgumentException("Invalid relation execution budget");
    }

    /** A lower administrative limit never erases calls already spent by the journal. */
    public RelationSearchBudget withSpentCalls(int cumulative, int beforeExecution) {
        int current = Math.max(0, cumulative - beforeExecution);
        int sections = current == 0 ? 0 : Math.max(1, (current + Math.max(1, callsPerSlice) - 1) / Math.max(1, callsPerSlice));
        return new RelationSearchBudget(callsPerSlice, maxTotalCalls, current, cumulative, automatic,
                Math.max(slices, sections), sourcesPerSlice, workItemsPerSlice, sourceCohortSize);
    }
}
