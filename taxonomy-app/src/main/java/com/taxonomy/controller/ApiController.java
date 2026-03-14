package com.taxonomy.controller;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AiStatusResponse;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.service.AnalysisEventCallback;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.service.HybridSearchService;
import com.taxonomy.service.LocalEmbeddingService;
import com.taxonomy.service.LlmProvider;
import com.taxonomy.service.LlmService;
import com.taxonomy.service.PromptTemplateService;
import com.taxonomy.service.RepositoryStateService;
import com.taxonomy.service.RequirementArchitectureViewService;
import com.taxonomy.service.AnalysisRelationGenerator;
import com.taxonomy.service.HypothesisService;
import com.taxonomy.service.SavedAnalysisService;
import com.taxonomy.service.SearchService;
import com.taxonomy.service.TaxonomyService;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.service.GraphSearchService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api")
@Tag(name = "Taxonomy")
public class ApiController {

    private final TaxonomyService taxonomyService;
    private final LlmService llmService;
    private final SearchService searchService;
    private final HybridSearchService hybridSearchService;
    private final LocalEmbeddingService embeddingService;
    private final GraphSearchService graphSearchService;
    private final ExecutorService analysisExecutor;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;
    private final RequirementArchitectureViewService architectureViewService;
    private final DiagramProjectionService diagramProjectionService;
    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;
    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;
    private final MermaidExportService mermaidExportService;
    private final SavedAnalysisService savedAnalysisService;
    private final AnalysisRelationGenerator analysisRelationGenerator;
    private final HypothesisService hypothesisService;
    private final RepositoryStateService repositoryStateService;

    @Value("${admin.token:}")
    private String adminPassword;

    public ApiController(TaxonomyService taxonomyService, LlmService llmService,
                         SearchService searchService, HybridSearchService hybridSearchService,
                         LocalEmbeddingService embeddingService,
                         GraphSearchService graphSearchService,
                         ExecutorService analysisExecutor,
                         ObjectMapper objectMapper, PromptTemplateService promptTemplateService,
                         RequirementArchitectureViewService architectureViewService,
                         DiagramProjectionService diagramProjectionService,
                         VisioDiagramService visioDiagramService,
                         VisioPackageBuilder visioPackageBuilder,
                         ArchiMateDiagramService archiMateDiagramService,
                         ArchiMateXmlExporter archiMateXmlExporter,
                         MermaidExportService mermaidExportService,
                         SavedAnalysisService savedAnalysisService,
                         AnalysisRelationGenerator analysisRelationGenerator,
                         HypothesisService hypothesisService,
                         RepositoryStateService repositoryStateService) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
        this.searchService = searchService;
        this.hybridSearchService = hybridSearchService;
        this.embeddingService = embeddingService;
        this.graphSearchService = graphSearchService;
        this.analysisExecutor = analysisExecutor;
        this.objectMapper = objectMapper;
        this.promptTemplateService = promptTemplateService;
        this.architectureViewService = architectureViewService;
        this.diagramProjectionService = diagramProjectionService;
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
        this.mermaidExportService = mermaidExportService;
        this.savedAnalysisService = savedAnalysisService;
        this.analysisRelationGenerator = analysisRelationGenerator;
        this.hypothesisService = hypothesisService;
        this.repositoryStateService = repositoryStateService;
    }

    @Operation(summary = "Get full taxonomy tree", description = "Returns the complete taxonomy hierarchy as a nested tree of nodes", tags = {"Taxonomy"})
    @ApiResponse(responseCode = "200", description = "Taxonomy tree returned successfully")
    @GetMapping("/taxonomy")
    public ResponseEntity<List<TaxonomyNodeDto>> getTaxonomy() {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(taxonomyService.getFullTree());
    }

    @Operation(summary = "Check AI availability", description = "Returns whether an LLM provider is available and which one is active", tags = {"Administration"})
    @GetMapping("/ai-status")
    public ResponseEntity<AiStatusResponse> aiStatus() {
        boolean available = llmService.isAvailable();
        String provider = available ? llmService.getActiveProviderName() : null;
        List<String> availableProviders = llmService.getAvailableProviders();
        return ResponseEntity.ok(new AiStatusResponse(available, provider, availableProviders));
    }

    @Operation(summary = "Startup status", description = "Returns the initialization state of the taxonomy data. Poll this endpoint after receiving a 503 to know when the app is ready.", tags = {"Status"})
    @GetMapping("/status/startup")
    public ResponseEntity<Map<String, Object>> startupStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", taxonomyService.isInitialized());
        status.put("status", taxonomyService.getInitStatus());

        // Phase details from AppInitializationStateService
        com.taxonomy.service.AppInitializationStateService stateService = taxonomyService.getStateService();
        status.put("phase", stateService.getState().name());
        status.put("phaseMessage", stateService.getMessage());
        status.put("phaseUpdatedAt", stateService.getUpdatedAt().toString());

        // Memory info
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("heapUsedMB", heapUsed / (1024 * 1024));
        memory.put("heapMaxMB", heapMax / (1024 * 1024));
        memory.put("heapUsagePercent", Math.round((double) heapUsed / heapMax * 100));
        memory.put("threadCount", Thread.activeCount());
        status.put("memory", memory);

        return ResponseEntity.ok(status);
    }

    /**
     * Returns a 503 response when the taxonomy is not yet initialized, or {@code null} when
     * the request may proceed normally.  Add {@code ResponseEntity<?> guard = checkInitialized(); if (guard != null) return guard;}
     * at the top of every data-dependent endpoint.
     */
    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!taxonomyService.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Taxonomy data is still loading. Please wait.");
            body.put("status", taxonomyService.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }

    @Operation(summary = "Analyze business requirement", description = "Analyzes a business requirement against the taxonomy using the configured LLM provider. Optionally includes an architecture view.", tags = {"Analysis"})
    @ApiResponse(responseCode = "200", description = "Analysis completed")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze(@RequestBody AnalysisRequest request) {
        ResponseEntity<AnalysisResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (request.getBusinessText() == null || request.getBusinessText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (request.getProvider() != null && !request.getProvider().isBlank()) {
            try {
                llmService.setRequestProvider(
                        LlmProvider.valueOf(request.getProvider().toUpperCase()));
            } catch (IllegalArgumentException e) {
                @SuppressWarnings("unchecked")
                ResponseEntity<AnalysisResult> badProvider = (ResponseEntity<AnalysisResult>)
                        (ResponseEntity<?>) ResponseEntity.badRequest().body(Map.of(
                                "error", "Unknown provider: " + request.getProvider(),
                                "validProviders", java.util.Arrays.toString(LlmProvider.values())));
                return badProvider;
            }
        }
        try {
            AnalysisResult result = llmService.analyzeWithBudget(request.getBusinessText());

            // Generate provisional relation hypotheses from scored nodes
            if (result.getScores() != null) {
                result.setProvisionalRelations(
                        analysisRelationGenerator.generate(result.getScores()));

                // Persist hypotheses to database for later accept/reject via API
                if (!result.getProvisionalRelations().isEmpty()) {
                    hypothesisService.persistFromAnalysis(result.getProvisionalRelations(), null);
                }
            }

            if (request.isIncludeArchitectureView() && result.getScores() != null) {
                result.setArchitectureView(
                        architectureViewService.build(result.getScores(), request.getBusinessText(),
                                request.getMaxArchitectureNodes(),
                                result.getProvisionalRelations()));
            }

            result.setViewContext(repositoryStateService.getViewContext("draft"));

            return ResponseEntity.ok(result);
        } finally {
            llmService.clearRequestProvider();
        }
    }

    /**
     * Streaming analysis endpoint using Server-Sent Events (SSE).
     * Emits {@code phase}, {@code scores}, {@code expanding}, {@code complete} and
     * {@code error} events as the LLM processes the taxonomy level by level.
     */
    @Operation(summary = "Streaming analysis (SSE)", description = "Server-Sent Events streaming analysis. Emits phase, scores, expanding, complete, and error events as the LLM processes the taxonomy level by level.", tags = {"Analysis"})
    @GetMapping(value = "/analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(
            @Parameter(description = "Business requirement text to analyze") @RequestParam String businessText,
            @Parameter(description = "LLM provider override (e.g. GEMINI, LOCAL_ONNX)") @RequestParam(required = false) String provider) {
        SseEmitter emitter = new SseEmitter(120_000L);

        if (!taxonomyService.isInitialized()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "status", "ERROR",
                                "errorMessage", "Taxonomy data is still loading. Please wait.",
                                "initStatus", taxonomyService.getInitStatus()))));
            } catch (Exception ignored) {
                // client already disconnected
            }
            emitter.complete();
            return emitter;
        }

        if (businessText == null || businessText.isBlank()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"status\":\"ERROR\",\"errorMessage\":\"businessText must not be blank\"}"));
            } catch (IOException ignored) {
                // client already disconnected
            }
            emitter.complete();
            return emitter;
        }

        LlmProvider resolvedProvider = null;
        if (provider != null && !provider.isBlank()) {
            try {
                resolvedProvider = LlmProvider.valueOf(provider.toUpperCase());
            } catch (IllegalArgumentException e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(objectMapper.writeValueAsString(Map.of(
                                    "status", "ERROR",
                                    "errorMessage", "Unknown provider: " + provider))));
                } catch (Exception ignored) {
                    // client already disconnected
                }
                emitter.complete();
                return emitter;
            }
        }

        final LlmProvider providerOverride = resolvedProvider;
        analysisExecutor.execute(() -> {
            if (providerOverride != null) {
                llmService.setRequestProvider(providerOverride);
            }
            try {
                llmService.analyzeStreaming(businessText, new AnalysisEventCallback() {

                    @Override
                    public void onPhase(String message, int progressPercent) {
                        sendEvent(emitter, "phase",
                                Map.of("message", message, "progress", progressPercent));
                    }

                    @Override
                    public void onScores(Map<String, Integer> newScores, Map<String, String> reasons,
                                         String description) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("scores", newScores);
                        payload.put("reasons", reasons != null ? reasons : Map.of());
                        payload.put("description", description);
                        sendEvent(emitter, "scores", payload);
                    }

                    @Override
                    public void onExpanding(String parentCode, List<String> childCodes) {
                        sendEvent(emitter, "expanding",
                                Map.of("parentCode", parentCode, "childCodes", childCodes));
                    }

                    @Override
                    public void onComplete(String status, Map<String, Integer> allScores,
                                           List<String> warnings,
                                           List<TaxonomyDiscrepancy> discrepancies) {
                        int matched = (int) allScores.values().stream()
                                .filter(v -> v > 0).count();
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("status", status);
                        payload.put("totalScores", allScores);
                        payload.put("totalMatched", matched);
                        payload.put("warnings", warnings);
                        payload.put("discrepancies", discrepancies);
                        sendEvent(emitter, "complete", payload);
                        emitter.complete();
                    }

                    @Override
                    public void onError(String status, String errorMessage,
                                        Map<String, Integer> partialScores,
                                        List<String> warnings,
                                        List<TaxonomyDiscrepancy> discrepancies) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("status", status);
                        payload.put("errorMessage", errorMessage);
                        payload.put("partialScores", partialScores);
                        payload.put("warnings", warnings);
                        payload.put("discrepancies", discrepancies);
                        sendEvent(emitter, "error", payload);
                        emitter.complete();
                    }
                });
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                llmService.clearRequestProvider();
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            // Client disconnected — complete silently
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    @Operation(summary = "Analyze single node children", description = "Analyzes the children of a specific taxonomy node against a business requirement", tags = {"Analysis"})
    @GetMapping("/analyze-node")
    public ResponseEntity<Map<String, Object>> analyzeNode(
            @Parameter(description = "Parent taxonomy node code") @RequestParam String parentCode,
            @Parameter(description = "Business requirement text") @RequestParam String businessText,
            @Parameter(description = "Parent node's score (0-100); defaults to 100 for root-level nodes") @RequestParam(defaultValue = "100") int parentScore) {
        ResponseEntity<Map<String, Object>> guard = checkInitialized();
        if (guard != null) return guard;
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<TaxonomyNode> children = taxonomyService.getChildrenOf(parentCode);
        if (children.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("scores", Map.of());
            empty.put("prompt", "");
            empty.put("rawResponse", "");
            empty.put("provider", "");
            empty.put("durationMs", 0);
            return ResponseEntity.ok(empty);
        }
        LlmCallDetail detail = llmService.analyzeSingleBatchDetailed(businessText, children, parentScore);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scores", detail.getScores());
        result.put("reasons", detail.getReasons() != null ? detail.getReasons() : Map.of());
        result.put("prompt", detail.getPrompt());
        result.put("rawResponse", detail.getRawResponse());
        result.put("provider", detail.getProvider());
        result.put("durationMs", detail.getDurationMs());
        result.put("error", detail.getError());
        return ResponseEntity.ok(result);
    }

    /**
     * Generates a leaf-node justification on demand.
     * Collects the path from root to the leaf node and cross-references to other
     * high-scoring nodes, then calls the LLM for a coherent summary.
     */
    @Operation(summary = "Generate leaf justification", description = "Generates an explanatory justification for a leaf node match using the LLM", tags = {"Analysis"})
    @PostMapping("/justify-leaf")
    public ResponseEntity<Map<String, Object>> justifyLeaf(
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> guard = checkInitialized();
        if (guard != null) return guard;
        String nodeCode = (String) body.get("nodeCode");
        String businessText = (String) body.get("businessText");
        if (nodeCode == null || nodeCode.isBlank() || businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawScores = body.get("scores") instanceof Map<?, ?>
                    ? (Map<String, Object>) body.get("scores") : Map.of();
            @SuppressWarnings("unchecked")
            Map<String, String> allReasons = body.get("reasons") instanceof Map<?, ?>
                    ? (Map<String, String>) body.get("reasons") : Map.of();

            Map<String, Integer> allScores = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rawScores.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    allScores.put(e.getKey(), n.intValue());
                }
            }

            List<com.taxonomy.model.TaxonomyNode> pathNodes =
                    taxonomyService.getPathToRoot(nodeCode);

            String justification = llmService.generateLeafJustification(
                    businessText, nodeCode, pathNodes, allScores, allReasons);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeCode", nodeCode);
            result.put("justification", justification);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeCode", nodeCode);
            result.put("justification", "Error: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    @Operation(summary = "Get LLM diagnostics", description = "Returns diagnostic information about the LLM provider (admin-only)", tags = {"Administration"})
    @ApiResponse(responseCode = "401", description = "Not authorized — admin password required")
    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(llmService.getDiagnostics());
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @Operation(summary = "Check admin status", description = "Returns whether the admin password is required", tags = {"Administration"})
    @GetMapping("/admin/status")
    public ResponseEntity<Map<String, Boolean>> adminStatus() {
        boolean required = adminPassword != null && !adminPassword.isBlank();
        return ResponseEntity.ok(Map.of("passwordRequired", required));
    }

    @Operation(summary = "Verify admin password", description = "Validates the admin password", tags = {"Administration"})
    @PostMapping("/admin/verify")
    public ResponseEntity<Map<String, Boolean>> verifyAdmin(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        boolean valid = adminPassword != null && !adminPassword.isBlank()
                && constantTimeEquals(adminPassword, password);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    // ── Visio Diagram Export ──────────────────────────────────────────────────

    /**
     * Generates a Visio .vsdx architecture diagram from a business text requirement.
     * The requirement is first analyzed, then an architecture view is built, projected
     * into a diagram model, and exported as a .vsdx file.
     */
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
            // 1. Analyze
            AnalysisResult result = llmService.analyzeWithBudget(businessText);

            // 2. Build architecture view
            RequirementArchitectureView view = architectureViewService.build(
                    result.getScores(), businessText, 20);

            // 3. Project to neutral diagram model
            String title = businessText.length() > 60
                    ? businessText.substring(0, 57) + "..."
                    : businessText;
            DiagramModel diagram = diagramProjectionService.project(view, title);

            // 4. Convert to Visio document model
            VisioDocument visioDoc = visioDiagramService.convert(diagram);

            // 5. Package as .vsdx
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

    /**
     * Generates an ArchiMate Model Exchange File Format XML from a business text requirement.
     * The requirement is first analyzed, then an architecture view is built, projected
     * into a diagram model, and exported as an ArchiMate 3.x XML file.
     */
    @Operation(summary = "Export ArchiMate XML", description = "Generates an ArchiMate Model Exchange File Format XML from a business requirement", tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "ArchiMate XML returned as attachment")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/diagram/archimate")
    public ResponseEntity<byte[]> exportArchiMate(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // 1. Analyze
        AnalysisResult result = llmService.analyzeWithBudget(businessText);

        // 2. Build architecture view
        RequirementArchitectureView view = architectureViewService.build(
                result.getScores(), businessText, 20);

        // 3. Project to neutral diagram model
        String title = businessText.length() > 60
                ? businessText.substring(0, 57) + "..."
                : businessText;
        DiagramModel diagram = diagramProjectionService.project(view, title);

        // 4. Convert to ArchiMate model
        ArchiMateModel archiMateModel = archiMateDiagramService.convert(diagram);

        // 5. Export as XML
        byte[] xml = archiMateXmlExporter.export(archiMateModel);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"requirement-architecture.xml\"");
        headers.set(HttpHeaders.CONTENT_TYPE, "application/xml");

        return ResponseEntity.ok().headers(headers).body(xml);
    }

    // ── Mermaid Diagram Export ────────────────────────────────────────────────

    /**
     * Generates a Mermaid flowchart from a business text requirement.
     * The requirement is first analyzed, then an architecture view is built, projected
     * into a diagram model, and exported as Mermaid markdown text.
     */
    @Operation(summary = "Export Mermaid diagram", description = "Generates a Mermaid flowchart from a business requirement for use in Markdown documents", tags = {"Export"})
    @ApiResponse(responseCode = "200", description = "Mermaid text returned")
    @ApiResponse(responseCode = "400", description = "Business text is blank or missing")
    @PostMapping("/diagram/mermaid")
    public ResponseEntity<String> exportMermaid(@RequestBody Map<String, Object> body) {
        String businessText = (String) body.get("businessText");
        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // 1. Analyze
        AnalysisResult result = llmService.analyzeWithBudget(businessText);

        // 2. Build architecture view
        RequirementArchitectureView view = architectureViewService.build(
                result.getScores(), businessText, 20);

        // 3. Project to neutral diagram model
        String title = businessText.length() > 60
                ? businessText.substring(0, 57) + "..."
                : businessText;
        DiagramModel diagram = diagramProjectionService.project(view, title);

        // 4. Convert to Mermaid text
        String mermaid = mermaidExportService.export(diagram);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .body(mermaid);
    }

    @Operation(summary = "Full-text search", description = "Search taxonomy nodes using full-text Lucene search", tags = {"Search"})
    @GetMapping("/search")
    public ResponseEntity<List<TaxonomyNodeDto>> search(
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "50") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchService.search(q, maxResults));
    }

    // ── Prompt template endpoints ──────────────────────────────────────────────

    @Operation(summary = "List all prompt templates", description = "Returns all prompt templates (admin-only)", tags = {"Administration"})
    @GetMapping("/prompts")
    public ResponseEntity<List<Map<String, Object>>> getAllPrompts(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String code : promptTemplateService.getAllTemplateCodes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", code);
            entry.put("name", promptTemplateService.getTaxonomyName(code));
            entry.put("template", promptTemplateService.getTemplate(code));
            entry.put("overridden", promptTemplateService.isOverridden(code));
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get prompt template", description = "Returns a specific prompt template by code (admin-only)", tags = {"Administration"})
    @GetMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> getPrompt(@Parameter(description = "Template code") @PathVariable String code,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", promptTemplateService.getTaxonomyName(code));
        result.put("template", promptTemplateService.getTemplate(code));
        result.put("defaultTemplate", promptTemplateService.getDefaultTemplate(code));
        result.put("overridden", promptTemplateService.isOverridden(code));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Update prompt template", description = "Overrides a prompt template (admin-only)", tags = {"Administration"})
    @PutMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> savePrompt(
            @PathVariable String code,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String template = body.get("template");
        if (template == null) {
            return ResponseEntity.badRequest().build();
        }
        promptTemplateService.setTemplate(code, template);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("overridden", true);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Reset prompt template", description = "Resets a prompt template to its default (admin-only)", tags = {"Administration"})
    @DeleteMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> resetPrompt(@PathVariable String code,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        promptTemplateService.resetTemplate(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("overridden", false);
        return ResponseEntity.ok(result);
    }

    // ── Semantic / Hybrid / Similar search endpoints ───────────────────────────

    /**
     * Semantic search across the full taxonomy using embedding similarity.
     * Returns nodes ranked by cosine similarity to {@code q}.
     * Requires {@code LLM_PROVIDER=LOCAL_ONNX} or {@code TAXONOMY_EMBEDDING_ENABLED=true}.
     *
     * @param q          natural-language query (e.g. "satellite communications")
     * @param maxResults maximum number of results (default 20)
     */
    @Operation(summary = "Semantic search", description = "Search taxonomy nodes using embedding similarity (KNN). Requires LOCAL_ONNX or embedding enabled.", tags = {"Search"})
    @GetMapping("/search/semantic")
    public ResponseEntity<List<TaxonomyNodeDto>> semanticSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(embeddingService.semanticSearch(q, maxResults));
    }

    /**
     * Hybrid search: combines full-text Lucene and semantic KNN results via
     * Reciprocal Rank Fusion.  Falls back to full-text only when embedding is unavailable.
     *
     * @param q          natural-language query
     * @param maxResults maximum number of results (default 20)
     */
    @Operation(summary = "Hybrid search", description = "Combines full-text Lucene and semantic KNN results via Reciprocal Rank Fusion. Falls back to full-text only when embedding is unavailable.", tags = {"Search"})
    @GetMapping("/search/hybrid")
    public ResponseEntity<List<TaxonomyNodeDto>> hybridSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(hybridSearchService.hybridSearch(q, maxResults));
    }

    /**
     * Find taxonomy nodes semantically similar to the node identified by {@code code}.
     *
     * @param code   taxonomy node code (e.g. "BP.001")
     * @param topK   maximum number of similar nodes (default 10)
     */
    @Operation(summary = "Find similar nodes", description = "Find taxonomy nodes semantically similar to a given node", tags = {"Search"})
    @GetMapping("/search/similar/{code}")
    public ResponseEntity<List<TaxonomyNodeDto>> findSimilar(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum number of similar nodes") @RequestParam(defaultValue = "10") int topK) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(embeddingService.findSimilarNodes(code, topK));
    }

    /**
     * Returns the current status of the local embedding model.
     *
     * <p>Response fields:
     * <ul>
     *   <li>{@code enabled} — whether embedding is globally enabled</li>
     *   <li>{@code available} — whether the model loaded successfully</li>
     *   <li>{@code modelUrl} — the DJL model URL in use</li>
     *   <li>{@code indexedNodes} — number of nodes currently in the vector index
     *       (0 = not yet built)</li>
     * </ul>
     */
    @Operation(summary = "Embedding model status", description = "Returns the current status of the local embedding model", tags = {"Embedding"})
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled",      embeddingService.isEnabled());
        status.put("available",    embeddingService.isAvailable());
        status.put("modelUrl",     embeddingService.effectiveModelUrl());
        status.put("indexedNodes", embeddingService.indexedNodeCount());
        return ResponseEntity.ok(status);
    }

    /**
     * Graph-semantic search: combines node and relation KNN queries to answer
     * graph-structural questions.
     *
     * <p>Returns:
     * <ul>
     *   <li>Matched nodes ranked by semantic similarity.</li>
     *   <li>Per-root relation counts (graph statistics).</li>
     *   <li>Most common relation types.</li>
     *   <li>A human-readable summary.</li>
     * </ul>
     *
     * @param q          natural-language query (e.g. "which Business Processes are most supported?")
     * @param maxResults maximum number of node results (default 20)
     */
    @Operation(summary = "Graph-semantic search", description = "Combines node and relation KNN queries to answer graph-structural questions. Returns matched nodes, per-root relation counts, top relation types, and a summary.", tags = {"Search"})
    @GetMapping("/search/graph")
    public ResponseEntity<GraphSearchResult> graphSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of node results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<GraphSearchResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(graphSearchService.graphSearch(q, maxResults));
    }

    // ── Scores import / export endpoints ────────────────────────────────────────

    /**
     * Exports the current analysis result as a {@link SavedAnalysis} JSON.
     *
     * <p>Request body must contain:
     * <ul>
     *   <li>{@code requirement} — business requirement text</li>
     *   <li>{@code scores} — map of node code → score</li>
     *   <li>{@code reasons} — map of node code → reason text (optional)</li>
     *   <li>{@code provider} — LLM provider name (optional, informational)</li>
     * </ul>
     */
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

    /**
     * Imports a {@link SavedAnalysis} JSON, validates it, and returns the scores and reasons
     * so the frontend can apply them to the tree.
     *
     * <p>The response includes any warnings about unknown node codes.
     */
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

    // ── Admin authorization helper ────────────────────────────────────────────
    /**
     * Returns {@code true} if the request is authorized to access admin-only endpoints.
     * Authorization is granted when no admin password is configured (backward compatible),
     * or when the {@code X-Admin-Token} header matches the configured password.
     */
    private boolean isAdminAuthorized(HttpServletRequest request) {
        if (adminPassword == null || adminPassword.isBlank()) {
            return true;
        }
        String token = request.getHeader("X-Admin-Token");
        return constantTimeEquals(adminPassword, token);
    }

    /**
     * Compares two strings using a constant-time algorithm to mitigate timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
