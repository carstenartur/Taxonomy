package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.workspace.service.RepositoryContext;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.reference;
import static com.taxonomy.exchange.backup.PortableRows.text;
import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Source-owned commit references, resolved only within the explicitly authorized Git inventory. */
public final class WorkspaceGitHistoryReferences {
    public enum Field {
        REPOSITORY_FORK_POINT, REPOSITORY_LAST_FETCH,
        WORKSPACE_BASE, WORKSPACE_CURRENT, WORKSPACE_LAST_FETCHED, WORKSPACE_LAST_INTEGRATED,
        SYNC_LAST_SYNCED, SYNC_LAST_PUBLISHED, EDITOR_CHECKPOINT,
        CHECKPOINT_EXPECTED, CHECKPOINT_COMMIT, INDEX_COMMIT, CONTEXT_FROM, CONTEXT_TO
    }
    public record CommitReference(SourceRecordId sourceId, Field field, BackupRepositoryKey repository, String commit) {
        public CommitReference {
            Objects.requireNonNull(sourceId); Objects.requireNonNull(field); Objects.requireNonNull(repository);
            if (commit == null || !commit.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid original commit identity");
        }
    }
    public record Capture(Map<BackupRepositoryKey, Set<String>> requiredCommits,
                          Map<BackupRepositoryKey, Map<String, SnapshotContext.WorkingState>> workingStates,
                          List<CommitReference> references, List<WorkingCopyProof> workingCopies) {
        public Capture(Map<BackupRepositoryKey, Set<String>> requiredCommits,
                       Map<BackupRepositoryKey, Map<String, SnapshotContext.WorkingState>> workingStates,
                       List<CommitReference> references) { this(requiredCommits, workingStates, references, List.of()); }
        public Capture {
            var commits = new HashMap<BackupRepositoryKey, Set<String>>();
            requiredCommits.forEach((key, values) -> commits.put(key, Set.copyOf(values)));
            requiredCommits = Map.copyOf(commits);
            var states = new HashMap<BackupRepositoryKey, Map<String, SnapshotContext.WorkingState>>();
            workingStates.forEach((key, values) -> states.put(key, Map.copyOf(values)));
            workingStates = Map.copyOf(states); references = List.copyOf(references); workingCopies = List.copyOf(workingCopies);
            if (!requiredCommits.keySet().equals(workingStates.keySet())) throw new IllegalArgumentException("Inconsistent Git inventory");
        }
    }
    public record WorkingCopyProof(SourceRecordId sourceId, BackupRepositoryKey repository, String branch,
                                   SnapshotContext.WorkingState state, String sha256, String interruptedCheckpoint) {
        public WorkingCopyProof {
            Objects.requireNonNull(sourceId); Objects.requireNonNull(repository); Objects.requireNonNull(state);
            if (!sourceId.kind().equals("workspace.editor") || repository.workspaceId() == null || branch == null
                    || !Repository.isValidRefName(Constants.R_HEADS + branch) || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid raw working copy proof");
            if (interruptedCheckpoint != null && !UUID.fromString(interruptedCheckpoint).toString().equals(interruptedCheckpoint))
                throw new IllegalArgumentException("Invalid interrupted checkpoint identity");
        }
    }
    private static final Comparator<BackupRepositoryKey> KEY_ORDER = Comparator.comparing(BackupRepositoryKey::repositoryId)
            .thenComparing(BackupRepositoryKey::workspaceId, Comparator.nullsFirst(Comparator.naturalOrder()));
    private static final int MAX_REFERENCES = 100_000;
    private static final int MAX_MEMBERSHIP_PROBES = 200_000;
    private final PortableRows rows;
    private final ExistingGitBackupRepositories repositories;
    private final int maximumReferences;
    public WorkspaceGitHistoryReferences(DataSource database, ExistingGitBackupRepositories repositories) {
        this(database, repositories, MAX_REFERENCES);
    }
    public WorkspaceGitHistoryReferences(DataSource database, ExistingGitBackupRepositories repositories, int maximumReferences) {
        rows = new PortableRows(database); this.repositories = Objects.requireNonNull(repositories);
        if (maximumReferences < 1 || maximumReferences > MAX_REFERENCES) throw new IllegalArgumentException("Invalid commit reference budget");
        this.maximumReferences = maximumReferences;
    }
    public Capture inspect(AuthorizedBackupRequest authorization, Set<BackupRepositoryKey> keys,
                           GitHistoryCapture.Limits limits, BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint.check();
            if (!authorization.request().profile().includesHistory()) throw failure("Database history closure requires history authorization");
            keys = Set.copyOf(keys); var scope = new BackupRowScope(authorization, keys);
            var result = new Builder(authorization, keys, limits, checkpoint);
            var repositoryIds = scope.repositoryIds("repository_id");
            read("select repository_id,upstream_repository_id,fork_point_commit,last_fetch_commit from system_repository", repositoryIds, "repository_id", checkpoint, row -> {
                String repository = text(row, "repository_id");
                if (!result.repositoryIds.contains(repository)) throw failure("Repository metadata belongs to another capture scope");
                var source = new SourceRecordId("workspace.repository", repository);
                var candidates = new ArrayList<BackupRepositoryKey>(); candidates.add(new BackupRepositoryKey(repository, null));
                String upstream = text(row, "upstream_repository_id");
                if (upstream != null) candidates.add(new BackupRepositoryKey(upstream, null));
                result.resolve(source, Field.REPOSITORY_FORK_POINT, candidates, text(row, "fork_point_commit"));
                result.resolve(source, Field.REPOSITORY_LAST_FETCH, candidates, text(row, "last_fetch_commit"));
            });
            read("select source_repository_id,workspace_id,base_commit,current_commit,last_fetched_commit,last_integrated_commit from user_workspace",
                    scope.repositories("source_repository_id", "workspace_id"), "workspace_id", checkpoint, row -> {
                var key = result.key(row, "source_repository_id", "workspace_id");
                var source = new SourceRecordId("workspace.workspace", key.workspaceId());
                var related = List.of(new BackupRepositoryKey(key.repositoryId(), null), key);
                result.resolve(source, Field.WORKSPACE_BASE, related, text(row, "base_commit"));
                result.resolve(source, Field.WORKSPACE_CURRENT, List.of(key), text(row, "current_commit"));
                result.resolve(source, Field.WORKSPACE_LAST_FETCHED, related, text(row, "last_fetched_commit"));
                result.resolve(source, Field.WORKSPACE_LAST_INTEGRATED, related, text(row, "last_integrated_commit"));
            });
            read("select w.source_repository_id,w.workspace_id as captured_workspace_id,s.workspace_id,s.id,s.last_synced_commit_id,s.last_published_commit_id from sync_state s join user_workspace w on w.workspace_id=s.workspace_id",
                    scope.repositories("w.source_repository_id", "w.workspace_id"), "s.id", checkpoint, row -> {
                var key = result.key(row, "source_repository_id", "captured_workspace_id");
                linked(row, "captured_workspace_id", "workspace_id");
                var source = reference(row, "workspace.sync", "id");
                var related = List.of(key, new BackupRepositoryKey(key.repositoryId(), null));
                result.resolve(source, Field.SYNC_LAST_SYNCED, related, text(row, "last_synced_commit_id"));
                result.resolve(source, Field.SYNC_LAST_PUBLISHED, related, text(row, "last_published_commit_id"));
            });
            read("select scope_id,repository_id,workspace_id,branch,semantic_revision,checkpoint_commit,checkpoint_revision,dsl,pending_checkpoint from editor_workspace",
                    scope.repositories("repository_id", "workspace_id"), "scope_id", checkpoint, row -> {
                var key = result.key(row, "repository_id", "workspace_id");
                String branch = text(row, "branch"), scopeId = text(row, "scope_id");
                if (key.workspaceId() == null || branch == null || !Repository.isValidRefName(Constants.R_HEADS + branch)
                        || !RepositoryContext.workspace(key.repositoryId(), key.workspaceId(), branch, "backup-reader")
                                .repositoryWorkspaceScopeKey().equals(scopeId)) throw failure("Stored editor scope is inconsistent");
                String commit = text(row, "checkpoint_commit");
                var state = new SnapshotContext.WorkingState(number(row, "semantic_revision"), commit, number(row, "checkpoint_revision"));
                if (++result.workingStateCount > BackupLimits.MAX_ITEMS || result.workingStates.get(key).putIfAbsent(branch, state) != null)
                    throw failure("Duplicate or excessive stored working states");
                result.workingCopies.add(new WorkingCopyProof(new SourceRecordId("workspace.editor", scopeId), key, branch, state,
                        workingCopyHash(text(row, "dsl"), checkpoint), text(row, "pending_checkpoint")));
                result.resolve(new SourceRecordId("workspace.editor", scopeId), Field.EDITOR_CHECKPOINT, List.of(key), commit);
            });
            read("select w.repository_id,w.workspace_id,w.scope_id as captured_editor_id,c.scope_id,c.id,c.expected_commit,c.commit_id from editor_checkpoint c join editor_workspace w on w.scope_id=c.scope_id",
                    scope.repositories("w.repository_id", "w.workspace_id"), "c.id", checkpoint, row -> {
                var key = result.key(row, "repository_id", "workspace_id"); linked(row, "captured_editor_id", "scope_id");
                var source = reference(row, "workspace.checkpoint", "id");
                result.resolve(source, Field.CHECKPOINT_EXPECTED, List.of(key), text(row, "expected_commit"));
                result.resolve(source, Field.CHECKPOINT_COMMIT, List.of(key), text(row, "commit_id"));
            });
            read("select id,repository_id,workspace_id,commit_id from architecture_commit_index",
                    scope.repositories("repository_id", "workspace_id"), "id", checkpoint, row -> {
                var key = result.key(row, "repository_id", "workspace_id");
                result.resolve(reference(row, "workspace.commit-index", "id"), Field.INDEX_COMMIT, List.of(key), text(row, "commit_id"));
            });
            if (scope.installation()) {
                // Context UUIDs have no repository-ownership contract. Only the explicit Git catalogue may prove these legacy hashes.
                var catalogue = keys.stream().sorted(KEY_ORDER).toList();
                read("select id,from_commit_id,to_commit_id from context_history_record", new Query("1=1", List.of()), "id", checkpoint, row -> {
                    var source = reference(row, "workspace.context-history", "id");
                    result.resolve(source, Field.CONTEXT_FROM, catalogue, text(row, "from_commit_id"));
                    result.resolve(source, Field.CONTEXT_TO, catalogue, text(row, "to_commit_id"));
                });
            }
            checkpoint.check(); return result.finish();
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    private void read(String select, Query predicate, String order, BackupCheckpoint checkpoint, RowRead action) throws IOException {
        rows.visit(new Query(select + " where " + predicate.sql() + " order by " + order, predicate.parameters()), row -> {
            checkpoint.check(); action.read(row); checkpoint.check(); return new Visited();
        }, ignored -> checkpoint.check());
    }
    private static void linked(ResultSet row, String first, String second) throws SQLException, IOException {
        if (!Objects.equals(text(row, first), text(row, second))) throw failure("History reference has an inconsistent parent scope");
    }
    private static long number(ResultSet row, String column) throws SQLException, IOException {
        long value = row.getLong(column); if (row.wasNull()) throw failure("Stored working revision is missing"); return value;
    }
    private static String workingCopyHash(String framed, BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check(); String raw = PortableRows.unframe(framed);
        var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(raw));
        if (bytes.remaining() > PortableRows.MAX_RECORD_BYTES) throw failure("Stored working copy byte budget exceeded");
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        while (bytes.hasRemaining()) {
            checkpoint.check(); int length = Math.min(bytes.remaining(), 8192);
            var chunk = bytes.slice(); chunk.limit(length); hash.update(chunk); bytes.position(bytes.position() + length);
        }
        checkpoint.check(); return HexFormat.of().formatHex(hash.digest());
    }
    private record Visited() { }
    private record Probe(BackupRepositoryKey key, String commit) { }
    private record ReferenceKey(SourceRecordId sourceId, Field field) { }
    @FunctionalInterface private interface RowRead { void read(ResultSet row) throws SQLException, IOException; }
    private final class Builder {
        final AuthorizedBackupRequest authorization;
        final Set<BackupRepositoryKey> keys;
        final Set<String> repositoryIds;
        final GitHistoryCapture.Limits limits;
        final BackupCheckpoint checkpoint;
        final Map<BackupRepositoryKey, Set<String>> required = new HashMap<>();
        final Map<BackupRepositoryKey, Map<String, SnapshotContext.WorkingState>> workingStates = new HashMap<>();
        final Map<ReferenceKey, CommitReference> references = new LinkedHashMap<>();
        final List<WorkingCopyProof> workingCopies = new ArrayList<>();
        final Map<Probe, Boolean> membership = new HashMap<>();
        int workingStateCount;
        Builder(AuthorizedBackupRequest authorization, Set<BackupRepositoryKey> keys, GitHistoryCapture.Limits limits, BackupCheckpoint checkpoint) {
            this.authorization = authorization; this.keys = keys; this.limits = Objects.requireNonNull(limits); this.checkpoint = checkpoint;
            repositoryIds = keys.stream().map(BackupRepositoryKey::repositoryId).collect(java.util.stream.Collectors.toSet());
            for (var key : keys) { required.put(key, new TreeSet<>()); workingStates.put(key, new TreeMap<>()); }
        }
        BackupRepositoryKey key(ResultSet row, String repository, String workspace) throws SQLException, IOException {
            var key = new BackupRepositoryKey(text(row, repository), text(row, workspace));
            if (!keys.contains(key)) throw failure("History row is outside the exact authorized Git inventory"); return key;
        }
        void resolve(SourceRecordId source, Field field, List<BackupRepositoryKey> candidates, String commit) throws IOException {
            checkpoint.check(); if (commit == null) return;
            if (!commit.matches("[a-f0-9]{40}") || source == null) throw failure("Invalid database commit reference");
            var identity = new ReferenceKey(source, field);
            if (references.size() >= maximumReferences || references.containsKey(identity)) throw failure("Duplicate or excessive database commit references");
            for (var key : candidates) {
                checkpoint.check(); if (!keys.contains(key)) continue;
                var probe = new Probe(key, commit); Boolean found = membership.get(probe);
                if (found == null) {
                    if (membership.size() >= MAX_MEMBERSHIP_PROBES) throw failure("Git membership proof budget exceeded");
                    try (var session = repositories.open(authorization, key, checkpoint)) { found = session.containsHistoryCommit(commit, limits, checkpoint); }
                    membership.put(probe, found);
                }
                if (found) {
                    references.put(identity, new CommitReference(source, field, key, commit));
                    var commits = required.get(key); commits.add(commit);
                    if (commits.size() > BackupLimits.MAX_ITEMS) throw failure("Required original commit budget exceeded");
                    return;
                }
            }
            throw failure("Database commit reference is missing from the authorized Git history closure");
        }
        Capture finish() {
            return new Capture(required, workingStates, references.values().stream().sorted(Comparator
                    .comparing((CommitReference value) -> value.sourceId().kind()).thenComparing(value -> value.sourceId().value())
                    .thenComparing(value -> value.field().name())).toList(), workingCopies.stream()
                    .sorted(Comparator.comparing(value -> value.sourceId().value())).toList());
        }
    }
}
