package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import javax.sql.DataSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Fenced current/selected file evidence, including the durable editor overlay and stand projection. */
public final class GitStandBackupSource {
    private static final String DSL = DslGitRepository.DSL_FILENAME;
    private static final int BUFFER = 8_192;
    private final DataSource database;
    private final ExistingGitBackupRepositories repositories;
    private final BackupDocumentProjector projector;
    public record Limits(GitTreeCapture.Limits git, int maxDocumentBytes) {
        public Limits {
            Objects.requireNonNull(git);
            if (maxDocumentBytes < 1 || maxDocumentBytes > PortableRows.MAX_RECORD_BYTES)
                throw new IllegalArgumentException("Invalid stand document limit");
        }
    }
    public record File(String path, int mode, long length, String sha256) { }
    public GitStandBackupSource(DataSource database, ExistingGitBackupRepositories repositories, BackupDocumentProjector projector) {
        this.database = Objects.requireNonNull(database); this.repositories = Objects.requireNonNull(repositories); this.projector = Objects.requireNonNull(projector);
    }

    /** Caller holds the installation fence and fresh authorization through the final copy and close. */
    public Stand open(AuthorizedBackupRequest authorization, BackupRepositoryKey key, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        ExistingGitBackupRepositories.ReadSession session = null;
        try {
            Objects.requireNonNull(limits); checkpoint.check();
            session = repositories.open(authorization, key, checkpoint);
            var tree = session.captureStand(limits.git(), checkpoint).orElse(null);
            boolean selected = authorization.request().profile() == BackupProfile.SELECTED_VERSION;
            var saved = selected || key.workspaceId() == null ? null : savedProof(key, session.branch(), limits, checkpoint);
            String commit = tree == null ? null : tree.commit();
            if (saved != null && !Objects.equals(saved.state().checkpointCommit(), commit))
                throw failure("Saved editor checkpoint and captured Git head disagree; reconcile the version first");
            var originals = new TreeMap<String, GitTreeCapture.File>();
            if (tree != null) tree.files().forEach(file -> originals.put(file.path(), file));
            var paths = new TreeSet<>(originals.keySet()); if (saved != null) paths.add(DSL);
            if (paths.size() > limits.git().maxFiles()) throw failure("Effective stand file limit exceeded");
            PortableGitPaths.requireTree(paths);
            var evidence = new LinkedHashMap<File, Evidence>(); var documents = new ArrayList<BackupDocumentReference>(); long total = 0;
            for (String path : paths) {
                checkpoint.check(); var original = originals.get(path); boolean working = saved != null && path.equals(DSL);
                boolean document = path.toLowerCase(Locale.ROOT).endsWith(".taxdsl");
                int mode = original == null ? FileMode.REGULAR_FILE.getBits() : original.mode();
                File file;
                if (document) {
                    byte[] raw = working ? savedBytes(saved, key, session.branch(), limits, checkpoint) : document(tree, original, limits, checkpoint);
                    if (working && saved.state().semanticRevision() == saved.state().checkpointRevision()
                            && !hash(raw).equals(original == null ? hash(new byte[0]) : original.sha256()))
                        throw failure("Completed editor checkpoint content disagrees with its Git document");
                    byte[] projected = project(raw, limits, checkpoint);
                    file = new File(path, mode, projected.length, hash(projected));
                    if (original != null && (!working || original.sha256().equals(saved.sha256())))
                        documents.add(new BackupDocumentReference(key, commit, path, original.sha256()));
                } else file = new File(path, mode, original.length(), original.sha256());
                if (file.length() > limits.git().maxFileBytes() || file.length() > limits.git().maxTotalBytes() - total)
                    throw failure("Effective stand byte limit exceeded");
                total += file.length(); evidence.put(file, new Evidence(original, document, working));
            }
            String branch = selected ? null : Constants.R_HEADS + session.branch();
            var state = new SnapshotContext.RepositoryState(branch == null || commit == null ? Map.of() : Map.of(branch, commit), branch,
                    saved == null ? Map.of() : Map.of(session.branch(), saved.state()), commit == null ? Set.of() : Set.of(commit));
            checkpoint.check();
            return new Stand(session, tree, limits, saved, evidence, state, documents);
        } catch (IOException | RuntimeException | Error problem) {
            if (session != null) try { session.close(); } catch (IOException | RuntimeException cleanup) { problem.addSuppressed(cleanup); }
            if (problem instanceof Error error) throw error;
            throw sanitized(problem);
        }
    }

    /** No document payloads are retained here: each copy rereads and checks one bounded document. */
    public final class Stand implements AutoCloseable {
        private final ExistingGitBackupRepositories.ReadSession session;
        private final GitTreeCapture tree;
        private final Limits limits;
        private final SavedProof saved;
        private final Map<File, Evidence> evidence;
        private final List<File> files;
        private final SnapshotContext.RepositoryState state;
        private final List<BackupDocumentReference> documents;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Stand(ExistingGitBackupRepositories.ReadSession session, GitTreeCapture tree, Limits limits, SavedProof saved,
                      Map<File, Evidence> evidence, SnapshotContext.RepositoryState state, List<BackupDocumentReference> documents) {
            this.session = session; this.tree = tree; this.limits = limits; this.saved = saved;
            this.evidence = Map.copyOf(evidence); files = List.copyOf(evidence.keySet()); this.state = state; this.documents = List.copyOf(documents);
        }
        public List<File> files() { return files; }
        /** Source capture evidence. This does not declare an exported Git representation. */
        public SnapshotContext.RepositoryState state() { return state; }
        public List<BackupDocumentReference> documents() { return documents; }
        public void copy(File file, OutputStream output, BackupCheckpoint checkpoint) throws IOException {
            try {
                Objects.requireNonNull(output); var check = (BackupCheckpoint) () -> check(checkpoint); check.check();
                var source = evidence.get(file); if (source == null) throw failure("File is outside the captured stand");
                if (source.document()) {
                    byte[] raw;
                    if (source.working()) {
                        raw = savedBytes(saved, session.key(), session.branch(), limits, check);
                    } else raw = document(tree, source.original(), limits, check);
                    byte[] projected = project(raw, limits, check);
                    if (projected.length != file.length() || !hash(projected).equals(file.sha256()))
                        throw failure("Projected stand content changed after capture");
                    for (int offset = 0; offset < projected.length; offset += BUFFER) {
                        check.check(); output.write(projected, offset, Math.min(BUFFER, projected.length - offset)); check.check();
                    }
                } else tree.copy(source.original(), output, check);
                check.check();
            } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
        }
        public BackupEntry writeFile(File file, String path, ComponentSink sink) throws IOException {
            try {
                if (!evidence.containsKey(file)) throw failure("File is outside the captured stand");
                return GitTreeCapture.writeGenerated(new BackupEntry(path, file.length(), file.sha256()), sink, output -> copy(file, output, sink::checkpoint));
            } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
        }
        private void check(BackupCheckpoint checkpoint) throws IOException {
            if (!active.get()) throw failure("Git stand capture session is closed");
            checkpoint.check();
            if (!active.get()) throw failure("Git stand capture session is closed");
        }
        @Override public void close() throws IOException { if (active.getAndSet(false)) session.close(); }
    }

    private SavedProof savedProof(BackupRepositoryKey key, String branch, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        var saved = saved(key, branch, limits, checkpoint); return saved == null ? null : saved.proof();
    }
    private byte[] savedBytes(SavedProof expected, BackupRepositoryKey key, String branch, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        var current = saved(key, branch, limits, checkpoint);
        if (current == null || !expected.equals(current.proof())) throw failure("Saved editor evidence changed after capture");
        return current.bytes();
    }
    private Saved saved(BackupRepositoryKey key, String branch, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        String scope = RepositoryContext.workspace(key.repositoryId(), key.workspaceId(), branch, "backup-reader").repositoryWorkspaceScopeKey();
        String sql = "select scope_id,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint from editor_workspace where repository_id=? and workspace_id=? and branch=?";
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (Rollback rollback = connection::rollback; var statement = connection.prepareStatement(sql)) {
                statement.setMaxRows(2); statement.setQueryTimeout(60); statement.setString(1, key.repositoryId()); statement.setString(2, key.workspaceId()); statement.setString(3, branch);
                checkpoint.check();
                try (var row = statement.executeQuery()) {
                    if (!row.next()) { checkpoint.check(); return null; }
                    if (!scope.equals(text(row, "scope_id", 64, checkpoint))) throw failure("Saved editor routing evidence is inconsistent");
                    if (text(row, "pending_checkpoint", 36, checkpoint) != null) throw failure("Saved editor checkpoint is pending; complete or recover it before stand capture");
                    long revision = number(row, "semantic_revision"), checkpointRevision = number(row, "checkpoint_revision");
                    var state = new SnapshotContext.WorkingState(revision, text(row, "checkpoint_commit", 40, checkpoint), checkpointRevision);
                    String framed = text(row, "dsl", limits.maxDocumentBytes() + 2, checkpoint);
                    if (framed == null || !framed.startsWith("1:")) throw failure("Unsupported saved editor body version");
                    byte[] bytes = utf8(framed.substring(2), limits, checkpoint);
                    if (row.next()) throw failure("Duplicate saved editor routing evidence");
                    checkpoint.check(); return new Saved(new SavedProof(state, hash(bytes)), bytes);
                }
            }
        } catch (SQLException problem) { throw sanitized(problem); }
    }

    private static String text(ResultSet row, String column, int maximum, BackupCheckpoint checkpoint) throws IOException, SQLException {
        try (var reader = row.getCharacterStream(column)) {
            if (reader == null) return null;
            var value = new StringBuilder(); var buffer = new char[Math.min(BUFFER, maximum + 1)];
            for (;;) {
                checkpoint.check(); int count = reader.read(buffer); if (count < 0) break;
                if (count == 0) { int single = reader.read(); if (single < 0) break; buffer[0] = (char) single; count = 1; }
                if (count > maximum - value.length()) throw failure("Saved editor text limit exceeded");
                value.append(buffer, 0, count);
            }
            checkpoint.check(); return value.toString();
        }
    }
    private static long number(ResultSet row, String column) throws SQLException, IOException {
        long value = row.getLong(column); if (row.wasNull()) throw failure("Saved editor revision is missing"); return value;
    }
    private static byte[] document(GitTreeCapture tree, GitTreeCapture.File file, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        if (file.length() > limits.maxDocumentBytes()) throw failure("Stand document byte limit exceeded");
        var output = new ByteArrayOutputStream((int) file.length()); tree.copy(file, output, checkpoint); return output.toByteArray();
    }
    private byte[] project(byte[] raw, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check();
        String document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
        String projected = projector.currentState(document); checkpoint.check(); return utf8(projected, limits, checkpoint);
    }
    private static byte[] utf8(String value, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        if (value == null) throw failure("Stand document projection is missing");
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (i % BUFFER == 0) checkpoint.check(); char c = value.charAt(i);
            if (c < 0x80) count++;
            else if (c < 0x800) count += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw failure("Stand document is not valid Unicode");
                count += 4;
            } else if (Character.isLowSurrogate(c)) throw failure("Stand document is not valid Unicode");
            else count += 3;
            if (count > limits.maxDocumentBytes()) throw failure("Stand document byte limit exceeded");
        }
        checkpoint.check(); return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private record Evidence(GitTreeCapture.File original, boolean document, boolean working) { }
    private record SavedProof(SnapshotContext.WorkingState state, String sha256) { }
    private record Saved(SavedProof proof, byte[] bytes) { }
    @FunctionalInterface private interface Rollback extends AutoCloseable { @Override void close() throws SQLException; }
}
