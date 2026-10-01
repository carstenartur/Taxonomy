package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.BackupScope;
import com.taxonomy.backup.BackupWriteBarrier;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Locale;
import java.util.Objects;

/**
 * Covers ORM and independent native/JGit transactions at the common JDBC boundary.
 * Autocommit mutations become a short explicit transaction so the generation check
 * and commit cannot race maintenance acquisition. Runtime DDL and SQL transaction
 * commands are rejected; schema migration is a separate startup maintenance step.
 */
public final class GuardedBackupDataSource extends AbstractDataSource implements AutoCloseable {
    private final DataSource database;
    private final BackupMaintenanceLease barrier;
    private volatile BackupMaintenanceLease.StartupMaintenance startup;

    public GuardedBackupDataSource(DataSource database, BackupMaintenanceLease barrier) {
        this(database, barrier, null);
    }

    public GuardedBackupDataSource(DataSource database, BackupMaintenanceLease barrier,
                                  BackupMaintenanceLease.StartupMaintenance startup) {
        this.database = Objects.requireNonNull(database); this.barrier = Objects.requireNonNull(barrier);
        this.startup = startup;
    }

    public void finishStartup() {
        var held = startup;
        if (held == null) return;
        held.successful(); startup = null;
    }

    BackupMaintenanceLease coordinator() { return barrier; }

    org.springframework.boot.jdbc.metadata.DataSourcePoolMetadata poolMetadata() {
        return database instanceof com.zaxxer.hikari.HikariDataSource pool
                ? new org.springframework.boot.jdbc.metadata.HikariDataSourcePoolMetadata(pool) : null;
    }

    @Override public void close() throws Exception {
        // Failed initialization deliberately does not release its non-expiring DDL hold.
        if (database instanceof AutoCloseable closeable) closeable.close();
    }

    @Override public Connection getConnection() throws SQLException { return guard(database.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException { return guard(database.getConnection(username, password)); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return type.cast(this);
        throw new SQLException("Unwrapping the backup persistence boundary is not supported");
    }
    @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }

    private Connection guard(Connection raw) {
        var handler = new GuardedConnection(raw);
        handler.proxy = proxy(Connection.class, handler);
        return handler.proxy;
    }

    private final class GuardedConnection implements InvocationHandler {
        private final Connection raw;
        private Connection proxy;
        private BackupWriteBarrier.Section section;
        private final BackupMaintenanceLease.StartupMaintenance bootstrap;
        private boolean bootstrapWrites;
        GuardedConnection(Connection raw) { this.raw = raw; this.bootstrap = startup; }

        @Override public Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("unwrap")) return unwrapProxy(receiver, (Class<?>) args[0]);
            if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(receiver);
            if (name.equals("toString")) return "BackupGuardedConnection";
            if (name.equals("commit") || (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]) && !raw.getAutoCommit())) {
                try {
                    if (section != null) barrier.fenceCommit(raw, section);
                    if (bootstrapWrites) barrier.fenceStartupCommit(raw, bootstrap);
                    return call(raw, method, args);
                } catch (Throwable failure) {
                    try { raw.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw sqlFailure(failure);
                } finally { release(); }
            }
            if (name.equals("rollback") && (args == null || args.length == 0)) {
                try { return call(raw, method, args); } finally { release(); }
            }
            if (name.equals("close") || name.equals("abort")) {
                // Return the application connection before lease cleanup borrows another.
                try { return call(raw, method, args); } finally { release(); }
            }
            Object result = call(raw, method, args);
            if (result instanceof Statement statement) {
                String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                return guardStatement(statement, sql);
            }
            if (result instanceof DatabaseMetaData metadata) {
                return proxy(DatabaseMetaData.class, (metadataProxy, operation, values) -> {
                    if (operation.getName().equals("getConnection")) return proxy;
                    if (operation.getName().equals("unwrap")) return unwrapProxy(metadataProxy, (Class<?>) values[0]);
                    if (operation.getName().equals("isWrapperFor")) return ((Class<?>) values[0]).isInstance(metadataProxy);
                    Object value = call(metadata, operation, values);
                    return value instanceof ResultSet rows
                            ? guardRows(rows, guardStatement(rows.getStatement(), null)) : value;
                });
            }
            return result;
        }

        private Statement guardStatement(Statement statement, String sql) {
            if (statement == null) return null;
            Class<? extends Statement> type = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return proxy(type, (statementProxy, operation, values) -> statement(statement, statementProxy, sql, operation, values));
        }

        private Object statement(Statement rawStatement, Object statementProxy, String preparedSql, Method operation, Object[] args) throws Throwable {
            String name = operation.getName();
            if (name.equals("getConnection")) return proxy;
            if (name.equals("unwrap")) return unwrapProxy(statementProxy, (Class<?>) args[0]);
            if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(statementProxy);
            if (name.equals("toString")) return "BackupGuardedStatement";
            String sql = preparedSql != null ? preparedSql
                    : args != null && args.length > 0 && args[0] instanceof String text ? text : null;
            if (name.equals("addBatch") && bootstrap == null) requireRuntimeSql(sql);
            boolean execute = name.startsWith("execute");
            boolean mutating = execute && (name.contains("Batch") || !readOnly(sql));
            if (execute && bootstrap == null) requireRuntimeSql(sql);
            Object result = mutating ? mutation(() -> call(rawStatement, operation, args)) : call(rawStatement, operation, args);
            if (result instanceof ResultSet rows) return guardRows(rows, statementProxy);
            return result;
        }

        private ResultSet guardRows(ResultSet rows, Object statement) {
            return proxy(ResultSet.class, (rowsProxy, operation, args) -> {
                String name = operation.getName();
                if (name.equals("getStatement")) return statement;
                if (name.equals("unwrap")) return unwrapProxy(rowsProxy, (Class<?>) args[0]);
                if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(rowsProxy);
                if (name.startsWith("update") || name.equals("insertRow") || name.equals("deleteRow"))
                    throw new SQLException("Use a guarded prepared statement for database writes");
                return guardLocator(call(rows, operation, args));
            });
        }

        private Object guardLocator(Object value) {
            Class<?> type = value instanceof NClob ? NClob.class : value instanceof Clob ? Clob.class
                    : value instanceof Blob ? Blob.class : value instanceof SQLXML ? SQLXML.class
                    : value instanceof java.sql.Array ? java.sql.Array.class : null;
            if (type == null) return value;
            return proxy(type, (locator, operation, args) -> {
                String name = operation.getName();
                if (name.startsWith("set") || name.equals("truncate"))
                    throw new SQLException("Use a guarded prepared statement for database writes");
                Object result = call(value, operation, args);
                return result instanceof ResultSet rows
                        ? guardRows(rows, guardStatement(rows.getStatement(), null)) : result;
            });
        }

        private Object mutation(Execution execution) throws Throwable {
            boolean auto = raw.getAutoCommit();
            try {
                if (bootstrap != null) {
                    bootstrap.checkValid(); bootstrapWrites = true;
                } else {
                    if (section == null) section = barrier.enter(new BackupScope.Installation());
                    section.checkValid();
                }
                if (auto) raw.setAutoCommit(false);
                Object result = execution.run();
                if (auto) {
                    if (bootstrap != null) barrier.fenceStartupCommit(raw, bootstrap);
                    else barrier.fenceCommit(raw, section);
                    raw.commit();
                }
                return result;
            } catch (Throwable failure) {
                try { raw.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                release();
                throw sqlFailure(failure);
            } finally {
                if (auto) { try { raw.setAutoCommit(true); } finally { release(); } }
            }
        }

        private void release() throws SQLException {
            bootstrapWrites = false;
            if (section == null) return;
            var previous = section; section = null;
            try { previous.close(); }
            catch (RuntimeException failure) { throw new SQLException("Cannot release backup writer lease", failure); }
        }
    }

    private static boolean readOnly(String sql) {
        String command = command(sql);
        return command.startsWith("SELECT ") || command.startsWith("VALUES ") || command.startsWith("VALUES(");
    }

    private static void requireRuntimeSql(String sql) throws SQLException {
        if (sql == null) return; // Prepared batch: each added command has already been checked.
        String command = command(sql);
        if (!command.matches("(?s)^(SELECT|VALUES|INSERT|UPDATE|DELETE|MERGE|WITH)\\b.*")
                || command.contains(";") || command.matches("(?s)^SELECT\\b.*\\bINTO\\b.*"))
            throw new SQLException("Native procedures, schema and SQL transaction commands require startup maintenance");
    }

    private static String command(String sql) {
        if (sql == null) return "";
        String command = sql.stripLeading();
        while (true) {
            if (command.startsWith("/*")) {
                int end = command.indexOf("*/", 2);
                if (end < 0) return "";
                command = command.substring(end + 2).stripLeading();
            } else if (command.startsWith("--")) {
                int end = command.indexOf('\n');
                if (end < 0) return "";
                command = command.substring(end + 1).stripLeading();
            } else return command.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }

    private static Object unwrapProxy(Object proxy, Class<?> type) throws SQLException {
        if (type.isInstance(proxy)) return proxy;
        throw new SQLException("Unwrapping the backup persistence boundary is not supported");
    }
    private static Throwable sqlFailure(Throwable failure) {
        return failure instanceof RuntimeException ? new SQLException(failure.getMessage(), "55000", failure) : failure;
    }
    private static Object call(Object receiver, Method method, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(GuardedBackupDataSource.class.getClassLoader(), new Class<?>[]{type},
                (receiver, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> receiver == args[0];
                            case "hashCode" -> System.identityHashCode(receiver);
                            // Driver strings may contain SQL, credentials or LOB contents.
                            case "toString" -> "BackupGuarded" + type.getSimpleName();
                            default -> throw new IllegalStateException("Unknown Object method");
                        };
                    }
                    return handler.invoke(receiver, method, args);
                }));
    }
    @FunctionalInterface private interface Execution { Object run() throws Throwable; }
}
