package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotTerminalStateRegressionTest {

    @Test
    void onlyAuthoritativeScoresCanReleaseCopilotSteps() throws IOException {
        String loader = resource("/static/js/core/taxonomy-analysis-session.js");
        String guard = resource("/static/js/core/taxonomy-copilot-terminal-state.js");

        assertThat(loader)
                .contains("taxonomy-analysis-session-transport.js")
                .contains("taxonomy-copilot-terminal-state.js");
        assertThat(guard)
                .contains("C.S.lastAnalysisStatus !== 'SUCCESS'")
                .contains("hasKnownNonAuthoritativeStatus")
                .contains("status !== 'SUCCESS' && status !== 'IMPORTED'")
                .contains("legacy hasScores() shortcut")
                .contains("lastAnalysisProvider = 'MANUAL'")
                .contains("did not complete successfully");
    }

    private static String resource(String path) throws IOException {
        try (var stream = CopilotTerminalStateRegressionTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
