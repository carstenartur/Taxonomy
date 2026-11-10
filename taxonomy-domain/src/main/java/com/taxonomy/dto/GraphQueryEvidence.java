package com.taxonomy.dto;
import java.util.Map;
/** Exact revision and declared limits of a graph answer. No universal absence claim. */
public record GraphQueryEvidence(String repositoryId, String workspaceId, String workspaceScopeKey,
        String branch, String commitId, String readAuthority, String readinessState, int snapshotRelationCount, String queryPolicy,
        int requestedMaxHops, int effectiveMaxHops, double minimumRelevance, double hopDecay,
        int anchorHighThreshold, int anchorLowThreshold, int minimumAnchors,
        Map<String, Double> relationTypeWeights, String completeness, String absenceScope) {
    public GraphQueryEvidence { relationTypeWeights = Map.copyOf(relationTypeWeights); }
    public GraphQueryEvidence withCompleteness(String value) {
        return new GraphQueryEvidence(repositoryId, workspaceId, workspaceScopeKey, branch, commitId,
                readAuthority, readinessState, snapshotRelationCount, queryPolicy, requestedMaxHops, effectiveMaxHops, minimumRelevance, hopDecay,
                anchorHighThreshold, anchorLowThreshold, minimumAnchors,
                relationTypeWeights, value, absenceScope);
    }
}
