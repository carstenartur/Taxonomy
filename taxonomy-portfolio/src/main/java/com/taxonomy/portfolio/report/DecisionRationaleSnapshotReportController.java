package com.taxonomy.portfolio.report;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;
import com.taxonomy.reporting.render.decision.DecisionReportTemplateHeaders;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
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
import java.util.Set;

/** Download API for reports generated from immutable project-analysis snapshots. */
@RestController
@RequestMapping("/api/projects/{projectId}/snapshots/{snapshotId}/decision-report")
@Tag(name = "Decision Rationale Report")
public class DecisionRationaleSnapshotReportController {

    private final DecisionRationaleSnapshotReportService snapshotReportService;
    private final ReportRendererRegistry reportRendererRegistry;
    private final SnapshotWordReportService wordReportService;
    private final WorkspaceResolver workspaceResolver;

    public DecisionRationaleSnapshotReportController(DecisionRationaleSnapshotReportService service,
            ReportRendererRegistry registry,WorkspaceResolver resolver) {
        this(service,registry,resolver,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionRationaleSnapshotReportController(
            DecisionRationaleSnapshotReportService snapshotReportService,
            ReportRendererRegistry reportRendererRegistry,
            WorkspaceResolver workspaceResolver, SnapshotWordReportService wordReportService) {
        this.snapshotReportService = snapshotReportService;
        this.wordReportService = wordReportService;
        this.reportRendererRegistry = reportRendererRegistry;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/formats")
    @Operation(summary = "List report formats available for an immutable analysis snapshot",
            description = "Lists the registered decision-rationale report formats and their descriptors. This capability read does not load a snapshot, generate a document or start analysis.")
    public List<ReportFormatDescriptor> listFormats() {
        return reportRendererRegistry.listDescriptors(
                DecisionRationaleReportPlugin.REPORT_TYPE_ID);
    }

    public record AvailableOptions(String snapshotId, AnalysisScope analysisScope, List<DecisionReportScope.Root> roots) {}

    @GetMapping("/options")
    @Operation(summary = "Read export options for a frozen decision report",
            description = "Derives available taxonomy roots and the original analysis scope from the authorized immutable snapshot. Optional language selects report labels. Reading options neither reruns analysis nor changes the saved result.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<AvailableOptions> options(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId, @Parameter(description = "Immutable analysis snapshot identifier") @PathVariable String snapshotId,
            @Parameter(description = "Optional BCP-47 report language; otherwise the current locale") @RequestParam(required = false) String language) {
        var context = workspaceResolver.resolveCurrentContext();
        var report = snapshotReportService.generate(projectId, snapshotId, workspaceResolver.resolveCurrentUsername(), context,
                resolveLocale(language), new DecisionReportOptions(DecisionReportOptions.Profile.COMPACT, null, null, null, null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AvailableOptions(
                report.metadata().analysisSnapshotId(), report.scope().analysisScope(), report.scope().availableRoots()));
    }

    /** Existing Java callers and unconfigured links retain their established full-report behavior. */
    public ResponseEntity<byte[]> export(Long projectId, String snapshotId, String formatId, String language) {
        return export(projectId, snapshotId, formatId, language, null, null, null, null, null);
    }

    @GetMapping("/{formatId}")
    @Operation(
            summary = "Download a hierarchical decision rationale for one immutable snapshot",
            description = "Replays the frozen requirement text, taxonomy hierarchy, scores, AI reasons, "
                    + "provider and Git provenance stored with the selected snapshot.")
    public ResponseEntity<byte[]> export(
            @Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,
            @Parameter(description = "Immutable analysis snapshot identifier") @PathVariable String snapshotId,
            @Parameter(description = "Registered report or diagram format identifier") @PathVariable String formatId,
            @Parameter(description = "Optional BCP-47 report language such as de or en")
            @RequestParam(required = false) String language,
            @RequestParam(required = false) DecisionReportOptions.Profile profile,
            @RequestParam(required = false) Set<String> taxonomyRoots,
            @RequestParam(required = false) DecisionReportOptions.Contents contents,
            @RequestParam(required = false) DecisionReportOptions.TreeLayout treeLayout,
            @RequestParam(required = false) Set<DecisionReportOptions.Section> sections) {
        DecisionReportOptions options = profile == null && taxonomyRoots == null && contents == null && treeLayout == null && sections == null
                ? null : new DecisionReportOptions(profile, taxonomyRoots, contents, treeLayout, sections);
        ReportRendererExtension renderer = reportRendererRegistry.findByFormatId(
                        DecisionRationaleReportPlugin.REPORT_TYPE_ID, formatId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Unknown decision-report format: " + formatId));
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        String username = workspaceResolver.resolveCurrentUsername();
        ReportFormatDescriptor format = renderer.descriptor();
        DecisionRationaleReport report;
        if (options == null) {
            report = "docx".equals(format.id().trim().toLowerCase(Locale.ROOT))
                    ? wordReportService.load(projectId, snapshotId, username, context, resolveLocale(language)).decision()
                    : snapshotReportService.generate(projectId, snapshotId, username, context, resolveLocale(language));
        } else if (options.includes(DecisionReportOptions.Section.ARCHITECTURE) && wordReportService != null) {
            report = ("docx".equals(format.id().trim().toLowerCase(Locale.ROOT))
                    ? wordReportService.load(projectId, snapshotId, username, context, resolveLocale(language), options)
                    : wordReportService.loadEvidence(projectId, snapshotId, username, context, resolveLocale(language), options)).decision();
        } else report = snapshotReportService.generate(projectId, snapshotId, username, context, resolveLocale(language), options);
        ReportRenderResult rendered = renderer.render(ReportRenderContext.ofPayload(report));
        String filename = DecisionRationaleReportPlugin.BASE_FILENAME
                + "-v" + valueOrUnknown(report.metadata().requirementVersionNumber())
                + "." + format.fileExtension();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header("X-Taxonomy-Snapshot-Id", report.metadata().analysisSnapshotId())
                .header("X-Taxonomy-Data-SHA256",
                        report.metadata().taxonomyDataFingerprintSha256())
                .header("X-Taxonomy-Analysis-SHA256",
                        report.metadata().analysisSnapshotFingerprintSha256());
        if(report.architecture()!=null)response.header("X-Taxonomy-Graph-SHA256",report.architecture().evidence().graphSha256());
        DecisionReportTemplateHeaders.apply(response, rendered);
        return response
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
