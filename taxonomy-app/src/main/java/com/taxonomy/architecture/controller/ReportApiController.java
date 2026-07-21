package com.taxonomy.architecture.controller;

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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/report")
@Tag(name = "Report Export")
public class ReportApiController {

    private final ArchitectureReportService reportService;
    private final ReportRendererRegistry reportRendererRegistry;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;

    public ReportApiController(ArchitectureReportService reportService,
                               ReportRendererRegistry reportRendererRegistry,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver) {
        this.reportService = reportService;
        this.reportRendererRegistry = reportRendererRegistry;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    public record ReportRequest(
            Map<String, Integer> scores,
            String businessText,
            int minScore) {
    }

    @Operation(summary = "Export Markdown report",
            description = "Generates a comprehensive architecture report as Markdown")
    @ApiResponse(responseCode = "200", description = "Markdown report returned")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/markdown")
    public ResponseEntity<byte[]> exportMarkdown(@RequestBody ReportRequest request) {
        return render(request, "markdown");
    }

    @Operation(summary = "Export HTML report",
            description = "Generates a comprehensive architecture report as standalone HTML")
    @ApiResponse(responseCode = "200", description = "HTML report returned")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/html")
    public ResponseEntity<byte[]> exportHtml(@RequestBody ReportRequest request) {
        return render(request, "html");
    }

    @Operation(summary = "Export DOCX report",
            description = "Generates a comprehensive architecture report as Word document")
    @ApiResponse(responseCode = "200", description = "DOCX report returned as binary")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/docx")
    public ResponseEntity<byte[]> exportDocx(@RequestBody ReportRequest request) {
        return render(request, "docx");
    }

    @Operation(summary = "Export JSON report",
            description = "Returns the full architecture report as structured JSON")
    @ApiResponse(responseCode = "200", description = "JSON report returned")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/json")
    public ResponseEntity<ArchitectureReport> exportJson(@RequestBody ReportRequest request) {
        if (!isValid(request)) {
            return ResponseEntity.badRequest().build();
        }
        ArchitectureReport report = generateReport(request);
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        String username = context.username();
        String branch = repositoryStateService.resolveWorkspaceBranch(username);
        report.setViewContext(repositoryStateService.getViewContext(username, branch, context));
        return ResponseEntity.ok(report);
    }

    private ResponseEntity<byte[]> render(ReportRequest request, String formatId) {
        if (!isValid(request)) {
            return ResponseEntity.badRequest().build();
        }
        ArchitectureReport report = generateReport(request);
        ReportRendererExtension renderer = reportRendererRegistry.getRequired(formatId);
        ReportFormatDescriptor format = renderer.descriptor();
        ReportRenderResult rendered = renderer.render(ReportRenderContext.of(report));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report."
                                + format.fileExtension() + "\"")
                .contentType(MediaType.parseMediaType(format.contentType()))
                .body(rendered.bytes());
    }

    private ArchitectureReport generateReport(ReportRequest request) {
        return reportService.generateReport(
                request.scores(), request.businessText(), request.minScore());
    }

    private boolean isValid(ReportRequest request) {
        return request != null && request.scores() != null && !request.scores().isEmpty();
    }
}
