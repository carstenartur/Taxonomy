package com.taxonomy.setup;

import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.Set;
import static com.taxonomy.setup.DatabaseProvisioningService.*;

/** SQL Server bootstrap: a restricted server login owns only the explicitly created database. */
@Component
public class MssqlProvisioningService implements DatabaseProvisioningService.Provider {
    private static final String LOCK_RESOURCE = "Taxonomy database preparation";
    private static final Set<String> RESERVED = Set.of("master", "model", "msdb", "tempdb", "resource",
            "distribution", "sa", "dbo", "guest", "sys", "information_schema");
    private final Connector connector;

    public MssqlProvisioningService() { this(DriverManager::getConnection); }
    MssqlProvisioningService(Connector connector) { this.connector = connector; }
    @Override public String type() { return "mssql"; }

    @Override public void validate(ProvisionSettings input) {
        var target = input.target();
        if (!name(target.database()) || !name(target.username()) || !"master".equals(input.maintenanceDatabase())
                || input.tablespace() != null || input.quotaMiB() != null
                || target.password().length() < 8 || target.password().length() > 128
                || target.password().chars().anyMatch(c -> c < 32 || c == 127 || c == '\'')) throw invalid();
    }

    @Override public ProvisionResult execute(ProvisionSettings input, boolean create, Progress progress) throws Exception {
        try (Connection admin = connect(connector, input, "master", input.adminUsername(), input.adminPassword())) {
            admin.setAutoCommit(true); // SQL Server does not allow CREATE DATABASE inside a transaction.
            if (admin.getMetaData().getDatabaseMajorVersion() < 15)
                return serverFailure(progress);
            try (Statement statement = statement(admin); ResultSet rows = statement.executeQuery("""
                    SELECT IS_SRVROLEMEMBER('sysadmin'),
                           CAST(SERVERPROPERTY('IsIntegratedSecurityOnly') AS int),
                           CAST(SERVERPROPERTY('EngineEdition') AS int),
                           CASE WHEN DATABASEPROPERTYEX(DB_NAME(), 'Updateability') = 'READ_WRITE' THEN 0 ELSE 1 END
                    """)) {
                if (!rows.next()) throw new SQLException("", "08000");
                if (!rows.getBoolean(1)) return denied(progress);
                if (rows.getBoolean(2) || !Set.of(2, 3, 4).contains(rows.getInt(3)) || rows.getBoolean(4))
                    return serverFailure(progress);
            }
            boolean locked = false;
            try {
                if (create) {
                    int acquired = lock(admin, true);
                    if (acquired < 0) return result(Outcome.BUSY, progress.role, progress.database, "busy",
                            "Another SQL Server preparation is running, or the session lock could not be acquired. Inspect again before retrying.");
                    locked = true;
                }
                Plan plan = inspectCatalog(admin, input, create, progress);
                if (plan.failure != null) return plan.failure;
                if (!create) return result(Outcome.READY, plan.loginExists ? ResourceState.REUSE : ResourceState.CREATE,
                        plan.databaseExists ? ResourceState.REUSE : ResourceState.CREATE, "mssql.ready",
                        "SQL Server login, database ownership and administrator permissions were inspected. Review the planned creation or reuse. Application schema initialization remains a separate compatibility step.");
                if (cancelled(progress)) return uncertain(progress, true);
                if (!plan.loginExists) {
                    progress.role = ResourceState.UNKNOWN;
                    progress.writing = true;
                    createLogin(admin, input);
                    progress.role = ResourceState.CREATED;
                    progress.writing = false;
                } else progress.role = ResourceState.REUSED;
                if (cancelled(progress)) return uncertain(progress, true);
                if (!plan.databaseExists) {
                    progress.database = ResourceState.UNKNOWN;
                    progress.writing = true;
                    executeSql(admin, "CREATE DATABASE " + identifier(input.target().database()));
                    progress.database = ResourceState.CREATED;
                    progress.writing = false;
                    // Ownership is assigned only to the database created by this operation.
                    // Failure here leaves a real database behind and must be reported as partial.
                    if (cancelled(progress)) return uncertain(progress, true);
                    progress.writing = true;
                    executeSql(admin, "ALTER AUTHORIZATION ON DATABASE::" + identifier(input.target().database())
                            + " TO " + identifier(input.target().username()));
                    progress.writing = false;
                } else progress.database = ResourceState.REUSED;
                if (cancelled(progress)) return uncertain(progress, true);
                verifyApplication(input, input.target().database(), true);
                return result(Outcome.COMPLETE, progress.role, progress.database, "mssql.complete",
                        "SQL Server login and database ownership were verified. The application connects as the database owner (dbo). Application tables, production migrations and data transfer are not prepared by this operation; follow the SQL Server compatibility setup before applying deployment configuration.");
            } finally {
                if (locked) {
                    try { lock(admin, false); }
                    catch (SQLException ignored) { /* Closing this unpooled session releases its lock. */ }
                }
            }
        }
    }

    private Plan inspectCatalog(Connection admin, ProvisionSettings input, boolean create, Progress progress) throws SQLException {
        boolean loginExists;
        try (PreparedStatement statement = admin.prepareStatement("""
                SELECT p.type, p.is_disabled,
                       CASE WHEN EXISTS (SELECT 1 FROM sys.server_role_members m WHERE m.member_principal_id = p.principal_id) THEN 1 ELSE 0 END,
                       CASE WHEN EXISTS (SELECT 1 FROM sys.server_permissions s WHERE s.grantee_principal_id = p.principal_id
                            AND (s.state = 'W' OR (s.state = 'G' AND s.permission_name <> 'CONNECT SQL'))) THEN 1 ELSE 0 END
                FROM sys.server_principals p WHERE p.name = ?
                """)) {
            statement.setQueryTimeout(15); statement.setString(1, input.target().username());
            try (ResultSet rows = statement.executeQuery()) {
                loginExists = rows.next();
                progress.role = loginExists ? ResourceState.REUSE : ResourceState.NOT_CREATED;
                if (create && input.expectedRole() != (loginExists ? ResourceState.REUSE : ResourceState.CREATE))
                    return new Plan(false, false, planChanged(progress));
                if (loginExists && (!"S".equals(rows.getString(1)) || rows.getBoolean(2) || rows.getBoolean(3) || rows.getBoolean(4)))
                    return conflict(progress, "The existing login is disabled, is not a SQL login, or has server roles or additional server permissions. Use a dedicated application login; existing logins are never changed.");
            }
        }
        boolean databaseExists;
        try (PreparedStatement statement = admin.prepareStatement("""
                SELECT SUSER_SNAME(owner_sid), state_desc, is_read_only, is_trustworthy_on, is_db_chaining_on
                FROM sys.databases WHERE name = ?
                """)) {
            statement.setQueryTimeout(15); statement.setString(1, input.target().database());
            try (ResultSet rows = statement.executeQuery()) {
                databaseExists = rows.next();
                progress.database = databaseExists ? ResourceState.REUSE : ResourceState.NOT_CREATED;
                if (create && input.expectedDatabase() != (databaseExists ? ResourceState.REUSE : ResourceState.CREATE))
                    return new Plan(false, false, planChanged(progress));
                if (databaseExists && (!loginExists || !input.target().username().equals(rows.getString(1))
                        || !"ONLINE".equals(rows.getString(2)) || rows.getBoolean(3) || rows.getBoolean(4) || rows.getBoolean(5)))
                    return conflict(progress, "The existing database has a different owner, is not online and writable, or enables TRUSTWORTHY or database ownership chaining. Existing databases are never changed.");
            }
        }
        if (loginExists) verifyApplication(input, databaseExists ? input.target().database() : "master", databaseExists);
        return new Plan(loginExists, databaseExists, null);
    }

    private void verifyApplication(ProvisionSettings input, String database, boolean requireOwner) throws SQLException {
        try (Connection app = connect(connector, input, database, input.target().username(), input.target().password());
             Statement statement = statement(app);
             ResultSet rows = statement.executeQuery("""
                     SELECT ORIGINAL_LOGIN(), DB_NAME(), USER_NAME(), HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CONTROL')
                     """)) {
            if (!rows.next() || !input.target().username().equals(rows.getString(1)) || !database.equals(rows.getString(2))
                    || (requireOwner && (!"dbo".equals(rows.getString(3)) || !rows.getBoolean(4))))
                throw new SQLException("", "42501");
        }
    }

    private static void createLogin(Connection admin, ProvisionSettings input) throws SQLException {
        // Identifiers and the password enter as parameters, never as client SQL text.
        // SQL Server requires a literal in CREATE LOGIN; QUOTENAME safely constructs
        // that literal inside a constant prepared batch (its input limit is 128).
        try (PreparedStatement statement = admin.prepareStatement("""
                DECLARE @login sysname = ?;
                DECLARE @password nvarchar(128) = ?;
                DECLARE @sql nvarchar(max) = N'CREATE LOGIN ' + QUOTENAME(@login)
                    + N' WITH PASSWORD = N' + QUOTENAME(@password, NCHAR(39))
                    + N', CHECK_POLICY = ON, CHECK_EXPIRATION = OFF';
                EXEC sys.sp_executesql @sql;
                """)) {
            statement.setQueryTimeout(15);
            statement.setString(1, input.target().username()); statement.setString(2, input.target().password());
            statement.execute();
        }
    }

    private static int lock(Connection connection, boolean acquire) throws SQLException {
        try (CallableStatement statement = connection.prepareCall(acquire
                ? "{? = call sys.sp_getapplock(?, ?, ?, ?)}" : "{? = call sys.sp_releaseapplock(?, ?)}")) {
            statement.setQueryTimeout(15); statement.registerOutParameter(1, Types.INTEGER);
            statement.setString(2, LOCK_RESOURCE);
            if (acquire) {
                statement.setString(3, "Exclusive"); statement.setString(4, "Session"); statement.setInt(5, 0);
            } else statement.setString(3, "Session");
            statement.execute();
            return statement.getInt(1);
        }
    }

    private static boolean name(String value) {
        return value != null && value.matches("[a-z_][a-z0-9_]{0,127}")
                && !value.startsWith("sys_") && !RESERVED.contains(value);
    }
    private static String identifier(String value) { return '[' + value.replace("]", "]]") + ']'; }
    private static boolean cancelled(Progress progress) { return progress.cancelled || Thread.currentThread().isInterrupted(); }
    private static Plan conflict(Progress progress, String message) {
        return new Plan(false, false, result(Outcome.CONFLICT, progress.role, progress.database, "conflict", message));
    }
    private static ProvisionResult serverFailure(Progress progress) {
        return result(Outcome.FAILED, progress.role, progress.database, "mssql.server",
                "Automatic preparation requires a writable SQL Server 2019 or later instance with SQL authentication. Azure SQL and contained-user provisioning are not handled by this operation.");
    }
    private record Plan(boolean loginExists, boolean databaseExists, ProvisionResult failure) { }
}
