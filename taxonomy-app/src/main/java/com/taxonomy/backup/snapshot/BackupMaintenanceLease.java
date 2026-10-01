package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.BackupScope;
import com.taxonomy.backup.BackupWriteBarrier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installation-wide gate shared by every instance. The database clock owns lease
 * expiry. Registration/draining use short transactions; a writer's final fence
 * holds the gate row lock on its own connection until its actual commit.
 */
public final class BackupMaintenanceLease implements BackupWriteBarrier {
    private final DataSource database;
    private final long lifetime;
    private final String gateSql;
    private final String clockSql;
    private final boolean utcWithoutOffset;
    private final ThreadLocal<Writer> currentWriter = new ThreadLocal<>();
    private volatile StartupMaintenance localStartup;

    public BackupMaintenanceLease(DataSource database, Duration lifetime) {
        this.database = Objects.requireNonNull(database);
        this.lifetime = bounded(lifetime);
        try (var connection = database.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            boolean sqlServer = product.contains("microsoft");
            gateSql = "select generation, owner_id, expires_at, phase from backup_barrier_state "
                    + (sqlServer ? "with (updlock, holdlock) where barrier_id='global'"
                    : "where barrier_id='global' for update");
            utcWithoutOffset = product.contains("mysql") || product.contains("mariadb");
            String clock;
            if (product.contains("postgresql")) clock = "clock_timestamp()";
            else if (sqlServer) clock = "sysdatetimeoffset()";
            else if (product.contains("oracle")) clock = "systimestamp";
            else if (utcWithoutOffset) clock = "utc_timestamp(3)";
            else if (product.contains("hsql")) clock = "current_timestamp";
            else throw new IllegalArgumentException("Unsupported backup coordination database");
            clockSql = "select " + clock + " from backup_barrier_state where barrier_id='global'";
        } catch (SQLException failure) { throw new IllegalStateException("Cannot inspect barrier database", failure); }
    }

    public static void initialize(DataSource database) {
        Flyway.configure().dataSource(database).table("backup_schema_history")
                .baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/backup-java-migrations")
                .javaMigrations(new V1__WriterBarrier(), new com.taxonomy.backup.jobs.JdbcBackupJobStore.V2__ExportJobs()).load().migrate();
    }

    public static final class V1__WriterBarrier extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            var connection = context.getConnection();
            update(connection, "create table backup_barrier_state (barrier_id varchar(16) primary key, generation numeric(19,0) not null, owner_id varchar(36), expires_at numeric(19,0) not null, phase smallint not null)");
            update(connection, "insert into backup_barrier_state values ('global', 1, null, 0, 0)");
            update(connection, "create table backup_writer_lease (writer_id varchar(36) primary key, generation numeric(19,0) not null, expires_at numeric(19,0) not null)");
        }
    }

    /** Exposes only the typed transient job store; callers cannot obtain the unfenced coordination datasource. */
    public com.taxonomy.backup.jobs.JdbcBackupJobStore jobs(com.taxonomy.backup.jobs.BackupJobLimits limits) {
        return new com.taxonomy.backup.jobs.JdbcBackupJobStore(database, limits);
    }

    @Override public Section enter(BackupScope scope) {
        Objects.requireNonNull(scope);
        var startup = localStartup;
        if (startup != null) {
            startup.checkValid();
            return new Section() {
                private boolean closed;
                @Override public long generation() { return startup.generation; }
                @Override public void checkValid() { if (closed) throw fenced(); startup.checkValid(); }
                @Override public void close() { closed = true; }
            };
        }
        var existing = currentWriter.get();
        if (existing != null) {
            validate(existing); existing.references++;
            return new WriterSection(existing);
        }
        var writer = transaction(connection -> {
            Gate gate = lock(connection);
            long now = now(connection);
            gate = expireMaintenance(connection, gate, now);
            if (gate.owner != null) throw new IllegalStateException("Backup maintenance is in progress");
            String id = UUID.randomUUID().toString();
            update(connection, "insert into backup_writer_lease values (?, ?, ?)", id, gate.generation, Math.addExact(now, lifetime));
            return new Writer(id, gate.generation);
        });
        currentWriter.set(writer);
        return new WriterSection(writer);
    }

    public Maintenance acquire(BackupScope scope, Duration waitFor) {
        Objects.requireNonNull(scope);
        long waitMillis = bounded(waitFor);
        if (currentWriter.get() != null) throw new IllegalStateException("Cannot capture inside a write section");
        String owner = UUID.randomUUID().toString();
        transaction(connection -> {
            Gate gate = expireMaintenance(connection, lock(connection), now(connection));
            if (gate.owner != null) throw new IllegalStateException("Backup maintenance is already in progress");
            update(connection, "update backup_barrier_state set owner_id=?, expires_at=?, phase=1 where barrier_id='global'",
                    owner, Math.addExact(now(connection), lifetime));
            return null;
        });
        long deadline = System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
        try {
            while (true) {
                Long generation = transaction(connection -> {
                    Gate gate = lock(connection);
                    long now = now(connection);
                    if (!owner.equals(gate.owner) || gate.expires <= now) throw fenced();
                    update(connection, "delete from backup_writer_lease where expires_at<=?", now);
                    long count;
                    try (var query = connection.prepareStatement("select count(*) from backup_writer_lease")) {
                        query.setQueryTimeout(5);
                        try (var rows = query.executeQuery()) { rows.next(); count = rows.getLong(1); }
                    }
                    if (count != 0) {
                        update(connection, "update backup_barrier_state set expires_at=? where barrier_id='global'", Math.addExact(now, lifetime));
                        return null;
                    }
                    long next = Math.addExact(gate.generation, 1);
                    update(connection, "update backup_barrier_state set generation=?, phase=2, expires_at=? where barrier_id='global'",
                            next, Math.addExact(now, lifetime));
                    return next;
                });
                if (generation != null) return new Maintenance(owner, generation);
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Timed out draining backup writers");
                try { Thread.sleep(20); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Backup maintenance interrupted", interrupted); }
            }
        } catch (RuntimeException failure) {
            try { release(owner); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * DDL can implicitly commit on supported databases. A crashed bootstrap must
     * therefore stay blocked until operator recovery has stopped/fenced its process.
     * It must never be made safe merely by expiring a timestamp.
     */
    public StartupMaintenance beginStartup(Duration waitFor) {
        Maintenance acquired = acquire(new BackupScope.Installation(), waitFor);
        try {
            transaction(connection -> {
                Gate gate = lock(connection);
                if (!acquired.owner.equals(gate.owner) || gate.generation != acquired.generation || gate.expires <= now(connection)) throw fenced();
                update(connection, "update backup_barrier_state set phase=3, expires_at=0 where barrier_id='global'");
                return null;
            });
            acquired.closed.set(true); // Ownership is transferred, not released.
            var startup = new StartupMaintenance(acquired.owner, acquired.generation);
            localStartup = startup;
            return startup;
        } catch (RuntimeException failure) { acquired.close(); throw failure; }
    }

    public final class StartupMaintenance {
        private final String owner;
        private final long generation;
        private volatile boolean finished;
        private StartupMaintenance(String owner, long generation) { this.owner = owner; this.generation = generation; }
        public void checkValid() { transaction(connection -> { validate(connection); return null; }); }
        private void validate(Connection connection) throws SQLException {
            Gate gate = lock(connection);
            if (finished || !owner.equals(gate.owner) || gate.generation != generation || gate.phase != 3) throw fenced();
        }
        void successful() {
            transaction(connection -> {
                validate(connection);
                update(connection, "update backup_barrier_state set owner_id=null, phase=0, expires_at=0 where barrier_id='global'");
                return null;
            });
            finished = true;
            localStartup = null;
        }
    }

    void fenceStartupCommit(Connection connection, StartupMaintenance startup) throws SQLException {
        if (connection.getAutoCommit()) throw fenced();
        startup.validate(connection);
    }

    /** Must be followed by commit on this exact connection, with autocommit off. */
    void fenceCommit(Connection connection, Section section) throws SQLException {
        if (!(section instanceof WriterSection writer) || writer.closed || connection.getAutoCommit()) throw fenced();
        validate(connection, writer.writer);
    }

    private void validate(Writer writer) {
        transaction(connection -> { validate(connection, writer); return null; });
    }

    private void validate(Connection connection, Writer writer) throws SQLException {
        Gate gate = lock(connection);
        long now = now(connection);
        if (gate.generation != writer.generation) throw fenced();
        try (var query = connection.prepareStatement("select expires_at from backup_writer_lease where writer_id=? and generation=?")) {
            query.setQueryTimeout(5); query.setString(1, writer.id); query.setLong(2, writer.generation);
            try (var rows = query.executeQuery()) { if (!rows.next() || rows.getLong(1) <= now) throw fenced(); }
        }
    }

    private Gate expireMaintenance(Connection connection, Gate gate, long now) throws SQLException {
        if (gate.owner == null || gate.phase == 3 || gate.expires > now) return gate;
        long next = Math.addExact(gate.generation, 1);
        update(connection, "update backup_barrier_state set generation=?, owner_id=null, expires_at=0, phase=0 where barrier_id='global'", next);
        update(connection, "delete from backup_writer_lease");
        return new Gate(next, null, 0, 0);
    }

    private void release(String owner) {
        transaction(connection -> {
            lock(connection);
            update(connection, "update backup_barrier_state set owner_id=null, expires_at=0, phase=0 where barrier_id='global' and owner_id=?", owner);
            return null;
        });
    }

    public final class Maintenance implements Section {
        private final String owner;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Maintenance(String owner, long generation) { this.owner = owner; this.generation = generation; }
        @Override public long generation() { return generation; }
        @Override public void checkValid() { check(false); }
        public void renew() { check(true); }
        private void check(boolean renew) {
            if (closed.get()) throw fenced();
            transaction(connection -> {
                Gate gate = lock(connection);
                long now = now(connection);
                if (!owner.equals(gate.owner) || gate.generation != generation || gate.phase != 2 || gate.expires <= now) throw fenced();
                if (renew) update(connection, "update backup_barrier_state set expires_at=? where barrier_id='global'", Math.addExact(now, lifetime));
                return null;
            });
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) release(owner); }
    }

    private final class WriterSection implements Section {
        private final Writer writer;
        private boolean closed;
        private WriterSection(Writer writer) { this.writer = writer; }
        @Override public long generation() { return writer.generation; }
        @Override public void checkValid() { if (closed) throw fenced(); validate(writer); }
        @Override public void close() {
            if (closed) return;
            if (currentWriter.get() != writer) throw new IllegalStateException("Write section belongs to another thread");
            closed = true;
            if (--writer.references > 0) return;
            currentWriter.remove();
            transaction(connection -> { lock(connection); update(connection, "delete from backup_writer_lease where writer_id=?", writer.id); return null; });
        }
    }

    private static final class Writer {
        final String id;
        final long generation;
        int references = 1;
        Writer(String id, long generation) { this.id = id; this.generation = generation; }
    }
    private record Gate(long generation, String owner, long expires, int phase) { }

    private Gate lock(Connection connection) throws SQLException {
        // SELECT FOR UPDATE alone does not acquire an exclusive row lock in HSQLDB MVCC.
        // Keep this write in the caller's transaction, including the final business commit fence.
        update(connection, "update backup_barrier_state set generation=generation where barrier_id='global'");
        try (var query = connection.prepareStatement(gateSql)) {
            query.setQueryTimeout(5);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Backup gate is missing");
                return new Gate(row.getLong(1), row.getString(2), row.getLong(3), row.getInt(4));
            }
        }
    }

    private long now(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(clockSql)) {
            query.setQueryTimeout(5);
            try (var row = query.executeQuery()) {
                row.next();
                // Preserve the database offset, independently of each instance's JVM zone.
                return (utcWithoutOffset
                        ? row.getObject(1, java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC)
                        : row.getObject(1, java.time.OffsetDateTime.class).toInstant()).toEpochMilli();
            }
        }
    }

    private static long bounded(Duration duration) {
        long millis = Objects.requireNonNull(duration).toMillis();
        if (millis < 1 || millis > Duration.ofMinutes(30).toMillis()) throw new IllegalArgumentException("Barrier duration must be within 1 ms and 30 minutes");
        return millis;
    }

    private <T> T transaction(SqlAction<T> action) {
        try (var connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection); connection.commit(); return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Backup barrier database operation failed", failure); }
    }
    @FunctionalInterface private interface SqlAction<T> { T run(Connection connection) throws SQLException; }
    private static IllegalStateException fenced() { return new IllegalStateException("Backup operation was fenced; restart from current state"); }
    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
