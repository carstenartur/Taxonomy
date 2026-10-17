package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmRawCompletionOptionsTest {
    @Test void rawCallForwardsOptionsAndExtractsItsAnswer() {
        var gateway = mock(LlmGateway.class);
        var options = OpenAiCompletionOptionsTest.options(1024);
        when(gateway.sendHttpRequest("prompt", "fixture-key", options)).thenReturn("wire-body");
        when(gateway.extractResponseText("wire-body")).thenReturn("answer");

        assertThat(service(gateway).callLlmRaw("prompt", options)).isEqualTo("answer");
        verify(gateway, never()).sendHttpRequest("prompt", "fixture-key");
    }

    @Test void incompleteAnswersPropagateThroughTheLegacyRawCall() {
        var parser = new LlmResponseParser(JsonMapper.builder().build());
        LlmGateway gateway = new LlmGateway() {
            public String providerName() { return "OPENAI"; }
            public String sendHttpRequest(String prompt, String key) {
                return "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}";
            }
            public String extractResponseText(String body) { return parser.extractOpenAiText(body); }
        };

        assertThatThrownBy(() -> service(gateway).callLlmRaw("prompt"))
                .isInstanceOfSatisfying(LlmIncompleteResponseException.class, incomplete -> {
                    assertThat(incomplete.rawText()).isEqualTo("partial");
                    assertThat(incomplete.finishReason()).isEqualTo("length");
                });
    }

    @Test void incompleteAnswersPropagateThroughTheOptionsRawCall() {
        var gateway = mock(LlmGateway.class);
        var options = OpenAiCompletionOptionsTest.options(1024);
        when(gateway.sendHttpRequest("prompt", "fixture-key", options)).thenReturn("wire-body");
        when(gateway.extractResponseText("wire-body")).thenThrow(new LlmIncompleteResponseException("partial", "length"));

        assertThatThrownBy(() -> service(gateway).callLlmRaw("prompt", options))
                .isInstanceOf(LlmIncompleteResponseException.class);
    }

    @Test void budgetDecoratorForwardsStructuredOptionsAfterValidation() {
        var delegate = mock(LlmGateway.class);
        when(delegate.providerName()).thenReturn("CUSTOM_OPENAI");
        var options = OpenAiCompletionOptionsTest.options(1024);
        when(delegate.sendHttpRequest("prompt", "fixture-key", options)).thenReturn("wire-body");
        var policy = mock(AiPromptBudgetPolicy.class);

        assertThat(new PromptBudgetEnforcingLlmGateway(delegate, policy)
                .sendHttpRequest("prompt", "fixture-key", options)).isEqualTo("wire-body");
        var order = inOrder(policy, delegate);
        order.verify(policy).requireWithinBudget(argThat(text -> text.startsWith("prompt")
                && text.contains("\"response_format\"")), eq("CUSTOM_OPENAI"));
        order.verify(delegate).sendHttpRequest("prompt", "fixture-key", options);
    }

    @Test void unsupportedProvidersKeepTheirExistingTransportThroughTheDefaultOverload() {
        LlmGateway gateway = new LlmGateway() {
            public String providerName() { return "EXAMPLE"; }
            public String sendHttpRequest(String prompt, String key) { return prompt + ":" + key; }
            public String extractResponseText(String body) { return body; }
        };

        assertThat(gateway.sendHttpRequest("prompt", "fixture-key", OpenAiCompletionOptionsTest.options(1024)))
                .isEqualTo("prompt:fixture-key");
    }

    @Test void schemaSizeCannotBypassTheFinalModelContextGuard() {
        var providers = new LlmProviderConfig(mock(com.taxonomy.catalog.service.LocalEmbeddingService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(providers, "customLlmUrl", "http://localhost:8000/v1/chat/completions");
        org.springframework.test.util.ReflectionTestUtils.setField(providers, "customLlmModel", "fixture-model");
        org.springframework.test.util.ReflectionTestUtils.setField(providers, "customLlmContextWindowTokens", 3072);
        org.springframework.test.util.ReflectionTestUtils.setField(providers, "customLlmMaxOutputTokens", 2048);
        var delegate = mock(LlmGateway.class);
        when(delegate.providerName()).thenReturn("CUSTOM_OPENAI");
        var gateway = new PromptBudgetEnforcingLlmGateway(delegate,
                new AiPromptBudgetPolicy(new AiTargetCatalogService(providers)));
        var options = new LlmCompletionOptions("relation_decisions", java.util.Map.of("type", "object",
                "description", "s".repeat(2000)), 2048);

        assertThatThrownBy(() -> gateway.sendHttpRequest("p".repeat(3000), "fixture-key", options))
                .isInstanceOf(PromptBudgetExceededException.class);
        verify(delegate, never()).sendHttpRequest(anyString(), anyString(), any(LlmCompletionOptions.class));
    }

    private static LlmService service(LlmGateway gateway) {
        var providers = mock(LlmProviderConfig.class);
        when(providers.getActiveProviderId()).thenReturn(LlmProvider.OPENAI.id());
        when(providers.getApiKey(LlmProvider.OPENAI.id())).thenReturn("fixture-key");
        var registry = mock(LlmGatewayRegistry.class);
        when(registry.getGatewayById(LlmProvider.OPENAI.id())).thenReturn(gateway);
        return new LlmService(providers, registry, JsonMapper.builder().build(), null, null, null, null);
    }
}
