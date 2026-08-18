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
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewElementMappingRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.ReviewRelationMappingRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.PortfolioScope;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
class PortfolioAnalysisCoverageRegressionTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private ProjectRequirementAnalysisService analysisService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

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
        when(fingerprintService.taxonomyFingerprint()).thenReturn("taxonomy-default");
        when(fingerprintService.promptFingerprint()).thenReturn("prompts-default");
        when(gapService.analyze(anyMap(), anyString(), anyInt()))
                .thenAnswer(invocation -> new GapAnalysisView());
        when(patternService.detectForScores(anyMap(), anyInt()))
                .thenAnswer(invocation -> new PatternDetectionView());
        when(recommendationService.recommend(anyMap(), anyString(), anyInt()))
                .thenAnswer(invocation -> new ArchitectureRecommendation());
    }

    @Test
    void diffsChangedSnapshotsAndReviewsElementAndRelationMappings() {
        WorkspaceContext context = context("analysis-diff");
        ProjectView project = createProject(context, "Snapshot diff project");
        RequirementView requirement = createRequirement(
                project.id(), context, "REQ-DIFF", "Compare two architecture analyses");
        AtomicInteger pass = new AtomicInteger();
        when(fingerprintService.taxonomyFingerprint())
                .thenReturn("taxonomy-v1", "taxonomy-v2");
        when(fingerprintService.promptFingerprint())
                .thenReturn("prompts-v1", "prompts-v2");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> {
                    AnalyzeRequirementCommand command = invocation.getArgument(0);
                    boolean second = pass.getAndIncrement() > 0;
                    return new AnalyzeRequirementResult(second
                            ? successfulAnalysis(command, "AP-2020", "TP-3000", 65, true, true)
                            : successfulAnalysis(command, "CP-1010", "BP-1000", 80, false, false));
                });

        var firstJob = analysisService.analyzeRequirement(
                project.id(), requirement.id(), "mock", 25, "diff-pass-one",
                context.username(), context);
        var secondJob = analysisService.analyzeRequirement(
                project.id(), requirement.id(), "gemini", 25, "diff-pass-two",
                context.username(), context);

        assertThat(firstJob.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(secondJob.status()).isEqualTo(AnalysisStatus.SUCCESS);
        var snapshots = analysisService.listSnapshots(
                project.id(), requirement.id(), context.username(), context);
        assertThat(snapshots).hasSize(2);
        var older = snapshots.stream()
                .filter(snapshot -> "MOCK".equals(snapshot.provider()))
                .findFirst()
                .orElseThrow();
        var newer = snapshots.stream()
                .filter(snapshot -> "GEMINI".equals(snapshot.provider()))
                .findFirst()
                .orElseThrow();

        var diff = analysisService.diffSnapshots(
                project.id(), older.id(), newer.id(), context.username(), context);

        assertThat(diff.scoreChanges()).containsOnlyKeys("AP-2020", "CP-1010");
        assertThat(diff.scoreChanges().get("CP-1010").oldScore()).isEqualTo(80);
        assertThat(diff.scoreChanges().get("CP-1010").newScore()).isNull();
        assertThat(diff.scoreChanges().get("AP-2020").oldScore()).isNull();
        assertThat(diff.scoreChanges().get("AP-2020").newScore()).isEqualTo(65);
        assertThat(diff.addedElements()).containsExactly("AP-2020");
        assertThat(diff.removedElements()).containsExactly("CP-1010");
        assertThat(diff.addedRelations()).singleElement().satisfies(
                relation -> assertThat(relation).contains("AP-2020", "TP-3000"));
        assertThat(diff.removedRelations()).singleElement().satisfies(
                relation -> assertThat(relation).contains("CP-1010", "BP-1000"));
        assertThat(diff.taxonomyFingerprintChanged()).isTrue();
        assertThat(diff.promptFingerprintChanged()).isTrue();
        assertThat(diff.providerChanged()).isTrue();

        var detail = analysisService.getSnapshot(
                project.id(), newer.id(), context.username(), context);
        var elementMapping = detail.elementMappings().getFirst();
        var reviewedElement = persistenceService.reviewElementMapping(
                project.id(),
                elementMapping.id(),
                new ReviewElementMappingRequest(
                        ReviewStatus.CONFIRMED,
                        ActionStatus.REUSE,
                        "Existing platform evidence",
                        "Reviewed against the target architecture"),
                context.username(),
                context);
        assertThat(reviewedElement.reviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(reviewedElement.actionStatus()).isEqualTo(ActionStatus.REUSE);
        assertThat(reviewedElement.decisionBy())
                .isEqualTo(PortfolioScope.username(context.username(), context));

        var relationMapping = detail.relationMappings().getFirst();
        var reviewedRelation = persistenceService.reviewRelationMapping(
                project.id(),
                relationMapping.id(),
                new ReviewRelationMappingRequest(
                        ReviewStatus.REJECTED,
                        "Relation needs independent evidence"),
                context.username(),
                context);
        assertThat(reviewedRelation.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(reviewedRelation.decisionBy())
                .isEqualTo(PortfolioScope.username(context.username(), context));

        assertThatThrownBy(() -> persistenceService.reviewElementMapping(
                project.id(), elementMapping.id(), null, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("mapping review is required");
        assertThatThrownBy(() -> persistenceService.reviewRelationMapping(
                project.id(), relationMapping.id(), null, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("mapping review is required");
    }

    @Test
    void secondaryAnalysisFailuresProduceReviewableFallbacks() {
        WorkspaceContext context = context("analysis-fallback");
        ProjectView project = createProject(context, "Fallback project");
        RequirementView requirement = createRequirement(
                project.id(), context, "REQ-FALLBACK", "Continue when secondary intelligence fails");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenAnswer(invocation -> new AnalyzeRequirementResult(
                        successfulAnalysis(
                                invocation.getArgument(0),
                                "CP-1010",
                                "BP-1000",
                                80,
                                false,
                                true)));
        when(gapService.analyze(anyMap(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("gap unavailable"));
        when(patternService.detectForScores(anyMap(), anyInt()))
                .thenThrow(new IllegalStateException());
        when(recommendationService.recommend(anyMap(), anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("   "));

        var job = analysisService.analyzeRequirement(
                project.id(), requirement.id(), " mock ", null, "fallback-pass",
                context.username(), context);

        assertThat(job.status()).isEqualTo(AnalysisStatus.SUCCESS);
        var snapshot = analysisService.listSnapshots(
                project.id(), requirement.id(), context.username(), context).getFirst();
        var detail = analysisService.getSnapshot(
                project.id(), snapshot.id(), context.username(), context);
        assertThat(detail.analysis().getWarnings())
                .hasSize(3)
                .anyMatch(warning -> warning.contains("gap unavailable"))
                .anyMatch(warning -> warning.contains("IllegalStateException"))
                .anyMatch(warning -> warning.contains("IllegalArgumentException"));
        assertThat(detail.gapAnalysis().getBusinessText())
                .isEqualTo("Continue when secondary intelligence fails");
        assertThat(detail.gapAnalysis().getNotes()).isNotEmpty();
        assertThat(detail.patternDetection().getNotes()).isNotEmpty();
        assertThat(detail.recommendation().getBusinessText())
                .isEqualTo("Continue when secondary intelligence fails");
        assertThat(detail.recommendation().getNotes()).isNotEmpty();
    }

    @Test
    void requestValidationAndUnusableResultsFailClosed() {
        WorkspaceContext context = context("analysis-validation");
        ProjectView project = createProject(context, "Validation project");
        RequirementView requirement = createRequirement(
                project.id(), context, "REQ-VALID", "Validate every analysis boundary");

        assertThatThrownBy(() -> analysisService.analyzeProject(
                project.id(), null, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("analysis request is required");

        ProjectView emptyProject = createProject(context, "Empty analysis project");
        assertThatThrownBy(() -> analysisService.analyzeProject(
                emptyProject.id(),
                new AnalyzeProjectRequest(List.of(), true, "MOCK", 25, "empty-project"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no requirements");

        assertThatThrownBy(() -> analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(List.of(), false, "MOCK", 25, "empty-selection"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("requirementIds must be supplied");
        assertThatThrownBy(() -> analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(
                        Arrays.asList(null, null), false, "MOCK", 25, "null-selection"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("valid requirementId");
        assertThatThrownBy(() -> analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(
                        List.of(requirement.id()), false, "MOCK", 0, "too-small"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("maxArchitectureNodes");
        assertThatThrownBy(() -> analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(
                        List.of(requirement.id()), false, "MOCK", 10_000, "too-large"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("maxArchitectureNodes");

        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenReturn(new AnalyzeRequirementResult(null));
        var nullResult = analysisService.analyzeRequirement(
                project.id(), requirement.id(), "MOCK", 25, "null-analysis",
                context.username(), context);
        assertThat(nullResult.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(nullResult.errorSummary()).contains("no usable scores");

        AnalysisResult providerError = new AnalysisResult(Map.of(), new ArrayList<>());
        providerError.setStatus("ERROR");
        providerError.setErrorMessage("provider refused the request");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenReturn(new AnalyzeRequirementResult(providerError));
        var errorResult = analysisService.analyzeRequirement(
                project.id(), requirement.id(), "MOCK", 25, "error-analysis",
                context.username(), context);
        assertThat(errorResult.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(errorResult.errorSummary()).contains("provider refused the request");
    }

    @Test
    void preparesFailedItemsExactlyOnceForRetry() {
        WorkspaceContext context = context("analysis-retry");
        ProjectView project = createProject(context, "Retry project");
        RequirementView requirement = createRequirement(
                project.id(), context, "REQ-RETRY", "Retry a failed provider call");
        when(analyzeRequirementUseCase.analyze(any(AnalyzeRequirementCommand.class)))
                .thenThrow(new IllegalStateException("provider unavailable"));

        var failed = analysisService.analyzeRequirement(
                project.id(), requirement.id(), "MOCK", 25, "failed-pass",
                context.username(), context);
        assertThat(failed.status()).isEqualTo(AnalysisStatus.FAILED);

        var prepared = persistenceService.prepareFailedItemsForRetry(
                failed.id(), project.id(), context.username(), context);
        assertThat(prepared.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(prepared.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(item.attempt()).isEqualTo(2);
        });

        assertThatThrownBy(() -> persistenceService.prepareFailedItemsForRetry(
                failed.id(), project.id(), context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no failed items to retry");
    }

    private AnalysisResult successfulAnalysis(AnalyzeRequirementCommand command,
                                              String nodeCode,
                                              String relationTarget,
                                              int score,
                                              boolean enriched,
                                              boolean structuredRelation) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(nodeCode);
        element.setTitle("Architecture element " + nodeCode);
        element.setTaxonomySheet(enriched ? null : nodeCode.substring(0, 2));
        element.setRelevance(score / 100.0);
        element.setDirectLlmScore(enriched ? 0 : score);
        element.setTaxonomyDepth(2);
        element.setHierarchyPath(nodeCode.substring(0, 2) + " > " + nodeCode);
        element.setOrigin(enriched ? NodeOrigin.ENRICHED_LEAF : NodeOrigin.DIRECT_SCORED);
        element.setSelectedForImpact(true);
        element.setPresenceReason(enriched ? null : "Direct match for " + command.businessText());
        element.setIncludedBecause("Included for architecture coverage");

        RequirementRelationshipView relationship = new RequirementRelationshipView();
        relationship.setSourceCode(nodeCode);
        relationship.setTargetCode(relationTarget);
        relationship.setRelationType("RELATES_TO");
        relationship.setPropagatedRelevance(score / 100.0);
        relationship.setConfidence(structuredRelation ? 0.85 : 0.55);
        relationship.setOrigin(structuredRelation ? RelationOrigin.LLM_SUPPORTED : null);
        relationship.setDerivationReason("Derived from requirement context");

        RequirementArchitectureView architecture = new RequirementArchitectureView();
        architecture.setIncludedElements(List.of(element));
        architecture.setIncludedRelationships(List.of(relationship));
        architecture.setTotalElements(1);
        architecture.setTotalRelationships(1);

        AnalysisResult result = new AnalysisResult(
                Map.of(nodeCode, score), new ArrayList<>());
        result.setStatus("SUCCESS");
        result.setArchitectureView(architecture);
        result.setViewContext(new ViewContext(
                "0123456789abcdef", "draft", Instant.now(), false, false, false));
        return result;
    }

    private ProjectView createProject(WorkspaceContext context, String title) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(),
                        title,
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
                        key + "-" + shortId(),
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
        return new WorkspaceContext(
                username + "-" + shortId(),
                "ws-" + username + "-" + shortId(),
                "draft",
                "repo-" + username + "-" + shortId());
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
