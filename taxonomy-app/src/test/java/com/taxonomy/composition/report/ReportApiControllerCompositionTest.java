package com.taxonomy.composition.report;

import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportApiControllerCompositionTest {
    private final ArchitectureReportService reports = mock(ArchitectureReportService.class);
    private final ReportRendererRegistry renderers = mock(ReportRendererRegistry.class);
    private final RepositoryStateService repositories = mock(RepositoryStateService.class);
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final ReportApiController controller = new ReportApiController(reports, renderers, repositories, workspaces);
    private final ReportApiController.ReportRequest request = new ReportApiController.ReportRequest(Map.of("A", 50), "business", 17);

    @ParameterizedTest
    @ValueSource(strings = {"markdown", "html", "docx", "json"})
    void invalidRequestsRemainBadRequestsBeforeAnyBackendAccess(String format) {
        var invalid = new ReportApiController.ReportRequest[] {
                null, new ReportApiController.ReportRequest(null, "business", 0),
                new ReportApiController.ReportRequest(Map.of(), "business", 0)};
        for (var value : invalid) {
            assertThat(dispatch(format, value).getStatusCode().value()).isEqualTo(400);
        }
        verifyNoInteractions(reports, renderers, repositories, workspaces);
    }

    @ParameterizedTest
    @ValueSource(strings = {"markdown", "html", "docx"})
    void fileFormatsKeepRendererBytesAndDownloadHeaders(String format) {
        var report = new ArchitectureReport();
        when(reports.generateReport(request.scores(), request.businessText(), request.minScore())).thenReturn(report);
        var renderer = mock(ReportRendererExtension.class);
        var result = mock(ReportRenderResult.class);
        byte[] bytes = ("rendered-" + format).getBytes(StandardCharsets.UTF_8);
        when(result.bytes()).thenReturn(bytes);
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor(format, format, format, "application/octet-stream", false));
        when(renderer.render(any(ReportRenderContext.class))).thenReturn(result);
        when(renderers.getRequired(format)).thenReturn(renderer);
        var response = dispatch(format, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(bytes);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"architecture-report." + format + "\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/octet-stream");
        verify(reports).generateReport(request.scores(), request.businessText(), request.minScore());
        verifyNoInteractions(repositories, workspaces);
    }

    @Test
    void jsonResolvesTheCurrentWorkspaceAndKeepsTheSameReport() {
        var report = new ArchitectureReport();
        var workspace = mock(WorkspaceContext.class);
        when(reports.generateReport(request.scores(), request.businessText(), request.minScore())).thenReturn(report);
        when(workspaces.resolveCurrentContext()).thenReturn(workspace);
        when(workspace.username()).thenReturn("alice");
        when(repositories.resolveWorkspaceBranch("alice")).thenReturn("workspace/alice");
        var response = controller.exportJson(request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(report);
        verify(repositories).getViewContext("alice", "workspace/alice", workspace);
        verifyNoInteractions(renderers);
    }

    @Test
    void rendererFailuresRemainVisibleToApplicationErrorHandling() {
        when(reports.generateReport(request.scores(), request.businessText(), request.minScore())).thenReturn(new ArchitectureReport());
        var failure = new IllegalStateException("renderer unavailable");
        when(renderers.getRequired("docx")).thenThrow(failure);
        assertThatThrownBy(() -> controller.exportDocx(request)).isSameAs(failure);
        verifyNoInteractions(repositories, workspaces);
    }

    private ResponseEntity<?> dispatch(String format, ReportApiController.ReportRequest value) {
        return switch (format) {
            case "markdown" -> controller.exportMarkdown(value);
            case "html" -> controller.exportHtml(value);
            case "docx" -> controller.exportDocx(value);
            case "json" -> controller.exportJson(value);
            default -> throw new AssertionError(format);
        };
    }
}
