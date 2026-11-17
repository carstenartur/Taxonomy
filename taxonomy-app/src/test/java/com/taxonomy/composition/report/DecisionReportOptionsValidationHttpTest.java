package com.taxonomy.composition.report;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.portfolio.report.DecisionRationaleSnapshotReportController;
import com.taxonomy.portfolio.report.DecisionRationaleSnapshotReportService;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
        when(workspace.resolveUnambiguousContextForRead()).thenReturn(context);
        var source = new com.taxonomy.dto.ViewContext("a".repeat(40), "draft", java.time.Instant.EPOCH, true, false, false);
        when(state.readSelectedViewContext(context)).thenReturn(java.util.Optional.of(source));
        var report=mock(com.taxonomy.reporting.api.decision.DecisionRationaleReport.class,RETURNS_DEEP_STUBS);
        when(report.metadata().taxonomyDataFingerprintSha256()).thenReturn("data-sha");
        when(report.metadata().analysisSnapshotFingerprintSha256()).thenReturn("analysis-sha");
        when(reports.generate(any(),eq(context),eq(source),any())).thenReturn(report);
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
        verify(workspace).resolveUnambiguousContextForRead();verify(state).readSelectedViewContext(context);
        catalog.beginDraining(identity);
        mvc.perform(post("/api/decision-report/example-markdown").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"CP\":100},\"businessText\":\"Requirement\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/decision-report/unknown-format").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scores\":{\"CP\":100},\"businessText\":\"Requirement\"}"))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest
    @CsvSource({"visio,none,true", "visio-pdf,none,true", "visio,roots,true", "visio-pdf,opt-out,false"})
    void directDrawingPostsHonorRecordedOwnerDefaultsAndExplicitOptOut(
            String format, String selection, boolean show) throws Exception {
        var workspace = mock(WorkspaceResolver.class);
        var state = mock(RepositoryStateService.class);
        var context = new WorkspaceContext("architect", "workspace", "draft", "repository");
        var source = new ViewContext("a".repeat(40), "draft", java.time.Instant.EPOCH, true, false, false);
        when(workspace.resolveUnambiguousContextForRead()).thenReturn(context);
        when(state.readSelectedViewContext(context)).thenReturn(java.util.Optional.of(source));

        var report = mock(DecisionRationaleReport.class, RETURNS_DEEP_STUBS);
        var reports = mock(DecisionRationaleReportService.class);
        when(reports.generate(any(), any(), any(), any())).thenReturn(report);
        when(reports.generate(any(), any(), any(), any(), any())).thenReturn(report);
        when(reports.generate(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(report);
        var semantics = mock(DecisionRationaleScoreSemanticsAdapter.class);
        when(semantics.adapt(same(report), anyMap(), any())).thenReturn(report);
        var registry = mock(ReportRendererRegistry.class);
        var renderer = mock(ReportRendererExtension.class);
        when(registry.getRequired("decision-rationale", format)).thenReturn(renderer);
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor(format, "Drawing", "pdf", "application/pdf", true));
        when(renderer.render(any())).thenReturn(new ReportRenderResult(new byte[]{1}));
        var mvc = MockMvcBuilders.standaloneSetup(
                new DecisionRationaleReportController(reports, registry, state, workspace, semantics, null)).build();
        String optionField = switch (selection) {
            case "roots" -> ", \"exportOptions\":{\"taxonomyRoots\":[\"CP\"]}";
            case "opt-out" -> ", \"exportOptions\":{\"taxonomyRoots\":[\"CP\"],\"showResponsibilities\":false}";
            default -> "";
        };
        String body = """
                {"scores":{"CP":100},"businessText":"Original requirement","modelBinding":{
                 "state":"MODEL_UNCOMMITTED","repositoryId":"repository","workspaceScopeKey":"workspace",
                 "branch":"draft","modelSource":"WORKSPACE_REVISION","modelSha256":"%s",
                 "scope":{},"rootCodes":[],"briefFields":{},"references":[],"excludedRelationTypes":[]}%s}
                """.formatted("a".repeat(64), optionField);
        mvc.perform(post("/api/decision-report/" + format).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        var options = ArgumentCaptor.forClass(DecisionReportOptions.class);
        if (show) {
            var binding = ArgumentCaptor.forClass(AnalysisModelBinding.class);
            verify(reports).generate(any(), eq(context), eq(source), any(), options.capture(), binding.capture(), eq(false));
            org.assertj.core.api.Assertions.assertThat(binding.getValue().modelSha256()).isEqualTo("a".repeat(64));
        } else {
            verify(reports).generate(any(), eq(context), eq(source), any(), options.capture());
        }
        org.assertj.core.api.Assertions.assertThat(options.getValue().showResponsibilities()).isEqualTo(show);
        org.assertj.core.api.Assertions.assertThat(options.getValue().visio().mode().name()).isEqualTo("SCREEN");
        org.assertj.core.api.Assertions.assertThat(options.getValue().taxonomyRoots())
                .isEqualTo(selection.equals("none") ? java.util.Set.of() : java.util.Set.of("CP"));
    }

    @Test void inaccessibleGitStorageReturnsAnUnavailableSourceBeforeRendering() throws Exception {
        var state = mock(RepositoryStateService.class);
        var workspace = mock(WorkspaceResolver.class);
        var selected = new com.taxonomy.workspace.service.WorkspaceContext("architect", null, "review", "source");
        when(workspace.resolveUnambiguousContextForRead()).thenReturn(selected);
        when(state.readSelectedViewContext(selected)).thenThrow(new java.io.IOException("storage detail"));
        var reports = mock(DecisionRationaleReportService.class);
        var renderers = mock(ReportRendererRegistry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DecisionRationaleReportController(reports, renderers, state, workspace)).build();
        mvc.perform(post("/api/decision-report/json").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":100},\"businessText\":\"Requirement\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("X-Taxonomy-Source-State", "FAILED"))
                .andExpect(header().doesNotExist("X-Taxonomy-Commit")).andExpect(content().string(""));
        verifyNoInteractions(reports, renderers);
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
