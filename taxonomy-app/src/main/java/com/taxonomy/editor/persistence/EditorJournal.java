package com.taxonomy.editor.persistence;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.Metadata;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/** The transaction boundary for accepted edits. No Git writes or derived model rows enter this store. */
@Repository
public class EditorJournal {
    private final EntityManager em;
    private final TransactionTemplate transaction;

    public EditorJournal(EntityManagerFactory factory, PlatformTransactionManager manager) {
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record State(String dsl, long revision, String checkpointCommit, long checkpointRevision,
                        String pendingCheckpoint) {}
    public record Entry(String commandId, String actor, String occurredAt, String rationale, String kind,
                        String targetOperationId, long previousRevision, long revision, String fingerprint,
                        int bodyVersion, String beforeDsl, String afterDsl, List<String> affectedIds) {}
    /** History/conflict scans never materialize the full source bodies of every operation. */
    public record OperationSummary(String commandId, String actor, String occurredAt, String rationale, String kind,
                                   String targetOperationId, long previousRevision, long revision,
                                   int bodyVersion, List<String> affectedIds) {}
    public record Snapshot(State state, List<OperationSummary> operations) {}
    public record Checkpoint(String commandId, String actor, String occurredAt, String rationale,
                             String fingerprint, long fromRevision, long revision, String expectedCommit,
                             String dsl, String commitId, boolean completed, boolean commitCreated, String failureCode, String origin) {}

    public Snapshot read(RepositoryContext context) {
        String scope = scope(context);
        return transaction.execute(status -> {
            EditorWorkspace workspace = em.find(EditorWorkspace.class, scope);
            return workspace == null ? null : new Snapshot(state(workspace), entries(scope, workspace.revision));
        });
    }

    public Entry operation(RepositoryContext context, String commandId) {
        return transaction.execute(status -> {
            EditorOperation operation = em.find(EditorOperation.class, key(scope(context), commandId));
            return operation == null ? null : entry(operation);
        });
    }

    /** Immutable historical source; fetch only the requested revision, including the initial baseline. */
    public String sourceAtRevision(RepositoryContext context, long revision) {
        return transaction.execute(status -> em.createQuery(
                "select o." + (revision == 0 ? "beforeDsl" : "afterDsl")
                        + " from EditorOperation o where o.scopeId = :scope and o.revision = :revision", String.class)
                .setParameter("scope", scope(context)).setParameter("revision", revision == 0 ? 1L : revision)
                .getResultList().stream().findFirst().map(EditorJournal::unframe).orElse(null));
    }

    public List<Checkpoint> checkpoints(RepositoryContext context) {
        return transaction.execute(status -> em.createQuery(
                "select c from EditorCheckpoint c where c.scopeId = :scope and c.completed = true order by c.revision desc", EditorCheckpoint.class)
                .setParameter("scope", scope(context)).getResultList().stream().map(EditorJournal::checkpoint).toList());
    }

    public <T> T locked(RepositoryContext context, State initial, Function<Session, T> action) {
        String scope = scope(context);
        boolean[] initializing = {false};
        // Two application instances can race on the very first accepted command.
        // The primary key chooses the initializer; retry only after that transaction rolled back.
        try {
            return transact(context, scope, initial, action, initializing);
        } catch (PersistenceException | org.springframework.dao.DataIntegrityViolationException failure) {
            // Only retry a failed initializer insert. Once the application action has started,
            // it may have published a Git version; replaying it would repeat an external side effect.
            if (!initializing[0] || read(context) == null) throw failure;
            return transact(context, scope, initial, action, new boolean[1]);
        }
    }

    private <T> T transact(RepositoryContext context, String scope, State initial, Function<Session, T> action,
                           boolean[] initializing) {
        return transaction.execute(status -> {
            EditorWorkspace workspace = em.find(EditorWorkspace.class, scope, LockModeType.PESSIMISTIC_WRITE);
            if (workspace == null) {
                workspace = new EditorWorkspace();
                workspace.scopeId = scope;
                workspace.repositoryId = context.repositoryId();
                workspace.workspaceId = context.workspaceId();
                workspace.branch = context.branch();
                workspace.dsl = frame(initial.dsl());
                workspace.checkpointCommit = initial.checkpointCommit();
                initializing[0] = true;
                em.persist(workspace);
                em.flush();
                initializing[0] = false;
            }
            T result = action.apply(new Session(workspace));
            em.flush();
            return result;
        });
    }

    public final class Session {
        private final EditorWorkspace workspace;
        private Session(EditorWorkspace workspace) { this.workspace = workspace; }
        public State state() { return EditorJournal.state(workspace); }
        public List<OperationSummary> operations() { return entries(workspace.scopeId, workspace.revision); }
        public Entry find(String commandId) {
            EditorOperation operation = em.find(EditorOperation.class, key(workspace.scopeId, commandId));
            return operation == null ? null : entry(operation);
        }
        public Checkpoint checkpoint(String commandId) {
            EditorCheckpoint checkpoint = em.find(EditorCheckpoint.class, key(workspace.scopeId, commandId));
            return checkpoint == null ? null : EditorJournal.checkpoint(checkpoint);
        }
        public void expect(long revision) {
            if (workspace.revision != revision) throw new RevisionConflict(revision, workspace.revision);
            if (workspace.pendingCheckpoint != null) throw problem("CHECKPOINT_PENDING", "Retry the pending checkpoint before editing", List.of(workspace.pendingCheckpoint));
        }
        public Entry append(Metadata metadata, String actor, String kind, String target, String fingerprint,
                            String dsl, List<String> affected) {
            if (checkpoint(metadata.commandId()) != null) throw problem("COMMAND_ID_REUSED", "Command ID belongs to a checkpoint", List.of());
            EditorOperation operation = new EditorOperation();
            operation.id = key(workspace.scopeId, metadata.commandId());
            operation.scopeId = workspace.scopeId;
            operation.commandId = metadata.commandId();
            operation.actor = actor;
            operation.occurredAt = Instant.now().toString();
            operation.rationale = metadata.rationale();
            operation.correlationId = metadata.correlationId();
            operation.causationId = metadata.causationId();
            operation.kind = kind;
            operation.targetOperationId = target;
            operation.fingerprint = fingerprint;
            operation.bodyVersion = 1;
            operation.beforeDsl = workspace.dsl;
            operation.afterDsl = frame(dsl);
            operation.affectedIds = frame(String.join("\n", affected));
            operation.previousRevision = workspace.revision;
            operation.revision = ++workspace.revision;
            workspace.dsl = operation.afterDsl;
            em.persist(operation);
            return entry(operation);
        }
        public Checkpoint prepare(Metadata metadata, String actor, String fingerprint) {
            if (find(metadata.commandId()) != null) throw problem("COMMAND_ID_REUSED", "Command ID belongs to an operation", List.of());
            EditorCheckpoint checkpoint = new EditorCheckpoint();
            checkpoint.id = key(workspace.scopeId, metadata.commandId());
            checkpoint.scopeId = workspace.scopeId;
            checkpoint.commandId = metadata.commandId();
            checkpoint.actor = actor;
            checkpoint.origin = "EDITOR_CHECKPOINT";
            checkpoint.occurredAt = Instant.now().toString();
            checkpoint.rationale = metadata.rationale();
            checkpoint.fingerprint = fingerprint;
            checkpoint.fromRevision = workspace.checkpointRevision;
            checkpoint.revision = workspace.revision;
            checkpoint.expectedCommit = workspace.checkpointCommit;
            checkpoint.dsl = workspace.dsl;
            workspace.pendingCheckpoint = metadata.commandId();
            em.persist(checkpoint);
            return EditorJournal.checkpoint(checkpoint);
        }
        public Checkpoint complete(String commandId, String commitId, boolean created) {
            EditorCheckpoint checkpoint = em.find(EditorCheckpoint.class, key(workspace.scopeId, commandId));
            if (checkpoint.completed) return EditorJournal.checkpoint(checkpoint);
            if (!commandId.equals(workspace.pendingCheckpoint) || workspace.revision != checkpoint.revision) {
                throw new IllegalStateException("Checkpoint reservation no longer owns this workspace revision");
            }
            checkpoint.commitId = commitId;
            checkpoint.commitCreated = created;
            checkpoint.completed = true;
            workspace.checkpointCommit = commitId;
            workspace.checkpointRevision = checkpoint.revision;
            workspace.pendingCheckpoint = null;
            return EditorJournal.checkpoint(checkpoint);
        }
        public void rejectCheckpoint(String commandId) {
            EditorCheckpoint checkpoint = em.find(EditorCheckpoint.class, key(workspace.scopeId, commandId));
            if (checkpoint.completed) return;
            checkpoint.failureCode = "CHECKPOINT_CONFLICT";
            if (commandId.equals(workspace.pendingCheckpoint)) workspace.pendingCheckpoint = null;
        }
        /** A version-boundary import is journaled by the caller before adopting the new Git authority. */
        public void adoptVersion(String commit, String actor, String rationale) {
            String commandId = java.util.UUID.nameUUIDFromBytes(("version:" + workspace.scopeId + ":" + commit
                    + ":" + workspace.revision).getBytes(StandardCharsets.UTF_8)).toString();
            String id = key(workspace.scopeId, commandId);
            if (em.find(EditorCheckpoint.class, id) == null) {
                EditorCheckpoint checkpoint = new EditorCheckpoint();
                checkpoint.id = id;
                checkpoint.scopeId = workspace.scopeId;
                checkpoint.commandId = commandId;
                checkpoint.origin = "VERSION_ACTION";
                checkpoint.actor = actor;
                checkpoint.occurredAt = Instant.now().toString();
                checkpoint.rationale = rationale;
                checkpoint.fromRevision = workspace.checkpointRevision;
                checkpoint.revision = workspace.revision;
                checkpoint.expectedCommit = workspace.checkpointCommit;
                checkpoint.dsl = workspace.dsl;
                checkpoint.commitId = commit;
                checkpoint.fingerprint = hash("version:" + commandId);
                checkpoint.completed = true;
                // The separate version action owns Git creation; this record only links its resulting version.
                checkpoint.commitCreated = false;
                em.persist(checkpoint);
            }
            workspace.checkpointCommit = commit;
            workspace.checkpointRevision = workspace.revision;
        }
    }

    private List<OperationSummary> entries(String scope, long revision) {
        return em.createQuery("select o.commandId, o.actor, o.occurredAt, o.rationale, o.kind, o.targetOperationId, "
                        + "o.previousRevision, o.revision, o.bodyVersion, o.affectedIds from EditorOperation o "
                        + "where o.scopeId = :scope and o.revision <= :revision order by o.revision desc", Object[].class)
                .setParameter("scope", scope).setParameter("revision", revision).getResultList().stream()
                .map(row -> new OperationSummary((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                        (String) row[4], (String) row[5], ((Number) row[6]).longValue(), ((Number) row[7]).longValue(),
                        ((Number) row[8]).intValue(), affectedIds((String) row[9]))).toList();
    }
    private static State state(EditorWorkspace w) {
        return new State(unframe(w.dsl), w.revision, w.checkpointCommit, w.checkpointRevision, w.pendingCheckpoint);
    }
    private static Entry entry(EditorOperation o) {
        return new Entry(o.commandId, o.actor, o.occurredAt, o.rationale, o.kind, o.targetOperationId,
                o.previousRevision, o.revision, o.fingerprint, o.bodyVersion, unframe(o.beforeDsl), unframe(o.afterDsl),
                affectedIds(o.affectedIds));
    }
    private static List<String> affectedIds(String framed) {
        String ids = unframe(framed);
        return ids.isEmpty() ? List.of() : List.of(ids.split("\n"));
    }
    private static Checkpoint checkpoint(EditorCheckpoint c) {
        return new Checkpoint(c.commandId, c.actor, c.occurredAt, c.rationale, c.fingerprint, c.fromRevision,
                c.revision, c.expectedCommit, unframe(c.dsl), c.commitId, c.completed, c.commitCreated, c.failureCode, c.origin);
    }
    // A nonempty version envelope also preserves empty source on Oracle, which maps empty VARCHARs to NULL.
    private static String frame(String source) { return "1:" + source; }
    private static String unframe(String source) {
        if (source == null || !source.startsWith("1:")) throw new IllegalStateException("Unknown or missing journal source body");
        return source.substring(2);
    }
    private static String key(String scope, String commandId) { return scope + ":" + commandId; }
    public static String scope(RepositoryContext context) {
        return hash(context.repositoryId() + "\u0000" + context.workspaceId() + "\u0000" + context.branch());
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static CommandProblem problem(String code, String detail, List<String> dependencies) {
        return new CommandProblem(code, "commandId", detail, dependencies);
    }
    public static final class RevisionConflict extends RuntimeException {
        private final long expected;
        private final long actual;
        public RevisionConflict(long expected, long actual) {
            super("Workspace revision moved from " + expected + " to " + actual);
            this.expected = expected; this.actual = actual;
        }
        public long expected() { return expected; }
        public long actual() { return actual; }
    }
}
