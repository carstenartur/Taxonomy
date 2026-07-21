package com.taxonomy;

import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.architecture.service.ExplanationTraceService;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.ExplanationTrace;
import com.taxonomy.dto.RecommendedElement;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "report-admin", roles = "ADMIN")
class ArchitectureReportTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArchitectureReportService reportService;
    @Autowired
    private ReportRendererRegistry reportRendererRegistry;
    @Autowired
    private ExplanationTraceService traceService;

    @Test
    void reportServicePopulatesSectionsAndHandlesEmptyInput() {
        ArchitectureReport populated = reportService.generateReport(
                Map.of("CP-1010", 90, "BP-1010", 80),
                "secure voice communications",
                50);
        assertThat(populated.getBusinessText()).isEqualTo("secure voice communications");
        assertThat(populated.getScores()).hasSize(2);
        assertThat(populated.getArchitectureView()).isNotNull();
        assertThat(populated.getGapAnalysis()).isNotNull();
        assertThat(populated.getPatternDetection()).isNotNull();
        assertThat(populated.getRecommendation()).isNotNull();
        assertThat(populated.getMermaidDiagram()).isNotNull();

        ArchitectureReport empty = reportService.generateReport(null, "empty", 0);
        assertThat(empty.getScores()).isEmpty();
        assertThat(empty.getGeneratedAt()).isNotNull();
    }

    @Test
    void textRenderersMatchExistingServiceOutput() {
        ArchitectureReport report = sampleReport();
        assertThat(reportRendererRegistry.getRequired("markdown")
                .render(ReportRenderContext.of(report)).utf8())
                .isEqualTo(reportService.renderMarkdown(report))
                .contains("# Architecture Analysis Report", "```mermaid");
        assertThat(reportRendererRegistry.getRequired("html")
                .render(ReportRenderContext.of(report)).utf8())
                .isEqualTo(reportService.renderHtml(report))
                .contains("<!DOCTYPE html>", "<table>", "<strong>");
    }

    @Test
    void docxRendererProducesZipPackage() {
        byte[] docx = reportRendererRegistry.getRequired("docx")
                .render(ReportRenderContext.of(sampleReport())).bytes();
        assertThat(docx).hasSizeGreaterThan(2);
        assertThat(docx[0]).isEqualTo((byte) 0x50);
        assertThat(docx[1]).isEqualTo((byte) 0x4B);
    }

    @Test
    void rendererRegistryListsFormatsAndNormalizesIds() {
        assertThat(reportRendererRegistry.listDescriptors())
                .extracting(ReportFormatDescriptor::id)
                .contains("markdown", "html", "docx", "json");
        assertThat(reportRendererRegistry.getRequired(" markdown ").descriptor().id())
                .isEqualTo("markdown");
    }

    @Test
    void rendererRegistryRejectsNormalizedDuplicateIds() {
        ReportRendererExtension first = renderer("markdown");
        ReportRendererExtension duplicate = renderer(" markdown ");
        assertThatThrownBy(() -> new ReportRendererRegistry(List.of(first, duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate report renderer format ID: markdown");
    }

    @Test
    void reportEndpointsReturnExpectedFormatsAndRejectMissingScores() throws Exception {
        String request = "{\"scores\":{\"CP-1010\":90},\"businessText\":\"test\",\"minScore\":50}";
        mockMvc.perform(post("/api/report/markdown")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"architecture-report.md\""));
        mockMvc.perform(post("/api/report/html")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"architecture-report.html\""));
        mockMvc.perform(post("/api/report/docx")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"architecture-report.docx\""));
        mockMvc.perform(post("/api/report/json")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessText").value("test"))
                .andExpect(jsonPath("$.viewContext").exists());
        mockMvc.perform(post("/api/report/markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void explanationTraceProvidesStructuredEvidence() {
        ExplanationTrace trace = traceService.buildTrace(
                "CP-1010",
                Map.of("CP-1010", 85),
                Map.of(),
                "communications secure voice capability");
        assertThat(trace.getSemanticScore()).isGreaterThan(0);
        assertThat(trace.getTaxonomyRoot()).isNotNull();
        assertThat(trace.getScoreBreakdown())
                .extracting(ExplanationTrace.ScoreComponent::getFactor)
                .contains("Direct LLM Score", "Keyword Overlap", "Hierarchy Depth");
        assertThat(trace.getRelationPath()).isNotEmpty();
    }

    @Test
    void explanationEndpointsAndRecommendedElementIntegrationWork() throws Exception {
        mockMvc.perform(post("/api/explain/CP-1010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP-1010\":85},\"businessText\":\"capability test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreBreakdown[0].factor").value("Direct LLM Score"));

        RecommendedElement element = new RecommendedElement(
                "CP-1010", "Test", "CP", 90, "reason");
        ExplanationTrace trace = new ExplanationTrace();
        trace.setSummaryText("test trace");
        element.setExplanationTrace(trace);
        assertThat(element.getExplanationTrace().getSummaryText()).isEqualTo("test trace");
    }

    private ArchitectureReport sampleReport() {
        return reportService.generateReport(
                Map.of("CP-1010", 90), "test requirement", 50);
    }

    private static ReportRendererExtension renderer(String id) {
        return new ReportRendererExtension() {
            @Override
            public ReportFormatDescriptor descriptor() {
                return new ReportFormatDescriptor(
                        id, id + " display", "txt", "text/plain", false);
            }

            @Override
            public ReportRenderResult render(ReportRenderContext context) {
                return new ReportRenderResult(new byte[0]);
            }
        };
    }
}
