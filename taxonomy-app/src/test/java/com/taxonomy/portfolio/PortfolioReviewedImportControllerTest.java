package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.PortfolioReviewedImportController;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ImportDecision;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.PersistedReviewImport;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportItem;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioReviewedImportService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioReviewedImportControllerTest {

    @Mock private PortfolioReviewedImportService importService;
    @Mock private ProjectRequirementAnalysisService analysisService;
    @Mock private WorkspaceResolver workspaceResolver;

    private final WorkspaceContext context =
            new WorkspaceContext("architect", "ws-architect", "feature/reviewed-import");

    @Test
    void missingItemsFailBeforeWorkspaceOrServiceResolution() {
        var controller = controller(10, 1_000);
        List<ReviewedImportRequest> invalidRequests = Arrays.asList(
                null,
                new ReviewedImportRequest(null, false, null, null, null),
                new ReviewedImportRequest(List.of(), false, null, null, null));

        for (ReviewedImportRequest request : invalidRequests) {
            PortfolioException error = catchThrowableOfType(
                    () -> controller.importReviewed(41L, request), PortfolioException.class);

            assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.VALIDATION);
            assertThat(error).hasMessage("At least one reviewed import item is required");
        }
        verifyNoInteractions(importService, analysisService, workspaceResolver);
    }

    @Test
    void oversizedItemListFailsBeforeAnySideEffect() {
        var controller = controller(1, 1_000);
        var request = request(List.of(item("REQ-1", "one", null),
                item("REQ-2", "two", null)), false);

        PortfolioException error = catchThrowableOfType(
                () -> controller.importReviewed(41L, request), PortfolioException.class);

        assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.VALIDATION);
        assertThat(error).hasMessage("Reviewed import contains more than 1 items");
        verifyNoInteractions(importService, analysisService, workspaceResolver);
    }

    @Test
    void combinedReviewedAndSourceTextLimitFailsBeforeAnySideEffect() {
        var controller = controller(10, 5);
        SourceReference source = new SourceReference(
                null, null, List.of(), "section-1", 2, "def");
        var request = request(List.of(item("REQ-1", "abc", source)), false);

        PortfolioException error = catchThrowableOfType(
                () -> controller.importReviewed(41L, request), PortfolioException.class);

        assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.VALIDATION);
        assertThat(error).hasMessage("Reviewed import exceeds 5 text characters");
        verifyNoInteractions(importService, analysisService, workspaceResolver);
    }

    @Test
    void nullItemsTextAndSourceAreCountedSafely() {
        stubWorkspace();
        var controller = controller(10, 1);
        List<ReviewedImportItem> items = Arrays.asList(
                null,
                item("REQ-NULL", null, null));
        var request = request(items, false);
        var persisted = new PersistedReviewImport(List.of(), List.of());
        when(importService.persist(41L, items, context.username(), context))
                .thenReturn(persisted);

        var response = controller.importReviewed(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().analysisJob()).isNull();
        verify(analysisService, never()).enqueueProject(
                eq(41L), org.mockito.ArgumentMatchers.any(),
                eq(context.username()), eq(context));
    }

    @Test
    void configuredNonPositiveLimitsAreClampedInsteadOfDisablingValidation() {
        var itemLimited = controller(0, 1_000);
        var tooMany = request(List.of(
                item("REQ-1", "a", null),
                item("REQ-2", "b", null)), false);
        PortfolioException itemError = catchThrowableOfType(
                () -> itemLimited.importReviewed(41L, tooMany), PortfolioException.class);
        assertThat(itemError).hasMessage("Reviewed import contains more than 1 items");

        var characterLimited = controller(100, 0);
        var tooLong = request(List.of(item("REQ-3", "ab", null)), false);
        PortfolioException characterError = catchThrowableOfType(
                () -> characterLimited.importReviewed(41L, tooLong), PortfolioException.class);
        assertThat(characterError).hasMessage("Reviewed import exceeds 1 text characters");

        verifyNoInteractions(importService, analysisService, workspaceResolver);
    }

    @Test
    void importWithoutRequestedAnalysisReturnsCreatedAndPreservesContext() {
        stubWorkspace();
        var controller = controller(10, 1_000);
        var request = request(List.of(item("REQ-1", "text", null)), false);
        RequirementView created = requirement(7L);
        var persisted = new PersistedReviewImport(List.of(created), List.of());
        when(importService.persist(
                41L, request.items(), context.username(), context))
                .thenReturn(persisted);

        var response = controller.importReviewed(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().newRequirements()).containsExactly(created);
        assertThat(response.getBody().versionedRequirements()).isEmpty();
        assertThat(response.getBody().analysisJob()).isNull();
        verify(workspaceResolver).resolveCurrentUsername();
        verify(workspaceResolver).resolveCurrentContext();
        verify(importService).persist(
                41L, request.items(), context.username(), context);
        verify(analysisService, never()).enqueueProject(
                eq(41L), org.mockito.ArgumentMatchers.any(),
                eq(context.username()), eq(context));
    }

    @Test
    void requestedAnalysisIsNotEnqueuedWhenNoRequirementWasAffected() {
        stubWorkspace();
        var controller = controller(10, 1_000);
        var request = request(List.of(item("REQ-1", "text", null)), true);
        when(importService.persist(
                41L, request.items(), context.username(), context))
                .thenReturn(new PersistedReviewImport(List.of(), List.of()));

        var response = controller.importReviewed(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().analysisJob()).isNull();
        verify(analysisService, never()).enqueueProject(
                eq(41L), org.mockito.ArgumentMatchers.any(),
                eq(context.username()), eq(context));
    }

    @Test
    void requestedAnalysisReturnsAcceptedLocationAndDeduplicatedRequest() {
        stubWorkspace();
        var controller = controller(10, 1_000);
        var request = new ReviewedImportRequest(
                List.of(item("REQ-1", "text", null)),
                true,
                "MOCK",
                33,
                "review-import-key");
        RequirementView first = requirement(7L);
        RequirementView duplicate = requirement(7L);
        RequirementView second = requirement(8L);
        var persisted = new PersistedReviewImport(
                List.of(first), List.of(duplicate, second));
        AnalysisJobView job = pendingJob("job-reviewed-import");
        when(importService.persist(
                41L, request.items(), context.username(), context))
                .thenReturn(persisted);
        when(analysisService.enqueueProject(
                eq(41L), org.mockito.ArgumentMatchers.any(AnalyzeProjectRequest.class),
                eq(context.username()), eq(context)))
                .thenReturn(job);

        var response = controller.importReviewed(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/projects/41/analysis-jobs/job-reviewed-import");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().newRequirements()).containsExactly(first);
        assertThat(response.getBody().versionedRequirements())
                .containsExactly(duplicate, second);
        assertThat(response.getBody().analysisJob()).isSameAs(job);

        ArgumentCaptor<AnalyzeProjectRequest> requestCaptor =
                ArgumentCaptor.forClass(AnalyzeProjectRequest.class);
        verify(analysisService).enqueueProject(
                eq(41L), requestCaptor.capture(), eq(context.username()), eq(context));
        AnalyzeProjectRequest analysisRequest = requestCaptor.getValue();
        assertThat(analysisRequest.requirementIds()).containsExactly(7L, 8L);
        assertThat(analysisRequest.analyzeAll()).isFalse();
        assertThat(analysisRequest.provider()).isEqualTo("MOCK");
        assertThat(analysisRequest.maxArchitectureNodes()).isEqualTo(33);
        assertThat(analysisRequest.idempotencyKey()).isEqualTo("review-import-key");
        verify(workspaceResolver).resolveCurrentUsername();
        verify(workspaceResolver).resolveCurrentContext();
    }

    private void stubWorkspace() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

    private PortfolioReviewedImportController controller(int maximumItems,
                                                          long maximumCharacters) {
        return new PortfolioReviewedImportController(
                importService,
                analysisService,
                workspaceResolver,
                maximumItems,
                maximumCharacters);
    }

    private ReviewedImportRequest request(List<ReviewedImportItem> items,
                                          boolean analyzeAfterImport) {
        return new ReviewedImportRequest(items, analyzeAfterImport, null, null, null);
    }

    private ReviewedImportItem item(String key, String text, SourceReference source) {
        return new ReviewedImportItem(
                ImportDecision.NEW_REQUIREMENT,
                null,
                key,
                "Title " + key,
                text,
                null,
                null,
                null,
                source);
    }

    private RequirementView requirement(Long id) {
        Instant now = Instant.parse("2026-08-08T10:00:00Z");
        return new RequirementView(
                id,
                41L,
                "REQ-" + id,
                "Requirement " + id,
                null,
                1,
                null,
                null,
                null,
                context.username(),
                null,
                null,
                now,
                now,
                null);
    }

    private AnalysisJobView pendingJob(String id) {
        return new AnalysisJobView(
                id,
                41L,
                AnalysisStatus.PENDING,
                "review-import-key",
                "MOCK",
                33,
                context.username(),
                context.workspaceId(),
                Instant.parse("2026-08-08T10:00:00Z"),
                null,
                null,
                2,
                0,
                0,
                0,
                null,
                List.of());
    }
}
