package com.taxonomy.shared.service;

import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.LocalEmbeddingService;


import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AiStatusResponse;
import com.taxonomy.catalog.service.AppInitializationStateService.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HealthSummaryService}.
 */
@ExtendWith(MockitoExtension.class)
class HealthSummaryServiceTest {

    @Mock
    private TaxonomyService taxonomyService;

    @Mock
    private AiStatusSource llmService;

    @Mock
    private LocalEmbeddingService embeddingService;

    @Mock
    private AppInitializationStateService stateService;

    @InjectMocks
    private HealthSummaryService healthSummaryService;

    @BeforeEach
    void setUp() {
        when(taxonomyService.getStateService()).thenReturn(stateService);
    }

    @Test
    void allHealthy_returnsOverallUp() {
        stubHealthy();

        Map<String, Object> summary = healthSummaryService.getSummary();

        assertThat(summary.get("overall")).isEqualTo("UP");

        @SuppressWarnings("unchecked")
        var ai = (Map<String, Object>) summary.get("ai");
        assertThat(ai.get("available")).isEqualTo(true);
        assertThat(ai.get("level")).isEqualTo("FULL");
        assertThat(ai.get("provider")).isEqualTo("Gemini");

        @SuppressWarnings("unchecked")
        var startup = (Map<String, Object>) summary.get("startup");
        assertThat(startup.get("initialized")).isEqualTo(true);
        assertThat(startup.get("phase")).isEqualTo("READY");

        @SuppressWarnings("unchecked")
        var embedding = (Map<String, Object>) summary.get("embedding");
        assertThat(embedding.get("enabled")).isEqualTo(true);
        assertThat(embedding.get("available")).isEqualTo(true);
    }

    @Test
    void aiUnavailable_returnsDegradedWithNullProvider() {
        stubStartupReady();
        when(llmService.snapshot()).thenReturn(status(AiAvailabilityLevel.UNAVAILABLE, null));
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.isAvailable()).thenReturn(true);

        Map<String, Object> summary = healthSummaryService.getSummary();

        assertThat(summary.get("overall")).isEqualTo("DEGRADED");

        @SuppressWarnings("unchecked")
        var ai = (Map<String, Object>) summary.get("ai");
        assertThat(ai.get("available")).isEqualTo(false);
        assertThat(ai.get("level")).isEqualTo("UNAVAILABLE");
        assertThat(ai.get("provider")).isNull();
    }

    @Test
    void unverifiedAiConnectionIsNotReportedHealthy() {
        stubStartupReady();
        when(llmService.snapshot()).thenReturn(status(AiAvailabilityLevel.UNKNOWN, "Custom OpenAI-compatible"));
        when(embeddingService.isEnabled()).thenReturn(false);
        when(embeddingService.isAvailable()).thenReturn(false);

        Map<String, Object> summary = healthSummaryService.getSummary();
        @SuppressWarnings("unchecked")
        var ai = (Map<String, Object>) summary.get("ai");
        assertThat(ai.get("available")).isEqualTo(false);
        assertThat(ai.get("level")).isEqualTo("UNKNOWN");
        assertThat(summary.get("overall")).isEqualTo("DEGRADED");
    }

    @Test
    void reportsConnectionStatusFromTheSameSnapshotAsAvailability() {
        stubStartupReady();
        when(llmService.snapshot()).thenReturn(new AiStatusResponse(AiAvailabilityLevel.UNAVAILABLE,
                "Custom OpenAI-compatible", List.of("CUSTOM_OPENAI"), "AUTHENTICATION_FAILED"));

        @SuppressWarnings("unchecked")
        var ai = (Map<String, Object>) healthSummaryService.getSummary().get("ai");
        assertThat(ai.get("connectionStatus")).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(ai.get("available")).isEqualTo(false);
        assertThat(ai.get("provider")).isEqualTo("Custom OpenAI-compatible");
    }

    @Test
    void notInitializedAndStateNotReady_returnsDegraded() {
        when(taxonomyService.isInitialized()).thenReturn(false);
        when(taxonomyService.getInitStatus()).thenReturn("Loading…");
        when(stateService.getState()).thenReturn(State.LOADING_TAXONOMY);
        when(stateService.getMessage()).thenReturn("Loading taxonomy data");
        when(stateService.isReady()).thenReturn(false);

        when(llmService.snapshot()).thenReturn(status(AiAvailabilityLevel.FULL, "Gemini"));
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.isAvailable()).thenReturn(true);

        Map<String, Object> summary = healthSummaryService.getSummary();

        assertThat(summary.get("overall")).isEqualTo("DEGRADED");

        @SuppressWarnings("unchecked")
        var startup = (Map<String, Object>) summary.get("startup");
        assertThat(startup.get("initialized")).isEqualTo(false);
        assertThat(startup.get("phase")).isEqualTo("LOADING_TAXONOMY");
        assertThat(startup.get("phaseMessage")).isEqualTo("Loading taxonomy data");
    }

    @Test
    void embeddingDisabled_showsDisabledInEmbeddingSection() {
        stubStartupReady();
        when(llmService.snapshot()).thenReturn(status(AiAvailabilityLevel.FULL, "Gemini"));
        when(embeddingService.isEnabled()).thenReturn(false);
        when(embeddingService.isAvailable()).thenReturn(false);

        Map<String, Object> summary = healthSummaryService.getSummary();

        @SuppressWarnings("unchecked")
        var embedding = (Map<String, Object>) summary.get("embedding");
        assertThat(embedding.get("enabled")).isEqualTo(false);
        assertThat(embedding.get("available")).isEqualTo(false);
    }

    @Test
    void timestampPresentAndStartupHasExpectedKeys() {
        stubHealthy();

        Map<String, Object> summary = healthSummaryService.getSummary();

        assertThat(summary.get("timestamp")).isNotNull();
        assertThat(Instant.parse((String) summary.get("timestamp"))).isBefore(Instant.now().plusSeconds(1));

        @SuppressWarnings("unchecked")
        var startup = (Map<String, Object>) summary.get("startup");
        assertThat(startup).containsKeys("initialized", "status", "phase", "phaseMessage");
    }

    @Test
    void memorySectionHasExpectedKeys() {
        stubHealthy();

        Map<String, Object> summary = healthSummaryService.getSummary();

        @SuppressWarnings("unchecked")
        var memory = (Map<String, Object>) summary.get("memory");
        assertThat(memory).containsKeys("heapUsedMB", "heapMaxMB", "heapUsagePercent");
        assertThat((long) memory.get("heapUsedMB")).isPositive();
        assertThat((long) memory.get("heapMaxMB")).isPositive();
        assertThat((long) memory.get("heapUsagePercent")).isBetween(0L, 100L);
    }

    @Test
    void aiLimited_returnsOverallUp() {
        stubStartupReady();
        when(llmService.snapshot()).thenReturn(status(AiAvailabilityLevel.LIMITED, "LocalOnnx"));
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.isAvailable()).thenReturn(true);

        Map<String, Object> summary = healthSummaryService.getSummary();

        assertThat(summary.get("overall")).isEqualTo("UP");

        @SuppressWarnings("unchecked")
        var ai = (Map<String, Object>) summary.get("ai");
        assertThat(ai.get("available")).isEqualTo(true);
        assertThat(ai.get("level")).isEqualTo("LIMITED");
        assertThat(ai.get("provider")).isEqualTo("LocalOnnx");
    }

    // --- helpers ---

    private static AiStatusResponse status(AiAvailabilityLevel level, String provider) {
        return new AiStatusResponse(level, provider, List.of());
    }

    private void stubStartupReady() {
        when(taxonomyService.isInitialized()).thenReturn(true);
        when(taxonomyService.getInitStatus()).thenReturn("Ready");
        when(stateService.getState()).thenReturn(State.READY);
        when(stateService.getMessage()).thenReturn("Application started");
        when(stateService.isReady()).thenReturn(true);
    }

    private void stubHealthy() {
        stubStartupReady();
        when(llmService.snapshot()).thenReturn(status(AiAvailabilityLevel.FULL, "Gemini"));
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.isAvailable()).thenReturn(true);
    }
}
