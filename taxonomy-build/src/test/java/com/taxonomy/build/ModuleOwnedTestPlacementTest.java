package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prevents module-owned workspace/versioning tests from drifting back into the
 * application test monolith.
 *
 * <p>The remaining tests are deliberately application-owned: they require a
 * full Spring Boot/MockMvc context, Testcontainers, or higher-level catalog /
 * relation composition that taxonomy-workspace must not depend on.</p>
 */
class ModuleOwnedTestPlacementTest {

    private static final Set<String> ALLOWED_APP_OWNED = Set.of(
            "workspace/controller/ExternalSyncControllerTest.java",
            "workspace/controller/WorkspaceAccessSecurityIT.java",
            "workspace/controller/WorkspaceControllerTest.java",
            "workspace/controller/WorkspaceProvisioningBoundaryIT.java",
            "workspace/controller/WorkspaceScopedEndpointIsolationTest.java",
            "workspace/service/WorkspaceDataIsolationTest.java",
            "workspace/service/WorkspaceProvisioningClaimIT.java",
            "workspace/service/WorkspaceRestartSelectionIT.java",
            "workspace/storage/DslWorkspaceReadAdapterTest.java",
            "workspace/storage/DslWorkspaceVersionAdapterTest.java",
            "workspace/storage/JgitStorageHibernateIntegrationTest.java",
            "workspace/storage/JgitStoragePostgresMigrationIT.java",
            "versioning/controller/ContextNavigationControllerTest.java",
            "versioning/controller/GitStateControllerTest.java",
            "versioning/controller/ViewContextIntegrationTest.java");

    @Test
    void workspaceAndVersioningTestsStayWithTheirOwningModule() throws Exception {
        Path root = repositoryRoot();
        Path packageRoot = root.resolve("taxonomy-app/src/test/java/com/taxonomy");

        Set<String> actual = new LinkedHashSet<>();
        collectJavaFiles(packageRoot.resolve("workspace"), packageRoot, actual);
        collectJavaFiles(packageRoot.resolve("versioning"), packageRoot, actual);

        assertThat(actual)
                .as("application-owned workspace/versioning tests; pure module tests belong in taxonomy-workspace")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_APP_OWNED);
    }

    private static void collectJavaFiles(
            Path directory,
            Path relativeTo,
            Set<String> target) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(relativeTo::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .forEach(target::add);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))
                    && Files.isDirectory(current.resolve("taxonomy-workspace"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
