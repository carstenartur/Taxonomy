package com.taxonomy.backup.jobs;

import com.taxonomy.backup.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.access.AccessDeniedException;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Short queue transactions use the private coordination pool, including while capture pauses business writers. */
public final class JdbcBackupJobStore {
    private static final String RUNNING = "('CAPTURING','WRITING','VERIFYING')";
    private final DataSource database;
    private final BackupJobLimits limits;
    private final BackupManifestCodec codec = new BackupManifestCodec();
    private final String lockSql, clockSql;

    public JdbcBackupJobStore(DataSource database, BackupJobLimits limits) {
        this.database = Objects.requireNonNull(database); this.limits = Objects.requireNonNull(limits);
        try (var connection = database.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            boolean sqlServer = product.contains("microsoft");
            lockSql = "select next_sequence from backup_job_queue " + (sqlServer
                    ? "with (updlock, holdlock) where queue_id='exports'" : "where queue_id='exports' for update");
            String clock = product.contains("postgres") ? "clock_timestamp()" : sqlServer ? "sysdatetimeoffset()"
                    : product.contains("oracle") ? "systimestamp" : product.contains("hsql") ? "current_timestamp" : null;
            if (clock == null) throw new IllegalArgumentException("Unsupported backup job database");
            clockSql = "select " + clock + " from backup_job_queue where queue_id='exports'";
        } catch (SQLException failure) { throw databaseFailure(failure); }
    }

    public static final class V2__ExportJobs extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            var c = context.getConnection(); String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            String text = product.contains("postgres") ? "text" : product.contains("microsoft") ? "nvarchar(max)" : "clob";
            update(c, "create table backup_job_queue (queue_id varchar(16) primary key, next_sequence numeric(19,0) not null)");
            update(c, "insert into backup_job_queue values ('exports', 0)");
            update(c, "create table backup_job (job_id varchar(36) primary key, principal_id varchar(36) not null, authorization_json " + text
                    + " not null, job_state varchar(16) not null, heavy smallint not null, enqueue_order numeric(19,0) not null unique,"
                    + " created_at numeric(19,0) not null, updated_at numeric(19,0) not null, owner_id varchar(36), attempt integer not null,"
                    + " lease_until numeric(19,0) not null, progress_bytes numeric(19,0) not null, cancellation_requested smallint not null,"
                    + " artifact_name varchar(128), artifact_bytes numeric(19,0), artifact_sha256 varchar(64), encrypted smallint,"
                    + " expires_at numeric(19,0) not null, failure_code varchar(32) not null, storage_pending smallint default 0 not null)");
            update(c, "create index ix_backup_job_queue on backup_job(job_state, heavy, enqueue_order)");
            update(c, "create index ix_backup_job_principal on backup_job(principal_id, job_state)");
        }
    }

    public BackupJobId enqueue(AuthorizedBackupRequest authorization) {
        byte[] document = codec.writeAuthorization(authorization);
        return transaction(c -> {
            long sequence = lock(c), now = now(c); recover(c, now);
            if (scalar(c, "select count(*) from backup_job where principal_id=? and (job_state='QUEUED' or job_state in " + RUNNING + ")",
                    authorization.principalId().value().toString()) != 0
                    || scalar(c, "select count(*) from backup_job where job_state='QUEUED'") >= limits.queueCapacity()
                    || scalar(c, "select count(*) from backup_job") >= BackupLimits.MAX_ITEMS) throw new BackupCapacityException();
            var id = BackupJobId.create(); var profile = authorization.request().profile();
            update(c, "update backup_job_queue set next_sequence=? where queue_id='exports'", Math.addExact(sequence, 1));
            try (var statement = c.prepareStatement("insert into backup_job (job_id, principal_id, authorization_json, job_state, heavy, enqueue_order,"
                    + " created_at, updated_at, owner_id, attempt, lease_until, progress_bytes, cancellation_requested, expires_at, failure_code)"
                    + " values (?, ?, ?, 'QUEUED', ?, ?, ?, ?, null, 0, 0, 0, 0, ?, 'NONE')")) {
                statement.setQueryTimeout(5); statement.setString(1, id.value().toString()); statement.setString(2, authorization.principalId().value().toString());
                String json = new String(document, StandardCharsets.UTF_8);
                statement.setCharacterStream(3, new StringReader(json), json.length()); statement.setInt(4, profile.includesHistory() || profile.isInstallation() ? 1 : 0);
                statement.setLong(5, sequence + 1); statement.setLong(6, now); statement.setLong(7, now);
                statement.setLong(8, Math.addExact(now, limits.retention().toMillis())); statement.executeUpdate();
            }
            return id;
        });
    }

    public Optional<BackupJobClaim> claim(UUID owner) {
        Objects.requireNonNull(owner);
        return transaction(c -> {
            lock(c); long now = now(c); recover(c, now);
            if (scalar(c, "select count(*) from backup_job where storage_pending=1")
                    >= limits.maxTemporaryBytes() / BackupJobLimits.TEMPORARY_BYTES_PER_JOB) return Optional.empty();
            boolean heavy = scalar(c, "select count(*) from backup_job where job_state in " + RUNNING + " and heavy=1") < limits.heavyConcurrency();
            boolean current = scalar(c, "select count(*) from backup_job where job_state in " + RUNNING + " and heavy=0") < limits.currentConcurrency();
            if (!heavy && !current) return Optional.empty();
            BackupJobId id;
            try (var query = c.prepareStatement("select job_id from backup_job where job_state='QUEUED' and expires_at>? and ((heavy=1 and ?=1) or (heavy=0 and ?=1)) order by enqueue_order")) {
                query.setQueryTimeout(5); query.setMaxRows(1); query.setLong(1, now); query.setInt(2, heavy ? 1 : 0); query.setInt(3, current ? 1 : 0);
                try (var rows = query.executeQuery()) { if (!rows.next()) return Optional.empty(); id = new BackupJobId(UUID.fromString(rows.getString(1))); }
            }
            update(c, "update backup_job set job_state='CAPTURING', owner_id=?, attempt=attempt+1, lease_until=?, updated_at=?, progress_bytes=0, storage_pending=1 where job_id=?",
                    owner.toString(), Math.addExact(now, limits.lease().toMillis()), now, id.value().toString());
            var job = require(c, id); return Optional.of(new BackupJobClaim(job, owner, job.attempt()));
        });
    }

    public void heartbeat(BackupJobClaim claim, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative job progress");
        transaction(c -> {
            lock(c); long now = now(c); var job = owned(c, claim, now, false);
            update(c, "update backup_job set lease_until=?, updated_at=?, progress_bytes=? where job_id=?",
                    Math.addExact(now, limits.lease().toMillis()), now, Math.max(job.progressBytes(), bytes), job.id().value().toString()); return null;
        });
    }

    public void transition(BackupJobClaim claim, BackupJobState next) {
        transaction(c -> {
            lock(c); long now = now(c); var job = owned(c, claim, now, false);
            if (job.state() == next) return null;
            if (!(job.state() == BackupJobState.CAPTURING && next == BackupJobState.WRITING
                    || job.state() == BackupJobState.WRITING && next == BackupJobState.VERIFYING)) throw new IllegalStateException("Invalid backup job transition");
            update(c, "update backup_job set job_state=?, updated_at=?, progress_bytes=0 where job_id=?", next.name(), now, job.id().value().toString()); return null;
        });
    }

    public void ready(BackupJobClaim claim, BackupArtifact artifact) {
        transaction(c -> {
            lock(c); long now = now(c); var job = owned(c, claim, now, false);
            if (job.state() != BackupJobState.VERIFYING || !artifact.name().equals(claim.artifactName())
                    || job.authorization().request().secrets() == SecretsSelection.INCLUDE_ENCRYPTED && !artifact.encrypted())
                throw new IllegalStateException("Unverified backup result");
            long retained = scalar(c, "select coalesce(sum(artifact_bytes), 0) from backup_job where artifact_name is not null");
            if (artifact.length() > limits.maxRetainedBytes() - retained) throw new BackupCapacityException();
            update(c, "update backup_job set job_state='READY', lease_until=0, updated_at=?, artifact_name=?, artifact_bytes=?, artifact_sha256=?, encrypted=?, expires_at=?, failure_code='NONE', storage_pending=0 where job_id=?",
                    now, artifact.name(), artifact.length(), artifact.sha256(), artifact.encrypted() ? 1 : 0,
                    Math.addExact(now, limits.retention().toMillis()), job.id().value().toString()); return null;
        });
    }

    public boolean cancel(BackupJobId id, PrincipalId actor) {
        return transaction(c -> {
            lock(c); var job = require(c, id);
            if (!job.authorization().principalId().equals(actor)) throw new AccessDeniedException("Backup access denied");
            if (!job.state().active()) return false;
            update(c, "update backup_job set cancellation_requested=1, job_state=?, updated_at=? where job_id=?",
                    job.state() == BackupJobState.QUEUED ? "CANCELLED" : job.state().name(), now(c), id.value().toString()); return true;
        });
    }

    public void fail(BackupJobClaim claim, BackupJobFailure failure) {
        Objects.requireNonNull(failure);
        if (failure == BackupJobFailure.NONE) throw new IllegalArgumentException("Failure code required");
        transaction(c -> {
            lock(c); long now = now(c); var job = owned(c, claim, now, true);
            update(c, "update backup_job set job_state=?, failure_code=?, lease_until=0, updated_at=?, expires_at=? where job_id=?",
                    job.cancellationRequested() ? "CANCELLED" : "FAILED", job.cancellationRequested() ? "NONE" : failure.name(),
                    now, Math.addExact(now, limits.retention().toMillis()), job.id().value().toString()); return null;
        });
    }

    public List<BackupJob> recoverExpired() { return transaction(c -> { lock(c); return recover(c, now(c)); }); }
    private List<BackupJob> recover(Connection c, long now) throws SQLException {
        List<BackupJob> lost = select(c, "select * from backup_job where (job_state in " + RUNNING
                + " and lease_until<=?) or (job_state='QUEUED' and expires_at<=?)", now, now);
        for (var job : lost) update(c, "update backup_job set job_state=?, failure_code=?, lease_until=0, updated_at=?, expires_at=? where job_id=?",
                job.cancellationRequested() ? "CANCELLED" : "FAILED", job.cancellationRequested() ? "NONE"
                        : job.state() == BackupJobState.QUEUED ? "QUEUE_EXPIRED" : "WORKER_EXPIRED", now,
                Math.addExact(now, limits.retention().toMillis()), job.id().value().toString());
        return lost.stream().map(job -> require(c, job.id())).toList();
    }

    public Optional<BackupJob> find(BackupJobId id) {
        return transaction(c -> select(c, "select * from backup_job where job_id=?", id.value().toString()).stream().findFirst());
    }
    public List<BackupJob> list(PrincipalId actor) {
        return transaction(c -> select(c, "select * from backup_job where principal_id=? order by enqueue_order desc", actor.value().toString()));
    }
    public List<BackupJob> expiredArtifacts() {
        return transaction(c -> select(c, "select * from backup_job where job_state in ('READY','FAILED','CANCELLED') and expires_at<=?", now(c)));
    }
    public void removeExpired(BackupJobId id) {
        transaction(c -> {
            lock(c); var job = require(c, id);
            if (job.state().active() || job.expiresAt().toEpochMilli() > now(c)) throw new IllegalStateException("Job retention is still active");
            update(c, "delete from backup_job where job_id=?", id.value().toString()); return null;
        });
    }
    public BackupJobLimits limits() { return limits; }

    public boolean retained(BackupJobId id) {
        return transaction(c -> scalar(c, "select count(*) from backup_job where job_id=? and job_state='READY' and expires_at>?",
                id.value().toString(), now(c)) == 1);
    }

    /** Lightweight cleanup records never materialize authorization documents for the full retention window. */
    public record Cleanup(BackupJobId id, UUID owner, int attempt, boolean expired) {
        public String directoryName() { return owner == null ? null : id.value() + "-" + owner + "-" + attempt + ".taxbackup"; }
    }
    public List<Cleanup> cleanupCandidates() {
        return transaction(c -> {
            long now = now(c); var result = new ArrayList<Cleanup>();
            try (var query = c.prepareStatement("select job_id, owner_id, attempt, expires_at from backup_job where "
                    + "(storage_pending=1 and job_state in ('FAILED','CANCELLED')) or (job_state in ('READY','FAILED','CANCELLED') and expires_at<=?)")) {
                bind(query, new Object[]{now}); query.setMaxRows(100);
                try (var rows = query.executeQuery()) { while (rows.next()) {
                    String owner = rows.getString(2);
                    result.add(new Cleanup(new BackupJobId(UUID.fromString(rows.getString(1))), owner == null ? null : UUID.fromString(owner),
                            rows.getInt(3), rows.getLong(4) <= now));
                } }
            }
            return List.copyOf(result);
        });
    }
    /** Release a failed worker's reservation only after its private directory has actually been removed. */
    public void storageCleaned(Cleanup cleaned) {
        transaction(c -> {
            lock(c); var job = require(c, cleaned.id());
            if (job.state().active() || !Objects.equals(job.owner(), cleaned.owner()) || job.attempt() != cleaned.attempt())
                throw new BackupJobFencedException();
            update(c, "update backup_job set storage_pending=0 where job_id=?", job.id().value().toString()); return null;
        });
    }

    private BackupJob owned(Connection c, BackupJobClaim claim, long now, boolean allowCancellation) {
        var job = require(c, claim.job().id());
        if (!job.state().running() || !claim.owner().equals(job.owner()) || claim.attempt() != job.attempt() || job.leaseUntil().toEpochMilli() <= now)
            throw new BackupJobFencedException();
        if (!allowCancellation && job.cancellationRequested()) throw new BackupJobCancelledException();
        return job;
    }
    private BackupJob require(Connection c, BackupJobId id) {
        try { return select(c, "select * from backup_job where job_id=?", id.value().toString()).stream().findFirst().orElseThrow(BackupJobFencedException::new); }
        catch (SQLException failure) { throw databaseFailure(failure); }
    }
    private List<BackupJob> select(Connection c, String sql, Object... parameters) throws SQLException {
        try (var query = c.prepareStatement(sql)) {
            bind(query, parameters); query.setMaxRows(100); var results = new ArrayList<BackupJob>();
            try (var rows = query.executeQuery()) { while (rows.next()) results.add(row(rows)); } return List.copyOf(results);
        }
    }
    private BackupJob row(ResultSet row) throws SQLException {
        String json;
        try (Reader input = row.getCharacterStream("authorization_json")) {
            var text = new StringBuilder(); char[] buffer = new char[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (count > BackupManifestCodec.MAX_AUTHORIZATION_BYTES - text.length()) throw new IOException("Oversized authorization record");
                text.append(buffer, 0, count);
            }
            json = text.toString();
        } catch (IOException failure) { throw new SQLException("Invalid backup job authorization record"); }
        var authorization = codec.readAuthorization(json.getBytes(StandardCharsets.UTF_8));
        if (!authorization.principalId().value().toString().equals(row.getString("principal_id"))) throw new SQLException("Conflicting job owner");
        String name = row.getString("artifact_name"), owner = row.getString("owner_id");
        BackupArtifact artifact = name == null ? null : new BackupArtifact(name, row.getLong("artifact_bytes"), row.getString("artifact_sha256"), row.getInt("encrypted") == 1);
        return new BackupJob(new BackupJobId(UUID.fromString(row.getString("job_id"))), authorization,
                BackupJobState.valueOf(row.getString("job_state")), Instant.ofEpochMilli(row.getLong("created_at")), Instant.ofEpochMilli(row.getLong("updated_at")),
                owner == null ? null : UUID.fromString(owner), row.getInt("attempt"), Instant.ofEpochMilli(row.getLong("lease_until")), row.getLong("progress_bytes"),
                row.getInt("cancellation_requested") == 1, artifact, Instant.ofEpochMilli(row.getLong("expires_at")), BackupJobFailure.valueOf(row.getString("failure_code")));
    }
    private long lock(Connection c) throws SQLException {
        // HSQLDB MVCC does not lock on SELECT FOR UPDATE. An actual update owns
        // the row until commit on every supported engine, even when its value is unchanged.
        update(c, "update backup_job_queue set next_sequence=next_sequence where queue_id='exports'");
        return scalar(c, lockSql);
    }
    private long now(Connection c) throws SQLException {
        try (var statement = c.prepareStatement(clockSql)) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("Missing job queue"); return rows.getObject(1, OffsetDateTime.class).toInstant().toEpochMilli(); }
        }
    }
    private static long scalar(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            bind(statement, values); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("Missing queue row"); return rows.getLong(1); }
        }
    }
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = c.prepareStatement(sql)) { bind(statement, values); statement.executeUpdate(); }
    }
    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        statement.setQueryTimeout(5); for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }
    private <T> T transaction(SqlWork<T> action) {
        try (var connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try { T value = action.run(connection); connection.commit(); return value; }
            catch (SQLException | RuntimeException failure) { try { connection.rollback(); } catch (SQLException ignored) { } throw failure; }
        } catch (SQLException failure) { throw databaseFailure(failure); }
    }
    private static IllegalStateException databaseFailure(SQLException cause) { return new IllegalStateException("Backup job storage unavailable"); }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection c) throws SQLException; }
}
