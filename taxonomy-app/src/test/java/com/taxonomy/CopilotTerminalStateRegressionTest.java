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
        String coordinator = resource("/static/js/core/taxonomy-operation-coordinator.js");

        assertThat(loader)
                .contains("taxonomy-analysis-session-transport.js")
                .contains("taxonomy-copilot-terminal-state.js")
                .contains("taxonomy-operation-coordinator.js");
        assertThat(guard)
                .contains("hasKnownNonAuthoritativeStatus")
                .contains("status !== 'SUCCESS' && status !== 'IMPORTED'")
                .contains("!target || !hasCurrentScores() || !hasKnownNonAuthoritativeStatus()")
                .contains("lastAnalysisProvider = 'MANUAL'")
                .contains("did not complete successfully")
                .doesNotContain("__taxonomyCopilotTerminalGuard", "window.setInterval =", "waitForScores");
        assertThat(coordinator)
                .contains("waitForMainAnalysis()")
                .contains("taxonomy:operation-state")
                .contains("terminal.status === 'SUCCEEDED'")
                .contains("window.TaxonomyAnalysis.runCopilotFlow()");
    }

    private static String resource(String path) throws IOException {
        try (var stream = CopilotTerminalStateRegressionTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
