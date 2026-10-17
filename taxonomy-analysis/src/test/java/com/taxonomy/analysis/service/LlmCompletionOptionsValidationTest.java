package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LlmCompletionOptionsValidationTest {
    @ParameterizedTest @ValueSource(ints = {-1, 0, 1_000_001, Integer.MAX_VALUE})
    void outputAllowanceIsPositiveAndBounded(int tokens) {
        assertThatThrownBy(() -> new LlmCompletionOptions(null, null, tokens))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxOutputTokens");
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"has spaces", "private/url?secret=value"})
    void structuredRequestsRequireABoundedProviderSafeSchemaName(String name) {
        assertThatThrownBy(() -> new LlmCompletionOptions(name, Map.of("type", "object"), 128))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schemaName")
                .hasMessageNotContaining("private/url");
    }

    @Test void overlongNamesAreRejectedBeforeTransport() {
        assertThatThrownBy(() -> new LlmCompletionOptions("x".repeat(65), Map.of(), 128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void outputOnlyRequestsMayOmitTheSchema() {
        var options = new LlmCompletionOptions(null, null, 128);
        assertThat(options.schema()).isNull();
        assertThat(options.maxOutputTokens()).isEqualTo(128);
    }
}
