package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.RelationHypothesisDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Generates provisional (not-yet-persisted) relation hypotheses from analysis
 * scores, using the {@link RelationCompatibilityMatrix} rules.
 *
 * <p>This service runs after LLM scoring and produces a list of
 * {@link RelationHypothesisDto} that can be shown in the UI and optionally
 * accepted into the knowledge graph by the user.
 */
@Service
public class AnalysisRelationGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRelationGenerator.class);

    /** Minimum score (0–100) for a node to be considered as a relation endpoint. */
    static final int MIN_SCORE = 50;

    private final RelationCompatibilityMatrix compatibilityMatrix;
    private final TaxonomyNodeRepository nodeRepository;

    public AnalysisRelationGenerator(RelationCompatibilityMatrix compatibilityMatrix,
                                     TaxonomyNodeRepository nodeRepository) {
        this.compatibilityMatrix = compatibilityMatrix;
        this.nodeRepository = nodeRepository;
    }

    /**
     * Generates provisional relations from scored nodes.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Select all nodes with score &ge; {@value #MIN_SCORE}</li>
     *   <li>Group them by taxonomy root</li>
     *   <li>For each pair of roots that have a compatible relation type in the
     *       {@link RelationCompatibilityMatrix}, generate candidate relations</li>
     *   <li>Compute confidence from {@code (scoreA &times; scoreB) / 10000}</li>
     *   <li>Return as DTOs sorted by confidence descending</li>
     * </ol>
     *
     * @param scores map of nodeCode → integer score (0–100) from LLM analysis
     * @return list of provisional relation hypotheses, sorted by confidence descending
     */
    @Transactional(readOnly = true)
    public List<RelationHypothesisDto> generate(Map<String, Integer> scores) {
        if (scores == null || scores.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Collect qualifying nodes (score >= MIN_SCORE), grouped by taxonomy root
        Map<String, List<ScoredNode>> nodesByRoot = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() < MIN_SCORE) {
                continue;
            }
            String code = entry.getKey();
            Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(code);
            if (nodeOpt.isEmpty()) {
                continue;
            }
            TaxonomyNode node = nodeOpt.get();
            String root = node.getTaxonomyRoot();
            if (root == null) {
                continue;
            }
            nodesByRoot.computeIfAbsent(root, k -> new ArrayList<>())
                    .add(new ScoredNode(code, node.getNameEn(), root, entry.getValue()));
        }

        if (nodesByRoot.size() < 2) {
            log.debug("Fewer than 2 taxonomy roots with qualifying nodes; no provisional relations generated.");
            return Collections.emptyList();
        }

        // 2. Generate candidates using compatibility matrix
        List<RelationHypothesisDto> hypotheses = new ArrayList<>();
        List<String> roots = new ArrayList<>(nodesByRoot.keySet());

        for (int i = 0; i < roots.size(); i++) {
            String sourceRoot = roots.get(i);
            List<ScoredNode> sourceNodes = nodesByRoot.get(sourceRoot);

            for (RelationType relationType : RelationType.values()) {
                Set<String> allowedTargets = compatibilityMatrix.allowedTargetRoots(sourceRoot, relationType);
                if (allowedTargets.isEmpty()) {
                    // Either no restrictions or no rules — skip for auto-generation
                    // to avoid noise from RELATED_TO
                    continue;
                }

                for (String targetRoot : allowedTargets) {
                    List<ScoredNode> targetNodes = nodesByRoot.get(targetRoot);
                    if (targetNodes == null) {
                        continue;
                    }

                    // Generate pairings: pick the top-scoring source × top-scoring target
                    ScoredNode bestSource = sourceNodes.stream()
                            .max(Comparator.comparingInt(n -> n.score))
                            .orElse(null);
                    ScoredNode bestTarget = targetNodes.stream()
                            .max(Comparator.comparingInt(n -> n.score))
                            .orElse(null);

                    if (bestSource == null || bestTarget == null) {
                        continue;
                    }

                    // Avoid self-relations
                    if (bestSource.code.equals(bestTarget.code)) {
                        continue;
                    }

                    double confidence = (bestSource.score * bestTarget.score) / 10000.0;
                    String reasoning = String.format(
                            "%s (%s, score %d) %s %s (%s, score %d) — inferred from compatibility matrix",
                            bestSource.name, bestSource.root, bestSource.score,
                            relationType.name(),
                            bestTarget.name, bestTarget.root, bestTarget.score);

                    hypotheses.add(new RelationHypothesisDto(
                            bestSource.code, bestSource.name,
                            bestTarget.code, bestTarget.name,
                            relationType.name(),
                            confidence,
                            reasoning));
                }
            }
        }

        // 3. Deduplicate: keep highest confidence per source+target+type triple
        Map<String, RelationHypothesisDto> deduped = new LinkedHashMap<>();
        for (RelationHypothesisDto h : hypotheses) {
            String key = h.getSourceCode() + "→" + h.getTargetCode() + ":" + h.getRelationType();
            RelationHypothesisDto existing = deduped.get(key);
            if (existing == null || h.getConfidence() > existing.getConfidence()) {
                deduped.put(key, h);
            }
        }

        // 4. Sort by confidence descending
        List<RelationHypothesisDto> result = new ArrayList<>(deduped.values());
        result.sort(Comparator.comparingDouble(RelationHypothesisDto::getConfidence).reversed());

        log.info("Generated {} provisional relation hypotheses from {} qualifying nodes across {} roots",
                result.size(), nodesByRoot.values().stream().mapToInt(List::size).sum(), nodesByRoot.size());

        return result;
    }

    /** Internal holder for a scored node with its metadata. */
    private record ScoredNode(String code, String name, String root, int score) {}
}
