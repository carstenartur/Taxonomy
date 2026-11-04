package com.taxonomy.setup;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

import static com.taxonomy.setup.DatabaseProvisioningService.*;
import static org.junit.jupiter.api.Assertions.*;

/** The JDBC boundary is external; creation, ownership, retry and secret handling stay real. */
class MssqlProvisioningServiceTest {
    private static final String SECRET = "Private-App-Secret-2026!";

    @Test void inspectionIsReadOnlyAndShowsBothResourcesToCreate() {
        var db = new Database();
        var plan = service(db).inspect(input(false, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.READY, plan.outcome());
        assertEquals(ResourceState.CREATE, plan.role());
        assertEquals(ResourceState.CREATE, plan.database());
        assertTrue(db.writes.isEmpty());
        assertEquals(0, db.locks);
    }

    @Test void createsLoginThenDatabaseAndAssignsOnlyThatNewDatabase() {
        var db = new Database();
        var result = service(db).provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.COMPLETE, result.outcome());
        assertEquals(ResourceState.CREATED, result.role());
        assertEquals(ResourceState.CREATED, result.database());
        assertEquals(List.of("login", "database", "owner"), db.actions);
        assertEquals("taxonomy_app", db.owner);
        assertTrue(db.appConnections > 0);
        assertFalse(db.writes.toString().contains(SECRET));
        assertFalse(result.toString().contains(SECRET));
    }

    @Test void reusesExistingRestrictedOwnerWithoutPasswordOrOwnershipChanges() {
        var db = new Database(); db.login = db.database = true;
        var result = service(db).provision(input(true, ResourceState.REUSE, ResourceState.REUSE));
        assertEquals(Outcome.COMPLETE, result.outcome());
        assertEquals(ResourceState.REUSED, result.role());
        assertEquals(ResourceState.REUSED, result.database());
        assertTrue(db.writes.isEmpty());
    }

    @Test void conflictingDatabaseOwnerPreventsCreationOfMissingLogin() {
        var db = new Database(); db.database = true; db.owner = "unrelated_owner";
        assertEquals(Outcome.CONFLICT, service(db).provision(input(true, ResourceState.CREATE, ResourceState.REUSE)).outcome());
        assertTrue(db.writes.isEmpty());
    }

    @Test void changedPlanAndPrivilegedExistingLoginBlockAllWrites() {
        var drift = new Database(); drift.login = true;
        var result = service(drift).provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.CONFLICT, result.outcome());
        assertEquals("database.provision.plan-changed", result.finding().key());
        assertTrue(drift.writes.isEmpty());
        var privileged = new Database(); privileged.login = true; privileged.serverRole = true;
        assertEquals(Outcome.CONFLICT, service(privileged).inspect(input(false, ResourceState.REUSE, ResourceState.CREATE)).outcome());
        assertTrue(privileged.writes.isEmpty());
    }

    @Test void connectSqlWithGrantOptionIsAnAdditionalServerPrivilege() {
        var db = new Database(); db.login = true; db.extraServerPermission = true;
        assertEquals(Outcome.CONFLICT, service(db).inspect(input(false, ResourceState.REUSE, ResourceState.CREATE)).outcome());
        assertTrue(db.writes.isEmpty());
        // A deterministic JDBC fixture cannot execute SQL Server's catalog predicate.
        // Assert the query contract here; the real IT proves CONNECT SQL WITH GRANT
        // OPTION is actually selected by that predicate, without changing the login.
        String catalog = db.reads.stream().filter(sql -> sql.contains("FROM sys.server_principals")).findFirst().orElseThrow();
        assertTrue(catalog.matches("(?s).*s\\.state\\s*=\\s*'W'\\s+OR\\s*\\(.*"),
                "All WITH GRANT OPTION permissions must be rejected independently of the permission name.");
    }

    @Test void insufficientAdminOrUnsupportedAuthenticationBlocksBeforeWrites() {
        for (String condition : List.of("admin", "windows", "edition", "readonly")) {
            var db = new Database();
            switch (condition) {
                case "admin" -> db.sysadmin = false;
                case "windows" -> db.windowsOnly = true;
                case "edition" -> db.edition = 5;
                case "readonly" -> db.readonly = true;
            }
            assertEquals(Outcome.FAILED, service(db).provision(input(true, ResourceState.CREATE, ResourceState.CREATE)).outcome(), condition);
            assertTrue(db.writes.isEmpty());
        }
    }

    @Test void wrongExistingPasswordAndBusyLockDoNotCreateDatabase() {
        var password = new Database(); password.login = true; password.badPassword = true;
        assertEquals(Outcome.FAILED, service(password).provision(input(true, ResourceState.REUSE, ResourceState.CREATE)).outcome());
        assertTrue(password.writes.isEmpty());
        var busy = new Database(); busy.lockResult = -1;
        assertEquals(Outcome.BUSY, service(busy).provision(input(true, ResourceState.CREATE, ResourceState.CREATE)).outcome());
        assertTrue(busy.writes.isEmpty());
    }

    @Test void databaseFailureRetainsLoginAndAllowsReviewedReuseOnRetry() {
        var db = new Database(); db.databaseFailure = new SQLException(SECRET, "42000", 262);
        var service = service(db);
        var first = service.provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.PARTIAL, first.outcome());
        assertEquals(ResourceState.CREATED, first.role());
        assertEquals(ResourceState.NOT_CREATED, first.database());
        assertFalse(first.toString().contains(SECRET));
        db.databaseFailure = null;
        var plan = service.inspect(input(false, null, null));
        assertEquals(Outcome.READY, plan.outcome());
        assertEquals(Outcome.COMPLETE, service.provision(input(true, plan.role(), plan.database())).outcome());
        assertEquals(1, Collections.frequency(db.actions, "login"));
    }

    @Test void failedOwnerAssignmentLeavesCreatedResourcesAndNeverRepairsExistingDatabase() {
        var db = new Database(); db.ownerFailure = new SQLException(SECRET, "42000", 15151);
        var service = service(db);
        var first = service.provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.PARTIAL, first.outcome());
        assertEquals(ResourceState.CREATED, first.role());
        assertEquals(ResourceState.CREATED, first.database());
        db.ownerFailure = null;
        int writes = db.writes.size();
        assertEquals(Outcome.CONFLICT, service.inspect(input(false, null, null)).outcome());
        assertEquals(writes, db.writes.size());
    }

    @Test void connectionLossDuringCreateDoesNotClaimDatabaseAbsent() {
        for (SQLException failure : List.of(new SQLRecoverableException(SECRET, "08S01"), new SQLException(SECRET, "HY008"))) {
            var db = new Database(); db.databaseFailure = failure;
            var result = service(db).provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
            assertEquals(Outcome.UNKNOWN, result.outcome(), failure.getSQLState());
            assertEquals(ResourceState.CREATED, result.role());
            assertEquals(ResourceState.UNKNOWN, result.database());
            assertFalse(result.toString().contains(SECRET));
        }
    }

    @Test void rejectedPasswordPolicyIdentifiesThePasswordWithoutLeakingItOrCreatingDatabase() {
        var db = new Database(); db.loginFailure = new SQLException(SECRET, "42000", 15118);
        var result = service(db).provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals("database.provision.password-policy", result.finding().key());
        assertFalse(result.toString().contains(SECRET));
        assertFalse(db.database);
    }

    @Test void unsafeNamesAndSystemDatabasesAreRejectedBeforeConnecting() {
        for (String database : List.of("master", "model", "msdb", "tempdb", "name];DROP LOGIN sa;--")) {
            var db = new Database(); var request = input(false, null, null); var old = request.target();
            var target = new DatabaseSetupService.ConnectionSettings("mssql", old.host(), old.port(), database,
                    old.username(), old.password(), false, "");
            assertThrows(IllegalArgumentException.class, () -> service(db).inspect(new ProvisionSettings(target,
                    "master", "sa", "Private-Admin-Secret!", false, null, null)));
            assertEquals(0, db.connections);
        }
    }

    @Test void oracleOnlyFieldsCannotBeSilentlyIgnoredBySqlServerPreparation() {
        var original = input(false, null, null);
        for (boolean quotaOnly : List.of(false, true)) {
            var db = new Database();
            assertThrows(IllegalArgumentException.class, () -> service(db).inspect(new ProvisionSettings(original.target(),
                    "master", "sa", "Private-Admin-Secret!", false, null, null,
                    quotaOnly ? null : "USERS", quotaOnly ? 100 : null)));
            assertEquals(0, db.connections);
        }
    }

    @Test void cancellationAfterDatabaseCreationNeverBeginsOwnershipAssignment() {
        var db = new Database(); db.interruptAfterDatabase = true;
        var result = service(db).provision(input(true, ResourceState.CREATE, ResourceState.CREATE));
        assertEquals(Outcome.UNKNOWN, result.outcome());
        assertEquals(ResourceState.CREATED, result.database());
        assertEquals(List.of("login", "database"), db.actions);
    }

    private static DatabaseProvisioningService service(Database database) {
        return new DatabaseProvisioningService(new MssqlProvisioningService(database::connect));
    }

    private static ProvisionSettings input(boolean confirmed, ResourceState login, ResourceState database) {
        return new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("mssql", "db", 1433,
                "taxonomy", "taxonomy_app", SECRET, false, ""), "master", "sa", "Private-Admin-Secret!",
                confirmed, login, database);
    }

    private static final class Database {
        boolean login, database, serverRole, extraServerPermission, badPassword, readonly, windowsOnly, interruptAfterDatabase;
        boolean sysadmin = true;
        int edition = 3, connections, appConnections, locks, lockResult;
        String owner = "taxonomy_app";
        SQLException loginFailure, databaseFailure, ownerFailure;
        final List<String> writes = new ArrayList<>(), actions = new ArrayList<>(), reads = new ArrayList<>();

        Connection connect(String url, Properties properties) throws SQLException {
            connections++;
            boolean admin = "sa".equals(properties.getProperty("user"));
            if (!admin) {
                appConnections++;
                if (badPassword) throw new SQLException(SECRET, "28000", 18456);
            }
            String name = url.contains("databaseName=master") ? "master" : "taxonomy";
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "createStatement" -> sql(null, admin, name, Statement.class);
                case "prepareStatement" -> sql((String) args[0], admin, name, PreparedStatement.class);
                case "prepareCall" -> sql((String) args[0], admin, name, CallableStatement.class);
                case "getMetaData" -> proxy(DatabaseMetaData.class, (operation, parameters) -> operation.equals("getDatabaseMajorVersion") ? 16 : null);
                default -> null;
            });
        }

        Object sql(String prepared, boolean admin, String databaseName, Class<?> type) {
            Map<Integer, Object> parameters = new HashMap<>();
            return proxy(type, (method, args) -> {
                if (method.startsWith("set") && args != null && args.length == 2 && args[0] instanceof Integer index) {
                    parameters.put(index, args[1]); return null;
                }
                if (method.equals("executeQuery")) {
                    String query = prepared != null ? prepared : (String) args[0];
                    reads.add(query);
                    if (query.contains("IS_SRVROLEMEMBER('sysadmin')")) return rows(new Object[]{sysadmin, windowsOnly, edition, readonly});
                    if (query.contains("FROM sys.server_principals")) return login ? rows(new Object[]{"S", false, serverRole, extraServerPermission}) : rows();
                    if (query.contains("FROM sys.databases")) return database ? rows(new Object[]{owner, "ONLINE", false, false, false}) : rows();
                    if (query.contains("ORIGINAL_LOGIN()")) return rows(new Object[]{"taxonomy_app", databaseName, "dbo", true});
                    throw new AssertionError("Unexpected query: " + query);
                }
                if (method.equals("getInt")) return lockResult;
                if (method.equals("execute") || method.equals("executeUpdate")) {
                    String query = prepared != null ? prepared : (String) args[0];
                    if (query.contains("sp_getapplock")) { locks++; return false; }
                    if (query.contains("sp_releaseapplock")) return false;
                    writes.add(query);
                    if (query.contains("CREATE LOGIN")) {
                        assertEquals("taxonomy_app", parameters.get(1)); assertEquals(SECRET, parameters.get(2));
                        if (loginFailure != null) throw loginFailure;
                        login = true; actions.add("login");
                    } else if (query.startsWith("CREATE DATABASE")) {
                        actions.add("database");
                        if (databaseFailure != null) throw databaseFailure;
                        database = true; owner = "sa";
                        if (interruptAfterDatabase) Thread.currentThread().interrupt();
                    } else if (query.startsWith("ALTER AUTHORIZATION")) {
                        actions.add("owner");
                        if (ownerFailure != null) throw ownerFailure;
                        owner = "taxonomy_app";
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
