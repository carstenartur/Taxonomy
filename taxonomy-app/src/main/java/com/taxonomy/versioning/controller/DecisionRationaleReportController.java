package com.taxonomy.versioning.controller;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.dto.ViewContext;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP adapter for the hierarchical decision-rationale report extension family.
 *
 * <p>The controller owns the report-specific request and trusted runtime provenance.
 * Format rendering is delegated exclusively to {@link ReportRendererRegistry}; adding
 * another decision-report format therefore requires only another renderer extension.</p>
 */
@RestController
@RequestMapping("/api/decision-report")
@Tag(name = "Decision Rationale Report")
public class DecisionRationaleReportController {

    private static final int MAX_SCORE_ENTRIES = 25_000;
    private static final int MAX_REASON_ENTRIES = 25_000;
    private static final int MAX_NODE_CODE_LENGTH = 256;
    private static final int MAX_REQUIREMENT_LENGTH = 500_000;
    private static final int MAX_REASON_LENGTH = 50_000;
    private static final long MAX_TOTAL_REASON_CHARACTERS = 5_000_000L;
    private static final int MAX_DISCREPANCIES = 10_000;
    private static final int MAX_METADATA_LENGTH = 256;

    private final DecisionRationaleReportService reportService;
    private final ReportRendererRegistry reportRendererRegistry;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;

    public DecisionRationaleReportController(
            DecisionRationaleReportService reportService,
            ReportRendererRegistry reportRendererRegistry,
            RepositoryStateService repositoryStateService,
            WorkspaceResolver workspaceResolver) {
        this.reportService = reportService;
        this.reportRendererRegistry = reportRendererRegistry;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
    }

    public record DecisionReportRequest(
            Map<String, Integer> scores,
            Map<String, String> reasons,
            String businessText,
            String provider,
            String analysisStatus,
            List<TaxonomyDiscrepancy> discrepancies,
            String language) {
    }

    @Operation(
            summary = "List decision-rationale report formats",
            description = "Returns formats registered through the report-renderer extension SPI.")
    @GetMapping("/formats")
    public List<ReportFormatDescriptor> listFormats() {
        return reportRendererRegistry.listDescriptors(
                DecisionRationaleReportPlugin.REPORT_TYPE_ID);
    }

    @Operation(
            summary = "Export hierarchical decision rationale as DOCX",
            description = "Creates a professional Word document with title page, executive summary, "
                    + "one parent/children decision chapter per positive hierarchy decision, diagrams, "
                    + "and account/version provenance in the footer.")
    @ApiResponse(responseCode = "200", description = "DOCX report returned")
    @ApiResponse(responseCode = "400", description = "Requirement or scores are missing")
    @PostMapping("/docx")
    public ResponseEntity<byte[]> exportDocx(@RequestBody DecisionReportRequest request) {
        return render(request, "docx");
    }

    @Operation(
            summary = "Export hierarchical decision rationale as HTML",
            description = "Creates a self-contained, print-ready HTML report with inline SVG diagrams.")
    @ApiResponse(responseCode = "200", description = "HTML report returned")
    @ApiResponse(responseCode = "400", description = "Requirement or scores are missing")
    @PostMapping("/html")
    public ResponseEntity<byte[]> exportHtml(@RequestBody DecisionReportRequest request) {
        return render(request, "html");
    }

    @Operation(
            summary = "Export hierarchical decision rationale as JSON",
            description = "Returns the format-neutral report model including all chapters and provenance.")
    @ApiResponse(responseCode = "200", description = "Structured report returned")
    @ApiResponse(responseCode = "400", description = "Requirement or scores are missing")
    @PostMapping("/json")
    public ResponseEntity<byte[]> exportJson(@RequestBody DecisionReportRequest request) {
        return render(request, "json");
    }

    private ResponseEntity<byte[]> render(
            DecisionReportRequest request,
            String formatId) {
        if (!isValid(request)) {
            return ResponseEntity.badRequest().build();
        }
        DecisionRationaleReport report = generate(request);
        ReportRendererExtension renderer = reportRendererRegistry.getRequired(
                DecisionRationaleReportPlugin.REPORT_TYPE_ID, formatId);
        ReportFormatDescriptor format = renderer.descriptor();
        ReportRenderResult rendered = renderer.render(ReportRenderContext.ofPayload(report));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + DecisionRationaleReportPlugin.BASE_FILENAME + "."
                                + format.fileExtension() + "\"")
                .header("X-Taxonomy-Data-SHA256",
                        report.metadata().taxonomyDataFingerprintSha256())
                .header("X-Taxonomy-Analysis-SHA256",
                        report.metadata().analysisSnapshotFingerprintSha256())
                .contentType(MediaType.parseMediaType(format.contentType()))
                .body(rendered.bytes());
    }

    private DecisionRationaleReport generate(DecisionReportRequest request) {
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        String branch = repositoryStateService.resolveWorkspaceBranch(context.username());
        ViewContext viewContext = repositoryStateService.getViewContext(
                context.username(), branch, context);
        Locale locale = resolveLocale(request.language());
        DecisionAnalysisInput input = new DecisionAnalysisInput(
                request.businessText(),
                request.scores(),
                request.reasons(),
                request.provider(),
                request.analysisStatus(),
                request.discrepancies());
        return reportService.generate(input, context, viewContext, locale);
    }

    private Locale resolveLocale(String language) {
        if (language == null || language.isBlank()) {
            return LocaleContextHolder.getLocale();
        }
        Locale locale = Locale.forLanguageTag(language.strip());
        return locale.getLanguage().isBlank()
                ? LocaleContextHolder.getLocale() : locale;
    }

    private boolean isValid(DecisionReportRequest request) {
        if (request == null
                || !boundedText(request.businessText(), MAX_REQUIREMENT_LENGTH, false)
                || request.scores() == null
                || request.scores().isEmpty()
                || request.scores().size() > MAX_SCORE_ENTRIES
                || !boundedMetadata(request.provider())
                || !boundedMetadata(request.analysisStatus())
                || !boundedMetadata(request.language())
                || (request.discrepancies() != null
                        && request.discrepancies().size() > MAX_DISCREPANCIES)) {
            return false;
        }
        boolean validScores = request.scores().entrySet().stream().allMatch(entry ->
                boundedText(entry.getKey(), MAX_NODE_CODE_LENGTH, false)
                        && entry.getValue() != null
                        && entry.getValue() >= 0
                        && entry.getValue() <= 100);
        return validScores && validReasons(request.reasons());
    }

    private boolean validReasons(Map<String, String> reasons) {
        if (reasons == null) {
            return true;
        }
        if (reasons.size() > MAX_REASON_ENTRIES) {
            return false;
        }
        long totalCharacters = 0;
        for (Map.Entry<String, String> entry : reasons.entrySet()) {
            if (!boundedText(entry.getKey(), MAX_NODE_CODE_LENGTH, false)
                    || !boundedText(entry.getValue(), MAX_REASON_LENGTH, true)) {
                return false;
            }
            if (entry.getValue() != null) {
                totalCharacters += entry.getValue().length();
                if (totalCharacters > MAX_TOTAL_REASON_CHARACTERS) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean boundedMetadata(String value) {
        return boundedText(value, MAX_METADATA_LENGTH, true);
    }

    private boolean boundedText(String value, int maxLength, boolean nullable) {
        if (value == null) {
            return nullable;
        }
        return value.length() <= maxLength && (nullable || !value.isBlank());
    }
}
