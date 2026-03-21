package com.taxonomy.export.controller;

import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.export.service.ExportFacade;
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

    private final ExportFacade exportFacade;

    public ExportApiController(ExportFacade exportFacade) {
        this.exportFacade = exportFacade;
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
            byte[] vsdx = exportFacade.exportAsVisio(businessText);

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

        byte[] xml = exportFacade.exportAsArchiMate(businessText);

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

        String mermaid = exportFacade.exportAsMermaid(businessText);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .body(mermaid);
    }

    // ── Structurizr DSL Export ────────────────────────────────────────────────

    @Operation(summary = "Export Structurizr DSL", description = "Generates a Structurizr workspace DSL from a business requirement for C4 tools", tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Structurizr DSL returned as text")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/diagram/structurizr")
    public ResponseEntity<byte[]> exportStructurizrDsl(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String dsl = exportFacade.exportAsStructurizrDsl(businessText);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"workspace.dsl\"");
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8");

        return ResponseEntity.ok().headers(headers).body(dsl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        String provider = body.get("provider") instanceof String p ? p : exportFacade.getActiveProviderName();

        SavedAnalysis saved = exportFacade.buildExport(requirement, scores, reasons, provider);
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
            SavedAnalysis saved = exportFacade.importFromJson(jsonBody);
            List<String> warnings = exportFacade.findUnknownCodes(saved)
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
