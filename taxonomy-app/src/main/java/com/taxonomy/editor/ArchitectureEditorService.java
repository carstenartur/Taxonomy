package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureDslCommands.Change;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.CommitRequest;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Git is both model authority and durable operation history. No architecture JPA writes occur here. */
@Service
public class ArchitectureEditorService implements ArchitectureCommandPort {
    private final DslGitRepositoryFactory repositories;
    private final RelationBranchProjectionRebuildService rebuild;
    private final RelationBranchProjectionReadinessService readiness;
    private final ArchitectureDslCommands transformer = new ArchitectureDslCommands();
    private final ExpectedHeadDslCommitter committer = new ExpectedHeadDslCommitter();

    public ArchitectureEditorService(DslGitRepositoryFactory repositories,
                                     RelationBranchProjectionRebuildService rebuild,
                                     RelationBranchProjectionReadinessService readiness) {
        this.repositories = repositories;
        this.rebuild = rebuild;
        this.readiness = readiness;
    }

    public record HistoryEntry(String commit, String commandId, String kind, String targetCommit,
                               String actor, String occurredAt, String rationale, List<String> changedIds) {}
    public record Document(Context context, String dsl, String projectionState, List<HistoryEntry> history) {}

    public Document read(RepositoryContext context, String commit) throws IOException {
        DslGitRepository repository = repositories.resolveRepository(context);
        String head = head(repository, context.branch());
        String selected = commit == null ? head : ObjectId.fromString(commit).name();
        if (selected != null) requireReachable(repository, head, selected);
        String dsl = source(repository, selected);
        return new Document(Context.of(context, selected), dsl,
                Objects.equals(head, selected) ? projectionState(context, selected) : "HISTORICAL",
                history(repository, context, selected));
    }

    @Override
    public Preview preview(RepositoryContext context, Command command) throws IOException {
        requireContext(context, command.context());
        requireWritable(context);
        DslGitRepository repository = repositories.resolveRepository(context);
        committer.verifyExpectedHead(repository, context.branch(), command.context().commit());
        Change change = transform(repository, context, command);
        return new Preview(command.context(), change, kind(command.operation()), target(command.operation()));
    }

    @Override
    public Accepted execute(RepositoryContext context, Command command) throws IOException {
        requireContext(context, command.context());
        requireWritable(context);
        DslGitRepository repository = repositories.resolveRepository(context);
        String fingerprint = fingerprint(command);
        RevCommit previous = findCommand(repository, context, command.metadata().commandId());
        if (previous != null) {
            if (!fingerprint.equals(footer(previous, "Editor-Fingerprint"))) {
                throw problem("COMMAND_ID_REUSED", "commandId", "Command identity was already used with another payload", List.of());
            }
            return new Accepted(Context.of(context, previous.name()), command.metadata().commandId(), false, true,
                    projectionState(context, previous.name()), new Change(source(repository, previous.name()), List.of()));
        }
        Preview preview = preview(context, command);
        if (preview.change().changes().isEmpty()) {
            throw problem("NO_CHANGE", "command", "No semantic change to accept", List.of());
        }
        var committed = committer.commit(repository, new CommitRequest(context.branch(), command.context().commit(),
                preview.change().dsl(), context.username(), message(context, command, fingerprint)));
        // Git succeeded. Projection failure cannot turn this into a rejected or replayable write.
        String state;
        try {
            var projected = rebuild.rebuild(context);
            state = committed.commitId().equals(projected.authoritativeCommitId())
                    ? projectionState(context, committed.commitId()) : "STALE";
        } catch (RuntimeException failure) {
            state = "REBUILD_REQUIRED";
        }
        return new Accepted(Context.of(context, committed.commitId()), command.metadata().commandId(), true, false,
                state, preview.change());
    }

    public String rebuild(RepositoryContext context, Context expected) throws IOException {
        requireContext(context, expected);
        requireWritable(context);
        committer.verifyExpectedHead(repositories.resolveRepository(context), context.branch(), expected.commit());
        rebuild.rebuild(context);
        return projectionState(context, expected.commit());
    }

    private Change transform(DslGitRepository repository, RepositoryContext context, Command command) throws IOException {
        String current = source(repository, command.context().commit());
        if (command.operation() instanceof SemanticCommand semantic) return transformer.apply(current, semantic.command());
        String target = target(command.operation());
        ObjectId.fromString(target);
        List<RevCommit> later = new ArrayList<>();
        RevCommit selected = null;
        try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
            for (RevCommit cursor = parse(walk, command.context().commit()); cursor != null; cursor = parent(walk, cursor)) {
                if (cursor.name().equals(target)) { selected = cursor; break; }
                later.add(cursor);
            }
        }
        if (selected == null || !scope(context).equals(footer(selected, "Editor-Scope"))
                || !context.username().equals(selected.getAuthorIdent().getName())) {
            throw problem("NOT_FOUND", "targetCommit", "No personal command at this context and commit", List.of());
        }
        String targetKind = footer(selected, "Editor-Kind");
        boolean redo = command.operation() instanceof RedoArchitectureCommand;
        if (targetKind == null || (redo && !"UNDO".equals(targetKind)) || (!redo && "UNDO".equals(targetKind))) {
            throw problem("NOT_UNDOABLE", "targetCommit", "Choose an accepted command for undo or an undo command for redo", List.of());
        }
        String before = selected.getParentCount() == 0 ? "" : source(repository, selected.getParent(0).name());
        String after = source(repository, selected.name());
        Set<String> affected = affected(ArchitectureSemanticPatch.between(before, after));
        for (RevCommit newer : later) {
            if (target.equals(footer(newer, "Editor-Target"))) {
                throw problem("ALREADY_INVERTED", "targetCommit", "This command already has an accepted inverse", List.of(newer.name()));
            }
            if (newer.getParentCount() != 1) {
                throw problem("UNDO_CONFLICT", "targetCommit", "A later merge requires a new semantic decision", List.of(newer.name()));
            }
            Set<String> touched = affected(ArchitectureSemanticPatch.between(
                    source(repository, newer.getParent(0).name()), source(repository, newer.name())));
            if (touched.stream().anyMatch(affected::contains)) {
                throw problem("UNDO_CONFLICT", "targetCommit", "A later command depends on the affected objects", List.of(newer.name()));
            }
        }
        return transformer.inverse(current, before, after);
    }

    private List<HistoryEntry> history(DslGitRepository repository, RepositoryContext context, String head) throws IOException {
        List<HistoryEntry> entries = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
            for (RevCommit cursor = parse(walk, head); cursor != null && entries.size() < 50; cursor = parent(walk, cursor)) {
                if (!scope(context).equals(footer(cursor, "Editor-Scope"))) continue;
                String before = cursor.getParentCount() == 0 ? "" : source(repository, cursor.getParent(0).name());
                entries.add(new HistoryEntry(cursor.name(), footer(cursor, "Editor-Command-Id"), footer(cursor, "Editor-Kind"),
                        footer(cursor, "Editor-Target"), cursor.getAuthorIdent().getName(),
                        cursor.getAuthorIdent().getWhenAsInstant().toString(), footer(cursor, "Rationale"),
                        ArchitectureSemanticPatch.between(before, source(repository, cursor.name())).stream()
                                .map(ArchitectureSemanticPatch.BlockChange::id).toList()));
            }
        }
        return List.copyOf(entries);
    }

    private RevCommit findCommand(DslGitRepository repository, RepositoryContext context, String id) throws IOException {
        try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
            for (RevCommit cursor = parse(walk, head(repository, context.branch())); cursor != null; cursor = parent(walk, cursor)) {
                if (scope(context).equals(footer(cursor, "Editor-Scope")) && id.equals(footer(cursor, "Editor-Command-Id"))
                        && context.username().equals(cursor.getAuthorIdent().getName())) return cursor;
            }
        }
        return null;
    }

    private static void requireReachable(DslGitRepository repository, String head, String selected) throws IOException {
        try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
            if (head != null && walk.isMergedInto(walk.parseCommit(ObjectId.fromString(selected)), walk.parseCommit(ObjectId.fromString(head)))) return;
        } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException ignored) {
            // Guessed objects must not expose whether another branch/repository contains the ID.
        }
        throw problem("NOT_FOUND", "commit", "Commit is not reachable in the selected context", List.of());
    }

    private String projectionState(RepositoryContext context, String commit) {
        if (commit == null) return "BRANCH_MISSING";
        try {
            var state = readiness.inspect(context);
            return commit.equals(state.currentHeadCommit()) && commit.equals(state.projectedCommit())
                    ? state.state().name() : "STALE";
        } catch (RuntimeException unavailable) { return "REBUILD_REQUIRED"; }
    }

    static void requireContext(RepositoryContext context, Context requested) {
        if (!Context.of(context, requested.commit()).equals(requested)) {
            throw problem("CONTEXT_CHANGED", "context", "The selected repository, workspace, branch, actor or mode changed; reopen the editor", List.of());
        }
        if (requested.commit() != null) ObjectId.fromString(requested.commit());
    }

    private static void requireWritable(RepositoryContext context) {
        if (context.scope() != RepositoryScope.WORKSPACE) {
            throw problem("READ_ONLY", "context", "Open a private writable workspace to edit this architecture", List.of());
        }
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

    private static RevCommit parse(RevWalk walk, String commit) throws IOException {
        return commit == null ? null : walk.parseCommit(ObjectId.fromString(commit));
    }

    private static RevCommit parent(RevWalk walk, RevCommit commit) throws IOException {
        return commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0));
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

    private static String footer(RevCommit commit, String name) {
        List<String> values = commit.getFooterLines(name);
        return values.size() == 1 ? values.getFirst() : null;
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
            case UndoArchitectureCommand undo -> undo.targetCommit();
            case RedoArchitectureCommand redo -> redo.targetCommit();
            default -> null;
        };
    }

    private static String message(RepositoryContext context, Command command, String fingerprint) {
        return "architecture: " + kind(command.operation()) + "\n\nEditor-Version: 1\nEditor-Command-Id: "
                + command.metadata().commandId() + "\nEditor-Fingerprint: " + fingerprint
                + "\nEditor-Scope: " + scope(context) + "\nEditor-Kind: " + kind(command.operation())
                + (target(command.operation()) == null ? "" : "\nEditor-Target: " + target(command.operation()))
                + "\nCorrelation-Id: " + command.metadata().correlationId()
                + "\nCausation-Id: " + command.metadata().causationId() + "\nRationale: " + command.metadata().rationale();
    }

    private static String scope(RepositoryContext context) {
        return digest(List.of(context.repositoryId(), context.workspaceId() == null ? "central" : context.workspaceId(), context.branch()));
    }

    private static String fingerprint(Command command) {
        List<String> parts = new ArrayList<>(List.of(kind(command.operation()), command.context().toString(),
                command.metadata().correlationId(), command.metadata().causationId(), command.metadata().rationale()));
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
