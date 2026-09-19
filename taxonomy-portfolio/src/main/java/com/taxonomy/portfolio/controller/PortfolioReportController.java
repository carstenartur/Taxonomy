package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.service.PortfolioReportService;
import com.taxonomy.portfolio.service.PortfolioReportService.Format;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/** Downloadable reports for a full project or one requirement. */
@RestController
@RequestMapping("/api/projects/{projectId}/reports")
@Tag(name = "Project Portfolio Reports")
public class PortfolioReportController {

    private final PortfolioReportService reportService;
    private final WorkspaceResolver workspaceResolver;

    public PortfolioReportController(PortfolioReportService reportService,
                                     WorkspaceResolver workspaceResolver) {
        this.reportService = reportService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/{format}")
    @Operation(summary = "Render a project or requirement portfolio report")
    public ResponseEntity<byte[]> report(
            @PathVariable Long projectId,
            @PathVariable String format,
            @RequestParam(required = false) Long requirementId,
            @RequestParam(required = false, defaultValue = "taxonomy") String matrix) {
        Format requestedFormat;
        try {
            requestedFormat = Format.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
        String username = workspaceResolver.resolveCurrentUsername();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        var rendered = reportService.render(
                projectId, requirementId, requestedFormat, matrix, username, context);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(rendered.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + rendered.filename() + "\"")
                .body(rendered.bytes());
    }
}
