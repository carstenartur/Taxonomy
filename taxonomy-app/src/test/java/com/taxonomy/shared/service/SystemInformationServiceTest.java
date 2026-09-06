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

import java.nio.file.Path;
import java.sql.DriverManager;
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

    private static SessionFactory factory(String url) {
        return new Configuration().setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "SA")
                .setProperty("hibernate.connection.password", "").setProperty("hibernate.connection.pool_size", "1")
                .setProperty("hibernate.hbm2ddl.auto", "none").setProperty("hibernate.search.enabled", "false")
                .buildSessionFactory();
    }
}
