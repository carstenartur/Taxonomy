package com.taxonomy.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.composition.analysis.SelectedArchitectureAdmissionModel;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.AnalysisDiagramSource;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.server.ResponseStatusException;

/** Adapts a validated email command to the same analysis and renderers as the application. */
@Service
@ConditionalOnProperty(name = "taxonomy.mail.enabled", havingValue = "true")
@ConditionalOnFeature({"analysis", "architecture", "reporting"})
final class MailAnalysisProcessor {
    static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }

    static final class SourceUnavailable extends RuntimeException { }

    private final MailAnalysisCommandClassifier classifier;
    private final TaxonomyService taxonomies;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService membership;
    private final SelectedArchitectureAdmissionModel admission;
    private final AnalysisRuntimeSettings settings;
    private final AnalyzeRequirementUseCase analysis;
    private final ExportFacade diagrams;
    private final MailAnalysisRenderers renderers;
    private final LayeredDiagramLayoutService layout;
    private final ArchitecturePdfRenderer pdf;
    private final ArchitectureReportService architectureReport;
    private final DecisionRationaleReportService reports;
    private final DecisionRationaleScoreSemanticsAdapter scoreSemantics = new DecisionRationaleScoreSemanticsAdapter();
    private final Locale locale;
    private final AnalysisLanguage language;
    private final int maxResultBytes;

    MailAnalysisProcessor(MailAnalysisCommandClassifier classifier, TaxonomyService taxonomies,
            SystemRepositoryService repositories, RepositoryMembershipService membership,
            SelectedArchitectureAdmissionModel admission, AnalysisRuntimeSettings settings,
            AnalyzeRequirementUseCase analysis, ExportFacade diagrams, MailAnalysisRenderers renderers,
            LayeredDiagramLayoutService layout, ArchitecturePdfRenderer pdf,
            ArchitectureReportService architectureReport, DecisionRationaleReportService reports,
            @Value("${taxonomy.mail.queue.language:de}") String language,
            @Value("${taxonomy.mail.queue.max-result-bytes:15000000}") int maxResultBytes) {
        this.classifier = classifier;
        this.taxonomies = taxonomies;
        this.repositories = repositories;
        this.membership = membership;
        this.admission = admission;
        this.settings = settings;
        this.analysis = analysis;
        this.diagrams = diagrams;
        this.renderers = renderers;
        this.layout = layout;
        this.pdf = pdf;
        this.architectureReport = architectureReport;
        this.reports = reports;
        this.language = AnalysisLanguage.fromValue(language);
        this.locale = Locale.forLanguageTag(language);
        if (maxResultBytes < 1 || maxResultBytes > 50_000_000) throw new IllegalArgumentException("Invalid email export size limit");
        this.maxResultBytes = maxResultBytes;
    }

    MailAnalysisCommand classify(String subject, String requirementText) {
        int maxText = Math.max(100, Math.min(100_000, settings.getInt("limits.max-business-text", 5_000)));
        if (requirementText == null || requirementText.isBlank() || requirementText.length() > maxText) {
            throw new Rejected("Der Anforderungstext ist leer oder überschreitet die Grenze von " + maxText + " Zeichen.");
        }
        try {
            return classifier.classify(subject);
        } catch (DataAccessException | CannotCreateTransactionException unavailable) {
            throw new SourceUnavailable();
        } catch (IllegalArgumentException invalid) {
            throw new Rejected("Der Betreff beschreibt keinen eindeutigen Auftrag mit verfügbaren Exportformaten."
                    + " Beispiel: Entscheidungsbaum BP und CR als Word.");
        }
    }

    MailAnalysisQueue.Outcome process(String privateJobId, MailAnalysisCommand command, String requirementText,
                                      BooleanSupplier sourceReady) {
        Objects.requireNonNull(privateJobId, "Private email analysis identity");
        // The queue generates this private key separately from its public status token.
        String actor = "mail-" + StableIdentityHash.sha256("mail-analysis-owner:v1:" + privateJobId);
        String operationId = "mail-" + StableIdentityHash.sha256("mail-analysis-operation:v1:" + privateJobId);
        var primary = primaryRepository(actor);
        var context = new WorkspaceContext(actor, null, primary.getDefaultBranch(), primary.getRepositoryId());

        boolean architecture = command.mode() == MailAnalysisCommand.Mode.ARCHITECTURE;
        Set<String> roots = Set.copyOf(command.taxonomyCodes());
        AnalysisScope scope = new AnalysisScope(roots, architecture ? AnalysisMode.FULL : AnalysisMode.TAXONOMIES_ONLY);
        AnalysisResult result;
        try {
            result = analysis.analyzeUnpublished(operationId, context, requirementText,
                    () -> captureRequest(primary, context, scope, architecture, requirementText, sourceReady)).analysisResult();
        } catch (DataAccessException | CannotCreateTransactionException unavailable) {
            throw new SourceUnavailable();
        }
        if (result.getScores() == null || result.getScores().isEmpty() || "ERROR".equals(result.getStatus())) {
            throw new Rejected("Die Analyse hat keine exportierbaren Bewertungen geliefert.");
        }

        var attachments = architecture ? architectureFiles(command, result, requirementText)
                : decisionFiles(command, result, requirementText, context);
        boolean partial = !"SUCCESS".equals(result.getStatus())
                || result.getAnalysisCoverage() != null && result.getAnalysisCoverage().hasOpenEvaluations();
        String summary = partial
                ? "Die Analyse enthält einen Teilstand. Offene Bewertungen oder Beziehungen können im Export gekennzeichnet sein."
                : "Die Analyse ist abgeschlossen. Die angeforderten Exporte sind angehängt.";
        return new MailAnalysisQueue.Outcome(summary, List.copyOf(attachments), true, partial);
    }

    private SystemRepository primaryRepository(String actor) {
        try {
            var primary = repositories.getPrimaryRepository();
            if (!membership.canRead(primary, actor)) throw new Rejected("Die gemeinsame Taxonomie ist derzeit nicht verfügbar.");
            return primary;
        } catch (IllegalStateException | DataAccessException | CannotCreateTransactionException unavailable) {
            throw new SourceUnavailable();
        }
    }

    private AnalyzeRequirementCommand captureRequest(SystemRepository primary, WorkspaceContext context, AnalysisScope scope,
            boolean architecture, String requirementText, BooleanSupplier sourceReady) {
        if (!sourceReady.getAsBoolean()) throw new SourceUnavailable();
        try {
            scope.validateRoots(taxonomies.getRootCodes());
        } catch (IllegalArgumentException changed) {
            throw new Rejected("Eine ausgewählte Teiltaxonomie ist derzeit nicht verfügbar.");
        }
        AnalysisModelBinding source;
        try {
            source = admission.capturePrimary(primary, context.username(), scope);
        } catch (ResponseStatusException failure) {
            if (HttpStatus.SERVICE_UNAVAILABLE.equals(failure.getStatusCode())) throw new SourceUnavailable();
            throw failure;
        }
        int maxNodes = Math.max(1, Math.min(1_000, settings.getInt("limits.max-architecture-nodes", 50)));
        return new AnalyzeRequirementCommand(requirementText, architecture, maxNodes, null, context.username(), context, null, scope)
                .withAnalysisLanguage(language).withModelBinding(source);
    }

    private List<MailAnalysisAttachment> architectureFiles(MailAnalysisCommand command, AnalysisResult result, String requirementText) {
        RequirementArchitectureView view = result.getArchitectureView();
        if (view == null || view.getIncludedElements() == null || view.getIncludedElements().isEmpty()) {
            throw new Rejected("Die Analyse hat keine exportierbare Architektur geliefert.");
        }
        boolean exportDrawing = command.exports().stream()
                .anyMatch(format -> format.architectureRoute() != MailAnalysisCommand.ArchitectureRoute.REPORT);
        var diagram = exportDrawing ? diagrams.buildCurrentDiagram(view, language.value()) : null;
        var reasons = ExportFacade.elementReasons(view);
        var source = AnalysisDiagramSource.from(result);
        var options = new java.util.HashMap<String, Object>();
        options.put("elementReasons", reasons);
        options.put("locale", language.value());
        options.put("analysisSource", source);
        if (view.getOriginalRequirementText() != null) options.put("originalRequirementText", view.getOriginalRequirementText());

        var files = new ArrayList<MailAnalysisAttachment>();
        ReportRenderContext report = null;
        long total = 0;
        for (var format : command.exports()) {
            MailAnalysisAttachment file;
            switch (format.architectureRoute()) {
                case ARCHITECTURE_PDF -> {
                    byte[] bytes = pdf.renderAnalysis(layout.layout(diagram), source, reasons, locale);
                    file = new MailAnalysisAttachment("requirement-architecture.pdf", "application/pdf", bytes);
                }
                case REPORT -> {
                    if (report == null) report = prepareArchitectureReport(result, requirementText);
                    var renderer = renderers.report(command.mode(), format)
                            .orElseThrow(() -> new Rejected("Ein angefordertes Berichtsformat ist derzeit nicht verfügbar."));
                    var descriptor = renderer.descriptor();
                    file = new MailAnalysisAttachment("architecture-report." + descriptor.fileExtension(),
                            descriptor.contentType(), renderer.render(report).bytes());
                }
                case DIAGRAM -> {
                    var renderer = renderers.diagram(format)
                            .orElseThrow(() -> new Rejected("Ein angefordertes Diagrammformat ist derzeit nicht verfügbar."));
                    var descriptor = renderer.descriptor();
                    var output = renderer.export(new ExportContext(diagram, options));
                    file = new MailAnalysisAttachment("requirement-architecture." + descriptor.fileExtension(),
                            descriptor.contentType(), output.bytes());
                }
                default -> throw new IllegalStateException("Unknown architecture export route");
            }
            total = include(files, file, total);
        }
        return files;
    }

    private ReportRenderContext prepareArchitectureReport(AnalysisResult result, String requirementText) {
        if (result.getCopilot() == null) throw new Rejected("Die Analyse hat keinen exportierbaren Architekturbericht geliefert.");
        var report = architectureReport.generateRetainedReport(result, requirementText);
        Map<String, String> reasons = result.getReasons() == null ? Map.of() : result.getReasons();
        report.setNodeReasons(reasons);
        report.setLanguageTag(language.value());
        return new ReportRenderContext(report, Map.of("language", language.value(), "nodeReasons", reasons));
    }

    private List<MailAnalysisAttachment> decisionFiles(MailAnalysisCommand command, AnalysisResult result,
            String requirementText, WorkspaceContext context) {
        var scope = new AnalysisScope(Set.copyOf(command.taxonomyCodes()), AnalysisMode.TAXONOMIES_ONLY);
        var details = result.getScoreDetails();
        var reasons = scoreSemantics.enrichReasons(result.getReasons(), details, locale);
        var input = new DecisionRationaleReportService.DecisionAnalysisInput(requirementText, result.getEffectiveScores(),
                reasons, result.getProvider(), result.getStatus(), result.getDiscrepancies(), result.getProductCoverageGaps(),
                scope.selectedTree(result.getTree()), null, details, result.getAnalysisDurationMillis())
                .withRecordedReasons(result.getReasons()).withScope(scope, result.getAnalysisCoverage())
                .withAnalysisReasonLength(result.getAnalysisReasonLength());
        var options = new DecisionReportOptions(DecisionReportOptions.Profile.COMPACT, scope.taxonomyRoots(),
                null, null, null, false);

        // Attribute exported reports to the mail adapter.
        var reportContext = new WorkspaceContext("mail", context.workspaceId(), context.currentBranch(), context.repositoryId());
        var report = scoreSemantics.adapt(reports.generate(input, reportContext, result.getViewContext(), locale, options), details, locale);
        if (result.getModelBinding() != null) report = report.withScope(report.scope().withModelBinding(result.getModelBinding()));
        var files = new ArrayList<MailAnalysisAttachment>();
        long total = 0;
        for (var format : command.exports()) {
            var renderer = renderers.report(command.mode(), format)
                    .orElseThrow(() -> new Rejected("Ein angefordertes Entscheidungsbaumformat ist derzeit nicht verfügbar."));
            var descriptor = renderer.descriptor();
            byte[] bytes = renderer.render(ReportRenderContext.ofPayload(report)).bytes();
            total = include(files, new MailAnalysisAttachment("decision-tree." + descriptor.fileExtension(),
                    descriptor.contentType(), bytes), total);
        }
        return files;
    }

    private long include(List<MailAnalysisAttachment> files, MailAnalysisAttachment file, long previous) {
        long total = previous + file.content().length;
        if (file.content().length == 0) throw new Rejected("Ein angeforderter Export ist leer.");
        if (total > maxResultBytes) throw new Rejected("Die angeforderten Exporte sind zu groß für den E-Mail-Versand.");
        files.add(file);
        return total;
    }
}
