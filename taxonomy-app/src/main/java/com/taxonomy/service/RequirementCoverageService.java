package com.taxonomy.service;

import com.taxonomy.dto.CoverageStatistics;
import com.taxonomy.dto.NodeCoverageEntry;
import com.taxonomy.model.RequirementCoverage;
import com.taxonomy.repository.RequirementCoverageRepository;
import com.taxonomy.repository.TaxonomyNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for recording and querying requirement → taxonomy-node coverage mappings.
 *
 * <p>This is an independent service that consumes the output of the analysis
 * pipeline without modifying any existing services.
 */
@Service
public class RequirementCoverageService {

    private static final int DEFAULT_MIN_SCORE = 50;
    private static final int TOP_N = 10;

    private final RequirementCoverageRepository coverageRepository;
    private final TaxonomyNodeRepository nodeRepository;

    public RequirementCoverageService(RequirementCoverageRepository coverageRepository,
                                      TaxonomyNodeRepository nodeRepository) {
        this.coverageRepository = coverageRepository;
        this.nodeRepository = nodeRepository;
    }

    /**
     * Records all node→score mappings from {@code scores} that are at or above
     * {@code minScore} for the given requirement. Existing entries for the same
     * (requirementId, nodeCode) pair are replaced.
     *
     * @param scores        map of nodeCode → score (0-100)
     * @param requirementId identifier for the requirement (e.g. "REQ-101")
     * @param requirementText original business text
     * @param minScore      minimum score threshold (inclusive); defaults to 50 if ≤ 0
     */
    @Transactional
    public void analyzeCoverage(Map<String, Integer> scores,
                                String requirementId,
                                String requirementText,
                                int minScore) {
        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;
        Instant now = Instant.now();

        // Remove stale entries for this requirement before re-recording
        coverageRepository.deleteByRequirementId(requirementId);
        coverageRepository.flush();

        List<RequirementCoverage> entries = scores.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= threshold)
                .map(e -> new RequirementCoverage(requirementId, requirementText,
                        e.getKey(), e.getValue(), now))
                .toList();

        coverageRepository.saveAll(entries);
    }

    /**
     * Returns all requirement-coverage records for a given node code.
     */
    @Transactional(readOnly = true)
    public List<RequirementCoverage> getCoverageForNode(String nodeCode) {
        return coverageRepository.findByNodeCode(nodeCode);
    }

    /**
     * Returns all requirement-coverage records for a given requirement ID.
     */
    @Transactional(readOnly = true)
    public List<RequirementCoverage> getCoverageForRequirement(String requirementId) {
        return coverageRepository.findByRequirementId(requirementId);
    }

    /**
     * Returns aggregated coverage statistics for the whole taxonomy.
     */
    @Transactional(readOnly = true)
    public CoverageStatistics getCoverageStatistics() {
        long totalNodes = nodeRepository.count();
        long coveredNodes = coverageRepository.countDistinctNodeCodeByScoreGreaterThanEqual(DEFAULT_MIN_SCORE);
        long uncoveredNodes = Math.max(0, totalNodes - coveredNodes);
        double coveragePct = totalNodes > 0 ? (double) coveredNodes / totalNodes * 100.0 : 0.0;

        long totalRequirements = coverageRepository.countDistinctRequirementIds();

        // Build nodeCode → requirementCount map
        Map<String, Long> densityMap = buildDensityMap();
        double avgReqPerNode = coveredNodes > 0
                ? densityMap.values().stream().mapToLong(Long::longValue).sum() / (double) coveredNodes
                : 0.0;

        List<NodeCoverageEntry> topCovered = densityMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_N)
                .map(e -> new NodeCoverageEntry(e.getKey(), e.getValue().intValue()))
                .toList();

        // Gap candidates: taxonomy nodes with zero coverage entries
        List<NodeCoverageEntry> gapCandidates = nodeRepository.findAll().stream()
                .filter(n -> !densityMap.containsKey(n.getCode()))
                .sorted(Comparator.comparing(n -> n.getCode()))
                .limit(TOP_N)
                .map(n -> new NodeCoverageEntry(n.getCode(), 0))
                .toList();

        return new CoverageStatistics(
                (int) totalNodes,
                (int) coveredNodes,
                (int) uncoveredNodes,
                coveragePct,
                avgReqPerNode,
                (int) totalRequirements,
                topCovered,
                gapCandidates
        );
    }

    /**
     * Returns a map of {@code nodeCode → requirementCount} for heatmap visualisation.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> getRequirementDensityMap() {
        return buildDensityMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue()));
    }

    /**
     * Removes all coverage entries for the given requirement ID.
     */
    @Transactional
    public void deleteCoverageForRequirement(String requirementId) {
        coverageRepository.deleteByRequirementId(requirementId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Long> buildDensityMap() {
        return coverageRepository.findNodeCodeCountPairs().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }
}
