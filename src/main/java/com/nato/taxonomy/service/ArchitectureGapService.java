package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.*;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.model.TaxonomyRelation;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
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
}
