package com.taxonomy.controller;

import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.service.ArchitectureReportService;
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

    public ReportApiController(ArchitectureReportService reportService) {
        this.reportService = reportService;
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
        String markdown = reportService.renderMarkdown(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report.md\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
                .body(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        String html = reportService.renderHtml(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report.html\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .body(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        byte[] docx = reportService.renderDocx(report);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report.docx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(docx);
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
        return ResponseEntity.ok(report);
    }
}
