package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.*;

/** Relevance ranks candidates; only an exact canonical revision establishes model membership. */
@Service
public class ArchitectureRecommendationService {
    static final int HIGH_SCORE_THRESHOLD = 70;
    private final ArchitectureGapService gaps;
    private final TaxonomyNodeRepository nodes;
    private final WorkspaceDslReadPort dslReader;
    private final LocalEmbeddingService embeddingService;
    public ArchitectureRecommendationService(ArchitectureGapService gaps, TaxonomyNodeRepository nodes,
            LocalEmbeddingService embeddingService, WorkspaceDslReadPort dslReader) {
        this.gaps = gaps; this.nodes = nodes; this.dslReader = dslReader; this.embeddingService = embeddingService;
    }
    /** A retained ranking has no acceptance authority and no implied obligations. */
    public ArchitectureRecommendation rankMatches(Map<String, Double> scores, String businessText, int minScore) {
        var result = new ArchitectureRecommendation(); result.setBusinessText(businessText);
        int threshold = minScore > 0 ? minScore : 50;
        if (scores != null) scores.entrySet().stream()
                .filter(e -> e.getValue() != null && Double.isFinite(e.getValue()) && e.getValue() >= threshold)
                .sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> nodes.findByCode(e.getKey()).ifPresent(node -> result.getMatchedElements().add(
                        new RecommendedElement(node.getCode(), node.getNameEn(), node.getTaxonomyRoot(), e.getValue(), "Relevance match; not accepted model membership"))));
        result.setMeanRelevance(result.getMatchedElements().isEmpty() ? null : result.getMatchedElements().stream().mapToDouble(RecommendedElement::getScore).average().orElseThrow());
        result.setGapAnalysis(ArchitectureGapService.compatibilityAssessment(businessText));
        result.getNotes().add("Scores rank candidates and do not establish acceptance, completeness or architectural correctness.");
        return result;
    }
    @Transactional(readOnly = true)
    public ArchitectureRecommendation recommend(RepositoryContext context, String commitId, String profileId,
            String profileVersion, EvidenceCompleteness completeness, Map<String,Double> scores,
            String businessText, int minScore) throws IOException {
        var result = rankMatches(scores, businessText, minScore);
        var assessment = gaps.analyze(context, commitId, profileId, profileVersion, completeness, businessText);
        result.setGapAnalysis(assessment);
        var read = dslReader.openRead(context);
        var model = new ArchitectureDslCommands().model(read.dslAtCommit(assessment.getCommitId())
                .orElseThrow(() -> new IllegalStateException("Selected revision has no canonical model")));
        model.getElements().forEach(e -> result.getAcceptedElements().add(new RecommendedElement(e.getId(), e.getTitle(),
                TaxonomyRootTypes.rootFor(e.getType()), 0, "Member of canonical model at " + assessment.getCommitId())));
        Set<String> members = new HashSet<>(); model.getElements().forEach(e -> members.add(e.getId()));
        Set<String> proposed = new HashSet<>();
        for (var judgment : assessment.getJudgments()) {
            if (judgment.status() != ObligationJudgment.Status.VIOLATED || judgment.observedCount() >= judgment.minCount()) continue;
            int remaining = judgment.minCount() - judgment.observedCount();
            Set<String> alreadyLinked = new HashSet<>();
            model.getRelations().stream()
                    .filter(r -> judgment.sourceNodeCode().equals(r.getSourceId()) && judgment.relationType().equals(r.getRelationType())
                            && "accepted".equals(r.getStatus()))
                    .forEach(r -> alreadyLinked.add(r.getTargetId()));
            Map<String, RecommendedElement> candidates = new LinkedHashMap<>();
            model.getElements().stream()
                    .filter(e -> !e.getId().equals(judgment.sourceNodeCode()) && !alreadyLinked.contains(e.getId()))
                    .filter(e -> TaxonomyRootTypes.rootFor(e.getType()) != null && judgment.targetRoots().contains(TaxonomyRootTypes.rootFor(e.getType())))
                    .sorted(Comparator.comparing(com.taxonomy.dsl.model.ArchitectureElement::getId))
                    .forEach(e -> candidates.put(e.getId(), new RecommendedElement(e.getId(), e.getTitle(), TaxonomyRootTypes.rootFor(e.getType()),
                            0, "Existing model element eligible for obligation " + judgment.ruleId())));
            // Canonical identities/types are authoritative. Introduce catalogue elements only
            // if unlinked, eligible existing members cannot fill the remaining cardinality.
            int suggestionLimit = Math.min(3, remaining);
            if (candidates.size() < remaining && candidates.size() < suggestionLimit) {
                var catalogue = judgment.targetRoots().stream().sorted()
                        .flatMap(root -> nodes.findByTaxonomyRootOrderByLevelAscNameEnAsc(root).stream())
                        .filter(n -> !members.contains(n.getCode()) && !alreadyLinked.contains(n.getCode()))
                        .toList();
                for (var n : rankCatalogue(catalogue, businessText, result.getNotes())) {
                    if (candidates.size() >= suggestionLimit) break;
                    candidates.putIfAbsent(n.getCode(), new RecommendedElement(n.getCode(), n.getNameEn(), n.getTaxonomyRoot(),
                            0, "Catalogue candidate for obligation " + judgment.ruleId()));
                }
            }
            for (var candidate : candidates.values().stream().limit(suggestionLimit).toList()) {
                if (!members.contains(candidate.getNodeCode()) && proposed.add(candidate.getNodeCode())) result.getProposedElements().add(candidate);
                result.getSuggestedRelations().add(new SuggestedRelation(judgment.sourceNodeCode(), candidate.getNodeCode(), judgment.relationType(),
                        "Review candidate for violated rule " + judgment.ruleId() + "; alternatives share one cardinality"));
            }
        }
        read.verifyExpectedHead(assessment.getCommitId());
        return result;
    }
    private List<TaxonomyNode> rankCatalogue(List<TaxonomyNode> candidates, String text, List<String> notes) {
        if (candidates.isEmpty()) return List.of();
        if (text != null && !text.isBlank() && embeddingService.isAvailable()) {
            try {
                var scores = embeddingService.scoreNodes(text, candidates);
                return candidates.stream().sorted(Comparator.comparingDouble(
                        (TaxonomyNode node) -> scores.getOrDefault(node.getCode(), 0d)).reversed().thenComparing(TaxonomyNode::getCode)).toList();
            } catch (Exception unavailable) {
                notes.add("Local semantic ranking failed; catalogue candidates use specificity order.");
            }
        }
        return candidates.stream().sorted(Comparator.comparingInt(TaxonomyNode::getLevel).reversed().thenComparing(TaxonomyNode::getCode)).toList();
    }
}
