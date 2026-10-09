package com.taxonomy.portfolio.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.architecture.report.ArchitectureReportDocxRenderer;
import com.taxonomy.workspace.service.WorkspaceResolver;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/** Reproducible architecture Word download from one saved analysis. */
@RestController
@Tag(name = "Snapshot architecture reports")
@RequestMapping("/api/projects/{projectId}/snapshots/{snapshotId}/architecture-report")
public class SnapshotArchitectureReportController {
    private final SnapshotWordReportService reports;
    private final ArchitectureReportDocxRenderer renderer;
    private final WorkspaceResolver resolver;

    public SnapshotArchitectureReportController(
            SnapshotWordReportService reports,
            ArchitectureReportDocxRenderer renderer,
            WorkspaceResolver resolver) {
        this.reports = reports;
        this.renderer = renderer;
        this.resolver = resolver;
    }

    @GetMapping("/docx")
    @Operation(summary = "Download a frozen architecture report as Word",
            description = "Loads the immutable project-analysis snapshot in the authenticated workspace and renders a DOCX attachment. Does not run another analysis. Optional language overrides the current locale; response headers identify the snapshot and graph, data and analysis fingerprints.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<byte[]> export(
            @Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,
            @Parameter(description = "Immutable analysis snapshot identifier") @PathVariable String snapshotId,
            @Parameter(description = "Optional BCP-47 report language; otherwise the current locale") @RequestParam(required = false) String language) {
        Locale locale =
                language == null || language.isBlank()
                        ? LocaleContextHolder.getLocale()
                        : Locale.forLanguageTag(language.strip());
        if (locale.getLanguage().isBlank()) locale = LocaleContextHolder.getLocale();
        var source =
                reports.load(
                        projectId,
                        snapshotId,
                        resolver.resolveCurrentUsername(),
                        resolver.resolveCurrentContext(),
                        locale);
        var evidence = source.architecture().evidence();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"taxonomy-architecture-report-v"
                                + evidence.requirementVersionNumber()
                                + ".docx\"")
                .header("X-Taxonomy-Snapshot-Id", evidence.snapshotId())
                .header("X-Taxonomy-Graph-SHA256", evidence.graphSha256())
                .header(
                        "X-Taxonomy-Data-SHA256",
                        source.decision().metadata().taxonomyDataFingerprintSha256())
                .header(
                        "X-Taxonomy-Analysis-SHA256",
                        source.decision().metadata().analysisSnapshotFingerprintSha256())
                .body(renderer.render(source.architecture()));
    }
}
