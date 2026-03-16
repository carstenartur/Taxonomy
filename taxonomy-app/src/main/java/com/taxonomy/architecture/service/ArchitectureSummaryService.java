package com.taxonomy.architecture.service;

import com.taxonomy.dto.ArchitectureSummary;
import com.taxonomy.dto.ArchitectureSummary.NextStep;
import com.taxonomy.dto.ArchitectureSummary.SummaryEntry;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces a compact, actionable architecture summary.
 *
 * <p>The summary aggregates data from nodes, relations, proposals,
 * hypotheses, and coverage to provide an at-a-glance view of the
 * current architecture state, plus recommended next steps.
 */
@Service
public class ArchitectureSummaryService {

    private static final int TOP_N = 5;

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;
    private final RelationProposalRepository proposalRepository;
    private final RelationHypothesisRepository hypothesisRepository;
    private final RequirementCoverageRepository coverageRepository;

    public ArchitectureSummaryService(TaxonomyNodeRepository nodeRepository,
                                      TaxonomyRelationRepository relationRepository,
                                      RelationProposalRepository proposalRepository,
                                      RelationHypothesisRepository hypothesisRepository,
                                      RequirementCoverageRepository coverageRepository) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
        this.proposalRepository = proposalRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.coverageRepository = coverageRepository;
    }

    /**
     * Build the architecture summary from current data.
     */
    @Transactional(readOnly = true)
    public ArchitectureSummary buildSummary() {
        List<TaxonomyNode> allNodes = nodeRepository.findAll();
        int totalRelations = (int) relationRepository.count();
        int totalProposals = (int) proposalRepository.count();
        int totalHypotheses = (int) hypothesisRepository.count();

        // Coverage stats
        Map<String, Integer> coverageCounts = new HashMap<>();
        coverageRepository.findNodeCodeCountPairs().forEach(pair -> {
            String code = (String) pair[0];
            long count = (Long) pair[1];
            coverageCounts.put(code, (int) count);
        });
        int coveredNodes = coverageCounts.size();

        // Classify nodes by taxonomy root
        Map<String, List<TaxonomyNode>> byRoot = allNodes.stream()
                .filter(n -> n.getTaxonomyRoot() != null)
                .collect(Collectors.groupingBy(TaxonomyNode::getTaxonomyRoot));

        List<SummaryEntry> topCapabilities = topByRelations(byRoot.getOrDefault("CP", List.of()), coverageCounts);
        List<SummaryEntry> topProcesses = topByRelations(byRoot.getOrDefault("BP", List.of()), coverageCounts);
        List<SummaryEntry> topServices = topByRelations(byRoot.getOrDefault("CR", List.of()), coverageCounts);

        // Hub nodes
        List<SummaryEntry> hubNodes = allNodes.stream()
                .filter(n -> "hub".equals(n.getGraphRole()))
                .sorted(Comparator.comparingInt(TaxonomyNode::getTotalRelationCount).reversed())
                .limit(TOP_N)
                .map(n -> new SummaryEntry(n.getCode(), n.getNameEn(), n.getTotalRelationCount()))
                .toList();

        // Gaps: nodes with no coverage
        List<String> gaps = allNodes.stream()
                .filter(n -> n.getLevel() > 1)
                .filter(n -> !coverageCounts.containsKey(n.getCode()))
                .filter(n -> n.getTotalRelationCount() > 0)
                .sorted(Comparator.comparingInt(TaxonomyNode::getTotalRelationCount).reversed())
                .limit(TOP_N)
                .map(n -> n.getCode() + " (" + n.getNameEn() + ")")
                .toList();

        // Next steps
        List<NextStep> nextSteps = buildNextSteps(totalRelations, totalProposals, totalHypotheses, coveredNodes);

        return new ArchitectureSummary(
                Instant.now(),
                allNodes.size(),
                totalRelations,
                totalProposals,
                totalHypotheses,
                coveredNodes,
                topCapabilities,
                topProcesses,
                topServices,
                gaps,
                hubNodes,
                nextSteps
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private List<SummaryEntry> topByRelations(List<TaxonomyNode> nodes, Map<String, Integer> coverageCounts) {
        return nodes.stream()
                .sorted(Comparator.comparingInt((TaxonomyNode n) ->
                        n.getTotalRelationCount() + coverageCounts.getOrDefault(n.getCode(), 0)).reversed())
                .limit(TOP_N)
                .map(n -> new SummaryEntry(
                        n.getCode(),
                        n.getNameEn(),
                        n.getTotalRelationCount() + coverageCounts.getOrDefault(n.getCode(), 0)))
                .toList();
    }

    private List<NextStep> buildNextSteps(int relations, int proposals, int hypotheses, int covered) {
        List<NextStep> steps = new ArrayList<>();

        if (relations == 0) {
            steps.add(new NextStep("Import Relations",
                    "No relations found. Import or create relations to enable graph analysis.",
                    "/api/relations"));
        }

        if (proposals > 0) {
            steps.add(new NextStep("Review Proposals",
                    proposals + " pending relation proposal(s). Accept or reject them to improve the architecture graph.",
                    "/api/proposals"));
        }

        if (hypotheses > 0) {
            String noun = hypotheses == 1 ? "hypothesis" : "hypotheses";
            steps.add(new NextStep("Evaluate Hypotheses",
                    hypotheses + " " + noun + " waiting for review.",
                    "/api/dsl/hypotheses"));
        }

        if (covered == 0) {
            steps.add(new NextStep("Analyze Requirements",
                    "No requirement coverage found. Analyze a business requirement to map it against the taxonomy.",
                    "/api/analyze"));
        }

        steps.add(new NextStep("Explore Graph",
                "Use the graph explorer to visualize upstream/downstream dependencies.",
                "/api/graph"));

        steps.add(new NextStep("Export Architecture",
                "Export the current architecture as ArchiMate, Mermaid, or Visio diagram.",
                "/api/report"));

        return steps;
    }
}
