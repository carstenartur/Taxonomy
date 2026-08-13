package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** JUnit-owned repository contract for version-bound reviewed release notes. */
class ReviewedReleaseNotesContractTest {

    @Test
    void currentReviewedNotesAreVersionBoundAndSubstantive() throws Exception {
        Path root = repositoryRoot();
        String notes = Files.readString(
                root.resolve("release_notes.md"), StandardCharsets.UTF_8);

        assertThat(notes)
                .startsWith("# Taxonomy 1.4.0\n")
                .contains("## Product highlights")
                .contains("## Verification boundary")
                .doesNotContain("No closed issues found since")
                .doesNotContain("Initial release");
        assertThat(notes.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(200);
    }

    @Test
    void validatorRejectsWrongVersionsPlaceholdersAndChangedBodies()
            throws Exception {
        Path root = repositoryRoot();
        String validator = Files.readString(root.resolve(
                ".github/scripts/validate-reviewed-release-notes.sh"),
                StandardCharsets.UTF_8);

        assertThat(validator)
                .contains("expected_heading=\"# Taxonomy ${RELEASE_VERSION}\"")
                .contains("No closed issues found since")
                .contains("Initial release")
                .contains("RELEASE_BODY_JSON_FILE")
                .contains("GitHub Release body differs")
                .contains("sha256(expected)")
                .contains("sha256(actual)");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                    ".github/scripts/validate-reviewed-release-notes.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
