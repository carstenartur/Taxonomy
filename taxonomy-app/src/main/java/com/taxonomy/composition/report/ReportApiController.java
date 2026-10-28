package com.taxonomy.composition.report;

import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.recovery.AnalysisContinuationService;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.reporting.render.decision.DecisionReportLanguage;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.AnalysisResult;
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
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

@com.taxonomy.shared.features.ConditionalOnFeature({"architecture", "reporting"})
@RestController
@RequestMapping("/api/report")
@Tag(name = "Report Export")
public class ReportApiController {

    private final ArchitectureReportService reportService;
    private final ReportRendererRegistry reportRendererRegistry;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;
    private final AnalysisContinuationService continuations;
    private final ClusterAnalysisObservation cluster;

    public ReportApiController(ArchitectureReportService reportService,
                               ReportRendererRegistry reportRendererRegistry,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver) {
        this(reportService, reportRendererRegistry, repositoryStateService, workspaceResolver, null, null);
    }

    @Autowired
    public ReportApiController(ArchitectureReportService reportService,
                               ReportRendererRegistry reportRendererRegistry,
                               RepositoryStateService repositoryStateService,
                               WorkspaceResolver workspaceResolver,
                               ObjectProvider<AnalysisContinuationService> continuations,
                               ObjectProvider<ClusterAnalysisObservation> cluster) {
        this.reportService = reportService;
        this.reportRendererRegistry = reportRendererRegistry;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.continuations = continuations == null ? null : continuations.getIfAvailable();
        this.cluster = cluster == null ? null : cluster.getIfAvailable();
    }

    public record AnalysisReference(String operationId, String continuationId, String workspaceId) { }

    public record ReportRequest(
            Map<String, Double> scores,
            String businessText,
            Integer minScore,
            Map<String, String> reasons,
            AnalysisLanguage analysisLanguage,
            String language,
            AnalysisReference analysisReference) {
        public ReportRequest(Map<String, Double> scores, String businessText, int minScore) {
            this(scores, businessText, minScore, Map.of(), null, null);
        }
        public ReportRequest(Map<String, Double> scores, String businessText, int minScore,
                             Map<String, String> reasons, AnalysisLanguage analysisLanguage, String language) {
            this(scores, businessText, minScore, reasons, analysisLanguage, language, null);
        }
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
        ArchitectureReport report = prepareReport(request).report();
        if (request.analysisReference() == null) {
            WorkspaceContext context = workspaceResolver.resolveCurrentContext();
            String username = context.username();
            String branch = repositoryStateService.resolveWorkspaceBranch(username);
            report.setViewContext(repositoryStateService.getViewContext(username, branch, context));
        }
        return ResponseEntity.ok(report);
    }

    private ResponseEntity<byte[]> render(ReportRequest request, String formatId) {
        if (!isValid(request)) {
            return ResponseEntity.badRequest().build();
        }
        PreparedReport prepared = prepareReport(request);
        ReportRendererExtension renderer = reportRendererRegistry.getRequired(formatId);
        ReportFormatDescriptor format = renderer.descriptor();
        String language = DecisionReportLanguage.resolve(
                DecisionReportLanguage.optionalOverride(request.language()), prepared.language()).toLanguageTag();
        ReportRenderResult rendered = renderer.render(new ReportRenderContext(prepared.report(), Map.of(
                "language", language, "nodeReasons", prepared.reasons() == null ? Map.of() : prepared.reasons())));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"architecture-report."
                                + format.fileExtension() + "\"")
                .contentType(MediaType.parseMediaType(format.contentType()))
                .body(rendered.bytes());
    }

    private record PreparedReport(ArchitectureReport report, Map<String, String> reasons, AnalysisLanguage language) { }

    private PreparedReport prepareReport(ReportRequest request) {
        AnalysisReference reference = request.analysisReference();
        if (reference == null) {
            return new PreparedReport(reportService.generateReport(request.scores(), request.businessText(),
                    request.minScore() == null ? 0 : request.minScore()),
                    request.reasons(), request.analysisLanguage());
        }
        WorkspaceContext scope = workspaceResolver.resolveCurrentContext();
        String username = workspaceResolver.resolveCurrentUsername();
        if (!hasText(reference.workspaceId()) || !Objects.equals(reference.workspaceId(), scope.workspaceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved analysis not found");
        }
        AnalysisResult result;
        String businessText;
        AnalysisLanguage language;
        if (hasText(reference.continuationId())) {
            if (continuations == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved analysis not found");
            var snapshot = continuations.read(reference.continuationId(), username, scope);
            result = snapshot.result();
            if (hasText(reference.operationId())) {
                if (hasText(snapshot.clusterOperationId()) && !Objects.equals(reference.operationId(), snapshot.clusterOperationId())
                        || result != null && hasText(result.getAnalysisOperationId())
                        && !Objects.equals(reference.operationId(), result.getAnalysisOperationId())
                        || !hasText(snapshot.clusterOperationId()) && (result == null || !hasText(result.getAnalysisOperationId()))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Saved analysis references disagree");
                }
            }
            if (snapshot.request() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Saved analysis input is unavailable");
            businessText = snapshot.request().getBusinessText();
            language = snapshot.request().getAnalysisLanguage();
        } else if (hasText(reference.operationId())) {
            if (cluster == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved analysis not found");
            var input = cluster.request(reference.operationId(), username, scope);
            result = cluster.result(reference.operationId(), username, scope);
            businessText = input.businessText();
            language = input.analysisLanguage();
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An analysis operation or continuation is required");
        }
        // Historical absence is distinct from a completed report with empty findings.
        // Never turn a failed/not-yet-retained result into a fresh global computation.
        if (result == null || result.getCopilot() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The saved Copilot report is not available");
        }
        return new PreparedReport(reportService.generateRetainedReport(result, businessText), result.getReasons(),
                result.getAnalysisLanguage() != null ? result.getAnalysisLanguage() : language);
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private boolean isValid(ReportRequest request) {
        return request != null && (request.analysisReference() != null || request.scores() != null && !request.scores().isEmpty());
    }
}
