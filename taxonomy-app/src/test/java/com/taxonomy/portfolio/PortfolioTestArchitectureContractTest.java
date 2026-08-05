package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the project against reintroducing a parallel portfolio script suite. */
class PortfolioTestArchitectureContractTest {

    @Test
    void portfolioAcceptanceIsOwnedByJUnitAndMavenFailsafe() throws IOException {
        Path root = findRepositoryRoot();
        Path scripts = root.resolve(".github/scripts");
        List<String> portfolioScripts;
        try (var entries = Files.list(scripts)) {
            portfolioScripts = entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("portfolio"))
                    .filter(name -> name.endsWith(".mjs"))
                    .sorted()
                    .toList();
        }

        assertThat(portfolioScripts)
                .as("Portfolio browser acceptance must not grow a second Node/Playwright test system")
                .isEmpty();
        assertThat(root.resolve(
                "taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java"))
                .isRegularFile();

        String applicationPom = Files.readString(root.resolve("taxonomy-app/pom.xml"));
        assertThat(applicationPom)
                .contains("maven-failsafe-plugin")
                .contains("**/*IT.java");

        String moduleReadme = Files.readString(root.resolve(
                "taxonomy-app/src/main/resources/static/js/portfolio/README.md"));
        assertThat(moduleReadme)
                .contains("PortfolioUiAcceptanceIT")
                .contains("Maven Failsafe")
                .contains("Node/Playwright workflow scripts are intentionally not permitted");

        String projectTemplate = Files.readString(root.resolve(
                "taxonomy-app/src/main/resources/templates/projects.html"));
        String projectScript = Files.readString(root.resolve(
                "taxonomy-app/src/main/resources/static/js/portfolio/taxonomy-portfolio.js"));
        String analysisNormalizer = Files.readString(root.resolve(
                "taxonomy-app/src/main/resources/static/js/portfolio/portfolio-analysis-response-normalizer.js"));
        String analysisSynchronizer = Files.readString(root.resolve(
                "taxonomy-app/src/main/resources/static/js/portfolio/portfolio-analysis-job-synchronizer.js"));
        String bundleController = Files.readString(root.resolve(
                "taxonomy-app/src/main/java/com/taxonomy/portfolio/controller/PortfolioScriptBundleController.java"));
        assertThat(projectTemplate)
                .doesNotContain("id=\"projectList\" class=\"list-group list-group-flush\" role=\"listbox\"");
        assertThat(projectScript)
                .doesNotContain("button.setAttribute('role', 'option')")
                .doesNotContain("button.setAttribute('aria-selected'")
                .contains("button.setAttribute('aria-current', 'page')");
        assertThat(analysisNormalizer)
                .contains("registerWithJobCenter(absoluteLocation, job)")
                .contains("status: registered ? 200 : 202")
                .contains("headers.set('Location', location)")
                .contains("/analysis-jobs/");
        assertThat(analysisSynchronizer)
                .contains("synchronizeCurrentProjectJobs")
                .contains("window.taxonomyPortfolioRegisterJob(location, job)")
                .contains("retryDelaysMs")
                .contains("maximumJobs");
        assertThat(bundleController)
                .contains("window.taxonomyPortfolioRegisterJob")
                .contains("registerJob(resolved.toString(), job)")
                .contains("exposeJobRegistrationBridge")
                .contains("ANALYSIS_JOB_SYNCHRONIZER");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to find repository root");
    }
}
