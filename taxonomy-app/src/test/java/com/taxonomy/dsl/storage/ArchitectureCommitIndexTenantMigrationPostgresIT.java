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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL evidence for repository-scoped commit-history projections. */
@Testcontainers
@Tag("db-postgres")
class ArchitectureCommitIndexTenantMigrationPostgresIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void purgesAmbiguousLegacyProjectionAndEnforcesTenantBranchIdentity()
            throws Exception {
        DataSource dataSource = isolatedDataSource("commit_index_tenant_upgrade");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "7");
        insertRepository(dataSource, "repo-a", "repo-a", true);
        insertRepository(dataSource, "repo-b", "repo-b", false);
        execute(dataSource, """
                insert into architecture_commit_index (
                    commit_id,
                    branch,
                    commit_timestamp,
                    indexed_at)
                values (
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'draft',
                    current_timestamp,
                    current_timestamp)
                """);

        migrateApplicationTo(dataSource, "8");

        assertThat(singleLong(dataSource,
                "select count(*) from architecture_commit_index"))
                .isZero();
        assertThat(columnExists(dataSource,
                "architecture_commit_index", "repository_id")).isTrue();
        assertThat(columnExists(dataSource,
                "architecture_commit_index", "workspace_id")).isTrue();
        assertThat(columnExists(dataSource,
                "architecture_commit_index", "workspace_scope_key")).isTrue();
        assertThat(columnNullable(dataSource,
                "architecture_commit_index", "repository_id")).isFalse();
        assertThat(columnNullable(dataSource,
                "architecture_commit_index", "workspace_scope_key")).isFalse();
        assertThat(columnNullable(dataSource,
                "architecture_commit_index", "branch")).isFalse();

        String commit = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        insertScoped(dataSource, "repo-a", null, "__shared__", "draft", commit);
        insertScoped(dataSource, "repo-b", null, "__shared__", "draft", commit);
        insertScoped(dataSource, "repo-a", "workspace-a", "workspace-a", "draft", commit);
        insertScoped(dataSource, "repo-a", null, "__shared__", "review", commit);

        assertThat(singleLong(dataSource,
                "select count(*) from architecture_commit_index"))
                .isEqualTo(4L);
        assertThatThrownBy(() -> insertScoped(
                dataSource, "repo-a", null, "__shared__", "draft", commit))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertScoped(
                dataSource,
                "missing-repository",
                null,
                "__shared__",
                "draft",
                "cccccccccccccccccccccccccccccccccccccccc"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(dataSource, """
                insert into architecture_commit_index (
                    repository_id,
                    workspace_scope_key,
                    commit_id,
                    branch,
                    commit_timestamp,
                    indexed_at)
                values (
                    'repo-a',
                    '__shared__',
                    'dddddddddddddddddddddddddddddddddddddddd',
                    null,
                    current_timestamp,
                    current_timestamp)
                """))
                .isInstanceOf(SQLException.class);
    }

    private static void insertRepository(
            DataSource dataSource,
            String repositoryId,
            String slug,
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
                """.formatted(repositoryId, repositoryId, primary, repositoryId, slug));
    }

    private static void insertScoped(
            DataSource dataSource,
            String repositoryId,
            String workspaceId,
            String workspaceScope,
            String branch,
            String commitId) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        execute(dataSource, """
                insert into architecture_commit_index (
                    repository_id,
                    workspace_id,
                    workspace_scope_key,
                    commit_id,
                    branch,
                    commit_timestamp,
                    indexed_at)
                values (
                    '%s',
                    %s,
                    '%s',
                    '%s',
                    '%s',
                    current_timestamp,
                    current_timestamp)
                """.formatted(
                        repositoryId,
                        workspace,
                        workspaceScope,
                        commitId,
                        branch));
    }

    private static void migrateJgit(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false);
    }

    private static void migrateApplicationTo(DataSource dataSource, String version) {
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

    private static DataSource isolatedDataSource(String schema) throws SQLException {
        DataSource admin = baseDataSource();
        execute(admin, "drop schema if exists " + schema + " cascade");
        execute(admin, "create schema " + schema);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrlForSchema(schema));
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
    }

    private static String jdbcUrlForSchema(String schema) {
        String jdbcUrl = database.getJdbcUrl();
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
    }

    private static DataSource baseDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(database.getJdbcUrl());
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
    }

    private static boolean columnExists(
            DataSource dataSource,
            String table,
            String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(
                     connection.getCatalog(), connection.getSchema(), table, column)) {
            return result.next();
        }
    }

    private static boolean columnNullable(
            DataSource dataSource,
            String table,
            String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.getMetaData().getColumns(
                     connection.getCatalog(), connection.getSchema(), table, column)) {
            assertThat(result.next()).isTrue();
            return result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
        }
    }

    private static long singleLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
