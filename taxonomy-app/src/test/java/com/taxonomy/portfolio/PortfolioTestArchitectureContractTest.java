package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                    .filter(name -> name.startsWith("ui-primary-portfolio"))
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
