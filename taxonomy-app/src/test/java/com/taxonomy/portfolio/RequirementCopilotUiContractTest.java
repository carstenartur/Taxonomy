package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementCopilotUiContractTest {

    private static final Path ROOT = locateRoot();

    @Test
    void savedRequirementUiUsesPersistentOperationApiInsteadOfBrowserMacro() throws IOException {
        String loader = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/static/js/api/portfolio-api.js"),
                StandardCharsets.UTF_8);
        String api = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/static/js/api/copilot-api.js"),
                StandardCharsets.UTF_8);
        String ui = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/static/js/portfolio/requirement-copilot.js"),
                StandardCharsets.UTF_8);

        assertThat(loader).contains("requirement-copilot.js", "copilot-api.js");
        assertThat(api)
                .contains("/copilot-operations/", "/copilot/latest", "/copilot', request)")
                .doesNotContain("Date.now()", "setInterval");
        assertThat(ui)
                .contains("selectedSnapshotId", "verificationPasses", "copilotCancel")
                .contains("window.location.replace")
                .contains("running || !manualCopilotReady")
                .contains("normalized === lastAnnouncement")
                .contains("lastAnnouncement = normalized")
                .contains("RECONNECTING", "consecutivePollFailures", "renderReconnect")
                .contains("The analysis has not been declared failed")
                .contains("progress.removeAttribute('aria-valuenow')")
                .contains("taxonomy:export-operation-state")
                .contains("blob.size < 1", "Unexpected content type", "Unexpected export filename")
                .contains("dataset.sessionControl", "dataset.sessionTestOutcome")
                .contains("primaryOperationMessage")
                .contains("item.errorMessage", "job.errorSummary")
                .contains("status === 'FAILED' ? 'alert' : 'status'")
                .doesNotContain("run.disabled = running;")
                .doesNotContain("analyzeBtn.click()", "waitForScores");
    }

    @Test
    void adHocCopilotUsesTypedAnalysisLifecycleInsteadOfScoreTimeout() throws IOException {
        String loader = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session.js"),
                StandardCharsets.UTF_8);
        String coordinator = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/static/js/core/taxonomy-operation-coordinator.js"),
                StandardCharsets.UTF_8);

        assertThat(loader).contains("taxonomy-operation-coordinator.js");
        assertThat(coordinator)
                .contains("taxonomy:operation-state")
                .contains("applicationApiPath(url) === '/api/analyze'")
                .contains("TaxonomyI18n", "getBasePath")
                .doesNotContain("url.pathname === '/api/analyze'")
                .contains("status: 'RUNNING'")
                .contains("status: cancelled ? 'CANCELLED' : 'FAILED'")
                .contains("window.TaxonomyAnalysis.runCopilotFlow()")
                .contains("progress-bar-striped progress-bar-animated")
                .doesNotContain("maxAttempts", "60s timeout", "waitForScores");
    }

    @Test
    void copilotAndExportStatusRemainInsideMobileRequirementViewport() throws IOException {
        String template = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/templates/requirement-detail.html"),
                StandardCharsets.UTF_8);
        String css = Files.readString(ROOT.resolve(
                "taxonomy-app/src/main/resources/static/css/taxonomy-portfolio.css"),
                StandardCharsets.UTF_8);

        assertThat(template).contains(
                "id=\"snapshotDetail\" class=\"border rounded p-3 bg-body text-break\"");
        assertThat(css)
                .contains("""
                        #copilotPolicy {
                            min-width: 0;
                            max-width: 100%;
                        }
                        """)
                .contains("""
                        #copilotPolicy .badge {
                            max-width: 100%;
                            white-space: normal;
                            overflow-wrap: anywhere;
                            text-align: start;
                        }
                        """)
                .contains("""
                        #requirementExportOperation {
                            flex: 1 1 100%;
                            min-width: 0;
                            max-width: 100%;
                            overflow-wrap: anywhere;
                        }
                        """);
    }

    private static Path locateRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
