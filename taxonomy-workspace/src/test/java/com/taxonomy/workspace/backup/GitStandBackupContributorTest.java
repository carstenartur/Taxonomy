package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jgit.lib.FileMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class GitStandBackupContributorTest {
    private static final BackupRepositoryKey WORKSPACE = new BackupRepositoryKey("repo-a", "private-a"), CENTRAL = new BackupRepositoryKey("repo-a", null);
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);
    private static final String DSL = "architecture.taxdsl", METADATA = "data/workspace/git-stands.ndjson";

    @Test void capturesExplicitCentralAndPrivateFilesWithManifestIdsAndUnsupersededDocumentProof() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            String central = f.commit("central-a", Map.of(DSL, bytes("CENTRAL embedded-secret")));
            String head = f.commit(Map.of(DSL, bytes("OLD-PRIVATE"))); f.saved(WORKSPACE, "draft", "SAVED-PRIVATE embedded-secret", 3, head, 1, null);
            var contributor = new GitStandBackupContributor(f.source(), LIMITS); var auth = both(); var plan = inspect(contributor, auth);
            assertThat(plan).allSatisfy(repo -> { assertThat(repo.archiveId()).matches("[a-z0-9-]{36}"); assertThat(repo.representation()).isEqualTo(GitRepresentation.NONE); assertThat(repo.exportedHead()).isNull(); assertThat(repo.sourceCommit()).isNull(); });
            var snapshot = snapshot(auth, plan); var output = new Contents(); contributor.write(snapshot, output);
            assertThat(output.text()).contains("SAVED-PRIVATE", "CENTRAL").doesNotContain("OLD-PRIVATE", "embedded-secret");
            var rows = output.rows(); assertThat(rows).filteredOn(row -> row.path("recordType").asText().equals("repository")).hasSize(2);
            assertThat(rows).filteredOn(row -> row.path("recordType").asText().equals("file")).hasSize(2).allSatisfy(row -> {
                String archiveId = row.path("archiveId").asText(), path = row.path("entry").path("path").asText();
                assertThat(snapshot.repositoryArchiveIds()).containsValue(archiveId); assertThat(path).startsWith("files/workspace-git/" + archiveId + "/");
                byte[] content = output.entries.get(path); assertThat(content).isNotNull(); assertThat(hash(content)).isEqualTo(row.path("entry").path("sha256").asText());
                assertThat(content.length).isEqualTo(row.path("entry").path("length").asInt()); assertThat(row.path("path").asText()).isEqualTo(DSL);
            });
            assertThat(contributor.documents(snapshot, CHECK)).containsExactly(new BackupDocumentReference(CENTRAL, central, DSL, hash(bytes("CENTRAL embedded-secret"))));
            assertThat(contributor.omissions(BackupProfile.CURRENT_STATE)).isNotEmpty();
        }
    }

    @Test void equalBytesShareOnePayloadWithinARepositoryWithoutLosingPathsOrExecutableMode() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit(Map.of("ordinary.bin", bytes("same"), "run.sh", bytes("same")));
            var contributor = new GitStandBackupContributor(f.source(), LIMITS); var output = new Contents(); contributor.write(snapshot(current(), inspect(contributor, current())), output);
            assertThat(output.entries).hasSize(2); var files = output.rows().stream().filter(r -> r.path("recordType").asText().equals("file")).toList();
            assertThat(files).hasSize(2); assertThat(files).extracting(r -> r.path("path").asText()).containsExactly("ordinary.bin", "run.sh");
            assertThat(files).extracting(r -> r.path("mode").asInt()).containsExactly(FileMode.REGULAR_FILE.getBits(), FileMode.EXECUTABLE_FILE.getBits());
            assertThat(files.get(0).path("entry")).isEqualTo(files.get(1).path("entry"));
        }
    }

    @Test void selectedArchiveUsesOnlyItsPinnedProjectedVersionAndNoLiveDraft() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            String selected = f.commit(Map.of(DSL, bytes("SELECTED embedded-secret"))); String today = f.commit(Map.of(DSL, bytes("TODAY")));
            f.saved(WORKSPACE, "draft", "LIVE-DRAFT", 3, today, 1, null);
            var auth = authorized(new BackupRequest(BackupProfile.SELECTED_VERSION, scope(), new BackupTime.SelectedVersion(Map.of(WORKSPACE, selected)), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
            var contributor = new GitStandBackupContributor(f.source(), LIMITS); var plan = inspect(contributor, auth);
            assertThat(plan.getFirst().sourceCommit()).isEqualTo(selected); var output = new Contents(); contributor.write(snapshot(auth, plan), output);
            assertThat(output.text()).contains("SELECTED").doesNotContain("embedded-secret", "TODAY", "LIVE-DRAFT");
            assertThat(contributor.documents(snapshot(auth, plan), CHECK)).containsExactly(new BackupDocumentReference(WORKSPACE, selected, DSL, hash(bytes("SELECTED embedded-secret"))));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ids", "version", "refs", "revision"})
    void refusesMissingIdentityOrChangedCaptureEvidenceBeforeWriting(String problem) throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            String head = f.commit(Map.of(DSL, bytes("OLD"))); f.saved(WORKSPACE, "draft", "CURRENT", 3, head, 1, null);
            var contributor = new GitStandBackupContributor(f.source(), LIMITS); var context = snapshot(current(), inspect(contributor, current()));
            var states = new HashMap<>(context.repositories()); var state = states.get(WORKSPACE);
            if (problem.equals("refs")) states.put(WORKSPACE, new SnapshotContext.RepositoryState(Map.of("refs/heads/draft", "a".repeat(40)), "refs/heads/draft", state.workingStates(), Set.of("a".repeat(40))));
            if (problem.equals("revision")) states.put(WORKSPACE, new SnapshotContext.RepositoryState(state.refs(), state.symbolicHead(), Map.of("draft", new SnapshotContext.WorkingState(99, head, 1)), state.requiredCommits()));
            var wrong = new SnapshotContext(context.backupId(), context.authorization(), context.startedAt(), context.completedAt(), 1, states,
                    Map.of(contributor.componentId(), problem.equals("version") ? 2 : 1), problem.equals("ids") ? Map.of() : context.repositoryArchiveIds());
            var output = new Contents(); assertThatThrownBy(() -> contributor.write(wrong, output)).isInstanceOf(IOException.class); assertThat(output.entries).isEmpty();
            assertThatThrownBy(() -> contributor.documents(wrong, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void doesNotFollowAMovedCurrentHeadAfterInventory() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit(Map.of(DSL, bytes("FIRST"))); var contributor = new GitStandBackupContributor(f.source(), LIMITS); var context = snapshot(current(), inspect(contributor, current()));
            f.commit(Map.of(DSL, bytes("MOVED"))); var output = new Contents();
            assertThatThrownBy(() -> contributor.write(context, output)).isInstanceOf(IOException.class); assertThat(output.entries).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"count", "bytes"})
    void limitsApplyToTheWholeSelectedClosureDuringInspectionAndWriting(String dimension) throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit("central-a", Map.of("file.bin", bytes("one"))); f.commit(Map.of("file.bin", bytes("two")));
            var large = new GitStandBackupContributor(f.source(), LIMITS); var context = snapshot(both(), inspect(large, both()));
            var small = new GitStandBackupContributor(f.source(), new GitStandBackupSource.Limits(new GitTreeCapture.Limits(dimension.equals("count") ? 1 : 100, 1_000_000, dimension.equals("bytes") ? 5 : 2_000_000), 100_000));
            assertThatThrownBy(() -> small.inspect(both(), both().request().scope().selectedRepositories(), CHECK)).isInstanceOf(IOException.class);
            var output = new Contents(); assertThatThrownBy(() -> small.write(context, output)).isInstanceOf(IOException.class); assertThat(output.entries).doesNotContainKey(METADATA);
        }
    }

    @ParameterizedTest @EnumSource(value = GitRepresentation.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void cannotSilentlyReplaceRequestedGitRepresentationsWithFileOnlyPayloads(GitRepresentation representation) throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            var contributor = new GitStandBackupContributor(f.source(), LIMITS);
            var auth = authorized(new BackupRequest(BackupProfile.CURRENT_STATE, scope(), new BackupTime.Current(), representation, SecretsSelection.EXCLUDE));
            assertThatThrownBy(() -> contributor.inspect(auth, Set.of(WORKSPACE), CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void historyProfilesRequireTheirOwnCompleteAdapter() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            var contributor = new GitStandBackupContributor(f.source(), LIMITS);
            var auth = authorized(new BackupRequest(BackupProfile.REPOSITORY_HISTORY, scope(), new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE));
            assertThatThrownBy(() -> contributor.inspect(auth, Set.of(WORKSPACE), CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void inspectionCannotDropOrAddRepositoriesOutsideTheAuthorizedScope() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            var contributor = new GitStandBackupContributor(f.source(), LIMITS);
            assertThatThrownBy(() -> contributor.inspect(current(), Set.of(), CHECK)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> contributor.inspect(current(), Set.of(WORKSPACE, CENTRAL), CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void emptyUnbornRepositoryRemainsExplicitWithoutInventedFiles() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit(Map.of(DSL, bytes("OTHER-BRANCH"))); f.sql("update user_workspace set current_branch='unborn'");
            var contributor = new GitStandBackupContributor(f.source(), LIMITS); var plan = inspect(contributor, current()); var output = new Contents(); contributor.write(snapshot(current(), plan), output);
            assertThat(output.entries).containsOnlyKeys(METADATA); assertThat(output.text()).doesNotContain("OTHER-BRANCH");
            assertThat(output.rows()).filteredOn(row -> row.path("recordType").asText().equals("repository")).hasSize(1);
            assertThat(output.rows()).filteredOn(row -> row.path("recordType").asText().equals("file")).isEmpty();
        }
    }

    @Test void installationWithNoLogicalRepositoriesStillHasAVersionedEmptyDataset() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            var auth = authorized(new BackupRequest(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation(), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
            var contributor = new GitStandBackupContributor(f.source(), LIMITS); var plan = contributor.inspect(auth, Set.of(), CHECK); var output = new Contents(); contributor.write(snapshot(auth, plan), output);
            assertThat(output.entries).containsOnlyKeys(METADATA); assertThat(output.rows()).hasSize(1); assertThat(output.rows().getFirst().path("schemaVersion").asInt()).isEqualTo(1);
        }
    }

    @Test void aMetadataReceiptWithoutACompletedProducerCannotProveTheDataset() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            f.commit(Map.of(DSL, bytes("CURRENT"))); var contributor = new GitStandBackupContributor(f.source(), LIMITS); var context = snapshot(current(), inspect(contributor, current()));
            var output = new Contents() {
                @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
                    if (path.equals(METADATA)) return new BackupEntry(path, 0, "0".repeat(64)); return super.writeGenerated(path, producer);
                }
            };
            assertThatThrownBy(() -> contributor.write(context, output)).isInstanceOf(IOException.class); assertThat(output.entries).doesNotContainKey(METADATA);
        }
    }

    @Test void cancellationDuringDiscoveryPreservesTheInterruptFlag() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            var contributor = new GitStandBackupContributor(f.source(), LIMITS);
            try {
                assertThatThrownBy(() -> contributor.inspect(current(), Set.of(WORKSPACE), () -> { throw new InterruptedIOException("PRIVATE"); })).isInstanceOf(InterruptedIOException.class).hasMessageNotContaining("PRIVATE");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    private static List<BackupManifest.Repository> inspect(GitStandBackupContributor contributor, AuthorizedBackupRequest auth) throws IOException {
        var plan = contributor.inspect(auth, auth.request().scope().selectedRepositories(), CHECK);
        assertThat(plan).as("inventory must include exactly the authorized repositories").extracting(BackupManifest.Repository::id).containsExactlyInAnyOrderElementsOf(auth.request().scope().selectedRepositories()); return plan;
    }
    private static SnapshotContext snapshot(AuthorizedBackupRequest auth, List<BackupManifest.Repository> plan) {
        return new SnapshotContext(BackupId.create(), auth, Instant.EPOCH, Instant.EPOCH, 1,
                plan.stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::captured)), Map.of(new BackupComponentId("workspace"), 1),
                plan.stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::archiveId)));
    }
    private static BackupScope scope() { return new BackupScope.Workspace(WORKSPACE.repositoryId(), WORKSPACE.workspaceId()); }
    private static AuthorizedBackupRequest current() { return authorized(new BackupRequest(BackupProfile.CURRENT_STATE, scope(), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE)); }
    private static AuthorizedBackupRequest both() { return authorized(new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Repositories(Map.of("repo-a", Set.of("private-a"))), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE)); }
    private static AuthorizedBackupRequest authorized(BackupRequest request) { return new AuthorizedBackupRequest(request, PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class)); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static class Contents implements ComponentSink {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        @Override public BackupEntry write(String path, InputStream input) throws IOException { return save(path, input.readAllBytes()); }
        @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException { var out = new ByteArrayOutputStream(); producer.write(out); return save(path, out.toByteArray()); }
        private BackupEntry save(String path, byte[] bytes) { assertThat(entries.putIfAbsent(path, bytes)).isNull(); return new BackupEntry(path, bytes.length, hash(bytes)); }
        String text() { return entries.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).collect(Collectors.joining("\n")); }
        List<JsonNode> rows() throws IOException {
            assertThat(entries).containsKey(METADATA); var result = new ArrayList<JsonNode>();
            for (String line : new String(entries.get(METADATA), StandardCharsets.UTF_8).split("\n")) if (!line.isBlank()) result.add(PortableRows.json().readTree(line)); return result;
        }
    }
}
