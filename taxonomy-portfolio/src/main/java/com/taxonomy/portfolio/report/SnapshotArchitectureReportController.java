package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.report.ArchitectureReportDocxRenderer;
import com.taxonomy.workspace.service.WorkspaceResolver;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/** Reproducible architecture Word download from one saved analysis. */
@RestController
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
    public ResponseEntity<byte[]> export(
            @PathVariable Long projectId,
            @PathVariable String snapshotId,
            @RequestParam(required = false) String language) {
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
