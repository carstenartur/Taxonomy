package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioDocumentationContractTest {

    private static final List<String> GUI_ROUTES = List.of(
            "/projects",
            "/projects/{projectId}/import",
            "/projects/{projectId}/requirements/{requirementId}",
            "/projects/{projectId}/matrices",
            "/projects/{projectId}/versioning",
            "/projects/{projectId}/reports");

    @Test
    void userGuidesDescribeTheSameGuiAndContainNoCurlWorkflow() throws IOException {
        String english = read("docs/en/PROJECT_REQUIREMENT_PORTFOLIO.md");
        String german = read("docs/de/PROJECT_REQUIREMENT_PORTFOLIO.md");

        for (String route : GUI_ROUTES) {
            assertThat(english).contains(route);
            assertThat(german).contains(route);
        }
        assertThat(english.toLowerCase()).doesNotContain("curl -");
        assertThat(german.toLowerCase()).doesNotContain("curl -");
        assertThat(english).contains("PROJECT_PORTFOLIO_API.md");
        assertThat(german).contains("PROJECT_PORTFOLIO_API.md");
    }

    @Test
    void apiReferencesContainAutomationExamplesAndAllNewContracts() throws IOException {
        String english = read("docs/en/PROJECT_PORTFOLIO_API.md");
        String german = read("docs/de/PROJECT_PORTFOLIO_API.md");
        List<String> contracts = List.of(
                "/requirements/import-review",
                "/analysis-jobs/",
                "/projects/git/materialize-preview",
                "/projects/git/merge",
                "/reports/{format}");

        assertThat(english).contains("curl -");
        assertThat(german).contains("curl -");
        for (String contract : contracts) {
            assertThat(english).contains(contract);
            assertThat(german).contains(contract);
        }
    }

    @Test
    void featureMatricesTrackTheSamePortfolioCapabilities() throws IOException {
        String english = read("docs/en/PROJECT_PORTFOLIO_FEATURE_MATRIX.md");
        String german = read("docs/de/PROJECT_PORTFOLIO_FEATURE_MATRIX.md");
        for (String route : GUI_ROUTES) {
            assertThat(english).contains(route);
            assertThat(german).contains(route);
        }
        assertThat(english).contains("#584", "#585", "#586");
        assertThat(german).contains("#584", "#585", "#586");
    }

    private static String read(String repositoryRelativePath) throws IOException {
        Path root = findRepositoryRoot();
        return Files.readString(root.resolve(repositoryRelativePath));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("docs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to find repository root");
    }
}
