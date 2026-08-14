package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseMetadataRoutingRepositoryTest {

    @Test
    void everyProductiveMetadataTransitionUsesJavaTooling() throws Exception {
        Path root = findRepositoryRoot();
        String releaseScript = read(root.resolve(".github/scripts/release.sh"));
        String releaseWorkflow = read(
                root.resolve(".github/workflows/deploy-release.yml"));
        String developmentWorkflow = read(
                root.resolve(".github/workflows/prepare-development-version.yml"));
        String toolingCli = read(root.resolve(
                "taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java"));

        assertThat(toolingCli)
                .contains("case \"update-release-metadata\"")
                .contains("ReleaseMetadataUpdater.update");
        assertThat(releaseScript)
                .contains("update_release_metadata()")
                .contains("java -jar \"$TOOLING_JAR\" update-release-metadata")
                .contains("update_release_metadata \"$RELEASE_VERSION\" --release")
                .contains("update_release_metadata \"$NEXT_VERSION\"")
                .doesNotContain("METADATA_HELPER")
                .doesNotContain("update-release-metadata.py");
        assertThat(releaseWorkflow)
                .contains("TOOLING_JAR: ${{ runner.temp }}/taxonomy-tooling.jar")
                .doesNotContain("METADATA_HELPER")
                .doesNotContain("update-release-metadata.py");
        assertThat(developmentWorkflow)
                .contains("update-release-metadata")
                .contains("--version \"$NEXT_VERSION\"")
                .doesNotContain("update-release-metadata.py");
        assertThat(root.resolve(".github/scripts/update-release-metadata.py"))
                .doesNotExist();
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
                            "taxonomy-tooling/pom.xml"))
                    && Files.isRegularFile(current.resolve("CITATION.cff"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
