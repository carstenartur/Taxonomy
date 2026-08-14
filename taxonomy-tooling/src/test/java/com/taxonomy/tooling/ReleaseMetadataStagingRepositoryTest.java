package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseMetadataStagingRepositoryTest {

    @Test
    void releaseAndNextSnapshotTransformBeforeCoherentMetadataStaging()
            throws Exception {
        Path root = findRepositoryRoot();
        String script = Files.readString(
                root.resolve(".github/scripts/release.sh"),
                StandardCharsets.UTF_8);

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
