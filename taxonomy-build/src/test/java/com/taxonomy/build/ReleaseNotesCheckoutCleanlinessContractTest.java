package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevent reviewed notes from leaving the advanced-main checkout dirty. */
class ReleaseNotesCheckoutCleanlinessContractTest {

    @Test
    void releaseScriptRestoresTheCheckoutAfterGitHubConsumesReviewedNotes()
            throws Exception {
        String script = Files.readString(findRepositoryRoot().resolve(
                ".github/scripts/release.sh"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("restore_release_notes_checkout()")
                .contains("git restore --source=HEAD --worktree -- release_notes.md");

        String draftCreationBlock = between(
                script,
                "if [[ \"$DRY_RUN\" != \"true\" && \"$STATE\" == \"tagged\" ]]; then",
                "if [[ \"$DRY_RUN\" != \"true\" && \"$STATE\" == \"draft\" ]]; then");
        assertOrdered(
                draftCreationBlock,
                "materialize_release_notes",
                "gh release create \"$TAG_NAME\"",
                "restore_release_notes_checkout",
                "STATE=draft");

        String directPublicationBlock = between(
                script,
                "if [[ \"$DRY_RUN\" != \"true\" && \"$STATE\" == \"draft\" ]]; then",
                "if [[ \"$DRY_RUN\" != \"true\" ]]; then");
        assertOrdered(
                directPublicationBlock,
                "materialize_release_notes",
                "gh release edit \"$TAG_NAME\"",
                "restore_release_notes_checkout",
                "STATE=published");
    }

    @Test
    void restoreMakesTheSubsequentTagCheckoutPossibleWhenNotesDiverge(
            @TempDir Path root) throws Exception {
        initializeRepository(root);
        String releaseNotes = validNotes("1.4.0", "release");
        write(root.resolve("release_notes.md"), releaseNotes);
        git(root, "add", "release_notes.md");
        git(root, "commit", "-m", "Review release notes");
        String releaseCommit = git(root, "rev-parse", "HEAD").output().trim();

        write(root.resolve("release_notes.md"), validNotes("1.4.1", "next"));
        git(root, "commit", "-am", "Prepare next development notes");
        String nextCommit = git(root, "rev-parse", "HEAD").output().trim();

        Result materialized = runValidator(root, releaseCommit);
        assertThat(materialized.exitCode()).as(materialized.output()).isZero();
        assertThat(git(root, "rev-parse", "HEAD").output().trim())
                .isEqualTo(nextCommit);
        assertThat(git(root, "status", "--porcelain").output())
                .contains(" M release_notes.md");

        git(root, "restore", "--source=HEAD", "--worktree", "--", "release_notes.md");
        assertThat(git(root, "status", "--porcelain").output()).isEmpty();

        git(root, "checkout", "--detach", releaseCommit);
        assertThat(Files.readString(
                root.resolve("release_notes.md"), StandardCharsets.UTF_8))
                .isEqualTo(releaseNotes);
    }

    private static Result runValidator(Path workingDirectory, String releaseCommit)
            throws Exception {
        Path validator = findRepositoryRoot().resolve(
                ".github/scripts/validate-reviewed-release-notes.sh");
        ProcessBuilder builder = new ProcessBuilder("bash", validator.toString())
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("RELEASE_VERSION", "1.4.0");
        environment.put("RELEASE_NOTES_COMMIT", releaseCommit);
        environment.put("RELEASE_NOTES_FILE", "release_notes.md");
        return run(builder, "Release-notes validator");
    }

    private static void assertOrdered(String text, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int current = text.indexOf(needle);
            assertThat(current)
                    .as("position of %s in release block", needle)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    private static String between(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        int endIndex = text.indexOf(end, startIndex);
        if (startIndex < 0 || endIndex < 0) {
            throw new AssertionError(
                    "Unable to locate source block between " + start + " and " + end);
        }
        return text.substring(startIndex, endIndex);
    }

    private static void initializeRepository(Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "Taxonomy release contract");
        git(root, "config", "user.email", "release-contract@example.invalid");
    }

    private static Result git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        Result result = run(
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true),
                "Git fixture command " + command);
        if (result.exitCode() != 0) {
            throw new AssertionError(
                    "Git fixture command failed: " + command + "\n" + result.output());
        }
        return result;
    }

    private static Result run(ProcessBuilder builder, String description)
            throws Exception {
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError(description + " exceeded 10 seconds");
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.exitValue(), output);
    }

    private static String validNotes(String version, String marker) {
        return """
                # Taxonomy %s

                ## Product highlights

                This substantive reviewed %s fixture documents deterministic exports,
                repository-owned evidence, deployment profiles and traceable workflows.

                ## Verification boundary

                Publication remains bound to source, tag, artifacts, image digest,
                vulnerability evidence and Helm manifests for this fixture.
                """.formatted(version, marker);
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
