package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.report.ArchitectureReportDocxRenderer;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SnapshotArchitectureReportControllerTest {
    @AfterEach
    void clearLocale() { LocaleContextHolder.resetLocaleContext(); }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "%%%", "de", " en "})
    void validAndFallbackLocalesDownloadTheSameFrozenEvidence(String language) throws Exception {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        Locale expected = " en ".equals(language) ? Locale.ENGLISH : Locale.GERMAN;
        var context = SnapshotWordReportServiceTest.CONTEXT;
        var decisions = mock(DecisionRationaleSnapshotReportService.class);
        var workbench = mock(ArchitectureWorkbenchService.class);
        when(decisions.generate(41L, "snapshot-1", "auditor", context, expected))
                .thenReturn(SnapshotWordReportServiceTest.decision());
        when(workbench.load(41L, "snapshot-1", "auditor", context))
                .thenReturn(SnapshotWordReportServiceTest.projection("Live title", "commit-a"));
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentContext()).thenReturn(context);
        when(resolver.resolveCurrentUsername()).thenReturn("auditor");
        var controller = new SnapshotArchitectureReportController(
                new SnapshotWordReportService(decisions, workbench), new ArchitectureReportDocxRenderer(), resolver);

        var response = controller.export(41L, "snapshot-1", language);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("taxonomy-architecture-report-v7.docx");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Snapshot-Id")).isEqualTo("snapshot-1");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Data-SHA256")).isEqualTo("data-sha");
        assertThat(response.getHeaders().getFirst("X-Taxonomy-Analysis-SHA256")).isEqualTo("analysis-sha");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(response.getBody()))) {
            assertThat(doc.getProperties().getCustomProperties().getProperty("taxonomy.graph.sha256").getLpwstr())
                    .isEqualTo(response.getHeaders().getFirst("X-Taxonomy-Graph-SHA256"));
            assertThat(new XWPFWordExtractor(doc).getText()).contains("Saved A", "Saved B", "R1", "snapshot-1",
                    expected.equals(Locale.GERMAN) ? "Architekturübersicht" : "Architecture overview");
        }
        verify(decisions).generate(41L, "snapshot-1", "auditor", context, expected);
    }

    @Test
    void unsupportedFormatHasNoRouteAndDoesNotLoadEvidence() throws Exception {
        var reports = mock(SnapshotWordReportService.class);
        var resolver = mock(WorkspaceResolver.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SnapshotArchitectureReportController(
                reports, new ArchitectureReportDocxRenderer(), resolver)).build();
        mvc.perform(get("/api/projects/41/snapshots/snapshot-1/architecture-report/pdf"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(reports, resolver);
    }
}
