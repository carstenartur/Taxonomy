package com.taxonomy.portfolio;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the packaged browser event handlers and portfolio API against a scoped HTTP fixture. */
class PortfolioFormActionsTest {

    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "review-confirmed", "review-rejected", "obsolete-mapping-decision", "other-tab-project-selection",
            "verbatim-human-review", "compare-snapshots", "compare-snapshot-read-failure", "obsolete-snapshot-comparison",
            "compare-snapshots-de", "compare-oldest-snapshot", "propose-solutions", "save-solution-action",
            "product-created-preserves-draft", "first-product-enables-candidates", "labeled-coverage-fields",
            "inventory-setup", "inventory-labels-de", "inventory-labels-en", "snapshot-summary-dto",
            "solution-coverage-submit", "product-coverage-submit", "candidate-submit", "invalid-forms",
            "candidate-review-confirmed", "candidate-review-rejected", "busy-candidate-review",
            "stale-candidate-review", "stale-candidate-project",
            "busy-form", "readonly-forms-and-review", "pending-edit-permission", "unavailable-edit-permission",
            "empty-project-analysis", "populated-project-analysis"
    })
    void portfolioFormActionsFollowTheRenderedControls(String contract) throws Exception {
        String node = System.getProperty("taxonomy.node.executable");
        assertNotNull(node, "Maven must provide its pinned Node runtime");
        Path script = copyResource("/portfolio/form-actions-contract.cjs");
        Path template = copyResource("/templates/projects.html");
        Path api = copyResource("/static/js/api/portfolio-api.js");
        Path guidance = copyResource("/static/js/portfolio/taxonomy-portfolio-async.js");
        Path portfolio = copyResource("/static/js/portfolio/taxonomy-portfolio.js");
        Path inventory = copyResource("/static/js/portfolio/inventory-comparison.js");
        Path log = directory.resolve("form-actions.log");
        Process process = new ProcessBuilder(node, script.toString(), contract, template.toString(),
                api.toString(), guidance.toString(), portfolio.toString(), inventory.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Contract timed out: " + log);
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PORTFOLIO_FORM_ACTIONS_OK " + contract), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Contract process did not stop");
            }
        }
    }

    private Path copyResource(String name) throws IOException {
        Path path = directory.resolve(Path.of(name).getFileName());
        try (var resource = getClass().getResourceAsStream(name)) {
            assertNotNull(resource, name);
            Files.copy(resource, path);
        }
        return path;
    }
}
