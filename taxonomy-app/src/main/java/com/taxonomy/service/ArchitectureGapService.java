package com.taxonomy.service;

import com.taxonomy.dto.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.model.TaxonomyRelation;
import com.taxonomy.repository.TaxonomyNodeRepository;
import com.taxonomy.repository.TaxonomyRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses architectural gaps by comparing expected relations (from the
 * {@link RelationCompatibilityMatrix}) with actual relations in the repository.
 *
 * <p>For each anchor node (high-scoring node from a requirement analysis),
 * this service checks which relation types <em>should</em> exist according to
 * the compatibility matrix and reports any that are missing.
 */
@Service
public class ArchitectureGapService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureGapService.class);

    private static final int DEFAULT_MIN_SCORE = 50;

    private final RelationCompatibilityMatrix compatibilityMatrix;
    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;

    public ArchitectureGapService(RelationCompatibilityMatrix compatibilityMatrix,
                                  TaxonomyNodeRepository nodeRepository,
                                  TaxonomyRelationRepository relationRepository) {
        this.compatibilityMatrix = compatibilityMatrix;
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * Performs a gap analysis for the given scores and business text.
     *
     * @param scores       map of nodeCode → score (0–100)
     * @param businessText the original business requirement text
     * @param minScore     minimum score threshold; 0 means default (50)
     * @return gap analysis view with missing relations, incomplete patterns, and coverage gaps
     */
    @Transactional(readOnly = true)
    public GapAnalysisView analyze(Map<String, Integer> scores, String businessText, int minScore) {
        GapAnalysisView view = new GapAnalysisView();
        view.setBusinessText(businessText);

        if (scores == null || scores.isEmpty()) {
            view.getNotes().add("No scores provided; gap analysis cannot be performed.");
            return view;
        }

        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;

        // Filter to anchor nodes above threshold
        Map<String, Integer> anchors = scores.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= threshold)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        view.setTotalAnchors(anchors.size());

        if (anchors.isEmpty()) {
            view.getNotes().add("No nodes above the score threshold of " + threshold + ".");
            return view;
        }

        List<MissingRelation> missingRelations = new ArrayList<>();
        List<IncompletePattern> incompletePatterns = new ArrayList<>();
        List<CoverageGap> coverageGaps = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : anchors.entrySet()) {
            String nodeCode = entry.getKey();
            int score = entry.getValue();

            Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
            if (nodeOpt.isEmpty()) {
                continue;
            }

            TaxonomyNode node = nodeOpt.get();
            String sourceRoot = node.getTaxonomyRoot();
            if (sourceRoot == null || sourceRoot.isBlank()) {
                continue;
            }

            // Get expected outgoing relation types for this taxonomy root
            Map<RelationType, Set<String>> expected =
                    compatibilityMatrix.getExpectedOutgoingRelations(sourceRoot);

            // Get actual outgoing relations for this specific node
            List<TaxonomyRelation> actualOutgoing = relationRepository.findBySourceNodeCode(nodeCode);
            Set<String> actualRelTypeNames = actualOutgoing.stream()
                    .map(r -> r.getRelationType().name())
                    .collect(Collectors.toSet());

            // Build set of actual target roots per relation type
            Map<String, Set<String>> actualTargetRootsByType = new HashMap<>();
            for (TaxonomyRelation rel : actualOutgoing) {
                String targetRoot = rel.getTargetNode() != null
                        ? rel.getTargetNode().getTaxonomyRoot() : null;
                if (targetRoot != null) {
                    actualTargetRootsByType
                            .computeIfAbsent(rel.getRelationType().name(), k -> new HashSet<>())
                            .add(targetRoot);
                }
            }

            boolean hasAnyGap = false;
            for (Map.Entry<RelationType, Set<String>> expectedEntry : expected.entrySet()) {
                RelationType relType = expectedEntry.getKey();
                Set<String> expectedTargets = expectedEntry.getValue();

                if (!actualRelTypeNames.contains(relType.name())) {
                    // No relation of this type exists at all
                    for (String targetRoot : expectedTargets) {
                        missingRelations.add(new MissingRelation(
                                nodeCode, sourceRoot, relType.name(), targetRoot,
                                nodeCode + " (" + sourceRoot + ") has no "
                                        + relType.name() + " relation to any " + targetRoot + " node"));
                    }
                    incompletePatterns.add(new IncompletePattern(
                            nodeCode, sourceRoot,
                            sourceRoot + " → " + relType.name() + " → "
                                    + String.join("/", expectedTargets),
                            "No " + relType.name() + " relation exists"));
                    hasAnyGap = true;
                } else {
                    // Relation type exists — check if all expected target roots are covered
                    Set<String> actualRoots = actualTargetRootsByType
                            .getOrDefault(relType.name(), Set.of());
                    for (String targetRoot : expectedTargets) {
                        if (!actualRoots.contains(targetRoot)) {
                            missingRelations.add(new MissingRelation(
                                    nodeCode, sourceRoot, relType.name(), targetRoot,
                                    nodeCode + " has " + relType.name()
                                            + " but not to a " + targetRoot + " node"));
                            hasAnyGap = true;
                        }
                    }
                }
            }

            if (hasAnyGap) {
                coverageGaps.add(new CoverageGap(
                        nodeCode, sourceRoot, score,
                        "Node has coverage (score " + score
                                + ") but is missing expected architectural relations"));
            }
        }

        view.setMissingRelations(missingRelations);
        view.setIncompletePatterns(incompletePatterns);
        view.setCoverageGaps(coverageGaps);
        view.setTotalGaps(missingRelations.size());

        log.info("Gap analysis: {} anchors, {} missing relations, {} incomplete patterns, {} coverage gaps",
                anchors.size(), missingRelations.size(), incompletePatterns.size(), coverageGaps.size());

        return view;
    }

    /**
     * Analyses which APQC process categories are covered by the current architecture model.
     *
     * <p>Searches for nodes whose code patterns suggest APQC provenance (imported
     * via the APQC pipeline) and checks whether they have outgoing relations,
     * indicating integration with the broader architecture.
     *
     * @param requirementText optional business requirement text for context
     * @return APQC coverage result with per-level statistics
     */
    @Transactional(readOnly = true)
    public ApqcCoverageResult analyzeApqcCoverage(String requirementText) {
        // Find all relations to check for APQC-sourced nodes
        List<TaxonomyRelation> allRelations = relationRepository.findAll();

        // Check relations for APQC provenance
        List<TaxonomyRelation> apqcRelations = allRelations.stream()
                .filter(r -> "APQC_IMPORT".equals(r.getProvenance()) ||
                             "dsl-materialize".equals(r.getProvenance()) ||
                             (r.getDescription() != null && r.getDescription().toLowerCase().contains("apqc")))
                .toList();

        // Identify unique APQC-related node codes from source and target
        Set<String> apqcNodeCodes = new LinkedHashSet<>();
        for (TaxonomyRelation rel : apqcRelations) {
            if (rel.getSourceNode() != null) apqcNodeCodes.add(rel.getSourceNode().getCode());
            if (rel.getTargetNode() != null) apqcNodeCodes.add(rel.getTargetNode().getCode());
        }

        // Group by taxonomy root (which maps to APQC levels via the import profile)
        Map<String, Integer> coverageByLevel = new LinkedHashMap<>();
        // APQC mapping: CP=Category, BP=ProcessGroup, CR=Process, CI=Activity, BR=Task
        Map<String, String> rootToLevel = Map.of(
                "CP", "Category", "BP", "ProcessGroup", "CR", "Process",
                "CI", "Activity", "BR", "Task");

        for (String code : apqcNodeCodes) {
            Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(code);
            nodeOpt.ifPresent(node -> {
                String level = rootToLevel.getOrDefault(node.getTaxonomyRoot(), "Unknown");
                coverageByLevel.merge(level, 1, Integer::sum);
            });
        }

        // Count categories that have at least one relation (covered)
        int totalCategories = coverageByLevel.values().stream().mapToInt(Integer::intValue).sum();
        Set<String> coveredRoots = new LinkedHashSet<>();
        for (TaxonomyRelation rel : apqcRelations) {
            if (rel.getSourceNode() != null) coveredRoots.add(rel.getSourceNode().getTaxonomyRoot());
            if (rel.getTargetNode() != null) coveredRoots.add(rel.getTargetNode().getTaxonomyRoot());
        }

        // Count categories with outgoing relations as "covered"
        int coveredCategories = 0;
        List<String> uncoveredCategories = new ArrayList<>();
        for (String levelName : rootToLevel.values()) {
            int count = coverageByLevel.getOrDefault(levelName, 0);
            if (count > 0) {
                coveredCategories++;
            } else {
                uncoveredCategories.add(levelName);
            }
        }

        double coveragePercent = rootToLevel.size() > 0
                ? (coveredCategories * 100.0) / rootToLevel.size()
                : 0.0;

        log.info("APQC coverage analysis: {} categories, {} covered, {}%",
                totalCategories, coveredCategories, String.format("%.1f", coveragePercent));

        return new ApqcCoverageResult(
                totalCategories,
                coveredCategories,
                coveragePercent,
                uncoveredCategories,
                coverageByLevel);
    }
}
