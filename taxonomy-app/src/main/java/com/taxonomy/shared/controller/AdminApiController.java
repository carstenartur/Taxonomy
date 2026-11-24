package com.taxonomy.shared.controller;

import com.taxonomy.extension.api.llm.ProviderId;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.shared.service.AiStatusSource;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AiStatusResponse;
import com.taxonomy.shared.service.HealthSummaryService;
import com.taxonomy.shared.service.LogRingBufferService;
import com.taxonomy.analysis.service.PromptTemplateOverrideStore;
import com.taxonomy.analysis.service.PromptTemplateService;
import com.taxonomy.analysis.service.PromptTemplateService.PromptCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@com.taxonomy.shared.features.ConditionalOnFeature({"analysis"})
@RestController
@RequestMapping("/api")
@Tag(name = "Administration")
public class AdminApiController {

    private final LlmService llmService;
    private final LlmProviderConfig llmProviderConfig;
    private final PromptTemplateService promptTemplateService;
    private final AiStatusSource aiStatusSource;

    public AdminApiController(LlmService llmService, LlmProviderConfig llmProviderConfig,
                              PromptTemplateService promptTemplateService,
                              AiStatusSource aiStatusSource) {
        this.llmService = llmService;
        this.llmProviderConfig = llmProviderConfig;
        this.promptTemplateService = promptTemplateService;
        this.aiStatusSource = aiStatusSource;
    }

    @Operation(summary = "Check AI availability",
            description = "Returns whether an LLM provider is available and which one is active",
            tags = {"Administration"})
    @GetMapping("/ai-status")
    public ResponseEntity<AiStatusResponse> aiStatus(@RequestParam(value = "provider", required = false) String provider) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(aiStatusSource.snapshot(provider));
    }

    @Operation(summary = "Get LLM diagnostics",
            description = "Returns diagnostic information about the LLM provider (ROLE_ADMIN only)",
            tags = {"Administration"})
    @ApiResponse(responseCode = "403", description = "ROLE_ADMIN required")
    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>(llmService.getDiagnostics());
        ProviderId provider = llmService.getActiveProviderId();
        diagnostics.put("providerConfigured", llmProviderConfig.isProviderConfigured(provider));

        if (provider.equals(LlmProvider.CUSTOM_OPENAI.id())
                && !llmProviderConfig.hasConfiguredApiKey(provider)) {
            // The custom endpoint is intentionally usable without authentication. Do not expose
            // the internal transport marker as though it were a configured operator secret.
            diagnostics.put("apiKeyConfigured", false);
            diagnostics.put("apiKeyPrefix", null);
            diagnostics.put("authenticationMode", "NONE");
        } else if (llmProviderConfig.hasConfiguredApiKey(provider)) {
            diagnostics.put("authenticationMode", "BEARER");
        } else {
            diagnostics.put("authenticationMode", "NOT_APPLICABLE");
        }

        return ResponseEntity.ok(diagnostics);
    }

    @Operation(summary = "List all prompt templates",
            description = "Returns all prompt templates (ROLE_ADMIN only)",
            tags = {"Administration"})
    @GetMapping("/prompts")
    public ResponseEntity<List<Map<String, Object>>> getAllPrompts(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(editablePrompts());
    }

    @Operation(summary = "Get prompt template",
            description = "Returns a specific prompt template by code (ROLE_ADMIN only)",
            tags = {"Administration"})
    @GetMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> getPrompt(
            @Parameter(description = "Template code") @PathVariable String code,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, String> overrides = promptTemplateService.overrideSnapshot();
        String defaultTemplate = promptTemplateService.getDefaultTemplate(code);
        Map<String, Object> result = promptEntry(code, overrides.getOrDefault(code, defaultTemplate), overrides.containsKey(code));
        result.put("defaultTemplate", defaultTemplate);
        result.put("editable", promptTemplateService.isEditable(code));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Update prompt template",
            description = "Overrides a prompt template (ROLE_ADMIN only)",
            tags = {"Administration"})
    @PutMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> savePrompt(
            @PathVariable String code,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String template = body.get("template");
        if (template == null || template.isBlank() || !promptTemplateService.isEditable(code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "A nonblank, editable prompt template is required"));
        }
        promptTemplateService.setTemplate(code, template);
        return ResponseEntity.ok(Map.of("code", code, "overridden", true));
    }

    @Operation(summary = "Reset prompt template",
            description = "Resets a prompt template to its default (ROLE_ADMIN only)",
            tags = {"Administration"})
    @DeleteMapping("/prompts/{code}")
    public ResponseEntity<Map<String, Object>> resetPrompt(
            @PathVariable String code,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!promptTemplateService.isEditable(code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Historical prompt templates cannot be edited"));
        }
        promptTemplateService.resetTemplate(code);
        return ResponseEntity.ok(Map.of("code", code, "overridden", false));
    }

    @Operation(summary = "List prompt templates by category",
            description = "Returns all prompt templates grouped by category (ROLE_ADMIN only)",
            tags = {"Administration"})
    @GetMapping("/prompts/categories")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> getPromptsByCategory(
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Map<String, List<Map<String, Object>>> categorized = new LinkedHashMap<>();
        for (PromptCategory category : PromptCategory.values()) {
            categorized.put(category.name(), new ArrayList<>());
        }
        for (Map<String, Object> entry : editablePrompts()) {
            var category = promptTemplateService.categorizeCode((String) entry.get("code"));
            categorized.get(category.name()).add(entry);
        }
        return ResponseEntity.ok(categorized);
    }

    @ExceptionHandler(PromptTemplateOverrideStore.UnavailableException.class)
    public ResponseEntity<Map<String, String>> promptStoreUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Prompt template storage is unavailable"));
    }

    private List<Map<String, Object>> editablePrompts() {
        var overrides = promptTemplateService.overrideSnapshot();
        return promptTemplateService.effectiveTemplates(overrides).entrySet().stream()
                .filter(entry -> promptTemplateService.isEditable(entry.getKey()))
                .map(entry -> promptEntry(entry.getKey(), entry.getValue(), overrides.containsKey(entry.getKey())))
                .toList();
    }

    private Map<String, Object> promptEntry(String code, String template, boolean overridden) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", code);
        entry.put("name", promptTemplateService.getTaxonomyName(code));
        entry.put("template", template);
        entry.put("overridden", overridden);
        return entry;
    }

    private boolean isAdminAuthorized(HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("ROLE_ADMIN");
    }
}
