package com.taxonomy.setup;

import io.swagger.v3.oas.annotations.media.Schema;
import org.postgresql.util.PasswordUtil;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;

/** Explicit PostgreSQL bootstrap. Never changes existing objects or the application's datasource. */
@Service
public class PostgresProvisioningService {
    private static final Semaphore OPERATIONS = new Semaphore(2);
    private static final long LOCK_KEY = 0x5441584f4e4f4d59L;
    private static final Set<String> RESERVED = Set.of("postgres", "template0", "template1", "public");
    private final Connector connector;

    public PostgresProvisioningService() { this(DriverManager::getConnection); }
    PostgresProvisioningService(Connector connector) { this.connector = connector; }

    @FunctionalInterface
    interface Connector { Connection connect(String url, Properties properties) throws SQLException; }
    public enum Outcome { READY, COMPLETE, CONFLICT, FAILED, PARTIAL, UNKNOWN, BUSY }
    public enum ResourceState { CREATE, REUSE, CREATED, REUSED, NOT_CREATED, UNKNOWN }
    @Schema(requiredProperties = {"target", "maintenanceDatabase", "adminUsername", "adminPassword"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ProvisionSettings(DatabaseSetupService.ConnectionSettings target, String maintenanceDatabase,
                                    String adminUsername,
                                    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String adminPassword,
                                    boolean confirmed,
                                    @Schema(allowableValues = {"CREATE", "REUSE"}) ResourceState expectedRole,
                                    @Schema(allowableValues = {"CREATE", "REUSE"}) ResourceState expectedDatabase) {
        @Override public String toString() { return "PostgreSQL preparation settings [redacted]"; }
    }
    public record ProvisionResult(Outcome outcome, ResourceState role, ResourceState database, SetupChecks.Finding finding) { }

    public ProvisionResult inspect(ProvisionSettings input) { validate(input); return bounded(input, false); }
    public ProvisionResult provision(ProvisionSettings input) {
        validate(input);
        if (!input.confirmed()) return result(Outcome.FAILED, ResourceState.NOT_CREATED, ResourceState.NOT_CREATED,
                "confirmation", "Review the inspection and explicitly confirm database and application account creation.");
        if (!expectedAction(input.expectedRole()) || !expectedAction(input.expectedDatabase())) throw invalid();
        return bounded(input, true);
    }

    private ProvisionResult bounded(ProvisionSettings input, boolean create) {
        if (!OPERATIONS.tryAcquire()) return result(Outcome.BUSY, ResourceState.UNKNOWN, ResourceState.UNKNOWN,
                "busy", "Other PostgreSQL preparation operations are running. Wait, then inspect again.");
        Progress progress = new Progress();
        FutureTask<ProvisionResult> task = new FutureTask<>(() -> execute(input, create, progress));
        Thread.ofVirtual().name("taxonomy-postgres-preparation").start(() -> {
            try { task.run(); } finally { OPERATIONS.release(); }
        });
        try {
            return task.get(create ? 60 : 20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            progress.cancelled = true;
            return uncertain(progress, create);
        } catch (TimeoutException timeout) {
            progress.cancelled = true;
            return uncertain(progress, create);
        } catch (ExecutionException failure) {
            return failure(progress, failure.getCause());
        } finally {
            // The permit is retained by the worker until JDBC actually exits.
            task.cancel(true);
        }
    }

    private ProvisionResult execute(ProvisionSettings input, boolean create, Progress progress) {
        try (Connection admin = connect(input, input.maintenanceDatabase(), input.adminUsername(), input.adminPassword())) {
            admin.setAutoCommit(true); // CREATE DATABASE must not run inside a transaction.
            if (admin.getMetaData().getDatabaseMajorVersion() < 15)
                return result(Outcome.FAILED, progress.role, progress.database, "version", "Automatic preparation requires PostgreSQL 15 or later.");
            if (scalarBoolean(admin, "SELECT pg_is_in_recovery() OR current_setting('transaction_read_only')::boolean"))
                return result(Outcome.FAILED, progress.role, progress.database, "readonly", "This server is read-only. Use the writable PostgreSQL primary endpoint.");
            boolean locked = false;
            try {
                if (create) {
                    locked = scalarBoolean(admin, "SELECT pg_try_advisory_lock(" + LOCK_KEY + ")");
                    if (!locked) return result(Outcome.BUSY, progress.role, progress.database,
                            "busy", "Another PostgreSQL preparation is running. Wait, then inspect again.");
                }
                Plan plan = inspectCatalog(admin, input, progress, create);
                if (plan.failure != null) return plan.failure;
                if (!create) return result(Outcome.READY, plan.roleExists ? ResourceState.REUSE : ResourceState.CREATE,
                        plan.databaseExists ? ResourceState.REUSE : ResourceState.CREATE, "ready",
                        "Names, administrator capabilities and existing application account were inspected. Review the planned creation or reuse before confirming.");
                if (progress.cancelled || Thread.currentThread().isInterrupted()) return uncertain(progress, true);
                if (!plan.roleExists) {
                    char[] password = input.target().password().toCharArray();
                    String verifier;
                    try { verifier = PasswordUtil.encodePassword(input.target().username(), password, "scram-sha-256"); }
                    finally { Arrays.fill(password, '\0'); }
                    progress.writing = true;
                    progress.role = ResourceState.UNKNOWN;
                    executeSql(admin, "CREATE ROLE " + identifier(input.target().username())
                            + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                            + verifier.replace("'", "''") + "'");
                    progress.role = ResourceState.CREATED;
                    progress.writing = false;
                } else progress.role = ResourceState.REUSED;
                if (progress.cancelled || Thread.currentThread().isInterrupted()) return uncertain(progress, true);
                if (!plan.databaseExists) {
                    progress.writing = true;
                    progress.database = ResourceState.UNKNOWN;
                    executeSql(admin, "CREATE DATABASE " + identifier(input.target().database()) + " OWNER "
                            + identifier(input.target().username()) + " TEMPLATE template0");
                    progress.database = ResourceState.CREATED;
                    progress.writing = false;
                } else progress.database = ResourceState.REUSED;
                verifyApplication(input, input.target().database(), true);
                return result(Outcome.COMPLETE, progress.role, progress.database, "complete",
                        "Database and restricted application account are ready; application login, ownership and schema privileges were verified. Apply deployment configuration and restart Taxonomy to run schema migrations.");
            } finally {
                if (locked) {
                    try { scalarBoolean(admin, "SELECT pg_advisory_unlock(" + LOCK_KEY + ")"); }
                    catch (SQLException ignored) { /* Connection close also releases this session lock. */ }
                }
            }
        } catch (Exception problem) { return failure(progress, problem); }
    }

    private Plan inspectCatalog(Connection admin, ProvisionSettings input, Progress progress, boolean create) throws SQLException {
        String role = input.target().username(), database = input.target().database();
        boolean superuser, createdb, createrole;
        try (Statement statement = statement(admin); ResultSet rows = statement.executeQuery(
                "SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user")) {
            if (!rows.next()) throw new SQLException("", "42501");
            superuser = rows.getBoolean(1); createdb = rows.getBoolean(2); createrole = rows.getBoolean(3);
        }
        boolean roleExists;
        try (PreparedStatement statement = admin.prepareStatement(
                "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, "
                        + "EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member = r.oid) "
                        + "FROM pg_roles r WHERE rolname = ?")) {
            statement.setQueryTimeout(15); statement.setString(1, role);
            try (ResultSet rows = statement.executeQuery()) {
                roleExists = rows.next();
                progress.role = roleExists ? ResourceState.REUSE : ResourceState.NOT_CREATED;
                if (create && input.expectedRole() != (roleExists ? ResourceState.REUSE : ResourceState.CREATE))
                    return planChanged(progress);
                if (roleExists && (!rows.getBoolean(1) || rows.getBoolean(2) || rows.getBoolean(3)
                        || rows.getBoolean(4) || rows.getBoolean(5) || rows.getBoolean(6) || rows.getBoolean(7)))
                    return conflict(progress, "The application role already exists with incompatible privileges or memberships. Choose a dedicated restricted application account; existing roles are never changed.");
            }
        }
        boolean databaseExists;
        try (PreparedStatement statement = admin.prepareStatement(
                "SELECT pg_get_userbyid(datdba), datallowconn, datistemplate FROM pg_database WHERE datname = ?")) {
            statement.setQueryTimeout(15); statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                databaseExists = rows.next();
                progress.database = databaseExists ? ResourceState.REUSE : ResourceState.NOT_CREATED;
                if (create && input.expectedDatabase() != (databaseExists ? ResourceState.REUSE : ResourceState.CREATE))
                    return planChanged(progress);
                if (databaseExists && (!roleExists || !role.equals(rows.getString(1)) || !rows.getBoolean(2) || rows.getBoolean(3)))
                    return conflict(progress, "The database already exists with a different owner or incompatible settings. Existing databases are never changed.");
            }
        }
        if (!superuser && ((!roleExists && (!createrole || !createdb)) || (!databaseExists && !createdb)))
            return denied(progress);
        // CREATE ROLE + CREATEDB does not establish SET ROLE for the future owner on every supported server.
        // Do not create a role and then discover that database ownership cannot be assigned.
        if (!superuser && !roleExists) return denied(progress);
        if (roleExists) {
            verifyApplication(input, databaseExists ? database : input.maintenanceDatabase(), databaseExists);
            if (!superuser && !databaseExists) {
                try {
                    executeSql(admin, "SET ROLE " + identifier(role));
                    executeSql(admin, "RESET ROLE");
                } catch (SQLException denied) { return denied(progress); }
            }
        }
        return new Plan(roleExists, databaseExists, null);
    }

    private void verifyApplication(ProvisionSettings input, String database, boolean requireSchema) throws SQLException {
        try (Connection app = connect(input, database, input.target().username(), input.target().password());
             Statement statement = statement(app);
             ResultSet rows = statement.executeQuery(requireSchema
                     ? "SELECT current_user, current_database(), pg_get_userbyid(d.datdba) = current_user, "
                        + "has_schema_privilege(current_user, 'public', 'USAGE'), has_schema_privilege(current_user, 'public', 'CREATE') "
                        + "FROM pg_database d WHERE d.datname = current_database()"
                     : "SELECT current_user, current_database()")) {
            if (!rows.next() || !input.target().username().equals(rows.getString(1)) || !database.equals(rows.getString(2)))
                throw new SQLException("", "42501");
            if (requireSchema && (!rows.getBoolean(3) || !rows.getBoolean(4) || !rows.getBoolean(5)))
                throw new SQLException("", "42501");
        }
    }

    private Connection connect(ProvisionSettings input, String database, String user, String password) throws SQLException {
        var target = input.target();
        var settings = new DatabaseSetupService.ConnectionSettings("postgres", target.host(), target.port(), database,
                user, password, target.tls(), target.caCertificate());
        String url = DatabaseSetupService.jdbcUrl(settings, !Boolean.FALSE.equals(target.tls()));
        Properties properties = SetupProbe.connectionProperties(url, user, password);
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("ApplicationName", "Taxonomy database preparation");
        return connector.connect(url, properties);
    }

    private static void validate(ProvisionSettings input) {
        if (input == null || input.target() == null) throw invalid();
        DatabaseSetupService.validate(input.target());
        if ((input.expectedRole() != null && !expectedAction(input.expectedRole()))
                || (input.expectedDatabase() != null && !expectedAction(input.expectedDatabase()))) throw invalid();
        var target = input.target();
        if (!"postgres".equals(target.type()) || !creationName(target.database()) || !creationName(target.username())
                || target.password() == null || target.password().isBlank()
                || input.maintenanceDatabase() == null || !input.maintenanceDatabase().matches("[a-z_][a-z0-9_]{0,62}")
                || input.maintenanceDatabase().equals(target.database())
                || input.adminUsername() == null || !input.adminUsername().matches("[A-Za-z0-9_@.$\\\\-]{1,128}")
                || input.adminUsername().equals(target.username()) || input.adminPassword() == null
                || input.adminPassword().isBlank() || input.adminPassword().length() > 4096) throw invalid();
    }
    private static boolean expectedAction(ResourceState state) {
        return state == ResourceState.CREATE || state == ResourceState.REUSE;
    }
    private static boolean creationName(String name) {
        return name != null && name.matches("[a-z_][a-z0-9_]{0,62}") && !name.startsWith("pg_") && !RESERVED.contains(name);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid PostgreSQL preparation settings."); }
    private static String identifier(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static Statement statement(Connection connection) throws SQLException {
        Statement statement = connection.createStatement(); statement.setQueryTimeout(15); return statement;
    }
    private static void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement statement = statement(connection)) { statement.execute(sql); }
    }
    private static boolean scalarBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = statement(connection); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("", "08000");
            return rows.getBoolean(1);
        }
    }
    private static Plan conflict(Progress progress, String message) {
        return new Plan(false, false, result(Outcome.CONFLICT, progress.role, progress.database, "conflict", message));
    }
    private static Plan planChanged(Progress progress) {
        return new Plan(false, false, result(Outcome.CONFLICT, progress.role, progress.database, "plan-changed",
                "The database or account creation/reuse plan changed after inspection. No resources were changed. Inspect again and review the new plan before confirming."));
    }
    private static Plan denied(Progress progress) {
        return new Plan(false, false, result(Outcome.FAILED, progress.role, progress.database, "permission",
                "Administrator permissions are insufficient to establish role creation, database creation and ownership assignment before writing. Use a database administrator; existing objects will not be changed."));
    }
    private static ProvisionResult failure(Progress progress, Throwable problem) {
        String state = problem instanceof SQLException sql ? sql.getSQLState() : null;
        if (progress.writing && uncertainWrite(problem))
            return uncertain(progress, true);
        if (progress.role == ResourceState.UNKNOWN) progress.role = ResourceState.NOT_CREATED;
        if (progress.database == ResourceState.UNKNOWN) progress.database = ResourceState.NOT_CREATED;
        boolean changed = progress.role == ResourceState.CREATED || progress.database == ResourceState.CREATED;
        if ("42710".equals(state) || "42P04".equals(state)) return result(changed ? Outcome.PARTIAL : Outcome.CONFLICT,
                progress.role, progress.database, "conflict", "A resource was created concurrently. No existing resource was changed. Inspect again before continuing.");
        if (changed) return new ProvisionResult(Outcome.PARTIAL, progress.role, progress.database, SetupProbe.databaseFailure(problem));
        return new ProvisionResult(Outcome.FAILED, progress.role, progress.database, SetupProbe.databaseFailure(problem));
    }
    /** PostgreSQL can explicitly report unknown completion without using connection-error class 08.
     * Inspect both JDBC chains and Java causes: wrapper states must not turn uncertainty into absence.
     */
    private static boolean uncertainWrite(Throwable problem) {
        if (!(problem instanceof SQLException)) return true;
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(problem);
        for (int count = 0; count < 32 && !pending.isEmpty(); count++) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLTimeoutException || current instanceof TimeoutException
                    || current instanceof InterruptedException || current instanceof java.io.IOException) return true;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state == null || state.startsWith("08") || Set.of("40003", "57P01", "57P02").contains(state)) return true;
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        // An exceptionally deep chain cannot establish definite non-creation.
        return !pending.isEmpty();
    }
    private static ProvisionResult uncertain(Progress progress, boolean create) {
        if (!create) return new ProvisionResult(Outcome.FAILED, progress.role, progress.database,
                SetupProbe.databaseFailure(Thread.currentThread().isInterrupted()
                        ? new InterruptedException() : new TimeoutException()));
        return result(Outcome.UNKNOWN, progress.role, progress.database, "unknown",
                "Preparation was interrupted or its outcome could not be confirmed. Resources may exist. Inspect before another creation attempt; nothing is automatically removed or retried.");
    }
    private static ProvisionResult result(Outcome outcome, ResourceState role, ResourceState database, String key, String message) {
        SetupChecks.Status status = switch (outcome) {
            case COMPLETE -> SetupChecks.Status.OK;
            case READY -> SetupChecks.Status.NOT_CHECKED;
            case BUSY -> SetupChecks.Status.WARNING;
            default -> SetupChecks.Status.ERROR;
        };
        return new ProvisionResult(outcome, role, database, new SetupChecks.Finding(status, "database.provision." + key, message));
    }
    private record Plan(boolean roleExists, boolean databaseExists, ProvisionResult failure) { }
    private static final class Progress {
        volatile ResourceState role = ResourceState.UNKNOWN, database = ResourceState.UNKNOWN;
        volatile boolean writing, cancelled;
    }
}
