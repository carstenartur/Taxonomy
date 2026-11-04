package com.taxonomy.setup;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

import static com.taxonomy.setup.DatabaseProvisioningService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Oracle's existing PDB and user/schema semantics must not become CREATE DATABASE. */
class OracleProvisioningServiceTest {
    private static final String SECRET = "Private-App-Secret-'2026!";

    @Test void inspectionUsesExistingPdbAndShowsOnlyUserToCreate() {
        var db = new Database();
        var plan = service(db).inspect(input(false, null));
        assertEquals(Outcome.READY, plan.outcome());
        assertEquals(ResourceState.CREATE, plan.role());
        assertEquals(ResourceState.REUSE, plan.database());
        assertTrue(db.writes.isEmpty());
        assertEquals(0, db.locks);
    }

    @Test void createsLocalUserWithFiniteQuotaAndOnlyOwnSchemaPrivileges() {
        var db = new Database();
        var result = service(db).provision(input(true, ResourceState.CREATE));
        assertEquals(Outcome.COMPLETE, result.outcome());
        assertEquals(ResourceState.CREATED, result.role());
        assertEquals(ResourceState.REUSED, result.database());
        assertEquals(List.of("user", "grant"), db.actions);
        assertEquals(100L * 1024 * 1024, db.quotaBytes);
        assertTrue(db.appConnections > 0);
        assertFalse(db.writes.toString().contains(SECRET));
        assertFalse(db.writes.toString().contains("UNLIMITED"));
        assertFalse(result.toString().contains(SECRET));
    }

    @Test void verifiedExistingUserIsReusedWithoutChangingQuotaGrantsOrPassword() {
        var db = new Database(); db.user = true; db.granted = true;
        var result = service(db).provision(input(true, ResourceState.REUSE));
        assertEquals(Outcome.COMPLETE, result.outcome());
        assertEquals(ResourceState.REUSED, result.role());
        assertEquals(ResourceState.REUSED, result.database());
        assertTrue(db.writes.isEmpty());
    }

    @Test void rootSeedReadOnlyPdbAndNonDbaAdministratorCannotPrepare() {
        for (String condition : List.of("root", "seed", "readonly", "admin", "version")) {
            var db = new Database();
            switch (condition) {
                case "root" -> { db.containerId = 1; db.pdb = "CDB$ROOT"; }
                case "seed" -> { db.containerId = 2; db.pdb = "PDB$SEED"; }
                case "readonly" -> db.openMode = "READ ONLY";
                case "admin" -> db.dba = false;
                case "version" -> db.version = 18;
            }
            assertEquals(Outcome.FAILED, service(db).provision(input(true, ResourceState.CREATE)).outcome(), condition);
            assertTrue(db.writes.isEmpty());
        }
    }

    @Test void unsuitableTablespaceAndExistingRoleNamePreventAnyCreation() {
        var tablespace = new Database(); tablespace.tablespaceOnline = false;
        assertEquals(Outcome.FAILED, service(tablespace).inspect(input(false, null)).outcome());
        assertTrue(tablespace.writes.isEmpty());
        var role = new Database(); role.roleCollision = true;
        assertEquals(Outcome.CONFLICT, service(role).provision(input(true, ResourceState.CREATE)).outcome());
        assertTrue(role.writes.isEmpty());
    }

    @Test void privilegedLockedCommonAndUnboundedExistingAccountsAreNeverAltered() {
        for (String condition : List.of("privileged", "locked", "common", "quota", "missing-grants")) {
            var db = new Database(); db.user = true; db.granted = true;
            switch (condition) {
                case "privileged" -> db.privileged = true;
                case "locked" -> db.accountStatus = "LOCKED";
                case "common" -> db.common = "YES";
                case "quota" -> db.quotaBytes = -1;
                case "missing-grants" -> db.granted = false;
            }
            assertEquals(Outcome.CONFLICT, service(db).provision(input(true, ResourceState.REUSE)).outcome(), condition);
            assertTrue(db.writes.isEmpty());
        }
    }

    @Test void userAppearingAfterReviewRequiresAnotherReview() {
        var db = new Database(); db.user = true; db.granted = true;
        var result = service(db).provision(input(true, ResourceState.CREATE));
        assertEquals(Outcome.CONFLICT, result.outcome());
        assertEquals("database.provision.plan-changed", result.finding().key());
        assertTrue(db.writes.isEmpty());
    }

    @Test void crossSchemaGrantsAndAdditionalTablespaceQuotasCannotBeAdopted() {
        for (boolean quota : List.of(false, true)) {
            var db = new Database(); db.user = true; db.granted = true;
            if (quota) db.extraQuotas = 1; else db.foreignGrants = 1;
            assertEquals(Outcome.CONFLICT, service(db).inspect(input(false, null)).outcome());
            assertTrue(db.writes.isEmpty());
        }
    }

    @Test void schemaWideGrantsOnOracle23CannotBypassRestrictedAccountInspection() {
        var db = new Database(); db.user = true; db.granted = true; db.schemaGrants = 1;
        assertEquals(Outcome.CONFLICT, service(db).inspect(input(false, null)).outcome());
        assertTrue(db.writes.isEmpty());
    }

    @Test void oracle19DoesNotQueryTheNewerSchemaPrivilegeDictionaryView() {
        var db = new Database(); db.version = 19; db.user = true; db.granted = true;
        assertEquals(Outcome.READY, service(db).inspect(input(false, null)).outcome());
        assertTrue(db.writes.isEmpty());
    }

    @Test void cancellationAfterUserCreationNeverBeginsGrantingPrivileges() {
        var db = new Database(); db.interruptAfterUser = true;
        var result = service(db).provision(input(true, ResourceState.CREATE));
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals(ResourceState.CREATED, result.role());
        assertEquals(List.of("user"), db.actions);
    }

    @Test void wrongExistingPasswordOrBusySessionLockDoesNotChangeAnything() {
        var badPassword = new Database(); badPassword.user = true; badPassword.granted = true; badPassword.badPassword = true;
        assertEquals(Outcome.FAILED, service(badPassword).provision(input(true, ResourceState.REUSE)).outcome());
        assertTrue(badPassword.writes.isEmpty());
        var busy = new Database(); busy.lockResult = 1;
        assertEquals(Outcome.BUSY, service(busy).provision(input(true, ResourceState.CREATE)).outcome());
        assertTrue(busy.writes.isEmpty());
    }

    @Test void failedGrantIsDurablePartialAndRetryDoesNotModifyTheExistingUser() {
        var db = new Database(); db.grantFailure = new SQLException(SECRET, "42000", 1031);
        var service = service(db);
        var first = service.provision(input(true, ResourceState.CREATE));
        assertEquals(Outcome.PARTIAL, first.outcome());
        assertEquals(ResourceState.CREATED, first.role());
        assertEquals(ResourceState.REUSED, first.database());
        assertFalse(first.toString().contains(SECRET));
        db.grantFailure = null;
        int writes = db.writes.size();
        assertEquals(Outcome.CONFLICT, service.inspect(input(false, null)).outcome());
        assertEquals(writes, db.writes.size());
    }

    @Test void connectionLossDuringCreatePreservesUnknownUserAndExistingPdb() {
        for (int code : List.of(1013, 3113, 3114, 17002, 17410)) {
            var db = new Database();
            var inner = new SQLException(SECRET, "72000", code);
            var wrapped = new SQLException("Opaque wrapper " + SECRET, "HY000");
            wrapped.setNextException(inner); db.userFailure = wrapped;
            var result = service(db).provision(input(true, ResourceState.CREATE));
            assertEquals(Outcome.UNKNOWN, result.outcome(), "Oracle code " + code);
            assertEquals(ResourceState.UNKNOWN, result.role());
            assertEquals(ResourceState.REUSED, result.database());
            assertFalse(result.toString().contains(SECRET));
        }
    }

    @Test void rejectedPasswordPolicyIsActionableAndNeverStartsGrantingPrivileges() {
        var db = new Database(); db.userFailure = new SQLException(SECRET, "99999", 28003);
        var result = service(db).provision(input(true, ResourceState.CREATE));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("database.provision.password-policy", result.finding().key());
        assertFalse(result.toString().contains(SECRET));
        assertEquals(List.of("user"), db.actions);
        assertFalse(db.user);
    }

    @Test void schemaNamesQuotaAndDatabaseCreationAreValidatedBeforeConnecting() {
        var original = input(false, null);
        for (String user : List.of("SYS", "SYSTEM", "C##USER", "SYS_USER", "quoted\"user", "x".repeat(31))) {
            var db = new Database(); var old = original.target();
            var target = new DatabaseSetupService.ConnectionSettings("oracle", old.host(), old.port(), old.database(), user, SECRET, false, "");
            assertThrows(IllegalArgumentException.class, () -> service(db).inspect(new ProvisionSettings(target,
                    "FREEPDB1", "SYSTEM", "Admin-Secret!", false, null, ResourceState.REUSE, "USERS", 100)));
            assertEquals(0, db.connections);
        }
        for (Integer quota : Arrays.asList(null, 0, -1, 1048577)) {
            var db = new Database();
            assertThrows(IllegalArgumentException.class, () -> service(db).inspect(new ProvisionSettings(original.target(),
                    "FREEPDB1", "SYSTEM", "Admin-Secret!", false, null, ResourceState.REUSE, "USERS", quota)));
            assertEquals(0, db.connections);
        }
        var db = new Database();
        assertThrows(IllegalArgumentException.class, () -> service(db).inspect(new ProvisionSettings(original.target(),
                "FREEPDB1", "SYSTEM", "Admin-Secret!", false, null, ResourceState.CREATE, "USERS", 100)));
        assertEquals(0, db.connections);
    }

    private static DatabaseProvisioningService service(Database database) {
        return new DatabaseProvisioningService(new OracleProvisioningService(database::connect));
    }

    private static ProvisionSettings input(boolean confirmed, ResourceState user) {
        return new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("oracle", "db", 1521,
                "FREEPDB1", "TAXONOMY_APP", SECRET, false, ""), "FREEPDB1", "SYSTEM", "Admin-Secret!",
                confirmed, user, ResourceState.REUSE, "USERS", 100);
    }

    private static final class Database {
        boolean user, granted, privileged, roleCollision, badPassword, interruptAfterUser;
        boolean dba = true, tablespaceOnline = true;
        String pdb = "FREEPDB1", openMode = "READ WRITE", accountStatus = "OPEN", common = "NO";
        int containerId = 3, version = 23, connections, appConnections, locks, lockResult, foreignGrants, extraQuotas, schemaGrants;
        long quotaBytes = 100L * 1024 * 1024;
        SQLException userFailure, grantFailure;
        final List<String> writes = new ArrayList<>(), actions = new ArrayList<>();

        Connection connect(String url, Properties properties) throws SQLException {
            connections++;
            boolean admin = "SYSTEM".equals(properties.getProperty("user"));
            if (!admin) {
                appConnections++;
                if (badPassword) throw new SQLException(SECRET, "72000", 1017);
            }
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "createStatement" -> sql(null, admin, Statement.class);
                case "prepareStatement" -> sql((String) args[0], admin, PreparedStatement.class);
                case "prepareCall" -> sql((String) args[0], admin, CallableStatement.class);
                case "getMetaData" -> proxy(DatabaseMetaData.class, (operation, parameters) -> operation.equals("getDatabaseMajorVersion") ? version : null);
                default -> null;
            });
        }

        Object sql(String prepared, boolean admin, Class<?> type) {
            Map<Integer, Object> parameters = new HashMap<>();
            return proxy(type, (method, args) -> {
                if (method.startsWith("set") && args != null && args.length == 2 && args[0] instanceof Integer index) {
                    parameters.put(index, args[1]); return null;
                }
                if (method.equals("executeQuery")) {
                    String query = prepared != null ? prepared : (String) args[0];
                    if (query.contains("FROM v$pdbs")) return rows(new Object[]{containerId, pdb, openMode, dba ? 1 : 0, dba ? 2 : 0});
                    if (query.contains("FROM dba_tablespaces")) return rows(new Object[]{tablespaceOnline ? "ONLINE" : "OFFLINE", "PERMANENT"});
                    if (query.contains("FROM dba_users")) return user
                            ? rows(new Object[]{accountStatus, common, "N", "PASSWORD", "USERS", privileged ? 1 : 0, 0,
                                granted ? 3 : 0, quotaBytes, foreignGrants, extraQuotas})
                            : rows();
                    if (query.contains("FROM dba_roles")) return rows(new Object[]{roleCollision ? 1 : 0});
                    if (query.contains("FROM dba_schema_privs")) {
                        if (version < 23) throw new SQLException("View does not exist on this version", "42000", 942);
                        return rows(new Object[]{schemaGrants});
                    }
                    if (query.contains("SYS_CONTEXT('USERENV', 'SESSION_USER')"))
                        return rows(new Object[]{"TAXONOMY_APP", "TAXONOMY_APP", pdb, granted ? 3 : 0, quotaBytes});
                    throw new AssertionError("Unexpected query: " + query);
                }
                if (method.equals("getInt")) return prepared.contains("DBMS_LOCK.REQUEST") ? lockResult : 4;
                if (method.equals("execute") || method.equals("executeUpdate")) {
                    String query = prepared != null ? prepared : (String) args[0];
                    if (query.contains("DBMS_LOCK.REQUEST")) { locks++; return false; }
                    if (query.contains("DBMS_LOCK.RELEASE")) return false;
                    writes.add(query);
                    if (query.contains("CREATE USER")) {
                        assertEquals("TAXONOMY_APP", parameters.get(1)); assertEquals(SECRET, parameters.get(2));
                        assertEquals("USERS", parameters.get(3)); assertEquals(100, parameters.get(4));
                        actions.add("user");
                        if (userFailure != null) throw userFailure;
                        user = true; quotaBytes = 100L * 1024 * 1024;
                        if (interruptAfterUser) Thread.currentThread().interrupt();
                    } else if (query.startsWith("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE")) {
                        actions.add("grant");
                        if (grantFailure != null) throw grantFailure;
                        granted = true;
                    } else throw new AssertionError("Unexpected write: " + query);
                    return method.equals("execute") ? false : 0;
                }
                return null;
            });
        }
    }

    private static ResultSet rows(Object[]... values) {
        int[] index = {-1};
        return proxy(ResultSet.class, (method, args) -> {
            if (method.equals("next")) return ++index[0] < values.length;
            if (method.startsWith("get")) return values[index[0]][(Integer) args[0] - 1];
            return null;
        });
    }

    @FunctionalInterface private interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("toString")) return "JDBC fixture";
            Object value = call.invoke(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        });
    }
}
