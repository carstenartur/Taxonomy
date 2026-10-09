package com.taxonomy.export.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Tag(name = "Export")
public class ExportApiController {
    @org.springframework.beans.factory.annotation.Autowired
    private tools.jackson.databind.ObjectMapper recoveryObjectMapper;


    /** Schema for existing map-based input; extension options remain accepted unchanged. */
    @Schema(name = "DiagramAnalysisRequest", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    public record DiagramRequestSchema(
            @Schema(description = "Non-blank business requirement to analyze", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
            String businessText,
            @Schema(description = "Optional output locale; built-in Mermaid uses German for values beginning with de and English otherwise")
            String locale) { }

    private static final Logger log = LoggerFactory.getLogger(ExportApiController.class);

    private final ExportFacade exportFacade;
    private final ExportFormatExtensionRegistry exportFormatRegistry;

    public ExportApiController(ExportFacade exportFacade,
                               ExportFormatExtensionRegistry exportFormatRegistry) {
        this.exportFacade = exportFacade;
        this.exportFormatRegistry = exportFormatRegistry;
    }

    @Operation(summary = "Export Visio diagram",
            description = "Generates a Visio .vsdx architecture diagram from a business requirement",
            tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Visio file returned as binary attachment")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Business requirement and optional adapter options. This endpoint may execute analysis; "
                    + "use /api/diagram/current/{formatId} to export an existing view without another AI call.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagramRequestSchema.class)))
    @ApiResponse(responseCode = "500", description = "Diagram file generation failed", content = @Content)
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
            headers.set(HttpHeaders.CONTENT_TYPE, "application/vnd.ms-visio.drawing");
            return ResponseEntity.ok().headers(headers).body(vsdx);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Export ArchiMate XML",
            description = "Generates an ArchiMate Model Exchange File Format XML from a business requirement",
            tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "ArchiMate XML returned as attachment")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Business requirement and optional adapter options. This endpoint may execute analysis; "
                    + "use /api/diagram/current/{formatId} to export an existing view without another AI call.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagramRequestSchema.class)))
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

    @Operation(summary = "Export Mermaid diagram",
            description = "Generates a Mermaid flowchart from a business requirement for use in Markdown documents. Accepts optional 'locale' field ('en' or 'de') to localize layer and relation labels.",
            tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Mermaid text returned")
    @ApiResponse(responseCode = "404", description = "Mermaid plugin is not installed or active", content = @Content)
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Business requirement and optional adapter options. This endpoint may execute analysis; "
                    + "use /api/diagram/current/{formatId} to export an existing view without another AI call.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagramRequestSchema.class)))
    @PostMapping("/diagram/mermaid")
    public ResponseEntity<String> exportMermaid(@RequestBody Map<String, Object> body) {
        ResponseEntity<byte[]> response = exportByFormat("mermaid", body);
        var result = ResponseEntity.status(response.getStatusCode());
        if (response.getStatusCode().is2xxSuccessful()) result.header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8");
        return result.body(response.getBody() == null ? null
                : new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Operation(summary = "Export Structurizr DSL",
            description = "Generates a Structurizr workspace DSL from a business requirement for C4 tools",
            tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Structurizr DSL returned as text")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Business requirement and optional adapter options. This endpoint may execute analysis; "
                    + "use /api/diagram/current/{formatId} to export an existing view without another AI call.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagramRequestSchema.class)))
    @PostMapping("/diagram/structurizr")
    public ResponseEntity<byte[]> exportStructurizrDsl(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String dsl = exportFacade.exportAsStructurizrDsl(businessText);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"workspace.dsl\"");
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8");
        return ResponseEntity.ok().headers(headers)
                .body(dsl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Operation(summary = "Export diagram in any registered format",
            description = "Exports a diagram in the format identified by {formatId}. Registered formats: archimate, mermaid, structurizr, visio, plus any custom ExportFormatExtension components. An optional 'locale' field is forwarded to the adapter.",
            tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Diagram file returned as attachment")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @ApiResponse(responseCode = "404", description = "Unknown format ID")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Business requirement and optional adapter options. This endpoint may execute analysis; "
                    + "use /api/diagram/current/{formatId} to export an existing view without another AI call.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagramRequestSchema.class)))
    @ApiResponse(responseCode = "500", description = "Diagram file generation failed", content = @Content)
    @PostMapping("/diagram/export/{formatId}")
    public ResponseEntity<byte[]> exportByFormat(
            @Parameter(description = "Registered export format identifier; discover formats through the extensions API") @PathVariable String formatId,
            @RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Optional<ExportFormatExtension> extensionOpt = exportFormatRegistry.findByFormatId(formatId);
        if (extensionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ExportFormatExtension extension = extensionOpt.get();
        ExportFormatDescriptor descriptor = extension.descriptor();
        Map<String, Object> options = new HashMap<>(body);
        options.remove("businessText");

        try {
            DiagramModel diagram = exportFacade.buildDiagram(businessText);
            ExportResult result = extension.export(new ExportContext(diagram, options));
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"diagram." + descriptor.fileExtension() + "\"");
            headers.set(HttpHeaders.CONTENT_TYPE, descriptor.contentType());
            return ResponseEntity.ok().headers(headers).body(result.bytes());
        } catch (UncheckedIOException e) {
            log.error("Export failed for format '{}': {}", formatId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Export the existing architecture without AI analysis",
            description = "Serializes a client-provided working-view snapshot. No re-scoring, second node selection, or persistence occurs. Use project snapshot exports for historical provenance.")
    @ApiResponse(responseCode = "200", description = "Diagram file returned as attachment")
    @ApiResponse(responseCode = "400", description = "Missing, malformed or oversized architecture view")
    @ApiResponse(responseCode = "404", description = "Unknown format identifier", content = @Content)
    @ApiResponse(responseCode = "500", description = "File generation failed; working state unchanged")
    @PostMapping("/diagram/current/{formatId}")
    public ResponseEntity<?> exportCurrentDiagram(@Parameter(description = "Registered export format identifier") @PathVariable("formatId") String formatId,
            @RequestBody RequirementArchitectureView view) {
        Optional<ExportFormatExtension> extensionOpt = exportFormatRegistry.findByFormatId(formatId);
        if (extensionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            DiagramModel diagram = exportFacade.buildCurrentDiagram(view);
            ExportFormatExtension extension = extensionOpt.get();
            ExportFormatDescriptor descriptor = extension.descriptor();
            ExportResult result = extension.export(new ExportContext(diagram, Map.of()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"requirement-architecture." + descriptor.fileExtension() + "\"")
                    .header(HttpHeaders.CONTENT_TYPE, descriptor.contentType())
                    .body(result.bytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (UncheckedIOException e) {
            log.error("Current architecture export failed for format '{}'", formatId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "The architecture file could not be created. The working state was not changed."));
        }
    }

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
        for (Map.Entry<String, Object> entry : rawScores.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                scores.put(entry.getKey(), number.intValue());
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String> reasons = body.get("reasons") instanceof Map<?, ?>
                ? (Map<String, String>) body.get("reasons") : Map.of();
        String provider = body.get("provider") instanceof String p
                ? p : exportFacade.getActiveProviderName();
        SavedAnalysis exported = exportFacade.buildExport(requirement, scores, reasons, provider);
        // Legacy and mixed worker evidence can be partial without v3 coverage.
        if (body.get("analysisStatus") instanceof String analysisStatus) {
            exported.setAnalysisStatus(analysisStatus);
        }
        if (body.get("analysisScope") != null) {
            try {
                exported.setAnalysisScope(recoveryObjectMapper.convertValue(body.get("analysisScope"), com.taxonomy.dto.AnalysisScope.class));
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
                return ResponseEntity.badRequest().build();
            }
        }
        if (body.get("analysisCoverage") != null) {
            try {
                var coverage = recoveryObjectMapper.convertValue(body.get("analysisCoverage"), com.taxonomy.dto.AnalysisCoverage.class);
                exported.setAnalysisCoverage(coverage);
                exported.setVersion(3);
                exported.setAnalysisStatus(coverage.hasOpenEvaluations() ? "PARTIAL" : String.valueOf(body.getOrDefault("analysisStatus", "UNKNOWN")));
                if (body.get("rawScores") instanceof Map<?, ?>) {
                    Map<String, Integer> raw = recoveryObjectMapper.convertValue(body.get("rawScores"),
                            new tools.jackson.core.type.TypeReference<Map<String, Integer>>() { });
                    if (raw.size() > 25000 || raw.values().stream().anyMatch(v -> v == null || v < 0 || v > 100))
                        return ResponseEntity.badRequest().build();
                    exported.setRawScores(raw);
                }
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) { return ResponseEntity.badRequest().build(); }
        }
        try {
            exportFacade.validateCoverageEvidence(exported);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(exported);
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
            result.put("scores", saved.getScores() != null ? saved.getScores() : Map.of());
            result.put("reasons", saved.getReasons() != null ? saved.getReasons() : Map.of());
            result.put("provider", saved.getProvider());
            result.put("warnings", warnings);
            result.put("analysisCoverage", saved.getAnalysisCoverage());
            result.put("analysisScope", saved.getAnalysisScope());
            result.put("rawScores", saved.getRawScores());
            result.put("analysisStatus", saved.getAnalysisCoverage() != null && saved.getAnalysisCoverage().hasOpenEvaluations()
                    ? "PARTIAL" : saved.getAnalysisStatus());
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
