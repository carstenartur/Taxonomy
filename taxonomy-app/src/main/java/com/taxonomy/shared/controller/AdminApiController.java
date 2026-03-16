package com.taxonomy.shared.controller;

import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AiStatusResponse;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.shared.service.PromptTemplateService;
import com.taxonomy.catalog.service.TaxonomyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Administration")
public class AdminApiController {

    private final LlmService llmService;
    private final PromptTemplateService promptTemplateService;
    private final TaxonomyService taxonomyService;

    @Value("${admin.token:}")
    private String adminPassword;

    public AdminApiController(LlmService llmService,
                               PromptTemplateService promptTemplateService,
                               TaxonomyService taxonomyService) {
        this.llmService = llmService;
        this.promptTemplateService = promptTemplateService;
        this.taxonomyService = taxonomyService;
    }

    @Operation(summary = "Check AI availability", description = "Returns whether an LLM provider is available and which one is active", tags = {"Administration"})
    @GetMapping("/ai-status")
    public ResponseEntity<AiStatusResponse> aiStatus() {
        AiAvailabilityLevel level = llmService.getAvailabilityLevel();
        String provider = level != AiAvailabilityLevel.UNAVAILABLE
                ? llmService.getActiveProviderName() : null;
        List<String> availableProviders = llmService.getAvailableProviders();
        return ResponseEntity.ok(new AiStatusResponse(level, provider, availableProviders));
    }

    @Operation(summary = "Startup status", description = "Returns the initialization state of the taxonomy data. Poll this endpoint after receiving a 503 to know when the app is ready.", tags = {"Status"})
    @GetMapping("/status/startup")
    public ResponseEntity<Map<String, Object>> startupStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", taxonomyService.isInitialized());
        status.put("status", taxonomyService.getInitStatus());

        // Phase details from AppInitializationStateService
        com.taxonomy.shared.service.AppInitializationStateService stateService = taxonomyService.getStateService();
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

    @Operation(summary = "Get LLM diagnostics", description = "Returns diagnostic information about the LLM provider (admin-only)", tags = {"Administration"})
    @ApiResponse(responseCode = "401", description = "Not authorized — admin password required")
    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(llmService.getDiagnostics());
    }

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
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
