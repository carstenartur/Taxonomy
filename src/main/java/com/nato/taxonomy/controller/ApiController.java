package com.nato.taxonomy.controller;

import com.nato.taxonomy.diagram.DiagramModel;
import com.nato.taxonomy.dto.LlmCallDetail;
import com.nato.taxonomy.dto.AnalysisRequest;
import com.nato.taxonomy.dto.AnalysisResult;
import com.nato.taxonomy.dto.AiStatusResponse;
import com.nato.taxonomy.dto.RequirementArchitectureView;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.service.AnalysisEventCallback;
import com.nato.taxonomy.service.DiagramProjectionService;
import com.nato.taxonomy.service.HybridSearchService;
import com.nato.taxonomy.service.LocalEmbeddingService;
import com.nato.taxonomy.service.LlmService;
import com.nato.taxonomy.service.PromptTemplateService;
import com.nato.taxonomy.service.RequirementArchitectureViewService;
import com.nato.taxonomy.service.SearchService;
import com.nato.taxonomy.service.TaxonomyService;
import com.nato.taxonomy.archimate.ArchiMateModel;
import com.nato.taxonomy.service.ArchiMateDiagramService;
import com.nato.taxonomy.service.ArchiMateXmlExporter;
import com.nato.taxonomy.service.VisioDiagramService;
import com.nato.taxonomy.service.VisioPackageBuilder;
import com.nato.taxonomy.visio.VisioDocument;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
public class ApiController {

    private final TaxonomyService taxonomyService;
    private final LlmService llmService;
    private final SearchService searchService;
    private final HybridSearchService hybridSearchService;
    private final LocalEmbeddingService embeddingService;
    private final ExecutorService analysisExecutor;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;
    private final RequirementArchitectureViewService architectureViewService;
    private final DiagramProjectionService diagramProjectionService;
    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;
    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;

    @Value("${admin.password:}")
    private String adminPassword;

    public ApiController(TaxonomyService taxonomyService, LlmService llmService,
                         SearchService searchService, HybridSearchService hybridSearchService,
                         LocalEmbeddingService embeddingService,
                         ExecutorService analysisExecutor,
                         ObjectMapper objectMapper, PromptTemplateService promptTemplateService,
                         RequirementArchitectureViewService architectureViewService,
                         DiagramProjectionService diagramProjectionService,
                         VisioDiagramService visioDiagramService,
                         VisioPackageBuilder visioPackageBuilder,
                         ArchiMateDiagramService archiMateDiagramService,
                         ArchiMateXmlExporter archiMateXmlExporter) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
        this.searchService = searchService;
        this.hybridSearchService = hybridSearchService;
        this.embeddingService = embeddingService;
        this.analysisExecutor = analysisExecutor;
        this.objectMapper = objectMapper;
        this.promptTemplateService = promptTemplateService;
        this.architectureViewService = architectureViewService;
        this.diagramProjectionService = diagramProjectionService;
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
    }

    @GetMapping("/taxonomy")
    public ResponseEntity<List<TaxonomyNodeDto>> getTaxonomy() {
        return ResponseEntity.ok(taxonomyService.getFullTree());
    }

    @GetMapping("/ai-status")
    public ResponseEntity<AiStatusResponse> aiStatus() {
        boolean available = llmService.isAvailable();
        String provider = available ? llmService.getActiveProviderName() : null;
        return ResponseEntity.ok(new AiStatusResponse(available, provider));
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze(@RequestBody AnalysisRequest request) {
        if (request.getBusinessText() == null || request.getBusinessText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        AnalysisResult result = llmService.analyzeWithBudget(request.getBusinessText());

        if (request.isIncludeArchitectureView() && result.getScores() != null) {
            result.setArchitectureView(
                    architectureViewService.build(result.getScores(), request.getBusinessText(),
                            request.getMaxArchitectureNodes()));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Streaming analysis endpoint using Server-Sent Events (SSE).
     * Emits {@code phase}, {@code scores}, {@code expanding}, {@code complete} and
     * {@code error} events as the LLM processes the taxonomy level by level.
     */
    @GetMapping(value = "/analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestParam String businessText) {
        SseEmitter emitter = new SseEmitter(120_000L);

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

        analysisExecutor.execute(() -> {
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
                                           List<String> warnings) {
                        int matched = (int) allScores.values().stream()
                                .filter(v -> v > 0).count();
                        sendEvent(emitter, "complete",
                                Map.of("status", status,
                                       "totalScores", allScores,
                                       "totalMatched", matched,
                                       "warnings", warnings));
                        emitter.complete();
                    }

                    @Override
                    public void onError(String status, String errorMessage,
                                        Map<String, Integer> partialScores,
                                        List<String> warnings) {
                        sendEvent(emitter, "error",
                                Map.of("status", status,
                                       "errorMessage", errorMessage,
                                       "partialScores", partialScores,
                                       "warnings", warnings));
                        emitter.complete();
                    }
                });
            } catch (Exception e) {
                emitter.completeWithError(e);
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

    @GetMapping("/analyze-node")
    public ResponseEntity<Map<String, Object>> analyzeNode(
            @RequestParam String parentCode,
            @RequestParam String businessText) {
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
        LlmCallDetail detail = llmService.analyzeSingleBatchDetailed(businessText, children);
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
    @PostMapping("/justify-leaf")
    public ResponseEntity<Map<String, Object>> justifyLeaf(
            @RequestBody Map<String, Object> body) {
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

            List<com.nato.taxonomy.model.TaxonomyNode> pathNodes =
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

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(llmService.getDiagnostics());
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @GetMapping("/admin/status")
    public ResponseEntity<Map<String, Boolean>> adminStatus() {
        boolean required = adminPassword != null && !adminPassword.isBlank();
        return ResponseEntity.ok(Map.of("passwordRequired", required));
    }

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

    @GetMapping("/search")
    public ResponseEntity<List<TaxonomyNodeDto>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int maxResults) {
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchService.search(q, maxResults));
    }

    // ── Prompt template endpoints ──────────────────────────────────────────────

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

    @GetMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> getPrompt(@PathVariable String code,
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
     * Requires {@code LLM_PROVIDER=LOCAL_ONNX} or {@code JGIT_EMBEDDING_ENABLED=true}.
     *
     * @param q          natural-language query (e.g. "satellite communications")
     * @param maxResults maximum number of results (default 20)
     */
    @GetMapping("/search/semantic")
    public ResponseEntity<List<TaxonomyNodeDto>> semanticSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
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
    @GetMapping("/search/hybrid")
    public ResponseEntity<List<TaxonomyNodeDto>> hybridSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(hybridSearchService.hybridSearch(q, maxResults));
    }

    /**
     * Find taxonomy nodes semantically similar to the node identified by {@code code}.
     *
     * @param code   taxonomy node code (e.g. "BP.001")
     * @param topK   maximum number of similar nodes (default 10)
     */
    @GetMapping("/search/similar/{code}")
    public ResponseEntity<List<TaxonomyNodeDto>> findSimilar(
            @PathVariable String code,
            @RequestParam(defaultValue = "10") int topK) {
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
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled",      embeddingService.isEnabled());
        status.put("available",    embeddingService.isAvailable());
        status.put("modelUrl",     embeddingService.effectiveModelUrl());
        status.put("indexedNodes", embeddingService.indexedNodeCount());
        return ResponseEntity.ok(status);
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
