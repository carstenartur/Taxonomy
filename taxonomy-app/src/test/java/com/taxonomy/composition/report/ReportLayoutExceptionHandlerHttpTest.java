package com.taxonomy.composition.report;

import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.reporting.render.document.WordReportLayoutException;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReportLayoutExceptionHandlerHttpTest {
    private static final String REQUEST = """
            {"scores":{"CP-1023":75},"businessText":"Report fixture","minScore":0}
            """;

    @Test
    void layoutFailureRetainsActionableConflictAheadOfTheGlobalBadRequestHandler() throws Exception {
        String message = "Word template cannot fit detail at minimum 8pt reading scale";
        var mvc = reportEndpointThrowing(new WordReportLayoutException(message));

        mvc.perform(post("/api/report/docx").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/api/report/docx"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void ordinaryRendererValidationStillUsesTheGlobalBadRequestContract() throws Exception {
        var mvc = reportEndpointThrowing(new IllegalArgumentException("Unsupported report options"));

        mvc.perform(post("/api/report/docx").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Unsupported report options"));
    }

    private MockMvc reportEndpointThrowing(RuntimeException failure) {
        var reports = mock(ArchitectureReportService.class);
        when(reports.generateReport(Map.of("CP-1023", 75), "Report fixture", 0))
                .thenReturn(new ArchitectureReport());
        var renderer = new ReportRendererExtension() {
            @Override
            public ReportFormatDescriptor descriptor() {
                return new ReportFormatDescriptor("docx", "Word", "docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false);
            }

            @Override
            public ReportRenderResult render(ReportRenderContext context) {
                throw failure;
            }
        };
        var controller = new ReportApiController(reports, new ReportRendererRegistry(List.of(renderer)),
                mock(RepositoryStateService.class), mock(WorkspaceResolver.class));
        return standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()),
                        new ReportLayoutExceptionHandler())
                .build();
    }
}
