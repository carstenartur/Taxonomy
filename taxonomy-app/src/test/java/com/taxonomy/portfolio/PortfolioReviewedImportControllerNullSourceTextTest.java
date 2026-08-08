package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.PortfolioReviewedImportController;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ImportDecision;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.PersistedReviewImport;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportItem;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.ReviewedImportRequest;
import com.taxonomy.portfolio.service.PortfolioReviewedImportService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioReviewedImportControllerNullSourceTextTest {

    @Mock private PortfolioReviewedImportService importService;
    @Mock private ProjectRequirementAnalysisService analysisService;
    @Mock private WorkspaceResolver workspaceResolver;

    @Test
    void nonNullSourceWithNullOriginalTextIsCountedSafely() {
        WorkspaceContext context =
                new WorkspaceContext("architect", "ws-architect", "feature/import");
        SourceReference source = new SourceReference(
                null, null, List.of(), "section-1", 2, null);
        ReviewedImportItem item = new ReviewedImportItem(
                ImportDecision.NEW_REQUIREMENT,
                null,
                "REQ-NULL-SOURCE",
                "Null source text",
                "a",
                null,
                null,
                null,
                source);
        ReviewedImportRequest request = new ReviewedImportRequest(
                List.of(item), false, null, null, null);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(importService.persist(41L, request.items(), context.username(), context))
                .thenReturn(new PersistedReviewImport(List.of(), List.of()));
        PortfolioReviewedImportController controller =
                new PortfolioReviewedImportController(
                        importService, analysisService, workspaceResolver, 10, 1);

        var response = controller.importReviewed(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(importService).persist(
                41L, request.items(), context.username(), context);
        verifyNoInteractions(analysisService);
    }
}
