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

/** Upgrade evidence for tenant-bound analysis artifacts with real V13 history. */
@Testcontainers
@Tag("db-postgres")
class AnalysisArtifactTenantPostgresMigrationIT {

    private static final String SCOPE_KEY =
            "v2|r18:primary-repository|s7:CENTRAL|b4:main";

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void upgradesExistingJobsItemsSnapshotsAndMappingsFromTheirParentAuthority()
            throws Exception {
        DataSource dataSource = isolatedDataSource("analysis_tenant_upgrade");
        migrateJgit(dataSource);
        installApplicationAtVersion(dataSource, "13");
        insertScopedRequirementAndLegacyAnalysisHistory(dataSource);

        TaxonomySchemaMigrationConfig.migrateApplicationSchema(
                Flyway.configure().dataSource(dataSource).load().getConfiguration());

        assertThat(singleString(dataSource, """
                select scope_key from req_analysis_job where id = 'job-legacy'
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleString(dataSource, """
                select idempotency_key from req_analysis_job where id = 'job-legacy'
                """)).isEqualTo("auto:job-legacy");
        assertThat(singleString(dataSource, """
                select scope_key from req_analysis_item where id = 4401
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleLong(dataSource, """
                select project_id from req_analysis_item where id = 4401
                """)).isEqualTo(4101L);
        assertThat(singleString(dataSource, """
                select scope_key from req_analysis_snapshot where id = 'snapshot-legacy'
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleString(dataSource, """
                select scope_key from req_element_mapping where id = 4501
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleString(dataSource, """
                select scope_key from req_relation_mapping where id = 4601
                """)).isEqualTo(SCOPE_KEY);
        assertThat(singleString(dataSource, """
                select current_snapshot_id from project_requirement where id = 4201
                """)).isEqualTo("snapshot-legacy");
        assertThat(singleString(dataSource, """
                select snapshot_id from req_analysis_item where id = 4401
                """)).isEqualTo("snapshot-legacy");
        assertThat(singleString(dataSource, """
                select convert_from(lo_get(analysis_payload), 'UTF8')
                from req_analysis_snapshot where id = 'snapshot-legacy'
                """)).isEqualTo("{\"status\":\"SUCCESS\"}");

        insertSecondScopedAnalysisIdentity(dataSource);
        assertThatThrownBy(() -> execute(dataSource, """
                update project_requirement
                set current_snapshot_id = 'snapshot-second'
                where id = 4201
                """))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(dataSource, """
                update req_analysis_item
                set snapshot_id = 'snapshot-second'
                where id = 4401
                """))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(dataSource, """
                update req_element_mapping
                set scope_key = 'v2|r5:other|s7:CENTRAL|b4:main'
                where id = 4501
                """))
                .isInstanceOf(SQLException.class);
    }

    private static void insertScopedRequirementAndLegacyAnalysisHistory(
            DataSource dataSource) throws SQLException {
        insertRepository(dataSource);
        execute(dataSource, """
                insert into arch_project (
                    id, scope_key, workspace_id, owner_username, project_key,
                    title, description, status, target_architecture, target_date,
                    budget_amount, budget_currency, created_at, updated_at,
                    row_version, repository_id, workspace_scope, branch_name)
                values (
                    4101, 'v2|r18:primary-repository|s7:CENTRAL|b4:main', null,
                    'architect', 'LEGACY-PROJECT', 'Legacy project',
                    'Analysis tenant fixture', 'ACTIVE', null, null, null, null,
                    current_timestamp, current_timestamp, 0,
                    'primary-repository', 'CENTRAL', 'main')
                """);
        execute(dataSource, """
                insert into project_requirement (
                    id, scope_key, project_id, requirement_key, title, status,
                    priority, criticality, requirement_type, review_status,
                    owner_username, current_version_id, current_snapshot_id,
                    created_at, updated_at, row_version)
                values (
                    4201, 'v2|r18:primary-repository|s7:CENTRAL|b4:main', 4101,
                    'LEGACY-REQ', 'Legacy requirement', 'APPROVED', 50, 'HIGH',
                    'FUNCTIONAL', 'CONFIRMED', 'architect', null, null,
                    current_timestamp, current_timestamp, 0)
                """);
        execute(dataSource, """
                insert into project_req_version (
                    id, scope_key, requirement_id, version_number,
                    requirement_text, content_hash, change_reason, created_by,
                    created_at)
                values (
                    4301, 'v2|r18:primary-repository|s7:CENTRAL|b4:main', 4201, 1,
                    lo_from_bytea(0, convert_to('Legacy requirement text', 'UTF8')),
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'legacy import', 'architect', current_timestamp)
                """);
        execute(dataSource, """
                update project_requirement set current_version_id = 4301 where id = 4201
                """);
        execute(dataSource, """
                insert into req_analysis_job (
                    id, project_id, status, idempotency_key, provider,
                    max_architecture_nodes, requested_by, workspace_id,
                    created_at, started_at, completed_at, total_items,
                    successful_items, partial_items, failed_items,
                    error_summary, row_version)
                values (
                    'job-legacy', 4101, 'SUCCESS', '', 'MOCK', 25,
                    'architect', null, current_timestamp, current_timestamp,
                    current_timestamp, 1, 1, 0, 0, null, 0)
                """);
        execute(dataSource, """
                insert into req_analysis_item (
                    id, job_id, requirement_id, requirement_version_id, status,
                    snapshot_id, attempt, started_at, completed_at,
                    error_message, row_version)
                values (
                    4401, 'job-legacy', 4201, 4301, 'SUCCESS', null, 1,
                    current_timestamp, current_timestamp, null, 0)
                """);
        execute(dataSource, """
                insert into req_analysis_snapshot (
                    id, project_id, requirement_id, requirement_version_id,
                    job_id, status, analysis_session_id, provider, model_name,
                    prompt_fingerprint, taxonomy_fingerprint, workspace_id,
                    branch_name, commit_sha, created_by, created_at,
                    duration_ms, warning_count, error_message, analysis_payload,
                    gap_payload, pattern_payload, recommendation_payload)
                values (
                    'snapshot-legacy', 4101, 4201, 4301, 'job-legacy',
                    'SUCCESS', 'portfolio:snapshot-legacy', 'MOCK', 'legacy-model',
                    'prompt-fingerprint', 'taxonomy-fingerprint', null, 'main',
                    'legacy-commit', 'architect', current_timestamp, 123, 0, null,
                    lo_from_bytea(0, convert_to('{\"status\":\"SUCCESS\"}', 'UTF8')),
                    null, null, null)
                """);
        execute(dataSource, """
                update req_analysis_item
                set snapshot_id = 'snapshot-legacy'
                where id = 4401
                """);
        execute(dataSource, """
                update project_requirement
                set current_snapshot_id = 'snapshot-legacy'
                where id = 4201
                """);
        execute(dataSource, """
                insert into req_element_mapping (
                    id, snapshot_id, node_code, node_title, taxonomy_root,
                    direct_score, relevance, confidence, mapping_origin,
                    hierarchy_path, presence_reason, selected_for_impact,
                    review_status, action_status, action_evidence, decision_by,
                    decision_at, decision_comment, row_version)
                values (
                    4501, 'snapshot-legacy', 'BP-1000', 'Business process', 'BP',
                    90, 0.9, 0.9, 'DIRECT', 'BP/BP-1000', 'Legacy mapping', true,
                    'PROPOSED', 'UNDECIDED', null, null, null, null, 0)
                """);
        execute(dataSource, """
                insert into req_relation_mapping (
                    id, snapshot_id, source_code, target_code, relation_type,
                    relation_origin, relation_category, relevance, confidence,
                    presence_reason, review_status, decision_by, decision_at,
                    decision_comment, row_version)
                values (
                    4601, 'snapshot-legacy', 'BP-1000', 'IS-1000', 'depends-on',
                    'CATALOG', 'DEPENDENCY', 0.8, 0.8, 'Legacy relation',
                    'PROPOSED', null, null, null, 0)
                """);
    }

    private static void insertSecondScopedAnalysisIdentity(DataSource dataSource)
            throws SQLException {
        execute(dataSource, """
                insert into project_requirement (
                    id, scope_key, project_id, requirement_key, title, status,
                    priority, criticality, requirement_type, review_status,
                    owner_username, current_version_id, current_snapshot_id,
                    created_at, updated_at, row_version)
                values (
                    4202, 'v2|r18:primary-repository|s7:CENTRAL|b4:main', 4101,
                    'SECOND-REQ', 'Second requirement', 'APPROVED', 50, 'HIGH',
                    'FUNCTIONAL', 'CONFIRMED', 'architect', null, null,
                    current_timestamp, current_timestamp, 0)
                """);
        execute(dataSource, """
                insert into project_req_version (
                    id, scope_key, requirement_id, version_number,
                    requirement_text, content_hash, change_reason, created_by,
                    created_at)
                values (
                    4302, 'v2|r18:primary-repository|s7:CENTRAL|b4:main', 4202, 1,
                    lo_from_bytea(0, convert_to('Second requirement text', 'UTF8')),
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'second fixture', 'architect', current_timestamp)
                """);
        execute(dataSource, """
                update project_requirement set current_version_id = 4302 where id = 4202
                """);
        execute(dataSource, """
                insert into req_analysis_job (
                    id, scope_key, project_id, status, idempotency_key, provider,
                    max_architecture_nodes, requested_by, workspace_id,
                    created_at, started_at, completed_at, total_items,
                    successful_items, partial_items, failed_items,
                    error_summary, row_version)
                values (
                    'job-second', 'v2|r18:primary-repository|s7:CENTRAL|b4:main',
                    4101, 'SUCCESS', 'second-key', 'MOCK', 25, 'architect', null,
                    current_timestamp, current_timestamp, current_timestamp,
                    1, 1, 0, 0, null, 0)
                """);
        execute(dataSource, """
                insert into req_analysis_snapshot (
                    id, scope_key, project_id, requirement_id,
                    requirement_version_id, job_id, status, analysis_session_id,
                    provider, model_name, prompt_fingerprint,
                    taxonomy_fingerprint, workspace_id, branch_name, commit_sha,
                    created_by, created_at, duration_ms, warning_count,
                    error_message, analysis_payload, gap_payload, pattern_payload,
                    recommendation_payload)
                values (
                    'snapshot-second',
                    'v2|r18:primary-repository|s7:CENTRAL|b4:main',
                    4101, 4202, 4302, 'job-second', 'SUCCESS',
                    'portfolio:snapshot-second', 'MOCK', 'second-model',
                    'prompt-second', 'taxonomy-second', null, 'main',
                    'second-commit', 'architect', current_timestamp, 50, 0, null,
                    lo_from_bytea(0, convert_to('{\"status\":\"SUCCESS\"}', 'UTF8')),
                    null, null, null)
                """);
    }

    private static void insertRepository(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                insert into system_repository (
                    repository_id, display_name, topology_mode, default_branch,
                    primary_repo, created_at, storage_repository_name, slug,
                    visibility, lifecycle_state, owner_type, owner_id,
                    created_by, updated_at)
                values (
                    'primary-repository', 'Primary', 'INTERNAL_SHARED', 'main',
                    true, current_timestamp, 'taxonomy-dsl',
                    'shared-architecture', 'ORGANIZATION', 'ACTIVE', 'SYSTEM',
                    'system', 'system', current_timestamp)
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
