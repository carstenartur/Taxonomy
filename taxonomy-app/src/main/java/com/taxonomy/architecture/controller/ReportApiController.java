package com.taxonomy.architecture.controller;

import com.taxonomy.architecture.report.ReportFormatDescriptor;
import com.taxonomy.architecture.report.ReportRenderContext;
import com.taxonomy.architecture.report.ReportRenderResult;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for Architecture Report Export.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/report/markdown} — Export as Markdown (.md)</li>
 *   <li>{@code POST /api/report/html}     — Export as standalone HTML</li>
 *   <li>{@code POST /api/report/docx}     — Export as Word document (.docx)</li>
 *   <li>{@code POST /api/report/json}     — Export as structured JSON</li>
 * </ul>
 */
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

    /**
     * Request body for report generation.
     */
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
        if (request.scores() == null || request.scores().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        ArchitectureReport report = reportService.generateReport(
                request.scores(), request.businessText(), request.minScore());
        ReportFormatDescriptor format = reportRendererRegistry.getRequired("markdown").descriptor();
        ReportRenderResult rendered = reportRendererRegistry.getRequired("markdown")
                .render(ReportRenderContext.of(report));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report." + format.fileExtension() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, format.contentType())
                .body(rendered.bytes());
    }

    @Operation(summary = "Export HTML report",
               description = "Generates a comprehensive architecture report as standalone HTML")
    @ApiResponse(responseCode = "200", description = "HTML report returned")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/html")
    public ResponseEntity<byte[]> exportHtml(@RequestBody ReportRequest request) {
        if (request.scores() == null || request.scores().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        ArchitectureReport report = reportService.generateReport(
                request.scores(), request.businessText(), request.minScore());
        ReportFormatDescriptor format = reportRendererRegistry.getRequired("html").descriptor();
        ReportRenderResult rendered = reportRendererRegistry.getRequired("html")
                .render(ReportRenderContext.of(report));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report." + format.fileExtension() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, format.contentType())
                .body(rendered.bytes());
    }

    @Operation(summary = "Export DOCX report",
               description = "Generates a comprehensive architecture report as Word document")
    @ApiResponse(responseCode = "200", description = "DOCX report returned as binary")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/docx")
    public ResponseEntity<byte[]> exportDocx(@RequestBody ReportRequest request) {
        if (request.scores() == null || request.scores().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        ArchitectureReport report = reportService.generateReport(
                request.scores(), request.businessText(), request.minScore());
        ReportFormatDescriptor format = reportRendererRegistry.getRequired("docx").descriptor();
        ReportRenderResult rendered = reportRendererRegistry.getRequired("docx")
                .render(ReportRenderContext.of(report));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report." + format.fileExtension() + "\"")
                .contentType(MediaType.parseMediaType(format.contentType()))
                .body(rendered.bytes());
    }

    @Operation(summary = "Export JSON report",
               description = "Returns the full architecture report as structured JSON")
    @ApiResponse(responseCode = "200", description = "JSON report returned")
    @ApiResponse(responseCode = "400", description = "Scores are missing")
    @PostMapping("/json")
    public ResponseEntity<ArchitectureReport> exportJson(@RequestBody ReportRequest request) {
        if (request.scores() == null || request.scores().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        ArchitectureReport report = reportService.generateReport(
                request.scores(), request.businessText(), request.minScore());
        report.setViewContext(repositoryStateService.getViewContext(
                workspaceResolver.resolveCurrentUsername(), "draft"));
        return ResponseEntity.ok(report);
    }
}
