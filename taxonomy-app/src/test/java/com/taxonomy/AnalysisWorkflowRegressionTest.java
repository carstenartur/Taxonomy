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
    void copilotAuthorityRoutesCompleteAnalysisIndependentlyFromRendering()
            throws IOException {
        String authority = resource(
                "/static/js/core/taxonomy-copilot-terminal-state.js");

        assertThat(authority)
                .contains("function installCompleteAnalysisRouting()")
                .contains("var completeRequested = copilotRunning()")
                .contains("interactive.checked === false")
                .contains("scoring.runAnalysis();")
                .contains("installCompleteAnalysisRouting();");
    }

    @Test
    void copilotAuthorityPreservesAndReusesManualScoring() throws IOException {
        String authority = resource(
                "/static/js/core/taxonomy-copilot-terminal-state.js");

        assertThat(authority)
                .contains("function hasCurrentScores()")
                .contains("selectedProvider.value === 'MANUAL'")
                .contains("if (!copilotRunning()) return;")
                .contains("already completed manual scoring result")
                .contains("lastAnalysisProvider = 'MANUAL'")
                .contains("lastAnalysisStatus = 'SUCCESS'");
    }

    @Test
    void copilotPreflightRejectsUnavailableAnalysisBeforeStartingAnalysis()
            throws IOException {
        String authority = resource(
                "/static/js/core/taxonomy-copilot-terminal-state.js");

        assertThat(authority)
                .contains("var copilotTarget = closest('#copilotBtn')")
                .contains("analyzeAction.disabled")
                .contains("elementAriaDisabled(analyzeAction)")
                .contains("showCopilotUnavailableFailure();")
                .contains("showCopilotManualProviderFailure();");
    }

    @Test
    void copilotWaitsForAnExactSuccessfulTerminalOperation() throws IOException {
        String authority = resource(
                "/static/js/core/taxonomy-copilot-terminal-state.js");
        String coordinator = resource(
                "/static/js/core/taxonomy-operation-coordinator.js");

        assertThat(authority)
                .contains("return hasScores(C.S.currentScores);")
                .contains("hasKnownNonAuthoritativeStatus")
                .doesNotContain("__taxonomyCopilotTerminalGuard", "window.setInterval =");
        assertThat(coordinator)
                .contains("function waitForMainAnalysis()")
                .contains("taxonomy:operation-state")
                .contains("if (!TERMINAL.has(detail.status)) return;")
                .contains("if (terminal.status === 'SUCCEEDED')")
                .contains("window.TaxonomyAnalysis.runCopilotFlow()")
                .contains("var scores = C.S.currentScores;");
    }

    private static String resource(String path) throws IOException {
        try (var stream = AnalysisWorkflowRegressionTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing test resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
