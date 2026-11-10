package com.taxonomy.dto;

import java.util.List;
import java.util.Map;

/** Observed APQC integration within one canonical model, never global process completeness. */
public record ApqcCoverageResult(String state, int observedElements, int linkedElements, Double linkedPercent,
        List<String> unlinkedElementIds, Map<String,Integer> observedByLevel, String repositoryId,
        String workspaceId, String branch, String commitId, List<String> notes) {
    public ApqcCoverageResult {
        unlinkedElementIds = List.copyOf(unlinkedElementIds); observedByLevel = Map.copyOf(observedByLevel); notes = List.copyOf(notes);
    }
}
