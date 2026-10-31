package com.taxonomy.build;

import com.taxonomy.tooling.TaxonomyTooling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Positive Maven-owned package evidence for the explicitly scoped documentation lane. */
@EnabledIfSystemProperty(named = "taxonomy.documentation.verify", matches = "true")
class DocumentationPackageIT {
    @Test void packagedDocumentationMatchesSource() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Repository root not found");
        ScenarioAcceptanceNamingContract.verifyNames(root);
        var inputs = new ArrayList<>(DocumentationLinkPolicy.DEFAULT_INPUTS);
        var scope = new ObjectMapper().readTree(Files.readString(root.resolve("target/ci-change-scope.json")));
        scope.path("paths").forEach(path -> inputs.add(path.asString()));
        var links = new DocumentationLinkPolicy().inspect(root, inputs);
        assertFalse(links.markdownFiles().isEmpty(), "Repository documentation inventory must be present");
        assertEquals(List.of(), links.errors(), "Repository-local documentation links and images");
        assertEquals(0, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--package", "true"},
                System.getenv(), root, System.out, System.err));
    }
}
