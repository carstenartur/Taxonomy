package com.taxonomy.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort;
import com.taxonomy.analysis.usecase.AnalysisRequestModelAdmission;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Adapts a validated email command to the same analysis and renderers as the application. */
@Service
@ConditionalOnProperty(name = "taxonomy.mail.enabled", havingValue = "true")
@ConditionalOnFeature({"analysis", "architecture", "reporting"})
final class MailAnalysisProcessor {
    static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }

    private final MailAnalysisCommandClassifier classifier;
    private final TaxonomyService taxonomies;
    private final SystemRepositoryService repositories;
    private final RepositoryMembershipService membership;
    private final ObjectProvider<AnalysisAdmissionModelPort> admission;
    private final AnalysisRuntimeSettings settings;
    private final AnalyzeRequirementUseCase analysis;
    private final ExportFacade diagrams;
    private final ExportFormatExtensionRegistry formats;
    private final LayeredDiagramLayoutService layout;
    private final ArchitecturePdfRenderer pdf;
    private final ArchitectureReportService architectureReport;
    private final DecisionRationaleReportService reports;
    private final ReportRendererRegistry reportFormats;
    private final DecisionRationaleScoreSemanticsAdapter scoreSemantics = new DecisionRationaleScoreSemanticsAdapter();
    private final Locale locale;
    private final AnalysisLanguage language;
    private final int maxResultBytes;

    MailAnalysisProcessor(MailAnalysisCommandClassifier classifier, TaxonomyService taxonomies,
            SystemRepositoryService repositories, RepositoryMembershipService membership,
            ObjectProvider<AnalysisAdmissionModelPort> admission, AnalysisRuntimeSettings settings,
            AnalyzeRequirementUseCase analysis, ExportFacade diagrams, ExportFormatExtensionRegistry formats,
            LayeredDiagramLayoutService layout, ArchitecturePdfRenderer pdf,
            ArchitectureReportService architectureReport, DecisionRationaleReportService reports, ReportRendererRegistry reportFormats,
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
        this.formats = formats;
        this.layout = layout;
        this.pdf = pdf;
        this.architectureReport = architectureReport;
        this.reports = reports;
        this.reportFormats = reportFormats;
        this.language = AnalysisLanguage.fromValue(language);
        this.locale = Locale.forLanguageTag(language);
        if (maxResultBytes < 1 || maxResultBytes > 50_000_000) throw new IllegalArgumentException("Invalid email export size limit");
        this.maxResultBytes = maxResultBytes;
    }

    MailAnalysisQueue.Outcome process(String subject, String requirementText) {
        int maxText = Math.max(100, Math.min(100_000, settings.getInt("limits.max-business-text", 5_000)));
        if (requirementText == null || requirementText.isBlank() || requirementText.length() > maxText) {
            throw new Rejected("Der Anforderungstext ist leer oder überschreitet die Grenze von " + maxText + " Zeichen.");
        }
        MailAnalysisCommand command;
        try {
            command = classifier.classify(subject);
        } catch (IllegalArgumentException invalid) {
            throw new Rejected("Der Betreff beschreibt keinen eindeutigen Auftrag mit verfügbaren Exportformaten."
                    + " Beispiel: Entscheidungsbaum BP und CI als Word.");
        }
        boolean architecture = command.mode() == MailAnalysisCommand.Mode.ARCHITECTURE;
        Set<String> roots = Set.copyOf(command.taxonomyCodes());
        AnalysisScope scope = new AnalysisScope(roots, architecture ? AnalysisMode.FULL : AnalysisMode.TAXONOMIES_ONLY);
        try {
            scope.validateRoots(taxonomies.getRootCodes());
        } catch (IllegalArgumentException changed) {
            throw new Rejected("Eine ausgewählte Teiltaxonomie ist derzeit nicht verfügbar.");
        }

        // Mail requests use the shared catalogue context. They never inherit a person's selected workspace.
        var primary = repositories.getPrimaryRepository();
        String actor = WorkspaceManager.DEFAULT_USER;
        if (!membership.canRead(primary, actor)) throw new Rejected("Die gemeinsame Taxonomie ist derzeit nicht verfügbar.");
        var context = new WorkspaceContext(actor, null, primary.getDefaultBranch(), primary.getRepositoryId());
        var source = AnalysisRequestModelAdmission.capture(admission.getIfAvailable(), context, null, null, scope);
        int maxNodes = Math.max(1, Math.min(1_000, settings.getInt("limits.max-architecture-nodes", 50)));
        var request = new AnalyzeRequirementCommand(requirementText, architecture, maxNodes, null, actor, context, null, scope)
                .withAnalysisLanguage(language).withModelBinding(source);
        AnalysisResult result = analysis.analyzeUnpublished(request).analysisResult();
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
        return new MailAnalysisQueue.Outcome(summary, List.copyOf(attachments), true);
    }

    private List<MailAnalysisAttachment> architectureFiles(MailAnalysisCommand command, AnalysisResult result, String requirementText) {
        RequirementArchitectureView view = result.getArchitectureView();
        if (view == null || view.getIncludedElements() == null || view.getIncludedElements().isEmpty()) {
            throw new Rejected("Die Analyse hat keine exportierbare Architektur geliefert.");
        }
        var diagram = diagrams.buildCurrentDiagram(view, language.value());
        var reasons = ExportFacade.elementReasons(view);
        var options = new java.util.HashMap<String, Object>();
        options.put("elementReasons", reasons);
        options.put("locale", language.value());
        if (view.getOriginalRequirementText() != null) options.put("originalRequirementText", view.getOriginalRequirementText());

        var files = new ArrayList<MailAnalysisAttachment>();
        long total = 0;
        for (var format : command.exports()) {
            MailAnalysisAttachment file;
            if (format == MailAnalysisCommand.Format.PDF) {
                byte[] bytes = pdf.renderWorkingView(layout.layout(diagram), reasons, Map.of(), locale);
                file = new MailAnalysisAttachment("requirement-architecture.pdf", "application/pdf", bytes);
            } else if (format == MailAnalysisCommand.Format.DOCX) {
                file = architectureWord(result, requirementText);
            } else {
                var renderer = formats.findByFormatId(format.name().toLowerCase(Locale.ROOT))
                        .orElseThrow(() -> new Rejected("Ein angefordertes Architekturformat ist derzeit nicht verfügbar."));
                var descriptor = renderer.descriptor();
                var output = renderer.export(new ExportContext(diagram, options));
                file = new MailAnalysisAttachment("requirement-architecture." + descriptor.fileExtension(),
                        descriptor.contentType(), output.bytes());
            }
            total = include(files, file, total);
        }
        return files;
    }

    private MailAnalysisAttachment architectureWord(AnalysisResult result, String requirementText) {
        if (result.getCopilot() == null) throw new Rejected("Die Analyse hat keinen exportierbaren Architekturbericht geliefert.");
        var renderer = reportFormats.findByFormatId("docx")
                .orElseThrow(() -> new Rejected("Der Word-Architekturbericht ist derzeit nicht verfügbar."));
        var report = architectureReport.generateRetainedReport(result, requirementText);
        var reasons = result.getReasons() == null ? Map.of() : result.getReasons();
        var output = renderer.render(new ReportRenderContext(report, Map.of("language", language.value(), "nodeReasons", reasons)));
        var descriptor = renderer.descriptor();
        return new MailAnalysisAttachment("architecture-report." + descriptor.fileExtension(), descriptor.contentType(), output.bytes());
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

        var report = scoreSemantics.adapt(reports.generate(input, context, result.getViewContext(), locale, options), details, locale);
        var files = new ArrayList<MailAnalysisAttachment>();
        long total = 0;
        for (var format : command.exports()) {
            var id = format == MailAnalysisCommand.Format.PDF ? "visio-pdf" : format.name().toLowerCase(Locale.ROOT);
            var renderer = reportFormats.findByFormatId(DecisionRationaleReportPlugin.REPORT_TYPE_ID, id)
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
