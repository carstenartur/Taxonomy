package com.taxonomy.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import com.taxonomy.setup.PostgresProvisioningService.*;

/** Actual PostgreSQL 18 SQL, SCRAM authentication and least-privilege proof. */
@Testcontainers
@Tag("db-postgres")
@Tag("docker-required")
class PostgresProvisioningServiceIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("postgres").withUsername("bootstrap").withPassword("bootstrap-integration-password")
            .withCommand("postgres", "-c", "log_statement=all");

    @Test void createsThenReusesRestrictedAccountAndOwnedDatabaseWithoutPlaintextSql() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String role = "tax_user_" + suffix, database = "tax_db_" + suffix, password = "Secret-ö-'\\-" + suffix;
        var settings = input(database, role, password);
        var service = new PostgresProvisioningService();
        try {
            assertEquals(Outcome.READY, service.inspect(settings).outcome());
            try (var admin = admin(); var check = admin.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
                check.setString(1, role); try (var rows = check.executeQuery()) { assertFalse(rows.next()); }
            }
            var first = service.provision(reviewed(service, settings));
            assertEquals(Outcome.COMPLETE, first.outcome(), first.toString());
            assertEquals(ResourceState.CREATED, first.role()); assertEquals(ResourceState.CREATED, first.database());
            try (var app = DriverManager.getConnection(url(database), role, password); var statement = app.createStatement()) {
                statement.execute("CREATE TABLE provisioning_ddl_proof (id bigint PRIMARY KEY)");
                statement.execute("INSERT INTO provisioning_ddl_proof VALUES (1)");
                statement.execute("ALTER TABLE provisioning_ddl_proof ADD COLUMN description text");
            }
            try (var admin = admin(); var check = admin.prepareStatement(
                    "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
                check.setString(1, role); try (var rows = check.executeQuery()) {
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                    for (int i = 2; i <= 6; i++) assertFalse(rows.getBoolean(i));
                }
            }
            var again = service.provision(reviewed(service, settings));
            assertEquals(Outcome.COMPLETE, again.outcome()); assertEquals(ResourceState.REUSED, again.role());
            assertEquals(ResourceState.REUSED, again.database());
            var wrongInput = input(database, role, "wrong-password");
            var wrong = service.provision(new ProvisionSettings(wrongInput.target(), "postgres", "bootstrap",
                    postgres.getPassword(), true, ResourceState.REUSE, ResourceState.REUSE));
            assertEquals(Outcome.FAILED, wrong.outcome());
            assertFalse(postgres.getLogs().contains(password));
            assertTrue(postgres.getLogs().contains("SCRAM-SHA-256$"));
        } finally {
            try (var admin = admin(); var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
                statement.execute("DROP ROLE IF EXISTS \"" + role + "\"");
            }
        }
    }
    @Test void nonSuperuserWithCreateRoleAndCreateDbCannotCreateUnverifiedOwnerRole() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String limited = "limited_" + suffix, app = "app_" + suffix, db = "db_" + suffix;
        try (var admin = admin(); var statement = admin.createStatement()) {
            statement.execute("CREATE ROLE \"" + limited + "\" LOGIN CREATEROLE CREATEDB PASSWORD 'limited-test-password'");
        }
        try {
            var settings = input(db, app, "app-test-password");
            settings = new ProvisionSettings(settings.target(), "postgres", limited, "limited-test-password", true, ResourceState.CREATE, ResourceState.CREATE);
            var result = new PostgresProvisioningService().provision(settings);
            assertEquals(Outcome.FAILED, result.outcome()); assertEquals("database.provision.permission", result.finding().key());
            try (var admin = admin(); var statement = admin.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
                statement.setString(1, app); try (var rows = statement.executeQuery()) { assertFalse(rows.next()); }
            }
        } finally {
            try (var admin = admin(); var statement = admin.createStatement()) { statement.execute("DROP ROLE \"" + limited + "\""); }
        }
    }
    private static ProvisionSettings input(String database, String role, String password) {
        return new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("postgres", postgres.getHost(),
                postgres.getMappedPort(5432), database, role, password, false, ""), "postgres", "bootstrap", postgres.getPassword(), true, ResourceState.CREATE, ResourceState.CREATE);
    }
    private static ProvisionSettings reviewed(PostgresProvisioningService service, ProvisionSettings input) {
        var plan = service.inspect(input); assertEquals(Outcome.READY, plan.outcome(), plan.toString());
        return new ProvisionSettings(input.target(), input.maintenanceDatabase(), input.adminUsername(), input.adminPassword(),
                true, plan.role(), plan.database());
    }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(url("postgres"), "bootstrap", postgres.getPassword()); }
    private static String url(String database) { return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database + "?sslmode=disable"; }
}
