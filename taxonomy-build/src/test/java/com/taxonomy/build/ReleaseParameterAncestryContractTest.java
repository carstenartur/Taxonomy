package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Process-level contract for staged release-tag ancestry validation. */
class ReleaseParameterAncestryContractTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsAnAlreadyAdvancedDevelopmentTreeWhoseReleaseTagDiverged() throws Exception {
        Path repository = createDivergedReleaseRepository();

        ProcessResult result = runResolver(repository);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr())
                .contains("staged release tag v1.3.1 is not an ancestor")
                .contains("repair release ancestry before publication");
        assertThat(Files.exists(repository.resolve("github-output"))).isFalse();
    }

    @Test
    void acceptsTheSameDevelopmentTreeAfterAHistoryOnlyAncestryMerge() throws Exception {
        Path repository = createDivergedReleaseRepository();
        run(repository, "git", "merge", "-s", "ours", "--no-ff", "release/1.3.1",
                "-m", "Repair v1.3.1 ancestry");

        ProcessResult result = runResolver(repository);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(repository.resolve("github-output"))
                .content(StandardCharsets.UTF_8)
                .contains(
                        "release_version=1.3.1",
                        "next_development_version=1.3.2-SNAPSHOT",
                        "skip_tests=false",
                        "dry_run=false",
                        "resume_staged_release=false");
        assertThat(run(repository, "git", "merge-base", "--is-ancestor",
                "v1.3.1", "HEAD").exitCode()).isZero();
        assertThat(readProjectVersion(repository)).isEqualTo("1.3.2-SNAPSHOT");
    }

    private Path createDivergedReleaseRepository() throws Exception {
        Path repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        run(repository, "git", "init");
        run(repository, "git", "config", "user.name", "Release Contract");
        run(repository, "git", "config", "user.email", "release-contract@taxonomy.local");
        run(repository, "git", "checkout", "-b", "main");

        writePom(repository, "1.3.1-SNAPSHOT");
        commitAll(repository, "Development base");

        run(repository, "git", "checkout", "-b", "release/1.3.1");
        writePom(repository, "1.3.1");
        commitAll(repository, "Release version 1.3.1");
        run(repository, "git", "tag", "v1.3.1");

        run(repository, "git", "checkout", "main");
        writePom(repository, "1.3.2-SNAPSHOT");
        commitAll(repository, "Prepare next development version 1.3.2-SNAPSHOT");
        writeReleaseRequest(repository);
        return repository;
    }

    private ProcessResult runResolver(Path repository) throws Exception {
        Path output = repository.resolve("github-output");
        Files.deleteIfExists(output);
        return run(
                repository,
                Map.of(
                        "EVENT_NAME", "push",
                        "RELEASE_REQUEST_PATH", repository.resolve("release-request.json").toString(),
                        "GITHUB_OUTPUT", output.toString()),
                "python3",
                repositoryRoot().resolve(".github/scripts/resolve-release-parameters.py").toString());
    }

    private static void writeReleaseRequest(Path repository) throws IOException {
        Files.writeString(
                repository.resolve("release-request.json"),
                """
                        {
                          "release_version": "1.3.1",
                          "next_development_version": "1.3.2-SNAPSHOT",
                          "skip_tests": false,
                          "dry_run": false
                        }
                        """,
                StandardCharsets.UTF_8);
    }

    private static void writePom(Path repository, String version) throws IOException {
        Files.writeString(
                repository.resolve("pom.xml"),
                """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>com.taxonomy</groupId>
                          <artifactId>release-contract</artifactId>
                          <version>%s</version>
                        </project>
                        """.formatted(version),
                StandardCharsets.UTF_8);
    }

    private static String readProjectVersion(Path repository) throws IOException {
        String pom = Files.readString(repository.resolve("pom.xml"), StandardCharsets.UTF_8);
        int start = pom.indexOf("<version>") + "<version>".length();
        return pom.substring(start, pom.indexOf("</version>", start));
    }

    private static void commitAll(Path repository, String message) throws Exception {
        run(repository, "git", "add", "pom.xml");
        run(repository, "git", "commit", "-m", message);
    }

    private static ProcessResult run(Path directory, String... command) throws Exception {
        return run(directory, Map.of(), command);
    }

    private static ProcessResult run(
            Path directory,
            Map<String, String> environment,
            String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        ProcessResult result = new ProcessResult(exitCode, stdout, stderr);
        if (exitCode != 0 && !isExpectedFailure(command)) {
            throw new AssertionError("Command failed: " + String.join(" ", command)
                    + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
        }
        return result;
    }

    private static boolean isExpectedFailure(String[] command) {
        List<String> arguments = new ArrayList<>(List.of(command));
        return arguments.contains("resolve-release-parameters.py")
                || arguments.contains("--is-ancestor");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            ".github/scripts/resolve-release-parameters.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Taxonomy repository root");
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
