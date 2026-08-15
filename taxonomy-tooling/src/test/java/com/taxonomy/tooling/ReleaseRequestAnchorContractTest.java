package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseRequestAnchorContractTest {

    @Test
    void acceptsOneRevisionChangingOnlyTheRequestAfterItsExactParent(
            @TempDir Path root) throws Exception {
        String parent = initializeRepository(root);
        commitRequest(root, parent, 8, false);
        Map<String, Object> request = ReleaseParametersResolver.readRequest(
                root.resolve(".github/release-request.json"));

        ReleaseParametersResolver.validateReleaseRequestAnchor(
                root, root.resolve(".github/release-request.json"), request);
    }

    @Test
    void rejectsARequestNotAnchoredToItsImmediateFirstParent(
            @TempDir Path root) throws Exception {
        initializeRepository(root);
        commitRequest(root, "0".repeat(40), 8, false);
        Map<String, Object> request = ReleaseParametersResolver.readRequest(
                root.resolve(".github/release-request.json"));

        assertThatThrownBy(() -> ReleaseParametersResolver
                .validateReleaseRequestAnchor(
                        root,
                        root.resolve(".github/release-request.json"),
                        request))
                .hasMessageContaining("requested_after_commit must equal")
                .hasMessageContaining("first parent");
    }

    @Test
    void rejectsAReleaseCommitContainingAnyOtherPath(
            @TempDir Path root) throws Exception {
        String parent = initializeRepository(root);
        commitRequest(root, parent, 8, true);
        Map<String, Object> request = ReleaseParametersResolver.readRequest(
                root.resolve(".github/release-request.json"));

        assertThatThrownBy(() -> ReleaseParametersResolver
                .validateReleaseRequestAnchor(
                        root,
                        root.resolve(".github/release-request.json"),
                        request))
                .hasMessageContaining("release request commit must change only")
                .hasMessageContaining("README.md");
    }

    @Test
    void rejectsARequestRevisionThatDoesNotAdvanceExactlyOnce(
            @TempDir Path root) throws Exception {
        String parent = initializeRepository(root);
        commitRequest(root, parent, 9, false);
        Map<String, Object> request = ReleaseParametersResolver.readRequest(
                root.resolve(".github/release-request.json"));

        assertThatThrownBy(() -> ReleaseParametersResolver
                .validateReleaseRequestAnchor(
                        root,
                        root.resolve(".github/release-request.json"),
                        request))
                .hasMessageContaining("request_revision must advance from 7 to 8")
                .hasMessageContaining("got 9");
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
        TestGit.run(root, "init");
        TestGit.run(root, "branch", "-M", "main");
        TestGit.run(root, "config", "user.name", "Release Contract Test");
        TestGit.run(root, "config", "user.email",
                "release-contract@example.invalid");
        TestGit.run(root, "add", ".");
        TestGit.run(root, "commit", "-m", "Verified release base");
        return TestGit.run(root, "rev-parse", "HEAD").strip();
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
        TestGit.run(root, "add", ".");
        TestGit.run(root, "commit", "-m", "Request release dry run");
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

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
