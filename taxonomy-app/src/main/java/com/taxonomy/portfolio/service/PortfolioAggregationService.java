package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos.AggregatedTaxonomyNode;
import com.taxonomy.portfolio.dto.PortfolioDtos.ConflictView;
import com.taxonomy.portfolio.dto.PortfolioDtos.MatrixView;
import com.taxonomy.portfolio.dto.PortfolioDtos.PortfolioMetrics;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectPortfolioView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.RequirementElementMapping;
import com.taxonomy.portfolio.model.RequirementSolutionLink;
import com.taxonomy.portfolio.model.SolutionProductCandidate;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.portfolio.repository.RequirementSolutionLinkRepository;
import com.taxonomy.portfolio.repository.SolutionProductCandidateRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Builds deduplicated project views without losing requirement and snapshot provenance. */
@Service
public class PortfolioAggregationService {

    private final ProjectPortfolioService projectService;
    private final SolutionPortfolioService solutionService;
    private final ProjectConflictService conflictService;
    private final RequirementElementMappingRepository elementRepository;
    private final RequirementSolutionLinkRepository requirementSolutionRepository;
    private final SolutionProductCandidateRepository productCandidateRepository;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final int staleAfterDays;

    public PortfolioAggregationService(ProjectPortfolioService projectService,
                                       SolutionPortfolioService solutionService,
                                       ProjectConflictService conflictService,
                                       RequirementElementMappingRepository elementRepository,
                                       RequirementSolutionLinkRepository requirementSolutionRepository,
                                       SolutionProductCandidateRepository productCandidateRepository,
                                       RequirementAnalysisSnapshotRepository snapshotRepository,
                                       @Value("${taxonomy.portfolio.snapshot-stale-after-days:30}")
                                       int staleAfterDays) {
        this.projectService = projectService;
        this.solutionService = solutionService;
        this.conflictService = conflictService;
        this.elementRepository = elementRepository;
        this.requirementSolutionRepository = requirementSolutionRepository;
        this.productCandidateRepository = productCandidateRepository;
        this.snapshotRepository = snapshotRepository;
        this.staleAfterDays = Math.max(1, staleAfterDays);
    }

    @Transactional(readOnly = true)
    public ProjectPortfolioView build(Long projectId,
                                      String username,
                                      WorkspaceContext context) {
        ProjectView project = projectService.getProject(projectId, username, context);
        List<RequirementView> requirements = projectService.listRequirements(projectId, username, context);
        List<RequirementElementMapping> mappings = elementRepository.findCurrentMappingsForProject(projectId);
        List<ProjectSolutionView> solutions = solutionService.listProjectSolutions(
                projectId, username, context);
        List<ConflictView> conflicts = conflictService.list(projectId, username, context);
        List<RequirementSolutionLink> solutionLinks = requirementSolutionRepository.findByProjectId(projectId);
        List<SolutionProductCandidate> productCandidates = productCandidateRepository.findByProjectId(projectId);

        List<AggregatedTaxonomyNode> taxonomyNodes = aggregateTaxonomy(mappings);
        MatrixView requirementTaxonomy = requirementTaxonomyMatrix(requirements, mappings);
        MatrixView requirementSolution = requirementSolutionMatrix(requirements, solutions, solutionLinks);
        MatrixView solutionProduct = solutionProductMatrix(solutions, productCandidates);
        PortfolioMetrics metrics = metrics(
                requirements, solutions, conflicts, solutionLinks, productCandidates);

        return new ProjectPortfolioView(
                project,
                metrics,
                requirements,
                taxonomyNodes,
                solutions,
                conflicts,
                requirementTaxonomy,
                requirementSolution,
                solutionProduct);
    }

    private List<AggregatedTaxonomyNode> aggregateTaxonomy(
            List<RequirementElementMapping> mappings) {
        Map<String, List<RequirementElementMapping>> byNode = mappings.stream()
                .collect(Collectors.groupingBy(
                        RequirementElementMapping::getNodeCode,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return byNode.entrySet().stream()
                .map(entry -> {
                    List<RequirementElementMapping> nodeMappings = entry.getValue();
                    RequirementElementMapping first = nodeMappings.get(0);
                    Set<String> requirementKeys = nodeMappings.stream()
                            .map(mapping -> mapping.getSnapshot().getRequirement().getRequirementKey())
                            .collect(Collectors.toCollection(TreeSet::new));
                    Set<String> snapshotIds = nodeMappings.stream()
                            .map(mapping -> mapping.getSnapshot().getId())
                            .collect(Collectors.toCollection(TreeSet::new));
                    Map<ActionStatus, Integer> actions = new EnumMap<>(ActionStatus.class);
                    for (RequirementElementMapping mapping : nodeMappings) {
                        actions.merge(mapping.getActionStatus(), 1, Integer::sum);
                    }
                    double average = nodeMappings.stream()
                            .mapToDouble(RequirementElementMapping::getRelevance)
                            .average().orElse(0.0);
                    int maximum = nodeMappings.stream()
                            .mapToInt(RequirementElementMapping::getDirectScore)
                            .max().orElse(0);
                    return new AggregatedTaxonomyNode(
                            entry.getKey(),
                            first.getNodeTitle(),
                            first.getTaxonomyRoot(),
                            requirementKeys.size(),
                            average,
                            maximum,
                            List.copyOf(requirementKeys),
                            List.copyOf(snapshotIds),
                            Map.copyOf(actions));
                })
                .sorted(Comparator.comparing(AggregatedTaxonomyNode::taxonomyRoot)
                        .thenComparing(AggregatedTaxonomyNode::nodeCode))
                .toList();
    }

    private PortfolioMetrics metrics(List<RequirementView> requirements,
                                     List<ProjectSolutionView> solutions,
                                     List<ConflictView> conflicts,
                                     List<RequirementSolutionLink> solutionLinks,
                                     List<SolutionProductCandidate> productCandidates) {
        int analyzed = (int) requirements.stream()
                .filter(requirement -> requirement.currentAnalysisSnapshotId() != null)
                .count();
        int confirmedRequirements = (int) requirements.stream()
                .filter(requirement -> requirement.reviewStatus() == ReviewStatus.CONFIRMED)
                .count();
        Set<Long> requirementsWithConfirmedSolution = solutionLinks.stream()
                .filter(link -> link.getReviewStatus() == ReviewStatus.CONFIRMED)
                .map(link -> link.getRequirement().getId())
                .collect(Collectors.toSet());
        int withoutConfirmedSolution = (int) requirements.stream()
                .filter(requirement -> !requirementsWithConfirmedSolution.contains(requirement.id()))
                .count();
        Map<ActionStatus, Integer> solutionsByAction = new EnumMap<>(ActionStatus.class);
        for (ProjectSolutionView solution : solutions) {
            solutionsByAction.merge(solution.actionStatus(), 1, Integer::sum);
        }
        int selectedProducts = (int) productCandidates.stream()
                .filter(candidate -> candidate.getSelectionStatus() == ProductSelectionStatus.SELECTED)
                .count();
        int openConflicts = (int) conflicts.stream()
                .filter(conflict -> conflict.status() != ConflictStatus.REJECTED
                        && conflict.status() != ConflictStatus.RESOLVED)
                .count();
        int staleSnapshots = countStaleSnapshots(requirements);

        return new PortfolioMetrics(
                requirements.size(),
                analyzed,
                confirmedRequirements,
                withoutConfirmedSolution,
                solutions.size(),
                Map.copyOf(solutionsByAction),
                productCandidates.size(),
                selectedProducts,
                openConflicts,
                staleSnapshots);
    }

    private int countStaleSnapshots(List<RequirementView> requirements) {
        Instant threshold = Instant.now().minus(Duration.ofDays(staleAfterDays));
        int stale = 0;
        for (RequirementView requirement : requirements) {
            if (requirement.currentAnalysisSnapshotId() == null) continue;
            RequirementAnalysisSnapshot snapshot = snapshotRepository
                    .findById(requirement.currentAnalysisSnapshotId()).orElse(null);
            if (snapshot == null || snapshot.getCreatedAt().isBefore(threshold)
                    || !Objects.equals(snapshot.getRequirementVersion().getId(), requirement.currentVersionId())) {
                stale++;
            }
        }
        return stale;
    }

    private MatrixView requirementTaxonomyMatrix(List<RequirementView> requirements,
                                                 List<RequirementElementMapping> mappings) {
        List<String> rows = requirements.stream().map(RequirementView::requirementKey).toList();
        List<String> columns = mappings.stream().map(RequirementElementMapping::getNodeCode)
                .distinct().sorted().toList();
        Map<String, Map<String, Integer>> values = initializeRows(rows);
        for (RequirementElementMapping mapping : mappings) {
            String requirementKey = mapping.getSnapshot().getRequirement().getRequirementKey();
            int value = mapping.getDirectScore() > 0
                    ? mapping.getDirectScore() : (int) Math.round(mapping.getRelevance() * 100.0);
            values.computeIfAbsent(requirementKey, ignored -> new LinkedHashMap<>())
                    .merge(mapping.getNodeCode(), value, Math::max);
        }
        return immutableMatrix(rows, columns, values);
    }

    private MatrixView requirementSolutionMatrix(List<RequirementView> requirements,
                                                 List<ProjectSolutionView> solutions,
                                                 List<RequirementSolutionLink> links) {
        List<String> rows = solutions.stream().map(solution -> solution.solution().solutionKey()).toList();
        List<String> columns = requirements.stream().map(RequirementView::requirementKey).toList();
        Map<String, Map<String, Integer>> values = initializeRows(rows);
        for (RequirementSolutionLink link : links) {
            values.computeIfAbsent(
                            link.getProjectSolution().getSolution().getSolutionKey(),
                            ignored -> new LinkedHashMap<>())
                    .put(link.getRequirement().getRequirementKey(), link.getCoveragePercent());
        }
        return immutableMatrix(rows, columns, values);
    }

    private MatrixView solutionProductMatrix(List<ProjectSolutionView> solutions,
                                             List<SolutionProductCandidate> candidates) {
        List<String> rows = solutions.stream().map(solution -> solution.solution().solutionKey()).toList();
        List<String> columns = candidates.stream()
                .map(candidate -> candidate.getProduct().getProductKey())
                .distinct().sorted().toList();
        Map<String, Map<String, Integer>> values = initializeRows(rows);
        for (SolutionProductCandidate candidate : candidates) {
            values.computeIfAbsent(
                            candidate.getProjectSolution().getSolution().getSolutionKey(),
                            ignored -> new LinkedHashMap<>())
                    .put(candidate.getProduct().getProductKey(), candidate.getCoveragePercent());
        }
        return immutableMatrix(rows, columns, values);
    }

    private static Map<String, Map<String, Integer>> initializeRows(List<String> rows) {
        Map<String, Map<String, Integer>> values = new LinkedHashMap<>();
        rows.forEach(row -> values.put(row, new LinkedHashMap<>()));
        return values;
    }

    private static MatrixView immutableMatrix(List<String> rows,
                                              List<String> columns,
                                              Map<String, Map<String, Integer>> values) {
        Map<String, Map<String, Integer>> immutableValues = new LinkedHashMap<>();
        values.forEach((row, rowValues) -> immutableValues.put(row, Map.copyOf(rowValues)));
        return new MatrixView(List.copyOf(rows), List.copyOf(columns), Map.copyOf(immutableValues));
    }
}
