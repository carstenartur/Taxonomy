package com.taxonomy.editor;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.backup.BackupScope;
import com.taxonomy.backup.BackupWriteBarrier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureDslCommands.Change;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.merge.TaxDslSemanticMerger;
import com.taxonomy.workspace.storage.DslCommit;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.editor.persistence.EditorJournal.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioGitPort;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Accepted editing revisions belong to the durable workspace journal. Git contains explicit versions only. */
@Service
public class ArchitectureEditorService implements ArchitectureCommandPort, WorkspaceArchitectureVersionPort {
    private final DslGitRepositoryFactory repositories;
    private final EditorJournal journal;
    private final ArchitectureCheckpointWriter checkpointWriter;
    private BackupWriteBarrier writeBarrier = BackupWriteBarrier.disabled();
    private ObjectProvider<WorkspacePortfolioGitPort> portfolioGitPort;

    @Autowired
    public void setBackupWriteBarrier(BackupWriteBarrier writeBarrier) {
        this.writeBarrier = Objects.requireNonNull(writeBarrier);
    }

    @Autowired
    public void setPortfolioGitPort(ObjectProvider<WorkspacePortfolioGitPort> portfolioGitPort) {
        // Resolve only at the write boundary: the portfolio document adapter also depends on this editor.
        this.portfolioGitPort = Objects.requireNonNull(portfolioGitPort);
    }

    private static BackupScope backupScope(RepositoryContext context) {
        return context.workspaceId() == null
                ? new BackupScope.Repositories(Map.of(context.repositoryId(), Set.of()))
                : new BackupScope.Workspace(context.repositoryId(), context.workspaceId());
    }

    private final ArchitectureDslCommands transformer = new ArchitectureDslCommands();
    // A normal block identity always contains ':', including unknown extension block kinds.
    private static final String DOCUMENT_SOURCE_CONFLICT = "$document";

    public ArchitectureEditorService(DslGitRepositoryFactory repositories, EditorJournal journal,
                                     ArchitectureCheckpointWriter checkpointWriter) {
        this.repositories = repositories; this.journal = journal; this.checkpointWriter = checkpointWriter;
    }

    public record HistoryEntry(String operationId, String commandId, String kind, String targetOperationId,
                               String actor, String occurredAt, String rationale, List<String> changedIds,
                               long previousRevision, long revision) {}
    public record Document(Context context, String dsl, String projectionState, List<HistoryEntry> history,
                           List<DslCommit> versions, long checkpointRevision, String pendingCheckpointId, String source) {}

    public enum Resolution { LOCAL, INCOMING }
    public record ReconciliationRequest(Context context, Metadata metadata, Map<String, Resolution> resolutions,
                                        String headCommit, String previewFingerprint) {
        public ReconciliationRequest {
            if (context == null || metadata == null) throw new IllegalArgumentException("Context and metadata are required");
            resolutions = resolutions == null ? Map.of() : Map.copyOf(resolutions);
            if (headCommit != null) ObjectId.fromString(headCommit);
        }
    }
    public record ReconciliationConflict(String id, String reason, String base, String local, String incoming,
                                          Resolution resolution) {}
    public record ReconciliationPreview(Context context, String headCommit, String fingerprint, String baseDsl,
                                         String localDsl, String incomingDsl, String mergedDsl,
                                         List<ArchitectureSemanticPatch.BlockChange> changes,
                                         List<ReconciliationConflict> conflicts, boolean canAccept) {}
    private record ReconciliationIntent(Checkpoint checkpoint, boolean replayed) {}

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
        // Central and fork views have Git checkpoints, never workspace journal revisions.
        if (context.scope() != RepositoryScope.WORKSPACE) {
            if (revision != null && revision != 0) {
                throw problem("NOT_FOUND", "revision", "Unknown workspace revision", List.of());
            }
            return new Document(Context.version(context, head), source(repository, head), "READY",
                    List.of(), versions, 0, null, "GIT_CHECKPOINT");
        }
        Snapshot snapshot = snapshot(context);
        State state = snapshot.state();
        String dsl = state.dsl();
        long selected = revision == null ? state.revision() : revision;
        if (selected < 0 || selected > state.revision()) throw problem("NOT_FOUND", "revision", "Unknown workspace revision", List.of());
        if (selected != state.revision()) {
            dsl = journal.sourceAtRevision(context, selected);
            if (dsl == null) throw problem("NOT_FOUND", "revision", "Unknown workspace revision", List.of());
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
                transform(context, command, snapshot.state().dsl(), snapshot.operations(), id -> journal.operation(context, id)),
                kind(command.operation()), target(command.operation()));
    }

    @Override
    public Accepted execute(RepositoryContext context, Command command) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
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
                Change change = transform(context, command, session.state().dsl(), session.operations(), session::find);
                if (change.changes().isEmpty()) throw problem("NO_CHANGE", "command", "No semantic change to accept", List.of());
                Entry entry = session.append(command.metadata(), context.username(), kind(command.operation()), target(command.operation()),
                        fingerprint, change.dsl(), List.copyOf(affected(change.changes())));
                return accepted(context, session.state(), entry, false);
                });
            } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
        }
    }

    /**
     * A reviewed integration is one explicit application boundary. Its typed architecture commands,
     * optional server-owned portfolio contribution and journal entry join the integration transaction.
     * The caller checkpoints separately after that transaction commits. No Git write happens here.
     */
    public Context acceptIntegration(RepositoryContext context, Context expected, Metadata metadata,
                                     String fingerprint, List<com.taxonomy.dsl.command.ArchitectureCommand> commands,
                                     java.util.function.UnaryOperator<String> portfolioContribution,
                                     Metadata checkpointMetadata) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
            requireContext(context, expected); requireWritable(context);
            State initial = seed(context);
            return journal.joinedLocked(context, initial, session -> {
                Entry prior = session.find(metadata.commandId());
                if (prior != null) {
                    requireFingerprint(prior.fingerprint(), fingerprint);
                    return Context.of(context, session.state().checkpointCommit(), prior.revision());
                }
                session.expect(expected.revision()); requireInitialVersion(session.state(), expected);
                try { verifyVersion(context, session.state()); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                String before = session.state().dsl(), next = before;
                if (portfolioContribution != null) next = portfolioContribution.apply(next);
                next = transformer.applyAll(next, commands).dsl();
                // Even an unchanged reviewed integration needs its own durable retry receipt.
                session.append(metadata, context.username(), "VERSION_IMPORT", null, fingerprint, next,
                        new ArrayList<>(affected(ArchitectureSemanticPatch.between(before, next))));
                Context result = Context.of(context, session.state().checkpointCommit(), session.state().revision());
                if (checkpointMetadata != null) session.prepare(checkpointMetadata, context.username(),
                        checkpointFingerprint(context, new CreateCheckpointCommand(result, checkpointMetadata)));
                return result;
            });
        }
    }

    /** Workspace is the first model lock for cross-aggregate integrations, before any project lock. */
    public <T> T integrationBoundary(RepositoryContext context, java.util.function.Function<Document, T> action) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
            requireWritable(context);
            return journal.joinedLocked(context, seed(context), session -> {
                State state = session.state();
                return action.apply(new Document(Context.of(context, state.checkpointCommit(), state.revision()), state.dsl(),
                        state.pendingCheckpoint() == null ? "READY" : "CHECKPOINT_PENDING", List.of(), List.of(), state.checkpointRevision(), state.pendingCheckpoint(), "WORKSPACE_REVISION"));
            });
        }
    }

    /** Read checkpoint evidence from the caller's locked integration transaction; no Git writes. */
    public boolean integrationCheckpointMatches(RepositoryContext context, Context expected) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
            requireContext(context, expected);
            requireWritable(context);
            try {
                return journal.joinedLocked(context, seed(context), session -> {
                    State persisted = session.state();
                    Context actual = Context.of(context, persisted.checkpointCommit(), persisted.revision());
                    try {
                        // The seed is initialization only; observe authoritative HEAD after the row lock.
                        String actualHead = head(repositories.resolveRepository(context), context.branch());
                        return actual.equals(expected) && persisted.pendingCheckpoint() == null
                                && persisted.checkpointRevision() == persisted.revision()
                                && persisted.checkpointCommit() != null
                                && Objects.equals(actualHead, persisted.checkpointCommit());
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
            } catch (java.io.UncheckedIOException failure) {
                throw failure.getCause();
            }
        }
    }

    private static Accepted accepted(RepositoryContext context, State state, Entry entry, boolean replayed) {
        return new Accepted(Context.of(context, state.checkpointCommit(), entry.revision()), entry.commandId(), entry.commandId(), replayed,
                "READY", new Change(entry.afterDsl(), ArchitectureSemanticPatch.between(entry.beforeDsl(), entry.afterDsl())));
    }

    /** Explicit checkpoint command: durable prepare, retryable Git write, durable completion. No semantic append. */
    public CheckpointAccepted checkpoint(RepositoryContext context, CreateCheckpointCommand command) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
            requireContext(context, command.context()); requireWritable(context);
            // A pending intent must be recoverable even when its Git phase has already advanced HEAD.
            Snapshot current = journal.read(context);
            State initial = current == null ? seed(context) : current.state();
            String fingerprint = checkpointFingerprint(context, command);
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
    }

    /** Resume the exact durable intent after restart; the authenticated actor must own it. */
    public CheckpointAccepted resumeCheckpoint(RepositoryContext context) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
            requireWritable(context);
            Snapshot snapshot = journal.read(context);
            if (snapshot == null || snapshot.state().pendingCheckpoint() == null) throw problem("NOT_FOUND", "checkpoint", "No pending checkpoint", List.of());
            Checkpoint intent = journal.locked(context, snapshot.state(), session -> session.checkpoint(snapshot.state().pendingCheckpoint()));
            if (!context.username().equals(intent.actor())) throw problem("NOT_FOUND", "checkpoint", "No personal checkpoint", List.of());
            Checkpoint completed = finishCheckpoint(context, snapshot.state(), intent);
            return new CheckpointAccepted(Context.of(context, completed.commitId(), completed.revision()), completed.commandId(),
                    completed.commitId(), completed.fromRevision(), completed.revision(), completed.commitCreated(), true);
        }
    }

    private Checkpoint finishCheckpoint(RepositoryContext context, State initial, Checkpoint intent) throws IOException {
        try {
            var written = checkpointWriter.write(repositories.resolveRepository(context), EditorJournal.scope(context), context.branch(), intent);
            return journal.locked(context, initial, session -> session.complete(intent.commandId(), written.commitId(), written.created()));
        } catch (BranchHeadConflictException conflict) {
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
        try (var write = writeBarrier.enter(backupScope(context))) {
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
    }

    /** Recovery for a version write that reached Git before the journal transaction could finish. */
    public void reconcileVersion(RepositoryContext context) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
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
    }

    /** Read-only three-way preview over the exact retained local revision and current Git version. */
    public ReconciliationPreview previewReconciliation(RepositoryContext context, ReconciliationRequest request) throws IOException {
        requireContext(context, request.context()); requireWritable(context);
        return reconciliation(context, snapshot(context).state(), request);
    }

    /** Append the explicit source choices and reserve their new checkpoint in one durable transaction. */
    public CheckpointAccepted acceptReconciliation(RepositoryContext context, ReconciliationRequest request) throws IOException {
        try (var write = writeBarrier.enter(backupScope(context))) {
            requireContext(context, request.context()); requireWritable(context);
            if (request.previewFingerprint() == null || !request.previewFingerprint().matches("[a-f0-9]{64}"))
                throw problem("PREVIEW_CHANGED", "previewFingerprint", "Preview the exact reconciliation before accepting", List.of());
            State initial = snapshot(context).state();
            String fingerprint = digest(reconciliationParts(request, "RECONCILIATION_ACCEPT", request.headCommit(), request.previewFingerprint()));
            Metadata checkpointMetadata = reconciliationCheckpointMetadata(request.metadata());
            ReconciliationIntent prepared;
            try {
                prepared = journal.locked(context, initial, session -> {
                    Entry prior = session.find(request.metadata().commandId());
                    if (prior != null) {
                        requireFingerprint(fingerprint, prior.fingerprint());
                        Checkpoint intent = session.checkpoint(checkpointMetadata.commandId());
                        if (intent == null) throw new IllegalStateException("Reconciliation has no durable checkpoint intent");
                        requireUnrejectedCheckpoint(intent);
                        return new ReconciliationIntent(intent, true);
                    }
                    session.expect(request.context().revision());
                    ReconciliationPreview preview;
                    try { preview = reconciliation(context, session.state(), request); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    if (!Objects.equals(request.headCommit(), preview.headCommit())
                            || !request.previewFingerprint().equals(preview.fingerprint()))
                        throw problem("PREVIEW_CHANGED", "previewFingerprint", "Git or the reconciliation choices changed; preview again", List.of());
                    if (!preview.canAccept()) throw problem("RECONCILIATION_CONFLICT", "resolutions",
                            "Choose LOCAL or INCOMING for every conflict and preview the result", preview.conflicts().stream().map(ReconciliationConflict::id).toList());
                    materializeReconciledPortfolio(context, preview.localDsl(), preview.mergedDsl());
                    session.append(request.metadata(), context.username(), "VERSION_RECONCILIATION", null, fingerprint,
                            preview.mergedDsl(), List.copyOf(affected(sourceChanges(preview.localDsl(), preview.mergedDsl()))));
                    // This only changes the next expected Git parent. No local revision is declared checkpointed yet.
                    session.rebaseCheckpoint(preview.headCommit());
                    var command = new CreateCheckpointCommand(Context.of(context, preview.headCommit(), session.state().revision()), checkpointMetadata);
                    return new ReconciliationIntent(session.prepare(checkpointMetadata, context.username(), checkpointFingerprint(context, command)), false);
                });
            } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
            Checkpoint completed = prepared.checkpoint().completed() ? prepared.checkpoint()
                    : finishCheckpoint(context, initial, prepared.checkpoint());
            return new CheckpointAccepted(Context.of(context, completed.commitId(), completed.revision()), completed.commandId(),
                    completed.commitId(), completed.fromRevision(), completed.revision(), completed.commitCreated(), prepared.replayed());
        }
    }

    private ReconciliationPreview reconciliation(RepositoryContext context, State state, ReconciliationRequest request) throws IOException {
        expect(state, request.context().revision());
        if (!Objects.equals(state.checkpointCommit(), request.context().commit()))
            throw problem("CONTEXT_CHANGED", "commit", "The reconciliation baseline changed; preview again", List.of());
        DslGitRepository repository = repositories.resolveRepository(context);
        String incomingHead = head(repository, context.branch());
        if (Objects.equals(incomingHead, state.checkpointCommit()))
            throw problem("NO_RECONCILIATION", "checkpoint", "The workspace already uses the current Git version", List.of());
        String base = source(repository, state.checkpointCommit()), local = state.dsl(), incoming = source(repository, incomingHead);
        var merger = new TaxDslSemanticMerger();
        var semantic = merger.merge(base, local, incoming);
        Map<String, String> baseBlocks = sourceBlocks(base), localBlocks = sourceBlocks(local), incomingBlocks = sourceBlocks(incoming);
        var identities = new LinkedHashSet<>(baseBlocks.keySet()); identities.addAll(localBlocks.keySet()); identities.addAll(incomingBlocks.keySet());
        List<ReconciliationConflict> conflicts = new ArrayList<>();
        Map<String, String> selected = new LinkedHashMap<>();
        Map<String, String> automatic = new LinkedHashMap<>();
        for (String id : identities) {
            String before = baseBlocks.get(id), ours = localBlocks.get(id), theirs = incomingBlocks.get(id);
            boolean localChanged = !Objects.equals(before, ours), incomingChanged = !Objects.equals(before, theirs);
            automatic.put(id, localChanged && !incomingChanged ? ours : theirs);
            if (localChanged && incomingChanged) {
                String reason = "Both versions changed this block, including its source annotations";
                var block = ArchitectureSemanticPatch.index(ours == null ? theirs : ours).values().stream().findFirst().orElse(null);
                if (block != null) reason = semantic.conflicts().stream().filter(c -> c.blockIdentity().equals(merger.identity(block)))
                        .map(c -> c.reason()).findFirst().orElse(reason);
                conflicts.add(new ReconciliationConflict(id, reason, before, ours, theirs, request.resolutions().get(id)));
                selected.put(id, request.resolutions().get(id) == Resolution.LOCAL ? ours : theirs);
            } else selected.put(id, localChanged ? ours : theirs);
        }
        CommandProblem combinedProblem = reconciliationProblem(local, selectSources(incoming, automatic));
        if (combinedProblem != null) {
            // Dependent edits can conflict across different blocks. Keep this decision set independent
            // of the user's choices: selecting one complete source then always remains an available path.
            Set<String> shared = new HashSet<>(); conflicts.forEach(c -> shared.add(c.id()));
            for (String id : identities) {
                String before = baseBlocks.get(id), ours = localBlocks.get(id), theirs = incomingBlocks.get(id);
                if (Objects.equals(before, ours) && Objects.equals(before, theirs)) continue;
                if (!shared.contains(id)) conflicts.add(new ReconciliationConflict(id,
                        "The combined model violates " + combinedProblem.code() + ": " + combinedProblem.getMessage()
                                + "; choose compatible source blocks", before, ours, theirs, request.resolutions().get(id)));
                selected.put(id, request.resolutions().get(id) == Resolution.LOCAL ? ours : theirs);
            }
        }
        String baseSurface = sourceSurface(base), localSurface = sourceSurface(local), incomingSurface = sourceSurface(incoming);
        boolean localSurfaceChanged = !baseSurface.equals(localSurface), incomingSurfaceChanged = !baseSurface.equals(incomingSurface);
        boolean surfaceConflict = localSurfaceChanged && incomingSurfaceChanged && !localSurface.equals(incomingSurface);
        if (surfaceConflict) conflicts.add(new ReconciliationConflict(DOCUMENT_SOURCE_CONFLICT,
                "Both versions changed document annotations or metadata outside model blocks", baseSurface, localSurface,
                incomingSurface, request.resolutions().get(DOCUMENT_SOURCE_CONFLICT)));
        Set<String> conflictIds = new LinkedHashSet<>(); conflicts.forEach(c -> conflictIds.add(c.id()));
        if (!conflictIds.containsAll(request.resolutions().keySet())) throw problem("RECONCILIATION_CONFLICT", "resolutions",
                "Decisions must name only the conflicts in this exact preview", List.copyOf(conflictIds));
        boolean canAccept = conflicts.stream().allMatch(c -> c.resolution() != null);
        String merged = null;
        if (canAccept) {
            boolean localSource = surfaceConflict ? request.resolutions().get(DOCUMENT_SOURCE_CONFLICT) == Resolution.LOCAL
                    : localSurfaceChanged && !incomingSurfaceChanged;
            merged = selectSources(localSource ? local : incoming, selected);
            if (conflicts.isEmpty() && (!semantic.isSuccessful()
                    || !ArchitectureSemanticPatch.between(semantic.mergedText(), merged).isEmpty()))
                throw problem("RECONCILIATION_CONFLICT", "source", "The lossless source selection differs from the semantic merge", semantic.conflictIdentifiers());
            CommandProblem selectedProblem = reconciliationProblem(local, merged);
            if (selectedProblem != null && Set.of("PORTFOLIO_RECONCILIATION_REQUIRED", "PORTFOLIO_UNAVAILABLE").contains(selectedProblem.code())) throw selectedProblem;
            if (selectedProblem != null) throw problem("RECONCILIATION_CONFLICT", "resolutions",
                    "The selected source blocks violate " + selectedProblem.code() + ": " + selectedProblem.getMessage()
                            + "; choose compatible sources and preview again", List.copyOf(conflictIds));
        }
        List<String> evidence = reconciliationParts(request, "RECONCILIATION_PREVIEW", incomingHead, base, local, incoming, merged);
        return new ReconciliationPreview(Context.of(context, state.checkpointCommit(), state.revision()), incomingHead,
                digest(evidence), base, local, incoming, merged, merged == null ? List.of() : sourceChanges(local, merged), List.copyOf(conflicts), canAccept);
    }

    private CommandProblem reconciliationProblem(String local, String source) {
        List<String> removed = sourceChanges(local, source).stream()
                .filter(change -> change.after() == null && isPortfolioBlock(change.before()))
                .map(ArchitectureSemanticPatch.BlockChange::id).toList();
        if (!removed.isEmpty()) return problem("PORTFOLIO_RECONCILIATION_REQUIRED", "resolutions",
                "Keep these portfolio blocks and explicitly archive the business records before removing them; a Git omission cannot delete portfolio history", removed);
        try {
            transformer.validateReconciledSource(source);
            if (hasPortfolioChanges(local, source)) portfolioPort().validatePortfolio(local, source);
            return null;
        } catch (CommandProblem invalid) { return invalid; }
    }

    private void materializeReconciledPortfolio(RepositoryContext context, String local, String merged) {
        if (!hasPortfolioChanges(local, merged)) return;
        WorkspacePortfolioGitPort portfolio = portfolioPort();
        portfolio.requireAvailable();
        // The existing owner joins the locked journal transaction. Failure leaves neither an edit nor a checkpoint intent.
        portfolio.materializePortfolio(merged, context.username(), new WorkspaceContext(
                context.username(), context.workspaceId(), context.branch(), context.repositoryId()));
    }

    private WorkspacePortfolioGitPort portfolioPort() {
        WorkspacePortfolioGitPort portfolio = portfolioGitPort == null ? null : portfolioGitPort.getIfAvailable();
        if (portfolio == null) throw problem("PORTFOLIO_UNAVAILABLE", "portfolio",
                "The portfolio feature is required to reconcile these business records", List.of());
        return portfolio;
    }

    private static boolean hasPortfolioChanges(String local, String candidate) {
        return ArchitectureSemanticPatch.between(local, candidate).stream()
                .anyMatch(change -> isPortfolioBlock(change.before()) || isPortfolioBlock(change.after()));
    }

    private static boolean isPortfolioBlock(String source) {
        return source != null && ArchitectureSemanticPatch.index(source).values().stream().anyMatch(block ->
                Set.of("project", "projectRequirement", "requirementVersion").contains(block.getKind())
                        || "true".equalsIgnoreCase(block.property("x-portfolio-managed")));
    }

    /** Keep raw blocks, comments and unknown fields; never serialize either original document. */
    private static String selectSources(String surface, Map<String, String> selected) {
        String result = surface;
        Map<String, String> existing = sourceBlocks(surface);
        List<ArchitectureSemanticPatch.BlockChange> replacements = new ArrayList<>();
        existing.forEach((id, raw) -> {
            String replacement = selected.get(id);
            // An EOF block can become a middle block. Its source must not consume the following header.
            if (replacement != null && blockTerminator(replacement).isEmpty()) replacement += blockTerminator(raw);
            if (!Objects.equals(raw, replacement)) replacements.add(new ArchitectureSemanticPatch.BlockChange(id, replacement, raw));
        });
        result = ArchitectureSemanticPatch.inverse(result, surface, replacements);
        // New independent blocks follow the retained document in stable source order; the exact result is previewed.
        for (var entry : selected.entrySet()) if (!existing.containsKey(entry.getKey()) && entry.getValue() != null)
            result += (result.isEmpty() || result.endsWith("\n") || result.endsWith("\r") ? "" : "\n") + entry.getValue();
        String expectedBlocks = selected.values().stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
        if (!ArchitectureSemanticPatch.between(expectedBlocks, result).isEmpty())
            throw problem("RECONCILIATION_CONFLICT", "source", "The selected source blocks cannot be combined without changing the model", List.copyOf(selected.keySet()));
        return result;
    }

    private static String blockTerminator(String raw) {
        return raw.endsWith("\r\n") ? "\r\n" : raw.endsWith("\n") ? "\n" : raw.endsWith("\r") ? "\r" : "";
    }

    private static String withoutBlockTerminator(String raw) {
        return raw == null ? null : raw.substring(0, raw.length() - blockTerminator(raw).length());
    }

    private static Map<String, String> sourceBlocks(String source) {
        Map<String, String> result = new LinkedHashMap<>();
        ArchitectureSemanticPatch.between("", source).forEach(c -> result.put(c.id(), c.after()));
        return result;
    }

    private static String sourceSurface(String source) {
        StringBuilder result = new StringBuilder(); int cursor = 0;
        for (String raw : sourceBlocks(source).values()) {
            int start = source.indexOf(raw, cursor);
            if (start < cursor) throw new IllegalStateException("Block source does not belong to its document");
            result.append(source, cursor, start); cursor = start + raw.length();
        }
        return result.append(source.substring(cursor)).toString();
    }

    private static List<ArchitectureSemanticPatch.BlockChange> sourceChanges(String before, String after) {
        Map<String, String> oldBlocks = sourceBlocks(before), newBlocks = sourceBlocks(after);
        Set<String> ids = new LinkedHashSet<>(oldBlocks.keySet()); ids.addAll(newBlocks.keySet());
        return ids.stream().filter(id -> !Objects.equals(oldBlocks.get(id), newBlocks.get(id)))
                .map(id -> new ArchitectureSemanticPatch.BlockChange(id, oldBlocks.get(id), newBlocks.get(id))).toList();
    }

    private static List<String> reconciliationParts(ReconciliationRequest request, String kind, String... evidence) {
        Context c = request.context(); Metadata m = request.metadata();
        List<String> parts = new ArrayList<>(Arrays.asList(kind, c.repositoryId(), c.workspaceScopeKey(), c.branch(), c.actor(),
                c.writeMode(), c.commit(), Long.toString(c.revision()), m.commandId(), m.correlationId(), m.causationId(), m.rationale()));
        parts.add(Integer.toString(request.resolutions().size()));
        new TreeMap<>(request.resolutions()).forEach((id, resolution) -> { parts.add(id); parts.add(resolution.name()); });
        parts.addAll(Arrays.asList(evidence)); return parts;
    }

    private static Metadata reconciliationCheckpointMetadata(Metadata metadata) {
        String id = UUID.nameUUIDFromBytes(("architecture-reconciliation:" + metadata.commandId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new Metadata(id, metadata.correlationId(), metadata.commandId(), metadata.rationale());
    }

    /** Current Git checkpoint evidence for an original accepted operation, including explicit reconciliation. */
    public Optional<CheckpointAccepted> integrationCheckpointForOperation(RepositoryContext context, String originalCommandId) throws IOException {
        requireWritable(context);
        String operationId = UUID.fromString(originalCommandId).toString();
        Snapshot snapshot = journal.read(context);
        if (snapshot == null) return Optional.empty();
        try {
            return journal.locked(context, snapshot.state(), session -> {
                Entry operation = session.find(operationId);
                State state = session.state();
                if (operation == null || !context.username().equals(operation.actor()) || state.pendingCheckpoint() != null
                        || state.checkpointCommit() == null || state.checkpointRevision() != state.revision()
                        || operation.revision() > state.checkpointRevision()) return Optional.empty();
                if (session.operations().stream().anyMatch(o -> o.revision() > operation.revision()
                        && operationId.equals(o.targetOperationId()))) return Optional.empty();
                Map<String, String> current = sourceBlocks(state.dsl());
                List<ArchitectureSemanticPatch.BlockChange> changes = sourceChanges(operation.beforeDsl(), operation.afterDsl());
                if (changes.stream().anyMatch(c -> !Objects.equals(withoutBlockTerminator(c.after()),
                        withoutBlockTerminator(current.get(c.id()))))) return Optional.empty();
                String beforeSurface = sourceSurface(operation.beforeDsl()), afterSurface = sourceSurface(operation.afterDsl());
                if (!beforeSurface.equals(afterSurface) && !afterSurface.equals(sourceSurface(state.dsl()))) return Optional.empty();
                // An unchanged integration has no narrower effect to compare; prove its complete accepted source.
                if (changes.isEmpty() && beforeSurface.equals(afterSurface)
                        && (!operation.beforeDsl().equals(operation.afterDsl())
                        || !operation.afterDsl().equals(state.dsl()))) return Optional.empty();
                Checkpoint checkpoint = session.currentCheckpoint();
                if (checkpoint == null || !checkpoint.dsl().equals(state.dsl())) return Optional.empty();
                try {
                    DslGitRepository repository = repositories.resolveRepository(context);
                    // Read HEAD after the row lock; a completed ORM record alone does not prove Git publication.
                    if (!Objects.equals(head(repository, context.branch()), checkpoint.commitId())
                            || !Objects.equals(source(repository, checkpoint.commitId()), checkpoint.dsl())) return Optional.empty();
                    repository.verifySnapshot(checkpoint.commitId());
                    if (!Objects.equals(head(repository, context.branch()), checkpoint.commitId())) return Optional.empty();
                    return Optional.of(new CheckpointAccepted(Context.of(context, checkpoint.commitId(), checkpoint.revision()),
                            checkpoint.commandId(), checkpoint.commitId(), checkpoint.fromRevision(), checkpoint.revision(), checkpoint.commitCreated(), true));
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    private static void requireUnrejectedCheckpoint(Checkpoint checkpoint) {
        if (checkpoint.failureCode() != null) throw problem(checkpoint.failureCode(), "checkpoint",
                "Git changed again; preview a new reconciliation with a new command identity", List.of(checkpoint.commandId()));
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

    private Change transform(RepositoryContext context, Command command, String current, List<OperationSummary> operations,
                             java.util.function.Function<String, Entry> loadOperation) {
        if (command.operation() instanceof SemanticCommand semantic) return transformer.apply(current, semantic.command());
        String target = target(command.operation());
        OperationSummary selected = operations.stream().filter(e -> e.commandId().equals(target) && e.actor().equals(context.username()))
                .findFirst().orElseThrow(() -> problem("NOT_FOUND", "targetOperationId", "No personal operation in this workspace", List.of()));
        boolean redo = command.operation() instanceof RedoArchitectureCommand;
        if (selected.bodyVersion() != 1 || (redo && !"UNDO".equals(selected.kind())) || (!redo && "UNDO".equals(selected.kind()))
                || selected.kind().startsWith("VERSION_")) {
            throw problem("NOT_UNDOABLE", "targetOperationId", "Choose an accepted edit for undo or an undo operation for redo", List.of());
        }
        for (OperationSummary newer : operations) {
            if (newer.revision() <= selected.revision()) continue;
            if (target.equals(newer.targetOperationId())) throw problem("ALREADY_INVERTED", "targetOperationId", "This operation already has an inverse", List.of(newer.commandId()));
            if (newer.affectedIds().stream().anyMatch(selected.affectedIds()::contains)) {
                throw problem("UNDO_CONFLICT", "targetOperationId", "A later operation depends on the affected objects", List.of(newer.commandId()));
            }
        }
        Entry body = loadOperation.apply(selected.commandId());
        return transformer.inverse(current, body.beforeDsl(), body.afterDsl());
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
    private static HistoryEntry history(OperationSummary entry) {
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
                case ReviewRequirementRealization realization -> {
                    var review = realization.review();
                    parts.add(realization.requirementId()); parts.add(Integer.toString(review.requirementVersion()));
                    parts.add(review.contentHash()); parts.add(review.status()); parts.add(review.contributorProject());
                    parts.add(review.actor()); parts.add(Objects.toString(review.occurredAt(), null)); parts.add(review.rationale());
                    parts.addAll(review.dependencies().stream().sorted().toList());
                }
                case AdoptAnalysisSnapshot a -> {
                    parts.add(a.viewId()); parts.add(a.title()); parts.add(a.description()); parts.add(a.graphFingerprint());
                    parts.add(Integer.toString(a.elements().size()));
                    for (var element : a.elements()) {
                        parts.add(element.id()); parts.add(element.type()); parts.add(element.title()); parts.add(element.description());
                    }
                    parts.add(Integer.toString(a.relations().size()));
                    a.relations().forEach(relation -> parts.add(relation.id()));
                    properties(parts, a.provenance());
                }
                case SetRequirementPlanning p -> planningFingerprint(parts, p.requirementId(), p.entry());
                case ImportRequirementPlanning p -> planningFingerprint(parts, p.requirementId(), p.entry());
                case DeleteRequirementPlanning p -> { parts.add(p.requirementId()); parts.add(p.entryId()); }
                case CreateArchitecturePackage p -> { parts.add(p.id()); properties(parts, p.properties()); }
                case UpdateArchitecturePackage p -> { parts.add(p.id()); properties(parts, p.properties()); }
                case DeleteArchitecturePackage p -> parts.add(p.id());
                case SetArchitecturePackagePlacements p -> {
                    p.placements().forEach(v -> { parts.add(v.kind().name()); parts.add(v.memberId()); parts.add(v.parentPackageId()); parts.add(Integer.toString(v.position())); });
                    parts.addAll(p.completeParentScopes().stream().sorted().toList());
                }
                case UpsertRequirementMapping m -> { parts.add(m.requirementIdentity()); parts.add(m.elementId()); parts.add(m.rationale()); properties(parts, m.exchangeProperties()); }
                case DeleteRequirementMapping m -> { parts.add(m.requirementIdentity()); parts.add(m.elementId()); }
                case CreateArchitectureElement e -> { parts.add(e.id()); parts.add(e.type()); properties(parts, e.properties()); }
                case UpdateArchitectureElement e -> { parts.add(e.id()); parts.add(e.type()); properties(parts, e.properties()); }
                case DeleteArchitectureElement e -> parts.add(e.id());
                case CreateArchitectureRelation r -> { parts.add(r.relation().id()); parts.add(r.status()); properties(parts, r.properties()); }
                case UpdateArchitectureRelation r -> { parts.add(r.relation().id()); parts.add(r.status()); }
                case DeleteArchitectureRelation r -> parts.add(r.relation().id());
                case MoveOrGroupElement e -> { parts.add(e.id()); parts.add(e.parentId()); }
                case SetExchangeProperties e -> { parts.add(e.objectKind()); parts.add(e.id()); properties(parts, e.properties()); }
                case ClearArchitectureElementProperties e -> { parts.add(e.id()); parts.addAll(e.properties().stream().sorted().toList()); }
                case UpsertArchitectureView e -> { parts.add(e.id()); parts.add(e.title()); parts.add(e.description()); parts.addAll(e.members()); properties(parts, e.properties()); }
                case DeleteArchitectureView e -> parts.add(e.id());
                case StoreExchangeEvidence e -> { parts.add(e.id()); parts.add(e.profile()); parts.add(e.profileVersion()); parts.add(e.fingerprint()); parts.add(e.source()); }
            }
        } else parts.add(target(command.operation()));
        return digest(parts);
    }

    private static String checkpointFingerprint(RepositoryContext context, CreateCheckpointCommand command) {
        return digest(Arrays.asList("CHECKPOINT", EditorJournal.scope(context), context.username(),
                Long.toString(command.context().revision()), command.metadata().rationale(),
                command.metadata().correlationId(), command.metadata().causationId()));
    }

    private static void planningFingerprint(List<String> parts, String requirement, com.taxonomy.dsl.planning.PlanningEntry entry) {
        parts.add(requirement); parts.add(entry.id()); parts.add(entry.profile()); parts.add(entry.version());
        parts.add(entry.origin()); properties(parts, entry.values());
    }

    private static void properties(List<String> parts, Map<String, String> properties) {
        new TreeMap<>(properties).forEach((key, value) -> { parts.add(key); parts.add(value); });
    }

    private static String digest(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream stream = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                for (String part : parts) {
                    if (part == null) { stream.writeInt(-1); continue; }
                    byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
                    stream.writeInt(encoded.length);
                    stream.write(encoded);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static CommandProblem problem(String code, String field, String message, List<String> dependencies) {
        return new CommandProblem(code, field, message, dependencies);
    }
}
