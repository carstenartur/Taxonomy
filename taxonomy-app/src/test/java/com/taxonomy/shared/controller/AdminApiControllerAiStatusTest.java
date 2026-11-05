package com.taxonomy.shared.controller;

import com.taxonomy.analysis.service.CustomOpenAiConnectivity;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.service.PromptTemplateService;
import com.taxonomy.composition.analysis.AnalysisAiStatusSource;
import com.taxonomy.dto.AiAvailabilityLevel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminApiControllerAiStatusTest {
    @Test
    void configuredCustomProviderIsNotReportedAvailableWhenItsServerCannotBeReached() {
        var config = mock(LlmProviderConfig.class);
        var probe = mock(CustomOpenAiConnectivity.class);
        var llm = service(config, probe);
        var controller = new AdminApiController(llm, config, mock(PromptTemplateService.class),
                new AnalysisAiStatusSource(llm));
        when(config.getAvailabilityLevel()).thenReturn(AiAvailabilityLevel.FULL);
        when(config.getActiveProviderId()).thenReturn(LlmProvider.CUSTOM_OPENAI.id());
        when(config.getActiveProviderName()).thenReturn("Custom OpenAI-compatible");
        when(config.getAvailableProviders()).thenReturn(List.of("CUSTOM_OPENAI"));
        when(config.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI)).thenReturn("http://unreachable/v1/chat/completions");
        when(probe.check("http://unreachable/v1/chat/completions", null))
                .thenReturn(CustomOpenAiConnectivity.Result.UNREACHABLE);

        var response = controller.aiStatus(null);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        var status = response.getBody();
        assertThat(status.getLevel()).isEqualTo(AiAvailabilityLevel.UNAVAILABLE);
        assertThat(status.isAvailable()).isFalse();
        assertThat(status.getConnectionStatus()).isEqualTo("UNREACHABLE");
        assertThat(status.getProvider()).isEqualTo("Custom OpenAI-compatible");
        assertThat(status.getAvailableProviders()).contains("CUSTOM_OPENAI");
    }

    @Test
    void unsupportedModelsEndpointIsUnknownRatherThanGreenOrUnavailable() {
        var config = mock(LlmProviderConfig.class);
        var probe = mock(CustomOpenAiConnectivity.class);
        var llm = service(config, probe);
        var controller = new AdminApiController(llm, config, mock(PromptTemplateService.class),
                new AnalysisAiStatusSource(llm));
        when(config.getAvailabilityLevel()).thenReturn(AiAvailabilityLevel.FULL);
        when(config.getActiveProviderId()).thenReturn(LlmProvider.CUSTOM_OPENAI.id());
        when(config.getActiveProviderName()).thenReturn("Custom OpenAI-compatible");
        when(config.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI)).thenReturn("http://model/v1/chat/completions");
        when(probe.check("http://model/v1/chat/completions", null))
                .thenReturn(CustomOpenAiConnectivity.Result.UNKNOWN);

        var status = controller.aiStatus(null).getBody();
        assertThat(status.getLevel()).isEqualTo(AiAvailabilityLevel.UNKNOWN);
        assertThat(status.isAvailable()).isFalse();
        assertThat(status.getConnectionStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void explicitlySelectedCustomProviderIsCheckedEvenWhenAnotherProviderIsTheDefault() {
        var config = mock(LlmProviderConfig.class);
        var probe = mock(CustomOpenAiConnectivity.class);
        var llm = service(config, probe);
        var controller = new AdminApiController(llm, config, mock(PromptTemplateService.class),
                new AnalysisAiStatusSource(llm));
        when(config.getAvailabilityLevel()).thenReturn(AiAvailabilityLevel.FULL);
        when(config.getActiveProviderId()).thenReturn(LlmProvider.OPENAI.id());
        when(config.getActiveProviderName()).thenReturn("OpenAI");
        when(config.getAvailableProviders()).thenReturn(List.of("OPENAI", "CUSTOM_OPENAI"));
        when(config.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI)).thenReturn("http://model/v1/chat/completions");
        when(probe.check("http://model/v1/chat/completions", null))
                .thenReturn(CustomOpenAiConnectivity.Result.UNREACHABLE);

        var status = controller.aiStatus("CUSTOM_OPENAI").getBody();
        assertThat(status.getProvider()).isEqualTo("Custom OpenAI-compatible");
        assertThat(status.isAvailable()).isFalse();
        assertThat(status.getConnectionStatus()).isEqualTo("UNREACHABLE");
    }

    @Test
    void authenticatedServerAndMockModeAreReportedWithoutMisclassifyingCredentials() {
        var config = mock(LlmProviderConfig.class);
        var probe = mock(CustomOpenAiConnectivity.class);
        var llm = service(config, probe);
        var source = new AnalysisAiStatusSource(llm);
        when(config.getAvailabilityLevel()).thenReturn(AiAvailabilityLevel.FULL);
        when(config.getActiveProviderId()).thenReturn(LlmProvider.CUSTOM_OPENAI.id());
        when(config.getActiveProviderName()).thenReturn("Custom OpenAI-compatible");
        when(config.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI)).thenReturn("https://model/v1/chat/completions");
        when(config.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).thenReturn(true);
        when(config.getApiKey(LlmProvider.CUSTOM_OPENAI)).thenReturn("secret");
        when(probe.check("https://model/v1/chat/completions", "secret"))
                .thenReturn(CustomOpenAiConnectivity.Result.REACHABLE, CustomOpenAiConnectivity.Result.AUTHENTICATION_FAILED);

        assertThat(source.snapshot().getConnectionStatus()).isEqualTo("REACHABLE");
        assertThat(source.snapshot().getLevel()).isEqualTo(AiAvailabilityLevel.UNAVAILABLE);

        var mockProbe = mock(CustomOpenAiConnectivity.class);
        when(config.isMockMode()).thenReturn(true);
        assertThat(new AnalysisAiStatusSource(service(config, mockProbe)).snapshot().getLevel())
                .isEqualTo(AiAvailabilityLevel.FULL);
        verifyNoInteractions(mockProbe);
    }

    private static LlmService service(LlmProviderConfig config, CustomOpenAiConnectivity probe) {
        var llm = new LlmService(config, null, new ObjectMapper(), null, null, null, null);
        ReflectionTestUtils.setField(llm, "customOpenAiConnectivity", probe);
        return llm;
    }
}
