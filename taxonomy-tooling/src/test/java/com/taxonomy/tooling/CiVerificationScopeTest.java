package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CiVerificationScopeTest {
    @TempDir Path root;

    @Test void internalProseAndPluginReadmeUseDocumentationScope() throws Exception {
        String base = initialize();
        write("docs/internal/tasks.md", "# Tasks\n\nUpdated prose.\n");
        write("plugins/example/README.md", "# Example\n");
        String head = commit();
        var decision = CiVerificationScope.classify(root, "pull_request", false, base, head);
        assertEquals("documentation", decision.scope());
        assertEquals(List.of("docs/internal/tasks.md", "plugins/example/README.md"), decision.paths());
        assertEquals(GitSupport.require(root, "rev-parse", "HEAD^{tree}").strip(), decision.sourceTree());
    }

    @Test void mixedChangesUserHelpAndUnknownPathsRequireFullVerification() throws Exception {
        String base = initialize();
        for (String path : List.of("docs/user-guide.md", "docs/images/a.svg", "pom.xml",
                ".github/workflows/ci-cd.yml", "taxonomy-app/src/main/resources/index.html",
                "docs/dev/OBSERVABILITY_PERFORMANCE.md", "README.md")) {
            write(path, "changed\n");
            String head = commit();
            assertEquals("full", CiVerificationScope.classify(root, "pull_request", false, base, head).scope(), path);
            base = head;
        }
        write("docs/internal/tasks.md", "# Prose\n");
        write("taxonomy-app/src/main/java/App.java", "class App {}\n");
        assertEquals("full", CiVerificationScope.classify(root, "pull_request", false, base, commit()).scope());
    }

    @Test void emptyDiffAndNonPullRequestEventsAlwaysRemainFull() throws Exception {
        String base = initialize();
        assertEquals("full", CiVerificationScope.classify(root, "pull_request", false, base, base).scope());
        write("docs/dev/notes.md", "# Notes\n");
        String head = commit();
        for (String event : List.of("push", "workflow_dispatch", "schedule", "pull_request_target"))
            assertEquals("full", CiVerificationScope.classify(root, event, true, base, head).scope());
        assertEquals("full", CiVerificationScope.classify(root, "pull_request", false, "missing", head).scope());
        assertEquals("draft", CiVerificationScope.classify(root, "pull_request", true, base, head).scope());
    }

    @Test void deletionsRenamesAndSymlinksCannotEnterTheProseFastPath() throws Exception {
        initialize();
        write("docs/dev/old.md", "# Old\n");
        String base = commit();
        Files.delete(root.resolve("docs/dev/old.md"));
        write("docs/dev/new.md", "# Old\n");
        assertEquals("full", CiVerificationScope.classify(root, "pull_request", false, base, commit()).scope());
        base = GitSupport.require(root, "rev-parse", "HEAD").strip();
        Files.createSymbolicLink(root.resolve("docs/dev/link.md"), Path.of("../../README.md"));
        assertEquals("full", CiVerificationScope.classify(root, "pull_request", false, base, commit()).scope());
    }

    @Test void exactTestedCommitAndSavedScopeAreValidated() throws Exception {
        String base = initialize();
        write("docs/dev/notes.md", "# Notes\n");
        String head = commit();
        var decision = CiVerificationScope.classify(root, "pull_request", false, base, head);
        Path file = root.resolve("target/scope.json");
        CiVerificationScope.write(file, decision);
        assertEquals(decision, CiVerificationScope.readAndVerify(root, file, head));
        assertThrows(IllegalArgumentException.class, () -> CiVerificationScope.readAndVerify(root, file, base));
        Files.writeString(file, Files.readString(file).replace("docs/dev/notes.md", "docs/dev/other.md"));
        assertThrows(IllegalArgumentException.class, () -> CiVerificationScope.readAndVerify(root, file, head));
    }

    @Test void gateRejectsFailedPlannerUnexpectedSkipsAndDrafts() {
        var full = new LinkedHashMap<String, String>();
        full.put("change-scope", "success");
        for (String lane : CiVerificationScope.FULL_LANES) full.put(lane, "success");
        full.put("documentation", "skipped");
        assertDoesNotThrow(() -> CiVerificationScope.requireLanes("full", full));
        full.put("core", "skipped");
        assertThrows(IllegalArgumentException.class, () -> CiVerificationScope.requireLanes("full", full));
        var docs = new LinkedHashMap<String, String>();
        docs.put("change-scope", "success");
        for (String lane : CiVerificationScope.FULL_LANES) docs.put(lane, lane.equals("application") ? "success" : "skipped");
        docs.put("documentation", "success");
        assertDoesNotThrow(() -> CiVerificationScope.requireLanes("documentation", docs));
        docs.put("change-scope", "failure");
        assertThrows(IllegalArgumentException.class, () -> CiVerificationScope.requireLanes("documentation", docs));
        assertThrows(IllegalArgumentException.class, () -> CiVerificationScope.requireLanes("draft", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> CiVerificationScope.requireLanes("unknown", full));
    }

    private String initialize() throws Exception {
        GitSupport.require(root, "init", "--quiet");
        GitSupport.require(root, "config", "user.name", "CI fixture");
        GitSupport.require(root, "config", "user.email", "ci@example.invalid");
        write("README.md", "# Fixture\n");
        return commit();
    }

    private void write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String commit() {
        GitSupport.require(root, "add", ".");
        GitSupport.require(root, "commit", "--quiet", "-m", "fixture");
        return GitSupport.require(root, "rev-parse", "HEAD").strip();
    }
}
