package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseMetadataRoutingRepositoryTest {

    @Test
    void productiveMetadataTransitionsUseTheVerifiedJavaBoundary()
            throws Exception {
        Path root = findRepositoryRoot();
        String releaseScript = read(root.resolve(".github/scripts/release.sh"));
        String developmentWorkflow = read(root.resolve(
                ".github/workflows/prepare-development-version.yml"));
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
                .doesNotContain("python3 \"$METADATA_HELPER\"");
        assertThat(developmentWorkflow)
                .contains("java -jar \"$RUNNER_TEMP/taxonomy-tooling.jar\" update-release-metadata")
                .contains("--root .")
                .contains("--version \"$NEXT_VERSION\"")
                .doesNotContain("python3 .github/scripts/update-release-metadata.py");
    }

    @Test
    void bothReleaseTransitionsTransformBeforeCoherentStaging()
            throws Exception {
        Path root = findRepositoryRoot();
        String script = read(root.resolve(".github/scripts/release.sh"));

        assertThat(script)
                .contains("git add -- \"${tracked_poms[@]}\" CITATION.cff CITATION.md .zenodo.json codemeta.json")
                .contains("git add -- deploy/helm/taxonomy/Chart.yaml");

        int releaseUpdate = script.indexOf(
                "update_release_metadata \"$RELEASE_VERSION\" --release");
        int releaseStage = script.indexOf(
                "stage_version_metadata", releaseUpdate);
        int nextUpdate = script.indexOf(
                "update_release_metadata \"$NEXT_VERSION\"");
        int nextStage = script.indexOf(
                "stage_version_metadata", nextUpdate);

        assertThat(releaseUpdate).isGreaterThanOrEqualTo(0);
        assertThat(releaseStage).isGreaterThan(releaseUpdate);
        assertThat(nextUpdate).isGreaterThan(releaseStage);
        assertThat(nextStage).isGreaterThan(nextUpdate);
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
