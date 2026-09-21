package com.taxonomy.export.service;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.service.SavedAnalysisService;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.MermaidLabels;
import com.taxonomy.export.StructurizrExportService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.visio.VisioDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** High-level application facade for analysis-driven exports and score exchange. */
@Service
public class ExportFacade {

    private final LlmService llmService;
    private final RequirementArchitectureViewService architectureViewService;
    private final DiagramProjectionService diagramProjectionService;
    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;
    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;
    private final MermaidExportService mermaidExportService;
    private final StructurizrExportService structurizrExportService;
    private final SavedAnalysisService savedAnalysisService;

    public ExportFacade(LlmService llmService,
                        RequirementArchitectureViewService architectureViewService,
                        DiagramProjectionService diagramProjectionService,
                        VisioDiagramService visioDiagramService,
                        VisioPackageBuilder visioPackageBuilder,
                        ArchiMateDiagramService archiMateDiagramService,
                        ArchiMateXmlExporter archiMateXmlExporter,
                        MermaidExportService mermaidExportService,
                        StructurizrExportService structurizrExportService,
                        SavedAnalysisService savedAnalysisService) {
        this.llmService = llmService;
        this.architectureViewService = architectureViewService;
        this.diagramProjectionService = diagramProjectionService;
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
        this.mermaidExportService = mermaidExportService;
        this.structurizrExportService = structurizrExportService;
        this.savedAnalysisService = savedAnalysisService;
    }

    public byte[] exportAsVisio(String businessText) throws IOException {
        DiagramModel diagram = analyzeAndProject(businessText);
        VisioDocument visioDocument = visioDiagramService.convert(diagram);
        return visioPackageBuilder.build(visioDocument);
    }

    public byte[] exportAsArchiMate(String businessText) {
        DiagramModel diagram = analyzeAndProject(businessText);
        ArchiMateModel model = archiMateDiagramService.convert(diagram);
        return archiMateXmlExporter.export(model);
    }

    public String exportAsMermaid(String businessText) {
        return mermaidExportService.export(analyzeAndProject(businessText));
    }

    public String exportAsMermaid(String businessText, MermaidLabels labels) {
        return mermaidExportService.export(analyzeAndProject(businessText), labels);
    }

    public String exportAsStructurizrDsl(String businessText) {
        return structurizrExportService.export(analyzeAndProject(businessText));
    }

    public SavedAnalysis buildExport(String requirement,
                                     Map<String, Integer> scores,
                                     Map<String, String> reasons,
                                     String provider) {
        return savedAnalysisService.buildExport(requirement, scores, reasons, provider);
    }

    public SavedAnalysis importFromJson(String jsonBody) throws IOException {
        return savedAnalysisService.importFromJson(jsonBody);
    }

    public List<String> findUnknownCodes(SavedAnalysis saved) {
        return savedAnalysisService.findUnknownCodes(saved);
    }

    public String getActiveProviderName() {
        return llmService.getActiveProviderName();
    }

    /**
     * Builds the format-neutral diagram consumed by an {@link ExportFormatExtension}.
     */
    public DiagramModel buildDiagram(String businessText) {
        return analyzeAndProject(businessText);
    }

    /**
     * Exports the supplied working-view snapshot without scoring, fetching current
     * taxonomy data, applying a second selection policy, or persisting changes.
     * This is client-provided working data, not a certified historical snapshot.
     */
    public DiagramModel buildCurrentDiagram(RequirementArchitectureView view) {
        if (view == null || view.getIncludedElements() == null
                || view.getIncludedElements().isEmpty()) {
            throw new IllegalArgumentException("An existing architecture view is required; no analysis was started.");
        }
        if (view.getIncludedElements().size() > 10_000
                || view.getIncludedRelationships() == null
                || view.getIncludedRelationships().size() > 30_000) {
            throw new IllegalArgumentException("Architecture exceeds export capacity or has no relationship list.");
        }
        var codes = new java.util.HashSet<String>();
        for (var element : view.getIncludedElements()) {
            if (element == null || element.getNodeCode() == null || element.getNodeCode().isBlank()
                    || !codes.add(element.getNodeCode()) || !Double.isFinite(element.getRelevance())
                    || element.getRelevance() < 0 || element.getRelevance() > 1) {
                throw new IllegalArgumentException("Architecture contains invalid or duplicate nodes.");
            }
        }
        for (var relation : view.getIncludedRelationships()) {
            if (relation == null || !codes.contains(relation.getSourceCode())
                    || !codes.contains(relation.getTargetCode()) || relation.getRelationType() == null
                    || relation.getRelationType().isBlank() || !Double.isFinite(relation.getPropagatedRelevance())
                    || relation.getPropagatedRelevance() < 0 || relation.getPropagatedRelevance() > 1) {
                throw new IllegalArgumentException("Architecture contains invalid relationships or missing endpoints.");
            }
        }
        String title = view.getViewTitle();
        return diagramProjectionService.projectRaw(view,
                title == null || title.isBlank() ? "Requirement architecture" : title);
    }

    private DiagramModel analyzeAndProject(String businessText) {
        AnalysisResult result = llmService.analyzeWithBudget(businessText);
        RequirementArchitectureView view = architectureViewService.build(
                result.getScores(), businessText, 20);
        String title = businessText.length() > 60
                ? businessText.substring(0, 57) + "..."
                : businessText;
        return diagramProjectionService.project(view, title);
    }
}
