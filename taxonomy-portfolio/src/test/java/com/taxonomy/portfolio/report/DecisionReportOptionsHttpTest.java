package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.decision.*;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.extension.api.report.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionReportOptionsHttpTest {
    @ParameterizedTest @ValueSource(strings={"html","json"})
    void configuredNonWordExportsLoadStructuralEvidenceWithoutWordPaginationLimits(String format) throws Exception {
        var service = mock(DecisionRationaleSnapshotReportService.class);
        var words = mock(SnapshotWordReportService.class);
        var workspace = mock(WorkspaceResolver.class);
        when(workspace.resolveCurrentContext()).thenReturn(SnapshotWordReportServiceTest.CONTEXT);
        when(workspace.resolveCurrentUsername()).thenReturn("auditor");
        when(words.loadEvidence(eq(41L), eq("snapshot-1"), eq("auditor"), any(), eq(Locale.GERMAN), any()))
                .thenReturn(new SnapshotWordReportService.Source(SnapshotWordReportServiceTest.decision(), null));
        var renderer = mock(ReportRendererExtension.class);
        when(renderer.reportTypeId()).thenReturn(DecisionRationaleReportPlugin.REPORT_TYPE_ID);
        doReturn(DecisionRationaleReport.class).when(renderer).reportModelType();
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor(format, format, format, "application/octet-stream", false));
        when(renderer.render(any())).thenReturn(new ReportRenderResult(new byte[]{1,2,3}));
        var mvc = MockMvcBuilders.standaloneSetup(new DecisionRationaleSnapshotReportController(service,
                new ReportRendererRegistry(List.of(renderer)), workspace, words)).build();
        mvc.perform(get("/api/projects/41/snapshots/snapshot-1/decision-report/" + format)
                .param("language", "de").param("profile", "FULL")).andExpect(status().isOk());
        verify(words).loadEvidence(eq(41L), eq("snapshot-1"), eq("auditor"), any(), eq(Locale.GERMAN), any());
        verify(words, never()).load(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"docx","html","json"})
    void compactSelectionIsBoundConsistentlyWithoutLoadingArchitecture(String format) throws Exception {
        var service = mock(DecisionRationaleSnapshotReportService.class);
        var words = mock(SnapshotWordReportService.class);
        var workspace = mock(WorkspaceResolver.class);
        when(workspace.resolveCurrentContext()).thenReturn(SnapshotWordReportServiceTest.CONTEXT);
        when(workspace.resolveCurrentUsername()).thenReturn("auditor");
        var renderer = mock(ReportRendererExtension.class);
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
                .param("contents","NONE").param("treeLayout","A4_LANDSCAPE"))
                .andExpect(status().isOk()).andExpect(header().string("X-Taxonomy-Snapshot-Id","snapshot-1"));
        var options = ArgumentCaptor.forClass(DecisionReportOptions.class);
        verify(service).generate(eq(41L),eq("snapshot-1"),eq("auditor"),any(),eq(Locale.GERMAN),options.capture());
        assertThat(options.getValue().taxonomyRoots()).containsExactly("BP","CP");
        assertThat(options.getValue().contents()).isEqualTo(DecisionReportOptions.Contents.NONE);
        assertThat(options.getValue().treeLayout()).isEqualTo(DecisionReportOptions.TreeLayout.A4_LANDSCAPE);
        verifyNoInteractions(words);
        mvc.perform(get("/api/projects/41/snapshots/snapshot-1/decision-report/"+format).param("profile","unknown"))
                .andExpect(status().isBadRequest());
        verify(service,times(1)).generate(any(),any(),any(),any(),any(),any());
    }
}
