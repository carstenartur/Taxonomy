package com.taxonomy.backup;

import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.preferences.backup.PreferencesBackupContributor;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.taxonomy.backup.CurrentStateExportIT.*;
import static org.assertj.core.api.Assertions.*;

class PreferencesExportIT {
    private static final String PATH = "data/application/preferences.json";

    @Test void installationCurrentExportsTypedSettingsWithoutPriorValuesOrCredentials() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            repository.commit("{\"dsl.project-name\":\"OLD-PRIVATE-NAME\",\"dsl.remote.token\":\"OLD-TOKEN\"}", "old-author", "OLD-MESSAGE");
            String current = """
                    {"llm.rpm":"12","llm.timeout.seconds":60,"rate-limit.per-minute":20,
                     "analysis.min-relevance-score":70,"dsl.default-branch":"draft","dsl.project-name":"Aktueller Stand",
                     "dsl.auto-save.interval-seconds":5,"limits.max-business-text":6000,
                     "limits.max-architecture-nodes":70,"limits.max-export-nodes":300,"diagram.policy":"defaultImpact",
                     "dsl.remote.url":"https://user:URL-PASSWORD@git.example/private?token=QUERY-SECRET",
                     "dsl.remote.token":"RAW-TOKEN-1234","dsl.remote.push-on-commit":"true"}
                    """;
            String head = repository.commit(current, "current-author", "CURRENT-MESSAGE");
            var output = new Contents();
            var contributor = new PreferencesBackupContributor(repository);
            contributor.write(snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output);
            assertThat(output.entries.keySet()).containsExactly(PATH);
            var document = PortableRows.json().readTree(output.entries.get(PATH));
            assertThat(document.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(document.path("selection").asText()).isEqualTo("CURRENT_STATE");
            assertThat(document.path("sourceCommit").asText()).isEqualTo(head);
            assertThat(document.path("settings").path("llmRequestsPerMinute").asInt()).isEqualTo(12);
            assertThat(document.path("settings").path("maximumBusinessText").asInt()).isEqualTo(6000);
            assertThat(document.path("settings").path("projectName").asText()).isEqualTo("Aktueller Stand");
            assertThat(document.path("remote").path("configured").asBoolean()).isTrue();
            assertThat(document.path("remote").path("credentialPresent").asBoolean()).isTrue();
            assertThat(document.path("remote").path("sourcePushOnCommit").asBoolean()).isTrue();
            assertThat(document.path("restorePolicy").asText()).isEqualTo("MANUAL_REVIEW_REQUIRED");
            assertThat(output.text()).doesNotContain("OLD-PRIVATE-NAME", "OLD-TOKEN", "OLD-MESSAGE", "CURRENT-MESSAGE",
                    "current-author", "URL-PASSWORD", "QUERY-SECRET", "RAW-TOKEN", "****1234", "git.example");
            assertThat(repository.readHead()).isEqualTo(current);
            assertThat(repository.getHistory()).hasSize(2);
            assertThat(contributor.componentId()).isEqualTo(new BackupComponentId("application"));
            assertThat(contributor.schemaVersion()).isEqualTo(1);
            assertThat(contributor.categories()).containsExactly("storage.git.preferences");
            assertThat(contributor.omissions(BackupProfile.INSTALLATION_CURRENT)).anyMatch(value -> value.contains("history"));
        }
    }

    @Test void workspaceAndSelectedVersionExportsNeverInspectGlobalAdministrativePreferences() throws Exception {
        try (var repository = new PreferencesGitRepository() {
            @Override public Repository getGitRepository() { throw new AssertionError("Global preferences inspected from scoped export"); }
        }) {
            for (var profile : List.of(BackupProfile.CURRENT_STATE, BackupProfile.SELECTED_VERSION, BackupProfile.REPOSITORY_HISTORY)) {
                var contributor = new PreferencesBackupContributor(repository);
                var output = new Contents();
                contributor.write(snapshot(profile, new BackupScope.Workspace("repo-a", "private-a")), output);
                var document = PortableRows.json().readTree(output.entries.get(PATH));
                assertThat(document.path("selection").asText()).isEqualTo("OUTSIDE_SCOPE");
                assertThat(document.path("settings").isNull()).isTrue();
                assertThat(document.path("sourceCommit").isNull()).isTrue();
                assertThat(contributor.omissions(profile)).anyMatch(value -> value.contains("administrative"));
            }
        }
    }

    @Test void uninitializedRepositoryIsReportedWithoutSeedingItOrInventingDeploymentDefaults() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            var output = new Contents();
            new PreferencesBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output);
            var document = PortableRows.json().readTree(output.entries.get(PATH));
            assertThat(document.path("selection").asText()).isEqualTo("UNINITIALIZED");
            assertThat(document.path("settings").isNull()).isTrue();
            assertThat(repository.getHistory()).isEmpty();
            assertThat(repository.getGitRepository().getRefDatabase().getRefs()).isEmpty();
        }
    }

    @Test void readsThePinnedCommitEvenIfTheHeadMovesBeforeTheBlobIsRead() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            String head = repository.commit("{\"dsl.project-name\":\"PINNED\"}", "alice", "initial");
            var output = new Contents();
            var sink = new ComponentSink() {
                private int checkpoints;
                @Override public void checkpoint() throws IOException {
                    if (++checkpoints == 2) repository.commit("{\"dsl.project-name\":\"LATER\"}", "alice", "later");
                }
                @Override public BackupEntry write(String path, InputStream input) throws IOException { return output.write(path, input); }
            };
            new PreferencesBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), sink);
            assertThat(output.text()).contains("PINNED", head).doesNotContain("LATER");
            assertThat(repository.readHead()).contains("LATER");
        }
    }

    @Test void rejectsMalformedOrUnclassifiedSettingsBeforeWritingAndRedactsParserDiagnostics() throws Exception {
        for (String json : List.of("[]", "null", "{\"llm.rpm\":1,\"llm.rpm\":2}", "{} {}",
                "{\"custom.secret\":\"MUST-NOT-LEAK\"}", "{\"llm.rpm\":\"MUST-NOT-LEAK\"}",
                "{\"llm.rpm\":2147483648}", "{\"dsl.project-name\":{\"hidden\":\"MUST-NOT-LEAK\"}}",
                "{\"dsl.remote.push-on-commit\":\"invalid\"}", "{\"dsl.remote.token\":{\"hidden\":\"MUST-NOT-LEAK\"}}")) {
            try (var repository = new PreferencesGitRepository()) {
                repository.commit(json, "alice", "input");
                var output = new Contents();
                assertThatThrownBy(() -> new PreferencesBackupContributor(repository).write(
                        snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output))
                        .isInstanceOf(IOException.class).hasMessage("Stored preferences cannot be exported safely").hasNoCause();
                assertThat(output.entries).isEmpty();
                assertThat(repository.readHead()).isEqualTo(json);
            }
        }
    }

    @Test void rejectsOversizedNonUtf8AndUnexpectedGitEntriesBeforeWriting() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            repository.commit("{\"dsl.project-name\":\"" + "X".repeat(1_048_576) + "\"}", "alice", "oversized");
            rejectsCurrent(repository);
        }
        for (var mode : List.of(FileMode.SYMLINK, FileMode.EXECUTABLE_FILE)) {
            try (var repository = new PreferencesGitRepository()) {
                rawCommit(repository, "preferences.json", mode, "{}".getBytes(StandardCharsets.UTF_8));
                rejectsCurrent(repository);
            }
        }
        try (var repository = new PreferencesGitRepository()) {
            rawCommit(repository, "preferences.json", FileMode.REGULAR_FILE, new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'});
            rejectsCurrent(repository);
        }
        try (var repository = new PreferencesGitRepository()) {
            rawCommit(repository, "unclassified.json", FileMode.REGULAR_FILE, "{}".getBytes(StandardCharsets.UTF_8));
            rejectsCurrent(repository);
        }
    }

    @Test void cancellationDuringPreflightWritesNothingAndPreservesTheSource() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            String head = repository.commit("{}", "alice", "input");
            var sink = new ComponentSink() {
                @Override public void checkpoint() throws IOException {
                    var interrupted = new InterruptedIOException("INTERNAL-SECRET-PATH");
                    interrupted.initCause(new IOException("SECRET-CAUSE"));
                    interrupted.addSuppressed(new IOException("SECRET-CLEANUP"));
                    throw interrupted;
                }
                @Override public BackupEntry write(String path, InputStream input) { throw new AssertionError("Cancelled capture wrote data"); }
            };
            assertThatThrownBy(() -> new PreferencesBackupContributor(repository).write(
                    snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), sink))
                    .isInstanceOf(InterruptedIOException.class).hasMessage("Preferences capture interrupted").hasNoCause()
                    .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
            assertThat(repository.getHistory()).extracting(com.taxonomy.preferences.storage.PreferencesCommit::commitId).containsExactly(head);
        }
    }

    @Test void currentAdapterCannotMisrepresentAFullHistorySelection() throws Exception {
        try (var repository = new PreferencesGitRepository()) {
            var output = new Contents();
            assertThatThrownBy(() -> new PreferencesBackupContributor(repository).write(
                    snapshot(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation()), output))
                    .isInstanceOf(IOException.class).hasMessageContaining("history capture");
            assertThat(output.entries).isEmpty();
        }
    }

    private static void rejectsCurrent(PreferencesGitRepository repository) {
        var output = new Contents();
        assertThatThrownBy(() -> new PreferencesBackupContributor(repository).write(
                snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output))
                .isInstanceOf(IOException.class).hasMessage("Stored preferences cannot be exported safely").hasNoCause();
        assertThat(output.entries).isEmpty();
    }

    private static void rawCommit(PreferencesGitRepository source, String path, FileMode mode, byte[] content) throws Exception {
        var repository = source.getGitRepository();
        try (var inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter(); tree.append(path, mode, inserter.insert(Constants.OBJ_BLOB, content));
            var commit = new CommitBuilder(); commit.setTreeId(inserter.insert(tree));
            var author = new PersonIdent("alice", "alice@example.test");
            commit.setAuthor(author); commit.setCommitter(author); commit.setMessage("fixture");
            var id = inserter.insert(commit); inserter.flush();
            var ref = repository.updateRef("refs/heads/main"); ref.setNewObjectId(id);
            assertThat(ref.update()).isEqualTo(RefUpdate.Result.NEW);
        }
    }
}
