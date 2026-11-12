package com.taxonomy.analysis.operation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable admission and settlement for provider-backed adapters. Every database operation commits independently of dispatch. */
public final class AnalysisAdapterStore {
    public static final long ORPHAN_MILLIS = Duration.ofMinutes(30).toMillis();
    public static final long RETAIN_MILLIS = Duration.ofDays(30).toMillis();
    public static final long MARKER_MILLIS = Duration.ofDays(90).toMillis();
    private static final Set<String> KINDS = Set.of("DOCUMENT_EXTRACTION", "REGULATION_MAPPING", "NODE_CHILDREN", "LEAF_JUSTIFICATION", "FOCUS_INVESTIGATION");
    private final EntityManager em;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public record Scope(String actor, String repository, String workspace, String branch) {
        public Scope {
            if (!UUID.fromString(actor).toString().equals(actor)) throw new IllegalArgumentException("Invalid analysis actor");
            requireText(repository, 320);
            if (workspace != null) requireText(workspace, 320);
            requireText(branch, 512);
        }
    }
    public record Operation(String id, Scope scope, String kind, String sourceCommit, String requestKey, String fingerprint,
            String state, String resultJson, String failureCode, long createdAt, long updatedAt, long revision) {}
    public record Admission(Operation operation, boolean claimed) {}

    public AnalysisAdapterStore(EntityManager em, PlatformTransactionManager tx) { this(em, tx, Clock.systemUTC()); }
    public AnalysisAdapterStore(EntityManager em, PlatformTransactionManager tx, Clock clock) {
        this.em = Objects.requireNonNull(em);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = new TransactionTemplate(tx);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Admission admit(Scope scope, String kind, String source, String fingerprint, String callerKey, boolean dispatchAvailable) {
        if (!kinds().contains(kind) || source == null || !source.matches("[a-f0-9]{40}|[a-f0-9]{64}") || !hash(fingerprint))
            throw new IllegalArgumentException("Invalid analysis command");
        if (callerKey != null && (callerKey.isBlank() || callerKey.length() > 256 || callerKey.chars().anyMatch(Character::isISOControl)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analysis idempotency key");
        String key = callerKey == null ? digest("input", source, fingerprint) : digest("caller", callerKey);
        String command = command(scope, kind, key);
        var found = transactions.execute(status -> byCommand(command));
        if (found != null) return replay(scope, source, fingerprint, found);
        if (!dispatchAvailable) return null;
        try {
            return transactions.execute(status -> {
                var row = new AnalysisAdapterOperation();
                row.id = UUID.randomUUID().toString();
                row.actor = scope.actor(); row.repository = scope.repository();
                row.workspace = scope.workspace(); row.branch = scope.branch();
                row.kind = kind; row.sourceCommit = source; row.fingerprint = fingerprint;
                row.requestKey = key; row.commandKey = command; row.state = "RUNNING";
                row.createdAt = row.updatedAt = clock.millis();
                em.persist(row);
                em.flush();
                return new Admission(snapshot(row), true);
            });
        } catch (RuntimeException conflict) {
            var winner = transactions.execute(status -> byCommand(command));
            if (winner == null) throw conflict;
            return replay(scope, source, fingerprint, winner);
        }
    }

    private Operation byCommand(String command) {
        var rows = em.createQuery("select o from AnalysisAdapterOperation o where o.commandKey=:command", AnalysisAdapterOperation.class)
                .setParameter("command", command).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        return age(row, clock.millis()) ? snapshot(row) : null;
    }

    private Admission replay(Scope scope, String source, String fingerprint, Operation prior) {
        if (!scope.equals(prior.scope())) throw unavailable();
        if (!prior.sourceCommit().equals(source) || !prior.fingerprint().equals(fingerprint))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis source or command changed");
        return new Admission(prior, false);
    }

    public Operation read(Scope scope, String id) { return transactions.execute(status -> snapshot(owned(scope, id))); }
    public Operation cancel(Scope scope, String id) {
        return transactions.execute(status -> {
            var row = owned(scope, id);
            if (row.state.equals("RUNNING")) {
                row.state = "CANCELLED"; row.updatedAt = clock.millis(); em.flush();
            }
            return snapshot(row);
        });
    }

    public Operation settle(Scope scope, String id, long revision, String result, String failure) {
        if ((result == null) == (failure == null)) throw new IllegalArgumentException("Exactly one analysis outcome is required");
        return transactions.execute(status -> {
            var row = owned(scope, id);
            if (row.revision == revision && row.state.equals("RUNNING")) {
                row.resultJson = result; row.failureCode = failure;
                row.state = failure == null ? "COMPLETED" : "FAILED";
                row.updatedAt = clock.millis(); em.flush();
            }
            return snapshot(row);
        });
    }

    private AnalysisAdapterOperation owned(Scope scope, String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw unavailable();
        var row = em.find(AnalysisAdapterOperation.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null || !scope.equals(scope(row)) || !age(row, clock.millis())) throw unavailable();
        return row;
    }

    public void sweep(long now) { sweep(null, now); }
    public void sweepScope(Scope scope, long now) { sweep(Objects.requireNonNull(scope), now); }

    private void sweep(Scope scope, long now) {
        String where = """
                ((o.state='RUNNING' and o.updatedAt<=:orphan)
                or (o.state in ('COMPLETED','FAILED','CANCELLED') and o.updatedAt<=:retained)
                or (o.state='EXPIRED' and o.updatedAt<=:marker))
                """;
        if (scope != null) where += " and o.actor=:actor and o.repository=:repository and o.branch=:branch and "
                + (scope.workspace() == null ? "o.workspace is null" : "o.workspace=:workspace");
        String selection = "select o.id from AnalysisAdapterOperation o where " + where + " order by o.updatedAt,o.id";
        List<String> ids = transactions.execute(status -> {
            var query = em.createQuery(selection,String.class).setParameter("orphan",now - ORPHAN_MILLIS)
                    .setParameter("retained",now - RETAIN_MILLIS).setParameter("marker",now - MARKER_MILLIS);
            if (scope != null) {
                query.setParameter("actor",scope.actor()).setParameter("repository",scope.repository()).setParameter("branch",scope.branch());
                if (scope.workspace() != null) query.setParameter("workspace",scope.workspace());
            }
            return query.setMaxResults(64).getResultList();
        });
        for (String id : ids) transactions.executeWithoutResult(status -> {
            var row = em.find(AnalysisAdapterOperation.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (row != null && (scope == null || scope.equals(scope(row)))) age(row, now);
        });
    }

    private boolean age(AnalysisAdapterOperation row, long now) {
        if (row.state.equals("RUNNING") && row.updatedAt <= now - ORPHAN_MILLIS) {
            row.state = "FAILED"; row.failureCode = "OUTCOME_UNKNOWN"; row.updatedAt += ORPHAN_MILLIS;
        }
        if (!row.state.equals("RUNNING") && !row.state.equals("EXPIRED") && row.updatedAt <= now - RETAIN_MILLIS) {
            row.resultJson = null; row.failureCode = null; row.state = "EXPIRED"; row.updatedAt += RETAIN_MILLIS;
        }
        if (row.state.equals("EXPIRED") && row.updatedAt <= now - MARKER_MILLIS) { em.remove(row); em.flush(); return false; }
        em.flush();
        return true;
    }

    static Set<String> kinds() { return KINDS; }
    static String command(Scope s, String kind, String key) { return digest(s.actor(), s.repository(), s.workspace() == null ? "" : s.workspace(), s.branch(), kind, key); }
    static Scope scope(AnalysisAdapterOperation row) { return new Scope(row.actor, row.repository, row.workspace, row.branch); }
    static Operation snapshot(AnalysisAdapterOperation r) {
        return new Operation(r.id, scope(r), r.kind, r.sourceCommit, r.requestKey, r.fingerprint, r.state, r.resultJson,
                r.failureCode, r.createdAt, r.updatedAt, r.revision);
    }
    static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("Invalid analysis scope");
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis operation is unavailable"); }
    public static String digest(String... parts) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part == null) { sha.update(ByteBuffer.allocate(4).putInt(-1).array()); continue; }
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
