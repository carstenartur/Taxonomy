package com.taxonomy.architecture.service;

import com.taxonomy.dto.ArchitectureSummary;
import com.taxonomy.dto.ArchitectureSummary.NextStep;
import com.taxonomy.dto.ArchitectureSummary.SummaryEntry;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.NodeGraphMetadata;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RequirementCoverageService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.relations.service.CoverageSourceUnavailable;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import java.io.IOException;
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
    private final RelationProjectionReadService relations;
    private final RelationProposalRepository proposalRepository;
    private final RelationHypothesisRepository hypothesisRepository;
    private final RequirementCoverageService coverage;
    private final WorkspaceDslReadPort git;

    public ArchitectureSummaryService(TaxonomyNodeRepository nodeRepository,
                                      RelationProjectionReadService relations,
                                      RelationProposalRepository proposalRepository,
                                      RelationHypothesisRepository hypothesisRepository,
                                      RequirementCoverageService coverage, WorkspaceDslReadPort git) {
        this.git = git;
        this.nodeRepository = nodeRepository;
        this.relations = relations;
        this.proposalRepository = proposalRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.coverage = coverage;
    }

    private record Graph(String head, Map<String,Integer> incoming, Map<String,Integer> outgoing,
                         Map<String,Integer> coverage, int relations, WorkspaceDslReadPort.RepositoryRead read) {
        int degree(TaxonomyNode node) { return incoming.getOrDefault(node.getCode(), 0) + outgoing.getOrDefault(node.getCode(), 0); }
        NodeGraphMetadata metadata(String code) {
            int in = incoming.getOrDefault(code, 0), out = outgoing.getOrDefault(code, 0);
            return new NodeGraphMetadata(code, in, out, in + out, coverage.getOrDefault(code, 0), DerivedMetadataService.classifyRole(in, out));
        }
        void verify() {
            try { read.verifyExpectedHead(head); }
            catch (BranchHeadConflictException moved) { throw new CoverageSourceUnavailable(CoverageSourceUnavailable.State.STALE, moved.getActualHeadCommit()); }
            catch (IOException failure) { throw new CoverageSourceUnavailable(CoverageSourceUnavailable.State.FAILED, null); }
        }
    }

    private Graph graph(RepositoryContext context) {
        var read = git.openRead(context);
        String head;
        try { head = read.currentHead(); } catch (IOException failure) {
            throw new CoverageSourceUnavailable(CoverageSourceUnavailable.State.FAILED, null);
        }
        if (head == null) throw new CoverageSourceUnavailable(CoverageSourceUnavailable.State.MISSING, null);
        RelationProjectionReadService.Snapshot snapshot;
        try { snapshot = relations.readSnapshot(context, head); }
        catch (RelationProjectionReadService.RelationProjectionUnavailableException unavailable) {
            var state = switch (unavailable.getReadinessState()) {
                case BRANCH_MISSING -> CoverageSourceUnavailable.State.MISSING;
                case NOT_BUILT, STALE, READY -> CoverageSourceUnavailable.State.STALE;
                case CORRUPT -> CoverageSourceUnavailable.State.FAILED;
            };
            throw new CoverageSourceUnavailable(state, unavailable.getCurrentHeadCommit());
        }
        Map<String,Integer> incoming = new HashMap<>(), outgoing = new HashMap<>();
        for (var relation : snapshot.relations()) {
            incoming.merge(relation.targetCode(), 1, Integer::sum);
            outgoing.merge(relation.sourceCode(), 1, Integer::sum);
        }
        return new Graph(head, incoming, outgoing, coverage.getRequirementDensityMap(context, head), snapshot.relations().size(), read);
    }

    @Transactional(readOnly = true)
    public NodeGraphMetadata nodeMetadata(RepositoryContext context, String code) {
        var graph = graph(context);
        var metadata = graph.metadata(code);
        graph.verify();
        return metadata;
    }

    @Transactional(readOnly = true)
    public ArchitectureSummary buildSummary(RepositoryContext context) {
        var graph = graph(context);
        List<TaxonomyNode> allNodes = nodeRepository.findAll();
        int totalRelations = graph.relations();
        int totalProposals = Math.toIntExact(proposalRepository.countByRepositoryIdAndWorkspaceId(context.repositoryId(), context.workspaceId()));
        int totalHypotheses = Math.toIntExact(hypothesisRepository.countVisibleInRepositoryWorkspaceAndBranch(
                context.repositoryId(), context.workspaceId(), context.branch()));
        Map<String,Integer> coverageCounts = graph.coverage();
        int coveredNodes = coverageCounts.size();

        // Classify nodes by taxonomy root
        Map<String, List<TaxonomyNode>> byRoot = allNodes.stream()
                .filter(n -> n.getTaxonomyRoot() != null)
                .collect(Collectors.groupingBy(TaxonomyNode::getTaxonomyRoot));

        List<SummaryEntry> topCapabilities = topByRelations(byRoot.getOrDefault("CP", List.of()), graph);
        List<SummaryEntry> topProcesses = topByRelations(byRoot.getOrDefault("BP", List.of()), graph);
        List<SummaryEntry> topServices = topByRelations(byRoot.getOrDefault("CR", List.of()), graph);

        // Hub nodes
        List<SummaryEntry> hubNodes = allNodes.stream()
                .filter(n -> "hub".equals(graph.metadata(n.getCode()).graphRole()))
                .sorted(Comparator.comparingInt(graph::degree).reversed())
                .limit(TOP_N)
                .map(n -> new SummaryEntry(n.getCode(), n.getNameEn(), graph.degree(n)))
                .toList();

        // Gaps: nodes with no coverage
        List<String> gaps = allNodes.stream()
                .filter(n -> n.getLevel() > 1)
                .filter(n -> !coverageCounts.containsKey(n.getCode()))
                .filter(n -> graph.degree(n) > 0)
                .sorted(Comparator.comparingInt(graph::degree).reversed())
                .limit(TOP_N)
                .map(n -> n.getCode() + " (" + n.getNameEn() + ")")
                .toList();

        // Next steps
        List<NextStep> nextSteps = buildNextSteps(totalRelations, totalProposals, totalHypotheses, coveredNodes);

        graph.verify();
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

    private List<SummaryEntry> topByRelations(List<TaxonomyNode> nodes, Graph graph) {
        return nodes.stream()
                .sorted(Comparator.comparingInt((TaxonomyNode n) ->
                        graph.degree(n) + graph.coverage().getOrDefault(n.getCode(), 0)).reversed())
                .limit(TOP_N)
                .map(n -> new SummaryEntry(
                        n.getCode(),
                        n.getNameEn(),
                        graph.degree(n) + graph.coverage().getOrDefault(n.getCode(), 0)))
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
