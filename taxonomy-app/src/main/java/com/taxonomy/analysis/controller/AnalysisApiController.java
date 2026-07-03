package com.taxonomy.analysis.controller;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisCommand;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase;
import com.taxonomy.analysis.usecase.UnknownAnalysisProviderException;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api")
@Tag(name = "Analysis")
public class AnalysisApiController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisApiController.class);

    private final TaxonomyService taxonomyService;
    private final LlmService llmService;
    private final ExecutorService analysisExecutor;
    private final ObjectMapper objectMapper;
    private final AnalyzeRequirementUseCase analyzeRequirementUseCase;
    private final StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase;
    private final AnalysisSseEventMapper analysisSseEventMapper;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;
    private final org.springframework.context.MessageSource messageSource;

    public AnalysisApiController(TaxonomyService taxonomyService,
                                  LlmService llmService,
                                  ExecutorService analysisExecutor,
                                  ObjectMapper objectMapper,
                                  AnalyzeRequirementUseCase analyzeRequirementUseCase,
                                  StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase,
                                  AnalysisSseEventMapper analysisSseEventMapper,
                                  RepositoryStateService repositoryStateService,
                                  WorkspaceResolver workspaceResolver,
                                  org.springframework.context.MessageSource messageSource) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
        this.analysisExecutor = analysisExecutor;
        this.objectMapper = objectMapper;
        this.analyzeRequirementUseCase = analyzeRequirementUseCase;
        this.streamRequirementAnalysisUseCase = streamRequirementAnalysisUseCase;
        this.analysisSseEventMapper = analysisSseEventMapper;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.messageSource = messageSource;
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
        try {
            String username = workspaceResolver.resolveCurrentUsername();
            AnalyzeRequirementResult result = analyzeRequirementUseCase.analyze(
                    new AnalyzeRequirementCommand(
                            request.getBusinessText(),
                            request.isIncludeArchitectureView(),
                            request.getMaxArchitectureNodes(),
                            request.getProvider(),
                            username,
                            resolveWorkspaceContext(username)));
            return ResponseEntity.ok(result.analysisResult());
        } catch (UnknownAnalysisProviderException e) {
            @SuppressWarnings("unchecked")
            ResponseEntity<AnalysisResult> badProvider = (ResponseEntity<AnalysisResult>)
                    (ResponseEntity<?>) ResponseEntity.badRequest().body(Map.of(
                            "error", "Unknown provider: " + e.getProvider(),
                            "validProviders", e.getValidProviders()));
            return badProvider;
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
                                "errorMessage", messageSource.getMessage("error.loading", null,
                                        "Taxonomy data is still loading. Please wait.",
                                        org.springframework.context.i18n.LocaleContextHolder.getLocale()),
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

        StreamRequirementAnalysisCommand command = new StreamRequirementAnalysisCommand(
                businessText,
                provider,
                LocaleContextHolder.getLocale());

        analysisExecutor.execute(() -> {
            try {
                streamRequirementAnalysisUseCase.stream(command, event -> {
                    AnalysisSseEventMapper.MappedEvent mappedEvent = analysisSseEventMapper.map(event);
                    sendEvent(emitter, mappedEvent.name(), mappedEvent.payload());
                    if (event instanceof AnalysisStreamEvent.Complete || event instanceof AnalysisStreamEvent.Error) {
                        emitter.complete();
                    }
                });
            } catch (UnknownAnalysisProviderException e) {
                sendEvent(emitter, "error", Map.of(
                        "status", "ERROR",
                        "errorMessage", "Unknown provider: " + e.getProvider()));
                emitter.complete();
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

    private WorkspaceContext resolveWorkspaceContext(String username) {
        try {
            repositoryStateService.ensureWorkspaceState(username);
            return workspaceResolver.resolveCurrentContext();
        } catch (Exception e) {
            log.warn("Falling back to shared workspace context for user '{}' due to: {}",
                    username, e.toString(), e);
            return WorkspaceContext.SHARED;
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

            List<com.taxonomy.catalog.model.TaxonomyNode> pathNodes =
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

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!taxonomyService.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", messageSource.getMessage("error.loading", null,
                    "Taxonomy data is still loading. Please wait.",
                    org.springframework.context.i18n.LocaleContextHolder.getLocale()));
            body.put("status", taxonomyService.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }
}
