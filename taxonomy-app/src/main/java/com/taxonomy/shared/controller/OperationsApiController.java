package com.taxonomy.shared.controller;

import com.taxonomy.extension.api.llm.ProviderId;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.service.AppInitializationStateService.State;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.versioning.service.CommitIndexSearchLifecycle;
import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AiStatusResponse;
import com.taxonomy.shared.service.HealthSummaryService;
import com.taxonomy.shared.service.LogRingBufferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Core operational routes remain available without the analysis package. */
@RestController
@RequestMapping("/api")
@Tag(name = "Administration")
public class OperationsApiController {
    private final TaxonomyService taxonomyService;
    private final LogRingBufferService logRingBufferService;
    private final HealthSummaryService healthSummaryService;
    @Autowired(required = false)
    private LocalOnnxIndexInitializer embeddingInitializer;
    @Autowired(required = false)
    private CommitIndexSearchLifecycle historyInitializer;
    public OperationsApiController(TaxonomyService taxonomyService, LogRingBufferService logRingBufferService,
                                   HealthSummaryService healthSummaryService) {
        this.taxonomyService = taxonomyService;
        this.logRingBufferService = logRingBufferService;
        this.healthSummaryService = healthSummaryService;
    }

    @Operation(summary = "Startup status",
            description = "Reports catalogue, full-text, semantic and history readiness with measured per-phase progress. HTTP readiness and login remain independent of these background tasks.",
            tags = {"Status"})
    @GetMapping("/status/startup")
    public ResponseEntity<Map<String, Object>> startupStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        var snapshot = taxonomyService.getStateService().getSnapshot();
        boolean initialized = snapshot.state() == State.READY;
        status.put("initialized", initialized);
        status.put("status", switch (snapshot.state()) {
            case READY -> "ready";
            case FAILED -> "error";
            case STARTING -> "pending";
            case LOADING_TAXONOMY, BUILDING_INDEX -> "loading";
        });
        status.put("phase", snapshot.state().name());
        status.put("phaseMessage", snapshot.message());
        status.put("phaseUpdatedAt", snapshot.updatedAt().toString());
        status.put("progress", Map.of("phase", snapshot.phase(),
                "processed", snapshot.processed(), "total", snapshot.total()));
        if (snapshot.state() == State.FAILED) status.put("errorCode", "INITIALIZATION_FAILED");

        var embedding = new LinkedHashMap<String, Object>();
        if (embeddingInitializer != null) {
            var progress = embeddingInitializer.getProgress();
            embedding.put("state", progress.state().name());
            embedding.put("processed", progress.processed());
            embedding.put("total", progress.total());
        } else embedding.put("state", "DISABLED");
        status.put("embedding", embedding);
        status.put("history", Map.of("state", historyInitializer == null ? "DISABLED" : historyInitializer.getState().name()));
        status.put("features", Map.of("catalogue", initialized, "fullTextSearch", initialized,
                "semanticSearch", embeddingInitializer != null && embeddingInitializer.isNodeSearchReady(),
                "historySearch", historyInitializer == null || historyInitializer.isReady()));

        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("heapUsedMB", heapUsed / (1024 * 1024));
        memory.put("heapMaxMB", heapMax / (1024 * 1024));
        memory.put("heapUsagePercent", Math.round((double) heapUsed / heapMax * 100));
        memory.put("threadCount", Thread.activeCount());
        status.put("memory", memory);

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }

    @Operation(summary = "Check admin UI authorization",
            description = "Returns whether the authenticated user must remain outside the admin UI",
            tags = {"Administration"})
    @GetMapping("/admin/status")
    public ResponseEntity<Map<String, Boolean>> adminStatus(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of("passwordRequired", !isAdminAuthorized(request)));
    }

    /**
     * Backward-compatible endpoint for the existing admin modal. Authorization is
     * derived exclusively from the authenticated ROLE_ADMIN identity; a second
     * application password is intentionally not accepted.
     */
    @Operation(summary = "Verify admin authorization",
            description = "Confirms whether the authenticated user has ROLE_ADMIN",
            tags = {"Administration"})
    @PostMapping("/admin/verify")
    public ResponseEntity<Map<String, Object>> verifyAdmin(
            @RequestBody(required = false) Map<String, String> ignoredBody,
            HttpServletRequest request) {
        boolean valid = isAdminAuthorized(request);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", valid);
        if (valid) {
            // Kept only for compatibility with older UI code. The server never
            // trusts this value; every protected request is authorized by ROLE_ADMIN.
            response.put("token", "role-admin");
        }
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get recent log entries",
            description = "Returns recent application log entries from the in-memory ring buffer (ROLE_ADMIN only)",
            tags = {"Administration"})
    @GetMapping("/admin/logs")
    public ResponseEntity<List<LogRingBufferService.LogEntry>> getLogs(
            @Parameter(description = "Filter by log level (e.g. ERROR, WARN, INFO)")
            @RequestParam(required = false) String level,
            @Parameter(description = "Filter by logger name substring")
            @RequestParam(required = false) String component,
            @Parameter(description = "Max number of entries to return (max 500)")
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(logRingBufferService.getEntries(level, component, Math.min(limit, 500)));
    }

    @Operation(summary = "Get health summary",
            description = "Aggregated health status from startup, AI, embedding, and memory subsystems (ROLE_ADMIN only)",
            tags = {"Administration"})
    @GetMapping("/admin/health-summary")
    public ResponseEntity<Map<String, Object>> getHealthSummary(HttpServletRequest request) {
        if (!isAdminAuthorized(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(healthSummaryService.getSummary());
    }

    private boolean isAdminAuthorized(HttpServletRequest request) {
        return request.isUserInRole("ADMIN") || request.isUserInRole("ROLE_ADMIN");
    }
}
