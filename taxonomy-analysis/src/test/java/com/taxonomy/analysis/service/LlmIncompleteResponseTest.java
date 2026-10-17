package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class LlmIncompleteResponseTest {
    private final LlmResponseParser parser = new LlmResponseParser(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "error"})
    void incompleteFinishReasonsDoNotBecomeSuccessfulText(String reason) {
        String body = "{\"choices\":[{\"finish_reason\":\"" + reason
                + "\",\"message\":{\"content\":\"partial-sensitive-content\"}}]}";

        assertThatThrownBy(() -> parser.extractOpenAiText(body))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM_INCOMPLETE_RESPONSE")
                .hasMessageNotContaining("partial-sensitive-content");
    }

    @Test
    void completeAndLegacyResponsesRemainUsable() {
        assertThat(parser.extractOpenAiText("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"complete\"}}]}"))
                .isEqualTo("complete");
        assertThat(parser.extractOpenAiText("{\"choices\":[{\"message\":{\"content\":\"legacy\"}}]}"))
                .isEqualTo("legacy");
    }

    @Test void partialTextAndFinishReasonAreAvailableOnlyThroughExplicitDiagnostics() {
        String body = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial-sensitive-content\"}}]}";
        assertThatThrownBy(() -> parser.extractOpenAiText(body))
                .isInstanceOfSatisfying(LlmIncompleteResponseException.class, incomplete -> {
                    assertThat(incomplete.rawText()).isEqualTo("partial-sensitive-content");
                    assertThat(incomplete.finishReason()).isEqualTo("length");
                    assertThat(incomplete.getMessage()).doesNotContain("partial-sensitive-content");
                });
    }

    @Test void responsesIncompleteStatusRetainsItsPartialText() {
        String body = "{\"object\":\"response\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"partial\"}]}]}";
        assertThatThrownBy(() -> OpenAiResponsesTokenContract.extractText(body, JsonMapper.builder().build()))
                .isInstanceOfSatisfying(LlmIncompleteResponseException.class, incomplete -> {
                    assertThat(incomplete.rawText()).isEqualTo("partial");
                    assertThat(incomplete.finishReason()).isEqualTo("length");
                });
    }
}
