package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Shared assessment evidence policy for live aggregation and interrupted archive restoration. */
final class ClusterAnalysisCoverage {
    private final List<AnalysisCoverage> coverage = new ArrayList<>();
    private final List<String> unassessedRoots = new ArrayList<>();
    private boolean legacyEvidence;

    void add(String root, AnalysisResult result) {
        if (result == null) unassessedRoots.add(root);
        else if (result.getAnalysisCoverage() != null) coverage.add(result.getAnalysisCoverage());
        else if (result.getRawScores().isEmpty()) unassessedRoots.add(root);
        else legacyEvidence = true;
    }

    AnalysisCoverage combine(AnalysisSourceAuthority authority, Function<String, RootCatalogueSnapshot> frozenRoot) {
        // A partial v3 map cannot represent legacy scored evidence. Preserve its
        // export contract rather than inventing assessment history.
        if (legacyEvidence || coverage.isEmpty()) return null;
        var roots = new ArrayList<>(coverage);
        for (var root : unassessedRoots) roots.add(unassessedCoverage(authority, root, frozenRoot.apply(root)));
        var nodes = new LinkedHashMap<String, AnalysisCoverage.NodeAssessment>();
        int assessed = 0, unknown = 0, unresolved = 0;
        for (var root : roots) {
            for (var node : root.nodes().entrySet()) {
                if (nodes.putIfAbsent(node.getKey(), node.getValue()) != null)
                    throw new IllegalStateException("Conflicting catalogue identities across root coverage");
            }
            assessed += root.assessedNodes();
            unknown += root.unknownNodes();
            unresolved += root.failedOrBlockedNodes();
        }
        return new AnalysisCoverage(nodes, assessed, unknown, unresolved);
    }

    /** Missing worker evidence leaves every node in its original frozen root open. */
    private static AnalysisCoverage unassessedCoverage(AnalysisSourceAuthority authority, String root,
                                                        RootCatalogueSnapshot snapshot) {
        if (!root.equals(snapshot.rootCode())
                || !Objects.equals(authority.repositoryId(), snapshot.source().repositoryId())
                || !Objects.equals(authority.workspaceId(), snapshot.source().workspaceId())
                || !Objects.equals(authority.branch(), snapshot.source().branch())
                || !Objects.equals(authority.sourceCommit(), snapshot.source().sourceCommit()))
            throw new IllegalStateException("Frozen coverage source differs from admitted authority");
        var parents = new HashSet<String>();
        for (var node : snapshot.nodes()) if (node.parentCode() != null) parents.add(node.parentCode());
        var nodes = new LinkedHashMap<String, AnalysisCoverage.NodeAssessment>();
        for (var node : snapshot.nodes()) {
            nodes.put(node.code(), new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    parents.contains(node.code()) ? AnalysisCoverage.Descendants.UNASSESSED : AnalysisCoverage.Descendants.COMPLETE,
                    "INTERRUPTED:ROOT_ANALYSIS_UNAVAILABLE"));
        }
        return new AnalysisCoverage(nodes, 0, nodes.size(), nodes.size());
    }
}
