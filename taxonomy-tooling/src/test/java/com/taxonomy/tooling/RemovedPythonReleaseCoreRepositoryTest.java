package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemovedPythonReleaseCoreRepositoryTest {

    private static final List<String> REMOVED_PATHS = List.of(
            ".github/scripts/resolve-release-parameters.py",
            ".github/scripts/test-resolve-release-parameters.py",
            ".github/scripts/check-release-plan.py",
            ".github/scripts/test-check-release-plan.py",
            ".github/scripts/check-version-state.py");

    @Test
    void removedPythonReleaseCoreCannotReturnToProductivePaths()
            throws Exception {
        Path root = findRepositoryRoot();
        for (String removed : REMOVED_PATHS) {
            assertThat(root.resolve(removed))
                    .as("removed Python release-core path %s", removed)
                    .doesNotExist();
        }

        String productive = String.join("\n",
                read(root.resolve("pom.xml")),
                read(root.resolve(".github/scripts/release.sh")),
                read(root.resolve(".github/workflows/ci-cd.yml")),
                read(root.resolve(".github/workflows/deploy-release.yml")),
                read(root.resolve(
                        ".github/workflows/protected-release-main-advance.yml")),
                read(root.resolve(
                        ".github/workflows/prepare-development-version.yml")));
        for (String removed : REMOVED_PATHS) {
            String fileName = Path.of(removed).getFileName().toString();
            assertThat(productive)
                    .as("productive source must not reference %s", fileName)
                    .doesNotContain(fileName);
        }
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
