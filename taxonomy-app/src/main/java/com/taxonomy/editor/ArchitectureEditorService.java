package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureDslCommands.Change;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.editor.persistence.EditorJournal.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Accepted editing revisions belong to the durable workspace journal. Git contains explicit versions only. */
@Service
public class ArchitectureEditorService implements ArchitectureCommandPort, WorkspaceArchitectureVersionPort {
    private final DslGitRepositoryFactory repositories;
    private final EditorJournal journal;
    private final ArchitectureCheckpointWriter checkpointWriter;
    private final ArchitectureDslCommands transformer = new ArchitectureDslCommands();

    public ArchitectureEditorService(DslGitRepositoryFactory repositories, EditorJournal journal,
                                     ArchitectureCheckpointWriter checkpointWriter) {
        this.repositories = repositories; this.journal = journal; this.checkpointWriter = checkpointWriter;
    }

    public record HistoryEntry(String operationId, String commandId, String kind, String targetOperationId,
                               String actor, String occurredAt, String rationale, List<String> changedIds,
                               long previousRevision, long revision) {}
    public record Document(Context context, String dsl, String projectionState, List<HistoryEntry> history,
                           List<DslCommit> versions, long checkpointRevision, String pendingCheckpointId, String source) {}

    public Document read(RepositoryContext context, String commit) throws IOException { return read(context, commit, null); }

    public Document read(RepositoryContext context, String commit, Long revision) throws IOException {
        if (commit != null && revision != null) throw new IllegalArgumentException("Select a version or a semantic revision");
        DslGitRepository repository = repositories.resolveRepository(context);
        String head = head(repository, context.branch());
        List<DslCommit> versions = head == null ? List.of() : repository.getDslHistory(context.branch());
        if (commit != null) {
            String selected = ObjectId.fromString(commit).name();
            requireReachable(repository, head, selected);
            return new Document(Context.version(context, selected), source(repository, selected), "HISTORICAL",
                    List.of(), versions, 0, null, "GIT_CHECKPOINT");
        }
        Snapshot snapshot = snapshot(context);
        State state = snapshot.state();
        String dsl = state.dsl();
        long selected = revision == null ? state.revision() : revision;
        if (selected < 0 || selected > state.revision()) throw problem("NOT_FOUND", "revision", "Unknown workspace revision", List.of());
        if (selected != state.revision()) {
            dsl = selected == 0 ? snapshot.operations().getLast().beforeDsl() : snapshot.operations().stream()
                    .filter(entry -> entry.revision() == selected).findFirst()
                    .orElseThrow(() -> problem("NOT_FOUND", "revision", "Unknown workspace revision", List.of())).afterDsl();
        }
        String status = selected != state.revision() ? "HISTORICAL"
                : state.pendingCheckpoint() != null ? "CHECKPOINT_PENDING"
                : !Objects.equals(head, state.checkpointCommit()) ? "VERSION_CHANGED" : "READY";
        return new Document(Context.of(context, state.checkpointCommit(), selected), dsl, status,
                snapshot.operations().stream().filter(e -> e.revision() <= selected && e.actor().equals(context.username()))
                        .limit(50).map(ArchitectureEditorService::history).toList(), versions,
                state.checkpointRevision(), state.pendingCheckpoint(), "WORKSPACE_REVISION");
    }

    @Override
    public Preview preview(RepositoryContext context, Command command) throws IOException {
        requireContext(context, command.context()); requireWritable(context);
        Snapshot snapshot = snapshot(context);
        expect(snapshot.state(), command.context().revision());
        requireInitialVersion(snapshot.state(), command.context());
        verifyVersion(context, snapshot.state());
        return new Preview(Context.of(context, snapshot.state().checkpointCommit(), snapshot.state().revision()),
                transform(context, command, snapshot.state().dsl(), snapshot.operations()), kind(command.operation()), target(command.operation()));
    }

    @Override
    public Accepted execute(RepositoryContext context, Command command) throws IOException {
        requireContext(context, command.context()); requireWritable(context);
        Snapshot initial = snapshot(context);
        String fingerprint = fingerprint(command);
        try {
            return journal.locked(context, initial.state(), session -> {
            Entry prior = session.find(command.metadata().commandId());
            if (prior != null) {
                requireFingerprint(fingerprint, prior.fingerprint());
                return accepted(context, session.state(), prior, true);
            }
            session.expect(command.context().revision());
            requireInitialVersion(session.state(), command.context());
            try { verifyVersion(context, session.state()); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            Change change = transform(context, command, session.state().dsl(), session.operations());
            if (change.changes().isEmpty()) throw problem("NO_CHANGE", "command", "No semantic change to accept", List.of());
            Entry entry = session.append(command.metadata(), context.username(), kind(command.operation()), target(command.operation()),
                    fingerprint, change.dsl(), List.copyOf(affected(change.changes())));
            return accepted(context, session.state(), entry, false);
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    private static Accepted accepted(RepositoryContext context, State state, Entry entry, boolean replayed) {
        return new Accepted(Context.of(context, state.checkpointCommit(), entry.revision()), entry.commandId(), entry.commandId(), replayed,
                "READY", new Change(entry.afterDsl(), ArchitectureSemanticPatch.between(entry.beforeDsl(), entry.afterDsl())));
    }

    /** Explicit checkpoint command: durable prepare, retryable Git write, durable completion. No semantic append. */
    public CheckpointAccepted checkpoint(RepositoryContext context, CreateCheckpointCommand command) throws IOException {
        requireContext(context, command.context()); requireWritable(context);
        // A pending intent must be recoverable even when its Git phase has already advanced HEAD.
        Snapshot current = journal.read(context);
        State initial = current == null ? seed(context) : current.state();
        String fingerprint = digest(Arrays.asList("CHECKPOINT", EditorJournal.scope(context), context.username(),
                Long.toString(command.context().revision()), command.metadata().rationale(),
                command.metadata().correlationId(), command.metadata().causationId()));
        Checkpoint prepared;
        try {
            prepared = journal.locked(context, initial, session -> {
            Checkpoint prior = session.checkpoint(command.metadata().commandId());
            if (prior != null) {
                requireFingerprint(fingerprint, prior.fingerprint());
                if (prior.failureCode() != null) throw problem(prior.failureCode(), "checkpoint", "The checkpoint was rejected because Git moved; reconcile and create a new checkpoint", List.of(prior.commandId()));
                return prior;
            }
            session.expect(command.context().revision());
            try { verifyVersion(context, session.state()); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            requireInitialVersion(session.state(), command.context());
            return session.prepare(command.metadata(), context.username(), fingerprint);
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
        boolean replayed = prepared.completed();
        Checkpoint completed = prepared;
        if (!prepared.completed()) {
            completed = finishCheckpoint(context, initial, prepared);
        }
        return new CheckpointAccepted(Context.of(context, completed.commitId(), completed.revision()), completed.commandId(),
                completed.commitId(), completed.fromRevision(), completed.revision(), completed.commitCreated(), replayed);
    }

    /** Resume the exact durable intent after restart; the authenticated actor must own it. */
    public CheckpointAccepted resumeCheckpoint(RepositoryContext context) throws IOException {
        requireWritable(context);
        Snapshot snapshot = journal.read(context);
        if (snapshot == null || snapshot.state().pendingCheckpoint() == null) throw problem("NOT_FOUND", "checkpoint", "No pending checkpoint", List.of());
        Checkpoint intent = journal.locked(context, snapshot.state(), session -> session.checkpoint(snapshot.state().pendingCheckpoint()));
        if (!context.username().equals(intent.actor())) throw problem("NOT_FOUND", "checkpoint", "No personal checkpoint", List.of());
        Checkpoint completed = finishCheckpoint(context, snapshot.state(), intent);
        return new CheckpointAccepted(Context.of(context, completed.commitId(), completed.revision()), completed.commandId(),
                completed.commitId(), completed.fromRevision(), completed.revision(), completed.commitCreated(), true);
    }

    private Checkpoint finishCheckpoint(RepositoryContext context, State initial, Checkpoint intent) throws IOException {
        try {
            var written = checkpointWriter.write(repositories.resolveRepository(context), EditorJournal.scope(context), context.branch(), intent);
            return journal.locked(context, initial, session -> session.complete(intent.commandId(), written.commitId(), written.created()));
        } catch (com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException conflict) {
            // Deterministic commit recovery ran first: this exception proves the intent did not advance the ref.
            journal.locked(context, initial, session -> { session.rejectCheckpoint(intent.commandId()); return null; });
            throw problem("CHECKPOINT_CONFLICT", "checkpoint", "Git changed before this checkpoint could be applied; reconcile the versions", List.of(intent.commandId()));
        }
    }

    /** Editor projections are rebuilt from exact canonical DSL on every read, including before the first checkpoint. */
    public String rebuild(RepositoryContext context, Context expected) throws IOException {
        requireContext(context, expected); requireWritable(context);
        Snapshot snapshot = snapshot(context);
        expect(snapshot.state(), expected.revision());
        transformer.model(snapshot.state().dsl());
        return "READY";
    }

    @Override
    public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
        Snapshot snapshot = journal.read(context);
        if (context.scope() != RepositoryScope.WORKSPACE || snapshot == null) return action.run();
        if (snapshot.state().pendingCheckpoint() != null) resumeCheckpoint(context);
        reconcileVersion(context);
        snapshot = journal.read(context);
        String id = UUID.nameUUIDFromBytes((EditorJournal.scope(context) + ":" + snapshot.state().revision()
                + ":" + context.username() + ":" + rationale).getBytes(StandardCharsets.UTF_8)).toString();
        var prepared = checkpoint(context, new CreateCheckpointCommand(
                Context.of(context, snapshot.state().checkpointCommit(), snapshot.state().revision()), new Metadata(id, id, id, rationale)));
        try {
            return journal.locked(context, snapshot.state(), session -> {
                session.expect(prepared.context().revision());
                try {
                    T result = action.run();
                    importVersion(context, session, rationale);
                    return result;
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    /** Recovery for a version write that reached Git before the journal transaction could finish. */
    public void reconcileVersion(RepositoryContext context) throws IOException {
        requireWritable(context);
        Snapshot snapshot = journal.read(context);
        if (snapshot == null) return;
        try {
            journal.locked(context, snapshot.state(), session -> {
                session.expect(session.state().revision());
                try {
                    String head = head(repositories.resolveRepository(context), context.branch());
                    if (Objects.equals(head, session.state().checkpointCommit())) return null;
                    if (session.state().revision() != session.state().checkpointRevision()) {
                        throw problem("VERSION_CHANGED", "checkpoint", "Uncheckpointed edits and a moved Git version require explicit reconciliation", List.of());
                    }
                    importVersion(context, session, "Recover completed architecture version");
                    return null;
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    private void importVersion(RepositoryContext context, EditorJournal.Session session, String rationale) throws IOException {
        var repository = repositories.resolveRepository(context);
        String head = head(repository, context.branch());
        if (Objects.equals(head, session.state().checkpointCommit())) return;
        String dsl = source(repository, head);
        if (!dsl.equals(session.state().dsl())) {
            var changes = ArchitectureSemanticPatch.between(session.state().dsl(), dsl);
            String id = UUID.randomUUID().toString();
            session.append(new Metadata(id, id, id, rationale), context.username(), "VERSION_IMPORT", null,
                    EditorJournal.hash("version:" + head), dsl, List.copyOf(affected(changes)));
        }
        session.adoptVersion(head, context.username(), rationale);
    }

    private Change transform(RepositoryContext context, Command command, String current, List<Entry> operations) {
        if (command.operation() instanceof SemanticCommand semantic) return transformer.apply(current, semantic.command());
        String target = target(command.operation());
        Entry selected = operations.stream().filter(e -> e.commandId().equals(target) && e.actor().equals(context.username()))
                .findFirst().orElseThrow(() -> problem("NOT_FOUND", "targetOperationId", "No personal operation in this workspace", List.of()));
        boolean redo = command.operation() instanceof RedoArchitectureCommand;
        if (selected.bodyVersion() != 1 || (redo && !"UNDO".equals(selected.kind())) || (!redo && "UNDO".equals(selected.kind()))
                || selected.kind().startsWith("VERSION_")) {
            throw problem("NOT_UNDOABLE", "targetOperationId", "Choose an accepted edit for undo or an undo operation for redo", List.of());
        }
        for (Entry newer : operations) {
            if (newer.revision() <= selected.revision()) continue;
            if (target.equals(newer.targetOperationId())) throw problem("ALREADY_INVERTED", "targetOperationId", "This operation already has an inverse", List.of(newer.commandId()));
            if (newer.affectedIds().stream().anyMatch(selected.affectedIds()::contains)) {
                throw problem("UNDO_CONFLICT", "targetOperationId", "A later operation depends on the affected objects", List.of(newer.commandId()));
            }
        }
        return transformer.inverse(current, selected.beforeDsl(), selected.afterDsl());
    }

    private void verifyVersion(RepositoryContext context, State state) throws IOException {
        String actual = head(repositories.resolveRepository(context), context.branch());
        if (!Objects.equals(actual, state.checkpointCommit())) {
            throw problem("VERSION_CHANGED", "checkpoint", "A version action must be reconciled before accepting further edits", List.of());
        }
    }

    private Snapshot snapshot(RepositoryContext context) throws IOException {
        Snapshot snapshot = journal.read(context);
        return snapshot == null ? new Snapshot(seed(context), List.of()) : snapshot;
    }
    private State seed(RepositoryContext context) throws IOException {
        DslGitRepository repository = repositories.resolveRepository(context);
        String head = head(repository, context.branch());
        return new State(source(repository, head), 0, head, 0, null);
    }
    private static HistoryEntry history(Entry entry) {
        return new HistoryEntry(entry.commandId(), entry.commandId(), entry.kind(), entry.targetOperationId(),
                entry.actor(), entry.occurredAt(), entry.rationale(), entry.affectedIds(), entry.previousRevision(), entry.revision());
    }
    private static void expect(State state, long expected) {
        if (state.revision() != expected) throw new RevisionConflict(expected, state.revision());
        if (state.pendingCheckpoint() != null) throw problem("CHECKPOINT_PENDING", "checkpoint", "Retry the pending checkpoint", List.of(state.pendingCheckpoint()));
    }
    private static void requireInitialVersion(State state, Context expected) {
        // Before the first accepted edit there is no durable semantic revision to distinguish Git baselines.
        // After revision zero, checkpoints may advance Git without invalidating a semantic preview.
        if (state.revision() == 0 && !Objects.equals(state.checkpointCommit(), expected.commit())) {
            throw problem("CONTEXT_CHANGED", "commit", "The initial architecture version changed; reload and preview again", List.of());
        }
    }
    private static void requireFingerprint(String expected, String actual) {
        if (!expected.equals(actual)) throw problem("COMMAND_ID_REUSED", "commandId", "Command identity was already used with another payload", List.of());
    }
    static void requireContext(RepositoryContext context, Context requested) {
        if (!Context.of(context, requested.commit(), requested.revision()).equals(requested)) {
            throw problem("CONTEXT_CHANGED", "context", "The repository, workspace, branch, actor or mode changed; reopen the editor", List.of());
        }
        if (requested.revision() < 0) throw new IllegalArgumentException("Revision must be nonnegative");
        if (requested.commit() != null) ObjectId.fromString(requested.commit());
    }
    private static void requireWritable(RepositoryContext context) {
        if (context.scope() != RepositoryScope.WORKSPACE) throw problem("READ_ONLY", "context", "Open a private writable workspace", List.of());
    }
    private static String source(DslGitRepository repository, String commit) throws IOException {
        if (commit == null) return "";
        String source = repository.getDslAtCommit(commit);
        if (source == null) throw problem("NOT_FOUND", "commit", "Commit has no architecture document", List.of());
        return source;
    }
    private static String head(DslGitRepository repository, String branch) throws IOException {
        var ref = repository.getGitRepository().exactRef(Constants.R_HEADS + branch);
        return ref == null ? null : ref.getObjectId().name();
    }
    private static void requireReachable(DslGitRepository repository, String head, String selected) throws IOException {
        try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
            if (head != null && walk.isMergedInto(walk.parseCommit(ObjectId.fromString(selected)), walk.parseCommit(ObjectId.fromString(head)))) return;
        } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException ignored) {
            // Guessed object IDs cannot expose another repository or branch.
        }
        throw problem("NOT_FOUND", "commit", "Commit is not reachable in the selected context", List.of());
    }
    private static Set<String> affected(List<ArchitectureSemanticPatch.BlockChange> changes) {
        Set<String> ids = new LinkedHashSet<>();
        for (var change : changes) {
            ids.add(change.id());
            if (change.id().startsWith("relation:")) {
                String[] tokens = change.id().substring("relation:".length()).split(" ");
                if (tokens.length == 3) { ids.add("element:" + tokens[0]); ids.add("element:" + tokens[2]); }
            }
        }
        return ids;
    }
    private static String kind(Operation operation) {
        return switch (operation) {
            case SemanticCommand semantic -> semantic.command().getClass().getSimpleName();
            case UndoArchitectureCommand ignored -> "UNDO";
            case RedoArchitectureCommand ignored -> "REDO";
        };
    }
    private static String target(Operation operation) {
        return switch (operation) {
            case UndoArchitectureCommand undo -> undo.targetOperationId().toString();
            case RedoArchitectureCommand redo -> redo.targetOperationId().toString();
            default -> null;
        };
    }
    private static String fingerprint(Command command) {
        List<String> parts = new ArrayList<>(List.of(kind(command.operation()), command.context().repositoryId(),
                command.context().workspaceScopeKey(), command.context().branch(), command.context().actor(), command.context().writeMode(),
                command.metadata().correlationId(), command.metadata().causationId(), command.metadata().rationale()));
        parts.add(Long.toString(command.context().revision()));
        if (command.operation() instanceof SemanticCommand semantic) {
            switch (semantic.command()) {
                case CreateArchitectureElement e -> { parts.add(e.id()); parts.add(e.type()); properties(parts, e.properties()); }
                case UpdateArchitectureElement e -> { parts.add(e.id()); parts.add(e.type()); properties(parts, e.properties()); }
                case DeleteArchitectureElement e -> parts.add(e.id());
                case CreateArchitectureRelation r -> { parts.add(r.relation().id()); parts.add(r.status()); }
                case UpdateArchitectureRelation r -> { parts.add(r.relation().id()); parts.add(r.status()); }
                case DeleteArchitectureRelation r -> parts.add(r.relation().id());
                case MoveOrGroupElement e -> { parts.add(e.id()); parts.add(e.parentId()); }
            }
        } else parts.add(target(command.operation()));
        return digest(parts);
    }

    private static void properties(List<String> parts, Map<String, String> properties) {
        new TreeMap<>(properties).forEach((key, value) -> { parts.add(key); parts.add(value); });
    }

    private static String digest(List<String> parts) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream stream = new DataOutputStream(bytes)) {
                for (String part : parts) {
                    if (part == null) { stream.writeInt(-1); continue; }
                    byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
                    stream.writeInt(encoded.length);
                    stream.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static CommandProblem problem(String code, String field, String message, List<String> dependencies) {
        return new CommandProblem(code, field, message, dependencies);
    }
}
