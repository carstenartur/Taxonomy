package com.taxonomy.composition.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaxonomySchemaMigrationConfigTest {

    private static final Set<String> LEGACY_TABLES = Set.of(
            "app_user",
            "architecture_dsl_document",
            "taxonomy_node",
            "taxonomy_relation");

    private static final Set<String> CURRENT_TABLES = Set.of(
            "app_user",
            "architecture_dsl_document",
            "taxonomy_node",
            "taxonomy_relation",
            "arch_project",
            "project_requirement",
            "project_req_version",
            "req_analysis_job",
            "req_analysis_item",
            "req_analysis_snapshot",
            "req_element_mapping",
            "req_relation_mapping",
            "solution_definition",
            "solution_taxonomy",
            "project_solution",
            "req_solution_link",
            "product_catalog",
            "product_taxonomy",
            "solution_product",
            "project_conflict",
            "webdav_application_credential",
            "editor_workspace",
            "editor_operation",
            "editor_checkpoint",
            "taxonomy_schema_history");

    @Test
    void primaryApplicationStrategyUsesQualifiedCoreBeforeApplicationMigration()
            throws SQLException {
        FlywayMigrationStrategy core = mock(FlywayMigrationStrategy.class);
        FlywayMigrationStrategy decoy = mock(FlywayMigrationStrategy.class);
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = configuration(nonPostgresDataSource());
        when(flyway.getConfiguration()).thenReturn(configuration);

        new ApplicationContextRunner()
                .withPropertyValues("spring.flyway.enabled=true")
                .withBean("jgitStorageFlywayMigrationStrategy",
                        FlywayMigrationStrategy.class, () -> core)
                .withBean("unrelatedFlywayMigrationStrategy",
                        FlywayMigrationStrategy.class, () -> decoy)
                .withUserConfiguration(TaxonomySchemaMigrationConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlywayMigrationStrategy application = context.getBean(
                            "taxonomyFlywayMigrationStrategy", FlywayMigrationStrategy.class);
                    assertThat(context.getBean(FlywayMigrationStrategy.class))
                            .isSameAs(application);

                    application.migrate(flyway);

                    InOrder order = inOrder(core, flyway);
                    order.verify(core).migrate(flyway);
                    order.verify(flyway).getConfiguration();
                    verifyNoInteractions(decoy);
                });
    }

    @Test
    void coreFailurePreventsAllApplicationMigrationWork() {
        FlywayMigrationStrategy core = mock(FlywayMigrationStrategy.class);
        Flyway flyway = mock(Flyway.class);
        IllegalStateException failure = new IllegalStateException("core migration failed");
        doThrow(failure).when(core).migrate(flyway);
        FlywayMigrationStrategy application = new TaxonomySchemaMigrationConfig()
                .taxonomyFlywayMigrationStrategy(core);

        assertThatThrownBy(() -> application.migrate(flyway)).isSameAs(failure);
        verify(core).migrate(flyway);
        verifyNoInteractions(flyway);
    }

    @Test
    void applicationStrategyIsDisabledWhenFlywayIsDisabled() {
        FlywayMigrationStrategy core = mock(FlywayMigrationStrategy.class);

        new ApplicationContextRunner()
                .withPropertyValues("spring.flyway.enabled=false")
                .withBean("jgitStorageFlywayMigrationStrategy",
                        FlywayMigrationStrategy.class, () -> core)
                .withUserConfiguration(TaxonomySchemaMigrationConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("taxonomyFlywayMigrationStrategy");
                });
    }

    @Test
    void nonPostgresqlDatabaseLeavesApplicationSchemaUntouched() throws SQLException {
        Configuration source = configuration(nonPostgresDataSource());

        try (MockedConstruction<FluentConfiguration> configurations =
                     mockConstruction(FluentConfiguration.class)) {
            TaxonomySchemaMigrationConfig.migrateApplicationSchema(source);

            assertThat(configurations.constructed()).isEmpty();
        }
    }

    @Test
    void freshPostgresqlSchemaUsesVersionZeroBaseline() throws SQLException {
        DataSource dataSource = postgresDataSource(Set.of(), CURRENT_TABLES);
        Configuration source = configuration(dataSource);

        try (MigrationBoundary boundary = new MigrationBoundary(dataSource)) {
            TaxonomySchemaMigrationConfig.migrateApplicationSchema(source);

            FluentConfiguration migration = boundary.configuration();
            verify(migration).baselineOnMigrate(true);
            verify(migration).baselineVersion("0");
            verify(migration).baselineDescription("before Taxonomy application schema");
            verify(boundary.flyway()).migrate();
        }
    }

    @Test
    void completeLegacyPostgresqlSchemaUsesVersionOneBaseline() throws SQLException {
        DataSource dataSource = postgresDataSource(LEGACY_TABLES, CURRENT_TABLES);
        Configuration source = configuration(dataSource);

        try (MigrationBoundary boundary = new MigrationBoundary(dataSource)) {
            TaxonomySchemaMigrationConfig.migrateApplicationSchema(source);

            FluentConfiguration migration = boundary.configuration();
            verify(migration).baselineOnMigrate(true);
            verify(migration).baselineVersion("1");
            verify(migration).baselineDescription("verified pre-portfolio Taxonomy schema");
            verify(boundary.flyway()).migrate();
        }
    }

    @Test
    void existingApplicationHistoryMigratesWithoutRebaselining() throws SQLException {
        DataSource dataSource = postgresDataSource(
                Set.of("taxonomy_schema_history"), CURRENT_TABLES);
        Configuration source = configuration(dataSource);

        try (MigrationBoundary boundary = new MigrationBoundary(dataSource)) {
            TaxonomySchemaMigrationConfig.migrateApplicationSchema(source);

            FluentConfiguration migration = boundary.configuration();
            verify(migration, never()).baselineOnMigrate(anyBoolean());
            verify(migration, never()).baselineVersion(anyString());
            verify(boundary.flyway()).migrate();
        }
    }

    @Test
    void partialLegacySchemaIsRejectedBeforeMigration() throws SQLException {
        DataSource dataSource = postgresDataSource(Set.of("app_user"), CURRENT_TABLES);
        Configuration source = configuration(dataSource);

        try (MigrationBoundary boundary = new MigrationBoundary(dataSource)) {
            assertThatThrownBy(() -> TaxonomySchemaMigrationConfig
                    .migrateApplicationSchema(source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unsafe partial Taxonomy application schema")
                    .hasMessageContaining("architecture_dsl_document")
                    .hasMessageContaining("taxonomy_node")
                    .hasMessageContaining("taxonomy_relation");
            verify(boundary.flyway(), never()).migrate();
        }
    }

    @Test
    void missingRequiredTableAfterMigrationIsRejected() throws SQLException {
        Set<String> incomplete = new LinkedHashSet<>(CURRENT_TABLES);
        incomplete.remove("editor_checkpoint");
        DataSource dataSource = postgresDataSource(Set.of(), Set.copyOf(incomplete));
        Configuration source = configuration(dataSource);

        try (MigrationBoundary boundary = new MigrationBoundary(dataSource)) {
            assertThatThrownBy(() -> TaxonomySchemaMigrationConfig
                    .migrateApplicationSchema(source))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "Taxonomy application migration completed without required tables")
                    .hasMessageContaining("editor_checkpoint");
            verify(boundary.flyway()).migrate();
        }
    }

    @Test
    void databaseIdentificationFailureIsReported() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        SQLException failure = new SQLException("connection unavailable");
        when(dataSource.getConnection()).thenThrow(failure);

        assertThatThrownBy(() -> TaxonomySchemaMigrationConfig
                .migrateApplicationSchema(configuration(dataSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not identify the database for Taxonomy schema migrations")
                .hasCause(failure);
    }

    @Test
    void schemaInspectionFailureIsReported() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection identification = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        SQLException failure = new SQLException("metadata unavailable");
        when(dataSource.getConnection()).thenReturn(identification).thenThrow(failure);
        when(identification.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL 16");

        assertThatThrownBy(() -> TaxonomySchemaMigrationConfig
                .migrateApplicationSchema(configuration(dataSource)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not inspect the Taxonomy application schema")
                .hasCause(failure);
    }

    private static Configuration configuration(DataSource dataSource) {
        Configuration configuration = mock(Configuration.class);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(configuration.getClassLoader())
                .thenReturn(TaxonomySchemaMigrationConfigTest.class.getClassLoader());
        return configuration;
    }

    private static DataSource nonPostgresDataSource() {
        JDBCDataSource dataSource = new JDBCDataSource();
        String databaseName = "taxonomy_application_schema_"
                + UUID.randomUUID().toString().replace("-", "");
        dataSource.setUrl("jdbc:hsqldb:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static DataSource postgresDataSource(Set<String> before, Set<String> after)
            throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getSchema()).thenReturn("public");
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL 16");
        ResultSet beforeTables = tableNames(before);
        ResultSet afterTables = tableNames(after);
        when(metadata.getTables(any(), any(), any(), any()))
                .thenReturn(beforeTables, afterTables);
        return dataSource;
    }

    private static ResultSet tableNames(Set<String> names) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        Iterator<String> iterator = names.iterator();
        AtomicReference<String> current = new AtomicReference<>();
        when(resultSet.next()).thenAnswer(invocation -> {
            if (!iterator.hasNext()) {
                current.set(null);
                return false;
            }
            current.set(iterator.next());
            return true;
        });
        when(resultSet.getString("TABLE_NAME"))
                .thenAnswer(invocation -> current.get());
        return resultSet;
    }

    private static final class MigrationBoundary implements AutoCloseable {
        private final Flyway flyway = mock(Flyway.class);
        private final MockedConstruction<FluentConfiguration> construction;

        private MigrationBoundary(DataSource dataSource) {
            construction = mockConstruction(FluentConfiguration.class,
                    (configuration, context) -> {
                        when(configuration.dataSource(dataSource)).thenReturn(configuration);
                        when(configuration.locations(anyString())).thenReturn(configuration);
                        when(configuration.table(anyString())).thenReturn(configuration);
                        when(configuration.baselineOnMigrate(anyBoolean()))
                                .thenReturn(configuration);
                        when(configuration.baselineVersion(anyString()))
                                .thenReturn(configuration);
                        when(configuration.baselineDescription(anyString()))
                                .thenReturn(configuration);
                        when(configuration.load()).thenReturn(flyway);
                    });
        }

        private FluentConfiguration configuration() {
            assertThat(construction.constructed()).hasSize(1);
            return construction.constructed().getFirst();
        }

        private Flyway flyway() {
            return flyway;
        }

        @Override
        public void close() {
            construction.close();
        }
    }
}
