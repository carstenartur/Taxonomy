package com.taxonomy.composition.report;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.portfolio.report.DecisionRationaleSnapshotReportController;
import com.taxonomy.portfolio.report.DecisionRationaleSnapshotReportService;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionReportOptionsValidationHttpTest {
    @Test void contributedFormatUsesTheExistingAuthorizedReportAssemblyAndProvenance() throws Exception {
        var reports=mock(DecisionRationaleReportService.class);
        var catalog=new com.taxonomy.extension.runtime.PluginCatalog();
        var registry=new ReportRendererRegistry(catalog,java.util.List.of());
        var state=mock(RepositoryStateService.class);var workspace=mock(WorkspaceResolver.class);
        var context=new com.taxonomy.workspace.service.WorkspaceContext("architect","workspace","draft","repository");
        when(workspace.resolveCurrentContext()).thenReturn(context);
        when(state.resolveWorkspaceBranch("architect")).thenReturn("draft");
        var report=mock(com.taxonomy.reporting.api.decision.DecisionRationaleReport.class,RETURNS_DEEP_STUBS);
        when(report.metadata().taxonomyDataFingerprintSha256()).thenReturn("data-sha");
        when(report.metadata().analysisSnapshotFingerprintSha256()).thenReturn("analysis-sha");
        when(reports.generate(any(),eq(context),isNull(),any())).thenReturn(report);
        var semantics=mock(com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter.class);
        when(semantics.adapt(same(report),anyMap(),any())).thenReturn(report);
        var renderer=new com.taxonomy.extension.api.report.ReportRendererExtension() {
            public String reportTypeId(){return "decision-rationale";}
            public com.taxonomy.extension.api.report.ReportFormatDescriptor descriptor(){return new com.taxonomy.extension.api.report.ReportFormatDescriptor("example-markdown","Markdown","md","text/markdown",false);}
            public com.taxonomy.extension.api.report.ReportRenderResult render(com.taxonomy.extension.api.report.ReportRenderContext input) {
                org.assertj.core.api.Assertions.assertThat(input.payload()).isSameAs(report);
                return new com.taxonomy.extension.api.report.ReportRenderResult("# Decision".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        var identity=new com.taxonomy.extension.api.plugin.PluginIdentity("example.report","1.0.0","a".repeat(64));
        catalog.publish(new com.taxonomy.extension.api.plugin.PluginDescriptor(identity,">=1.0.0",
                java.util.List.of(),java.util.Set.of(),com.taxonomy.extension.api.plugin.PluginMode.DYNAMIC),java.util.List.of(renderer));
        var controller=new DecisionRationaleReportController(reports,registry,state,workspace,semantics,null);
        var mvc=MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(MessageSource.class))).build();
        mvc.perform(post("/api/decision-report/example-markdown").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"CP\":100},\"businessText\":\"Requirement\",\"provider\":\"MOCK\",\"analysisStatus\":\"SUCCESS\",\"language\":\"en\"}"))
                .andExpect(status().isOk()).andExpect(content().string("# Decision"))
                .andExpect(header().string("Content-Type","text/markdown"))
                .andExpect(header().string("X-Taxonomy-Analysis-SHA256","analysis-sha"))
                .andExpect(header().string("X-Taxonomy-Data-SHA256","data-sha"));
        verify(workspace).resolveCurrentContext();verify(state).getViewContext("architect","draft",context);
        catalog.beginDraining(identity);
        mvc.perform(post("/api/decision-report/example-markdown").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"CP\":100},\"businessText\":\"Requirement\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/decision-report/unknown-format").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"CP\":100},\"businessText\":\"Requirement\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void invalidTypedOptionsRemainClientErrorsWithTheApplicationAdvice() throws Exception {
        var controller = new DecisionRationaleReportController(mock(DecisionRationaleReportService.class),
                mock(ReportRendererRegistry.class), mock(RepositoryStateService.class), mock(WorkspaceResolver.class));
        var saved = new DecisionRationaleSnapshotReportController(mock(DecisionRationaleSnapshotReportService.class),
                mock(ReportRendererRegistry.class), mock(WorkspaceResolver.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller, saved)
                .setControllerAdvice(new GlobalExceptionHandler(mock(MessageSource.class))).build();
        mvc.perform(post("/api/decision-report/json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"exportOptions\":{\"profile\":\"WRONG\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/decision-report/json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"exportOptions\":{\"taxonomyRoots\":[]}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/1/snapshots/saved/decision-report/json").param("profile","WRONG"))
                .andExpect(status().isBadRequest());
    }
}
