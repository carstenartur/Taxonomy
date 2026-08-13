package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** JUnit-owned contract for push-triggered release-request ancestry. */
class ReleaseRequestAnchorContractTest {

    @Test
    void acceptsOneRevisionChangingOnlyTheRequestAfterItsExactParent(
            @TempDir Path root) throws Exception {
        String parent = initializeRepository(root);
        commitRequest(root, parent, 8, false);

        AdapterResult result = runAdapter(root);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).doesNotContain("::error::");
        assertThat(Files.readAllLines(
                root.resolve("github-output"), StandardCharsets.UTF_8))
                .containsExactly(
                        "release_version=1.4.0",
                        "next_development_version=1.4.1-SNAPSHOT",
                        "skip_tests=false",
                        "dry_run=true",
                        "resume_staged_release=false");
    }

    @Test
    void rejectsARequestNotAnchoredToItsImmediateFirstParent(
            @TempDir Path root) throws Exception {
        initializeRepository(root);
        commitRequest(root, "0".repeat(40), 8, false);

        AdapterResult result = runAdapter(root);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("requested_after_commit must equal")
                .contains("first parent");
    }

    @Test
    void rejectsAReleaseCommitContainingAnyOtherPath(
            @TempDir Path root) throws Exception {
        String parent = initializeRepository(root);
        commitRequest(root, parent, 8, true);

        AdapterResult result = runAdapter(root);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("release request commit must change only")
                .contains("README.md");
    }

    @Test
    void rejectsARequestRevisionThatDoesNotAdvanceExactlyOnce(
            @TempDir Path root) throws Exception {
        String parent = initializeRepository(root);
        commitRequest(root, parent, 9, false);

        AdapterResult result = runAdapter(root);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("request_revision must advance from 7 to 8")
                .contains("got 9");
    }

    private static String initializeRepository(Path root) throws Exception {
        write(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>1.4.0-SNAPSHOT</version>
                </project>
                """);
        write(root.resolve(".github/release-request.json"), request(
                "f".repeat(40), 7));

        git(root, "init");
        git(root, "branch", "-M", "main");
        git(root, "config", "user.name", "Release Contract Test");
        git(root, "config", "user.email", "release-contract@example.invalid");
        git(root, "add", ".");
        git(root, "commit", "-m", "Verified release base");
        return git(root, "rev-parse", "HEAD").strip();
    }

    private static void commitRequest(
            Path root,
            String requestedAfter,
            int revision,
            boolean includeOtherPath) throws Exception {
        write(root.resolve(".github/release-request.json"), request(
                requestedAfter, revision));
        if (includeOtherPath) {
            write(root.resolve("README.md"), "unreviewed release content\n");
        }
        git(root, "add", ".");
        git(root, "commit", "-m", "Request release dry run");
    }

    private static AdapterResult runAdapter(Path fixtureRoot) throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        Path output = fixtureRoot.resolve("github-output");
        List<String> command = new ArrayList<>(List.of(
                "python3",
                repositoryRoot.resolve(
                        ".github/scripts/resolve-release-parameters.py").toString()));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(fixtureRoot.toFile())
                .redirectErrorStream(true);
        builder.environment().put("EVENT_NAME", "push");
        builder.environment().put("GITHUB_OUTPUT", output.toString());
        builder.environment().put(
                "RELEASE_REQUEST_PATH", ".github/release-request.json");

        Process process = builder.start();
        String text = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new AdapterResult(process.waitFor(), text);
    }

    private static String git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertThat(exitCode).as("%s%n%s", command, output).isZero();
        return output;
    }

    private static String request(String requestedAfter, int revision) {
        return """
                {
                  "release_version": "1.4.0",
                  "next_development_version": "1.4.1-SNAPSHOT",
                  "skip_tests": false,
                  "dry_run": true,
                  "resume_staged_release": false,
                  "request_revision": %d,
                  "requested_after_commit": "%s"
                }
                """.formatted(revision, requestedAfter);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                    ".github/scripts/resolve-release-parameters.py"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
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

    private record AdapterResult(int exitCode, String output) {
    }
}
