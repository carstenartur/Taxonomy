package com.nato.taxonomy.dto;

import java.util.List;

/**
 * Aggregated requirement-coverage statistics for the whole taxonomy.
 */
public record CoverageStatistics(
        int totalNodes,
        int coveredNodes,
        int uncoveredNodes,
        double coveragePercentage,
        double avgRequirementsPerNode,
        int totalRequirements,
        List<NodeCoverageEntry> topCovered,
        List<NodeCoverageEntry> gapCandidates
) {}
