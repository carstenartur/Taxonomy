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
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ProjectRequirementAnalysisServiceTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private ProjectRequirementAnalysisService analysisService;

    @MockitoBean
    private AnalyzeRequirementUseCase analyzeRequirementUseCase;

    @MockitoBean
    private ArchitectureGapService gapService;

    @MockitoBean
    private ArchitecturePatternService patternService;

    @MockitoBean
    private ArchitectureRecommendationService recommendationService;

    @MockitoBean
    private PortfolioFingerprintService fingerprintService;

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
    }

    @Test
    void createsOneImmutableSnapshotPerRequirement() {
        WorkspaceContext context = context("batch-success");
        ProjectView project = createProject(context);
        createRequirement(project.id(), context, "REQ-001", "First requirement");
        createRequirement(project.id(), context, "REQ-002", "Second requirement");
        createRequirement(project.id(), context, "REQ-003", "Third requirement");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> new AnalyzeRequirementResult(
                        successfulAnalysis(invocation.getArgument(0))));

        var job = analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(List.of(), true, "MOCK", 25, "success-batch"),
                context.username(),
                context);

        assertThat(job.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(job.totalItems()).isEqualTo(3);
        assertThat(job.successfulItems()).isEqualTo(3);
        assertThat(job.failedItems()).isZero();
        assertThat(job.items()).allMatch(item -> item.snapshotId() != null);
        verify(analyzeRequirementUseCase, times(3)).analyze(any(AnalyzeRequirementCommand.class));

        List<RequirementView> requirements = projectService.listRequirements(
                project.id(), context.username(), context);
        assertThat(requirements).allMatch(requirement -> requirement.currentAnalysisSnapshotId() != null);
        for (RequirementView requirement : requirements) {
            var snapshots = analysisService.listSnapshots(
                    project.id(), requirement.id(), context.username(), context);
            assertThat(snapshots).hasSize(1);
            var detail = analysisService.getSnapshot(
                    project.id(), snapshots.get(0).id(), context.username(), context);
            assertThat(detail.analysis().getScores()).containsEntry("CP-1010", 80);
            assertThat(detail.elementMappings()).singleElement()
                    .satisfies(mapping -> {
                        assertThat(mapping.nodeCode()).isEqualTo("CP-1010");
                        assertThat(mapping.directScore()).isEqualTo(80);
                        assertThat(mapping.mappingOrigin().name()).isEqualTo("DIRECT");
                    });
        }
    }

    @Test
    void isolatesFailedRequirementAndRetriesOnlyThatItem() {
        WorkspaceContext context = context("batch-partial");
        ProjectView project = createProject(context);
        createRequirement(project.id(), context, "REQ-001", "Successful requirement one");
        RequirementView failing = createRequirement(
                project.id(), context, "REQ-002", "FAIL this requirement once");
        createRequirement(project.id(), context, "REQ-003", "Successful requirement three");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> {
                    AnalyzeRequirementCommand command = invocation.getArgument(0);
                    if (command.businessText().contains("FAIL")) {
                        throw new IllegalStateException("simulated provider failure");
                    }
                    return new AnalyzeRequirementResult(successfulAnalysis(command));
                });

        var partial = analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(List.of(), true, "MOCK", 25, "partial-batch"),
                context.username(),
                context);

        assertThat(partial.status()).isEqualTo(AnalysisStatus.PARTIAL);
        assertThat(partial.successfulItems()).isEqualTo(2);
        assertThat(partial.failedItems()).isEqualTo(1);
        assertThat(partial.items()).filteredOn(item -> item.status() == AnalysisStatus.FAILED)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.requirementId()).isEqualTo(failing.id());
                    assertThat(item.errorMessage()).contains("simulated provider failure");
                });

        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> new AnalyzeRequirementResult(
                        successfulAnalysis(invocation.getArgument(0))));
        var completed = analysisService.retryFailed(
                partial.id(), project.id(), context.username(), context);

        assertThat(completed.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(completed.successfulItems()).isEqualTo(3);
        assertThat(completed.failedItems()).isZero();
        assertThat(completed.items()).filteredOn(item -> item.requirementId().equals(failing.id()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.attempt()).isEqualTo(2);
                    assertThat(item.status()).isEqualTo(AnalysisStatus.SUCCESS);
                    assertThat(item.snapshotId()).isNotBlank();
                });
        verify(analyzeRequirementUseCase, times(4)).analyze(any(AnalyzeRequirementCommand.class));
    }

    @Test
    void reusesClientIdempotencyKeyWithoutCreatingAnotherSnapshot() {
        WorkspaceContext context = context("idempotency");
        ProjectView project = createProject(context);
        createRequirement(project.id(), context, "REQ-001", "Idempotent requirement");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> new AnalyzeRequirementResult(
                        successfulAnalysis(invocation.getArgument(0))));
        AnalyzeProjectRequest request = new AnalyzeProjectRequest(
                List.of(), true, "MOCK", 25, "stable-client-key");

        var first = analysisService.analyzeProject(
                project.id(), request, context.username(), context);
        var second = analysisService.analyzeProject(
                project.id(), request, context.username(), context);

        assertThat(second.id()).isEqualTo(first.id());
        verify(analyzeRequirementUseCase, times(1)).analyze(any(AnalyzeRequirementCommand.class));
    }

    private AnalysisResult successfulAnalysis(AnalyzeRequirementCommand command) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode("CP-1010");
        element.setTitle("Example Capability");
        element.setTaxonomySheet("CP");
        element.setRelevance(0.80);
        element.setDirectLlmScore(80);
        element.setTaxonomyDepth(2);
        element.setHierarchyPath("CP > CP-1000 > CP-1010");
        element.setOrigin(NodeOrigin.DIRECT_SCORED);
        element.setSelectedForImpact(true);
        element.setPresenceReason("Direct match for " + command.businessText());

        RequirementArchitectureView architecture = new RequirementArchitectureView();
        architecture.setIncludedElements(List.of(element));
        architecture.setTotalElements(1);

        AnalysisResult result = new AnalysisResult(Map.of("CP-1010", 80), List.of());
        result.setStatus("SUCCESS");
        result.setArchitectureView(architecture);
        result.setViewContext(new ViewContext(
                "0123456789abcdef", "draft", Instant.now(), false, false, false));
        return result;
    }

    private ProjectView createProject(WorkspaceContext context) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(),
                        "Batch analysis project",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);
    }

    private RequirementView createRequirement(Long projectId,
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
                        50,
                        Criticality.MEDIUM,
                        RequirementType.FUNCTIONAL,
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
