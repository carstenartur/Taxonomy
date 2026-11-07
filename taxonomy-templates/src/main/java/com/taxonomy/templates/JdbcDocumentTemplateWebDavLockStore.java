package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateWebDavLockManager.LockStoreUnavailableException;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.TemplateLock;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/**
 * Transient WebDAV leases shared by the configured installation database.
 * A short, installation-wide database fence also covers the existing bounded Git
 * upload, so an expired lease cannot admit a second writer during that upload.
 * Template content and its history remain exclusively in the existing Git owner.
 */
final class JdbcDocumentTemplateWebDavLockStore implements DocumentTemplateWebDavLockStore {
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private final DataSource database;
    private final String clockSql;
    private final boolean utcWithoutOffset;

    JdbcDocumentTemplateWebDavLockStore(DataSource database) {
        this.database = Objects.requireNonNull(database, "database");
        Flyway.configure().dataSource(database).table("webdav_lock_schema_history")
                .locations("classpath:db/webdav-lock-java-migrations")
                .baselineOnMigrate(true).baselineVersion("0")
                .javaMigrations(new V1__SharedWebDavLocks()).load().migrate();
        try (var connection = database.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            utcWithoutOffset = product.contains("mysql") || product.contains("mariadb");
            String clock;
            if (product.contains("postgresql")) clock = "clock_timestamp()";
            else if (product.contains("microsoft")) clock = "sysdatetimeoffset()";
            else if (product.contains("oracle")) clock = "systimestamp";
            else if (utcWithoutOffset) clock = "utc_timestamp(3)";
            else if (product.contains("hsql")) clock = "current_timestamp";
            else throw new IllegalArgumentException("Unsupported WebDAV coordination database");
            clockSql = "select " + clock + " from template_webdav_gate where gate_id=1";
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public <T> T locked(String resource, Operation<T> operation) throws IOException {
        try (var connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                // A write fence also serializes absent-row acquisition in HSQLDB MVCC.
                try (var gate = connection.prepareStatement(
                        "update template_webdav_gate set gate_id=gate_id where gate_id=1")) {
                    gate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    if (gate.executeUpdate() != 1) throw new SQLException("Missing WebDAV coordination gate");
                }
                Instant now = now(connection);
                try (var expired = connection.prepareStatement(
                        "delete from template_webdav_lock where expires_at<=?")) {
                    expired.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    expired.setLong(1, now.toEpochMilli());
                    expired.executeUpdate();
                }
                var state = new State(connection, resource, read(connection, resource));
                T result = operation.execute(state);
                state.flush();
                connection.commit();
                return result;
            } catch (IOException | SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    private TemplateLock read(Connection connection, String resource) throws SQLException {
        try (var query = connection.prepareStatement(
                "select lock_token, owner_id, base_commit, current_commit, expires_at "
                        + "from template_webdav_lock where resource_id=?")) {
            query.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            query.setString(1, resource);
            try (var row = query.executeQuery()) {
                return row.next() ? new TemplateLock(resource, row.getString(1), row.getString(2),
                        row.getString(3), row.getString(4), Instant.ofEpochMilli(row.getLong(5))) : null;
            }
        }
    }

    private Instant now(Connection connection) {
        try (var query = connection.prepareStatement(clockSql)) {
            query.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing WebDAV coordination clock");
                Instant timestamp = (utcWithoutOffset
                        ? row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC)
                        : row.getObject(1, OffsetDateTime.class).toInstant());
                return Instant.ofEpochMilli(timestamp.toEpochMilli());
            }
        } catch (SQLException failure) {
            throw unavailable(failure);
        }
    }

    private final class State implements Lease {
        private final Connection connection;
        private final String resource;
        private TemplateLock current;
        private boolean changed;

        private State(Connection connection, String resource, TemplateLock current) {
            this.connection = connection;
            this.resource = resource;
            this.current = current;
        }

        @Override public TemplateLock current() { return current; }
        @Override public Instant now() { return JdbcDocumentTemplateWebDavLockStore.this.now(connection); }
        @Override public void save(TemplateLock lock) {
            if (!resource.equals(lock.resource())) throw new IllegalArgumentException("Foreign WebDAV lease resource");
            current = lock;
            changed = true;
        }
        @Override public void remove() { current = null; changed = true; }

        private void flush() throws SQLException {
            if (!changed) return;
            try (var delete = connection.prepareStatement("delete from template_webdav_lock where resource_id=?")) {
                delete.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                delete.setString(1, resource);
                delete.executeUpdate();
            }
            if (current == null) return;
            try (var insert = connection.prepareStatement(
                    "insert into template_webdav_lock "
                            + "(resource_id,lock_token,owner_id,base_commit,current_commit,expires_at) values (?,?,?,?,?,?)")) {
                insert.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                insert.setString(1, resource);
                insert.setString(2, current.token());
                insert.setString(3, current.owner());
                insert.setString(4, current.baseCommit());
                insert.setString(5, current.currentCommit());
                insert.setLong(6, current.expiresAt().toEpochMilli());
                insert.executeUpdate();
            }
        }
    }

    private static LockStoreUnavailableException unavailable(SQLException failure) {
        return new LockStoreUnavailableException("Shared WebDAV edit authority is unavailable", failure);
    }

    public static final class V1__SharedWebDavLocks extends BaseJavaMigration {
        @Override public void migrate(Context context) throws SQLException {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute("create table template_webdav_gate (gate_id smallint primary key)");
                statement.execute("insert into template_webdav_gate values (1)");
                statement.execute("create table template_webdav_lock (resource_id varchar(128) primary key, "
                        + "lock_token varchar(64) not null, owner_id varchar(255) not null, "
                        + "base_commit varchar(40), current_commit varchar(40), expires_at numeric(19,0) not null)");
                statement.execute("create index idx_template_webdav_expiry on template_webdav_lock(expires_at)");
            }
        }
    }
}
