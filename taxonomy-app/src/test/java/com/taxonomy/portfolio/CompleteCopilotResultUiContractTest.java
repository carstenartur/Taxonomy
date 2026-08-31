package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompleteCopilotResultUiContractTest {

    private static final Path ROOT = locateRoot();

    @Test
    void completeRunUsesStructuredReviewSurfaceInsteadOfRawJson() throws IOException {
        String detail = read("taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js");

        assertThat(detail)
                .contains("activateDetailTab('analyses-tab')")
                .contains("aria-current", "snapshotResultOverview", "portfolio-result-kpi")
                .contains("renderGapResult", "renderPatternResult", "renderRecommendationResult")
                .contains("portfolio-finding-details", "technicalSnapshotData")
                .contains("architectureWorkbenchUrl", "portfolio-decision-filters")
                .doesNotContain("gap.summary || gap.description || JSON.stringify(gap)")
                .doesNotContain("recommendation.summary || recommendation.recommendation || JSON.stringify(recommendation)");
    }

    @Test
    void longEvidenceIsBoundedAndSecondaryDetailsStartCollapsed() throws IOException {
        String css = read("taxonomy-app/src/main/resources/static/css/taxonomy-portfolio.css");
        String detail = read("taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js");

        assertThat(css)
                .contains(".portfolio-result-kpis")
                .contains("grid-template-columns: repeat(6")
                .contains(".portfolio-result-table")
                .contains("max-height: 34rem")
                .contains("overflow: auto")
                .contains(".portfolio-result-table-sm")
                .contains("@media (max-width: 767.98px)");
        assertThat(detail)
                .contains("<details class=\"portfolio-finding-details\"")
                .doesNotContain("<details class=\"portfolio-finding-details\" open");
    }

    @Test
    void terminalCopilotStateYieldsSpaceToTheResult() throws IOException {
        String copilot = read("taxonomy-app/src/main/resources/static/js/portfolio/requirement-copilot.js");
        String css = read("taxonomy-app/src/main/resources/static/css/taxonomy-portfolio.css");

        assertThat(copilot)
                .contains("id=\"copilotRunSettings\"")
                .contains("settings.open = !isTerminal")
                .contains("classList.toggle('copilot-terminal', isTerminal)");
        assertThat(css)
                .contains("#requirementCopilotCard.copilot-terminal")
                .contains("#copilotProgress")
                .contains("height: 0.45rem");
    }

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path), StandardCharsets.UTF_8);
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
