package com.taxonomy.versioning.controller;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.versioning.controller.DecisionRationaleReportController.DecisionReportRequest;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DecisionRationaleReportControllerValidationTest {

    @Test
    void rejectsMalformedDiscrepanciesBeforeResolvingTrustedContext() {
        DecisionRationaleReportService reportService =
                mock(DecisionRationaleReportService.class);
        ReportRendererRegistry rendererRegistry =
                mock(ReportRendererRegistry.class);
        RepositoryStateService repositoryStateService =
                mock(RepositoryStateService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        DecisionRationaleReportController controller =
                new DecisionRationaleReportController(
                        reportService,
                        rendererRegistry,
                        repositoryStateService,
                        workspaceResolver);

        List<List<TaxonomyDiscrepancy>> invalidCases = new ArrayList<>();
        invalidCases.add(Collections.singletonList(null));
        invalidCases.add(List.of(new TaxonomyDiscrepancy(" ", 100, 100)));
        invalidCases.add(List.of(new TaxonomyDiscrepancy(
                "X".repeat(257), 100, 100)));
        invalidCases.add(List.of(new TaxonomyDiscrepancy("CP", -1, 100)));
        invalidCases.add(List.of(new TaxonomyDiscrepancy("CP", 101, 100)));
        invalidCases.add(List.of(new TaxonomyDiscrepancy("CP", 100, -1)));
        invalidCases.add(Collections.nCopies(
                10_001, new TaxonomyDiscrepancy("CP", 100, 101)));

        for (List<TaxonomyDiscrepancy> discrepancies : invalidCases) {
            assertThat(controller.exportJson(request(discrepancies)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(
                reportService,
                rendererRegistry,
                repositoryStateService,
                workspaceResolver);
    }

    @Test
    void acceptsBoundedDiscrepancyIncludingLegitimateOversubscription() {
        DecisionRationaleReportService reportService =
                mock(DecisionRationaleReportService.class);
        ReportRendererRegistry rendererRegistry =
                mock(ReportRendererRegistry.class);
        RepositoryStateService repositoryStateService =
                mock(RepositoryStateService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        DecisionRationaleReportController controller =
                new DecisionRationaleReportController(
                        reportService,
                        rendererRegistry,
                        repositoryStateService,
                        workspaceResolver);
        when(workspaceResolver.resolveCurrentContext())
                .thenThrow(new IllegalStateException("validation passed"));

        assertThatThrownBy(() -> controller.exportJson(request(List.of(
                new TaxonomyDiscrepancy("CP", 100, 250)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("validation passed");

        verify(workspaceResolver).resolveCurrentContext();
        verifyNoInteractions(
                reportService,
                rendererRegistry,
                repositoryStateService);
    }

    private DecisionReportRequest request(
            List<TaxonomyDiscrepancy> discrepancies) {
        return new DecisionReportRequest(
                Map.of("CP", 100),
                Map.of("CP", "reason"),
                "bounded requirement",
                "MOCK",
                "SUCCESS",
                discrepancies,
                "en");
    }
}
