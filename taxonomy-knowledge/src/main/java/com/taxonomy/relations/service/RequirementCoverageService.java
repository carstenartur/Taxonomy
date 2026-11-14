package com.taxonomy.relations.service;

import com.taxonomy.dto.CoverageStatistics;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.RepositoryMutationFence;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import java.io.IOException;
import static com.taxonomy.relations.model.RequirementCoverage.scope;
import com.taxonomy.dto.NodeCoverageEntry;
import com.taxonomy.relations.model.RequirementCoverage;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
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

    private final RepositoryMutationFence mutations;
    private final WorkspaceDslReadPort git;

    public RequirementCoverageService(RequirementCoverageRepository coverageRepository,
                                      TaxonomyNodeRepository nodeRepository, RepositoryMutationFence mutations, WorkspaceDslReadPort git) {
        this.mutations = mutations;
        this.git = git;
        this.coverageRepository = coverageRepository;
        this.nodeRepository = nodeRepository;
    }

    public static RequirementCoverage.Source recordedSource(RepositoryContext context, String commit) {
        return new RequirementCoverage.Source(context.repositoryId(), context.workspaceId(), context.branch(),
                WorkspaceDslReadPort.normalizeCommitId(commit), context.username());
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
    public void analyzeCoverage(RepositoryContext context, String commit, Map<String, Double> scores,
                                String requirementId,
                                String requirementText,
                                int minScore) {
        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;
        Instant now = Instant.now();
        var source = recordedSource(context, commit);

        // Remove stale entries for this requirement before re-recording
        mutations.lock(context.repositoryId());
        coverageRepository.remove(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit, requirementId);

        List<RequirementCoverage> entries = scores.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= threshold)
                .map(e -> new RequirementCoverage(source, requirementId, requirementText,
                        e.getKey(), e.getValue(), now))
                .toList();

        coverageRepository.saveAllAndFlush(entries);
        verify(context, commit);
    }

    /**
     * Returns all requirement-coverage records for a given node code.
     */
    @Transactional(readOnly = true)
    public List<RequirementCoverage> getCoverageForNode(RepositoryContext context, String commit, String nodeCode) {
        return coverageRepository.forNode(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit, nodeCode);
    }

    /**
     * Returns all requirement-coverage records for a given requirement ID.
     */
    @Transactional(readOnly = true)
    public List<RequirementCoverage> getCoverageForRequirement(RepositoryContext context, String commit, String requirementId) {
        return coverageRepository.forRequirement(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit, requirementId);
    }

    /**
     * Returns aggregated coverage statistics for the whole taxonomy.
     */
    @Transactional(readOnly = true)
    public CoverageStatistics getCoverageStatistics(RepositoryContext context, String commit) {
        long totalNodes = nodeRepository.count();
        long coveredNodes = coverageRepository.coveredNodes(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit, DEFAULT_MIN_SCORE);
        long uncoveredNodes = Math.max(0, totalNodes - coveredNodes);
        double coveragePct = totalNodes > 0 ? (double) coveredNodes / totalNodes * 100.0 : 0.0;

        long totalRequirements = coverageRepository.requirements(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit);

        // Build nodeCode → requirementCount map
        Map<String, Long> densityMap = buildDensityMap(context, commit);
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
    public Map<String, Integer> getRequirementDensityMap(RepositoryContext context, String commit) {
        return buildDensityMap(context, commit).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue()));
    }

    /**
     * Removes all coverage entries for the given requirement ID.
     */
    @Transactional
    public void deleteCoverageForRequirement(RepositoryContext context, String commit, String requirementId) {
        mutations.lock(context.repositoryId());
        coverageRepository.remove(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit, requirementId);
        verify(context, commit);
    }

    /** A lost source precondition must roll the whole replacement back. */
    private void verify(RepositoryContext context, String commit) {
        try {
            git.openRead(context).verifyExpectedHead(commit);
        } catch (BranchHeadConflictException moved) {
            throw new CoverageSourceUnavailable(CoverageSourceUnavailable.State.STALE, moved.getActualHeadCommit());
        } catch (IOException failure) {
            throw new CoverageSourceUnavailable(CoverageSourceUnavailable.State.FAILED, null);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Long> buildDensityMap(RepositoryContext context, String commit) {
        return coverageRepository.density(context.repositoryId(), scope(context.workspaceId()), context.branch(), commit).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }
}
