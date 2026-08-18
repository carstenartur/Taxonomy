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

/** Upgrade evidence for PostgreSQL V13 with real pre-existing requirement history. */
@Testcontainers
@Tag("db-postgres")
class RequirementTenantPostgresMigrationIT {

    private static final String REPOSITORY_ID = "primary-repository";
    private static final String SCOPE_KEY =
            "v2|r18:primary-repository|s7:CENTRAL|b4:main";

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void scopesExistingRequirementsAndVersionsFromTheirAuthoritativeProject()
            throws Exception {
        DataSource dataSource = isolatedDataSource("requirement_tenant_upgrade");
        migrateJgit(dataSource);
        installApplicationAtVersion(dataSource, "12");
        insertRepositoryAndLegacyRequirementHistory(dataSource);

        TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration());

        assertThat(singleString(dataSource, """
                select scope_key from project_requirement where id = 4201
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleString(dataSource, """
                select scope_key from project_req_version where id = 4301
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleLong(dataSource, """
                select current_version_id from project_requirement where id = 4201
                """)).isEqualTo(4301L);
        assertThat(singleString(dataSource, """
                select convert_from(lo_get(requirement_text), 'UTF8')
                from project_req_version
                where id = 4301
                """)).isEqualTo("Legacy requirement text");

        insertSecondScopedRequirementVersion(dataSource);
        assertThatThrownBy(() -> execute(dataSource, """
                update project_requirement
                set current_version_id = 4302
                where id = 4201
                """))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(dataSource, """
                update project_req_version
                set scope_key = 'v2|r5:other|s7:CENTRAL|b4:main'
                where id = 4301
                """))
                .isInstanceOf(SQLException.class);
    }

    private static void insertRepositoryAndLegacyRequirementHistory(
            DataSource dataSource) throws SQLException {
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
                    'main',
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
                insert into arch_project (
                    id,
                    scope_key,
                    workspace_id,
                    owner_username,
                    project_key,
                    title,
                    description,
                    status,
                    target_architecture,
                    target_date,
                    budget_amount,
                    budget_currency,
                    created_at,
                    updated_at,
                    row_version,
                    repository_id,
                    workspace_scope,
                    branch_name)
                values (
                    4101,
                    'v2|r18:primary-repository|s7:CENTRAL|b4:main',
                    null,
                    'architect',
                    'LEGACY-PROJECT',
                    'Legacy project',
                    'V13 backfill fixture',
                    'ACTIVE',
                    null,
                    null,
                    null,
                    null,
                    current_timestamp,
                    current_timestamp,
                    0,
                    'primary-repository',
                    'CENTRAL',
                    'main')
                """);
        execute(dataSource, """
                insert into project_requirement (
                    id,
                    project_id,
                    requirement_key,
                    title,
                    status,
                    priority,
                    criticality,
                    requirement_type,
                    review_status,
                    owner_username,
                    current_version_id,
                    current_snapshot_id,
                    created_at,
                    updated_at,
                    row_version)
                values (
                    4201,
                    4101,
                    'LEGACY-REQ',
                    'Legacy requirement',
                    'APPROVED',
                    50,
                    'HIGH',
                    'FUNCTIONAL',
                    'CONFIRMED',
                    'architect',
                    null,
                    null,
                    current_timestamp,
                    current_timestamp,
                    0)
                """);
        execute(dataSource, """
                insert into project_req_version (
                    id,
                    requirement_id,
                    version_number,
                    requirement_text,
                    content_hash,
                    change_reason,
                    created_by,
                    created_at)
                values (
                    4301,
                    4201,
                    1,
                    lo_from_bytea(0, convert_to('Legacy requirement text', 'UTF8')),
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'legacy import',
                    'architect',
                    current_timestamp)
                """);
        execute(dataSource, """
                update project_requirement
                set current_version_id = 4301
                where id = 4201
                """);
    }

    private static void insertSecondScopedRequirementVersion(DataSource dataSource)
            throws SQLException {
        execute(dataSource, """
                insert into project_requirement (
                    id,
                    scope_key,
                    project_id,
                    requirement_key,
                    title,
                    status,
                    priority,
                    criticality,
                    requirement_type,
                    review_status,
                    owner_username,
                    current_version_id,
                    current_snapshot_id,
                    created_at,
                    updated_at,
                    row_version)
                values (
                    4202,
                    'v2|r18:primary-repository|s7:CENTRAL|b4:main',
                    4101,
                    'SECOND-REQ',
                    'Second requirement',
                    'APPROVED',
                    50,
                    'HIGH',
                    'FUNCTIONAL',
                    'CONFIRMED',
                    'architect',
                    null,
                    null,
                    current_timestamp,
                    current_timestamp,
                    0)
                """);
        execute(dataSource, """
                insert into project_req_version (
                    id,
                    scope_key,
                    requirement_id,
                    version_number,
                    requirement_text,
                    content_hash,
                    change_reason,
                    created_by,
                    created_at)
                values (
                    4302,
                    'v2|r18:primary-repository|s7:CENTRAL|b4:main',
                    4202,
                    1,
                    lo_from_bytea(0, convert_to('Second requirement text', 'UTF8')),
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'tenant pointer fixture',
                    'architect',
                    current_timestamp)
                """);
    }

    private static void migrateJgit(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false);
    }

    private static void installApplicationAtVersion(
            DataSource dataSource, String version) {
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

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String singleString(DataSource dataSource, String sql)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static long singleLong(DataSource dataSource, String sql)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }
}
