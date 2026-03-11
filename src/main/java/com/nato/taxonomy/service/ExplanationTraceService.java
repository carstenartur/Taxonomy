package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.ExplanationTrace;
import com.nato.taxonomy.dto.ExplanationTrace.ScoreComponent;
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
 * Builds structured {@link ExplanationTrace}s that explain why a specific
 * taxonomy node was selected during architecture analysis.
 *
 * <p>Unlike LLM-based free-text justifications, this service produces
 * machine-readable explanation components derived entirely from the
 * scoring data and taxonomy structure — no LLM calls required.</p>
 */
@Service
public class ExplanationTraceService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationTraceService.class);

    /** Maximum depth when walking the taxonomy hierarchy to prevent infinite loops. */
    private static final int MAX_HIERARCHY_DEPTH = 10;

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;

    public ExplanationTraceService(TaxonomyNodeRepository nodeRepository,
                                    TaxonomyRelationRepository relationRepository) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * Builds an explanation trace for a single node given the analysis context.
     *
     * @param nodeCode     the node to explain
     * @param scores       full score map from the analysis
     * @param reasons      optional map of nodeCode → reason strings from the LLM
     * @param businessText the original business requirement
     * @return structured explanation trace, or empty trace if node not found
     */
    @Transactional(readOnly = true)
    public ExplanationTrace buildTrace(String nodeCode,
                                        Map<String, Integer> scores,
                                        Map<String, String> reasons,
                                        String businessText) {
        ExplanationTrace trace = new ExplanationTrace();

        Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
        if (nodeOpt.isEmpty()) {
            trace.setSummaryText("Node not found: " + nodeCode);
            return trace;
        }

        TaxonomyNode node = nodeOpt.get();
        int score = scores != null ? scores.getOrDefault(nodeCode, 0) : 0;

        trace.setTaxonomyRoot(node.getTaxonomyRoot());

        // 1. Extract matched keywords from the business text and node name
        if (businessText != null && !businessText.isBlank()) {
            List<String> keywords = extractMatchedKeywords(businessText, node);
            trace.setMatchedKeywords(keywords);
        }

        // 2. Compute semantic score component (normalised score)
        trace.setSemanticScore(score / 100.0);

        // 3. Build relation path from node to its root
        List<String> path = buildRelationPath(node);
        trace.setRelationPath(path);

        // 4. Build score breakdown
        List<ScoreComponent> breakdown = buildScoreBreakdown(node, scores, reasons, businessText);
        trace.setScoreBreakdown(breakdown);

        // 5. Generate summary text
        trace.setSummaryText(buildSummary(node, score, trace));

        return trace;
    }

    /**
     * Builds explanation traces for all scored nodes above a threshold.
     */
    @Transactional(readOnly = true)
    public Map<String, ExplanationTrace> buildTraces(Map<String, Integer> scores,
                                                      Map<String, String> reasons,
                                                      String businessText,
                                                      int minScore) {
        if (scores == null || scores.isEmpty()) {
            return Map.of();
        }

        Map<String, ExplanationTrace> traces = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() != null && entry.getValue() >= minScore) {
                traces.put(entry.getKey(),
                        buildTrace(entry.getKey(), scores, reasons, businessText));
            }
        }
        return traces;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Extracts keywords from the business text that appear in the node's name or description.
     */
    List<String> extractMatchedKeywords(String businessText, TaxonomyNode node) {
        Set<String> nodeTokens = new HashSet<>();
        if (node.getNameEn() != null) {
            for (String token : node.getNameEn().toLowerCase(Locale.ROOT).split("\\W+")) {
                if (token.length() > 2) {
                    nodeTokens.add(token);
                }
            }
        }

        Set<String> matched = new LinkedHashSet<>();
        for (String token : businessText.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() > 2 && nodeTokens.contains(token)) {
                matched.add(token);
            }
        }

        return new ArrayList<>(matched);
    }

    /**
     * Builds the path from root to this node by walking up the taxonomy hierarchy.
     */
    List<String> buildRelationPath(TaxonomyNode node) {
        List<String> path = new ArrayList<>();
        path.add(node.getCode());

        // Walk up to parent(s) using relations
        String currentCode = node.getCode();
        Set<String> visited = new HashSet<>();
        visited.add(currentCode);

        for (int i = 0; i < MAX_HIERARCHY_DEPTH; i++) { // Safety limit
            List<TaxonomyRelation> incoming = relationRepository.findByTargetNodeCode(currentCode);
            if (incoming.isEmpty()) break;

            // Pick the first parent relation
            String parentCode = incoming.get(0).getSourceNode().getCode();
            if (visited.contains(parentCode)) break;
            visited.add(parentCode);

            path.add(0, parentCode);
            currentCode = parentCode;
        }

        return path;
    }

    /**
     * Builds a score breakdown showing which factors contribute to the node's score.
     */
    List<ScoreComponent> buildScoreBreakdown(TaxonomyNode node,
                                              Map<String, Integer> scores,
                                              Map<String, String> reasons,
                                              String businessText) {
        List<ScoreComponent> components = new ArrayList<>();
        int score = scores != null ? scores.getOrDefault(node.getCode(), 0) : 0;

        // Direct match component
        components.add(new ScoreComponent("Direct LLM Score", 1.0, score / 100.0));

        // Keyword overlap component
        if (businessText != null && !businessText.isBlank()) {
            List<String> keywords = extractMatchedKeywords(businessText, node);
            double keywordScore = Math.min(keywords.size() * 0.2, 1.0);
            components.add(new ScoreComponent("Keyword Overlap", 0.3, keywordScore));
        }

        // Hierarchy depth component (leaf nodes get more specific scores)
        double depthFactor = Math.min(node.getLevel() / 5.0, 1.0);
        components.add(new ScoreComponent("Hierarchy Depth", 0.2, depthFactor));

        // Connected nodes component (nodes with many high-scoring neighbours score higher)
        List<TaxonomyRelation> relations = relationRepository.findBySourceNodeCode(node.getCode());
        long highScoringNeighbours = relations.stream()
                .filter(r -> scores != null && scores.getOrDefault(r.getTargetNode().getCode(), 0) >= 50)
                .count();
        double contextScore = Math.min(highScoringNeighbours * 0.25, 1.0);
        components.add(new ScoreComponent("Neighbourhood Context", 0.2, contextScore));

        return components;
    }

    /**
     * Generates a human-readable summary from the structured trace components.
     */
    private String buildSummary(TaxonomyNode node, int score, ExplanationTrace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.getCode()).append(" (").append(node.getNameEn()).append(") ");
        sb.append("scored ").append(score).append("% ");

        if (!trace.getMatchedKeywords().isEmpty()) {
            sb.append("— matched keywords: ").append(String.join(", ", trace.getMatchedKeywords()));
        }

        if (trace.getRelationPath().size() > 1) {
            sb.append("; path: ").append(String.join(" → ", trace.getRelationPath()));
        }

        return sb.toString();
    }
}
