package com.taxonomy.export.service;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.service.SavedAnalysisService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.visio.VisioDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * High-level facade that aggregates the export domain services.
 *
 * <p>Provides coarse-grained operations for diagram export (Visio,
 * ArchiMate, Mermaid) and scores import/export, so that the
 * {@code ExportApiController} only needs a single dependency.
 */
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
    private final SavedAnalysisService savedAnalysisService;

    public ExportFacade(LlmService llmService,
                        RequirementArchitectureViewService architectureViewService,
                        DiagramProjectionService diagramProjectionService,
                        VisioDiagramService visioDiagramService,
                        VisioPackageBuilder visioPackageBuilder,
                        ArchiMateDiagramService archiMateDiagramService,
                        ArchiMateXmlExporter archiMateXmlExporter,
                        MermaidExportService mermaidExportService,
                        SavedAnalysisService savedAnalysisService) {
        this.llmService = llmService;
        this.architectureViewService = architectureViewService;
        this.diagramProjectionService = diagramProjectionService;
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
        this.mermaidExportService = mermaidExportService;
        this.savedAnalysisService = savedAnalysisService;
    }

    // ── Diagram export ──────────────────────────────────────────────

    /**
     * Analyze business text, build an architecture view, and export as Visio.
     *
     * @param businessText the requirement text to analyze
     * @return the Visio .vsdx file as a byte array
     * @throws IOException if Visio package building fails
     */
    public byte[] exportAsVisio(String businessText) throws IOException {
        DiagramModel diagram = analyzeAndProject(businessText);
        VisioDocument visioDoc = visioDiagramService.convert(diagram);
        return visioPackageBuilder.build(visioDoc);
    }

    /**
     * Analyze business text, build an architecture view, and export as ArchiMate XML.
     *
     * @param businessText the requirement text to analyze
     * @return the ArchiMate XML as a byte array
     */
    public byte[] exportAsArchiMate(String businessText) {
        DiagramModel diagram = analyzeAndProject(businessText);
        ArchiMateModel archiMateModel = archiMateDiagramService.convert(diagram);
        return archiMateXmlExporter.export(archiMateModel);
    }

    /**
     * Analyze business text, build an architecture view, and export as Mermaid text.
     *
     * @param businessText the requirement text to analyze
     * @return the Mermaid flowchart text
     */
    public String exportAsMermaid(String businessText) {
        DiagramModel diagram = analyzeAndProject(businessText);
        return mermaidExportService.export(diagram);
    }

    // ── Scores import / export ──────────────────────────────────────

    /**
     * Build a {@link SavedAnalysis} export envelope for the given scores.
     */
    public SavedAnalysis buildExport(String requirement,
                                     Map<String, Integer> scores,
                                     Map<String, String> reasons,
                                     String provider) {
        return savedAnalysisService.buildExport(requirement, scores, reasons, provider);
    }

    /**
     * Import scores from a JSON string.
     */
    public SavedAnalysis importFromJson(String jsonBody) throws IOException {
        return savedAnalysisService.importFromJson(jsonBody);
    }

    /**
     * Find unknown taxonomy codes in a saved analysis.
     */
    public List<String> findUnknownCodes(SavedAnalysis saved) {
        return savedAnalysisService.findUnknownCodes(saved);
    }

    /**
     * Returns the name of the currently active LLM provider.
     */
    public String getActiveProviderName() {
        return llmService.getActiveProviderName();
    }

    // ── Internal helpers ────────────────────────────────────────────

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
