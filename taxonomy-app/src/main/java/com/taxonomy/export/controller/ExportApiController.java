package com.taxonomy.export.controller;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.service.SavedAnalysisService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.visio.VisioDocument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Export")
public class ExportApiController {

    private final LlmService llmService;
    private final RequirementArchitectureViewService architectureViewService;
    private final DiagramProjectionService diagramProjectionService;
    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;
    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;
    private final MermaidExportService mermaidExportService;
    private final SavedAnalysisService savedAnalysisService;

    public ExportApiController(LlmService llmService,
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

    // ── Diagram Export Helper ─────────────────────────────────────────────────

    /**
     * Shared analysis-and-projection pipeline used by all three diagram export
     * endpoints.  Analyzes the business text, builds an architecture view, and
     * projects it into a format-neutral {@link DiagramModel}.
     */
    private DiagramModel analyzeAndProject(String businessText) {
        AnalysisResult result = llmService.analyzeWithBudget(businessText);

        RequirementArchitectureView view = architectureViewService.build(
                result.getScores(), businessText, 20);

        String title = businessText.length() > 60
                ? businessText.substring(0, 57) + "..."
                : businessText;
        return diagramProjectionService.project(view, title);
    }

    // ── Visio Diagram Export ──────────────────────────────────────────────────

    @Operation(summary = "Export Visio diagram", description = "Generates a Visio .vsdx architecture diagram from a business requirement", tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Visio file returned as binary attachment")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/diagram/visio")
    public ResponseEntity<byte[]> exportVisio(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            DiagramModel diagram = analyzeAndProject(businessText);

            VisioDocument visioDoc = visioDiagramService.convert(diagram);
            byte[] vsdx = visioPackageBuilder.build(visioDoc);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"requirement-architecture.vsdx\"");
            headers.set(HttpHeaders.CONTENT_TYPE, "application/vnd.ms-visio.drawing.main+xml");

            return ResponseEntity.ok().headers(headers).body(vsdx);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── ArchiMate Diagram Export ──────────────────────────────────────────────

    @Operation(summary = "Export ArchiMate XML", description = "Generates an ArchiMate Model Exchange File Format XML from a business requirement", tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "ArchiMate XML returned as attachment")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/diagram/archimate")
    public ResponseEntity<byte[]> exportArchiMate(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        DiagramModel diagram = analyzeAndProject(businessText);

        ArchiMateModel archiMateModel = archiMateDiagramService.convert(diagram);
        byte[] xml = archiMateXmlExporter.export(archiMateModel);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"requirement-architecture.xml\"");
        headers.set(HttpHeaders.CONTENT_TYPE, "application/xml");

        return ResponseEntity.ok().headers(headers).body(xml);
    }

    // ── Mermaid Diagram Export ────────────────────────────────────────────────

    @Operation(summary = "Export Mermaid diagram", description = "Generates a Mermaid flowchart from a business requirement for use in Markdown documents", tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Mermaid text returned")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/diagram/mermaid")
    public ResponseEntity<String> exportMermaid(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        DiagramModel diagram = analyzeAndProject(businessText);

        String mermaid = mermaidExportService.export(diagram);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .body(mermaid);
    }

    // ── Scores import / export endpoints ────────────────────────────────────────

    @Operation(summary = "Export analysis scores as JSON",
               description = "Returns a SavedAnalysis JSON with timestamp and version added. The frontend triggers a file download.",
               tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "SavedAnalysis JSON returned")
    @ApiResponse(responseCode = "400", description = "Requirement is blank or scores are missing")
    @PostMapping("/scores/export")
    public ResponseEntity<SavedAnalysis> exportScores(@RequestBody Map<String, Object> body) {
        String requirement = (String) body.get("requirement");
        if (requirement == null || requirement.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rawScores = body.get("scores") instanceof Map<?, ?>
                ? (Map<String, Object>) body.get("scores") : null;
        if (rawScores == null || rawScores.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rawScores.entrySet()) {
            if (e.getValue() instanceof Number n) {
                scores.put(e.getKey(), n.intValue());
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String> reasons = body.get("reasons") instanceof Map<?, ?>
                ? (Map<String, String>) body.get("reasons") : Map.of();
        String provider = body.get("provider") instanceof String p ? p : llmService.getActiveProviderName();

        SavedAnalysis saved = savedAnalysisService.buildExport(requirement, scores, reasons, provider);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Import analysis scores from JSON",
               description = "Validates a SavedAnalysis JSON and returns the scores, reasons, requirement, and any warnings.",
               tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Scores imported and returned with any warnings")
    @ApiResponse(responseCode = "400", description = "Invalid JSON format or validation failure")
    @PostMapping("/scores/import")
    public ResponseEntity<Map<String, Object>> importScores(@RequestBody String jsonBody) {
        try {
            SavedAnalysis saved = savedAnalysisService.importFromJson(jsonBody);
            List<String> warnings = savedAnalysisService.findUnknownCodes(saved)
                    .stream()
                    .map(code -> "Unknown node code: " + code)
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requirement", saved.getRequirement());
            result.put("scores",      saved.getScores() != null ? saved.getScores() : Map.of());
            result.put("reasons",     saved.getReasons() != null ? saved.getReasons() : Map.of());
            result.put("provider",    saved.getProvider());
            result.put("warnings",    warnings);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "warnings", List.of()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid JSON: " + e.getMessage(), "warnings", List.of()));
        }
    }
}
