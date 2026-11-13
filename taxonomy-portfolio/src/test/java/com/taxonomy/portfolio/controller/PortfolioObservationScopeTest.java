package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.service.PortfolioAggregationService;
import com.taxonomy.portfolio.service.PortfolioReportService;
import com.taxonomy.portfolio.service.ProjectConflictService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioObservationScopeTest {
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final PortfolioAggregationService aggregate = mock(PortfolioAggregationService.class);
    private final ProjectConflictService conflicts = mock(ProjectConflictService.class);
    private final PortfolioReportService reports = mock(PortfolioReportService.class);
    private final PortfolioQueryController query = new PortfolioQueryController(aggregate, conflicts, resolver);
    private final PortfolioReportController report = new PortfolioReportController(reports, resolver);

    @Test void matrixConflictAndReportUseOnlyTheAuthorizedReadSelection() {
        var selected = new WorkspaceContext("reader", "personal-copy", "review", "chosen-repository");
        when(resolver.resolveCurrentUsername()).thenReturn("reader");
        when(resolver.resolveUnambiguousContextForRead()).thenReturn(selected);
        when(reports.render(17L, 23L, PortfolioReportService.Format.JSON, "taxonomy", "reader", selected))
                .thenReturn(new PortfolioReportService.RenderedReport(new byte[]{1}, "application/json", "review.json"));
        query.portfolio(17L);
        query.listConflicts(17L);
        assertThat(report.report(17L,"json",23L,"taxonomy").getBody()).containsExactly(1);
        verify(aggregate).build(17L, "reader", selected);
        verify(conflicts).list(17L, "reader", selected);
        verify(resolver, never()).resolveCurrentContext();
    }

    @Test void aStaleSelectionStopsBeforeProjectLookupOrRendering() {
        when(resolver.resolveUnambiguousContextForRead()).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> query.portfolio(17L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> query.listConflicts(17L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> report.report(17L,"json",null,"taxonomy")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(aggregate, conflicts, reports);
        verify(resolver, never()).resolveCurrentContext();
    }
}
