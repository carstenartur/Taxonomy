package com.taxonomy.shared.controller;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.shared.service.HealthSummaryService;
import com.taxonomy.shared.service.LogRingBufferService;
import com.taxonomy.shared.service.PromptTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminApiControllerDiagnosticsTest {

    @Mock private LlmService llmService;
    @Mock private LlmProviderConfig llmProviderConfig;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private TaxonomyService taxonomyService;
    @Mock private LogRingBufferService logRingBufferService;
    @Mock private HealthSummaryService healthSummaryService;
    @Mock private HttpServletRequest request;

    private AdminApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminApiController(
                llmService,
                llmProviderConfig,
                promptTemplateService,
                taxonomyService,
                logRingBufferService,
                healthSummaryService);
        when(request.isUserInRole("ADMIN")).thenReturn(true);
    }

    @Test
    void unauthenticatedCustomProviderDoesNotExposeInternalTransportMarkerAsApiKey() {
        Map<String, Object> serviceDiagnostics = new LinkedHashMap<>();
        serviceDiagnostics.put("provider", "Custom OpenAI-compatible");
        serviceDiagnostics.put("apiKeyConfigured", true);
        serviceDiagnostics.put("apiKeyPrefix", "__taxonomy_custom_no_auth__");
        when(llmService.getDiagnostics()).thenReturn(serviceDiagnostics);
        when(llmService.getActiveProvider()).thenReturn(LlmProvider.CUSTOM_OPENAI);
        when(llmProviderConfig.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).thenReturn(true);
        when(llmProviderConfig.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.diagnostics(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("providerConfigured", true)
                .containsEntry("apiKeyConfigured", false)
                .containsEntry("authenticationMode", "NONE");
        assertThat(response.getBody().get("apiKeyPrefix")).isNull();
        assertThat(response.getBody().toString()).doesNotContain("__taxonomy_custom_no_auth__");
    }

    @Test
    void authenticatedCustomProviderReportsBearerModeWithoutChangingMaskedKeyMetadata() {
        Map<String, Object> serviceDiagnostics = new LinkedHashMap<>();
        serviceDiagnostics.put("provider", "Custom OpenAI-compatible");
        serviceDiagnostics.put("apiKeyConfigured", true);
        serviceDiagnostics.put("apiKeyPrefix", "cust****");
        when(llmService.getDiagnostics()).thenReturn(serviceDiagnostics);
        when(llmService.getActiveProvider()).thenReturn(LlmProvider.CUSTOM_OPENAI);
        when(llmProviderConfig.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).thenReturn(true);
        when(llmProviderConfig.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.diagnostics(request);

        assertThat(response.getBody()).containsEntry("providerConfigured", true)
                .containsEntry("apiKeyConfigured", true)
                .containsEntry("apiKeyPrefix", "cust****")
                .containsEntry("authenticationMode", "BEARER");
    }
}
