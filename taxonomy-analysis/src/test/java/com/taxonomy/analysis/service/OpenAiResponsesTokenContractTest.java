package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesTokenContractTest {
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private static final String ADAPTER = "openai.responses.gpt-4o-mini-2024-07-18.v1";
    private static final String PROMPT = "System: preserve all authored instructions.\nCatalogue: CP-1 — Äußere Kommunikation\nRequirement: 治療 🧭";
    private ProviderRateQuotas.TokenBudget budget() { return new ProviderRateQuotas.TokenBudget(ADAPTER, 5000, 256); }

    @Test void freezesTheEntireWireInputWithDisabledTruncationAndAnActualFullOutputCap() {
        var prepared = OpenAiResponsesTokenContract.prepare(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL,
                LlmProviderConfig.OPENAI_MODEL, budget(), PROMPT, json);
        assertThat(prepared).as("A supported TPM adapter must prepare a complete immutable wire contract").isNotNull();
        var count = json.readTree(prepared.countBody()); var generation = json.readTree(prepared.generationBody());
        assertThat(count.get("model").asText()).isEqualTo("gpt-4o-mini-2024-07-18");
        assertThat(count.get("model")).isEqualTo(generation.get("model"));
        assertThat(count.get("input")).isEqualTo(generation.get("input"));
        assertThat(count.get("truncation").asText()).isEqualTo("disabled");
        assertThat(generation.get("truncation").asText()).isEqualTo("disabled");
        assertThat(count.get("input").get(0).get("role").asText()).isEqualTo("user");
        assertThat(count.get("input").get(0).get("content").get(0).get("text").asText()).isEqualTo(PROMPT);
        assertThat(generation.get("max_output_tokens").intValue()).isEqualTo(256);
        assertThat(generation.get("store").booleanValue()).isFalse();
        assertThat(count.size()).isEqualTo(3); assertThat(generation.size()).isEqualTo(5);
        assertThat(prepared.countBody().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1_048_576);
    }

    @Test void acceptsOnlyAnExactCompleteCountWithinTheDeclaredInputBound() {
        assertThat(OpenAiResponsesTokenContract.validateCount("{\"object\":\"response.input_tokens\",\"input_tokens\":5000}", budget(), json)).isEqualTo(5000);
        for (String body : java.util.List.of("{\"object\":\"response.input_tokens\",\"input_tokens\":5001}",
                "{\"object\":\"response.input_tokens\",\"input_tokens\":-1}", "{\"object\":\"response.input_tokens\",\"input_tokens\":1.5}",
                "{\"object\":\"response.input_tokens\",\"input_tokens\":9223372036854775808}", "{\"object\":\"other\",\"input_tokens\":1}",
                "{\"object\":\"response.input_tokens\",\"input_tokens\":1,\"usage\":{}}", "{\"object\":\"response.input_tokens\",\"input_tokens\":1,\"input_tokens\":2}",
                "{\"object\":\"response.input_tokens\",\"input_tokens\":1}{}")) {
            assertThatThrownBy(() -> OpenAiResponsesTokenContract.validateCount(body, budget(), json))
                    .as("Invalid or unproven full-input count: %s", body).isInstanceOf(LlmProviderException.class);
        }
    }

    @Test void unsupportedIdentityAndOversizedWireAreRejectedBeforeCountAdmission() {
        for (LlmProvider provider : java.util.List.of(LlmProvider.GEMINI, LlmProvider.CUSTOM_OPENAI, LlmProvider.DEEPSEEK)) {
            assertThatThrownBy(() -> OpenAiResponsesTokenContract.prepare(provider, LlmProviderConfig.OPENAI_URL,
                    LlmProviderConfig.OPENAI_MODEL, budget(), PROMPT, json)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        }
        assertThatThrownBy(() -> OpenAiResponsesTokenContract.prepare(LlmProvider.OPENAI, "https://foreign.invalid/v1/chat/completions",
                LlmProviderConfig.OPENAI_MODEL, budget(), PROMPT, json)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThatThrownBy(() -> OpenAiResponsesTokenContract.prepare(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL,
                "moving-other-alias", budget(), PROMPT, json)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThatThrownBy(() -> OpenAiResponsesTokenContract.prepare(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL,
                LlmProviderConfig.OPENAI_MODEL, budget(), "界".repeat(400_000), json)).isInstanceOf(LlmProviderException.class);
    }

    @Test void contextAndOutputLimitsAreModelSpecificAndNotEstimatedFromText() {
        for (var invalid : java.util.List.of(new ProviderRateQuotas.TokenBudget("unsupported", 5000, 256),
                new ProviderRateQuotas.TokenBudget(ADAPTER, 127_999, 2), new ProviderRateQuotas.TokenBudget(ADAPTER, 5000, 16_385))) {
            assertThatThrownBy(() -> OpenAiResponsesTokenContract.prepare(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL,
                    LlmProviderConfig.OPENAI_MODEL, invalid, PROMPT, json)).isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        }
    }

    @Test void requiresCompletedActualResponsesTextAndDoesNotAcceptRefusalOrPartialOutput() {
        String response = "{\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}]}";
        assertThat(OpenAiResponsesTokenContract.extractText(response, json)).isEqualTo("answer");
        assertThatThrownBy(() -> OpenAiResponsesTokenContract.extractText(response.replace("completed", "incomplete"), json)).isInstanceOf(LlmProviderException.class);
        assertThatThrownBy(() -> OpenAiResponsesTokenContract.extractText(response.replace("output_text", "refusal"), json)).isInstanceOf(LlmProviderException.class);
    }
}
