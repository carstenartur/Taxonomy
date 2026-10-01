package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Fenced projected current/selected stands as files or standalone Git bundles. */
public final class GitStandBackupContributor implements BackupDataContributor {
    private static final String METADATA = "data/workspace/git-stands.ndjson";
    private static final JsonMapper JSON = PortableRows.json();
    private final GitStandBackupSource source;
    private final GitStandBackupSource.Limits limits;
    public GitStandBackupContributor(GitStandBackupSource source, GitStandBackupSource.Limits limits) {
        this.source = Objects.requireNonNull(source); this.limits = Objects.requireNonNull(limits);
    }

    /** Explicit server-selected closure, under the coordinator's fresh authorization and writer fence. */
    public List<BackupManifest.Repository> inspect(AuthorizedBackupRequest authorization, Set<BackupRepositoryKey> keys, BackupCheckpoint checkpoint) throws IOException {
        try {
            requireProfile(authorization.request()); checkpoint.check(); keys = Set.copyOf(keys);
            if (keys.size() > BackupLimits.MAX_ITEMS || !(authorization.request().scope() instanceof BackupScope.Installation)
                    && !keys.equals(authorization.request().scope().selectedRepositories())) throw failure("Git stand inventory differs from authorized scope");
            var result = new ArrayList<BackupManifest.Repository>(); var budget = new Budget();
            for (var key : ordered(keys)) {
                checkpoint.check();
                try (var stand = source.open(authorization, key, limits, checkpoint)) {
                    for (var file : stand.files()) { checkpoint.check(); budget.add(file); }
                    String selected = authorization.request().time() instanceof BackupTime.SelectedVersion version ? version.commitsByRepository().get(key) : null;
                    boolean bundle = authorization.request().gitRepresentation() == GitRepresentation.BUNDLE;
                    String head = bundle ? GitStandBundle.capture(stand, authorization.authorizedAt(), checkpoint).head() : null;
                    String original = bundle ? stand.state().requiredCommits().stream().findFirst().orElse(null) : selected;
                    result.add(new BackupManifest.Repository(key, UUID.randomUUID().toString(), authorization.request().gitRepresentation(), stand.state(), null, head, original));
                }
            }
            checkpoint.check(); return List.copyOf(result);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    /** Architecture proof selection rechecks the same captured source states under the still-held fence. */
    public List<BackupDocumentReference> documents(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        try {
            requireSnapshot(snapshot); checkpoint.check(); var references = new ArrayList<BackupDocumentReference>(); var budget = new Budget();
            for (var key : ordered(snapshot.repositories().keySet())) {
                checkpoint.check();
                try (var stand = source.open(snapshot.authorization(), key, limits, checkpoint)) {
                    requireState(snapshot, key, stand);
                    for (var file : stand.files()) { checkpoint.check(); budget.add(file); }
                    if (snapshot.authorization().request().gitRepresentation() == GitRepresentation.BUNDLE) requireBundle(snapshot, key, stand, checkpoint);
                    references.addAll(stand.documents());
                }
            }
            checkpoint.check(); return List.copyOf(references);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("workspace"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("storage.jgit.objects-refs-reflogs"); }
    @Override public List<String> omissions(BackupProfile profile) {
        return List.of("workspace: stand payloads exclude source Git ancestry, reflogs and other branches; captured refs and checkpoint IDs are source evidence",
                "workspace: saved states on other branches remain separate workspace records; no source commit is created");
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        try {
            requireSnapshot(snapshot); sink.checkpoint(); var budget = new Budget();
            boolean bundle = snapshot.authorization().request().gitRepresentation() == GitRepresentation.BUNDLE;
            var records = new ArrayList<Record>(); records.add(new PortableRows.Header(1, "git-stands", snapshot.authorization().request().profile()));
            for (var key : ordered(snapshot.repositories().keySet())) {
                sink.checkpoint(); String archiveId = snapshot.repositoryArchiveIds().get(key);
                try (var stand = source.open(snapshot.authorization(), key, limits, sink::checkpoint)) {
                    requireState(snapshot, key, stand);
                    for (var file : stand.files()) { sink.checkpoint(); budget.add(file); }
                    if (bundle) {
                        requireBundle(snapshot, key, stand, sink::checkpoint).write("repositories/" + archiveId + "/stand.bundle", sink);
                        continue;
                    }
                    records.add(new RepositoryRecord("repository", key, archiveId, stand.state()));
                    var written = new HashMap<String, BackupEntry>();
                    for (var file : stand.files()) {
                        sink.checkpoint();
                        String path = "files/workspace-git/" + archiveId + "/" + file.sha256();
                        var entry = new BackupEntry(path, file.length(), file.sha256()); var prior = written.get(path);
                        if (prior == null) {
                            if (!entry.equals(stand.writeFile(file, path, sink))) throw failure("Git stand file receipt differs from captured evidence");
                            written.put(path, entry);
                        } else if (!prior.equals(entry)) throw failure("Git stand content-address collision");
                        records.add(new FileRecord("file", archiveId, file.path(), file.mode(), entry));
                    }
                }
            }
            sink.checkpoint(); if (bundle) return;
            var digest = new DigestOutput(); serialize(records, digest, sink::checkpoint);
            var entry = new BackupEntry(METADATA, digest.bytes, HexFormat.of().formatHex(digest.digest.digest()));
            GitTreeCapture.writeGenerated(entry, sink, output -> serialize(records, output, sink::checkpoint)); sink.checkpoint();
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    private void requireSnapshot(SnapshotContext snapshot) throws IOException {
        requireProfile(snapshot.authorization().request());
        if (!Integer.valueOf(schemaVersion()).equals(snapshot.componentVersions().get(componentId()))) throw failure("Git stand component version differs from capture");
        if (!snapshot.repositoryArchiveIds().keySet().equals(snapshot.repositories().keySet())) throw failure("Git stand payloads require explicit manifest repository IDs");
        if (snapshot.authorization().request().gitRepresentation() == GitRepresentation.BUNDLE
                && !snapshot.exportedHeads().keySet().equals(snapshot.repositories().keySet())) throw failure("Git stand bundles require explicit manifest exported heads");
    }
    private static void requireProfile(BackupRequest request) throws IOException {
        if (request.profile().includesHistory() || request.gitRepresentation() != GitRepresentation.NONE && request.gitRepresentation() != GitRepresentation.BUNDLE)
            throw failure("Git stand capture requires a current or selected profile with files or bundles");
    }
    private static GitStandBundle requireBundle(SnapshotContext snapshot, BackupRepositoryKey key, GitStandBackupSource.Stand stand, BackupCheckpoint checkpoint) throws IOException {
        var bundle = GitStandBundle.capture(stand, snapshot.authorization().authorizedAt(), checkpoint);
        if (!bundle.head().equals(snapshot.exportedHeads().get(key))) throw failure("Git stand exported head changed after inventory");
        return bundle;
    }
    private static void requireState(SnapshotContext snapshot, BackupRepositoryKey key, GitStandBackupSource.Stand stand) throws IOException {
        if (!stand.state().equals(snapshot.repositories().get(key))) throw failure("Git stand source state changed after inventory");
    }
    private static List<BackupRepositoryKey> ordered(Set<BackupRepositoryKey> keys) {
        return keys.stream().sorted(Comparator.comparing(BackupRepositoryKey::repositoryId)
                .thenComparing(BackupRepositoryKey::workspaceId, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }
    private static void serialize(List<Record> records, OutputStream output, BackupCheckpoint checkpoint) throws IOException {
        try (var generator = JSON.getFactory().createGenerator(output).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
            generator.setRootValueSeparator(null);
            for (var record : records) { checkpoint.check(); JSON.writeValue(generator, record); generator.writeRaw('\n'); }
            generator.flush(); checkpoint.check();
        }
    }
    public record RepositoryRecord(String recordType, BackupRepositoryKey repository, String archiveId, SnapshotContext.RepositoryState source) { }
    public record FileRecord(String recordType, String archiveId, String path, int mode, BackupEntry entry) { }
    private final class Budget {
        int files; long bytes;
        void add(GitStandBackupSource.File file) throws IOException {
            if (++files > limits.git().maxFiles() || file.length() > limits.git().maxTotalBytes() - bytes)
                throw failure("Git stand closure limit exceeded");
            bytes += file.length();
        }
    }
    private static final class DigestOutput extends OutputStream {
        final MessageDigest digest; long bytes;
        DigestOutput() { try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
        @Override public void write(int value) { digest.update((byte) value); bytes = Math.addExact(bytes, 1); }
        @Override public void write(byte[] value, int offset, int length) { digest.update(value, offset, length); bytes = Math.addExact(bytes, length); }
    }
}
