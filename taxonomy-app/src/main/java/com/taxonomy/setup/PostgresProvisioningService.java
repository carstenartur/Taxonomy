package com.taxonomy.setup;

import org.postgresql.util.PasswordUtil;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.Arrays;
import java.util.Set;
import com.taxonomy.setup.DatabaseProvisioningService.*;
import static com.taxonomy.setup.DatabaseProvisioningService.*;

/** PostgreSQL preparation; never changes existing resources or the active datasource. */
@Component
public class PostgresProvisioningService implements DatabaseProvisioningService.Provider {
    private static final long LOCK_KEY = 0x5441584f4e4f4d59L;
    private static final Set<String> RESERVED = Set.of("postgres", "template0", "template1", "public");
    private final Connector connector;
    public PostgresProvisioningService() { this(DriverManager::getConnection); }
    PostgresProvisioningService(Connector connector) { this.connector = connector; }
    @Override public String type() { return "postgres"; }

    @Override public ProvisionResult execute(ProvisionSettings input, boolean create, Progress progress) {
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
                    return new Plan(false, false, DatabaseProvisioningService.planChanged(progress));
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
                    return new Plan(false, false, DatabaseProvisioningService.planChanged(progress));
                if (databaseExists && (!roleExists || !role.equals(rows.getString(1)) || !rows.getBoolean(2) || rows.getBoolean(3)))
                    return conflict(progress, "The database already exists with a different owner or incompatible settings. Existing databases are never changed.");
            }
        }
        if (!superuser && ((!roleExists && (!createrole || !createdb)) || (!databaseExists && !createdb)))
            return new Plan(false, false, DatabaseProvisioningService.denied(progress));
        // CREATE ROLE + CREATEDB does not establish SET ROLE for the future owner on every supported server.
        // Do not create a role and then discover that database ownership cannot be assigned.
        if (!superuser && !roleExists) return new Plan(false, false, DatabaseProvisioningService.denied(progress));
        if (roleExists) {
            verifyApplication(input, databaseExists ? database : input.maintenanceDatabase(), databaseExists);
            if (!superuser && !databaseExists) {
                try {
                    executeSql(admin, "SET ROLE " + identifier(role));
                    executeSql(admin, "RESET ROLE");
                } catch (SQLException denied) { return new Plan(false, false, DatabaseProvisioningService.denied(progress)); }
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
        return DatabaseProvisioningService.connect(connector, input, database, user, password);
    }
    @Override public void validate(ProvisionSettings input) {
        if (input == null || input.target() == null) throw invalid();
        DatabaseSetupService.validate(input.target());
        if ((input.expectedRole() != null && !expectedAction(input.expectedRole()))
                || (input.expectedDatabase() != null && !expectedAction(input.expectedDatabase()))) throw invalid();
        var target = input.target();
        if (input.tablespace() != null || input.quotaMiB() != null || !"postgres".equals(target.type()) || !creationName(target.database()) || !creationName(target.username())
                || target.password() == null || target.password().isBlank()
                || input.maintenanceDatabase() == null || !input.maintenanceDatabase().matches("[a-z_][a-z0-9_]{0,62}")
                || input.maintenanceDatabase().equals(target.database())
                || input.adminUsername() == null || !input.adminUsername().matches("[A-Za-z0-9_@.$\\\\-]{1,128}")
                || input.adminUsername().equals(target.username()) || input.adminPassword() == null
                || input.adminPassword().isBlank() || input.adminPassword().length() > 4096) throw invalid();
    }
    private static boolean creationName(String name) {
        return name != null && name.matches("[a-z_][a-z0-9_]{0,62}") && !name.startsWith("pg_") && !RESERVED.contains(name);
    }
    private static String identifier(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static Plan conflict(Progress progress, String message) {
        return new Plan(false, false, result(Outcome.CONFLICT, progress.role, progress.database, "conflict", message));
    }
    private record Plan(boolean roleExists, boolean databaseExists, ProvisionResult failure) { }
}
