package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.MissingRelation;
import com.taxonomy.dto.RecommendedElement;
import com.taxonomy.dto.SuggestedRelation;
import com.taxonomy.shared.service.LocalEmbeddingService;

/**
 * Combines requirement scoring, gap analysis, and semantic search
 * to produce architecture recommendations for a business requirement.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Identify confirmed elements from high scores</li>
 *   <li>Run gap analysis to find missing architectural links</li>
 *   <li>For each gap, propose candidate nodes from the missing taxonomy root</li>
 *   <li>Suggest relations to fill the gaps</li>
 * </ol>
 */
@Service
public class ArchitectureRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureRecommendationService.class);

    private static final int HIGH_SCORE_THRESHOLD = 70;
    private static final int DEFAULT_MIN_SCORE = 50;
    private static final int MAX_PROPOSALS_PER_GAP = 3;

    private final ArchitectureGapService gapService;
    private final TaxonomyNodeRepository nodeRepository;
    private final LocalEmbeddingService embeddingService;

    public ArchitectureRecommendationService(ArchitectureGapService gapService,
                                              TaxonomyNodeRepository nodeRepository,
                                              LocalEmbeddingService embeddingService) {
        this.gapService = gapService;
        this.nodeRepository = nodeRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * Produces architecture recommendations for a business requirement.
     *
     * @param scores       map of nodeCode → score (0–100)
     * @param businessText the original business requirement text
     * @param minScore     minimum score threshold; 0 means default (50)
     * @return architecture recommendation with confirmed, proposed elements, and suggested relations
     */
    @Transactional(readOnly = true)
    public ArchitectureRecommendation recommend(Map<String, Integer> scores,
                                                 String businessText, int minScore) {
        ArchitectureRecommendation rec = new ArchitectureRecommendation();
        rec.setBusinessText(businessText);

        if (scores == null || scores.isEmpty()) {
            rec.getNotes().add("No scores provided; recommendations cannot be generated.");
            return rec;
        }

        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;

        // Step 1: Identify confirmed elements (high score)
        List<RecommendedElement> confirmed = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() != null && entry.getValue() >= HIGH_SCORE_THRESHOLD) {
                Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(entry.getKey());
                if (nodeOpt.isPresent()) {
                    TaxonomyNode node = nodeOpt.get();
                    confirmed.add(new RecommendedElement(
                            node.getCode(),
                            node.getNameEn(),
                            node.getTaxonomyRoot(),
                            entry.getValue(),
                            "High-confidence match (score " + entry.getValue() + ")"));
                }
            }
        }
        rec.setConfirmedElements(confirmed);
        rec.getReasoning().add("Identified " + confirmed.size()
                + " confirmed elements with score >= " + HIGH_SCORE_THRESHOLD);

        // Step 2: Run gap analysis
        GapAnalysisView gaps = gapService.analyze(scores, businessText, threshold);
        rec.getReasoning().add("Gap analysis found " + gaps.getMissingRelations().size()
                + " missing relations");

        // Step 3: For each missing relation, propose candidates
        List<RecommendedElement> proposed = new ArrayList<>();
        List<SuggestedRelation> suggestedRelations = new ArrayList<>();
        Set<String> proposedCodes = new HashSet<>();

        for (MissingRelation missing : gaps.getMissingRelations()) {
            String targetRoot = missing.getExpectedTargetRoot();
            String sourceCode = missing.getSourceNodeCode();

            // Find candidate nodes from the target taxonomy root
            List<TaxonomyNode> candidates = nodeRepository.findByTaxonomyRootOrderByLevelAscNameEnAsc(targetRoot);

            // If embedding service is available, score candidates semantically
            List<TaxonomyNode> ranked;
            if (embeddingService.isAvailable() && businessText != null && !businessText.isBlank()) {
                ranked = rankByEmbeddingSimilarity(candidates, businessText);
            } else {
                // Fallback: use first N candidates sorted by level (leaf nodes are more specific)
                ranked = candidates.stream()
                        .sorted(Comparator.comparingInt(TaxonomyNode::getLevel).reversed())
                        .limit(MAX_PROPOSALS_PER_GAP)
                        .collect(Collectors.toList());
            }

            int added = 0;
            for (TaxonomyNode candidate : ranked) {
                if (added >= MAX_PROPOSALS_PER_GAP) break;
                if (proposedCodes.contains(candidate.getCode())) continue;

                proposedCodes.add(candidate.getCode());
                proposed.add(new RecommendedElement(
                        candidate.getCode(),
                        candidate.getNameEn(),
                        candidate.getTaxonomyRoot(),
                        0, // No score — this is a proposal
                        "Proposed to fill gap: " + missing.getDescription()));

                suggestedRelations.add(new SuggestedRelation(
                        sourceCode,
                        candidate.getCode(),
                        missing.getExpectedRelationType(),
                        "Would complete " + missing.getSourceRoot() + " → "
                                + missing.getExpectedRelationType() + " → " + targetRoot));
                added++;
            }
        }

        rec.setProposedElements(proposed);
        rec.setSuggestedRelations(suggestedRelations);
        rec.getReasoning().add("Proposed " + proposed.size() + " elements and "
                + suggestedRelations.size() + " relations to fill gaps");

        // Step 4: Compute confidence
        int totalExpected = confirmed.size() + gaps.getMissingRelations().size();
        double confidence = totalExpected > 0
                ? (double) confirmed.size() / totalExpected * 100.0
                : 0.0;
        rec.setConfidence(Math.round(confidence * 100.0) / 100.0);
        rec.getReasoning().add("Overall confidence: " + rec.getConfidence()
                + "% (" + confirmed.size() + " confirmed / " + totalExpected + " expected)");

        log.info("Recommendation: {} confirmed, {} proposed, {} suggested relations, confidence={}%",
                confirmed.size(), proposed.size(), suggestedRelations.size(), rec.getConfidence());

        return rec;
    }

    /**
     * Ranks candidate nodes by semantic similarity to the business text
     * using the local embedding service.
     */
    private List<TaxonomyNode> rankByEmbeddingSimilarity(List<TaxonomyNode> candidates,
                                                          String businessText) {
        try {
            Map<String, Integer> candidateScores = embeddingService.scoreNodes(
                    businessText, candidates);

            return candidates.stream()
                    .sorted(Comparator.comparingInt(
                            (TaxonomyNode n) -> candidateScores.getOrDefault(n.getCode(), 0)).reversed())
                    .limit(MAX_PROPOSALS_PER_GAP)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Embedding ranking failed, using fallback: {}", e.getMessage());
            return candidates.stream()
                    .sorted(Comparator.comparingInt(TaxonomyNode::getLevel).reversed())
                    .limit(MAX_PROPOSALS_PER_GAP)
                    .collect(Collectors.toList());
        }
    }
}
