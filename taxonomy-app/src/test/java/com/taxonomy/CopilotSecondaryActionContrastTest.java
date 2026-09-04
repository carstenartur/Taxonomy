package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotSecondaryActionContrastTest {

    @Test
    void disabledFocusedAnalysisKeepsTestedForegroundBorderAndOpacity() throws IOException {
        String css;
        try (InputStream input = getClass().getResourceAsStream(
                "/static/css/taxonomy-analysis-workflow.css")) {
            assertThat(input).as("analysis workflow stylesheet").isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(css)
                .contains("--bs-btn-disabled-color: var(--bs-primary-text-emphasis, #052c65);")
                .contains("--bs-btn-disabled-bg: transparent;")
                .contains("--bs-btn-disabled-border-color: var(--bs-primary-text-emphasis, #052c65);")
                .contains(".analysis-secondary-action:disabled,")
                .contains(".analysis-secondary-action.disabled {")
                .contains("opacity: 1;");
    }
}
