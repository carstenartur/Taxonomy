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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies fresh installation and adoption of the application migration stream. */
@Testcontainers
@Tag("db-postgres")
class TaxonomySchemaPostgresMigrationIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void freshInstallationCreatesLegacyAndPortfolioSchema() throws Exception {
        DataSource dataSource = isolatedDataSource("fresh_schema");
        migrateJgit(dataSource);

        TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration());

        assertThat(tableExists(dataSource, "taxonomy_node")).isTrue();
        assertThat(tableExists(dataSource, "arch_project")).isTrue();
        assertThat(tableExists(dataSource, "req_analysis_snapshot")).isTrue();
        assertThat(tableExists(dataSource, "solution_taxonomy")).isTrue();
        assertThat(tableExists(dataSource, "product_taxonomy")).isTrue();
        assertThat(tableExists(dataSource, "project_conflict")).isTrue();
        assertThat(tableExists(dataSource, "repository_membership")).isTrue();
        assertThat(columnExists(dataSource, "system_repository", "storage_repository_name"))
                .isTrue();
        assertThat(columnExists(dataSource, "system_repository", "slug")).isTrue();
        assertThat(columnExists(dataSource, "system_repository", "provisioning_error"))
                .isTrue();
        assertThat(columnExists(dataSource, "user_workspace", "source_branch")).isTrue();
        assertThat(columnExists(dataSource, "user_workspace", "relationship_type")).isTrue();
        assertThat(columnExists(dataSource, "taxonomy_relation", "repository_id")).isTrue();
        assertThat(columnExists(dataSource, "relation_proposal", "repository_id")).isTrue();
        assertThat(columnExists(dataSource, "relation_hypothesis", "repository_id")).isTrue();
        assertThat(columnExists(dataSource, "relation_hypothesis", "workspace_scope_key"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_hypothesis", "analysis_session_scope_key"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "architecture_commit_index", "repository_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "architecture_commit_index", "workspace_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "architecture_commit_index", "workspace_scope_key"))
                .isTrue();
        assertThat(tableExists(dataSource, "relation_decision_projection")).isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "repository_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "workspace_scope_key"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "branch"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "relation_present"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "authoritative_commit_id"))
                .isTrue();
        assertThat(tableExists(dataSource, TaxonomySchemaMigrationConfig.HISTORY_TABLE)).isTrue();
        assertThat(successfulVersions(dataSource))
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void adoptsPreMigrationSchemaAndPreservesExistingData() throws Exception {
        DataSource dataSource = isolatedDataSource("upgrade_schema");
        migrateJgit(dataSource);
        installApplicationBaseline(dataSource);
        execute(dataSource, """
                insert into app_user
                    (username, password_hash, enabled, must_change_password)
                values ('existing-user', 'hash', true, false)
                """);
        execute(dataSource, "drop table " + TaxonomySchemaMigrationConfig.HISTORY_TABLE);

        TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration());

        assertThat(singleLong(dataSource,
                "select count(*) from app_user where username = 'existing-user'"))
                .isEqualTo(1L);
        assertThat(tableExists(dataSource, "project_requirement")).isTrue();
        assertThat(tableExists(dataSource, "repository_membership")).isTrue();
        assertThat(columnExists(dataSource, "relation_hypothesis", "analysis_snapshot_id"))
                .isTrue();
        assertThat(columnExists(dataSource, "system_repository", "storage_repository_name"))
                .isTrue();
        assertThat(columnExists(dataSource, "system_repository", "provisioning_error"))
                .isTrue();
        assertThat(columnExists(dataSource, "user_workspace", "source_repository_id"))
                .isTrue();
        assertThat(columnExists(dataSource, "user_workspace", "source_branch"))
                .isTrue();
        assertThat(columnExists(dataSource, "taxonomy_relation", "repository_id"))
                .isTrue();
        assertThat(columnExists(dataSource, "relation_proposal", "repository_id"))
                .isTrue();
        assertThat(columnExists(dataSource, "relation_hypothesis", "repository_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "architecture_commit_index", "repository_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "architecture_commit_index", "workspace_scope_key"))
                .isTrue();
        assertThat(tableExists(dataSource, "relation_decision_projection")).isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "branch"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_decision_projection", "authoritative_commit_id"))
                .isTrue();
        assertThat(successfulVersions(dataSource))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void bindsExistingWorkspacesToTheOnlyPrimaryRepository() throws Exception {
        DataSource dataSource = isolatedDataSource("workspace_repository_binding");
        migrateJgit(dataSource);
        installApplicationAtVersion(dataSource, "3");
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
                    'primary-repository',
                    'Primary',
                    'INTERNAL_SHARED',
                    'draft',
                    true,
                    current_timestamp,
                    'taxonomy-dsl',
                    'shared-architecture',
                    'ORGANIZATION',
                    'ACTIVE',
                    'SYSTEM',
                    'system',
                    'system',
                    current_timestamp)
                """);
        execute(dataSource, """
                insert into user_workspace (
                    workspace_id,
                    username,
                    display_name,
                    current_branch,
                    base_branch,
                    shared,
                    created_at,
                    provisioning_status,
                    topology_mode,
                    archived,
                    is_default,
                    source_branch,
                    relationship_type)
                values (
                    'legacy-workspace',
                    'alice',
                    'Legacy workspace',
                    'alice/workspace',
                    'draft',
                    false,
                    current_timestamp,
                    'READY',
                    'INTERNAL_SHARED',
                    false,
                    true,
                    'draft',
                    'WORKING_COPY')
                """);

        TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration());

        assertThat(singleString(dataSource, """
                select source_repository_id
                from user_workspace
                where workspace_id = 'legacy-workspace'
                """))
                .isEqualTo("primary-repository");
        assertThatThrownBy(() -> execute(dataSource, """
                update user_workspace
                set source_repository_id = 'missing-repository'
                where workspace_id = 'legacy-workspace'
                """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesToBindExistingWorkspacesWithoutExactlyOnePrimaryRepository() throws Exception {
        DataSource dataSource = isolatedDataSource("workspace_repository_binding_failure");
        migrateJgit(dataSource);
        installApplicationAtVersion(dataSource, "3");
        execute(dataSource, """
                insert into user_workspace (
                    workspace_id,
                    username,
                    display_name,
                    current_branch,
                    shared,
                    created_at,
                    provisioning_status,
                    topology_mode,
                    archived,
                    is_default)
                values (
                    'unbound-workspace',
                    'alice',
                    'Unbound workspace',
                    'draft',
                    false,
                    current_timestamp,
                    'READY',
                    'INTERNAL_SHARED',
                    false,
                    true)
                """);

        assertThatThrownBy(() -> TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration()))
                .hasStackTraceContaining("expected exactly one primary repository");
    }

    @Test
    void refusesToBaselineAPartialLegacySchema() throws Exception {
        DataSource dataSource = isolatedDataSource("partial_schema");
        migrateJgit(dataSource);
        execute(dataSource, """
                create table app_user (
                    id bigint generated by default as identity primary key,
                    username varchar(255) not null unique,
                    password_hash varchar(255) not null,
                    enabled boolean not null,
                    must_change_password boolean not null,
                    display_name varchar(255),
                    email varchar(255)
                )
                """);

        assertThatThrownBy(() -> TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe partial Taxonomy application schema")
                .hasMessageContaining("taxonomy_node");
        assertThat(tableExists(dataSource, TaxonomySchemaMigrationConfig.HISTORY_TABLE))
                .isFalse();
    }

    private static void migrateJgit(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false);
    }

    private static void installApplicationBaseline(DataSource dataSource) {
        installApplicationAtVersion(dataSource, "1");
    }

    private static void installApplicationAtVersion(DataSource dataSource, String version) {
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
        dataSource.setUrl(withCurrentSchema(database.getJdbcUrl(), schema));
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
    }

    private static String withCurrentSchema(String jdbcUrl, String schema) {
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

    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getTables(
                     connection.getCatalog(), connection.getSchema(), table, new String[] {"TABLE"})) {
            return resultSet.next();
        }
    }

    private static boolean columnExists(
            DataSource dataSource, String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(
                     connection.getCatalog(), connection.getSchema(), table, column)) {
            return resultSet.next();
        }
    }

    private static List<String> successfulVersions(DataSource dataSource) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select version from " + TaxonomySchemaMigrationConfig.HISTORY_TABLE
                             + " where success = true order by installed_rank")) {
            while (resultSet.next()) {
                versions.add(resultSet.getString(1));
            }
        }
        return versions;
    }

    private static long singleLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String singleString(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
