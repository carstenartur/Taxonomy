package com.taxonomy.shared.service;

import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.LocalEmbeddingService;

import org.jspecify.annotations.Nullable;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AiStatusResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates health information from multiple internal status sources into a single dashboard view.
 */
@Service
public class HealthSummaryService {

    private final TaxonomyService taxonomyService;
    private final @Nullable AiStatusSource llmService;
    private final LocalEmbeddingService embeddingService;

    public HealthSummaryService(TaxonomyService taxonomyService,
                                @Nullable AiStatusSource llmService,
                                LocalEmbeddingService embeddingService) {
        this.taxonomyService = taxonomyService;
        this.llmService = llmService;
        this.embeddingService = embeddingService;
    }

    public Map<String, Object> getSummary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("timestamp", Instant.now().toString());

        // Startup status
        var startup = new LinkedHashMap<String, Object>();
        AppInitializationStateService stateService = taxonomyService.getStateService();
        startup.put("initialized", taxonomyService.isInitialized());
        startup.put("status", taxonomyService.getInitStatus());
        startup.put("phase", stateService.getState().name());
        startup.put("phaseMessage", stateService.getMessage());
        summary.put("startup", startup);

        // AI / LLM status
        var ai = new LinkedHashMap<String, Object>();
        AiStatusResponse status = llmService == null ? null : llmService.snapshot();
        AiAvailabilityLevel level = status == null ? AiAvailabilityLevel.UNAVAILABLE : status.getLevel();
        boolean aiAvailable = level == AiAvailabilityLevel.FULL || level == AiAvailabilityLevel.LIMITED;
        ai.put("available", aiAvailable);
        ai.put("level", level.name());
        ai.put("provider", status == null ? null : status.getProvider());
        ai.put("connectionStatus", status == null ? "NOT_CHECKED" : status.getConnectionStatus());
        ai.put("installed", llmService != null);
        summary.put("ai", ai);

        // Embedding status
        var embedding = new LinkedHashMap<String, Object>();
        embedding.put("enabled", embeddingService.isEnabled());
        embedding.put("available", embeddingService.isAvailable());
        summary.put("embedding", embedding);

        // Memory
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        var memory = new LinkedHashMap<String, Object>();
        memory.put("heapUsedMB", heapUsed / (1024 * 1024));
        memory.put("heapMaxMB", heapMax / (1024 * 1024));
        memory.put("heapUsagePercent", Math.round((double) heapUsed / heapMax * 100));
        summary.put("memory", memory);

        // Overall status
        boolean allOk = stateService.isReady()
                && (llmService == null || aiAvailable);
        summary.put("overall", allOk ? "UP" : "DEGRADED");

        return summary;
    }
}
