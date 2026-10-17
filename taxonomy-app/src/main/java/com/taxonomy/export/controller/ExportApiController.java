package com.taxonomy.export.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
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
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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

@com.taxonomy.shared.features.ConditionalOnFeature({"analysis"})
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

    private final LayeredDiagramLayoutService currentDiagramLayout;
    private final ArchitecturePdfRenderer currentDiagramPdf;

    public ExportApiController(ExportFacade exportFacade,
                               ExportFormatExtensionRegistry exportFormatRegistry) {
        this(exportFacade, exportFormatRegistry, new LayeredDiagramLayoutService(), new ArchitecturePdfRenderer());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExportApiController(ExportFacade exportFacade,
                               ExportFormatExtensionRegistry exportFormatRegistry,
                               LayeredDiagramLayoutService currentDiagramLayout,
                               ArchitecturePdfRenderer currentDiagramPdf) {
        this.exportFacade = exportFacade;
        this.exportFormatRegistry = exportFormatRegistry;
        this.currentDiagramLayout = currentDiagramLayout;
        this.currentDiagramPdf = currentDiagramPdf;
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

    @Operation(summary = "Export the complete current architecture as PDF",
            description = "Renders a client-provided working view with bounded PDF pages and complete label and relationship evidence inventories. No AI analysis, repository lookup, second selection or persistence occurs; historical source authority is not asserted.")
    @ApiResponse(responseCode = "200", description = "Complete working-view PDF returned as attachment",
            content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "400", description = "Missing, malformed or oversized architecture view")
    @ApiResponse(responseCode = "500", description = "PDF generation failed; working state unchanged")
    @PostMapping("/diagram/current/pdf")
    public ResponseEntity<?> exportCurrentDiagramPdf(
            @Parameter(description = "Captured working architecture view with its saved labels and relationship evidence", required = true)
            @RequestBody RequirementArchitectureView view) {
        final DiagramModel diagram;
        try {
            diagram = exportFacade.buildCurrentDiagram(view);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "A valid architecture view within export capacity is required."));
        }
        try {
            byte[] bytes = currentDiagramPdf.renderWorkingView(currentDiagramLayout.layout(diagram));
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("Empty architecture PDF");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"requirement-architecture.pdf\"")
                    .body(bytes);
        } catch (RuntimeException failure) {
            log.error("Current working-view PDF export failed", failure);
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "The architecture PDF could not be created. The working state was not changed."));
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
        String requirement = body.get("requirement") instanceof String text ? text : null;
        if (requirement == null || requirement.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rawScores = body.get("scores") instanceof Map<?, ?>
                ? (Map<String, Object>) body.get("scores") : null;
        if (rawScores == null || rawScores.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        final Map<String, Double> scores;
        try { scores = percentageMap(rawScores); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().build(); }
        @SuppressWarnings("unchecked")
        Map<String, String> reasons = body.get("reasons") instanceof Map<?, ?>
                ? (Map<String, String>) body.get("reasons") : Map.of();
        String provider = body.get("provider") instanceof String p
                ? p : exportFacade.getActiveProviderName();
        SavedAnalysis exported = exportFacade.buildExport(requirement, scores, reasons, provider);
        if (body.get("analysisOperationId") instanceof String id) exported.setAnalysisOperationId(id);
        if (body.get("analysisProviderId") instanceof String id) exported.setAnalysisProviderId(id);
        if (body.get("analysisReasonLength") != null) {
            try {
                exported.setAnalysisReasonLength(recoveryObjectMapper.convertValue(body.get("analysisReasonLength"),
                        com.taxonomy.dto.AnalysisReasonLength.class));
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
                return ResponseEntity.badRequest().build();
            }
        }
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
                exported.setVersion(4);
                exported.setAnalysisStatus(coverage.hasOpenEvaluations() ? "PARTIAL" : String.valueOf(body.getOrDefault("analysisStatus", "UNKNOWN")));

            } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) { return ResponseEntity.badRequest().build(); }
        }
        try {
            // Keep raw product suitability even for a manual export without automatic coverage.
            if (body.get("rawScores") != null) exported.setRawScores(percentageMap(body.get("rawScores")));
            exportFacade.validateCoverageEvidence(exported);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(exported);
    }

    private static Map<String, Double> percentageMap(Object input) {
        if (!(input instanceof Map<?, ?> values) || values.size() > 25000)
            throw new IllegalArgumentException("Invalid score map");
        Map<String, Double> result = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String code) || code.isBlank()
                    || !(entry.getValue() instanceof Number score))
                throw new IllegalArgumentException("A score requires a node identity and numeric percentage");
            result.put(code, com.taxonomy.dto.AnalysisPercentages.require(score));
        }
        return result;
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
            result.put("analysisOperationId", saved.getAnalysisOperationId());
            result.put("analysisProviderId", saved.getAnalysisProviderId());
            result.put("warnings", warnings);
            result.put("analysisCoverage", saved.getAnalysisCoverage());
            result.put("analysisScope", saved.getAnalysisScope());
            result.put("analysisReasonLength", saved.getAnalysisReasonLength());
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
