package com.taxonomy.versioning.controller;

import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter;
import com.taxonomy.architecture.decision.DecisionReportTemplateHeaders;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.ProductCoverageGap;
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
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final int MAX_PRODUCT_COVERAGE_GAPS = 10_000;
    private static final int MAX_PRODUCT_GAP_CANDIDATES = 25_000;
    private static final int MAX_METADATA_LENGTH = 256;

    private final DecisionRationaleReportService reportService;
    private final ReportRendererRegistry reportRendererRegistry;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;
    private final DecisionRationaleScoreSemanticsAdapter scoreSemanticsAdapter;
    private final TaxonomyService taxonomyService;

    /** Backward-compatible constructor for focused unit tests. */
    public DecisionRationaleReportController(
            DecisionRationaleReportService reportService,
            ReportRendererRegistry reportRendererRegistry,
            RepositoryStateService repositoryStateService,
            WorkspaceResolver workspaceResolver) {
        this(reportService, reportRendererRegistry, repositoryStateService, workspaceResolver,
                new DecisionRationaleScoreSemanticsAdapter(), null);
    }

    @Autowired
    public DecisionRationaleReportController(
            DecisionRationaleReportService reportService,
            ReportRendererRegistry reportRendererRegistry,
            RepositoryStateService repositoryStateService,
            WorkspaceResolver workspaceResolver,
            DecisionRationaleScoreSemanticsAdapter scoreSemanticsAdapter,
            TaxonomyService taxonomyService) {
        this.reportService = reportService;
        this.reportRendererRegistry = reportRendererRegistry;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.scoreSemanticsAdapter = scoreSemanticsAdapter;
        this.taxonomyService = taxonomyService;
    }

    public record DecisionReportRequest(
            Map<String, Integer> scores,
            Map<String, Integer> rawScores,
            Map<String, Integer> effectiveScores,
            Map<String, AnalysisScoreDetail> scoreDetails,
            Map<String, Integer> productSuitabilityScores,
            Integer scoreSemanticsVersion,
            Map<String, String> reasons,
            String businessText,
            String provider,
            String analysisStatus,
            List<TaxonomyDiscrepancy> discrepancies,
            List<ProductCoverageGap> productCoverageGaps,
            String language) {

        /** Legacy request constructor retained for Java callers and validation tests. */
        public DecisionReportRequest(
                Map<String, Integer> scores,
                Map<String, String> reasons,
                String businessText,
                String provider,
                String analysisStatus,
                List<TaxonomyDiscrepancy> discrepancies,
                List<ProductCoverageGap> productCoverageGaps,
                String language) {
            this(scores, null, null, null, null, null, reasons, businessText, provider,
                    analysisStatus, discrepancies, productCoverageGaps, language);
        }
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
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + DecisionRationaleReportPlugin.BASE_FILENAME + "."
                                + format.fileExtension() + "\"")
                .header("X-Taxonomy-Data-SHA256",
                        report.metadata().taxonomyDataFingerprintSha256())
                .header("X-Taxonomy-Analysis-SHA256",
                        report.metadata().analysisSnapshotFingerprintSha256());
        DecisionReportTemplateHeaders.apply(response, rendered);
        return response
                .contentType(MediaType.parseMediaType(format.contentType()))
                .body(rendered.bytes());
    }

    private DecisionRationaleReport generate(DecisionReportRequest request) {
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        String branch = repositoryStateService.resolveWorkspaceBranch(context.username());
        ViewContext viewContext = repositoryStateService.getViewContext(
                context.username(), branch, context);
        Locale locale = resolveLocale(request.language());
        AnalysisScoreSemantics.Derived scoreSemantics = resolveScoreSemantics(request);
        Map<String, AnalysisScoreDetail> scoreDetails = scoreSemantics.scoreDetails();
        Map<String, Integer> effectiveScores = scoreSemantics.effectiveScores();
        Map<String, String> reasons = scoreSemanticsAdapter.enrichReasons(
                request.reasons(), scoreDetails, locale);
        DecisionAnalysisInput input = new DecisionAnalysisInput(
                request.businessText(),
                effectiveScores,
                reasons,
                request.provider(),
                request.analysisStatus(),
                request.discrepancies(),
                request.productCoverageGaps(),
                List.of(),
                null,
                scoreDetails);
        DecisionRationaleReport report = reportService.generate(
                input, context, viewContext, locale);
        return scoreSemanticsAdapter.adapt(report, scoreDetails, locale);
    }

    private AnalysisScoreSemantics.Derived resolveScoreSemantics(
            DecisionReportRequest request) {
        Map<String, Integer> suppliedRaw = request.rawScores() != null
                && !request.rawScores().isEmpty()
                ? request.rawScores() : request.scores();
        Map<String, Integer> authoritativeRaw = new LinkedHashMap<>();
        request.scores().forEach((code, score) -> authoritativeRaw.put(
                code, suppliedRaw.getOrDefault(code, score)));
        return AnalysisScoreSemantics.derive(
                authoritativeRaw,
                taxonomyService == null ? List.of() : taxonomyService.getFullTree());
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
                || !validScoreMap(request.scores())
                || !validOptionalScoreMap(request.rawScores())
                || !validOptionalScoreMap(request.effectiveScores())
                || !validOptionalScoreMap(request.productSuitabilityScores())
                || !validScoreDetails(request.scoreDetails())
                || !boundedMetadata(request.provider())
                || !boundedMetadata(request.analysisStatus())
                || !boundedMetadata(request.language())
                || (request.scoreSemanticsVersion() != null
                        && request.scoreSemanticsVersion() < 0)
                || (request.discrepancies() != null
                        && request.discrepancies().size() > MAX_DISCREPANCIES)
                || (request.productCoverageGaps() != null
                        && request.productCoverageGaps().size() > MAX_PRODUCT_COVERAGE_GAPS)) {
            return false;
        }
        return validReasons(request.reasons())
                && validDiscrepancies(request.discrepancies())
                && validProductCoverageGaps(request.productCoverageGaps());
    }

    private boolean validOptionalScoreMap(Map<String, Integer> scores) {
        return scores == null || (scores.size() <= MAX_SCORE_ENTRIES && validScoreMap(scores));
    }

    private boolean validScoreMap(Map<String, Integer> scores) {
        return scores != null && scores.entrySet().stream().allMatch(entry ->
                boundedText(entry.getKey(), MAX_NODE_CODE_LENGTH, false)
                        && entry.getValue() != null
                        && entry.getValue() >= 0
                        && entry.getValue() <= 100);
    }

    private boolean validScoreDetails(Map<String, AnalysisScoreDetail> details) {
        if (details == null) {
            return true;
        }
        if (details.size() > MAX_SCORE_ENTRIES) {
            return false;
        }
        return details.entrySet().stream().allMatch(entry -> {
            AnalysisScoreDetail detail = entry.getValue();
            return boundedNodeCode(entry.getKey())
                    && detail != null
                    && entry.getKey().equals(detail.nodeCode())
                    && detail.kind() != null
                    && detail.rawScore() >= 0 && detail.rawScore() <= 100
                    && detail.effectiveRelevance() >= 0
                    && detail.effectiveRelevance() <= 100
                    && (detail.parentCode() == null || boundedNodeCode(detail.parentCode()))
                    && (detail.parentScore() == null
                            || (detail.parentScore() >= 0 && detail.parentScore() <= 100));
        });
    }

    private boolean validProductCoverageGaps(
            List<ProductCoverageGap> productCoverageGaps) {
        if (productCoverageGaps == null) {
            return true;
        }
        int totalCandidates = 0;
        Set<String> familyCodes = new HashSet<>();
        for (ProductCoverageGap gap : productCoverageGaps) {
            if (gap == null
                    || !boundedNodeCode(gap.productFamilyCode())
                    || !boundedText(gap.productFamilyName(), MAX_REASON_LENGTH, true)
                    || gap.familyScore() < 0
                    || gap.familyScore() > 100
                    || !boundedText(gap.reason(), MAX_REASON_LENGTH, false)
                    || gap.candidateCodes() == null
                    || gap.candidateCodes().isEmpty()
                    || !familyCodes.add(gap.productFamilyCode())) {
                return false;
            }
            Set<String> candidateCodes = new HashSet<>();
            for (String candidateCode : gap.candidateCodes()) {
                totalCandidates++;
                if (totalCandidates > MAX_PRODUCT_GAP_CANDIDATES
                        || !boundedNodeCode(candidateCode)
                        || !candidateCodes.add(candidateCode)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean validDiscrepancies(
            List<TaxonomyDiscrepancy> discrepancies) {
        if (discrepancies == null) {
            return true;
        }
        for (TaxonomyDiscrepancy discrepancy : discrepancies) {
            if (discrepancy == null
                    || !boundedText(discrepancy.parentCode(),
                            MAX_NODE_CODE_LENGTH, false)
                    || discrepancy.expectedParentScore() < 0
                    || discrepancy.expectedParentScore() > 100
                    || discrepancy.actualChildSum() < 0) {
                return false;
            }
        }
        return true;
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

    private boolean boundedNodeCode(String value) {
        return boundedText(value, MAX_NODE_CODE_LENGTH, false)
                && value.equals(value.strip());
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
