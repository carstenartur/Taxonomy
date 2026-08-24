package com.taxonomy.dsl.storage;

import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("db-postgres")
class TaxonomyRelationProjectionIndexMigrationIT {

    @Container
    static final PostgreSQLContainer database =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("taxonomy")
                    .withUsername("taxonomy")
                    .withPassword("taxonomy");

    @Test
    void freshMigrationKeepsOnlyTheConstraintOwnedScopeIndex() throws Exception {
        DataSource dataSource = isolatedDataSource("projection_index_cleanup");
        migrateJgit(dataSource);

        TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration());

        assertThat(indexExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "idx_rel_projection_checkpoint_scope"))
                .isFalse();
        assertThat(indexExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "uq_rel_projection_checkpoint_scope"))
                .isTrue();
    }

    @Test
    void entityMappingCannotRecreateTheRedundantScopeIndex() {
        Table table = RelationDecisionProjectionCheckpoint.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.indexes())
                .extracting(Index::name)
                .containsExactly("idx_rel_projection_checkpoint_repository")
                .doesNotContain("idx_rel_projection_checkpoint_scope");
    }

    private static void migrateJgit(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false);
    }

    private static DataSource isolatedDataSource(String schema) throws SQLException {
        DataSource admin = baseDataSource();
        execute(admin, "drop schema if exists " + schema + " cascade");
        execute(admin, "create schema " + schema);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(database.getJdbcUrl() + "?currentSchema=" + schema);
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
    }

    private static DataSource baseDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(database.getJdbcUrl());
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
    }

    private static boolean indexExists(
            DataSource dataSource,
            String table,
            String index) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select 1
                     from pg_indexes
                     where schemaname = current_schema()
                       and tablename = '%s'
                       and indexname = '%s'
                     """.formatted(table, index))) {
            return resultSet.next();
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
