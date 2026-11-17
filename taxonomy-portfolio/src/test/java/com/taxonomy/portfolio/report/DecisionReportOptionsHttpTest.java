package com.taxonomy.portfolio.report;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;

import com.taxonomy.architecture.decision.*;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.extension.api.report.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.diagram.VisioDrawingOptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionReportOptionsHttpTest {
    @ParameterizedTest
    @CsvSource({"html,FULL,false", "json,FULL,false", "visio,,false", "visio-pdf,,false",
            "visio,,true", "visio-pdf,,true"})
    void fullReportsAndDrawingExportsLoadSavedStructuralEvidence(String format, String profile, boolean wall) throws Exception {
        var service = mock(DecisionRationaleSnapshotReportService.class);
        var words = mock(SnapshotWordReportService.class);
        var workspace = mock(WorkspaceResolver.class);
        when(workspace.resolveCurrentContext()).thenReturn(SnapshotWordReportServiceTest.CONTEXT);
        when(workspace.resolveCurrentUsername()).thenReturn("auditor");
        when(words.loadEvidence(eq(41L), eq("snapshot-1"), eq("auditor"), any(), eq(Locale.GERMAN), any()))
                .thenReturn(new SnapshotWordReportService.Source(SnapshotWordReportServiceTest.decision(), null));
        var renderer = mock(ReportRendererExtension.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(renderer.reportTypeId()).thenReturn(DecisionRationaleReportPlugin.REPORT_TYPE_ID);
        doReturn(DecisionRationaleReport.class).when(renderer).reportModelType();
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor(format, format, format, "application/octet-stream", false));
        when(renderer.render(any())).thenReturn(new ReportRenderResult(new byte[]{1,2,3}));
        var mvc = MockMvcBuilders.standaloneSetup(new DecisionRationaleSnapshotReportController(service,
                new ReportRendererRegistry(List.of(renderer)), workspace, words)).build();
        var request = get("/api/projects/41/snapshots/snapshot-1/decision-report/" + format).param("language", "de");
        if (profile != null) request.param("profile", profile);
        if (wall) request.param("visio.mode", "WALL").param("visio.paper", "A0")
                .param("visio.appendix", "true").param("visio.shortReasons", "true");
        mvc.perform(request).andExpect(status().isOk());
        var options = ArgumentCaptor.forClass(DecisionReportOptions.class);
        verify(words).loadEvidence(eq(41L), eq("snapshot-1"), eq("auditor"), any(), eq(Locale.GERMAN), options.capture());
        if (wall) {
            assertThat(options.getValue().visio()).isEqualTo(new DecisionReportOptions.Visio(
                    VisioDrawingOptions.Mode.WALL, VisioDrawingOptions.Paper.A0, true, true));
        } else if (format.startsWith("visio")) {
            assertThat(options.getValue().visio().mode()).isEqualTo(VisioDrawingOptions.Mode.SCREEN);
            assertThat(options.getValue().showResponsibilities()).isTrue();
        } else assertThat(options.getValue().visio()).isNull();
        verify(words, never()).load(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"docx","html","json","visio-pdf"})
    void compactSelectionIsBoundConsistentlyWithoutLoadingArchitecture(String format) throws Exception {
        var service = mock(DecisionRationaleSnapshotReportService.class);
        var words = mock(SnapshotWordReportService.class);
        var workspace = mock(WorkspaceResolver.class);
        when(workspace.resolveCurrentContext()).thenReturn(SnapshotWordReportServiceTest.CONTEXT);
        when(workspace.resolveCurrentUsername()).thenReturn("auditor");
        var renderer = mock(ReportRendererExtension.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(renderer.reportTypeId()).thenReturn(DecisionRationaleReportPlugin.REPORT_TYPE_ID);
        doReturn(DecisionRationaleReport.class).when(renderer).reportModelType();
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor(format,format,format,"application/octet-stream",false));
        when(renderer.render(any())).thenReturn(new ReportRenderResult(new byte[]{1,2,3}));
        when(service.generate(eq(41L),eq("snapshot-1"),eq("auditor"),any(),eq(Locale.GERMAN),any()))
                .thenReturn(SnapshotWordReportServiceTest.decision());
        var registry = new ReportRendererRegistry(List.of(renderer));
        var mvc = MockMvcBuilders.standaloneSetup(new DecisionRationaleSnapshotReportController(service,registry,workspace,words)).build();
        mvc.perform(get("/api/projects/41/snapshots/snapshot-1/decision-report/"+format)
                .param("language","de").param("profile","COMPACT").param("taxonomyRoots","CP","BP")
                .param("contents","NONE").param("treeLayout","A4_LANDSCAPE").param("showResponsibilities","true"))
                .andExpect(status().isOk()).andExpect(header().string("X-Taxonomy-Snapshot-Id","snapshot-1"));
        var options = ArgumentCaptor.forClass(DecisionReportOptions.class);
        verify(service).generate(eq(41L),eq("snapshot-1"),eq("auditor"),any(),eq(Locale.GERMAN),options.capture());
        assertThat(options.getValue().taxonomyRoots()).containsExactly("BP","CP");
        assertThat(options.getValue().showResponsibilities()).isTrue();
        assertThat(options.getValue().contents()).isEqualTo(DecisionReportOptions.Contents.NONE);
        assertThat(options.getValue().treeLayout()).isEqualTo(DecisionReportOptions.TreeLayout.A4_LANDSCAPE);
        verifyNoInteractions(words);
        mvc.perform(get("/api/projects/41/snapshots/snapshot-1/decision-report/"+format).param("profile","unknown"))
                .andExpect(status().isBadRequest());
        verify(service,times(1)).generate(any(),any(),any(),any(),any(),any());
    }
}
