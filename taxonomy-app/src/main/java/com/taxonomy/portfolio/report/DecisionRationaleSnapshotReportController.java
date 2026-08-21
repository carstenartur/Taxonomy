package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** Download API for reports generated from immutable project-analysis snapshots. */
@RestController
@RequestMapping("/api/projects/{projectId}/snapshots/{snapshotId}/decision-report")
@Tag(name = "Decision Rationale Report")
public class DecisionRationaleSnapshotReportController {

    private final DecisionRationaleSnapshotReportService snapshotReportService;
    private final ReportRendererRegistry reportRendererRegistry;
    private final WorkspaceResolver workspaceResolver;

    public DecisionRationaleSnapshotReportController(
            DecisionRationaleSnapshotReportService snapshotReportService,
            ReportRendererRegistry reportRendererRegistry,
            WorkspaceResolver workspaceResolver) {
        this.snapshotReportService = snapshotReportService;
        this.reportRendererRegistry = reportRendererRegistry;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/formats")
    @Operation(summary = "List report formats available for an immutable analysis snapshot")
    public List<ReportFormatDescriptor> listFormats() {
        return reportRendererRegistry.listDescriptors(
                DecisionRationaleReportPlugin.REPORT_TYPE_ID);
    }

    @GetMapping("/{formatId}")
    @Operation(
            summary = "Download a hierarchical decision rationale for one immutable snapshot",
            description = "Replays the frozen requirement text, taxonomy hierarchy, scores, AI reasons, "
                    + "provider and Git provenance stored with the selected snapshot.")
    public ResponseEntity<byte[]> export(
            @PathVariable Long projectId,
            @PathVariable String snapshotId,
            @PathVariable String formatId,
            @Parameter(description = "Optional BCP-47 report language such as de or en")
            @RequestParam(required = false) String language) {
        ReportRendererExtension renderer = reportRendererRegistry.findByFormatId(
                        DecisionRationaleReportPlugin.REPORT_TYPE_ID, formatId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Unknown decision-report format: " + formatId));
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        String username = workspaceResolver.resolveCurrentUsername();
        DecisionRationaleReport report = snapshotReportService.generate(
                projectId, snapshotId, username, context, resolveLocale(language));
        ReportFormatDescriptor format = renderer.descriptor();
        ReportRenderResult rendered = renderer.render(ReportRenderContext.ofPayload(report));
        String filename = DecisionRationaleReportPlugin.BASE_FILENAME
                + "-v" + valueOrUnknown(report.metadata().requirementVersionNumber())
                + "." + format.fileExtension();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header("X-Taxonomy-Snapshot-Id", report.metadata().analysisSnapshotId())
                .header("X-Taxonomy-Data-SHA256",
                        report.metadata().taxonomyDataFingerprintSha256())
                .header("X-Taxonomy-Analysis-SHA256",
                        report.metadata().analysisSnapshotFingerprintSha256())
                .contentType(MediaType.parseMediaType(format.contentType()))
                .body(rendered.bytes());
    }

    private Locale resolveLocale(String language) {
        if (language == null || language.isBlank()) {
            return LocaleContextHolder.getLocale();
        }
        Locale locale = Locale.forLanguageTag(language.strip());
        return locale.getLanguage().isBlank()
                ? LocaleContextHolder.getLocale() : locale;
    }

    private String valueOrUnknown(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }
}
