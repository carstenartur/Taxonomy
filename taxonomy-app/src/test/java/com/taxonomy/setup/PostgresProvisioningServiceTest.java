package com.taxonomy.setup;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import com.taxonomy.setup.DatabaseProvisioningService.*;

/** Real service with a deterministic JDBC boundary; SQL writes and login attempts remain observable. */
class PostgresProvisioningServiceTest {
    private static DatabaseProvisioningService service() { return new DatabaseProvisioningService(new PostgresProvisioningService()); }
    private static DatabaseProvisioningService service(Connector connector) { return new DatabaseProvisioningService(new PostgresProvisioningService(connector)); }
    private static final String SECRET = "private-app-secret-2026";
    private static ProvisionSettings input(boolean confirmed) {
        return new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("postgres", "db", 5432,
                "taxonomy", "taxonomy_app", SECRET, false, ""), "postgres", "bootstrap", "private-admin-secret", confirmed, ResourceState.CREATE, ResourceState.CREATE);
    }
    private static ProvisionSettings reviewed(DatabaseProvisioningService service) {
        var request = input(false); var plan = service.inspect(request);
        assertEquals(Outcome.READY, plan.outcome());
        return actions(plan.role(), plan.database());
    }
    private static ProvisionSettings actions(ResourceState role, ResourceState database) {
        var request = input(true);
        return new ProvisionSettings(request.target(), request.maintenanceDatabase(), request.adminUsername(),
                request.adminPassword(), true, role, database);
    }
    @Test void changedReviewedActionsNeverCreateOrAdoptResourcesWithoutAnotherReview() {
        for (boolean disappear : List.of(true, false)) {
            for (boolean roleChanges : List.of(true, false)) {
                var db = new Database(); db.role = true; db.database = !roleChanges;
                if (roleChanges) db.role = disappear;
                else db.database = disappear;
                var service = service(db::connect);
                var reviewed = reviewed(service);
                if (roleChanges) db.role = !db.role; else db.database = !db.database;
                var result = service.provision(reviewed);
                assertEquals(Outcome.CONFLICT, result.outcome(), "disappear=" + disappear + " role=" + roleChanges);
                assertEquals("database.provision.plan-changed", result.finding().key());
                assertTrue(db.writes.isEmpty());
            }
        }
    }
    @Test void confirmedProvisionRequiresOnlyCreateOrReuseExpectedActionsBeforeConnecting() {
        var db = new Database(); var service = service(db::connect);
        assertThrows(IllegalArgumentException.class, () -> service.provision(actions(null, ResourceState.CREATE)));
        assertThrows(IllegalArgumentException.class, () -> service.provision(actions(ResourceState.CREATE, null)));
        for (var invalid : List.of(ResourceState.CREATED, ResourceState.REUSED, ResourceState.NOT_CREATED, ResourceState.UNKNOWN)) {
            assertThrows(IllegalArgumentException.class, () -> service.provision(actions(invalid, ResourceState.CREATE)));
            assertThrows(IllegalArgumentException.class, () -> service.provision(actions(ResourceState.CREATE, invalid)));
        }
        assertEquals(0, db.connections); assertTrue(db.writes.isEmpty());
    }
    @Test void inspectionPlansWithoutDdlAndCreationNeedsExplicitConfirmation() {
        var db = new Database(); var service = service(db::connect);
        var plan = service.inspect(input(false));
        assertEquals(Outcome.READY, plan.outcome()); assertEquals(ResourceState.CREATE, plan.role());
        assertEquals(ResourceState.CREATE, plan.database()); assertTrue(db.writes.isEmpty());
        assertEquals(Outcome.FAILED, service.provision(input(false)).outcome()); assertTrue(db.writes.isEmpty());
    }
    @Test void createsRestrictedScramRoleAndOwnedDatabaseThenChecksApplicationLogin() {
        var db = new Database();
        var result = service(db::connect).provision(input(true));
        assertEquals(Outcome.COMPLETE, result.outcome());
        assertEquals(ResourceState.CREATED, result.role()); assertEquals(ResourceState.CREATED, result.database());
        assertEquals(2, db.writes.size());
        assertTrue(db.writes.getFirst().contains("LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"));
        assertTrue(db.writes.getFirst().contains("SCRAM-SHA-256$"));
        assertTrue(db.writes.getLast().contains("OWNER \"taxonomy_app\" TEMPLATE template0"));
        assertTrue(db.appConnections > 0); assertFalse(db.writes.toString().contains(SECRET));
        assertFalse(result.toString().contains(SECRET)); assertFalse(input(true).toString().contains("private-"));
    }
    @Test void conflictingDatabasePreventsEvenTheMissingRoleFromBeingCreated() {
        var db = new Database(); db.database = true; db.owner = "someone_else";
        assertEquals(Outcome.CONFLICT, service(db::connect).provision(input(true)).outcome());
        assertTrue(db.writes.isEmpty());
    }
    @Test void existingRestrictedObjectsAreVerifiedAndReusedWithoutAnyWrites() {
        var db = new Database(); db.role = db.database = true;
        var service = service(db::connect);
        var result = service.provision(reviewed(service));
        assertEquals(Outcome.COMPLETE, result.outcome()); assertEquals(ResourceState.REUSED, result.role());
        assertEquals(ResourceState.REUSED, result.database()); assertTrue(db.writes.isEmpty());
    }
    @Test void wrongExistingPasswordAndPrivilegedRoleBlockAllWrites() {
        var badPassword = new Database(); badPassword.role = true; badPassword.badPassword = true;
        assertEquals(Outcome.FAILED, service(badPassword::connect).provision(actions(ResourceState.REUSE, ResourceState.CREATE)).outcome());
        assertTrue(badPassword.writes.isEmpty());
        var privileged = new Database(); privileged.role = true; privileged.privileged = true;
        assertEquals(Outcome.CONFLICT, service(privileged::connect).provision(input(true)).outcome());
        assertTrue(privileged.writes.isEmpty());
    }
    @Test void nonSuperuserCannotPromiseOwnershipOfANotYetCreatedRole() {
        var db = new Database(); db.superuser = false;
        assertEquals(Outcome.FAILED, service(db::connect).provision(input(true)).outcome());
        assertTrue(db.writes.isEmpty());
    }
    @Test void existingRoleCanBeUsedByNonSuperuserOnlyWhenSetRoleWorks() {
        var db = new Database(); db.superuser = false; db.role = true;
        var service = service(db::connect);
        assertEquals(Outcome.COMPLETE, service.provision(reviewed(service)).outcome());
        var denied = new Database(); denied.superuser = false; denied.role = true; denied.setRoleDenied = true;
        assertEquals(Outcome.FAILED, service(denied::connect).provision(actions(ResourceState.REUSE, ResourceState.CREATE)).outcome());
        assertTrue(denied.writes.isEmpty());
    }
    @Test void databaseFailureRetainsCreatedRoleAndNeverCleansUpOrLeaksExceptions() {
        var db = new Database(); db.createDatabaseFailure = new SQLException("private-admin-secret " + SECRET, "42501");
        var result = service(db::connect).provision(input(true));
        assertEquals(Outcome.PARTIAL, result.outcome()); assertEquals(ResourceState.CREATED, result.role());
        assertEquals(ResourceState.NOT_CREATED, result.database()); assertEquals(2, db.writes.size());
        assertFalse(result.toString().contains("private-"));
    }
    @Test void retryAfterPartialCreationReusesTheRoleWithoutRotatingItsPassword() {
        var db = new Database(); db.createDatabaseFailure = new SQLException(SECRET, "42501");
        var service = service(db::connect);
        assertEquals(Outcome.PARTIAL, service.provision(input(true)).outcome());
        db.createDatabaseFailure = null;
        var retry = service.provision(reviewed(service));
        assertEquals(Outcome.COMPLETE, retry.outcome()); assertEquals(ResourceState.REUSED, retry.role());
        assertEquals(1, db.writes.stream().filter(sql -> sql.startsWith("CREATE ROLE")).count());
        assertTrue(db.writes.stream().noneMatch(sql -> sql.startsWith("ALTER") || sql.startsWith("DROP")));
    }
    @Test void postCreateApplicationLoginFailureReportsPartialInsteadOfComplete() {
        var db = new Database(); db.badPassword = true;
        var result = service(db::connect).provision(input(true));
        assertEquals(Outcome.PARTIAL, result.outcome());
        assertEquals(ResourceState.CREATED, result.role()); assertEquals(ResourceState.CREATED, result.database());
        assertFalse(result.toString().contains(SECRET));
    }
    @Test void lostConnectionDuringCreateReportsUncertainOutcomeInsteadOfRollback() {
        var db = new Database(); db.createDatabaseFailure = new SQLException(SECRET, "08006");
        var result = service(db::connect).provision(input(true));
        assertEquals(Outcome.UNKNOWN, result.outcome()); assertEquals(ResourceState.UNKNOWN, result.database());
        assertFalse(result.toString().contains(SECRET));
    }
    @Test void statementCompletionUnknownAndServerTerminationPreserveEachActiveResource() {
        for (String state : List.of("40003", "57P01", "57P02")) {
            var roleFailure = new Database(); roleFailure.createRoleFailure = new SQLException(SECRET, state);
            var roleResult = service(roleFailure::connect).provision(input(true));
            assertEquals(Outcome.UNKNOWN, roleResult.outcome(), "role state " + state);
            assertEquals(ResourceState.UNKNOWN, roleResult.role());
            assertEquals(ResourceState.NOT_CREATED, roleResult.database());
            assertEquals(1, roleFailure.writes.size());
            assertFalse(roleResult.toString().contains(SECRET));
            var databaseFailure = new Database(); databaseFailure.createDatabaseFailure = new SQLException(SECRET, state);
            var databaseResult = service(databaseFailure::connect).provision(input(true));
            assertEquals(Outcome.UNKNOWN, databaseResult.outcome(), "database state " + state);
            assertEquals(ResourceState.CREATED, databaseResult.role());
            assertEquals(ResourceState.UNKNOWN, databaseResult.database());
            assertEquals(2, databaseFailure.writes.size());
            assertFalse(databaseResult.toString().contains(SECRET));
        }
    }
    @Test void genericOuterSqlStateCannotHideUnknownCompletionInCauseOrNextException() {
        for (boolean nextException : List.of(false, true)) {
            var outer = new SQLException(SECRET, "HY000");
            var inner = new SQLException(SECRET, "40003");
            if (nextException) outer.setNextException(inner); else outer.initCause(inner);
            inner.initCause(outer); // The traversal must terminate even with cycles.
            var db = new Database(); db.createDatabaseFailure = outer;
            var result = service(db::connect).provision(input(true));
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals(ResourceState.UNKNOWN, result.database());
            assertEquals(ResourceState.CREATED, result.role()); assertEquals(2, db.writes.size());
            assertFalse(result.toString().contains(SECRET));
        }
    }
    @Test void leadingUnderscoreDatabaseNameIsAcceptedByReadOnlyInspection() {
        var db = new Database(); var original = input(false);
        var target = new DatabaseSetupService.ConnectionSettings("postgres", "db", 5432,
                "_taxonomy", "taxonomy_app", SECRET, false, "");
        var result = service(db::connect).inspect(new ProvisionSettings(target,
                "postgres", "bootstrap", original.adminPassword(), false, null, null));
        assertEquals(Outcome.READY, result.outcome()); assertEquals(ResourceState.CREATE, result.database());
        assertTrue(db.writes.isEmpty());
    }
    @Test void cancellationDuringCreateRoleRetainsPermitsAndNeverStartsCreateDatabase() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var exited = new CountDownLatch(2);
        var firstDb = new Database(); var secondDb = new Database();
        for (var db : List.of(firstDb, secondDb)) {
            db.roleCreateEntered = entered; db.roleCreateRelease = release; db.connectionClosed = exited;
        }
        var results = new CopyOnWriteArrayList<ProvisionResult>();
        Thread first = Thread.ofVirtual().start(() -> results.add(service(firstDb::connect).provision(input(true))));
        Thread second = Thread.ofVirtual().start(() -> results.add(service(secondDb::connect).provision(input(true))));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            first.interrupt(); second.interrupt(); first.join(3000); second.join(3000);
            assertEquals(2, results.size());
            for (var result : results) {
                assertEquals(Outcome.UNKNOWN, result.outcome()); assertEquals(ResourceState.UNKNOWN, result.role());
                assertEquals(ResourceState.NOT_CREATED, result.database());
            }
            var available = service(new Database()::connect);
            assertEquals(Outcome.BUSY, available.inspect(input(false)).outcome());
            release.countDown();
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertEquals(1, firstDb.writes.size()); assertEquals(1, secondDb.writes.size());
            assertTrue(firstDb.writes.getFirst().startsWith("CREATE ROLE"));
            assertTrue(secondDb.writes.getFirst().startsWith("CREATE ROLE"));
        } finally {
            release.countDown(); first.interrupt(); second.interrupt();
            var recovery = service(new Database()::connect);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (recovery.inspect(input(false)).outcome() == Outcome.BUSY && System.nanoTime() < deadline) Thread.sleep(10);
        }
    }
    @Test void lockContentionAndOldOrReadOnlyServersDoNotWrite() {
        var db = new Database(); db.lock = false;
        assertEquals(Outcome.BUSY, service(db::connect).provision(input(true)).outcome());
        db.lock = true; db.version = 14;
        assertEquals(Outcome.FAILED, service(db::connect).inspect(input(false)).outcome());
        db.version = 18; db.readonly = true;
        assertEquals(Outcome.FAILED, service(db::connect).provision(input(true)).outcome());
        assertTrue(db.writes.isEmpty());
    }
    @Test void interruptedCallersDoNotReleasePermitsWhileJdbcIsStillRunning() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var results = new java.util.concurrent.CopyOnWriteArrayList<ProvisionResult>();
        var service = service((url, properties) -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; } catch (InterruptedException ignored) { /* Simulate stuck JDBC. */ }
            }
            throw new SQLException(SECRET, "08006");
        });
        Thread first = Thread.ofVirtual().start(() -> results.add(service.inspect(input(false))));
        Thread second = Thread.ofVirtual().start(() -> results.add(service.inspect(input(false))));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            first.interrupt(); second.interrupt(); first.join(3000); second.join(3000);
            assertEquals(2, results.size());
            assertEquals(Outcome.BUSY, service.inspect(input(false)).outcome());
        } finally {
            release.countDown(); first.interrupt(); second.interrupt();
            var recovery = service(new Database()::connect);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (recovery.inspect(input(false)).outcome() == Outcome.BUSY && System.nanoTime() < deadline) Thread.sleep(10);
        }
    }
    @Test void invalidNamesFailBeforeOpeningAnAdministrativeConnection() {
        var db = new Database();
        for (String name : List.of("postgres", "template0", "pg_internal", "MixedCase", "bad;sql", "a".repeat(64))) {
            var target = new DatabaseSetupService.ConnectionSettings("postgres", "db", 5432, name, "taxonomy_app", SECRET, false, "");
            assertThrows(IllegalArgumentException.class, () -> service(db::connect)
                    .inspect(new ProvisionSettings(target, "postgres", "bootstrap", "secret", false, null, null)));
        }
        assertEquals(0, db.connections);
    }

    private static final class Database {
        boolean role, database, privileged, badPassword, setRoleDenied, readonly;
        boolean superuser = true, lock = true; int version = 18, connections, appConnections;
        String owner = "taxonomy_app"; SQLException createRoleFailure, createDatabaseFailure; List<String> writes = new ArrayList<>();
        CountDownLatch roleCreateEntered, roleCreateRelease, connectionClosed;
        Connection connect(String url, Properties props) throws SQLException {
            connections++; boolean admin = props.getProperty("user").equals("bootstrap");
            if (!admin) { appConnections++; if (badPassword) throw new SQLException(SECRET, "28P01"); }
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "close" -> { if (admin && connectionClosed != null) connectionClosed.countDown(); yield null; }
                case "setAutoCommit", "setReadOnly" -> null;
                case "isValid", "getAutoCommit" -> true;
                case "getMetaData" -> proxy(DatabaseMetaData.class, (m, a) -> switch (m) {
                    case "getDatabaseMajorVersion" -> version; case "getDatabaseProductName" -> "PostgreSQL";
                    default -> throw new AssertionError(m);
                });
                case "createStatement" -> statement(null, admin);
                case "prepareStatement" -> statement((String) args[0], admin);
                default -> throw new AssertionError(method);
            });
        }
        Object statement(String prepared, boolean admin) {
            Class<? extends Statement> type = prepared == null ? Statement.class : PreparedStatement.class;
            return proxy(type, (method, args) -> {
                if (Set.of("close", "setQueryTimeout", "setString").contains(method)) return null;
                String sql = prepared == null ? (String) args[0] : prepared;
                if (method.equals("executeQuery")) {
                    if (sql.contains("pg_try_advisory_lock")) return rows(new Object[]{lock});
                    if (sql.contains("pg_advisory_unlock")) return rows(new Object[]{true});
                    if (sql.contains("pg_is_in_recovery")) return rows(new Object[]{readonly});
                    if (sql.contains("rolname = current_user")) return rows(new Object[]{superuser, true, true});
                    if (sql.contains("FROM pg_roles r WHERE rolname = ?")) return role
                            ? rows(new Object[]{true, privileged, false, false, false, false, false}) : rows();
                    if (sql.contains("current_database()")) return rows(new Object[]{"taxonomy_app", sql.contains("has_schema_privilege") ? "taxonomy" : "postgres", true, true, true});
                    if (sql.contains("FROM pg_database")) return database ? rows(new Object[]{owner, true, false}) : rows();
                    throw new AssertionError(sql);
                }
                if (method.equals("execute")) {
                    if (sql.startsWith("SET ROLE") && setRoleDenied) throw new SQLException(SECRET, "42501");
                    if (sql.startsWith("CREATE ROLE")) {
                        writes.add(sql);
                        if (roleCreateEntered != null) {
                            roleCreateEntered.countDown(); boolean done = false;
                            while (!done) {
                                try { roleCreateRelease.await(); done = true; }
                                catch (InterruptedException ignored) { /* Driver continues despite cancellation. */ }
                            }
                        }
                        if (createRoleFailure != null) throw createRoleFailure;
                        role = true;
                    }
                    if (sql.startsWith("CREATE DATABASE")) { writes.add(sql); if (createDatabaseFailure != null) throw createDatabaseFailure; database = true; }
                    return false;
                }
                throw new AssertionError(method);
            });
        }
        ResultSet rows(Object[]... values) {
            int[] cursor = {-1};
            return proxy(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> ++cursor[0] < values.length;
                case "getBoolean", "getString", "getInt" -> values[cursor[0]][(Integer) args[0] - 1];
                case "close" -> null;
                default -> throw new AssertionError(method);
            });
        }
    }
    interface Invocation { Object invoke(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (p, method, args) -> invocation.invoke(method.getName(), args));
    }
}
