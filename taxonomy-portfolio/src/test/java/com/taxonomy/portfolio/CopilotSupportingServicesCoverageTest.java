package com.taxonomy.portfolio;

import com.taxonomy.portfolio.config.AiAutomationDefaultsConfiguration;
import com.taxonomy.portfolio.controller.ProjectAutopilotController;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunRequest;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotRunView;
import com.taxonomy.portfolio.dto.CopilotDtos.ProjectAutopilotStatus;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProductView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectSolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionProductCandidateView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SolutionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.TaxonomyCoverageView;
import com.taxonomy.portfolio.dto.PortfolioDtos.UpsertProductCandidateRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.service.CopilotCompletionService;
import com.taxonomy.portfolio.service.CopilotJobControlService;
import com.taxonomy.portfolio.service.CopilotResultPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProductCatalogService;
import com.taxonomy.portfolio.service.ProjectAutopilotService;
import com.taxonomy.portfolio.service.SolutionPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CopilotSupportingServicesCoverageTest {

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void documentedAutomationDefaultsConfigurationIsConstructible() {
        assertThat(new AiAutomationDefaultsConfiguration()).isNotNull();
    }

    @Test
    void projectAutopilotControllerUsesOneResolvedRequestScopeForStatusAndRun() {
        ProjectAutopilotService service = mock(ProjectAutopilotService.class);
        WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        ProjectAutopilotController controller = new ProjectAutopilotController(service, resolver);
        ProjectAutopilotStatus status = new ProjectAutopilotStatus(
                41L, true, false, 3, 10, "ready");
        ProjectAutopilotRunRequest request = new ProjectAutopilotRunRequest(
                List.of(7L, 8L), 2);
        ProjectAutopilotRunView run = new ProjectAutopilotRunView(
                41L, 2, 2, List.of("operation-a", "operation-b"), "started");
        when(resolver.resolveCurrentUsername()).thenReturn(context.username());
        when(resolver.resolveCurrentContext()).thenReturn(context);
        when(service.status(41L, context.username(), context)).thenReturn(status);
        when(service.run(41L, request, context.username(), context)).thenReturn(run);

        assertThat(controller.status(41L)).isSameAs(status);
        var response = controller.run(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(run);
        verify(service).status(41L, context.username(), context);
        verify(service).run(41L, request, context.username(), context);
        verify(resolver, org.mockito.Mockito.times(2)).resolveCurrentUsername();
        verify(resolver, org.mockito.Mockito.times(2)).resolveCurrentContext();
    }

    @Test
    void completionCanReturnExistingSolutionsWithoutGeneratingProducts() {
        SolutionPortfolioService solutions = mock(SolutionPortfolioService.class);
        ProductCatalogService products = mock(ProductCatalogService.class);
        CopilotCompletionService service = new CopilotCompletionService(
                solutions, products, 25, 0.25);
        ProjectSolutionView existing = projectSolution(
                11L,
                List.of(coverage("CP", 80, ReviewStatus.CONFIRMED)),
                List.of());
        when(solutions.listProjectSolutions(41L, context.username(), context))
                .thenReturn(List.of(existing));

        var result = service.enrich(
                41L, context.username(), context, false, false);

        assertThat(result.projectSolutionCount()).isEqualTo(1);
        assertThat(result.productCandidatesCreated()).isZero();
        assertThat(result.reviewBoundary()).contains("PROPOSED/CANDIDATE", "human review");
        verify(solutions).listProjectSolutions(41L, context.username(), context);
        verifyNoInteractions(products);
    }

    @Test
    void completionCreatesOnlySupportedHighConfidenceConfirmedOverlapCandidates() {
        SolutionPortfolioService solutions = mock(SolutionPortfolioService.class);
        ProductCatalogService products = mock(ProductCatalogService.class);
        CopilotCompletionService service = new CopilotCompletionService(
                solutions, products, 60, 0.75);

        ProductView existingProduct = product(
                1L, ProductStatus.ACTIVE, "existing-source",
                List.of(coverage("CP", 90, ReviewStatus.CONFIRMED)));
        SolutionProductCandidateView existingCandidate = mock(SolutionProductCandidateView.class);
        when(existingCandidate.product()).thenReturn(existingProduct);

        ProjectSolutionView eligible = projectSolution(
                11L,
                Arrays.asList(
                        null,
                        coverage("ignored", 100, ReviewStatus.PROPOSED),
                        coverage(null, 100, ReviewStatus.CONFIRMED),
                        coverage(" ", 100, ReviewStatus.CONFIRMED),
                        coverage("CP", 70, ReviewStatus.CONFIRMED),
                        coverage("CP", 80, ReviewStatus.CONFIRMED),
                        coverage("BP", 70, ReviewStatus.CONFIRMED)),
                List.of(existingCandidate));
        ProjectSolutionView withoutConfirmedCoverage = projectSolution(
                12L, null, List.of());

        ProductView endOfSupport = product(
                2L, ProductStatus.END_OF_SUPPORT, "eos-source",
                List.of(coverage("CP", 100, ReviewStatus.CONFIRMED)));
        ProductView withdrawn = product(
                3L, ProductStatus.WITHDRAWN, "withdrawn-source",
                List.of(coverage("CP", 100, ReviewStatus.CONFIRMED)));
        ProductView noOverlap = product(
                4L, ProductStatus.ACTIVE, "unrelated-source",
                List.of(coverage("UA", 100, ReviewStatus.CONFIRMED)));
        ProductView lowCoverage = product(
                5L, ProductStatus.CANDIDATE, "low-source",
                List.of(
                        coverage("CP", 20, ReviewStatus.CONFIRMED),
                        coverage("BP", 20, ReviewStatus.CONFIRMED)));
        ProductView lowConfidence = product(
                6L, ProductStatus.DEPRECATED, "partial-source",
                List.of(coverage("CP", 100, ReviewStatus.CONFIRMED)));
        ProductView eligibleProduct = product(
                7L, ProductStatus.ACTIVE, "catalogue-source",
                List.of(
                        coverage("CP", 90, ReviewStatus.CONFIRMED),
                        coverage("BP", 80, ReviewStatus.CONFIRMED)));

        when(solutions.proposeFromCurrentMappings(41L, context.username(), context))
                .thenReturn(List.of(eligible));
        when(solutions.listProjectSolutions(41L, context.username(), context))
                .thenReturn(List.of(eligible, withoutConfirmedCoverage));
        when(products.listProducts(context.username(), context)).thenReturn(List.of(
                existingProduct,
                endOfSupport,
                withdrawn,
                noOverlap,
                lowCoverage,
                lowConfidence,
                eligibleProduct));

        var result = service.enrich(
                41L, context.username(), context, true, true);

        assertThat(result.projectSolutionCount()).isEqualTo(2);
        assertThat(result.productCandidatesCreated()).isEqualTo(1);
        verify(solutions).proposeFromCurrentMappings(41L, context.username(), context);
        verify(products).upsertCandidate(
                eq(41L),
                eq(11L),
                argThat(request -> candidateMatches(request, eligibleProduct.id())),
                eq(context.username()),
                eq(context));
        verify(products, never()).upsertCandidate(
                eq(41L), eq(12L), any(), eq(context.username()), eq(context));
    }

    @Test
    void terminalJobCancellationIsIdempotentForEveryTerminalState() {
        RequirementAnalysisJobRepository jobs = mock(RequirementAnalysisJobRepository.class);
        PortfolioAnalysisPersistenceService persistence =
                mock(PortfolioAnalysisPersistenceService.class);
        EntityManager entityManager = mock(EntityManager.class);
        CopilotJobControlService service = new CopilotJobControlService(
                jobs, persistence, entityManager);
        RequirementAnalysisJob job = mock(RequirementAnalysisJob.class);
        var expected = mock(com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView.class);
        String scopeKey = PortfolioScope.key(context.username(), context);

        for (AnalysisStatus status : List.of(
                AnalysisStatus.SUCCESS,
                AnalysisStatus.PARTIAL,
                AnalysisStatus.FAILED,
                AnalysisStatus.CANCELLED)) {
            reset(job, jobs, persistence, entityManager);
            when(job.getStatus()).thenReturn(status);
            when(jobs.findByIdAndProjectIdAndScopeKey("job-1", 41L, scopeKey))
                    .thenReturn(Optional.of(job));
            when(persistence.getJob("job-1", 41L, context.username(), context))
                    .thenReturn(expected);

            assertThat(service.cancel("job-1", 41L, context.username(), context))
                    .isSameAs(expected);
            verifyNoInteractions(entityManager);
        }
    }

    @Test
    void activeJobCancellationUpdatesItemsAndReloadsTheExactTenantView() {
        RequirementAnalysisJobRepository jobs = mock(RequirementAnalysisJobRepository.class);
        PortfolioAnalysisPersistenceService persistence =
                mock(PortfolioAnalysisPersistenceService.class);
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        CopilotJobControlService service = new CopilotJobControlService(
                jobs, persistence, entityManager);
        RequirementAnalysisJob job = mock(RequirementAnalysisJob.class);
        var expected = mock(com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView.class);
        String scopeKey = PortfolioScope.key(context.username(), context);
        when(job.getStatus()).thenReturn(AnalysisStatus.RUNNING);
        when(jobs.findByIdAndProjectIdAndScopeKey("job-1", 41L, scopeKey))
                .thenReturn(Optional.of(job));
        when(entityManager.createQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(2);
        when(persistence.getJob("job-1", 41L, context.username(), context))
                .thenReturn(expected);

        assertThat(service.cancel("job-1", 41L, context.username(), context))
                .isSameAs(expected);

        verify(query).executeUpdate();
        verify(job).cancel(any(Instant.class));
        verify(jobs).save(job);
        verify(entityManager).flush();
        verify(entityManager).clear();
    }

    @Test
    void missingCancellationTargetIsHiddenAsNotFound() {
        RequirementAnalysisJobRepository jobs = mock(RequirementAnalysisJobRepository.class);
        PortfolioAnalysisPersistenceService persistence =
                mock(PortfolioAnalysisPersistenceService.class);
        EntityManager entityManager = mock(EntityManager.class);
        CopilotJobControlService service = new CopilotJobControlService(
                jobs, persistence, entityManager);
        String scopeKey = PortfolioScope.key(context.username(), context);
        when(jobs.findByIdAndProjectIdAndScopeKey("missing", 41L, scopeKey))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(
                "missing", 41L, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("not found");
        verifyNoInteractions(persistence, entityManager);
    }

    @Test
    void selectedSnapshotMustMatchExactRequirementAndCurrentVersion() {
        ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
        RequirementAnalysisSnapshotRepository snapshots =
                mock(RequirementAnalysisSnapshotRepository.class);
        CopilotResultPersistenceService service = new CopilotResultPersistenceService(
                requirements, snapshots);
        ProjectRequirement requirement = mock(ProjectRequirement.class);
        RequirementAnalysisSnapshot snapshot = mock(RequirementAnalysisSnapshot.class);
        String scopeKey = PortfolioScope.key(context.username(), context);
        when(requirements.findByIdAndProjectIdAndScopeKey(7L, 41L, scopeKey))
                .thenReturn(Optional.of(requirement));
        when(snapshots.findByIdAndProjectIdAndScopeKey("snapshot-1", 41L, scopeKey))
                .thenReturn(Optional.of(snapshot));
        when(requirement.getCurrentVersionId()).thenReturn(9L);

        when(snapshot.getRequirementId()).thenReturn(8L);
        assertThatThrownBy(() -> service.selectCurrentSnapshot(
                41L, 7L, "snapshot-1", context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("does not belong");

        when(snapshot.getRequirementId()).thenReturn(7L);
        when(snapshot.getRequirementVersionId()).thenReturn(10L);
        assertThatThrownBy(() -> service.selectCurrentSnapshot(
                41L, 7L, "snapshot-1", context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("stale");

        when(snapshot.getRequirementVersionId()).thenReturn(9L);
        assertThat(service.selectCurrentSnapshot(
                41L, 7L, "snapshot-1", context.username(), context))
                .isEqualTo("snapshot-1");
        verify(requirement).pointToAnalysis(eq("snapshot-1"), any(Instant.class));
        verify(requirements).save(requirement);
    }

    @Test
    void snapshotSelectionHidesMissingTenantParents() {
        ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
        RequirementAnalysisSnapshotRepository snapshots =
                mock(RequirementAnalysisSnapshotRepository.class);
        CopilotResultPersistenceService service = new CopilotResultPersistenceService(
                requirements, snapshots);
        String scopeKey = PortfolioScope.key(context.username(), context);
        when(requirements.findByIdAndProjectIdAndScopeKey(7L, 41L, scopeKey))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectCurrentSnapshot(
                41L, 7L, "snapshot-1", context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Requirement 7");
        verifyNoInteractions(snapshots);
    }

    private static boolean candidateMatches(
            UpsertProductCandidateRequest request,
            Long productId) {
        return request != null
                && productId.equals(request.productId())
                && request.coveragePercent() == 75
                && request.confidence() == 1.0
                && request.reviewStatus() == ReviewStatus.PROPOSED
                && request.strengths().contains("BP")
                && request.strengths().contains("CP")
                && request.openEvidence().contains("catalogue-source");
    }

    private static ProjectSolutionView projectSolution(
            Long id,
            List<TaxonomyCoverageView> coverage,
            List<SolutionProductCandidateView> candidates) {
        ProjectSolutionView projectSolution = mock(ProjectSolutionView.class);
        SolutionView solution = mock(SolutionView.class);
        when(projectSolution.id()).thenReturn(id);
        when(projectSolution.solution()).thenReturn(solution);
        when(projectSolution.productCandidates()).thenReturn(candidates);
        when(solution.taxonomyCoverage()).thenReturn(coverage);
        return projectSolution;
    }

    private static ProductView product(
            Long id,
            ProductStatus status,
            String source,
            List<TaxonomyCoverageView> coverage) {
        ProductView product = mock(ProductView.class);
        when(product.id()).thenReturn(id);
        when(product.productStatus()).thenReturn(status);
        when(product.sourceReference()).thenReturn(source);
        when(product.taxonomyCoverage()).thenReturn(coverage);
        return product;
    }

    private static TaxonomyCoverageView coverage(
            String code,
            int percent,
            ReviewStatus status) {
        TaxonomyCoverageView coverage = mock(TaxonomyCoverageView.class);
        when(coverage.nodeCode()).thenReturn(code);
        when(coverage.coveragePercent()).thenReturn(percent);
        when(coverage.reviewStatus()).thenReturn(status);
        return coverage;
    }
}
