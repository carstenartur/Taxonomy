package com.taxonomy.setup;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.sql.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static com.taxonomy.setup.DatabaseProvisioningService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Oracle PDB/user semantics, bound DDL password, finite quota and own-schema privileges. */
@Testcontainers
@Tag("db-oracle")
@Tag("docker-required")
class OracleProvisioningServiceIT {
    @Container
    static final OracleContainer server = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("fixture_user").withPassword("Admin-Secret-2026!")
            .withStartupTimeout(Duration.ofMinutes(3));

    @BeforeAll static void permitOnlySetupAdministratorToUseSessionLock() throws SQLException {
        // Test-container DBA setup, not application provisioning. Production preparation
        // explicitly requires this permission and never grants it to either account.
        Properties sys = new Properties(); sys.setProperty("user", "sys");
        sys.setProperty("password", server.getPassword()); sys.setProperty("internal_logon", "sysdba");
        try (var connection = DriverManager.getConnection(server.getJdbcUrl(), sys); var sql = connection.createStatement()) {
            sql.execute("GRANT EXECUTE ON SYS.DBMS_LOCK TO SYSTEM");
        }
    }

    @Test void createsAndReusesLocalUserWithFiniteQuotaAndNoAdministrativeGrants() throws Exception {
        String user = "TAX_APP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        String password = "App-'Secret-2026!";
        var settings = input(user, password);
        var service = new DatabaseProvisioningService(new OracleProvisioningService());
        try {
            var plan = service.inspect(settings);
            assertEquals(Outcome.READY, plan.outcome(), plan.toString());
            assertEquals(ResourceState.CREATE, plan.role()); assertEquals(ResourceState.REUSE, plan.database());
            assertFalse(userExists(user));
            var created = service.provision(reviewed(settings, plan));
            assertEquals(Outcome.COMPLETE, created.outcome(), created.toString());
            assertEquals(ResourceState.CREATED, created.role()); assertEquals(ResourceState.REUSED, created.database());
            try (var app = DriverManager.getConnection(server.getJdbcUrl(), user, password); var sql = app.createStatement()) {
                sql.execute("CREATE TABLE provisioning_ddl_proof (id NUMBER PRIMARY KEY)");
                sql.execute("CREATE SEQUENCE provisioning_sequence");
                sql.execute("INSERT INTO provisioning_ddl_proof VALUES (provisioning_sequence.NEXTVAL)");
                sql.execute("ALTER TABLE provisioning_ddl_proof ADD description VARCHAR2(255)");
                assertThrows(SQLException.class, () -> sql.execute("CREATE USER forbidden_user IDENTIFIED BY ForbiddenSecret1"));
            }
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT privilege, admin_option FROM dba_sys_privs WHERE grantee = ?")) {
                statement.setString(1, user); var privileges = new HashSet<String>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) { privileges.add(rows.getString(1)); assertEquals("NO", rows.getString(2)); }
                }
                assertEquals(Set.of("CREATE SESSION", "CREATE TABLE", "CREATE SEQUENCE"), privileges);
            }
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT max_bytes FROM dba_ts_quotas WHERE username = ? AND tablespace_name = 'USERS'")) {
                statement.setString(1, user);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(100L * 1024 * 1024, rows.getLong(1)); }
            }
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT common, oracle_maintained FROM dba_users WHERE username = ?")) {
                statement.setString(1, user);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals("NO", rows.getString(1)); assertEquals("N", rows.getString(2)); }
            }
            var reused = service.provision(reviewed(settings, service.inspect(settings)));
            assertEquals(Outcome.COMPLETE, reused.outcome(), reused.toString());
            assertEquals(ResourceState.REUSED, reused.role()); assertEquals(ResourceState.REUSED, reused.database());
            assertEquals(Outcome.FAILED, service.inspect(input(user, "Wrong-Password-2026!")).outcome());
        } finally { cleanup(user); }
    }

    @Test void rootAndLimitedAdministratorCannotCreateLocalApplicationUser() throws Exception {
        String user = "TAX_APP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        var service = new DatabaseProvisioningService(new OracleProvisioningService());
        var original = input(user, "App-Secret-2026!");
        var rootTarget = new DatabaseSetupService.ConnectionSettings("oracle", server.getHost(), server.getMappedPort(1521),
                "FREE", user, original.target().password(), false, "");
        var root = new ProvisionSettings(rootTarget, "FREE", "SYSTEM", server.getPassword(),
                true, ResourceState.CREATE, ResourceState.REUSE, "USERS", 100);
        assertEquals(Outcome.FAILED, service.provision(root).outcome());
        var limited = new ProvisionSettings(original.target(), original.maintenanceDatabase(), server.getUsername(), server.getPassword(),
                true, ResourceState.CREATE, ResourceState.REUSE, "USERS", 100);
        assertEquals(Outcome.FAILED, service.provision(limited).outcome());
        assertFalse(userExists(user));
    }

    @Test void existingUserWithSchemaWideGrantIsRefusedWithoutChangingThatGrant() throws Exception {
        String user = "TAX_APP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        var service = new DatabaseProvisioningService(new OracleProvisioningService());
        var settings = input(user, "App-Secret-2026!");
        try {
            assertEquals(Outcome.COMPLETE, service.provision(reviewed(settings, service.inspect(settings))).outcome());
            try (var admin = admin(); var sql = admin.createStatement()) {
                sql.execute("GRANT SELECT ANY TABLE ON SCHEMA \"FIXTURE_USER\" TO \"" + user + "\"");
            }
            assertEquals(Outcome.CONFLICT, service.inspect(settings).outcome());
            var reuse = new ProvisionSettings(settings.target(), settings.maintenanceDatabase(), "SYSTEM", server.getPassword(),
                    true, ResourceState.REUSE, ResourceState.REUSE, "USERS", 100);
            assertEquals(Outcome.CONFLICT, service.provision(reuse).outcome());
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT COUNT(*) FROM dba_schema_privs WHERE grantee = ?")) {
                statement.setString(1, user);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
        } finally { cleanup(user); }
    }

    private static ProvisionSettings input(String user, String password) {
        return new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("oracle", server.getHost(), server.getMappedPort(1521),
                server.getDatabaseName(), user, password, false, ""), server.getDatabaseName(), "SYSTEM", server.getPassword(),
                false, null, ResourceState.REUSE, "USERS", 100);
    }
    private static ProvisionSettings reviewed(ProvisionSettings input, ProvisionResult plan) {
        assertEquals(Outcome.READY, plan.outcome(), plan.toString());
        return new ProvisionSettings(input.target(), input.maintenanceDatabase(), "SYSTEM", server.getPassword(),
                true, plan.role(), plan.database(), "USERS", 100);
    }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(server.getJdbcUrl(), "SYSTEM", server.getPassword()); }
    private static boolean userExists(String user) throws SQLException {
        try (var admin = admin(); var statement = admin.prepareStatement("SELECT 1 FROM dba_users WHERE username = ?")) {
            statement.setString(1, user); try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static void cleanup(String user) throws SQLException {
        if (userExists(user)) try (var admin = admin(); var sql = admin.createStatement()) { sql.execute("DROP USER \"" + user + "\" CASCADE"); }
    }
}
