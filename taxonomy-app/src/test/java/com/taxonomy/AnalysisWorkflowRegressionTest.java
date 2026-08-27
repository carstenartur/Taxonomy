package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisWorkflowRegressionTest {

    @Test
    void projectPromotionUsesPlanningWhileItsFirstRequirementRemainsDraft()
            throws IOException {
        String projects = resource(
                "/static/js/core/taxonomy-analysis-session-projects.js");

        assertThat(projects)
                .contains("description: descriptionInput.value.trim(),\n"
                        + "                    status: 'PLANNING'")
                .contains("text: values.text,\n"
                        + "                status: 'DRAFT'")
                .doesNotContain("description: descriptionInput.value.trim(),\n"
                        + "                    status: 'DRAFT'");
    }

    @Test
    void projectDialogBoundsFrameworkDeserializationDetails() throws IOException {
        String projects = resource(
                "/static/js/core/taxonomy-analysis-session-projects.js");

        assertThat(projects)
                .contains("function safeDialogErrorMessage(error)")
                .contains("JSON parse error")
                .contains("Cannot deserialize")
                .contains("PortfolioTypes\\$")
                .contains("parts.error.textContent = safeDialogErrorMessage(error)");
    }

    @Test
    void completeAnalysisRoutingIsIndependentFromTheCurrentRendering() throws IOException {
        String transport = resource(
                "/static/js/core/taxonomy-analysis-session-transport.js");

        assertThat(transport)
                .contains("function installCompleteAnalysisRouting()")
                .contains("var completeRequested = copilotRunning()")
                .contains("interactive.checked === false")
                .contains("scoring.runAnalysis();")
                .contains("installCompleteAnalysisRouting();");
    }

    @Test
    void completeAnalysisRoutingPreservesAndReusesManualScoring() throws IOException {
        String transport = resource(
                "/static/js/core/taxonomy-analysis-session-transport.js");

        assertThat(transport)
                .contains("function hasCurrentScores()")
                .contains("provider.value === 'MANUAL'")
                .contains("if (!copilotRunning()) return;")
                .contains("!existingScores && selectedProvider")
                .contains("already completed manual scoring result");
    }

    @Test
    void copilotPreflightRejectsUnavailableAnalysisWithoutStartingItsTimeout()
            throws IOException {
        String transport = resource(
                "/static/js/core/taxonomy-analysis-session-transport.js");

        assertThat(transport)
                .contains("var copilotTarget = closest('#copilotBtn')")
                .contains("analyzeAction.disabled")
                .contains("elementAriaDisabled(analyzeAction)")
                .contains("showCopilotUnavailableFailure();")
                .contains("showCopilotManualProviderFailure();");
    }

    @Test
    void copilotWaitsForTheMainAnalysisTerminalState() throws IOException {
        String transport = resource(
                "/static/js/core/taxonomy-analysis-session-transport.js");

        assertThat(transport)
                .contains("var tracked = copilotRunning() && analysisRunning();")
                .contains("if (tracked && analysisRunning()) return;")
                .contains("C.S.lastAnalysisStatus === 'ERROR'")
                .contains("showCopilotAnalysisFailure();")
                .contains("resetBusyControls();");
    }

    private static String resource(String path) throws IOException {
        try (var stream = AnalysisWorkflowRegressionTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
