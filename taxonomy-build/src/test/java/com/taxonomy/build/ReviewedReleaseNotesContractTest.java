package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** JUnit-owned contract for version-bound, reviewed GitHub release notes. */
class ReviewedReleaseNotesContractTest {

    @Test
    void acceptsSubstantiveReviewedNotesForTheExactVersion(@TempDir Path root)
            throws Exception {
        Path notes = root.resolve("release_notes.md");
        write(notes, validNotes("1.4.0"));

        Result result = runValidator(root, notes, "1.4.0");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output())
                .contains("Reviewed release notes are valid for Taxonomy 1.4.0")
                .doesNotContain("::error::");
    }

    @Test
    void rejectsNotesWhoseHeadingNamesAnotherVersion(@TempDir Path root)
            throws Exception {
        Path notes = root.resolve("release_notes.md");
        write(notes, validNotes("1.3.1"));

        Result result = runValidator(root, notes, "1.4.0");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("must begin with '# Taxonomy 1.4.0'")
                .contains("# Taxonomy 1.3.1");
    }

    @Test
    void rejectsDynamicallyGeneratedPlaceholderNotes(@TempDir Path root)
            throws Exception {
        Path notes = root.resolve("release_notes.md");
        write(notes, """
                # Taxonomy 1.4.0

                ## Changes

                No closed issues found since v1.3.0

                This text is padded so the fixture is long enough to prove that
                placeholder detection is independent from the minimum-size gate.
                The reviewed release must never publish this generated fallback.
                """);

        Result result = runValidator(root, notes, "1.4.0");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("generated placeholder text")
                .contains("No closed issues found since");
    }

    @Test
    void rejectsImplausiblyShortNotes(@TempDir Path root) throws Exception {
        Path notes = root.resolve("release_notes.md");
        write(notes, "# Taxonomy 1.4.0\n\n## Changes\n\nSmall.\n");

        Result result = runValidator(root, notes, "1.4.0");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("implausibly short");
    }

    @Test
    void releaseTransactionUsesTheCommittedReviewedFileWithoutRegeneration()
            throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        String releaseScript = Files.readString(repositoryRoot.resolve(
                ".github/scripts/release.sh"), StandardCharsets.UTF_8);
        String workflow = Files.readString(repositoryRoot.resolve(
                ".github/workflows/deploy-release.yml"), StandardCharsets.UTF_8);

        assertThat(releaseScript)
                .contains(": \"${RELEASE_NOTES_VALIDATOR:?RELEASE_NOTES_VALIDATOR is required}\"")
                .contains("validate_release_notes()")
                .contains("git ls-files --error-unmatch \"$notes_file\"")
                .contains("git diff --quiet HEAD -- \"$notes_file\"")
                .contains("\"$RELEASE_NOTES_VALIDATOR\"")
                .contains("--notes-file release_notes.md")
                .doesNotContain("generate_release_notes()")
                .doesNotContain("gh issue list")
                .doesNotContain("--generate-notes")
                .doesNotContain("No closed issues found since");

        assertThat(workflow)
                .contains("cp .github/scripts/validate-reviewed-release-notes.sh")
                .contains("$RUNNER_TEMP/validate-reviewed-release-notes.sh")
                .contains("bash -n .github/scripts/validate-reviewed-release-notes.sh")
                .contains("RELEASE_NOTES_VALIDATOR: ${{ runner.temp }}/validate-reviewed-release-notes.sh");
    }

    private static Result runValidator(
            Path workingDirectory,
            Path notes,
            String version) throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        Path validator = repositoryRoot.resolve(
                ".github/scripts/validate-reviewed-release-notes.sh");
        ProcessBuilder builder = new ProcessBuilder("bash", validator.toString())
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("RELEASE_VERSION", version);
        environment.put("RELEASE_NOTES_FILE", notes.toString());

        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Release-notes validator exceeded 10 seconds");
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output);
    }

    private static String validNotes(String version) {
        return """
                # Taxonomy %s

                ## Product highlights

                This reviewed release contains deterministic architecture exports,
                repository-owned verification evidence, documented deployment
                profiles, and a complete traceable project portfolio workflow.

                ## Upgrade notes

                Back up persistent state, deploy only the immutable release image,
                and verify the readiness endpoint before routing production traffic.

                ## Verification boundary

                Publication is allowed only after source, tag, artifacts, image
                digest, vulnerability evidence and Helm manifests agree.
                """.formatted(version);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                    ".github/scripts/release.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private record Result(int exitCode, String output) {
    }
}
