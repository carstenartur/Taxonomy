package com.taxonomy.service;

import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.PatternDetectionView;
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
 * Detects standard architecture patterns by checking whether defined
 * relation chains exist from a given starting node.
 *
 * <p>Pattern templates define expected chains of
 * {@code sourceRoot → relationType → targetRoot} steps. The service
 * traverses the graph from a starting node and checks whether each step
 * in a pattern can be fulfilled.
 */
@Service
public class ArchitecturePatternService {

    private static final Logger log = LoggerFactory.getLogger(ArchitecturePatternService.class);

    private static final int DEFAULT_MIN_SCORE = 50;

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;

    /** Pre-defined pattern templates. */
    private final List<PatternTemplate> patterns;

    public ArchitecturePatternService(TaxonomyNodeRepository nodeRepository,
                                       TaxonomyRelationRepository relationRepository) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
        this.patterns = initPatterns();
    }

    // ── Pattern Templates ──────────────────────────────────────────────────

    /**
     * A single step in a pattern: source root → relation type → target root.
     */
    record PatternStep(String sourceRoot, String relationType, String targetRoot) {
        @Override
        public String toString() {
            return sourceRoot + " → " + relationType + " → " + targetRoot;
        }
    }

    /**
     * A named sequence of steps.
     */
    record PatternTemplate(String name, List<PatternStep> steps) {}

    private List<PatternTemplate> initPatterns() {
        return List.of(
                new PatternTemplate("Full Stack", List.of(
                        new PatternStep("CP", "REALIZES", "CR"),
                        new PatternStep("CR", "SUPPORTS", "BP"),
                        new PatternStep("BP", "CONSUMES", "IP")
                )),
                new PatternTemplate("App Chain", List.of(
                        new PatternStep("UA", "USES", "CR"),
                        new PatternStep("CR", "SUPPORTS", "BP")
                )),
                new PatternTemplate("Role Chain", List.of(
                        new PatternStep("BR", "ASSIGNED_TO", "BP"),
                        new PatternStep("BP", "CONSUMES", "IP")
                ))
        );
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Detects patterns starting from a specific node.
     *
     * @param nodeCode the starting node code
     * @return pattern detection view
     */
    @Transactional(readOnly = true)
    public PatternDetectionView detectForNode(String nodeCode) {
        PatternDetectionView view = new PatternDetectionView();
        view.setNodeCode(nodeCode);

        Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
        if (nodeOpt.isEmpty()) {
            view.getNotes().add("Node not found: " + nodeCode);
            return view;
        }

        TaxonomyNode node = nodeOpt.get();
        String nodeRoot = node.getTaxonomyRoot();

        List<DetectedPattern> matched = new ArrayList<>();
        List<DetectedPattern> incomplete = new ArrayList<>();

        for (PatternTemplate pattern : patterns) {
            // Only check patterns whose first step starts from this node's root
            if (!pattern.steps().isEmpty()
                    && pattern.steps().get(0).sourceRoot().equals(nodeRoot)) {
                DetectedPattern detected = checkPattern(nodeCode, pattern);
                if (detected.getCompleteness() >= 100.0) {
                    matched.add(detected);
                } else if (detected.getCompleteness() > 0.0) {
                    incomplete.add(detected);
                }
            }
        }

        view.setMatchedPatterns(matched);
        view.setIncompletePatterns(incomplete);

        int totalPatterns = matched.size() + incomplete.size();
        view.setPatternCoverage(totalPatterns > 0
                ? matched.size() * 100.0 / totalPatterns
                : 0.0);

        log.info("Pattern detection for {}: {} matched, {} incomplete",
                nodeCode, matched.size(), incomplete.size());

        return view;
    }

    /**
     * Detects patterns across all anchor nodes from scored results.
     *
     * @param scores       map of nodeCode → score (0–100)
     * @param minScore     minimum score threshold
     * @return pattern detection view aggregated across all anchors
     */
    @Transactional(readOnly = true)
    public PatternDetectionView detectForScores(Map<String, Integer> scores, int minScore) {
        PatternDetectionView view = new PatternDetectionView();

        if (scores == null || scores.isEmpty()) {
            view.getNotes().add("No scores provided; pattern detection cannot be performed.");
            return view;
        }

        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;

        List<DetectedPattern> allMatched = new ArrayList<>();
        List<DetectedPattern> allIncomplete = new ArrayList<>();
        Set<String> seenPatterns = new HashSet<>();

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < threshold) continue;

            PatternDetectionView nodeView = detectForNode(entry.getKey());

            for (DetectedPattern p : nodeView.getMatchedPatterns()) {
                String key = p.getPatternName() + "|" + String.join(",", p.getPresentSteps());
                if (seenPatterns.add(key)) {
                    allMatched.add(p);
                }
            }
            for (DetectedPattern p : nodeView.getIncompletePatterns()) {
                String key = p.getPatternName() + "|" + String.join(",", p.getPresentSteps());
                if (seenPatterns.add(key)) {
                    allIncomplete.add(p);
                }
            }
        }

        view.setMatchedPatterns(allMatched);
        view.setIncompletePatterns(allIncomplete);

        int totalPatterns = allMatched.size() + allIncomplete.size();
        view.setPatternCoverage(totalPatterns > 0
                ? allMatched.size() * 100.0 / totalPatterns
                : 0.0);

        log.info("Pattern detection for scores: {} matched, {} incomplete across {} anchors",
                allMatched.size(), allIncomplete.size(), scores.size());

        return view;
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private DetectedPattern checkPattern(String startNodeCode, PatternTemplate pattern) {
        List<String> expectedSteps = pattern.steps().stream()
                .map(PatternStep::toString)
                .collect(Collectors.toList());

        List<String> presentSteps = new ArrayList<>();
        List<String> missingSteps = new ArrayList<>();

        // Track current set of node codes at each traversal step
        Set<String> currentNodes = Set.of(startNodeCode);

        for (PatternStep step : pattern.steps()) {
            Set<String> nextNodes = new HashSet<>();
            boolean stepFound = false;

            for (String nodeCode : currentNodes) {
                List<TaxonomyRelation> outgoing = relationRepository.findBySourceNodeCode(nodeCode);
                for (TaxonomyRelation rel : outgoing) {
                    if (rel.getRelationType().name().equals(step.relationType())
                            && rel.getTargetNode() != null
                            && step.targetRoot().equals(rel.getTargetNode().getTaxonomyRoot())) {
                        nextNodes.add(rel.getTargetNode().getCode());
                        stepFound = true;
                    }
                }
            }

            if (stepFound) {
                presentSteps.add(step.toString());
            } else {
                missingSteps.add(step.toString());
            }

            currentNodes = nextNodes.isEmpty() ? currentNodes : nextNodes;
        }

        double completeness = expectedSteps.isEmpty() ? 0.0
                : (double) presentSteps.size() / expectedSteps.size() * 100.0;

        return new DetectedPattern(pattern.name(), expectedSteps,
                presentSteps, missingSteps, completeness);
    }
}
