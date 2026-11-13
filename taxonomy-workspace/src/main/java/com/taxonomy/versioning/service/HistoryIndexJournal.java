package com.taxonomy.versioning.service;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class HistoryIndexJournal {
    public enum Kind {
        INDEX, REBUILD, PURGE, RELATION, VECTOR_BUILD, VECTOR_PURGE;

        boolean destructive() { return this == PURGE || this == VECTOR_PURGE; }
        boolean relationProjection() { return this == RELATION || this == VECTOR_BUILD || this == VECTOR_PURGE; }
    }

    public record Receipt(String operationId, String causationId, Kind kind, String repositoryId,
                          String workspaceId, String branch, String sourceCommit, String actor,
                          String phase, int attempts, Integer count, String executor, String reason,
                          long createdAt, Long attemptedAt, Long completedAt, Long archivedAt) {}

    record Capture(String commit, String owner, String sourceKey, String generation) {
        Capture(String commit, String owner) { this(commit, owner, null, null); }
    }
    record Claim(String id, String lease) {}
    record Operation(Receipt receipt, String username, String owner, RepositoryScope scope,
                     boolean collaborative, Grant grant, String sourceKey, String generation, String result) {
        RepositoryContext context() {
            return new RepositoryContext(receipt.repositoryId(), receipt.workspaceId(),
                    receipt.branch(), username, scope, collaborative);
        }
    }

    private static final long LEASE = Duration.ofMinutes(5).toMillis();
    private static final int MAX_ATTEMPTS = 6;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public HistoryIndexJournal(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    HistoryIndexJournal(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    private static final RowMapper<Operation> ROW = (r, n) -> {
        String actor = r.getString("actor_id");
        var receipt = new Receipt(r.getString("operation_id"), r.getString("cause_id"), Kind.valueOf(r.getString("command_kind")),
                r.getString("repository_id"), r.getString("workspace_id"), r.getString("branch_name"), r.getString("captured_commit"),
                actor, r.getString("phase"), r.getInt("attempts"), r.getObject("result_count", Integer.class),
                r.getString("executor_id"), r.getString("reason_code"), r.getLong("created_at"),
                r.getObject("attempted_at", Long.class), r.getObject("completed_at", Long.class), r.getObject("archived_at", Long.class));
        return new Operation(receipt, r.getString("actor_scope"), r.getString("workspace_owner"),
                RepositoryScope.valueOf(r.getString("repository_scope")), r.getBoolean("collaborative"),
                new Grant(actor, r.getString("login_kind"), r.getString("binding_key")),
                r.getString("projection_source_key"), r.getString("captured_generation"), r.getString("projection_result"));
    };

    Operation admit(RepositoryContext selected, String branch, Kind kind, Grant grant, String key, String cause,
                    Supplier<Capture> source) {
        if (key != null && !key.matches("[a-zA-Z0-9._~:-]{1,128}")) throw bad("Invalid history index idempotency key");
        if (cause != null && !cause.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw bad("Invalid causation ID");
        String id = StableIdentityHash.sha256(String.join("\u0000", selected.repositoryId(),
                selected.workspaceId() == null ? "C" : "W:" + selected.workspaceId(), grant.principalId(),
                key == null ? UUID.randomUUID().toString() : "key:" + key));
        try {
            return transaction.execute(status -> {
                var existing = lookup(id);
                if (existing != null) return replay(existing, selected, branch, kind, grant);
                var capture = source.get();
                long now = clock.millis();
                jdbc.update("""
                        insert into history_index_operation(operation_id,command_kind,cause_id,actor_id,actor_scope,login_kind,
                        binding_key,repository_id,workspace_id,workspace_owner,repository_scope,collaborative,branch_name,
                        captured_commit,projection_source_key,captured_generation,phase,attempts,created_at,due_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ADMITTED',0,?,?)
                        """, id, kind.name(), cause == null ? id : cause, grant.principalId(), selected.username(), grant.loginKind(),
                        grant.bindingKey(), selected.repositoryId(), selected.workspaceId(), capture.owner(), selected.scope().name(),
                        selected.collaborative(), branch, capture.commit(), capture.sourceKey(), capture.generation(), now, now);
                if (kind == Kind.PURGE) {
                    String tenant = selected.workspaceId() == null ? "workspace_id is null" : "workspace_id = ?";
                    String sql = "insert into history_index_purge_target(operation_id,projection_id) select ?,id from architecture_commit_index where repository_id=? and " + tenant;
                    if (selected.workspaceId() == null) jdbc.update(sql, id, selected.repositoryId());
                    else jdbc.update(sql, id, selected.repositoryId(), selected.workspaceId());
                }
                event(id, grant.principalId(), "ADMITTED", now);
                return lookup(id);
            });
        } catch (DuplicateKeyException conflict) {
            var winner = lookup(id);
            if (winner == null) throw conflict;
            return replay(winner, selected, branch, kind, grant);
        }
    }

    private Operation replay(Operation row, RepositoryContext selected, String branch, Kind kind, Grant grant) {
        checkOwner(row, selected, grant.principalId());
        if (row.receipt.archivedAt() != null) throw new ResponseStatusException(HttpStatus.GONE);
        if (row.receipt.kind() != kind || !row.receipt.branch().equals(branch) || !row.grant.equals(grant)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "History index command differs from the original");
        }
        return row;
    }

    void saveProjectionResult(String id, String result) {
        if (result.length() > 2048) throw new IllegalArgumentException("Projection result exceeds its fixed receipt");
        jdbc.update("update history_index_operation set projection_result=? where operation_id=? and phase='RUNNING'", result, id);
    }

    Operation get(RepositoryContext selected, String actor, String id) {
        Operation row = lookup(id);
        checkOwner(row, selected, actor);
        return row;
    }

    Receipt inspect(RepositoryContext selected, String actor, String id, java.util.function.Consumer<Operation> authorize) {
        return inspectOperation(selected, actor, id, authorize).receipt();
    }

    Operation inspectOperation(RepositoryContext selected, String actor, String id, java.util.function.Consumer<Operation> authorize) {
        return transaction.execute(status -> {
            Operation row = get(selected, actor, id);
            authorize.accept(row);
            return row;
        });
    }

    private Operation lookup(String id) {
        return jdbc.query("select * from history_index_operation where operation_id=?", ROW, id).stream().findFirst().orElse(null);
    }

    private static void checkOwner(Operation row, RepositoryContext selected, String actor) {
        if (row == null || !row.receipt.actor().equals(actor) || !row.username.equals(selected.username())
                || !row.receipt.repositoryId().equals(selected.repositoryId())
                || !Objects.equals(row.receipt.workspaceId(), selected.workspaceId())
                || row.scope != selected.scope() || row.collaborative != selected.collaborative()
                || (row.receipt.kind().relationProjection() && !row.receipt.branch().equals(selected.branch()))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    Claim claim(String id, String executor, boolean explicit) {
        long now = clock.millis();
        String eligible = explicit
                ? "(phase in ('ADMITTED','RETRYABLE','AWAITING_AUTHORIZATION') or (phase='RUNNING' and due_at<=?))"
                : "(phase in ('ADMITTED','RETRYABLE','RUNNING') and due_at<=?)";
        return transaction.execute(status -> {
            String lease = UUID.randomUUID().toString();
            int won = jdbc.update("""
                    update history_index_operation set phase='RUNNING',attempts=attempts+1,executor_id=?,lease_id=?,attempted_at=?,due_at=?,projection_result=null
                    where operation_id=? and archived_at is null and attempts < ? and
                    """ + eligible, executor, lease, now, now + LEASE, id, MAX_ATTEMPTS, now);
            if (won == 1) {
                event(id, executor, "CLAIMED", now);
                return new Claim(id, lease);
            }
            if (jdbc.update("""
                    update history_index_operation set phase='MANUAL_REVIEW',reason_code='ATTEMPTS_EXHAUSTED',completed_at=?,due_at=null,lease_id=null
                    where operation_id=? and archived_at is null and attempts>=? and
                    """ + eligible, now, id, MAX_ATTEMPTS, now) == 1) event(id, executor, "ATTEMPTS_EXHAUSTED", now);
            return null;
        });
    }

    @FunctionalInterface interface Projection { int apply(Operation row); }

    Receipt execute(Claim token, Projection projection) {
        return transaction.execute(status -> {
            Operation row = lock(token);
            int count = projection.apply(row);
            long now = clock.millis();
            if (jdbc.update("""
                    update history_index_operation set phase='COMPLETED',result_count=?,reason_code='APPLIED',
                    completed_at=?,due_at=null,lease_id=null where operation_id=? and lease_id=? and due_at>?
                    """, count, now, token.id, token.lease, now) != 1) throw lost();
            event(token.id, row.receipt.executor(), "COMPLETED", now);
            return lookup(token.id).receipt;
        });
    }

    Receipt failed(Claim token, String phase, String reason) {
        return failed(token, phase, reason, null);
    }

    Receipt failed(Claim token, String phase, String reason, String result) {
        return transaction.execute(status -> {
            Operation row = lock(token);
            if (result != null) saveProjectionResult(token.id(), result);
            long now = clock.millis();
            boolean retry = phase.equals("RETRYABLE");
            Long due = retry ? now + Duration.ofSeconds(15L << (row.receipt.attempts() - 1)).toMillis() : null;
            Long finished = retry || phase.equals("AWAITING_AUTHORIZATION") ? null : now;
            jdbc.update("update history_index_operation set phase=?,reason_code=?,due_at=?,completed_at=?,lease_id=null where operation_id=? and lease_id=?",
                    phase, reason, due, finished, token.id, token.lease);
            event(token.id, row.receipt.executor(), reason, now);
            return lookup(token.id).receipt;
        });
    }

    Operation claimed(Claim claim) {
        return transaction.execute(status -> lock(claim));
    }

    private Operation lock(Claim claim) {
        if (jdbc.update("update history_index_operation set lease_id=? where operation_id=? and phase='RUNNING' and lease_id=? and due_at>?",
                claim.lease, claim.id, claim.lease, clock.millis()) != 1) throw lost();
        return lookup(claim.id);
    }

    Receipt archive(RepositoryContext context, String actor, String id, java.util.function.Consumer<Operation> authorize) {
        return transaction.execute(status -> {
            Operation operation = get(context, actor, id);
            authorize.accept(operation);
            Receipt row = operation.receipt;
            if (row.archivedAt() != null) return row;
            long now = clock.millis();
            int changed = jdbc.update("update history_index_operation set archived_at=? where operation_id=? and completed_at is not null and archived_at is null", now, id);
            if (changed == 0 && lookup(id).receipt.archivedAt() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "History index operation is active");
            if (changed == 1) {
                jdbc.update("delete from history_index_purge_target where operation_id=?", id);
                event(id, actor, "ARCHIVED", now);
            }
            return lookup(id).receipt;
        });
    }

    /** Retain a compact idempotency tombstone and append-only decisions after executable evidence expires. */
    void expire() {
        long now = clock.millis();
        long cutoff = now - Duration.ofDays(30).toMillis();
        long archiveCutoff = now - Duration.ofDays(90).toMillis();
        var selected = bounded().queryForList("""
                select operation_id from history_index_operation
                where (archived_at is null and (completed_at<=? or (phase='AWAITING_AUTHORIZATION' and attempted_at<=?)))
                   or (archived_at<=? and phase<>'EXPIRED') order by operation_id
                """, String.class, cutoff, cutoff, archiveCutoff);
        for (String id : selected) {
            transaction.executeWithoutResult(status -> {
                int expired = jdbc.update("""
                        update history_index_operation set phase='EXPIRED',reason_code='RETENTION_EXPIRED',login_kind='RETIRED',
                        binding_key='retired',captured_commit=null,projection_source_key=null,captured_generation=null,projection_result=null,result_count=null,due_at=null,lease_id=null
                        where operation_id=? and archived_at<=? and phase<>'EXPIRED'
                        """, id, archiveCutoff);
                if (expired == 1) {
                    jdbc.update("delete from history_index_purge_target where operation_id=?", id);
                    event(id, "system:history-index", "RETENTION_EXPIRED", now);
                    return;
                }
                if (jdbc.update("""
                        update history_index_operation set completed_at=?,phase='MANUAL_REVIEW',reason_code='AUTHORIZATION_EXPIRED'
                        where operation_id=? and phase='AWAITING_AUTHORIZATION' and archived_at is null and attempted_at<=?
                        """, now, id, cutoff) == 1) event(id, "system:history-index", "AUTHORIZATION_EXPIRED", now);
                if (jdbc.update("""
                        update history_index_operation set archived_at=? where operation_id=? and archived_at is null
                        and (completed_at<=? or reason_code='AUTHORIZATION_EXPIRED')
                        """, now, id, cutoff) == 1) {
                    jdbc.update("delete from history_index_purge_target where operation_id=?", id);
                    event(id, "system:history-index", "RETENTION_ARCHIVED", now);
                }
            });
        }
    }

    List<String> due() {
        return bounded().queryForList("select operation_id from history_index_operation where phase in ('ADMITTED','RETRYABLE','RUNNING') and due_at<=? order by due_at,operation_id",
                String.class, clock.millis());
    }

    private JdbcTemplate bounded() {
        var query = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        query.setMaxRows(20);
        return query;
    }

    private void event(String id, String actor, String reason, long now) {
        jdbc.update("insert into history_index_decision(decision_id,operation_id,actor_id,decision_code,occurred_at) values(?,?,?,?,?)",
                UUID.randomUUID().toString(), id, actor, reason, now);
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException lost() { return new ResponseStatusException(HttpStatus.CONFLICT, "History index claim changed or expired"); }
}
