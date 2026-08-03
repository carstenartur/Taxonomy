package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.portfolio.dto.PortfolioDtos.AddProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.LinkRequirementSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementSolutionLinkView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.TaxonomyCoverageView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectSolutionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementSolutionRole;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectSolution;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.RequirementElementMapping;
import com.taxonomy.portfolio.model.RequirementSolutionLink;
import com.taxonomy.portfolio.model.SolutionDefinition;
import com.taxonomy.portfolio.model.SolutionTaxonomyCoverage;
import com.taxonomy.portfolio.repository.ProjectSolutionRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.portfolio.repository.RequirementSolutionLinkRepository;
import com.taxonomy.portfolio.repository.SolutionDefinitionRepository;
import com.taxonomy.portfolio.repository.SolutionProductCandidateRepository;
import com.taxonomy.portfolio.repository.SolutionTaxonomyCoverageRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Reusable solution catalogue plus project-specific solution decisions and coverage. */
@Service
public class SolutionPortfolioService {

    private static final Pattern BUSINESS_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final SolutionDefinitionRepository solutionRepository;
    private final SolutionTaxonomyCoverageRepository coverageRepository;
    private final ProjectSolutionRepository projectSolutionRepository;
    private final RequirementSolutionLinkRepository requirementLinkRepository;
    private final RequirementElementMappingRepository elementMappingRepository;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final SolutionProductCandidateRepository productCandidateRepository;
    private final TaxonomyNodeRepository taxonomyNodeRepository;
    private final ProjectPortfolioService projectService;
    private final ProductCatalogService productService;
    private final PortfolioJsonCodec jsonCodec;

    public SolutionPortfolioService(SolutionDefinitionRepository solutionRepository,
                                    SolutionTaxonomyCoverageRepository coverageRepository,
                                    ProjectSolutionRepository projectSolutionRepository,
                                    RequirementSolutionLinkRepository requirementLinkRepository,
                                    RequirementElementMappingRepository elementMappingRepository,
                                    RequirementAnalysisSnapshotRepository snapshotRepository,
                                    SolutionProductCandidateRepository productCandidateRepository,
                                    TaxonomyNodeRepository taxonomyNodeRepository,
                                    ProjectPortfolioService projectService,
                                    ProductCatalogService productService,
                                    PortfolioJsonCodec jsonCodec) {
        this.solutionRepository = solutionRepository;
        this.coverageRepository = coverageRepository;
        this.projectSolutionRepository = projectSolutionRepository;
        this.requirementLinkRepository = requirementLinkRepository;
        this.elementMappingRepository = elementMappingRepository;
        this.snapshotRepository = snapshotRepository;
        this.productCandidateRepository = productCandidateRepository;
        this.taxonomyNodeRepository = taxonomyNodeRepository;
        this.projectService = projectService;
        this.productService = productService;
        this.jsonCodec = jsonCodec;
    }

    @Transactional
    public SolutionView createSolution(CreateSolutionRequest request,
                                       String username,
                                       WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("solution request is required");
        String scopeKey = PortfolioScope.key(username, context);
        String solutionKey = normalizeKey(request.solutionKey(), "solutionKey");
        if (solutionRepository.findByScopeKeyAndSolutionKeyIgnoreCase(scopeKey, solutionKey).isPresent()) {
            throw PortfolioException.conflict("Solution key already exists in this workspace: " + solutionKey);
        }
        int maturity = normalizeMaturity(request.maturityLevel());
        var costAmount = PortfolioValueValidator.money(request.costAmount(), "costAmount");
        String costCurrency = normalizeCurrency(request.costCurrency());
        PortfolioValueValidator.requireMoneyPair(
                costAmount, costCurrency, "costAmount", "costCurrency");
        Integer leadTimeDays = PortfolioValueValidator.nonNegativeDays(
                request.leadTimeDays(), "leadTimeDays");
        Map<String, String> extensionAttributes = PortfolioValueValidator.extensionAttributes(
                request.extensionAttributes() != null ? request.extensionAttributes() : Map.of());
        String extensionAttributesJson = ProjectPortfolioService.limited(
                jsonCodec.write(extensionAttributes), 4000, "extensionAttributes");

        Instant now = Instant.now();
        SolutionDefinition solution = new SolutionDefinition(
                scopeKey,
                PortfolioScope.workspaceId(context),
                solutionKey,
                ProjectPortfolioService.requireText(request.title(), "title", 240),
                ProjectPortfolioService.limited(request.description(), 4000, "description"),
                request.solutionType() != null ? request.solutionType() : SolutionType.OTHER,
                request.operatingModel() != null ? request.operatingModel() : OperatingModel.UNSPECIFIED,
                request.lifecycleStatus() != null ? request.lifecycleStatus() : LifecycleStatus.PLANNED,
                maturity,
                PortfolioScope.username(username, context),
                ProjectPortfolioService.limited(
                        request.responsibleOrganization(), 240, "responsibleOrganization"),
                now);
        solution.update(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                costAmount,
                costCurrency,
                ProjectPortfolioService.limited(request.riskNotes(), 2000, "riskNotes"),
                leadTimeDays,
                extensionAttributesJson,
                now);
        return toSolutionView(solutionRepository.save(solution));
    }

    @Transactional(readOnly = true)
    public List<SolutionView> listSolutions(String username, WorkspaceContext context) {
        return solutionRepository.findByScopeKeyOrderByTitleAsc(PortfolioScope.key(username, context))
                .stream().map(this::toSolutionView).toList();
    }

    @Transactional(readOnly = true)
    public SolutionView getSolution(Long solutionId, String username, WorkspaceContext context) {
        return toSolutionView(requireSolution(solutionId, username, context));
    }

    @Transactional
    public SolutionView updateSolution(Long solutionId,
                                       UpdateSolutionRequest request,
                                       String username,
                                       WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("solution update is required");
        SolutionDefinition solution = requireSolution(solutionId, username, context);
        var requestedAmount = request.costAmount() != null
                ? PortfolioValueValidator.money(request.costAmount(), "costAmount") : null;
        String requestedCurrency = request.costCurrency() != null
                ? normalizeCurrency(request.costCurrency()) : null;
        var effectiveAmount = request.costAmount() != null
                ? requestedAmount : solution.getCostAmount();
        String effectiveCurrency = request.costCurrency() != null
                ? requestedCurrency : solution.getCostCurrency();
        PortfolioValueValidator.requireMoneyPair(
                effectiveAmount, effectiveCurrency, "costAmount", "costCurrency");
        Integer leadTimeDays = request.leadTimeDays() != null
                ? PortfolioValueValidator.nonNegativeDays(request.leadTimeDays(), "leadTimeDays") : null;
        String extensionAttributesJson = null;
        if (request.extensionAttributes() != null) {
            extensionAttributesJson = ProjectPortfolioService.limited(
                    jsonCodec.write(PortfolioValueValidator.extensionAttributes(request.extensionAttributes())),
                    4000,
                    "extensionAttributes");
        }

        solution.update(
                request.title() != null
                        ? ProjectPortfolioService.requireText(request.title(), "title", 240) : null,
                ProjectPortfolioService.limited(request.description(), 4000, "description"),
                request.solutionType(),
                request.operatingModel(),
                request.lifecycleStatus(),
                request.maturityLevel() != null ? normalizeMaturity(request.maturityLevel()) : null,
                request.ownerUsername() != null && !request.ownerUsername().isBlank()
                        ? ProjectPortfolioService.requireText(request.ownerUsername(), "ownerUsername", 160) : null,
                ProjectPortfolioService.limited(
                        request.responsibleOrganization(), 240, "responsibleOrganization"),
                requestedAmount,
                requestedCurrency,
                ProjectPortfolioService.limited(request.riskNotes(), 2000, "riskNotes"),
                leadTimeDays,
                extensionAttributesJson,
                Instant.now());
        return toSolutionView(solution);
    }

    @Transactional
    public SolutionView upsertTaxonomyCoverage(Long solutionId,
                                               UpsertTaxonomyCoverageRequest request,
                                               String username,
                                               WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("taxonomy coverage is required");
        SolutionDefinition solution = requireSolution(solutionId, username, context);
        String nodeCode = requireNodeCode(request.nodeCode());
        int coverage = normalizePercentage(request.coveragePercent(), "coveragePercent");
        Instant now = Instant.now();
        SolutionTaxonomyCoverage mapping = coverageRepository
                .findBySolutionIdAndNodeCode(solutionId, nodeCode)
                .orElseGet(() -> new SolutionTaxonomyCoverage(
                        solution,
                        nodeCode,
                        coverage,
                        ProjectPortfolioService.limited(request.evidence(), 2000, "evidence"),
                        request.reviewStatus() != null ? request.reviewStatus() : ReviewStatus.PROPOSED,
                        PortfolioScope.username(username, context),
                        now));
        if (mapping.getId() != null) {
            mapping.update(
                    coverage,
                    ProjectPortfolioService.limited(request.evidence(), 2000, "evidence"),
                    request.reviewStatus(),
                    PortfolioScope.username(username, context),
                    now);
        }
        coverageRepository.save(mapping);
        return toSolutionView(solution);
    }

    @Transactional
    public ProjectSolutionView addProjectSolution(Long projectId,
                                                  AddProjectSolutionRequest request,
                                                  String username,
                                                  WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("project solution request is required");
        ArchitectureProject project = projectService.requireProject(projectId, username, context);
        SolutionDefinition solution = requireSolution(request.solutionId(), username, context);
        Instant now = Instant.now();
        ProjectSolution projectSolution = projectSolutionRepository
                .findByProjectIdAndSolutionId(projectId, solution.getId())
                .orElseGet(() -> new ProjectSolution(
                        project,
                        solution,
                        request.status() != null ? request.status() : ProjectSolutionStatus.PROPOSED,
                        request.actionStatus() != null ? request.actionStatus() : ActionStatus.UNDECIDED,
                        normalizePriority(request.priority()),
                        ProjectPortfolioService.limited(request.rationale(), 2000, "rationale"),
                        PortfolioScope.username(username, context),
                        now));
        if (projectSolution.getId() != null) {
            projectSolution.update(
                    request.status(),
                    request.actionStatus(),
                    request.priority() != null ? normalizePriority(request.priority()) : null,
                    ProjectPortfolioService.limited(request.rationale(), 2000, "rationale"),
                    now);
        }
        projectSolutionRepository.save(projectSolution);
        return toProjectSolutionView(projectSolution);
    }

    @Transactional
    public ProjectSolutionView updateProjectSolution(Long projectId,
                                                     Long projectSolutionId,
                                                     UpdateProjectSolutionRequest request,
                                                     String username,
                                                     WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("project solution update is required");
        projectService.requireProject(projectId, username, context);
        ProjectSolution projectSolution = requireProjectSolution(projectId, projectSolutionId);
        projectSolution.update(
                request.status(),
                request.actionStatus(),
                request.priority() != null ? normalizePriority(request.priority()) : null,
                ProjectPortfolioService.limited(request.rationale(), 2000, "rationale"),
                Instant.now());
        return toProjectSolutionView(projectSolution);
    }

    @Transactional
    public ProjectSolutionView linkRequirement(Long projectId,
                                               Long projectSolutionId,
                                               LinkRequirementSolutionRequest request,
                                               String username,
                                               WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("requirement solution link is required");
        ProjectSolution projectSolution = requireProjectSolutionScoped(
                projectId, projectSolutionId, username, context);
        ProjectRequirement requirement = projectService.requireRequirement(
                projectId, request.requirementId(), username, context);
        String snapshotId = request.snapshotId();
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = requirement.getCurrentAnalysisSnapshotId();
        }
        final String effectiveSnapshotId = snapshotId;
        validateSnapshot(effectiveSnapshotId, projectId, requirement.getId());
        int coverage = normalizePercentage(request.coveragePercent(), "coveragePercent");
        Instant now = Instant.now();
        RequirementSolutionLink link = requirementLinkRepository
                .findByProjectSolutionIdAndRequirementId(projectSolutionId, requirement.getId())
                .orElseGet(() -> new RequirementSolutionLink(
                        projectSolution,
                        requirement,
                        effectiveSnapshotId,
                        coverage,
                        request.role() != null ? request.role() : RequirementSolutionRole.USES,
                        request.reviewStatus() != null ? request.reviewStatus() : ReviewStatus.PROPOSED,
                        ProjectPortfolioService.limited(request.evidence(), 2000, "evidence"),
                        PortfolioScope.username(username, context),
                        now));
        if (link.getId() != null) {
            link.update(
                    effectiveSnapshotId,
                    coverage,
                    request.role(),
                    request.reviewStatus(),
                    ProjectPortfolioService.limited(request.evidence(), 2000, "evidence"),
                    PortfolioScope.username(username, context),
                    now);
        }
        requirementLinkRepository.save(link);
        return toProjectSolutionView(projectSolution);
    }

    /**
     * Deterministically proposes reuse candidates from confirmed solution-to-taxonomy coverage.
     * Every generated link remains PROPOSED; no action or selection decision is automated.
     */
    @Transactional
    public List<ProjectSolutionView> proposeFromCurrentMappings(Long projectId,
                                                                String username,
                                                                WorkspaceContext context) {
        ArchitectureProject project = projectService.requireProject(projectId, username, context);
        String scopeKey = PortfolioScope.key(username, context);
        List<RequirementElementMapping> mappings = elementMappingRepository
                .findCurrentMappingsForProject(projectId);
        Map<Long, ProjectSolution> touched = new LinkedHashMap<>();
        Instant now = Instant.now();

        for (RequirementElementMapping element : mappings) {
            for (SolutionTaxonomyCoverage coverage : coverageRepository.findByNodeCode(element.getNodeCode())) {
                SolutionDefinition solution = coverage.getSolution();
                if (!scopeKey.equals(solution.getScopeKey())
                        || coverage.getReviewStatus() != ReviewStatus.CONFIRMED) {
                    continue;
                }
                ProjectSolution projectSolution = projectSolutionRepository
                        .findByProjectIdAndSolutionId(projectId, solution.getId())
                        .orElseGet(() -> projectSolutionRepository.save(new ProjectSolution(
                                project,
                                solution,
                                ProjectSolutionStatus.PROPOSED,
                                ActionStatus.UNDECIDED,
                                Math.max(1, element.getDirectScore()),
                                "Proposed from confirmed taxonomy coverage; human decision required",
                                PortfolioScope.username(username, context),
                                now)));
                touched.put(projectSolution.getId(), projectSolution);

                ProjectRequirement requirement = element.getSnapshot().getRequirement();
                int effectiveCoverage = (int) Math.round(
                        coverage.getCoveragePercent() * Math.max(0.0, Math.min(1.0, element.getRelevance())));
                RequirementSolutionLink link = requirementLinkRepository
                        .findByProjectSolutionIdAndRequirementId(
                                projectSolution.getId(), requirement.getId())
                        .orElseGet(() -> new RequirementSolutionLink(
                                projectSolution,
                                requirement,
                                element.getSnapshot().getId(),
                                effectiveCoverage,
                                RequirementSolutionRole.USES,
                                ReviewStatus.PROPOSED,
                                "Solution covers " + element.getNodeCode()
                                        + " at " + coverage.getCoveragePercent()
                                        + "% and the requirement relevance is "
                                        + Math.round(element.getRelevance() * 100) + "%",
                                PortfolioScope.username(username, context),
                                now));
                if (link.getId() == null) {
                    requirementLinkRepository.save(link);
                }
            }
        }
        return touched.values().stream().map(this::toProjectSolutionView).toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectSolutionView> listProjectSolutions(Long projectId,
                                                          String username,
                                                          WorkspaceContext context) {
        projectService.requireProject(projectId, username, context);
        return projectSolutionRepository.findByProjectIdOrderByPriorityDescSolutionTitleAsc(projectId)
                .stream().map(this::toProjectSolutionView).toList();
    }

    @Transactional(readOnly = true)
    public SolutionDefinition requireSolution(Long solutionId,
                                               String username,
                                               WorkspaceContext context) {
        if (solutionId == null) throw PortfolioException.validation("solutionId is required");
        return solutionRepository.findByIdAndScopeKey(solutionId, PortfolioScope.key(username, context))
                .orElseThrow(() -> PortfolioException.notFound("Solution not found: " + solutionId));
    }

    public SolutionView toSolutionView(SolutionDefinition solution) {
        List<TaxonomyCoverageView> coverage = coverageRepository
                .findBySolutionIdOrderByNodeCodeAsc(solution.getId()).stream()
                .map(mapping -> new TaxonomyCoverageView(
                        mapping.getId(),
                        mapping.getNodeCode(),
                        mapping.getCoveragePercent(),
                        mapping.getEvidence(),
                        mapping.getReviewStatus(),
                        mapping.getUpdatedBy(),
                        mapping.getUpdatedAt()))
                .toList();
        return new SolutionView(
                solution.getId(),
                solution.getSolutionKey(),
                solution.getTitle(),
                solution.getDescription(),
                solution.getSolutionType(),
                solution.getOperatingModel(),
                solution.getLifecycleStatus(),
                solution.getMaturityLevel(),
                solution.getOwnerUsername(),
                solution.getResponsibleOrganization(),
                solution.getCostAmount(),
                solution.getCostCurrency(),
                solution.getRiskNotes(),
                solution.getLeadTimeDays(),
                jsonCodec.readStringMap(solution.getExtensionAttributesJson()),
                solution.getCreatedAt(),
                solution.getUpdatedAt(),
                coverage);
    }

    public ProjectSolutionView toProjectSolutionView(ProjectSolution projectSolution) {
        List<RequirementSolutionLinkView> requirements = requirementLinkRepository
                .findByProjectSolutionIdOrderByRequirementRequirementKeyAsc(projectSolution.getId()).stream()
                .map(link -> new RequirementSolutionLinkView(
                        link.getId(),
                        link.getRequirement().getId(),
                        link.getRequirement().getRequirementKey(),
                        link.getSnapshotId(),
                        link.getCoveragePercent(),
                        link.getRole(),
                        link.getReviewStatus(),
                        link.getEvidence(),
                        link.getUpdatedBy(),
                        link.getUpdatedAt()))
                .toList();
        return new ProjectSolutionView(
                projectSolution.getId(),
                projectSolution.getProject().getId(),
                toSolutionView(projectSolution.getSolution()),
                projectSolution.getStatus(),
                projectSolution.getActionStatus(),
                projectSolution.getPriority(),
                projectSolution.getRationale(),
                projectSolution.getCreatedBy(),
                projectSolution.getCreatedAt(),
                projectSolution.getUpdatedAt(),
                requirements,
                productCandidateRepository
                        .findByProjectSolutionIdOrderByCoveragePercentDesc(projectSolution.getId()).stream()
                        .map(productService::toCandidateView)
                        .toList());
    }

    private ProjectSolution requireProjectSolutionScoped(Long projectId,
                                                         Long projectSolutionId,
                                                         String username,
                                                         WorkspaceContext context) {
        projectService.requireProject(projectId, username, context);
        return requireProjectSolution(projectId, projectSolutionId);
    }

    private ProjectSolution requireProjectSolution(Long projectId, Long projectSolutionId) {
        if (projectSolutionId == null) {
            throw PortfolioException.validation("projectSolutionId is required");
        }
        return projectSolutionRepository.findByIdAndProjectId(projectSolutionId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Project solution not found: " + projectSolutionId));
    }

    private void validateSnapshot(String snapshotId, Long projectId, Long requirementId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw PortfolioException.validation(
                    "A requirement solution link requires an analysis snapshot");
        }
        RequirementAnalysisSnapshot snapshot = snapshotRepository
                .findByIdAndProjectId(snapshotId, projectId)
                .orElseThrow(() -> PortfolioException.notFound("Analysis snapshot not found: " + snapshotId));
        if (!snapshot.getRequirement().getId().equals(requirementId)) {
            throw PortfolioException.validation(
                    "Snapshot " + snapshotId + " does not belong to requirement " + requirementId);
        }
    }

    private String requireNodeCode(String nodeCode) {
        String normalized = ProjectPortfolioService.requireText(nodeCode, "nodeCode", 80)
                .toUpperCase(Locale.ROOT);
        if (taxonomyNodeRepository.findByCode(normalized).isEmpty()) {
            throw PortfolioException.validation("Unknown taxonomy node: " + normalized);
        }
        return normalized;
    }

    private static String normalizeKey(String value, String field) {
        String normalized = ProjectPortfolioService.requireText(value, field, 64)
                .toUpperCase(Locale.ROOT);
        if (!BUSINESS_KEY.matcher(normalized).matches()) {
            throw PortfolioException.validation(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static int normalizeMaturity(Integer value) {
        int normalized = value != null ? value : 0;
        if (normalized < 0 || normalized > 5) {
            throw PortfolioException.validation("maturityLevel must be between 0 and 5");
        }
        return normalized;
    }

    private static int normalizePriority(Integer value) {
        int normalized = value != null ? value : 50;
        if (normalized < 0 || normalized > 100) {
            throw PortfolioException.validation("priority must be between 0 and 100");
        }
        return normalized;
    }

    private static int normalizePercentage(int value, String field) {
        if (value < 0 || value > 100) {
            throw PortfolioException.validation(field + " must be between 0 and 100");
        }
        return value;
    }

    private static String normalizeCurrency(String value) {
        return PortfolioValueValidator.currency(value, "costCurrency");
    }
}
