package com.taxonomy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Requires actual source and compiled ownership of the extracted context. */
class ArchitectureWorkspaceModuleTest {

    @Test
    void workspaceImplementationIsPhysicallyOwnedByItsMavenModule() throws Exception {
        Path root = checkoutRoot();
        Path module = root.resolve("taxonomy-workspace");
        assertThat(module.resolve("pom.xml")).as("physical workspace Maven module").isRegularFile();
        for (String part : List.of("workspace", "versioning", "editor")) {
            Path source = module.resolve("src/main/java/com/taxonomy/" + part);
            assertThat(source).isDirectory();
            try (var files = Files.walk(source)) {
                assertThat(files.filter(p -> p.toString().endsWith(".java")).count()).isPositive();
            }
            assertThat(root.resolve("taxonomy-app/src/main/java/com/taxonomy/" + part)).doesNotExist();
        }
        assertThat(module.resolve("target/classes/com/taxonomy/editor/ArchitectureEditorService.class")).isRegularFile();
        assertThat(module.resolve("target/classes/com/taxonomy/workspace/storage/DslGitRepository.class")).isRegularFile();
        assertThat(module.resolve("target/classes/com/taxonomy/versioning/service/RepositoryStateService.class")).isRegularFile();
    }

    private static Path checkoutRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Repository root not found");
        return root;
    }
}
