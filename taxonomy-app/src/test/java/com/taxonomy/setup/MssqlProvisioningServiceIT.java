package com.taxonomy.setup;

import com.taxonomy.security.service.LegacyOwnershipDatabaseContract;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

import java.sql.*;
import java.util.UUID;

import static com.taxonomy.setup.DatabaseProvisioningService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL Server syntax, authentication, database ownership and restricted server privileges. */
@Testcontainers
@Tag("db-mssql")
@Tag("docker-required")
class MssqlProvisioningServiceIT {
    @Container
    @SuppressWarnings("rawtypes")
    static final MSSQLServerContainer server = new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU18-ubuntu-22.04")
            .withPassword("A_Str0ng_Required_Password").acceptLicense();

    @Test void verifiedLegacyOwnershipOnAProvisionedDatabase() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String login = "owner_user_" + suffix;
        String database = "owner_db_" + suffix;
        String password = "Owner-Fixture-1!" + suffix;
        var settings = input(database, login, password);
        var service = new DatabaseProvisioningService(new MssqlProvisioningService());
        try {
            var provisioned = service.provision(reviewed(settings, service.inspect(settings)));
            assertEquals(Outcome.COMPLETE, provisioned.outcome(), provisioned.toString());
            LegacyOwnershipDatabaseContract.verify(new DriverManagerDataSource(
                    url(database), login, password), "bit");
        } finally { cleanup(database, login); }
    }

    @Test void createsAndReusesDedicatedLoginAndDatabaseOwner() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String login = "tax_app_" + suffix, database = "tax_db_" + suffix, password = "App-Ä-2026!" + suffix;
        var settings = input(database, login, password);
        var service = new DatabaseProvisioningService(new MssqlProvisioningService());
        try {
            var plan = service.inspect(settings);
            assertEquals(Outcome.READY, plan.outcome(), plan.toString());
            assertEquals(ResourceState.CREATE, plan.role());
            assertEquals(ResourceState.CREATE, plan.database());
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT 1 FROM sys.server_principals WHERE name = ?")) {
                statement.setString(1, login); try (var rows = statement.executeQuery()) { assertFalse(rows.next()); }
            }
            var prepared = service.provision(reviewed(settings, plan));
            assertEquals(Outcome.COMPLETE, prepared.outcome(), prepared.toString());
            assertEquals(ResourceState.CREATED, prepared.role()); assertEquals(ResourceState.CREATED, prepared.database());
            try (var app = DriverManager.getConnection(url(database), login, password); var sql = app.createStatement()) {
                try (var rows = sql.executeQuery("SELECT ORIGINAL_LOGIN(), USER_NAME(), DB_NAME()")) {
                    assertTrue(rows.next()); assertEquals(login, rows.getString(1));
                    assertEquals("dbo", rows.getString(2)); assertEquals(database, rows.getString(3));
                }
                sql.execute("CREATE TABLE provisioning_ddl_proof (id bigint PRIMARY KEY)");
                sql.execute("INSERT INTO provisioning_ddl_proof VALUES (1)");
                sql.execute("ALTER TABLE provisioning_ddl_proof ADD description nvarchar(255)");
                assertThrows(SQLException.class, () -> sql.execute("CREATE DATABASE forbidden_database_creation"));
            }
            try (var admin = admin(); var statement = admin.prepareStatement("""
                    SELECT p.type, p.is_disabled,
                           (SELECT COUNT(*) FROM sys.server_role_members r WHERE r.member_principal_id = p.principal_id),
                           (SELECT COUNT(*) FROM sys.server_permissions s WHERE s.grantee_principal_id = p.principal_id
                                 AND s.state = 'W'),
                           (SELECT COUNT(*) FROM sys.server_permissions s WHERE s.grantee_principal_id = p.principal_id
                                 AND s.state = 'G' AND s.permission_name <> 'CONNECT SQL')
                    FROM sys.server_principals p WHERE p.name = ?
                    """)) {
                statement.setString(1, login);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next()); assertEquals("S", rows.getString(1)); assertFalse(rows.getBoolean(2));
                    assertEquals(0, rows.getInt(3)); assertEquals(0, rows.getInt(4)); assertEquals(0, rows.getInt(5));
                }
            }
            var repeat = service.provision(reviewed(settings, service.inspect(settings)));
            assertEquals(Outcome.COMPLETE, repeat.outcome(), repeat.toString());
            assertEquals(ResourceState.REUSED, repeat.role()); assertEquals(ResourceState.REUSED, repeat.database());
            var wrongPassword = service.inspect(input(database, login, "Incorrect-Password-2026!"));
            assertEquals(Outcome.FAILED, wrongPassword.outcome());
        } finally { cleanup(database, login); }
    }

    @Test void existingForeignOwnedDatabaseDoesNotCauseLoginCreationOrOwnerChange() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String login = "tax_app_" + suffix, database = "tax_db_" + suffix;
        try (var admin = admin(); var sql = admin.createStatement()) { sql.execute("CREATE DATABASE [" + database + "]"); }
        try {
            var settings = input(database, login, "Restricted-App-2026!" + suffix);
            settings = new ProvisionSettings(settings.target(), "master", "sa", server.getPassword(),
                    true, ResourceState.CREATE, ResourceState.REUSE);
            var result = new DatabaseProvisioningService(new MssqlProvisioningService()).provision(settings);
            assertEquals(Outcome.CONFLICT, result.outcome(), result.toString());
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT 1 FROM sys.server_principals WHERE name = ?")) {
                statement.setString(1, login); try (var rows = statement.executeQuery()) { assertFalse(rows.next()); }
            }
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT SUSER_SNAME(owner_sid) FROM sys.databases WHERE name = ?")) {
                statement.setString(1, database); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals("sa", rows.getString(1)); }
            }
        } finally { cleanup(database, login); }
    }

    @Test void existingLoginWithGrantableConnectIsRefusedWithoutChangingItsPermission() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String login = "tax_app_" + suffix, database = "tax_db_" + suffix;
        String password = "Existing-App-2026!";
        try (var admin = admin(); var sql = admin.createStatement()) {
            sql.execute("CREATE LOGIN [" + login + "] WITH PASSWORD = N'Existing-App-2026!', CHECK_POLICY=ON, CHECK_EXPIRATION=OFF");
            sql.execute("GRANT CONNECT SQL TO [" + login + "] WITH GRANT OPTION");
        }
        try {
            var service = new DatabaseProvisioningService(new MssqlProvisioningService());
            var settings = input(database, login, password);
            assertEquals(Outcome.CONFLICT, service.inspect(settings).outcome());
            var reviewed = new ProvisionSettings(settings.target(), "master", "sa", server.getPassword(),
                    true, ResourceState.REUSE, ResourceState.CREATE);
            assertEquals(Outcome.CONFLICT, service.provision(reviewed).outcome());
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT 1 FROM sys.databases WHERE name = ?")) {
                statement.setString(1, database); try (var rows = statement.executeQuery()) { assertFalse(rows.next()); }
            }
            try (var admin = admin(); var statement = admin.prepareStatement("""
                    SELECT state FROM sys.server_permissions WHERE grantee_principal_id = SUSER_ID(?) AND permission_name = 'CONNECT SQL'
                    """)) {
                statement.setString(1, login);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals("W", rows.getString(1)); }
            }
            try (var app = DriverManager.getConnection(url("master"), login, password)) { assertTrue(app.isValid(5)); }
        } finally { cleanup(database, login); }
    }

    private static ProvisionSettings input(String database, String login, String password) {
        return new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("mssql", server.getHost(), server.getMappedPort(1433),
                database, login, password, false, ""), "master", "sa", server.getPassword(), false, null, null);
    }
    private static ProvisionSettings reviewed(ProvisionSettings input, ProvisionResult plan) {
        assertEquals(Outcome.READY, plan.outcome(), plan.toString());
        return new ProvisionSettings(input.target(), "master", "sa", server.getPassword(), true, plan.role(), plan.database());
    }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(url("master"), "sa", server.getPassword()); }
    private static String url(String database) {
        return "jdbc:sqlserver://" + server.getHost() + ":" + server.getMappedPort(1433)
                + ";databaseName=" + database + ";encrypt=false;loginTimeout=5;socketTimeout=20000";
    }
    private static void cleanup(String database, String login) throws SQLException {
        try (var admin = admin(); var sql = admin.createStatement()) {
            sql.execute("IF DB_ID(N'" + database + "') IS NOT NULL DROP DATABASE [" + database + "]");
            sql.execute("IF SUSER_ID(N'" + login + "') IS NOT NULL DROP LOGIN [" + login + "]");
        }
    }
}
