package com.taxonomy.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class MailAnalysisProcessorTest {
    private final MailAnalysisCommandClassifier classifier = mock(MailAnalysisCommandClassifier.class);
    private final TaxonomyService taxonomy = mock(TaxonomyService.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    private final AnalysisRuntimeSettings settings = mock(AnalysisRuntimeSettings.class);
    private final AnalyzeRequirementUseCase analysis = mock(AnalyzeRequirementUseCase.class);
    private final ExportFacade diagrams = mock(ExportFacade.class);
    private final ExportFormatExtensionRegistry formats = mock(ExportFormatExtensionRegistry.class);
    private final ArchitectureReportService architectureReport = mock(ArchitectureReportService.class);
    private final DecisionRationaleReportService reports = mock(DecisionRationaleReportService.class);
    private final ReportRendererRegistry reportFormats = mock(ReportRendererRegistry.class);

    private final MailAnalysisProcessor processor = new MailAnalysisProcessor(classifier, taxonomy, repositories, membership,
            new StaticListableBeanFactory().getBeanProvider(com.taxonomy.analysis.usecase.AnalysisAdmissionModelPort.class),
            settings, analysis, diagrams, formats, mock(LayeredDiagramLayoutService.class), mock(ArchitecturePdfRenderer.class),
            architectureReport, reports, reportFormats, "de", 10_000);

    @BeforeEach
    void setUp() {
        var primary = new SystemRepository();
        primary.setRepositoryId("primary");
        primary.setDefaultBranch("main");
        when(repositories.getPrimaryRepository()).thenReturn(primary);
        when(membership.canRead(primary, WorkspaceManager.DEFAULT_USER)).thenReturn(true);
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
        when(analysis.analyzeUnpublished(any())).thenReturn(new AnalyzeRequirementResult(result));
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

        var output = processor.process("Architektur als SVG und Word", "Text und Anhang");

        assertThat(output.attachments()).extracting(MailAnalysisAttachment::filename)
                .containsExactly("requirement-architecture.svg", "architecture-report.docx");
        verify(diagrams).buildCurrentDiagram(architecture, "de");
        verifyNoInteractions(reports);
        var request = capturedAnalysis();
        assertThat(request.businessText()).isEqualTo("Text und Anhang");
        assertThat(request.workspaceContext().workspaceId()).isNull();
        assertThat(request.workspaceContext().repositoryId()).isEqualTo("primary");
        assertThat(request.includeArchitectureView()).isTrue();
        assertThat(request.analysisScope().legacyFull()).isTrue();
    }

    @Test
    void separateDecisionRootsAreScoredWithoutRelationsAndPartialDeliveryIsMarked() {
        when(classifier.classify("Entscheidungsbaum BP/CI")).thenReturn(new MailAnalysisCommand(
                MailAnalysisCommand.Mode.DECISION_TREE, List.of("BP-1000", "CI-1000"), List.of(MailAnalysisCommand.Format.HTML)));
        AnalysisResult result = result("PARTIAL");
        when(analysis.analyzeUnpublished(any())).thenReturn(new AnalyzeRequirementResult(result));
        var report = new DecisionRationaleReport("Baum", "de", "Anforderung", null, null, null, null, null, null,
                null, null, null, null);
        when(reports.generate(any(), any(), eq(null), eq(Locale.GERMAN), any(DecisionReportOptions.class))).thenReturn(report);
        var renderer = mock(ReportRendererExtension.class);
        when(reportFormats.findByFormatId(DecisionRationaleReportPlugin.REPORT_TYPE_ID, "html")).thenReturn(Optional.of(renderer));
        when(renderer.descriptor()).thenReturn(new ReportFormatDescriptor("html", "HTML", "html", "text/html", true));
        when(renderer.render(any())).thenReturn(new ReportRenderResult(new byte[] {1}));

        var output = processor.process("Entscheidungsbaum BP/CI", "Anforderung");

        assertThat(output.summary()).contains("Teilstand");
        assertThat(output.attachments()).extracting(MailAnalysisAttachment::filename).containsExactly("decision-tree.html");
        var request = capturedAnalysis();
        assertThat(request.includeArchitectureView()).isFalse();
        assertThat(request.analysisScope().mode()).isEqualTo(AnalysisMode.TAXONOMIES_ONLY);
        assertThat(request.analysisScope().taxonomyRoots()).containsExactly("BP-1000", "CI-1000");
        verifyNoInteractions(diagrams);
    }

    private static AnalysisResult result(String status) {
        var result = new AnalysisResult(Map.of("BP-1000", 84), List.of());
        result.setStatus(status);
        return result;
    }

    private AnalyzeRequirementCommand capturedAnalysis() {
        var request = ArgumentCaptor.forClass(AnalyzeRequirementCommand.class);
        verify(analysis).analyzeUnpublished(request.capture());
        return request.getValue();
    }
}
