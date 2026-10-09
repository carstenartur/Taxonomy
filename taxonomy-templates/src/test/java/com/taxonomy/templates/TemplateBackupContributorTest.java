package com.taxonomy.templates;

import com.taxonomy.backup.*;
import com.taxonomy.templates.backup.TemplateBackupContributor;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.internal.storage.dfs.*;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TemplateBackupContributorTest {
    private InMemoryRepository git;
    private DocumentTemplateGitRepository repository;
    private Map<String, byte[]> parts;
    private final Instant now = Instant.parse("2026-10-01T00:00:00Z");

    @BeforeEach void source() throws Exception {
        git = new InMemoryRepository(new DfsRepositoryDescription("portable-templates"));
        repository = new DocumentTemplateGitRepository(git);
        try (var input = getClass().getResourceAsStream("/" + TemplateTestFixture.DEFAULT_RESOURCE)) {
            parts = new TreeMap<>(new OoxmlTemplatePackageCodec().unpack(input).parts());
        }
        parts.put("binary.dat", new byte[]{0, 1, 2, (byte) 255, 10, 0});
    }
    @AfterEach void close() { git.close(); }

    @Test void installationCurrentStreamsAllCurrentPartsWithoutEarlierBytesOrSourceWrites() throws Exception {
        parts.put("word/extra.xml", "OLD-PRIVATE-TEMPLATE-TEXT".getBytes(StandardCharsets.UTF_8));
        var first = commit("report", null);
        parts.put("word/extra.xml", "CURRENT-TEMPLATE-TEXT".getBytes(StandardCharsets.UTF_8));
        commit("report", first.commitId());
        commit("second", null);
        String head = repository.headCommit();
        var output = new Contents();

        new TemplateBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_CURRENT), output);

        assertThat(output.bytes).hasSize(2 * (parts.size() + 1) + 1);
        for (String id : List.of("report", "second")) {
            for (var part : parts.entrySet())
                assertThat(output.bytes.get("data/templates/current/templates/" + id + "/package/" + part.getKey())).isEqualTo(part.getValue());
            assertThat(output.bytes).containsKey("data/templates/current/templates/" + id + "/template.json");
        }
        assertThat(new String(output.bytes.get("data/templates/inventory.json"), StandardCharsets.UTF_8)).contains(head, "INSTALLATION_CURRENT");
        assertThat(output.bytes.values()).noneMatch(value -> new String(value, StandardCharsets.UTF_8).contains("OLD-PRIVATE-TEMPLATE-TEXT"));
        assertThat(repository.headCommit()).isEqualTo(head);
        assertThat(new String(repository.read("report", first.commitId()).parts().get("word/extra.xml"), StandardCharsets.UTF_8))
                .isEqualTo("OLD-PRIVATE-TEMPLATE-TEXT");
    }

    @Test void workspaceAndSelectedVersionDoNotReadTheAdministrativeTemplateRepository() throws Exception {
        var inaccessible = new DocumentTemplateGitRepository(git) {
            @Override public CapturedTree captureCurrentTree(BackupCheckpoint checkpoint) throws IOException {
                throw new AssertionError("Out-of-scope templates must not be read");
            }
        };
        for (var profile : List.of(BackupProfile.CURRENT_STATE, BackupProfile.SELECTED_VERSION, BackupProfile.REPOSITORY_HISTORY)) {
            var output = new Contents();
            var contributor = new TemplateBackupContributor(inaccessible);
            contributor.write(snapshot(profile), output);
            assertThat(output.bytes).containsOnlyKeys("data/templates/inventory.json");
            assertThat(contributor.omissions(profile)).isNotEmpty();
        }
    }

    @Test void missingStoredPartFailsBeforeWritingAnyPayload() throws Exception {
        commit("report", null);
        var files = files("report");
        files.remove("templates/report/package/word/document.xml");
        replaceTree(files, Map.of());
        assertRejectedBeforeOutput();
    }

    @Test void changedBytesWithUnchangedLengthAreDetectedByTheStoredPackageChecksum() throws Exception {
        commit("report", null);
        var files = files("report");
        files.get("templates/report/package/binary.dat")[0] ^= 1;
        replaceTree(files, Map.of());
        var output = new Contents();
        assertThatThrownBy(() -> new TemplateBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_CURRENT), output))
                .isInstanceOf(IOException.class).hasMessageContaining("checksum");
        assertThat(output.bytes).isEmpty();
    }

    @Test void ambiguousAndOversizedManifestsFailBeforeWritingAnyPayload() throws Exception {
        commit("report", null);
        var files = files("report");
        String path = "templates/report/template.json";
        String manifest = new String(files.get(path), StandardCharsets.UTF_8);
        for (String invalid : List.of("null", manifest + " {}", manifest.replaceFirst("\\{", "{\"schemaVersion\":1,"),
                manifest.replaceFirst("\\{", "{\"unknown\":true,"), " ".repeat(1_048_577))) {
            files.put(path, invalid.getBytes(StandardCharsets.UTF_8));
            replaceTree(files, Map.of());
            assertRejectedBeforeOutput();
        }
        files.remove(path);
        replaceTree(files, Map.of());
        assertRejectedBeforeOutput();
    }

    @Test void emptyRepositoryIsCapturedWithoutCreatingAHead() throws Exception {
        var output = new Contents();
        new TemplateBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_CURRENT), output);
        assertThat(output.bytes).containsOnlyKeys("data/templates/inventory.json");
        assertThat(repository.headCommit()).isNull();
    }

    @Test void oversizedCommitMetadataFailsBeforeWritingAnyPayload() throws Exception {
        commit("report", null, "x".repeat(1_048_577));
        assertRejectedBeforeOutput();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void tagHeadCannotBypassCommitMetadataPreflight(boolean oversizedTarget) throws Exception {
        commit("report", null, oversizedTarget ? "x".repeat(1_048_577) : "Stored template");
        try (var inserter = git.newObjectInserter()) {
            var tag = new TagBuilder();
            tag.setObjectId(ObjectId.fromString(repository.headCommit()), Constants.OBJ_COMMIT);
            tag.setTag("unexpected-head");
            tag.setTagger(new PersonIdent("operator", "operator@example.test"));
            tag.setMessage("A tag is not a captured commit");
            ObjectId id = inserter.insert(tag); inserter.flush();
            var update = git.updateRef("refs/heads/main"); update.setNewObjectId(id); update.setForceUpdate(true); update.forceUpdate();
        }
        assertRejectedBeforeOutput();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void oversizedRootOrNestedTreeMetadataFailsBeforeWritingAnyPayload(boolean nested) throws Exception {
        try (var inserter = git.newObjectInserter()) {
            ObjectId tree = directoryTree(inserter, 16_000);
            if (nested) tree = parentTree(inserter, "templates", tree);
            replaceHead(inserter, tree);
        }
        assertRejectedBeforeOutput();
    }

    @Test void cumulativeTreeMetadataIsBoundedEvenWhenIndividualTreesFit() throws Exception {
        try (var inserter = git.newObjectInserter()) {
            ObjectId subtree = directoryTree(inserter, 14_000);
            var root = new TreeFormatter();
            for (int i = 0; i < 5; i++) root.append("directory" + i, FileMode.TREE, subtree);
            replaceHead(inserter, inserter.insert(root));
        }
        assertRejectedBeforeOutput();
    }

    @Test void cancellationIsCheckedBeforeOpeningCommitMetadata() {
        assertThatThrownBy(() -> TemplateBackupCapture.capture(git, ObjectId.fromString("a".repeat(40)),
                () -> { throw new InterruptedIOException("capture stopped"); }))
                .isInstanceOf(InterruptedIOException.class).hasMessage("capture stopped");
    }

    @Test void unsafeAndCollidingPathsFailBeforeWritingAnyPayload() throws Exception {
        commit("report", null);
        var files = files("report");
        files.put("templates/report/package/WORD/unrelated.xml", new byte[]{1});
        replaceTree(files, Map.of());
        assertRejectedBeforeOutput();
        files.remove("templates/report/package/WORD/unrelated.xml");
        files.put("templates/report/package/CON", new byte[]{1});
        replaceTree(files, Map.of());
        assertRejectedBeforeOutput();
    }

    @Test void symbolicLinksAndForeignRepositoryFilesCannotBecomeTemplatePayloads() throws Exception {
        commit("report", null);
        var files = files("report");
        replaceTree(files, Map.of("templates/report/package/binary.dat", FileMode.SYMLINK));
        assertRejectedBeforeOutput();
        files.put("other/secret.txt", new byte[]{1});
        replaceTree(files, Map.of());
        assertRejectedBeforeOutput();
    }

    @Test void immutableCapturedBlobsRemainReadableWhenTheSourceHeadMoves() throws Exception {
        var first = commit("report", null);
        var captured = repository.captureCurrentTree(() -> { });
        var binary = captured.files().stream().filter(file -> file.path().endsWith("/binary.dat")).findFirst().orElseThrow();
        parts.put("binary.dat", new byte[]{9, 9});
        commit("report", first.commitId());
        try (var input = repository.openCapturedFile(binary)) {
            assertThat(input.readAllBytes()).isEqualTo(new byte[]{0, 1, 2, (byte) 255, 10, 0});
        }
        assertThat(captured.commitId()).isEqualTo(first.commitId());
    }

    @Test void fullInstallationHistoryCannotBeClaimedByTheCurrentTreeAdapter() throws Exception {
        commit("report", null);
        var output = new Contents();
        assertThatThrownBy(() -> new TemplateBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_FULL), output))
                .isInstanceOf(IOException.class).hasMessageContaining("history");
        assertThat(output.bytes).isEmpty();
    }

    private void assertRejectedBeforeOutput() {
        var output = new Contents();
        assertThatThrownBy(() -> new TemplateBackupContributor(repository).write(snapshot(BackupProfile.INSTALLATION_CURRENT), output))
                .isInstanceOfAny(IOException.class, IllegalArgumentException.class);
        assertThat(output.bytes).isEmpty();
    }
    private DocumentTemplateGitRepository.TemplateSnapshot commit(String id, String expected) throws Exception {
        return commit(id, expected, "Update template");
    }
    private DocumentTemplateGitRepository.TemplateSnapshot commit(String id, String expected, String message) throws Exception {
        var manifest = new com.taxonomy.templates.api.TemplateManifest(1, id, id, id + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, now.toString(), "operator",
                parts.values().stream().mapToLong(value -> value.length).sum(), parts.size(), OoxmlTemplatePackageCodec.packageSha256(parts));
        return repository.commit(manifest, parts, expected, "operator", message);
    }

    private ObjectId directoryTree(ObjectInserter inserter, int count) throws IOException {
        ObjectId empty = inserter.insert(new TreeFormatter());
        var tree = new TreeFormatter();
        for (int i = 0; i < count; i++) {
            tree.append(String.format(Locale.ROOT, "%05d", i) + "x".repeat(240), FileMode.TREE, empty);
        }
        return inserter.insert(tree);
    }

    private ObjectId parentTree(ObjectInserter inserter, String name, ObjectId child) throws IOException {
        var tree = new TreeFormatter();
        tree.append(name, FileMode.TREE, child);
        return inserter.insert(tree);
    }

    private void replaceHead(ObjectInserter inserter, ObjectId tree) throws IOException {
        var commit = new CommitBuilder(); commit.setTreeId(tree);
        var identity = new PersonIdent("operator", "operator@example.test");
        commit.setAuthor(identity); commit.setCommitter(identity); commit.setMessage("Stored metadata fixture");
        ObjectId id = inserter.insert(commit); inserter.flush();
        var update = git.updateRef("refs/heads/main"); update.setNewObjectId(id); update.setForceUpdate(true); update.forceUpdate();
    }
    private Map<String, byte[]> files(String id) throws Exception {
        var snapshot = repository.readCurrent(id);
        var files = new TreeMap<String, byte[]>();
        files.put("templates/" + id + "/template.json", new tools.jackson.databind.ObjectMapper().writeValueAsBytes(snapshot.manifest()));
        snapshot.parts().forEach((path, value) -> files.put("templates/" + id + "/package/" + path, value));
        return files;
    }
    private void replaceTree(Map<String, byte[]> files, Map<String, FileMode> modes) throws Exception {
        try (var inserter = git.newObjectInserter()) {
            var cache = DirCache.newInCore(); var builder = cache.builder();
            for (var item : files.entrySet()) {
                var entry = new DirCacheEntry(item.getKey()); entry.setFileMode(modes.getOrDefault(item.getKey(), FileMode.REGULAR_FILE));
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, item.getValue())); builder.add(entry);
            }
            builder.finish(); var commit = new CommitBuilder(); commit.setTreeId(cache.writeTree(inserter));
            var identity = new PersonIdent("operator", "operator@example.test"); commit.setAuthor(identity); commit.setCommitter(identity);
            commit.setMessage("Corrupt stored template fixture"); var id = inserter.insert(commit); inserter.flush();
            var update = git.updateRef("refs/heads/main"); update.setNewObjectId(id); update.setForceUpdate(true); update.forceUpdate();
        }
    }
    private SnapshotContext snapshot(BackupProfile profile) {
        var key = new BackupRepositoryKey("repo", "workspace");
        BackupScope scope = profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace("repo", "workspace");
        BackupTime time = profile.includesHistory() ? new BackupTime.History() : profile == BackupProfile.SELECTED_VERSION
                ? new BackupTime.SelectedVersion(Map.of(key, "a".repeat(40))) : new BackupTime.Current();
        var request = new BackupRequest(profile, scope, time, profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var authorization = new AuthorizedBackupRequest(request, PrincipalId.create(), "test-decision", now,
                EnumSet.allOf(BackupCapability.class));
        return new SnapshotContext(BackupId.create(), authorization, now, now, 1,
                profile.isInstallation() ? Map.of() : Map.of(key, new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of())),
                Map.of(new BackupComponentId("templates"), 1));
    }
    private static final class Contents implements ComponentSink {
        final Map<String, byte[]> bytes = new LinkedHashMap<>();
        boolean open;
        @Override public BackupEntry write(String path, InputStream input) throws IOException {
            assertThat(open).isFalse(); open = true;
            try {
                byte[] content = input.readAllBytes(); assertThat(bytes.putIfAbsent(path, content)).isNull();
                return new BackupEntry(path, content.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            finally { open = false; }
        }
    }
}
