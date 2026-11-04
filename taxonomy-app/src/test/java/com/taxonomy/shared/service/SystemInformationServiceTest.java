package com.taxonomy.shared.service;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.HSQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.StandardBasicTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SystemInformationServiceTest {
    @TempDir Path temporary;

    @Test
    void memoryDatabaseReportsNativeVersionStorageAndApplicationLifetime() throws Exception {
        String url = "jdbc:hsqldb:mem:" + UUID.randomUUID();
        try (SessionFactory factory = factory(url)) {
            var snapshot = new SystemInformationService(factory, environment()).snapshot();
            assertThat(snapshot.database().versionSource()).isEqualTo("DATABASE_QUERY");
            assertThat(snapshot.database().storageSource()).isEqualTo("DATABASE_QUERY");
            // HSQL pads the CASE expression to CHAR width; the service must normalize it.
            assertThat(snapshot.database().storage()).isEqualTo("IN_MEMORY");
            assertThat(snapshot.database().lifetime()).isEqualTo("APPLICATION_PROCESS");
            assertThat(snapshot.database().warnings()).containsExactly("IN_MEMORY_APPLICATION_PROCESS");
            assertThat(snapshot.runtime().availableProcessors()).isPositive();
            assertThat(snapshot.runtime().heapMaxBytes()).isPositive();
            assertThat(snapshot.instanceId()).isNotBlank();
            assertThat(snapshot.disks()).hasSize(1);
            assertThat(snapshot.toString()).doesNotContain("jdbc:", url, temporary.toString());
            try (var connection = DriverManager.getConnection(url, "SA", "")) {
                assertThat(snapshot.database().version())
                        .isEqualTo(connection.getMetaData().getDatabaseProductVersion());
            }
        }
    }

    @Test
    void identityDescribesTheActiveSessionRatherThanTheConfiguredWizardTarget() throws Exception {
        String url = "jdbc:hsqldb:mem:" + UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "SA", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER \"runtime_user\" PASSWORD 'runtime-password'");
            statement.execute("CREATE SCHEMA \"runtime_schema\" AUTHORIZATION \"runtime_user\"");
            statement.execute("ALTER USER \"runtime_user\" SET INITIAL SCHEMA \"runtime_schema\"");
        }
        try (SessionFactory factory = factory(url, "runtime_user", "runtime-password")) {
            var env = environment().withProperty("spring.datasource.username", "draft-user")
                    .withProperty("spring.datasource.url", "jdbc:postgresql://draft-host/draft-database");
            var snapshot = new SystemInformationService(factory, env).snapshot();
            var database = JsonMapper.builder().build().valueToTree(snapshot.database());
            assertThat(database.path("identity").has("user")).isTrue();
            assertThat(database.path("identity").path("user").textValue()).isEqualTo("runtime_user");
            assertThat(database.path("identity").has("sessionUser")).isTrue();
            assertThat(database.path("identity").path("sessionUser").textValue()).isEqualTo("runtime_user");
            assertThat(database.path("identity").path("database").textValue()).isEqualTo("PUBLIC");
            assertThat(database.path("identity").path("schema").textValue()).isEqualTo("runtime_schema");
            assertThat(database.path("identity").path("status").textValue()).isEqualTo("AVAILABLE");
            assertThat(database.path("identity").path("source").textValue()).isEqualTo("DATABASE_QUERY");
            assertThat(database.path("engineStart").path("status").textValue()).isEqualTo("NOT_SUPPORTED");
            assertThat(database.path("engineStart").path("value").isNull()).isTrue();
            assertThat(database.path("engineStart").path("timeBasis").isNull()).isTrue();
            assertThat(snapshot.runtime().startTime()).isNotNull();
            assertThat(snapshot.toString()).doesNotContain("draft-user", "draft-host", "draft-database", "runtime-password");
        }
    }

    @Test
    void optionalStartProbeFailuresLeaveTheMeasuredIdentityAndPersistenceIntact() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.ENGINE_START, "cast(1 / 0 as varchar(60))");
            var database = new SystemInformationService(factory, environment()).database();
            assertThat(database.engineStart().status()).isEqualTo("UNAVAILABLE");
            assertThat(database.engineStart().value()).isNull();
            assertThat(database.engineStart().timeBasis()).isNull();
            assertThat(database.engineStart().source()).isEqualTo("UNAVAILABLE");
            assertThat(database.identity().status()).isEqualTo("AVAILABLE");
            assertThat(database.identity().user()).isEqualTo("SA");
            assertThat(database.status()).isEqualTo("AVAILABLE");
            assertThat(database.versionSource()).isEqualTo("DATABASE_QUERY");
            assertThat(database.storage()).isEqualTo("IN_MEMORY");
            assertThat(database.warnings()).containsExactly("IN_MEMORY_APPLICATION_PROCESS");
        }
    }

    @Test
    void failedIdentityDoesNotDiscardIndependentCoreDatabaseMetadata() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.CURRENT_USER, "cast(1 / 0 as varchar(60))");
            var database = new SystemInformationService(factory, environment()).database();
            assertThat(database.identity().status()).isEqualTo("UNAVAILABLE");
            assertThat(database.identity().source()).isEqualTo("UNAVAILABLE");
            assertThat(database.identity().user()).isNull();
            assertThat(database.status()).isEqualTo("AVAILABLE");
            assertThat(database.versionSource()).isEqualTo("DATABASE_QUERY");
            assertThat(database.storage()).isEqualTo("IN_MEMORY");
            assertThat(database.engineStart().status()).isEqualTo("NOT_SUPPORTED");
        }
    }

    @Test
    void unavailableSchemaDoesNotHideTheMeasuredUserAndDatabase() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.CURRENT_SCHEMA, "cast(null as varchar(60))");
            var identity = new SystemInformationService(factory, environment()).database().identity();
            assertThat(identity.status()).isEqualTo("PARTIAL");
            assertThat(identity.source()).isEqualTo("DATABASE_QUERY");
            assertThat(identity.user()).isEqualTo("SA");
            assertThat(identity.database()).isEqualTo("PUBLIC");
            assertThat(identity.schema()).isNull();
        }
    }

    @Test
    void effectivePrincipalAndSessionUserKeepTheirSeparateMeanings() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.CURRENT_USER, "'delegated_role'");
            var identity = new SystemInformationService(factory, environment()).database().identity();
            assertThat(identity.user()).isEqualTo("delegated_role");
            assertThat(identity.sessionUser()).isEqualTo("SA");
            assertThat(identity.source()).isEqualTo("DATABASE_QUERY");
        }
    }

    @Test
    void offsetStartIsNormalizedButServerLocalTimeIsNeverGivenAnInventedTimezone() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.ENGINE_START, "'2026-07-01T14:20:30+02:00'");
            var instant = new SystemInformationService(factory, environment()).database().engineStart();
            assertThat(instant.status()).isEqualTo("AVAILABLE");
            assertThat(instant.value()).isEqualTo("2026-07-01T12:20:30Z");
            assertThat(instant.timeBasis()).isEqualTo("UTC");
            assertThat(instant.source()).isEqualTo("DATABASE_QUERY");
        }
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.ENGINE_START, "'2026-07-01T14:20:30.123'");
            var local = new SystemInformationService(factory, environment()).database().engineStart();
            assertThat(local.status()).isEqualTo("AVAILABLE");
            assertThat(local.value()).isEqualTo("2026-07-01T14:20:30.123");
            assertThat(local.timeBasis()).isEqualTo("SERVER_LOCAL_TIME");
            assertThat(local.source()).isEqualTo("DATABASE_QUERY");
        }
    }

    @Test
    void invalidStartValuesDoNotReachTheUiOrReplaceTheApplicationStart() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            diagnosticExpression(factory, DatabaseDiagnosticFunctions.ENGINE_START, "'not-a-date private-error-detail'");
            var snapshot = new SystemInformationService(factory, environment()).snapshot();
            assertThat(snapshot.database().engineStart().status()).isEqualTo("UNAVAILABLE");
            assertThat(snapshot.database().engineStart().value()).isNull();
            assertThat(snapshot.runtime().startTime()).isNotNull();
            assertThat(snapshot.toString()).doesNotContain("private-error-detail");
        }
    }

    @Test
    void onlyUnambiguousVendorPermissionErrorsAreReportedAsDenied() {
        assertThat(SystemInformationService.diagnosticFailure(new RuntimeException(
                new SQLException("secret jdbc:postgresql://private", "42501")), new PostgreSQLDialect()))
                .isEqualTo("NOT_PERMITTED");
        for (int code : new int[]{229, 297, 300}) {
            assertThat(SystemInformationService.diagnosticFailure(new SQLException("private SQL error", "42000", code),
                    new SQLServerDialect())).isEqualTo("NOT_PERMITTED");
        }
        assertThat(SystemInformationService.diagnosticFailure(new SQLException("private Oracle error", "42000", 1031),
                new OracleDialect())).isEqualTo("NOT_PERMITTED");
        // These codes can mean a missing object, not just a missing privilege.
        assertThat(SystemInformationService.diagnosticFailure(new SQLException("private Oracle error", "42000", 942),
                new OracleDialect())).isEqualTo("UNAVAILABLE");
        assertThat(SystemInformationService.diagnosticFailure(new SQLException("private HSQL error", "42501", -5501),
                new HSQLDialect())).isEqualTo("UNAVAILABLE");
        assertThat(SystemInformationService.diagnosticFailure(new SQLException("connection timed out", "08006"),
                new PostgreSQLDialect())).isEqualTo("UNAVAILABLE");
    }

    @Test
    void fileDatabaseRetainsCommittedContentButDoesNotClaimVolumeDurability() throws Exception {
        String url = "jdbc:hsqldb:file:" + temporary.resolve("database");
        try (var connection = DriverManager.getConnection(url, "SA", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE qa_persistence_probe (value INTEGER)");
            statement.execute("INSERT INTO qa_persistence_probe VALUES (42)");
            statement.execute("SHUTDOWN");
        }
        try (SessionFactory factory = factory(url)) {
            var service = new SystemInformationService(factory, environment());
            var snapshot = service.snapshot();
            assertThat(snapshot.database().storage()).isEqualTo("FILE_BACKED");
            assertThat(snapshot.database().storageSource()).isEqualTo("DATABASE_QUERY");
            assertThat(snapshot.database().schemaAction()).isEqualTo("NONE");
            assertThat(snapshot.database().warnings()).containsExactly("STORAGE_DURABILITY_UNVERIFIED");
            try (var session = factory.openSession()) {
                assertThat(session.createNativeQuery("SELECT value FROM qa_persistence_probe", Integer.class)
                        .getSingleResult()).isEqualTo(42);
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, "SA", "");
                 var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    @Test
    void nativeVersionFailureHasAnExplicitMetadataFallback() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            var internal = factory.unwrap(SessionFactoryImplementor.class);
            var text = internal.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);
            internal.getQueryEngine().getSqmFunctionRegistry()
                    .patternDescriptorBuilder(DatabaseDiagnosticFunctions.VERSION, "cast(null as varchar(30))")
                    .setExactArgumentCount(0).setInvariantType(text).register();
            var database = new SystemInformationService(factory, environment()).snapshot().database();
            assertThat(database.version()).isNotBlank();
            assertThat(database.versionSource()).isEqualTo("JDBC_METADATA_FALLBACK");
            assertThat(database.storage()).isEqualTo("IN_MEMORY");
            assertThat(database.status()).isEqualTo("PARTIAL");
        }
    }

    @Test
    void jpaCreateIsNotMistakenForHibernateDropAndCreate() {
        assertThat(SystemInformationService.schemaAction(Map.of("hibernate.hbm2ddl.auto", "create")))
                .isEqualTo("CREATE");
        assertThat(SystemInformationService.schemaAction(Map.of(
                "hibernate.hbm2ddl.auto", "create", "jakarta.persistence.schema-generation.database.action", "create")))
                .isEqualTo("CREATE_ONLY");
        assertThat(SystemInformationService.destructive("CREATE")).isTrue();
        assertThat(SystemInformationService.destructive("CREATE_DROP")).isTrue();
        assertThat(SystemInformationService.destructive("DROP")).isTrue();
        assertThat(SystemInformationService.destructive("TRUNCATE")).isTrue();
        assertThat(SystemInformationService.destructive("CREATE_ONLY")).isFalse();
        assertThat(SystemInformationService.destructive("VALIDATE")).isFalse();
        assertThat(SystemInformationService.destructive("UPDATE")).isFalse();
        assertThat(SystemInformationService.schemaAction(Map.of("hibernate.hbm2ddl.auto", "unexpected")))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void remoteHsqlConnectionIsNotMistakenForAnEmbeddedDatabase() {
        assertThat(SystemInformationService.connectionStorage("jdbc:hsqldb:hsql://localhost/catalogue"))
                .isEqualTo("UNKNOWN");
        assertThat(SystemInformationService.connectionStorage("jdbc:hsqldb:mem:test"))
                .isEqualTo("IN_MEMORY");
        assertThat(SystemInformationService.connectionStorage("jdbc:postgresql://db/taxonomy"))
                .isEqualTo("SERVER_MANAGED");
        assertThat(SystemInformationService.connectionStorage(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void closedDatabaseDoesNotHideRuntimeInformationOrInventPersistence() {
        SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID());
        factory.close();
        var snapshot = new SystemInformationService(factory, environment()).snapshot();
        assertThat(snapshot.runtime().availableProcessors()).isPositive();
        assertThat(snapshot.database().storage()).isEqualTo("UNKNOWN");
        assertThat(snapshot.database().warnings()).contains("DATABASE_DIAGNOSTICS_UNAVAILABLE", "PERSISTENCE_UNKNOWN");
        assertThat(snapshot.database().version()).isNull();
    }

    @Test
    void filesystemsAreDeduplicatedAndMissingIndexDirectoriesAreNotCreated() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            var env = environment().withProperty("spring.jpa.properties.hibernate.search.backend.directory.type", "local-filesystem")
                    .withProperty("spring.jpa.properties.hibernate.search.backend.directory.root", System.getProperty("java.io.tmpdir"));
            var snapshot = new SystemInformationService(factory, env).snapshot();
            assertThat(snapshot.disks()).hasSize(1);
            assertThat(snapshot.disks().getFirst().purposes()).containsExactly("TEMPORARY_FILES", "SEARCH_INDEX");
            Path missing = temporary.resolve("must-not-be-created");
            env.withProperty("spring.jpa.properties.hibernate.search.backend.directory.root", missing.toString());
            assertThat(new SystemInformationService(factory, env).snapshot().disks())
                    .anySatisfy(disk -> {
                        assertThat(disk.purposes()).contains("SEARCH_INDEX");
                        assertThat(disk.status()).isEqualTo("UNAVAILABLE");
                        assertThat(disk.usableBytes()).isNull();
                    });
            assertThat(missing).doesNotExist();
        }
    }

    @Test
    void unsupportedIndexStorageIsNormalizedBeforeItReachesTheUi() {
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            var unsupported = environment().withProperty(
                    "spring.jpa.properties.hibernate.search.backend.directory.type", "remote-backend")
                    .withProperty("spring.jpa.properties.hibernate.search.backend.directory.root",
                            System.getProperty("java.io.tmpdir"));
            var unsupportedSnapshot = new SystemInformationService(factory, unsupported).snapshot();
            assertThat(unsupportedSnapshot.indexStorage()).isEqualTo("unknown");
            assertThat(unsupportedSnapshot.disks()).allSatisfy(disk ->
                    assertThat(disk.purposes()).doesNotContain("SEARCH_INDEX"));

            var supported = environment().withProperty(
                    "spring.jpa.properties.hibernate.search.backend.directory.type", " LOCAL-FILESYSTEM ")
                    .withProperty("spring.jpa.properties.hibernate.search.backend.directory.root",
                            System.getProperty("java.io.tmpdir"));
            var supportedSnapshot = new SystemInformationService(factory, supported).snapshot();
            assertThat(supportedSnapshot.indexStorage()).isEqualTo("local-filesystem");
            assertThat(supportedSnapshot.disks()).anySatisfy(disk ->
                    assertThat(disk.purposes()).contains("SEARCH_INDEX"));
        }
    }

    @Test
    void allSupportedDatabaseFamiliesHaveNativeVersionExpressions() {
        assertThat(DatabaseDiagnosticFunctions.versionExpression(new HSQLDialect())).isEqualTo("database_version()");
        assertThat(DatabaseDiagnosticFunctions.versionExpression(new PostgreSQLDialect()))
                .isEqualTo("current_setting('server_version')");
        assertThat(DatabaseDiagnosticFunctions.versionExpression(new SQLServerDialect()))
                .contains("serverproperty('ProductVersion')");
        assertThat(DatabaseDiagnosticFunctions.versionExpression(new OracleDialect()))
                .contains("version_full", "product_component_version");
    }

    @Test
    void oracleVersionExpressionDoesNotDependOnMarketingNameOrRowOrder() {
        // Execute the exact Oracle scalar expression on a controlled SQL fixture.
        // The existing Oracle container test remains the real-engine acceptance test.
        try (SessionFactory factory = factory("jdbc:hsqldb:mem:" + UUID.randomUUID())) {
            try (var session = factory.openSession()) {
                session.doWork(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE product_component_version "
                                + "(product VARCHAR(129), version_full VARCHAR(258))");
                        statement.execute("INSERT INTO product_component_version VALUES "
                                + "('PL/SQL', NULL), ('Oracle AI Database 26ai Free', '23.26.0.0.0'), "
                                + "('NLSRTL', NULL)");
                    }
                });
                var internal = factory.unwrap(SessionFactoryImplementor.class);
                var text = internal.getTypeConfiguration().getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.STRING);
                internal.getQueryEngine().getSqmFunctionRegistry()
                        .patternDescriptorBuilder("qa_oracle_version",
                                DatabaseDiagnosticFunctions.versionExpression(new OracleDialect()))
                        .setExactArgumentCount(0).setInvariantType(text).register();
                assertThat(session.createQuery("select qa_oracle_version()", String.class)
                        .getSingleResult()).isEqualTo("23.26.0.0.0");
                session.doWork(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("UPDATE product_component_version SET product = "
                                + "'Oracle Database 23ai Free' WHERE version_full IS NOT NULL");
                        statement.execute("INSERT INTO product_component_version VALUES "
                                + "('Same database component', '23.26.0.0.0')");
                    }
                });
                assertThat(session.createQuery("select qa_oracle_version()", String.class)
                        .getSingleResult()).isEqualTo("23.26.0.0.0");
                session.doWork(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("UPDATE product_component_version SET version_full = NULL");
                    }
                });
                assertThat(session.createQuery("select qa_oracle_version()", String.class)
                        .getSingleResult()).isNull();
            }
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("spring.jpa.properties.hibernate.search.backend.directory.type", "local-heap");
    }

    private static void diagnosticExpression(SessionFactory factory, String name, String expression) {
        var internal = factory.unwrap(SessionFactoryImplementor.class);
        var text = internal.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);
        internal.getQueryEngine().getSqmFunctionRegistry().patternDescriptorBuilder(name, expression)
                .setExactArgumentCount(0).setInvariantType(text).register();
    }

    private static SessionFactory factory(String url) {
        return factory(url, "SA", "");
    }

    private static SessionFactory factory(String url, String username, String password) {
        return new Configuration().setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", username)
                .setProperty("hibernate.connection.password", password).setProperty("hibernate.connection.pool_size", "1")
                .setProperty("hibernate.hbm2ddl.auto", "none").setProperty("hibernate.search.enabled", "false")
                .buildSessionFactory();
    }
}
