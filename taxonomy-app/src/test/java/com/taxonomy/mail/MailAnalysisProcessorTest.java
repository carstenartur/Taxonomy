package com.taxonomy.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.service.ArchitectureReportService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisCopilotResult;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionRationaleReportPlugin;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.render.document.ArchitectureReportTextRenderer;
import com.taxonomy.reporting.render.document.HtmlReportRendererExtension;
import com.taxonomy.reporting.render.document.JsonReportRendererExtension;
import com.taxonomy.reporting.render.document.MarkdownReportRendererExtension;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.taxonomy.composition.analysis.SelectedArchitectureAdmissionModel;

class MailAnalysisProcessorTest {
    private static final String JOB_ID = "e9da5d15-973a-4a89-9d02-0c13253e8784";
    private final MailAnalysisCommandClassifier classifier = mock(MailAnalysisCommandClassifier.class);
    private final TaxonomyService taxonomy = mock(TaxonomyService.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    private final SelectedArchitectureAdmissionModel admission = mock(SelectedArchitectureAdmissionModel.class);
    private final AnalysisRuntimeSettings settings = mock(AnalysisRuntimeSettings.class);
    private final AnalyzeRequirementUseCase analysis = mock(AnalyzeRequirementUseCase.class);
    private final ExportFacade diagrams = mock(ExportFacade.class);
    private final ExportFormatExtensionRegistry formats = mock(ExportFormatExtensionRegistry.class);
    private final ArchitectureReportService architectureReport = mock(ArchitectureReportService.class);
    private final DecisionRationaleReportService reports = mock(DecisionRationaleReportService.class);
    private final ReportRendererRegistry reportFormats = mock(ReportRendererRegistry.class);
    private AnalyzeRequirementCommand admitted;

    private final MailAnalysisProcessor processor = new MailAnalysisProcessor(classifier, taxonomy, repositories, membership,
            admission,
            settings, analysis, diagrams, new MailAnalysisRenderers(formats, reportFormats),
            mock(LayeredDiagramLayoutService.class), mock(ArchitecturePdfRenderer.class),
            architectureReport, reports, "de", 10_000);

    @BeforeEach
    void setUp() {
        var primary = new SystemRepository();
        primary.setRepositoryId("primary");
        primary.setDefaultBranch("main");
        when(repositories.getPrimaryRepository()).thenReturn(primary);
        when(membership.canRead(eq(primary), org.mockito.ArgumentMatchers.startsWith("mail-"))).thenReturn(true);
        when(taxonomy.getRootCodes()).thenReturn(Set.of("BP-1000", "CI-1000", "IP-1000"));
        when(settings.getInt("limits.max-business-text", 5_000)).thenReturn(5_000);
        when(settings.getInt("limits.max-architecture-nodes", 50)).thenReturn(50);
    }

    @Test
    void fullArchitectureUsesOneUnpublishedRunAndItsExistingDiagram() {
        when(classifier.classify("Architektur als SVG und Word")).thenReturn(new MailAnalysisCommand(
                MailAnalysisCommand.Mode.ARCHITECTURE, List.of(), List.of(MailAnalysisCommand.Format.SVG, MailAnalysisCommand.Format.DOCX)));
        AnalysisResult result = result("SUCCESS");
        var architecture = new RequirementArchitectureView();
        var node = new RequirementElementView();
        node.setNodeCode("BP-1000");
        architecture.setIncludedElements(List.of(node));
        result.setArchitectureView(architecture);
        result.setCopilot(mock(AnalysisCopilotResult.class));
        admitAndReturn(result);
        when(diagrams.buildCurrentDiagram(architecture, "de")).thenReturn(mock(com.taxonomy.diagram.DiagramModel.class));
        var renderer = mock(ExportFormatExtension.class);
        when(formats.findByFormatId("svg")).thenReturn(Optional.of(renderer));
        when(renderer.descriptor()).thenReturn(new ExportFormatDescriptor("svg", "SVG", "svg", "image/svg+xml", true));
        when(renderer.export(any())).thenReturn(new ExportResult(new byte[] {7}));
        var word = mock(ReportRendererExtension.class);
        when(reportFormats.findByFormatId("docx")).thenReturn(Optional.of(word));
        when(architectureReport.generateRetainedReport(result, "Text und Anhang")).thenReturn(new ArchitectureReport());
        when(word.descriptor()).thenReturn(new ReportFormatDescriptor("docx", "Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true));
        when(word.render(any())).thenReturn(new ReportRenderResult(new byte[] {8}));

        var output = process("Architektur als SVG und Word", "Text und Anhang");

        assertThat(output.attachments()).extracting(MailAnalysisAttachment::filename)
                .containsExactly("requirement-architecture.svg", "architecture-report.docx");
        verify(diagrams).buildCurrentDiagram(architecture, "de");
        verifyNoInteractions(reports);
        var request = admitted;
        assertThat(request.businessText()).isEqualTo("Text und Anhang");
        assertThat(request.workspaceContext().workspaceId()).isNull();
        assertThat(request.username()).startsWith("mail-").isEqualTo(request.workspaceContext().username());
        verify(membership).canRead(any(SystemRepository.class), eq(request.username()));
        assertThat(request.workspaceContext().repositoryId()).isEqualTo("primary");
        assertThat(request.includeArchitectureView()).isTrue();
        assertThat(request.analysisScope().legacyFull()).isTrue();
    }

    @Test
    void reportOnlyDeliveryRendersOneCapturedReportInEachSelectedFormat() {
        var requested = List.of(MailAnalysisCommand.Format.MARKDOWN, MailAnalysisCommand.Format.HTML,
                MailAnalysisCommand.Format.JSON);
        when(classifier.classify("Berichte")).thenReturn(new MailAnalysisCommand(
                MailAnalysisCommand.Mode.ARCHITECTURE, List.of(), requested));
        var result = result("SUCCESS");
        var architecture = new RequirementArchitectureView();
        var node = new RequirementElementView();
        node.setNodeCode("BP-1000");
        architecture.setIncludedElements(List.of(node));
        result.setArchitectureView(architecture);
        result.setCopilot(mock(AnalysisCopilotResult.class));
        result.setReasons(Map.of("BP-1000", "Begründung aus dem Lauf"));
        admitAndReturn(result);
        var retained = new ArchitectureReport();
        retained.setBusinessText("Dienstreise genehmigen");
        retained.setScores(result.getScores());
        when(architectureReport.generateRetainedReport(result, "Dienstreise genehmigen")).thenReturn(retained);
        var text = new ArchitectureReportTextRenderer();
        for (var renderer : List.of(new MarkdownReportRendererExtension(text), new HtmlReportRendererExtension(text),
                new JsonReportRendererExtension(new StaticListableBeanFactory().getBeanProvider(
                        com.fasterxml.jackson.databind.ObjectMapper.class)))) {
            when(reportFormats.findByFormatId(renderer.descriptor().id())).thenReturn(Optional.of(renderer));
        }

        var output = process("Berichte", "Dienstreise genehmigen");

        assertThat(output.attachments()).extracting(MailAnalysisAttachment::filename)
                .containsExactly("architecture-report.md", "architecture-report.html", "architecture-report.json");
        assertThat(output.attachments()).allSatisfy(file -> assertThat(new String(file.content(), StandardCharsets.UTF_8))
                .contains("Dienstreise genehmigen", "BP-1000"));
        var json = new tools.jackson.databind.ObjectMapper().readTree(output.attachments().getLast().content());
        assertThat(json.get("languageTag").asString()).isEqualTo("de");
        assertThat(json.get("nodeReasons").get("BP-1000").asString()).isEqualTo("Begründung aus dem Lauf");
        verify(architectureReport).generateRetainedReport(result, "Dienstreise genehmigen");
        verifyNoInteractions(diagrams);
    }

    @Test
    void separateDecisionRootsAreScoredWithoutRelationsAndPartialDeliveryIsMarked() {
        when(classifier.classify("Entscheidungsbaum BP/CI")).thenReturn(new MailAnalysisCommand(
                MailAnalysisCommand.Mode.DECISION_TREE, List.of("BP-1000", "CI-1000"), List.of(MailAnalysisCommand.Format.HTML)));
        AnalysisResult result = result("PARTIAL");
        admitAndReturn(result);
        var report = new DecisionRationaleReport("Baum", "de", "Anforderung", null, null, null, null, null, null,
                null, null, null, null);
        when(reports.generate(any(), any(), eq(null), eq(Locale.GERMAN), any(DecisionReportOptions.class))).thenReturn(report);
        var renderer = mock(ReportRendererExtension.class);
        when(reportFormats.findByFormatId(DecisionRationaleReportPlugin.REPORT_TYPE_ID, "html")).thenReturn(Optional.of(renderer));
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor("html", "HTML", "html", "text/html", true));
        when(renderer.render(any())).thenReturn(new ReportRenderResult(new byte[] {1}));

        var output = process("Entscheidungsbaum BP/CI", "Anforderung");

        assertThat(output.partial()).isTrue();
        assertThat(output.summary()).contains("Teilstand");
        assertThat(output.attachments()).extracting(MailAnalysisAttachment::filename).containsExactly("decision-tree.html");
        var request = admitted;
        assertThat(request.includeArchitectureView()).isFalse();
        assertThat(request.analysisScope().mode()).isEqualTo(AnalysisMode.TAXONOMIES_ONLY);
        assertThat(request.analysisScope().taxonomyRoots()).containsExactly("BP-1000", "CI-1000");
        verify(reports).generate(any(), eq(new WorkspaceContext("mail", null, "main", "primary")),
                eq(null), eq(Locale.GERMAN), any(DecisionReportOptions.class));
        verifyNoInteractions(diagrams);
    }

    private static AnalysisResult result(String status) {
        var result = new AnalysisResult(Map.of("BP-1000", 84), List.of());
        result.setStatus(status);
        return result;
    }

    @Test
    void retainedDeliveryUsesTheSamePrivateOperationAndOwnerWhileLiveSourceIsUnavailable() {
        String separateJob = "5899dec4-f0ef-40f4-84ca-8d8ba194719b";
        String text = "Anforderung";
        var command = new MailAnalysisCommand(MailAnalysisCommand.Mode.DECISION_TREE,
                List.of("BP-1000"), List.of(MailAnalysisCommand.Format.HTML));
        var offlineSource = mock(BooleanSupplier.class);
        when(analysis.analyzeUnpublished(anyString(), any(), eq(text), any()))
                .thenThrow(new DataAccessResourceFailureException("Durable analysis store unavailable"))
                .thenReturn(new AnalyzeRequirementResult(result("ERROR")));

        assertThatThrownBy(() -> processor.process(JOB_ID, command, text, offlineSource)).isInstanceOf(MailAnalysisProcessor.SourceUnavailable.class);
        assertThatThrownBy(() -> processor.process(JOB_ID, command, text, offlineSource)).isInstanceOf(MailAnalysisProcessor.Rejected.class);
        assertThatThrownBy(() -> processor.process(separateJob, command, text, offlineSource)).isInstanceOf(MailAnalysisProcessor.Rejected.class);

        var operations = ArgumentCaptor.forClass(String.class);
        var scopes = ArgumentCaptor.forClass(WorkspaceContext.class);
        verify(analysis, times(3)).analyzeUnpublished(operations.capture(), scopes.capture(), eq(text), any());
        assertThat(scopes.getAllValues().get(1)).isEqualTo(scopes.getAllValues().getFirst());
        assertThat(scopes.getAllValues().get(2)).isNotEqualTo(scopes.getAllValues().getFirst());
        assertThat(operations.getAllValues().get(1)).isEqualTo(operations.getAllValues().getFirst());
        assertThat(operations.getAllValues().get(2)).isNotEqualTo(operations.getAllValues().getFirst());
        assertThat(operations.getAllValues().getFirst())
                .doesNotContain(JOB_ID).isNotEqualTo(scopes.getAllValues().getFirst().username());
        assertThat(scopes.getAllValues().getFirst().username()).doesNotContain(JOB_ID);
        verifyNoInteractions(classifier, offlineSource, taxonomy, admission);
    }

    @Test
    void missingPrimaryMetadataDefersTheDeliveryWithoutStartingAnalysis() {
        when(repositories.getPrimaryRepository()).thenThrow(new IllegalStateException("Primary missing"));
        var command = new MailAnalysisCommand(MailAnalysisCommand.Mode.ARCHITECTURE,
                List.of(), List.of(MailAnalysisCommand.Format.PDF));

        assertThatThrownBy(() -> processor.process(JOB_ID, command, "Anforderung", () -> false))
                .isInstanceOf(MailAnalysisProcessor.SourceUnavailable.class);
        verifyNoInteractions(analysis, classifier, taxonomy, admission);
    }

    @Test
    void unavailableGitSourceDefersWhileSourceConflictsRemainTerminal() {
        var conflict = new ResponseStatusException(HttpStatus.CONFLICT, "The source changed");
        when(admission.capturePrimary(any(), anyString(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The source is unavailable"))
                .thenThrow(conflict);
        admitAndReturn(result("ERROR"));
        var command = new MailAnalysisCommand(MailAnalysisCommand.Mode.ARCHITECTURE,
                List.of(), List.of(MailAnalysisCommand.Format.PDF));

        assertThatThrownBy(() -> processor.process(JOB_ID, command, "Anforderung", () -> true))
                .isInstanceOf(MailAnalysisProcessor.SourceUnavailable.class);
        assertThatThrownBy(() -> processor.process(JOB_ID, command, "Anforderung", () -> true)).isSameAs(conflict);
    }

    @Test
    void temporarilyUnavailableCatalogueDefersClassification() {
        when(classifier.classify("Entscheidungsbaum"))
                .thenThrow(new DataAccessResourceFailureException("Catalogue unavailable"))
                .thenThrow(new IllegalArgumentException("Unsupported subject"));

        assertThatThrownBy(() -> processor.classify("Entscheidungsbaum", "Anforderung"))
                .isInstanceOf(MailAnalysisProcessor.SourceUnavailable.class);
        assertThatThrownBy(() -> processor.classify("Entscheidungsbaum", "Anforderung"))
                .isInstanceOf(MailAnalysisProcessor.Rejected.class);
        verifyNoInteractions(analysis);
    }

    private MailAnalysisQueue.Outcome process(String subject, String text) {
        return processor.process(JOB_ID, processor.classify(subject, text), text, () -> true);
    }

    private void admitAndReturn(AnalysisResult result) {
        when(analysis.analyzeUnpublished(anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            admitted = invocation.<Supplier<AnalyzeRequirementCommand>>getArgument(3).get();
            return new AnalyzeRequirementResult(result);
        });
    }
}
