package com.taxonomy.dsl.storage;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL evidence for the exact relation branch projection checkpoint. */
@Testcontainers
@Tag("db-postgres")
class RelationProjectionCheckpointPostgresMigrationIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void enforcesRepositoryScopeBranchIdentityAndCheckpointIntegrity()
            throws Exception {
        DataSource dataSource = isolatedDataSource(
                "relation_projection_checkpoint");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "9");
        insertRepository(dataSource, "repo-a", true);
        insertRepository(dataSource, "repo-b", false);

        migrateApplicationTo(dataSource, "10");

        insertCheckpoint(
                dataSource,
                "repo-a",
                null,
                "__shared__",
                "accepted",
                "a".repeat(40),
                2);
        insertCheckpoint(
                dataSource,
                "repo-a",
                "workspace-a",
                "workspace-a",
                "accepted",
                "b".repeat(40),
                1);
        insertCheckpoint(
                dataSource,
                "repo-a",
                null,
                "__shared__",
                "review",
                "c".repeat(40),
                0);

        assertThat(singleLong(dataSource, """
                select count(*)
                from relation_decision_projection_checkpoint
                """))
                .isEqualTo(3L);

        assertThatThrownBy(() -> insertCheckpoint(
                dataSource,
                "repo-a",
                null,
                "__shared__",
                "accepted",
                "d".repeat(40),
                4))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertCheckpoint(
                dataSource,
                "missing-repository",
                null,
                "__shared__",
                "accepted",
                "e".repeat(40),
                1))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertCheckpoint(
                dataSource,
                "repo-b",
                "workspace-b",
                "wrong-scope",
                "draft",
                "f".repeat(40),
                1))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertCheckpoint(
                dataSource,
                "repo-b",
                null,
                "__shared__",
                "draft",
                "1".repeat(40),
                -1))
                .isInstanceOf(SQLException.class);
    }

    private static void insertRepository(
            DataSource dataSource,
            String repositoryId,
            boolean primary) throws SQLException {
        execute(dataSource, """
                insert into system_repository (
                    repository_id,
                    display_name,
                    topology_mode,
                    default_branch,
                    primary_repo,
                    created_at,
                    storage_repository_name,
                    slug,
                    visibility,
                    lifecycle_state,
                    owner_type,
                    owner_id,
                    created_by,
                    updated_at)
                values (
                    '%s',
                    '%s',
                    'INTERNAL_SHARED',
                    'draft',
                    %s,
                    current_timestamp,
                    'storage-%s',
                    '%s',
                    'ORGANIZATION',
                    'ACTIVE',
                    'SYSTEM',
                    'system',
                    'system',
                    current_timestamp)
                """.formatted(
                        repositoryId,
                        repositoryId,
                        primary,
                        repositoryId,
                        repositoryId));
    }

    private static void insertCheckpoint(
            DataSource dataSource,
            String repositoryId,
            String workspaceId,
            String workspaceScopeKey,
            String branch,
            String commitId,
            int relationCount) throws SQLException {
        String workspace = workspaceId == null
                ? "null"
                : "'" + workspaceId + "'";
        execute(dataSource, """
                insert into relation_decision_projection_checkpoint (
                    repository_id,
                    workspace_id,
                    workspace_scope_key,
                    branch,
                    authoritative_commit_id,
                    relation_count,
                    completed_at,
                    version)
                values (
                    '%s',
                    %s,
                    '%s',
                    '%s',
                    '%s',
                    %d,
                    current_timestamp,
                    0)
                """.formatted(
                        repositoryId,
                        workspace,
                        workspaceScopeKey,
                        branch,
                        commitId,
                        relationCount));
    }

    private static void migrateJgit(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false);
    }

    private static void migrateApplicationTo(
            DataSource dataSource,
            String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(TaxonomySchemaMigrationConfig.POSTGRES_LOCATION)
                .table(TaxonomySchemaMigrationConfig.HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .baselineDescription("before Taxonomy application schema")
                .target(version)
                .load()
                .migrate();
    }

    private static DataSource isolatedDataSource(String schema)
            throws SQLException {
        DataSource admin = baseDataSource();
        execute(admin, "drop schema if exists " + schema + " cascade");
        execute(admin, "create schema " + schema);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(database.getJdbcUrl()
                + (database.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema);
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

    private static long singleLong(DataSource dataSource, String sql)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(DataSource dataSource, String sql)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
