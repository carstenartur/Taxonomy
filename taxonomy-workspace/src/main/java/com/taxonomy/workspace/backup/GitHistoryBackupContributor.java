package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.util.*;
import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Complete history payloads backed by the exact authorized database and Git closure. */
public final class GitHistoryBackupContributor implements BackupDataContributor {
    private final ExistingGitBackupRepositories repositories;
    private final GitStandBackupSource workingCopies;
    private final WorkspaceGitHistoryReferences databaseReferences;
    private final GitHistoryCapture.Limits limits;
    private final GitStandBackupSource.Limits workingLimits;
    private Plan plan;
    public GitHistoryBackupContributor(ExistingGitBackupRepositories repositories, GitStandBackupSource workingCopies,
            WorkspaceGitHistoryReferences databaseReferences, GitHistoryCapture.Limits limits, GitStandBackupSource.Limits workingLimits) {
        this.repositories = Objects.requireNonNull(repositories); this.workingCopies = Objects.requireNonNull(workingCopies);
        this.databaseReferences = Objects.requireNonNull(databaseReferences); this.limits = Objects.requireNonNull(limits);
        this.workingLimits = Objects.requireNonNull(workingLimits);
    }
    public List<BackupManifest.Repository> inspect(AuthorizedBackupRequest authorization, Set<BackupRepositoryKey> keys, BackupCheckpoint checkpoint) throws IOException {
        try {
            requireProfile(authorization.request()); checkpoint.check(); keys = Set.copyOf(keys);
            if (plan != null) throw failure("History contributor already has a fenced capture plan");
            var database = databaseReferences.inspect(authorization, keys, limits, checkpoint);
            var entries = new ArrayList<BackupManifest.Repository>(); var evidence = new HashMap<BackupRepositoryKey, Evidence>();
            var budget = new Budget();
            for (var key : ordered(keys)) {
                checkpoint.check();
                try (var session = repositories.open(authorization, key, checkpoint);
                     var working = openWorking(authorization, key, checkpoint)) {
                    var history = session.captureHistory(database.requiredCommits().get(key), database.workingStates().get(key), limits, checkpoint);
                    if (authorization.request().gitRepresentation() == GitRepresentation.BUNDLE && history.objectCount() == 0)
                        throw failure("An empty Git history requires a bare repository representation");
                    budget.add(history, working); budget.documents(history.documents(key, checkpoint).size());
                    var proof = evidence(history, working); evidence.put(key, proof);
                    String head = working == null ? history.state().refs().get("HEAD")
                            : working.state().refs().get(working.state().symbolicHead());
                    entries.add(new BackupManifest.Repository(key, UUID.randomUUID().toString(), authorization.request().gitRepresentation(), history.state(), null, head, null));
                }
            }
            if (!database.equals(databaseReferences.inspect(authorization, keys, limits, checkpoint)))
                throw failure("Database history closure changed during inventory");
            checkpoint.check(); plan = new Plan(authorization, database, Map.copyOf(evidence), List.copyOf(entries));
            return plan.repositories();
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    public List<BackupDocumentReference> documents(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        try {
            requireSnapshot(snapshot, checkpoint); var documents = new ArrayList<BackupDocumentReference>(); var budget = new Budget();
            scan(snapshot, checkpoint, budget, (key, history, working) -> {
                var current = history.documents(key, checkpoint); budget.documents(current.size()); documents.addAll(current);
            });
            requireDatabase(snapshot, checkpoint); return List.copyOf(documents);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    /** Inspects original document bytes under the exact fenced plan and one shared selection budget. */
    public GitHistoryCapture.DocumentVisit visitDocuments(SnapshotContext snapshot, GitHistoryCapture.DocumentLimits bounds,
            BackupCheckpoint checkpoint, GitHistoryCapture.DocumentVisitor visitor) throws IOException {
        try {
            Objects.requireNonNull(bounds); Objects.requireNonNull(visitor); requireSnapshot(snapshot, checkpoint);
            int[] documents = {0}; long[] bytes = {0};
            scan(snapshot, checkpoint, new Budget(), (key, history, working) -> {
                var remaining = new GitHistoryCapture.DocumentLimits(bounds.maxDocuments() - documents[0], bounds.maxDocumentBytes(), bounds.maxTotalBytes() - bytes[0]);
                var visited = history.visitDocuments(key, remaining, checkpoint, visitor);
                documents[0] += visited.documents(); bytes[0] += visited.bytes();
            });
            requireDatabase(snapshot, checkpoint); return new GitHistoryCapture.DocumentVisit(documents[0], bytes[0]);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("workspace"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("storage.jgit.objects-refs-reflogs", "com.taxonomy.workspace.model.GitReflogProvenanceRecord"); }
    @Override public List<String> omissions(BackupProfile profile) { return List.of(); }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        try {
            requireSnapshot(snapshot, sink::checkpoint);
            var request = snapshot.authorization().request();
            scan(snapshot, sink::checkpoint, new Budget(), (key, history, working) -> {
                String archiveId = snapshot.repositoryArchiveIds().get(key);
                if (working == null) history.write(archiveId, request.gitRepresentation(), request.profile(), sink);
                else history.writeWorkingTree(archiveId, request.profile(), working, sink);
            });
            requireDatabase(snapshot, sink::checkpoint);
            records("git-commit-references", request.profile(), plan.database().references(), sink);
            records("git-working-copy-proofs", request.profile(), plan.database().workingCopies(), sink);
            // A later repository or metadata write may not conceal movement in an earlier source.
            scan(snapshot, sink::checkpoint, new Budget(), (key, history, working) -> { });
            requireDatabase(snapshot, sink::checkpoint);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    private static void records(String kind, BackupProfile profile, List<? extends Record> records, ComponentSink sink) throws IOException {
        GitTreeCapture.writeGenerated("data/workspace/" + kind + ".ndjson", sink, output -> {
            var json = PortableRows.json();
            try (var generator = json.getFactory().createGenerator(output).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                generator.setRootValueSeparator(null);
                json.writeValue(generator, new PortableRows.Header(1, kind, profile)); generator.writeRaw('\n');
                for (var record : records) {
                    sink.checkpoint(); json.writeValue(generator, record); generator.writeRaw('\n');
                }
                generator.flush(); sink.checkpoint();
            }
        });
    }
    private GitStandBackupSource.Stand openWorking(AuthorizedBackupRequest authorization, BackupRepositoryKey key, BackupCheckpoint checkpoint) throws IOException {
        return authorization.request().gitRepresentation() == GitRepresentation.WORKTREE
                ? workingCopies.openHistoryWorkingCopy(authorization, key, workingLimits, checkpoint) : null;
    }
    private void scan(SnapshotContext snapshot, BackupCheckpoint checkpoint, Budget budget, Visitor visitor) throws IOException {
        for (var key : ordered(snapshot.repositories().keySet())) {
            checkpoint.check();
            try (var session = repositories.open(snapshot.authorization(), key, checkpoint);
                 var working = openWorking(snapshot.authorization(), key, checkpoint)) {
                var database = plan.database();
                var history = session.captureHistory(database.requiredCommits().get(key), database.workingStates().get(key), limits, checkpoint);
                budget.add(history, working);
                if (!evidence(history, working).equals(plan.evidence().get(key))) throw failure("Git history changed after inventory");
                visitor.accept(key, history, working); checkpoint.check();
            }
        }
    }
    private void requireSnapshot(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check(); requireProfile(snapshot.authorization().request());
        if (plan == null || !plan.authorization().equals(snapshot.authorization())
                || !Integer.valueOf(schemaVersion()).equals(snapshot.componentVersions().get(componentId()))
                || !plan.evidence().keySet().equals(snapshot.repositories().keySet())
                || !snapshot.repositoryArchiveIds().keySet().equals(snapshot.repositories().keySet()))
            throw failure("History payload requires its own authorized capture plan");
        var heads = new HashMap<BackupRepositoryKey, String>();
        for (var repository : plan.repositories()) {
            if (!repository.archiveId().equals(snapshot.repositoryArchiveIds().get(repository.id()))
                    || !repository.captured().equals(snapshot.repositories().get(repository.id())))
                throw failure("History manifest source differs from inventory");
            if (repository.exportedHead() != null) heads.put(repository.id(), repository.exportedHead());
        }
        if (!heads.equals(snapshot.exportedHeads())) throw failure("History exported heads differ from inventory");
        requireDatabase(snapshot, checkpoint);
    }
    private void requireDatabase(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        if (!plan.database().equals(databaseReferences.inspect(snapshot.authorization(), snapshot.repositories().keySet(), limits, checkpoint)))
            throw failure("Database history reference or raw working copy changed after inventory");
        checkpoint.check();
    }
    private static Evidence evidence(GitHistoryCapture history, GitStandBackupSource.Stand working) throws IOException {
        if (working != null) {
            var source = working.state();
            if (!Objects.equals(source.refs().get(source.symbolicHead()), history.state().refs().get(source.symbolicHead()))
                    || !history.state().workingStates().entrySet().containsAll(source.workingStates().entrySet()))
                throw failure("Raw working copy differs from history inventory");
        }
        return new Evidence(history.state(), history.symbolicRefs(), history.reflogs(), history.importedReflogs(), history.objectCount(), history.objectBytes(),
                working == null ? null : working.state(), working == null ? List.of() : working.files());
    }
    private static void requireProfile(BackupRequest request) throws IOException {
        if (!request.profile().includesHistory() || request.gitRepresentation() == GitRepresentation.NONE)
            throw failure("Complete Git history requires an authorized history profile");
    }
    private static List<BackupRepositoryKey> ordered(Set<BackupRepositoryKey> keys) {
        return keys.stream().sorted(Comparator.comparing(BackupRepositoryKey::repositoryId)
                .thenComparing(BackupRepositoryKey::workspaceId, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }
    private record Evidence(SnapshotContext.RepositoryState state, Map<String, String> aliases, List<GitHistoryCapture.Reflog> reflogs,
                            List<GitReflogProvenance.Event> importedReflogs,
                            int objects, long bytes, SnapshotContext.RepositoryState working, List<GitStandBackupSource.File> workingFiles) { }
    private record Plan(AuthorizedBackupRequest authorization, WorkspaceGitHistoryReferences.Capture database,
                        Map<BackupRepositoryKey, Evidence> evidence, List<BackupManifest.Repository> repositories) { }
    @FunctionalInterface private interface Visitor {
        void accept(BackupRepositoryKey key, GitHistoryCapture history, GitStandBackupSource.Stand working) throws IOException;
    }
    private final class Budget {
        long objects, bytes, refs, reflogs, documents, workingFiles, workingBytes;
        void add(GitHistoryCapture history, GitStandBackupSource.Stand working) throws IOException {
            if (history.objectCount() > limits.maxObjects() - objects || history.objectBytes() > limits.maxTotalBytes() - bytes
                    || history.state().refs().size() > limits.maxRefs() - refs
                    || history.reflogs().size() + history.importedReflogs().size() > limits.maxReflogEntries() - reflogs)
                throw failure("Complete Git history inventory exceeds its shared budget");
            objects += history.objectCount(); bytes += history.objectBytes(); refs += history.state().refs().size();
            reflogs += history.reflogs().size() + history.importedReflogs().size();
            if (working != null) for (var file : working.files()) {
                if (++workingFiles > workingLimits.git().maxFiles() || file.length() > workingLimits.git().maxTotalBytes() - workingBytes)
                    throw failure("Raw working copies exceed their shared budget");
                workingBytes += file.length();
            }
        }
        void documents(long count) throws IOException {
            if (count > BackupLimits.MAX_ITEMS - documents) throw failure("Historical documents exceed the shared proof budget"); documents += count;
        }
    }
}
