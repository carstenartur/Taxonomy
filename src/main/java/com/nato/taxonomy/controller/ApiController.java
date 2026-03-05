package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.LlmCallDetail;
import com.nato.taxonomy.dto.AnalysisRequest;
import com.nato.taxonomy.dto.AnalysisResult;
import com.nato.taxonomy.dto.AiStatusResponse;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.service.AnalysisEventCallback;
import com.nato.taxonomy.service.LlmService;
import com.nato.taxonomy.service.PromptTemplateService;
import com.nato.taxonomy.service.SearchService;
import com.nato.taxonomy.service.TaxonomyService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
    private final ExecutorService analysisExecutor;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    public ApiController(TaxonomyService taxonomyService, LlmService llmService,
                         SearchService searchService, ExecutorService analysisExecutor,
                         ObjectMapper objectMapper, PromptTemplateService promptTemplateService) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
        this.searchService = searchService;
        this.analysisExecutor = analysisExecutor;
        this.objectMapper = objectMapper;
        this.promptTemplateService = promptTemplateService;
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
                    public void onScores(Map<String, Integer> newScores, String description) {
                        sendEvent(emitter, "scores",
                                Map.of("scores", newScores, "description", description));
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
        result.put("prompt", detail.getPrompt());
        result.put("rawResponse", detail.getRawResponse());
        result.put("provider", detail.getProvider());
        result.put("durationMs", detail.getDurationMs());
        result.put("error", detail.getError());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        return ResponseEntity.ok(llmService.getDiagnostics());
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
    public ResponseEntity<List<Map<String, Object>>> getAllPrompts() {
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
    public ResponseEntity<Map<String, Object>> getPrompt(@PathVariable String code) {
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
            @RequestBody Map<String, String> body) {
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
    public ResponseEntity<Map<String, Object>> resetPrompt(@PathVariable String code) {
        promptTemplateService.resetTemplate(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("overridden", false);
        return ResponseEntity.ok(result);
    }
}
