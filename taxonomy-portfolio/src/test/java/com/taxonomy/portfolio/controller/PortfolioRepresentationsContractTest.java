package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.service.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Locale;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioRepresentationsContractTest {
    @Test void guiRoutesRetainTheirDedicatedTemplates() {
        var pages=new PortfolioPageController();
        assertThat(pages.projects()).isEqualTo("projects");
        assertThat(pages.requirementDetail(1L,2L)).isEqualTo("requirement-detail");
        assertThat(pages.matrices(1L)).isEqualTo("portfolio-matrices");
        assertThat(pages.documentImport(1L)).isEqualTo("portfolio-import");
        assertThat(pages.versioning(1L)).isEqualTo("portfolio-versioning");
        assertThat(pages.reports(1L)).isEqualTo("portfolio-reports");
    }
    @ParameterizedTest @EnumSource(PortfolioReportService.Format.class)
    void reportDownloadsPreserveFormatBytesAndScope(PortfolioReportService.Format format) {
        var service=mock(PortfolioReportService.class);var resolver=mock(WorkspaceResolver.class);
        var context=new WorkspaceContext("alice","ws-a","draft","repo-a");
        when(resolver.resolveCurrentUsername()).thenReturn("alice");when(resolver.resolveCurrentContext()).thenReturn(context);
        byte[] bytes={1,2,3};String filename="portfolio."+format.extension();
        when(service.render(1L,2L,format,"solution","alice",context))
                .thenReturn(new PortfolioReportService.RenderedReport(bytes,format.contentType(),filename));
        var response=new PortfolioReportController(service,resolver).report(1L,format.name().toLowerCase(Locale.ROOT),2L,"solution");
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getBody()).isSameAs(bytes);
        assertThat(response.getHeaders().getContentType()).hasToString(format.contentType());
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=\""+filename+"\"");
        verify(service).render(1L,2L,format,"solution","alice",context);
    }
    @Test void unsupportedFormatDoesNotResolveWorkspaceOrRender() {
        var service=mock(PortfolioReportService.class);var resolver=mock(WorkspaceResolver.class);
        assertThat(new PortfolioReportController(service,resolver).report(1L,"unrecognized",null,null).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(service,resolver);
    }
    @Test void requirementAnalysisPreservesExplicitOptionsAndAbsentBodyDefaults() {
        var service=mock(ProjectRequirementAnalysisService.class);var persistence=mock(PortfolioAnalysisPersistenceService.class);
        var resolver=mock(WorkspaceResolver.class);var context=new WorkspaceContext("alice","ws-a","draft","repo-a");
        when(resolver.resolveCurrentUsername()).thenReturn("alice");when(resolver.resolveCurrentContext()).thenReturn(context);
        var job=mock(AnalysisJobView.class);when(job.id()).thenReturn("job-a");
        when(service.enqueueRequirement(1L,2L,null,null,null,"alice",context)).thenReturn(job);
        var controller=new ProjectAnalysisController(service,persistence,resolver);
        var response=controller.analyzeRequirement(1L,2L,null);
        assertThat(response.getStatusCode().value()).isEqualTo(202);assertThat(response.getBody()).isSameAs(job);
        var request=new AnalyzeProjectRequest(java.util.List.of(2L),false,"mock",17,"request-a");
        when(service.enqueueRequirement(1L,2L,"mock",17,"request-a","alice",context)).thenReturn(job);
        assertThat(controller.analyzeRequirement(1L,2L,request).getBody()).isSameAs(job);
        verify(service).enqueueRequirement(1L,2L,"mock",17,"request-a","alice",context);
    }
}
