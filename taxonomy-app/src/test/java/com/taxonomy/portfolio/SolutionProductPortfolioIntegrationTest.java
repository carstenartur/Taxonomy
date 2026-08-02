package com.taxonomy.portfolio;

import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.service.ArchitectureGapService;
import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.portfolio.dto.PortfolioDtos.AddProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProductRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.LinkRequirementSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewConflictRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpdateProjectSolutionRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertTaxonomyCoverageRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ConflictStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectSolutionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementSolutionRole;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;
import com.taxonomy.portfolio.service.PortfolioAggregationService;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.portfolio.service.ProjectConflictService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
class SolutionProductPortfolioIntegrationTest {

    @Autowired private ProjectPortfolioService projectService;
    @Autowired private ProjectRequirementAnalysisService analysisService;
    @Autowired private SolutionPortfolioService solutionService;
    @Autowired private ProductCatalogService productService;
    @Autowired private ProjectConflictService conflictService;
    @Autowired private PortfolioAggregationService aggregationService;

    @MockitoBean private AnalyzeRequirementUseCase analyzeRequirementUseCase;
    @MockitoBean private ArchitectureGapService gapService;
    @MockitoBean private ArchitecturePatternService patternService;
    @MockitoBean private ArchitectureRecommendationService recommendationService;
    @MockitoBean private PortfolioFingerprintService fingerprintService;

    @BeforeEach
    void setUp() {
        reset(analyzeRequirementUseCase, gapService, patternService,
                recommendationService, fingerprintService);
        when(fingerprintService.contentFingerprint(anyString()))
                .thenAnswer(invocation -> sha256(invocation.getArgument(0)));
        when(fingerprintService.taxonomyFingerprint()).thenReturn("taxonomy-v1");
        when(fingerprintService.promptFingerprint()).thenReturn("prompts-v1");
        when(gapService.analyze(anyMap(), anyString(), anyInt()))
                .thenAnswer(invocation -> new GapAnalysisView());
        when(patternService.detectForScores(anyMap(), anyInt()))
                .thenAnswer(invocation -> new PatternDetectionView());
        when(recommendationService.recommend(anyMap(), anyString(), anyInt()))
                .thenAnswer(invocation -> new ArchitectureRecommendation());
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> new AnalyzeRequirementResult(
                        successfulAnalysis(invocation.getArgument(0))));
    }

    @Test
    void consolidatesOneSharedSolutionAndOneSelectedProductAcrossRequirements() {
        WorkspaceContext context = context("portfolio-chain");
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(), "Portfolio chain", null, ProjectStatus.ACTIVE,
                        null, null, null, null),
                context.username(), context);
        var requirementA = createRequirement(
                project.id(), context, "REQ-001", "Cloud required for operations");
        var requirementB = createRequirement(
                project.id(), context, "REQ-002", "No cloud is permitted for classified data");

        analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(List.of(), true, "MOCK", 25, "portfolio-chain-analysis"),
                context.username(),
                context);

        var solution = solutionService.createSolution(
                new CreateSolutionRequest(
                        "SOL-SHARED",
                        "Shared capability solution",
                        "Reusable implementation of the capability",
                        SolutionType.SERVICE,
                        OperatingModel.HYBRID,
                        LifecycleStatus.ACTIVE,
                        4,
                        "Architecture Office",
                        null,
                        null,
                        null,
                        30,
                        Map.of("owner", "platform-team")),
                context.username(),
                context);
        solutionService.upsertTaxonomyCoverage(
                solution.id(),
                new UpsertTaxonomyCoverageRequest(
                        "CP", 100, "Confirmed architecture catalogue mapping", ReviewStatus.CONFIRMED),
                context.username(),
                context);

        var proposals = solutionService.proposeFromCurrentMappings(
                project.id(), context.username(), context);
        assertThat(proposals).singleElement().satisfies(projectSolution -> {
            assertThat(projectSolution.solution().solutionKey()).isEqualTo("SOL-SHARED");
            assertThat(projectSolution.requirements()).hasSize(2);
            assertThat(projectSolution.actionStatus()).isEqualTo(ActionStatus.UNDECIDED);
        });
        var projectSolution = proposals.get(0);
        solutionService.updateProjectSolution(
                project.id(),
                projectSolution.id(),
                new UpdateProjectSolutionRequest(
                        ProjectSolutionStatus.SELECTED,
                        ActionStatus.REUSE,
                        90,
                        "One implementation is reused by both requirements"),
                context.username(),
                context);
        for (var link : projectSolution.requirements()) {
            solutionService.linkRequirement(
                    project.id(),
                    projectSolution.id(),
                    new LinkRequirementSolutionRequest(
                            link.requirementId(),
                            link.snapshotId(),
                            80,
                            RequirementSolutionRole.USES,
                            ReviewStatus.CONFIRMED,
                            "Reviewed architecture mapping"),
                    context.username(),
                    context);
        }

        var product = productService.createProduct(
                new CreateProductRequest(
                        "PRD-001",
                        "Example Vendor",
                        "Example Family",
                        "Example Product",
                        "2026.1",
                        ProductStatus.ACTIVE,
                        null,
                        "Subscription",
                        OperatingModel.HYBRID,
                        "Linux; Kubernetes",
                        "Encryption and audit logging",
                        "Documented controls",
                        null,
                        null,
                        null,
                        "Vendor product documentation, verified test fixture",
                        Instant.now()),
                context.username(),
                context);
        productService.upsertTaxonomyCoverage(
                product.id(),
                new UpsertTaxonomyCoverageRequest(
                        "CP", 90, "Confirmed product capability mapping", ReviewStatus.CONFIRMED),
                context.username(),
                context);
        productService.upsertCandidate(
                project.id(),
                projectSolution.id(),
                new UpsertProductCandidateRequest(
                        product.id(),
                        90,
                        null,
                        "Strong functional match",
                        "Commercial dependency",
                        "Cost validation remains open",
                        0.90,
                        ReviewStatus.CONFIRMED,
                        ProductSelectionStatus.SELECTED),
                context.username(),
                context);

        var conflictHypotheses = conflictService.detect(
                project.id(), context.username(), context);
        assertThat(conflictHypotheses).singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.requirementAId()).isIn(requirementA.id(), requirementB.id());
                    assertThat(conflict.requirementBId()).isIn(requirementA.id(), requirementB.id());
                    assertThat(conflict.status()).isEqualTo(ConflictStatus.PROPOSED);
                });
        conflictService.review(
                project.id(),
                conflictHypotheses.get(0).id(),
                new ReviewConflictRequest(
                        ConflictStatus.CONFIRMED,
                        "Resolve by separating classified and unclassified workloads"),
                context.username(),
                context);

        var portfolio = aggregationService.build(project.id(), context.username(), context);
        assertThat(portfolio.metrics().totalRequirements()).isEqualTo(2);
        assertThat(portfolio.metrics().analyzedRequirements()).isEqualTo(2);
        assertThat(portfolio.metrics().totalSolutions()).isEqualTo(1);
        assertThat(portfolio.metrics().requirementsWithoutConfirmedSolution()).isZero();
        assertThat(portfolio.metrics().selectedProducts()).isEqualTo(1);
        assertThat(portfolio.metrics().openConflicts()).isEqualTo(1);
        assertThat(portfolio.taxonomyNodes()).singleElement()
                .satisfies(node -> {
                    assertThat(node.nodeCode()).isEqualTo("CP");
                    assertThat(node.requirementCount()).isEqualTo(2);
                    assertThat(node.requirementKeys()).containsExactly("REQ-001", "REQ-002");
                });
        assertThat(portfolio.requirementTaxonomyMatrix().values().get("REQ-001"))
                .containsEntry("CP", 80);
        assertThat(portfolio.requirementSolutionMatrix().values().get("SOL-SHARED"))
                .containsEntry("REQ-001", 80)
                .containsEntry("REQ-002", 80);
        assertThat(portfolio.solutionProductMatrix().values().get("SOL-SHARED"))
                .containsEntry("PRD-001", 90);
    }

    private AnalysisResult successfulAnalysis(AnalyzeRequirementCommand command) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode("CP");
        element.setTitle("Capabilities");
        element.setTaxonomySheet("CP");
        element.setRelevance(0.80);
        element.setDirectLlmScore(80);
        element.setTaxonomyDepth(0);
        element.setHierarchyPath("CP");
        element.setOrigin(NodeOrigin.DIRECT_SCORED);
        element.setSelectedForImpact(true);
        element.setPresenceReason("Direct match for " + command.businessText());

        RequirementArchitectureView architecture = new RequirementArchitectureView();
        architecture.setIncludedElements(List.of(element));
        architecture.setTotalElements(1);

        AnalysisResult result = new AnalysisResult(Map.of("CP", 80), List.of());
        result.setStatus("SUCCESS");
        result.setArchitectureView(architecture);
        result.setViewContext(new ViewContext(
                "abcdef0123456789", "draft", Instant.now(), false, false, false));
        return result;
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView createRequirement(
            Long projectId,
            WorkspaceContext context,
            String key,
            String text) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        key,
                        key + " title",
                        text,
                        RequirementStatus.APPROVED,
                        80,
                        Criticality.HIGH,
                        RequirementType.TECHNICAL,
                        ReviewStatus.CONFIRMED,
                        context.username(),
                        "Initial version",
                        null),
                context.username(),
                context);
    }

    private WorkspaceContext context(String username) {
        return new WorkspaceContext(username, "ws-" + username + "-" + shortId(), "draft");
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
