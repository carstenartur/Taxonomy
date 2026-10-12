package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.fasterxml.jackson.core.JsonGenerator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.transport.BundleWriter;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Fenced complete Git capture; current-state projection remains a separate adapter. */
public final class GitHistoryCapture {
    public record Limits(int maxRefs, int maxObjects, long maxObjectBytes, long maxTotalBytes, int maxReflogEntries) {
        public Limits {
            if (maxRefs < 1 || maxRefs > BackupLimits.MAX_ITEMS || maxObjects < 1 || maxObjects > 1_000_000
                    || maxObjectBytes < 0 || maxTotalBytes < 0 || maxReflogEntries < 0 || maxReflogEntries > BackupLimits.MAX_ITEMS)
                throw new IllegalArgumentException("Invalid Git history limits");
        }
        public static Limits defaults() { return new Limits(10_000, 100_000, 1L << 30, 64L << 30, 10_000); }
    }
    public record Reflog(String ref, String oldId, String newId, String name, String email,
                         long epochSeconds, int offsetMinutes, String message) { }
    private final Repository repository;
    private final Limits limits;
    private final SnapshotContext.RepositoryState state;
    private final Map<String, String> backupRefs;
    private final Map<String, String> symbolicRefs;
    private final List<Reflog> reflogs;
    private final List<GitReflogProvenance.Event> importedReflogs;
    private final Map<ObjectId, GitHistoryObjects.Proof> objects;
    private final BackupCheckpoint lifetime;
    private final ReflogInventory reflogInventory;
    private final ProvenanceInventory provenanceInventory;
    private final Origin origin;

    /** An internal capability ties an editor overlay to the same authorized repository facade and key. */
    record Origin(ExistingGitBackupRepositories source, BackupRepositoryKey key) { }

    @FunctionalInterface interface ReflogInventory { Set<String> names(BackupCheckpoint checkpoint) throws IOException; }
    @FunctionalInterface interface ProvenanceInventory { List<GitReflogProvenance.Event> events(BackupCheckpoint checkpoint) throws IOException; }

    private GitHistoryCapture(Repository repository, Limits limits, SnapshotContext.RepositoryState state,
                              Map<String, String> backupRefs, Map<String, String> symbolicRefs, List<Reflog> reflogs,
                              Map<ObjectId, GitHistoryObjects.Proof> objects, BackupCheckpoint lifetime, ReflogInventory reflogInventory, Origin origin,
                              List<GitReflogProvenance.Event> importedReflogs, ProvenanceInventory provenanceInventory) {
        this.repository = repository; this.limits = limits; this.state = state; this.backupRefs = Map.copyOf(backupRefs);
        this.symbolicRefs = Map.copyOf(symbolicRefs);
        this.reflogs = List.copyOf(reflogs); this.objects = Map.copyOf(objects); this.lifetime = lifetime; this.reflogInventory = reflogInventory;
        this.importedReflogs = List.copyOf(importedReflogs); this.provenanceInventory = provenanceInventory;
        this.origin = origin;
    }
    public static GitHistoryCapture capture(Repository repository, Set<String> requiredCommits,
            Map<String, SnapshotContext.WorkingState> workingStates, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        return capture(repository, requiredCommits, workingStates, limits, checkpoint, () -> { });
    }
    static GitHistoryCapture capture(Repository repository, Set<String> requiredCommits,
            Map<String, SnapshotContext.WorkingState> workingStates, Limits limits,
            BackupCheckpoint checkpoint, BackupCheckpoint lifetime) throws IOException {
        return capture(repository, requiredCommits, workingStates, limits, checkpoint, lifetime,
                check -> fileReflogNames(repository, limits, check));
    }
    static GitHistoryCapture capture(Repository repository, Set<String> requiredCommits,
            Map<String, SnapshotContext.WorkingState> workingStates, Limits limits,
            BackupCheckpoint checkpoint, BackupCheckpoint lifetime, ReflogInventory reflogInventory) throws IOException {
        return capture(repository, requiredCommits, workingStates, limits, checkpoint, lifetime, reflogInventory, null);
    }
    static GitHistoryCapture capture(Repository repository, Set<String> requiredCommits,
            Map<String, SnapshotContext.WorkingState> workingStates, Limits limits,
            BackupCheckpoint checkpoint, BackupCheckpoint lifetime, ReflogInventory reflogInventory, Origin origin) throws IOException {
        return capture(repository, requiredCommits, workingStates, limits, checkpoint, lifetime, reflogInventory, origin, check -> List.of());
    }
    static GitHistoryCapture capture(Repository repository, Set<String> requiredCommits,
            Map<String, SnapshotContext.WorkingState> workingStates, Limits limits,
            BackupCheckpoint checkpoint, BackupCheckpoint lifetime, ReflogInventory reflogInventory, Origin origin,
            ProvenanceInventory provenanceInventory) throws IOException {
        try {
            Objects.requireNonNull(repository); Objects.requireNonNull(limits); Objects.requireNonNull(lifetime); Objects.requireNonNull(reflogInventory);
            Objects.requireNonNull(provenanceInventory);
            var check = guard(lifetime, checkpoint); check.check();
            if (requiredCommits.size() > limits.maxRefs()) throw failure("Required Git commit limit exceeded");
            var refs = refs(repository, limits, check);
            String symbolicHead = symbolicHead(repository);
            var symbolicRefs = symbolicRefs(repository, limits, check);
            var logs = logs(repository, refs.keySet(), reflogInventory, limits, check);
            var imported = GitReflogProvenance.canonical(provenanceInventory.events(check), limits.maxReflogEntries() - logs.size(), check);
            var logBudget = new GitHistoryReflogs.Budget(limits.maxReflogEntries());
            for (var log : logs) logBudget.add(log);
            for (var event : imported) logBudget.add(event.entry());
            var required = new TreeSet<String>(requiredCommits);
            workingStates.values().stream().map(SnapshotContext.WorkingState::checkpointCommit).filter(Objects::nonNull).forEach(required::add);
            var extras = new TreeSet<String>(required);
            for (var log : logs) {
                if (!ObjectId.zeroId().name().equals(log.oldId())) extras.add(log.oldId());
                if (!ObjectId.zeroId().name().equals(log.newId())) extras.add(log.newId());
            }
            for (var event : imported) {
                var log = event.entry();
                if (!ObjectId.zeroId().name().equals(log.oldId())) extras.add(log.oldId());
                if (!ObjectId.zeroId().name().equals(log.newId())) extras.add(log.newId());
            }
            var objects = new LinkedHashMap<ObjectId, GitHistoryObjects.Proof>();
            try (var reader = GitHistoryObjects.inventoryReader(repository, limits, check); var walk = new ObjectWalk(reader)) {
                walk.setRetainBody(false);
                for (String id : required) {
                    check.check(); requireId(id); walk.parseCommit(ObjectId.fromString(id));
                }
                var starts = new TreeSet<>(refs.values()); starts.addAll(extras);
                for (String id : starts) { check.check(); requireId(id); walk.markStart(walk.parseAny(ObjectId.fromString(id))); }
                for (var commit = walk.next(); commit != null; commit = walk.next()) {
                    objects.put(commit.copy(), GitHistoryObjects.verify(reader, commit, check));
                    required.add(commit.name());
                    if (required.size() > BackupLimits.MAX_ITEMS) throw failure("Git commit proof limit exceeded");
                }
                for (var object = walk.nextObject(); object != null; object = walk.nextObject())
                    objects.put(object.copy(), GitHistoryObjects.verify(reader, object, check));
                for (String id : extras) if (objects.get(ObjectId.fromString(id)).type() == Constants.OBJ_COMMIT) required.add(id);
            }
            var backupRefs = new TreeMap<String, String>();
            for (String id : extras) {
                check.check();
                if (!refs.containsValue(id)) {
                    String ref = "refs/taxonomy-backup/required/" + id;
                    if (refs.containsKey(ref) && !id.equals(refs.get(ref))) throw failure("Required Git ref collides with source ref");
                    backupRefs.put(ref, id);
                }
            }
            if (refs.size() + backupRefs.size() > limits.maxRefs() || required.size() > BackupLimits.MAX_ITEMS)
                throw failure("Git history ref closure limit exceeded");
            check.check();
            var result = new GitHistoryCapture(repository, limits,
                    new SnapshotContext.RepositoryState(refs, symbolicHead, workingStates, required), backupRefs, symbolicRefs, logs, objects, lifetime, reflogInventory, origin,
                    imported, provenanceInventory);
            result.checkSource(check); return result;
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    public SnapshotContext.RepositoryState state() { return state; }
    public List<Reflog> reflogs() { return reflogs; }
    public List<GitReflogProvenance.Event> importedReflogs() { return importedReflogs; }
    public Map<String, String> symbolicRefs() { return symbolicRefs; }
    public List<BackupDocumentReference> documents(BackupRepositoryKey key, BackupCheckpoint checkpoint) throws IOException {
        return documents(key, BackupLimits.MAX_ITEMS, checkpoint);
    }
    /** Bounded original-document inspection; zero remaining capacity is valid for an empty source. */
    public record DocumentLimits(int maxDocuments, int maxDocumentBytes, long maxTotalBytes) {
        public DocumentLimits {
            if (maxDocuments < 0 || maxDocuments > BackupLimits.MAX_ITEMS || maxDocumentBytes < 1
                    || maxDocumentBytes > PortableRows.MAX_RECORD_BYTES || maxTotalBytes < 0 || maxTotalBytes > (10L << 30))
                throw new IllegalArgumentException("Invalid historical document visit limits");
        }
        public static DocumentLimits defaults() { return new DocumentLimits(BackupLimits.MAX_ITEMS, PortableRows.MAX_RECORD_BYTES, 10L << 30); }
    }
    public record DocumentVisit(int documents, long bytes) { }
    @FunctionalInterface public interface DocumentVisitor {
        void accept(BackupDocumentReference reference, byte[] originalBody) throws IOException;
    }
    /**
     * Visits exact bounded bodies during this capture's lifetime. The complete source inventory
     * is verified around the pass; each body uses the captured object/type/length/content proof.
     * Only one body is retained by the owner at a time, and no repository handle escapes.
     */
    public DocumentVisit visitDocuments(BackupRepositoryKey key, DocumentLimits bounds, BackupCheckpoint checkpoint,
                                       DocumentVisitor visitor) throws IOException {
        try {
            Objects.requireNonNull(bounds); Objects.requireNonNull(visitor);
            var check = guard(lifetime, checkpoint); check.check();
            var references = documents(key, check);
            if (references.size() > bounds.maxDocuments()) throw failure("Historical document visit count limit exceeded");
            long bytes = 0;
            for (var reference : references) {
                check.check();
                var body = new ByteArrayOutputStream();
                long length = copyCapturedDocument(reference, Math.min(bounds.maxDocumentBytes(), bounds.maxTotalBytes() - bytes), body, check);
                bytes += length; check.check(); visitor.accept(reference, body.toByteArray()); check.check();
            }
            checkSource(check); return new DocumentVisit(references.size(), bytes);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    /** Copies one original historical document through the caller's bounded parser input. */
    public void copyDocument(BackupDocumentReference reference, long maximumBytes, OutputStream output,
                             BackupCheckpoint checkpoint) throws IOException {
        try {
            var check = guard(lifetime, checkpoint); checkSource(check);
            copyCapturedDocument(reference, maximumBytes, output, check);
            checkSource(check);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    private long copyCapturedDocument(BackupDocumentReference reference, long maximumBytes, OutputStream output,
                                      BackupCheckpoint check) throws IOException {
        Objects.requireNonNull(reference); Objects.requireNonNull(output); check.check();
        if (maximumBytes < 0 || origin != null && !origin.key().equals(reference.repository())
                || !documentPath(reference.path().getBytes(StandardCharsets.UTF_8)))
            throw failure("Invalid historical document source or bound");
        var id = ObjectId.fromString(reference.commit()); var commitProof = objects.get(id);
        if (commitProof == null || commitProof.type() != Constants.OBJ_COMMIT || !state.requiredCommits().contains(reference.commit()))
            throw failure("Historical document commit is outside the captured closure");
        try (var reader = reader(check); var revisions = new RevWalk(reader)) {
            revisions.setRetainBody(false); var commit = revisions.parseCommit(id);
            try (var tree = TreeWalk.forPath(reader, reference.path(), commit.getTree())) {
                if (tree == null || !tree.getFileMode(0).equals(FileMode.REGULAR_FILE) && !tree.getFileMode(0).equals(FileMode.EXECUTABLE_FILE))
                    throw failure("Historical document path has no captured regular file");
                var proof = objects.get(tree.getObjectId(0));
                if (proof == null || proof.type() != Constants.OBJ_BLOB || proof.length() > maximumBytes || !reference.sha256().equals(proof.sha256()))
                    throw failure("Historical document content proof differs or exceeds its bound");
                reader.open(tree.getObjectId(0), Constants.OBJ_BLOB).copyTo(output); check.check();
                return proof.length();
            }
        }
    }
    public List<BackupDocumentReference> documents(BackupRepositoryKey key, int maximum, BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(key);
            if (maximum < 1 || maximum > BackupLimits.MAX_ITEMS) throw failure("Invalid history document proof limit");
            if (origin != null && !origin.key().equals(key)) throw failure("History document key differs from its authorized source");
            var check = guard(lifetime, checkpoint); checkSource(check);
            var documents = new ArrayList<BackupDocumentReference>();
            var commits = objects.entrySet().stream().filter(entry -> entry.getValue().type() == Constants.OBJ_COMMIT)
                    .map(Map.Entry::getKey).sorted(Comparator.comparing(ObjectId::name)).toList();
            try (var reader = reader(check); var revisions = new RevWalk(reader)) {
                revisions.setRetainBody(false);
                for (var id : commits) {
                    check.check(); var commit = revisions.parseCommit(id);
                    try (var tree = new TreeWalk(reader)) {
                        tree.addTree(commit.getTree()); tree.setRecursive(true);
                        while (tree.next()) {
                            check.check();
                            byte[] rawPath = tree.getRawPath();
                            if (!documentPath(rawPath)) continue;
                            String path = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(rawPath)).toString();
                            var mode = tree.getFileMode(0);
                            if (!mode.equals(FileMode.REGULAR_FILE) && !mode.equals(FileMode.EXECUTABLE_FILE))
                                throw failure("Unsupported historical document file mode");
                            var proof = objects.get(tree.getObjectId(0));
                            if (proof == null || proof.type() != Constants.OBJ_BLOB || proof.sha256() == null)
                                throw failure("Historical document has no captured content proof");
                            if (documents.size() >= maximum) throw failure("History document proof limit exceeded");
                            documents.add(new BackupDocumentReference(key, id.name(), path, proof.sha256()));
                        }
                    }
                }
            }
            checkSource(check); return List.copyOf(documents);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    private static boolean documentPath(byte[] path) {
        byte[] suffix = {'.', 't', 'a', 'x', 'd', 's', 'l'};
        if (path.length < suffix.length) return false;
        for (int i = 0; i < suffix.length; i++) {
            int value = Byte.toUnsignedInt(path[path.length - suffix.length + i]);
            if (value >= 'A' && value <= 'Z') value += 'a' - 'A';
            if (value != suffix[i]) return false;
        }
        return true;
    }
    public void write(String archiveId, GitRepresentation representation, ComponentSink sink) throws IOException {
        write(archiveId, representation, BackupProfile.REPOSITORY_HISTORY, sink);
    }
    public void writeWorkingTree(String archiveId, BackupProfile profile, GitStandBackupSource.Stand workingCopy, ComponentSink sink) throws IOException {
        Objects.requireNonNull(workingCopy);
        write(archiveId, GitRepresentation.WORKTREE, profile, workingCopy, sink);
    }
    public void write(String archiveId, GitRepresentation representation, BackupProfile profile, ComponentSink sink) throws IOException {
        write(archiveId, representation, profile, null, sink);
    }
    private void write(String archiveId, GitRepresentation representation, BackupProfile profile, GitStandBackupSource.Stand workingCopy, ComponentSink sink) throws IOException {
        try {
            if (archiveId == null || !archiveId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || archiveId.equals(".") || archiveId.equals(".."))
                throw failure("Invalid Git history archive identity");
            if (!profile.includesHistory() || representation == null || representation == GitRepresentation.NONE) throw failure("Unsupported Git history representation");
            var check = guard(lifetime, sink::checkpoint); check.check();
            if (workingCopy != null) {
                check = guard(workingCopy::checkLifetime, check);
                requireWorkingCopy(workingCopy, check);
            }
            final var writeCheck = check;
            checkSource(check);
            if (representation == GitRepresentation.BUNDLE) {
                if (objects.isEmpty()) throw failure("An empty history has no native Git bundle; select a bare repository");
                GitTreeCapture.writeGenerated("repositories/" + archiveId + "/history.bundle", sink, output -> {
                    try (var reader = reader(writeCheck)) {
                        var writer = new BundleWriter(reader); writer.setPackConfig(packConfig());
                        for (var ref : exportedRefs().entrySet()) writer.include(ref.getKey(), ObjectId.fromString(ref.getValue()));
                        writer.writeBundle(monitor(writeCheck), output); writeCheck.check();
                    }
                });
            } else GitFileRepositoryWriter.write(this, archiveId, representation, workingCopy, sink, check);
            GitTreeCapture.writeGenerated("data/workspace/git-history-" + archiveId + ".ndjson", sink,
                    output -> metadata(archiveId, profile, workingCopy, output, writeCheck));
            checkSource(check); check.check();
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    Map<String, String> exportedRefs() { var result = new TreeMap<>(state.refs()); result.putAll(backupRefs); return result; }
    int objectCount() { return objects.size(); }
    long objectBytes() { return objects.values().stream().mapToLong(GitHistoryObjects.Proof::length).reduce(0, Math::addExact); }
    private void requireWorkingCopy(GitStandBackupSource.Stand working, BackupCheckpoint check) throws IOException {
        check.check();
        if (!working.isHistoryWorkingCopy() || origin == null || !origin.equals(working.origin())) throw failure("Working copy belongs to a different history capture source");
        var source = working.state(); String branch = source.symbolicHead();
        if (branch == null || !Objects.equals(state.refs().get(branch), source.refs().get(branch))
                || !state.workingStates().entrySet().containsAll(source.workingStates().entrySet()))
            throw failure("Working copy differs from the captured branch or checkpoint evidence");
    }
    ObjectReader reader(BackupCheckpoint check) throws IOException { return GitHistoryObjects.capturedReader(repository, limits, objects, guard(lifetime, check)); }
    Optional<GitTreeCapture> headTree(BackupCheckpoint check) throws IOException {
        String head = state.refs().get(Constants.HEAD);
        return head == null ? Optional.empty() : Optional.of(GitTreeCapture.capture(repository, head,
                new GitTreeCapture.Limits(BackupLimits.MAX_ITEMS, limits.maxObjectBytes(), limits.maxTotalBytes()), check, lifetime));
    }
    private void metadata(String archiveId, BackupProfile profile, GitStandBackupSource.Stand workingCopy, OutputStream output, BackupCheckpoint check) throws IOException {
        var json = PortableRows.json();
        try (var generator = json.getFactory().createGenerator(output).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
            generator.setRootValueSeparator(null);
            json.writeValue(generator, new PortableRows.Header(1, "git-history", profile)); generator.writeRaw('\n');
            json.writeValue(generator, new HistoryRecord("repository", archiveId, state, backupRefs, symbolicRefs, objects.size(),
                    workingCopy == null ? null : workingCopy.state())); generator.writeRaw('\n');
            for (var log : reflogs) { check.check(); json.writeValue(generator, new LogRecord("reflog", log)); generator.writeRaw('\n'); }
            for (var event : importedReflogs) { check.check(); json.writeValue(generator, new OriginalLogRecord("original-reflog", event)); generator.writeRaw('\n'); }
            generator.flush(); check.check();
        }
    }
    private static Map<String, String> refs(Repository repository, Limits limits, BackupCheckpoint check) throws IOException {
        check.check();repository.scanForRepoChanges();check.check();
        var result = new TreeMap<String, String>();
        for (var ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
            check.check();
            if (!Repository.isValidRefName(ref.getName()) || ref.getObjectId() == null) throw failure("Invalid Git history ref");
            if (result.put(ref.getName(), ref.getObjectId().name()) != null || result.size() > limits.maxRefs()) throw failure("Git history ref limit exceeded");
        }
        Ref head = repository.exactRef(Constants.HEAD);
        if (head != null && head.getObjectId() != null) result.put(Constants.HEAD, head.getObjectId().name());
        if (result.size() > limits.maxRefs()) throw failure("Git history ref limit exceeded");
        check.check(); return Map.copyOf(result);
    }
    private void checkSource(BackupCheckpoint check) throws IOException {
        check.check();
        if (!state.refs().equals(refs(repository, limits, check)) || !Objects.equals(state.symbolicHead(), symbolicHead(repository))
                || !symbolicRefs.equals(symbolicRefs(repository, limits, check)))
            throw failure("Git refs changed after history capture");
        if (!reflogs.equals(logs(repository, state.refs().keySet(), reflogInventory, limits, check)))
            throw failure("Git reflogs changed after history capture");
        if (!importedReflogs.equals(GitReflogProvenance.canonical(provenanceInventory.events(check), limits.maxReflogEntries() - reflogs.size(), check)))
            throw failure("Original Git event provenance changed after history capture");
        check.check();
    }
    private static String symbolicHead(Repository repository) throws IOException {
        Ref head = repository.exactRef(Constants.HEAD);
        return head != null && head.isSymbolic() ? head.getTarget().getName() : null;
    }
    private static Map<String, String> symbolicRefs(Repository repository, Limits limits, BackupCheckpoint check) throws IOException {
        var result = new TreeMap<String, String>();
        for (var ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
            check.check();
            if (!ref.isSymbolic()) continue;
            String target = ref.getTarget().getName();
            if (!Repository.isValidRefName(ref.getName()) || !Repository.isValidRefName(target)) throw failure("Invalid symbolic Git ref");
            if (result.put(ref.getName(), target) != null || result.size() > limits.maxRefs()) throw failure("Git symbolic ref limit exceeded");
        }
        check.check(); return Map.copyOf(result);
    }
    private static List<Reflog> logs(Repository repository, Set<String> refs, ReflogInventory inventory, Limits limits, BackupCheckpoint check) throws IOException {
        var result = new ArrayList<Reflog>(); var names = new TreeSet<>(refs); names.add(Constants.HEAD); names.addAll(inventory.names(check));
        if (names.size() > limits.maxRefs()) throw failure("Git reflog ref limit exceeded");
        var budget = new GitHistoryReflogs.Budget(limits.maxReflogEntries());
        for (String name : names) {
            if (!name.equals(Constants.HEAD) && (!name.startsWith(Constants.R_REFS) || !Repository.isValidRefName(name)))
                throw failure("Invalid retained Git reflog name");
            check.check();
            if (repository.getDirectory() != null) {
                result.addAll(GitHistoryReflogs.file(repository.getDirectory().toPath().resolve("logs").resolve(name), name, budget, check));
                continue;
            }
            var reader = repository.getReflogReader(name);
            // File storage resolves a live ref in its String overload. Its public Ref overload can
            // read an inventoried deleted name without creating a source ref. Hibernate owns the
            // String overload directly, so retain that storage-specific public implementation.
            if (reader == null) reader = repository.getReflogReader(new ObjectIdRef.Unpeeled(Ref.Storage.NEW, name, null));
            if (reader == null) continue;
            var entries = reader.getReverseEntries(limits.maxReflogEntries() - result.size() + 1);
            if (entries.size() > limits.maxReflogEntries() - result.size()) throw failure("Git reflog entry limit exceeded");
            for (var entry : entries.reversed()) {
                check.check(); var who = entry.getWho();
                var log = new Reflog(name, entry.getOldId().name(), entry.getNewId().name(), who.getName(), who.getEmailAddress(),
                        who.getWhenAsInstant().getEpochSecond(), who.getTimeZoneOffset(), entry.getComment());
                budget.add(log); result.add(log);
            }
        }
        check.check(); return List.copyOf(result);
    }
    private static Set<String> fileReflogNames(Repository repository, Limits limits, BackupCheckpoint check) throws IOException {
        check.check();
        if (repository.getDirectory() == null) throw failure("Git storage needs an explicit retained-reflog inventory");
        Path directory = repository.getDirectory().toPath().resolve("logs");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        var names = new TreeSet<String>();
        Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), 34, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                check.check(); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                check.check();
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw failure("Retained Git reflog path is not a regular file");
                String name = directory.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (names.size() >= limits.maxRefs()) throw failure("Git reflog ref limit exceeded");
                names.add(name); return FileVisitResult.CONTINUE;
            }
        });
        check.check(); return Set.copyOf(names);
    }
    static PackConfig packConfig() {
        var config = new PackConfig(); config.setDeltaCompress(false); config.setReuseObjects(false);
        config.setReuseDeltas(false); config.setBuildBitmaps(false); config.setThreads(1); return config;
    }
    static ProgressMonitor monitor(BackupCheckpoint check) {
        return new ProgressMonitor() {
            @Override public void start(int tasks) { }
            @Override public void beginTask(String title, int totalWork) { }
            @Override public void update(int completed) { }
            @Override public void endTask() { }
            @Override public boolean isCancelled() { try { check.check(); return false; } catch (IOException failure) { return true; } }
            @Override public void showDuration(boolean enabled) { }
        };
    }
    private static BackupCheckpoint guard(BackupCheckpoint lifetime, BackupCheckpoint check) {
        return () -> { lifetime.check(); check.check(); lifetime.check(); };
    }
    private static void requireId(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{40}") || id.equals(ObjectId.zeroId().name())) throw failure("Invalid required Git object");
    }
    public record HistoryRecord(String recordType, String archiveId, SnapshotContext.RepositoryState source,
                                 Map<String, String> backupRefs, Map<String, String> symbolicRefs, int objectCount,
                                 SnapshotContext.RepositoryState workingCopy) { }
    public record LogRecord(String recordType, Reflog entry) { }
    public record OriginalLogRecord(String recordType, GitReflogProvenance.Event event) { }
}
