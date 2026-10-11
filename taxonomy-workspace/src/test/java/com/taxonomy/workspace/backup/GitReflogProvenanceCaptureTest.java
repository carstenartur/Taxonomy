package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitReflogProvenanceCaptureTest {
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repo-a", "private-a");
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitReflogProvenance.Origin ORIGIN = new GitReflogProvenance.Origin("a".repeat(64),
            new BackupRepositoryKey("original-repo", "original-workspace"), "original-repository");

    @Test void laterBackupPreservesOriginalEventsAndTheirOtherwiseUnreachableObjects() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            f.commit("ws-private-a", "LIVE"); String orphan = f.orphan("ws-private-a", "ORIGINAL HISTORY BODY");
            var events = events(orphan); retain(f, events);
            try (var session = f.base.repositories.open(authorization(), KEY, CHECK)) {
                var history = session.captureHistory(Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
                assertThat(history.state().requiredCommits()).contains(orphan);
                assertThat(history.importedReflogs()).containsExactlyElementsOf(events);
                assertThat(history.reflogs()).noneMatch(entry -> entry.message().equals("ORIGINAL IMPORTED EVENT"));
                var sink = new Sink(); history.write("next-repository", GitRepresentation.BUNDLE, sink);
                var lines = new String(sink.entries.get("data/workspace/git-history-next-repository.ndjson"), StandardCharsets.UTF_8).lines().toList();
                var preserved = new ArrayList<GitReflogProvenance.Event>();
                for (var line : lines) {
                    var json = PortableRows.json().readTree(line);
                    if (json.path("recordType").asText().equals("original-reflog"))
                        preserved.add(PortableRows.json().treeToValue(json.get("event"), GitReflogProvenance.Event.class));
                }
                assertThat(preserved).containsExactlyElementsOf(events);
                var document = history.documents(KEY, CHECK).stream().filter(entry -> entry.commit().equals(orphan)).findFirst().orElseThrow();
                var content = new ByteArrayOutputStream(); history.copyDocument(document, 1_000, content, CHECK);
                assertThat(content.toString(StandardCharsets.UTF_8)).isEqualTo("ORIGINAL HISTORY BODY");
            }
        }
    }

    @Test void originalEvidenceMovementAfterCaptureCannotBeHiddenByUnchangedLiveRefs() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            String commit = f.commit("ws-private-a", "BODY"); retain(f, events(commit));
            try (var session = f.base.repositories.open(authorization(), KEY, CHECK)) {
                var history = session.captureHistory(Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
                f.base.sql("delete from workspace_git_reflog_provenance");
                var sink = new Sink();
                assertThatThrownBy(() -> history.write("next", GitRepresentation.BARE, sink)).isInstanceOf(IOException.class);
                assertThat(sink.entries).isEmpty();
            }
        }
    }

    @Test void originalAndLiveEventsShareOneBoundedCaptureBudget() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            String commit = f.commit("ws-private-a", "BODY");
            try (var session = f.base.repositories.open(authorization(), KEY, CHECK)) {
                int live = session.captureHistory(Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK).reflogs().size();
                retain(f, events(commit));
                var bound = new GitHistoryCapture.Limits(100, 100, 1_000_000, 2_000_000, live + 1);
                assertThatThrownBy(() -> session.captureHistory(Set.of(), Map.of(), bound, CHECK)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void danglingOriginalObjectEvidencePreventsAClaimedCompleteBackup() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            f.commit("ws-private-a", "BODY"); retain(f, events("1".repeat(40)));
            try (var session = f.base.repositories.open(authorization(), KEY, CHECK)) {
                assertThatThrownBy(() -> session.captureHistory(Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK))
                        .isInstanceOf(IOException.class);
            }
        }
    }
    private static List<GitReflogProvenance.Event> events(String id) {
        var event = new GitHistoryCapture.Reflog("refs/heads/deleted", "0".repeat(40), id, "Original Author", "original@example.invalid",
                315630245, 120, "ORIGINAL IMPORTED EVENT");
        return GitReflogProvenance.original(ORIGIN, List.of(event, event));
    }
    private static void retain(WorkspaceGitHistoryReferencesTest.Fixture fixture, List<GitReflogProvenance.Event> events) throws Exception {
        try (var transaction = fixture.base.database.getConnection()) {
            transaction.setAutoCommit(false);
            new GitReflogProvenanceStore(fixture.base.database).retain(transaction, KEY, "ws-private-a", events, CHECK); transaction.commit();
        }
    }
    private static AuthorizedBackupRequest authorization() {
        return new AuthorizedBackupRequest(new BackupRequest(BackupProfile.REPOSITORY_HISTORY, new BackupScope.Workspace("repo-a", "private-a"),
                new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE), PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class));
    }
    private static final class Sink implements ComponentSink {
        final Map<String, byte[]> entries = new HashMap<>();
        public BackupEntry write(String path, InputStream input) throws IOException {
            byte[] bytes = input.readAllBytes(); entries.put(path, bytes); return new BackupEntry(path, bytes.length, GitReflogProvenance.digest(bytes));
        }
        public BackupEntry writeGenerated(String path, EntryWriter writer) throws IOException {
            var output = new ByteArrayOutputStream(); writer.write(output); return write(path, new ByteArrayInputStream(output.toByteArray()));
        }
    }
}
