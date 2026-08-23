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
                .doesNotContain("analyzeBtn.click()", "waitForScores");
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
