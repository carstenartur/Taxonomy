package com.taxonomy.setup;

import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.Locale;
import java.util.Set;
import static com.taxonomy.setup.DatabaseProvisioningService.*;

/** Creates a local schema owner inside an existing PDB; never creates an Oracle database or tablespace. */
@Component
public class OracleProvisioningService implements DatabaseProvisioningService.Provider {
    // User-assigned DBMS_LOCK IDs must be in 0..1073741823. Do not ALLOCATE_UNIQUE:
    // that operation would persist lock metadata and commit during inspection.
    private static final int LOCK_ID = 0x5441584;
    private static final Set<String> RESERVED_USERS = Set.of("SYS", "SYSTEM", "PUBLIC", "OUTLN", "DBSNMP",
            "SYSMAN", "XDB", "AUDSYS", "GSMADMIN_INTERNAL", "DVSYS", "DVF", "LBACSYS", "MDSYS", "ORDSYS",
            "WMSYS", "CTXSYS", "APPQOSSYS", "ANONYMOUS", "XS_NULL", "REMOTE_SCHEDULER_AGENT");
    private static final Set<String> RESERVED_TABLESPACES = Set.of("SYSTEM", "SYSAUX", "TEMP", "UNDOTBS1", "UNDOTBS2");
    private final Connector connector;

    public OracleProvisioningService() { this(DriverManager::getConnection); }
    OracleProvisioningService(Connector connector) { this.connector = connector; }
    @Override public String type() { return "oracle"; }

    @Override public void validate(ProvisionSettings input) {
        String user = canonical(input.target().username()), tablespace = canonical(input.tablespace());
        if (!name(user) || RESERVED_USERS.contains(user) || user.startsWith("SYS_") || user.startsWith("ORA_")
                || !name(tablespace) || RESERVED_TABLESPACES.contains(tablespace)
                || input.quotaMiB() == null || input.quotaMiB() < 1 || input.quotaMiB() > 1048576
                || !input.target().database().equals(input.maintenanceDatabase())
                || input.expectedDatabase() != null && input.expectedDatabase() != ResourceState.REUSE
                || input.target().password().length() > 30
                || input.target().password().chars().anyMatch(c -> c < 32 || c > 126 || c == '"')) throw invalid();
    }

    @Override public ProvisionResult execute(ProvisionSettings input, boolean create, Progress progress) throws Exception {
        try (Connection admin = connect(connector, input, input.target().database(), input.adminUsername(), input.adminPassword())) {
            admin.setAutoCommit(true); // Oracle DDL commits; this operation cannot promise transactional rollback.
            if (admin.getMetaData().getDatabaseMajorVersion() < 19) return pdbFailure(progress);
            String pdb;
            try (Statement statement = statement(admin); ResultSet rows = statement.executeQuery("""
                    SELECT TO_NUMBER(SYS_CONTEXT('USERENV', 'CON_ID')), SYS_CONTEXT('USERENV', 'CON_NAME'),
                           (SELECT open_mode FROM v$pdbs WHERE con_id = TO_NUMBER(SYS_CONTEXT('USERENV', 'CON_ID'))),
                           (SELECT COUNT(*) FROM session_roles WHERE role = 'DBA'),
                           (SELECT COUNT(*) FROM session_privs WHERE privilege IN ('CREATE USER', 'GRANT ANY PRIVILEGE'))
                    FROM dual
                    """)) {
                if (!rows.next()) throw new SQLException("", "08000");
                pdb = rows.getString(2);
                if (rows.getInt(1) < 3 || pdb == null || Set.of("CDB$ROOT", "PDB$SEED").contains(pdb)
                        || !"READ WRITE".equals(rows.getString(3))) return pdbFailure(progress);
                progress.database = ResourceState.REUSED;
                if (rows.getInt(4) != 1 || rows.getInt(5) != 2) return denied(progress);
            }
            boolean locked = false;
            try {
                // This is a fresh, unpooled connection. RELEASE checks package execution
                // without acquiring a lock or creating objects; it normally returns 4
                // (this session does not own the lock). The application user never needs it.
                try {
                    int released = lock(admin, false);
                    if (released != 0 && released != 4) return denied(progress);
                } catch (SQLException permission) {
                    if (permission.getErrorCode() == 1031 || permission.getErrorCode() == 6550)
                        return result(Outcome.FAILED, progress.role, progress.database, "oracle.lock-permission",
                                "The setup administrator needs EXECUTE on SYS.DBMS_LOCK in this PDB. Ask the Oracle administrator to provide this setup permission; the application user does not need it.");
                    throw permission;
                }
                if (create) {
                    int acquired = lock(admin, true);
                    if (acquired != 0 && acquired != 4) return result(Outcome.BUSY, progress.role, progress.database, "busy",
                            "Another Oracle preparation is running, or the session lock could not be acquired. Inspect again before retrying.");
                    locked = true;
                }
                Plan plan = inspectCatalog(admin, input, pdb, create, progress);
                if (plan.failure != null) return plan.failure;
                if (!create) return result(Outcome.READY, plan.userExists ? ResourceState.REUSE : ResourceState.CREATE,
                        ResourceState.REUSE, "oracle.ready",
                        "The existing PDB, application tablespace, finite quota and local user were inspected. Only the displayed local user/schema may be created; the PDB and tablespace are reused. Application schema initialization remains a separate compatibility step.");
                if (cancelled(progress)) return uncertain(progress, true);
                if (!plan.userExists) {
                    progress.role = ResourceState.UNKNOWN;
                    progress.writing = true;
                    createUser(admin, input);
                    progress.role = ResourceState.CREATED;
                    progress.writing = false;
                    if (cancelled(progress)) return uncertain(progress, true);
                    progress.writing = true;
                    executeSql(admin, "GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + identifier(canonical(input.target().username())));
                    progress.writing = false;
                } else progress.role = ResourceState.REUSED;
                if (cancelled(progress)) return uncertain(progress, true);
                verifyApplication(input, pdb);
                return result(Outcome.COMPLETE, progress.role, ResourceState.REUSED, "oracle.complete",
                        "The local Oracle user, own-schema privileges and finite tablespace quota were verified in the existing PDB. No PDB or tablespace was created. Application tables, production migrations and data transfer are not prepared by this operation; follow the Oracle compatibility setup before applying deployment configuration.");
            } finally {
                if (locked) {
                    try { lock(admin, false); }
                    catch (SQLException ignored) { /* Closing this unpooled session releases its lock. */ }
                }
            }
        }
    }

    private Plan inspectCatalog(Connection admin, ProvisionSettings input, String pdb, boolean create, Progress progress) throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement("SELECT status, contents FROM dba_tablespaces WHERE tablespace_name = ?")) {
            statement.setQueryTimeout(15); statement.setString(1, canonical(input.tablespace()));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !"ONLINE".equals(rows.getString(1)) || !"PERMANENT".equals(rows.getString(2)))
                    return new Plan(false, result(Outcome.FAILED, progress.role, progress.database, "oracle.tablespace",
                            "Select an existing online permanent application tablespace. The wizard does not create or change tablespaces."));
            }
        }
        boolean userExists;
        try (PreparedStatement statement = admin.prepareStatement("""
                SELECT u.account_status, u.common, u.oracle_maintained, u.authentication_type, u.default_tablespace,
                       (SELECT COUNT(*) FROM dba_role_privs r WHERE r.grantee = u.username),
                       (SELECT COUNT(*) FROM dba_sys_privs s WHERE s.grantee = u.username
                            AND (s.privilege NOT IN ('CREATE SESSION', 'CREATE TABLE', 'CREATE SEQUENCE') OR s.admin_option = 'YES')),
                       (SELECT COUNT(*) FROM dba_sys_privs s WHERE s.grantee = u.username
                            AND s.privilege IN ('CREATE SESSION', 'CREATE TABLE', 'CREATE SEQUENCE')),
                       NVL((SELECT q.max_bytes FROM dba_ts_quotas q WHERE q.username = u.username AND q.tablespace_name = ?), 0),
                       (SELECT COUNT(*) FROM dba_tab_privs t WHERE t.grantee = u.username AND t.owner <> u.username)
                            + (SELECT COUNT(*) FROM dba_col_privs c WHERE c.grantee = u.username AND c.owner <> u.username),
                       (SELECT COUNT(*) FROM dba_ts_quotas q WHERE q.username = u.username AND q.tablespace_name <> ? AND q.max_bytes <> 0)
                FROM dba_users u WHERE u.username = ?
                """)) {
            statement.setQueryTimeout(15); statement.setString(1, canonical(input.tablespace()));
            statement.setString(2, canonical(input.tablespace())); statement.setString(3, canonical(input.target().username()));
            try (ResultSet rows = statement.executeQuery()) {
                userExists = rows.next();
                progress.role = userExists ? ResourceState.REUSE : ResourceState.NOT_CREATED;
                if (create && input.expectedRole() != (userExists ? ResourceState.REUSE : ResourceState.CREATE))
                    return new Plan(false, planChanged(progress));
                if (userExists && (!"OPEN".equals(rows.getString(1)) || !"NO".equals(rows.getString(2))
                        || !"N".equals(rows.getString(3)) || !"PASSWORD".equals(rows.getString(4))
                        || !canonical(input.tablespace()).equals(rows.getString(5)) || rows.getInt(6) != 0
                        || rows.getInt(7) != 0 || rows.getInt(8) != 3 || rows.getLong(9) != quotaBytes(input)
                        || rows.getInt(10) != 0 || rows.getInt(11) != 0))
                    return conflict(progress, "The existing user is not an open, local, restricted application account with only own-schema access, the selected tablespace and exactly the reviewed finite quota. Existing users, grants, passwords and quotas are never changed.");
            }
        }
        if (!userExists) {
            try (PreparedStatement statement = admin.prepareStatement("SELECT COUNT(*) FROM dba_roles WHERE role = ?")) {
                statement.setQueryTimeout(15); statement.setString(1, canonical(input.target().username()));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("", "08000");
                    if (rows.getInt(1) != 0) return conflict(progress, "An Oracle role already uses the requested application user name. Choose a new dedicated name; existing roles are never changed.");
                }
            }
        } else {
            // Oracle 23 introduced schema-wide grants; they are not individual
            // object grants and do not appear in the older privilege views.
            // Do not query this view on 19/21, where it does not exist.
            if (admin.getMetaData().getDatabaseMajorVersion() >= 23) {
                try (PreparedStatement statement = admin.prepareStatement("SELECT COUNT(*) FROM dba_schema_privs WHERE grantee = ?")) {
                    statement.setQueryTimeout(15); statement.setString(1, canonical(input.target().username()));
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new SQLException("", "08000");
                        if (rows.getInt(1) != 0) return conflict(progress,
                                "The existing application user has schema-wide grants. Choose a dedicated restricted account; existing grants are never changed.");
                    }
                }
            }
            verifyApplication(input, pdb);
        }
        return new Plan(userExists, null);
    }

    private void verifyApplication(ProvisionSettings input, String pdb) throws SQLException {
        try (Connection app = connect(connector, input, input.target().database(), input.target().username(), input.target().password());
             PreparedStatement statement = app.prepareStatement("""
                     SELECT SYS_CONTEXT('USERENV', 'SESSION_USER'), SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'),
                            SYS_CONTEXT('USERENV', 'CON_NAME'),
                            (SELECT COUNT(*) FROM session_privs WHERE privilege IN ('CREATE SESSION', 'CREATE TABLE', 'CREATE SEQUENCE')),
                            NVL((SELECT max_bytes FROM user_ts_quotas WHERE tablespace_name = ?), 0)
                     FROM dual
                     """)) {
            statement.setQueryTimeout(15); statement.setString(1, canonical(input.tablespace()));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !canonical(input.target().username()).equals(rows.getString(1))
                        || !canonical(input.target().username()).equals(rows.getString(2)) || !pdb.equals(rows.getString(3))
                        || rows.getInt(4) != 3 || rows.getLong(5) != quotaBytes(input)) throw new SQLException("", "42501");
            }
        }
    }

    private static void createUser(Connection admin, ProvisionSettings input) throws SQLException {
        // Oracle does not accept binds in DDL itself. Bind into this constant PL/SQL
        // block, quote identifiers with DBMS_ASSERT, and permit no double quote or
        // control character in the password. Raw secrets never enter client SQL text.
        try (PreparedStatement statement = admin.prepareStatement("""
                DECLARE
                    app_name VARCHAR2(30) := ?;
                    app_password VARCHAR2(30) := ?;
                    app_tablespace VARCHAR2(30) := ?;
                    app_quota PLS_INTEGER := ?;
                BEGIN
                    EXECUTE IMMEDIATE 'CREATE USER ' || SYS.DBMS_ASSERT.ENQUOTE_NAME(app_name, FALSE)
                        || ' IDENTIFIED BY "' || app_password || '" DEFAULT TABLESPACE '
                        || SYS.DBMS_ASSERT.ENQUOTE_NAME(app_tablespace, FALSE)
                        || ' QUOTA ' || TO_CHAR(app_quota, 'FM9999999', 'NLS_NUMERIC_CHARACTERS=''.,''')
                        || 'M ON ' || SYS.DBMS_ASSERT.ENQUOTE_NAME(app_tablespace, FALSE) || ' CONTAINER=CURRENT';
                END;
                """)) {
            statement.setQueryTimeout(15);
            statement.setString(1, canonical(input.target().username())); statement.setString(2, input.target().password());
            statement.setString(3, canonical(input.tablespace())); statement.setInt(4, input.quotaMiB());
            statement.execute();
        }
    }

    private static int lock(Connection connection, boolean acquire) throws SQLException {
        try (CallableStatement statement = connection.prepareCall(acquire
                ? "BEGIN ? := SYS.DBMS_LOCK.REQUEST(id => ?, lockmode => 6, timeout => 0, release_on_commit => FALSE); END;"
                : "BEGIN ? := SYS.DBMS_LOCK.RELEASE(?); END;")) {
            statement.setQueryTimeout(15); statement.registerOutParameter(1, Types.INTEGER); statement.setInt(2, LOCK_ID);
            statement.execute();
            return statement.getInt(1);
        }
    }

    private static String canonical(String value) { return value == null ? null : value.toUpperCase(Locale.ROOT); }
    private static boolean name(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,29}"); }
    private static String identifier(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static long quotaBytes(ProvisionSettings input) { return input.quotaMiB().longValue() * 1024 * 1024; }
    private static boolean cancelled(Progress progress) { return progress.cancelled || Thread.currentThread().isInterrupted(); }
    private static Plan conflict(Progress progress, String message) {
        return new Plan(false, result(Outcome.CONFLICT, progress.role, progress.database, "conflict", message));
    }
    private static ProvisionResult pdbFailure(Progress progress) {
        return result(Outcome.FAILED, progress.role, progress.database, "oracle.pdb",
                "Automatic preparation requires Oracle 19 or later and an existing PDB open READ WRITE. Connect to its service; CDB root, PDB seed, non-CDB databases and PDB creation are not handled by this operation.");
    }
    private record Plan(boolean userExists, ProvisionResult failure) { }
}
