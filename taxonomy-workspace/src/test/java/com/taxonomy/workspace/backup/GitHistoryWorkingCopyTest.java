package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class GitHistoryWorkingCopyTest {
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repo-a", "private-a");
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);
    private static final String DSL = "architecture.taxdsl";
    @TempDir Path temporary;

    @Test void selectedWorkspaceSavedTextIsAnUncommittedOverlayOverItsOriginalBranch() throws Exception {
        String parent, current; var contents = new GitHistoryCaptureTest.Contents();
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            parent = f.commit(Map.of(DSL, bytes("ORIGINAL embedded-secret")));
            current = f.commit(Map.of(DSL, bytes("COMMITTED embedded-secret")));
            f.saved(KEY, "draft", "SAVED embedded-secret", 3, current, 2, null);
            f.saved(KEY, "other", "OTHER SAVED", 8, parent, 1, null);
            try (var source = new DslGitRepository(new DefaultHibernateRepositoryFactory(f.persistence.factory), "ws-private-a")) {
                var repository = source.getGitRepository();
                var ref = repository.updateRef("refs/heads/other"); ref.setExpectedOldObjectId(ObjectId.zeroId()); ref.setNewObjectId(ObjectId.fromString(parent));
                assertThat(ref.update()).isEqualTo(RefUpdate.Result.NEW);
                assertThat(repository.updateRef("HEAD").link("refs/heads/other")).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED);
            }
            var authorization = history();
            try (var working = f.source(ignored -> { throw new AssertionError("History may not project original document bytes"); })
                    .openHistoryWorkingCopy(authorization, KEY, LIMITS, CHECK);
                 var session = f.repositories.open(authorization, KEY, CHECK)) {
                assertThat(working.state().symbolicHead()).isEqualTo("refs/heads/draft");
                assertThat(working.state().workingStates()).containsOnlyKeys("draft");
                var captured = session.captureHistory(Set.of(), working.state().workingStates(), GitHistoryCapture.Limits.defaults(), CHECK);
                assertThat(captured.state().symbolicHead()).isEqualTo("refs/heads/other");
                captured.writeWorkingTree("workspace-a", BackupProfile.REPOSITORY_HISTORY, working, contents);
                assertThat(session.captureHistory(Set.of(), working.state().workingStates(), GitHistoryCapture.Limits.defaults(), CHECK).state())
                        .isEqualTo(captured.state());
            }
        }
        Path repository = materialize(contents);
        assertThat(git(repository, "symbolic-ref", "HEAD").strip()).isEqualTo("refs/heads/draft");
        assertThat(git(repository, "rev-parse", "HEAD").strip()).isEqualTo(current);
        assertThat(git(repository, "rev-parse", "HEAD^1").strip()).isEqualTo(parent);
        assertThat(git(repository, "show", "HEAD:" + DSL)).isEqualTo("COMMITTED embedded-secret");
        assertThat(Files.readString(repository.resolve(DSL))).isEqualTo("SAVED embedded-secret");
        assertThat(git(repository, "status", "--porcelain").strip()).isEqualTo("M " + DSL);
        assertThat(new String(contents.entries.get("data/workspace/git-history-workspace-a.ndjson"), StandardCharsets.UTF_8))
                .contains("\"workingCopy\"", "\"semanticRevision\":3", "\"checkpointRevision\":2");
        git(repository, "fsck", "--full"); git(repository, "add", DSL);
        git(repository, "-c", "user.name=Offline", "-c", "user.email=offline@example.invalid", "commit", "-m", "Continue saved work");
        assertThat(git(repository, "rev-parse", "HEAD^1").strip()).isEqualTo(current);
    }

    @Test void savedUnbornWorkspaceBecomesAnUntrackedFileWithNoInventedGitCommit() throws Exception {
        var contents = new GitHistoryCaptureTest.Contents();
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit(Map.of(DSL, bytes("OTHER BRANCH"))); f.sql("update user_workspace set current_branch='unborn'");
            f.saved(KEY, "unborn", "UNBORN SAVED", 1, null, 0, null);
            try (var working = f.source().openHistoryWorkingCopy(history(), KEY, LIMITS, CHECK);
                 var session = f.repositories.open(history(), KEY, CHECK)) {
                session.captureHistory(Set.of(), working.state().workingStates(), GitHistoryCapture.Limits.defaults(), CHECK)
                        .writeWorkingTree("workspace-a", BackupProfile.REPOSITORY_HISTORY, working, contents);
            }
        }
        Path repository = materialize(contents);
        assertThat(git(repository, "symbolic-ref", "HEAD").strip()).isEqualTo("refs/heads/unborn");
        assertThat(git(repository, "ls-files")).isEmpty();
        assertThat(git(repository, "status", "--porcelain").strip()).isEqualTo("?? " + DSL);
        assertThat(Files.readString(repository.resolve(DSL))).isEqualTo("UNBORN SAVED"); git(repository, "fsck", "--full");
    }

    @ParameterizedTest @ValueSource(strings = {"content", "revision", "closed", "moved-head"})
    void changedOrClosedSavedEvidenceCannotProduceACompleteWorkingTree(String change) throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            String current = f.commit(Map.of(DSL, bytes("COMMITTED"))); f.saved(KEY, "draft", "SAVED", 3, current, 2, null);
            try (var working = f.source().openHistoryWorkingCopy(history(), KEY, LIMITS, CHECK);
                 var session = f.repositories.open(history(), KEY, CHECK)) {
                var captured = session.captureHistory(Set.of(), working.state().workingStates(), GitHistoryCapture.Limits.defaults(), CHECK);
                switch (change) {
                    case "content" -> f.sql("update editor_workspace set dsl='1:CHANGED'");
                    case "revision" -> f.sql("update editor_workspace set semantic_revision=4");
                    case "closed" -> working.close();
                    case "moved-head" -> f.commit(Map.of(DSL, bytes("MOVED")));
                }
                assertThatThrownBy(() -> captured.writeWorkingTree("workspace-a", BackupProfile.REPOSITORY_HISTORY, working, new GitHistoryCaptureTest.Contents()))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test void currentAuthorizationCannotObtainUnprojectedHistoryWorkingBytes() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit(Map.of(DSL, bytes("CURRENT embedded-secret")));
            var current = authorized(new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()),
                    new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
            assertThatThrownBy(() -> f.source().openHistoryWorkingCopy(current, KEY, LIMITS, CHECK)).isInstanceOf(IOException.class);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static AuthorizedBackupRequest history() { return authorized(new BackupRequest(BackupProfile.REPOSITORY_HISTORY,
            new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()), new BackupTime.History(), GitRepresentation.WORKTREE, SecretsSelection.EXCLUDE)); }
    private static AuthorizedBackupRequest authorized(BackupRequest request) { return new AuthorizedBackupRequest(request, PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class)); }
    private Path materialize(GitHistoryCaptureTest.Contents contents) throws IOException {
        Path root = Files.createDirectory(temporary.resolve("export"));
        for (var entry : contents.entries.entrySet()) {
            Path path = root.resolve(entry.getKey()).normalize(); assertThat(path.startsWith(root)).isTrue();
            if (entry.getKey().endsWith("/")) Files.createDirectories(path);
            else { Files.createDirectories(path.getParent()); Files.write(path, entry.getValue()); }
        }
        return root.resolve("repositories/workspace-a");
    }
    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0"); builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        var process = builder.start(); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).describedAs(String.join(" ", command) + "\n" + output).isZero(); return output;
    }
}
