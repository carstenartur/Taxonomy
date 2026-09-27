package com.taxonomy.composition.persistence;

import com.taxonomy.workspace.storage.JgitStorageSchemaMigrationConfig;

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
        assertThat(tableExists(dataSource, "analysis_working_draft")).isTrue();
        assertThat(columnExists(dataSource, "project_requirement", "scope_key")).isTrue();
        assertThat(columnExists(dataSource, "project_req_version", "scope_key")).isTrue();
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
        assertThat(tableExists(
                dataSource, "relation_decision_projection_checkpoint"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "repository_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "workspace_scope_key"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "branch"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "authoritative_commit_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "relation_count"))
                .isTrue();
        assertThat(tableExists(dataSource, "relation_projection_recovery")).isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "repository_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "workspace_scope_key"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "branch"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "authoritative_commit_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "status"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "attempt_count"))
                .isTrue();
        assertThat(tableExists(dataSource, "editor_workspace")).isTrue();
        assertThat(tableExists(dataSource, "editor_operation")).isTrue();
        assertThat(tableExists(dataSource, "editor_checkpoint")).isTrue();
        assertThat(columnExists(dataSource, "editor_operation", "before_dsl")).isTrue();
        assertThat(columnExists(dataSource, "editor_operation", "target_operation_id")).isTrue();
        assertIntegrationSchema(dataSource);
        assertReformulationSchema(dataSource);
        assertAnalysisRecoverySchema(dataSource);
        assertThat(tableExists(dataSource, TaxonomySchemaMigrationConfig.HISTORY_TABLE)).isTrue();
        assertThat(successfulVersions(dataSource))
                .containsExactly(
                        "0", "1", "2", "3", "4", "5",
                        "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
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
        assertThat(columnExists(dataSource, "project_requirement", "scope_key")).isTrue();
        assertThat(columnExists(dataSource, "project_req_version", "scope_key")).isTrue();
        assertThat(tableExists(dataSource, "repository_membership")).isTrue();
        assertThat(tableExists(dataSource, "analysis_working_draft")).isTrue();
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
        assertThat(columnExists(dataSource, "taxonomy_relation", "repository_id")).isTrue();
        assertThat(columnExists(dataSource, "relation_proposal", "repository_id")).isTrue();
        assertThat(columnExists(dataSource, "relation_hypothesis", "repository_id")).isTrue();
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
        assertThat(tableExists(
                dataSource, "relation_decision_projection_checkpoint"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "branch"))
                .isTrue();
        assertThat(columnExists(
                dataSource,
                "relation_decision_projection_checkpoint",
                "authoritative_commit_id"))
                .isTrue();
        assertThat(tableExists(dataSource, "relation_projection_recovery")).isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "authoritative_commit_id"))
                .isTrue();
        assertThat(columnExists(
                dataSource, "relation_projection_recovery", "status"))
                .isTrue();
        assertThat(successfulVersions(dataSource))
                .containsExactly(
                        "1", "2", "3", "4", "5",
                        "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
        assertIntegrationSchema(dataSource);
        assertReformulationSchema(dataSource);
        assertAnalysisRecoverySchema(dataSource);
    }

    private static void assertAnalysisRecoverySchema(DataSource dataSource) throws SQLException {
        for (String table : List.of("analysis_continuation", "analysis_question_checkpoint")) {
            assertThat(tableExists(dataSource, table)).as(table).isTrue();
        }
        for (String column : List.of("id", "username", "workspace_id", "branch_name", "repository_id",
                "input_hash", "request_json", "result_json", "state", "claim_token", "claim_until",
                "updated_at", "payload_characters", "current_node", "row_version")) {
            assertThat(columnExists(dataSource, "analysis_continuation", column))
                    .as("analysis continuation " + column).isTrue();
        }
        for (String column : List.of("id", "run_id", "question_key", "input_hash", "provider",
                "node_codes", "detail_json", "prompt_text", "state", "attempts", "started_at", "error_text")) {
            assertThat(columnExists(dataSource, "analysis_question_checkpoint", column))
                    .as("analysis question " + column).isTrue();
        }
        assertThat(foreignKeyBindings(dataSource, "analysis_question_checkpoint", "fk_analysis_question_run"))
                .containsExactly("run_id->analysis_continuation.id");
        assertRecoveryIndex(dataSource, "analysis_continuation", "idx_analysis_cont_scope", true,
                "workspace_id", "username");
        assertRecoveryIndex(dataSource, "analysis_question_checkpoint", "idx_analysis_question_run", true,
                "run_id", "state");
        assertRecoveryIndex(dataSource, "analysis_question_checkpoint", "uq_analysis_question", false,
                "run_id", "question_key");
    }

    private static void assertRecoveryIndex(DataSource dataSource, String table, String name,
            boolean nonUnique, String... expectedColumns) throws SQLException {
        var columns = new java.util.TreeMap<Integer, String>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.getMetaData().getIndexInfo(
                     connection.getCatalog(), connection.getSchema(), table, false, false)) {
            while (rows.next()) {
                if (name.equals(rows.getString("INDEX_NAME"))) {
                    assertThat(rows.getBoolean("NON_UNIQUE")).as(name + " uniqueness").isEqualTo(nonUnique);
                    columns.put(rows.getInt("ORDINAL_POSITION"), rows.getString("COLUMN_NAME"));
                }
            }
        }
        assertThat(List.copyOf(columns.values())).as(name + " ordered columns").containsExactly(expectedColumns);
    }

    private static void assertReformulationSchema(DataSource dataSource) throws SQLException {
        com.taxonomy.portfolio.reformulation.ReformulationCheckpointIndexContract.verify(dataSource);
        com.taxonomy.portfolio.reformulation.ReformulationAdoptionSchemaContract.verify(dataSource);
        assertThat(tableExists(dataSource, "reformulation_portable_evidence")).isTrue();
        for (String column : List.of("id", "scope_key", "project_key", "requirement_key",
                "target_version_number", "schema_version", "evidence_hash", "target_text_hash",
                "evidence_payload", "created_at")) {
            assertThat(columnExists(dataSource, "reformulation_portable_evidence", column))
                    .as("portable reformulation evidence " + column).isTrue();
        }
        for (String table : List.of("reformulation_usage_session", "reformulation_usage_attempt")) assertThat(tableExists(dataSource, table)).isTrue();
        for (String column : List.of("run_id", "proposal_id", "scope_key", "created_at", "schema_version", "from_first_attempt"))
            assertThat(columnExists(dataSource, "reformulation_usage_session", column)).as("usage session " + column).isTrue();
        for (String column : List.of("id", "run_id", "owner_id", "lease_epoch", "invocation_id", "provider", "source_kind", "retry_index",
                "started_at", "completed_at", "status_code", "outcome", "duration_millis", "input_tokens", "output_tokens", "total_tokens", "cached_input_tokens", "reasoning_tokens", "invalid_usage", "row_version"))
            assertThat(columnExists(dataSource, "reformulation_usage_attempt", column)).as("usage attempt " + column).isTrue();
        assertThat(foreignKeyBindings(dataSource, "reformulation_usage_session", "fk_reform_usage_run")).containsExactly("run_id->reformulation_run.id");
        assertThat(foreignKeyBindings(dataSource, "reformulation_usage_attempt", "fk_reform_usage_attempt_session")).containsExactly("run_id->reformulation_usage_session.run_id");
        assertThat(tableExists(dataSource, "reformulation_recovery_lease")).isTrue();
        for (String column : List.of("run_id", "dispatch_payload", "owner_id", "lease_epoch", "lease_until", "active", "row_version")) {
            assertThat(columnExists(dataSource, "reformulation_recovery_lease", column)).as("recovery " + column).isTrue();
        }
        assertThat(foreignKeyBindings(dataSource, "reformulation_recovery_lease", "fk_reform_recovery_run"))
                .containsExactly("run_id->reformulation_run.id");
        assertThat(tableExists(dataSource, "reformulation_proposal")).isTrue();
        assertThat(tableExists(dataSource, "reformulation_revision")).isTrue();
        assertThat(tableExists(dataSource, "reformulation_run")).isTrue();
        for (String column : List.of("proposal_id", "scope_key", "run_payload", "row_version", "cancelled_by", "cancelled_at")) {
            assertThat(columnExists(dataSource, "reformulation_run", column)).as("run " + column).isTrue();
        }
        assertThat(foreignKeyBindings(dataSource, "reformulation_run", "fk_reform_run_proposal"))
                .containsExactly("proposal_id->reformulation_proposal.id", "scope_key->reformulation_proposal.scope_key");
        assertThat(tableExists(dataSource, "reformulation_node_checkpoint")).isTrue();
        for (String column : List.of("id", "proposal_id", "scope_key", "run_id", "task_kind",
                "input_fingerprint", "result_payload", "created_at")) {
            assertThat(columnExists(dataSource, "reformulation_node_checkpoint", column)).as("checkpoint " + column).isTrue();
        }
        assertThat(foreignKeyBindings(dataSource, "reformulation_node_checkpoint", "fk_reform_checkpoint_proposal"))
                .containsExactly("proposal_id->reformulation_proposal.id", "scope_key->reformulation_proposal.scope_key");
        assertThat(foreignKeyBindings(dataSource, "reformulation_node_checkpoint", "fk_reform_checkpoint_run"))
                .containsExactly("run_id->reformulation_run.id");
        for (String column : List.of("scope_key", "project_id", "requirement_id", "source_version_id",
                "snapshot_id", "baseline_payload", "current_revision", "row_version")) {
            assertThat(columnExists(dataSource, "reformulation_proposal", column)).as("proposal " + column).isTrue();
        }
        for (String column : List.of("proposal_id", "scope_key", "revision_number", "revision_payload")) {
            assertThat(columnExists(dataSource, "reformulation_revision", column)).as("revision " + column).isTrue();
        }
        assertThat(foreignKeyBindings(dataSource, "reformulation_proposal", "fk_reform_source"))
                .containsExactly("source_version_id->project_req_version.id",
                        "requirement_id->project_req_version.requirement_id", "scope_key->project_req_version.scope_key");
        assertThat(foreignKeyBindings(dataSource, "reformulation_proposal", "fk_reform_snapshot"))
                .containsExactly("snapshot_id->req_analysis_snapshot.id",
                        "source_version_id->req_analysis_snapshot.requirement_version_id",
                        "requirement_id->req_analysis_snapshot.requirement_id", "project_id->req_analysis_snapshot.project_id",
                        "scope_key->req_analysis_snapshot.scope_key");
        assertThat(foreignKeyBindings(dataSource, "reformulation_revision", "fk_reform_revision_proposal"))
                .containsExactly("proposal_id->reformulation_proposal.id", "scope_key->reformulation_proposal.scope_key");
    }

    private static List<String> foreignKeyBindings(DataSource dataSource, String table, String constraint) throws SQLException {
        var bindings = new java.util.TreeMap<Integer, String>();
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = connection.getMetaData().getImportedKeys(
                     connection.getCatalog(), connection.getSchema(), table)) {
            while (keys.next()) {
                if (constraint.equals(keys.getString("FK_NAME"))) {
                    bindings.put(keys.getInt("KEY_SEQ"), keys.getString("FKCOLUMN_NAME") + "->"
                            + keys.getString("PKTABLE_NAME") + "." + keys.getString("PKCOLUMN_NAME"));
                }
            }
        }
        return List.copyOf(bindings.values());
    }

    private static void assertIntegrationSchema(DataSource dataSource) throws SQLException {
        for (String table : List.of("interop_connection", "interop_operation", "interop_identity", "interop_checkpoint", "interop_event", "interop_publication", "interop_publish_item", "interop_publish_attempt")) {
            assertThat(tableExists(dataSource, table)).as(table).isTrue();
            assertThat(columnExists(dataSource, table, "scope_id")).as(table + " exact scope").isTrue();
        }
        assertThat(columnExists(dataSource, "interop_operation", "result_file_json")).isTrue();
        assertThat(columnExists(dataSource, "interop_operation", "review_fingerprint")).isTrue();
        assertThat(columnExists(dataSource, "interop_identity", "row_version")).isTrue();
        assertThat(columnExists(dataSource, "interop_checkpoint", "context_json")).isTrue();
        assertThat(columnExists(dataSource, "interop_connection", "common_checkpoint_id")).isTrue();
        assertThat(columnExists(dataSource, "interop_checkpoint", "kind")).isTrue();
        assertThat(columnExists(dataSource, "interop_checkpoint", "baseline_json")).isTrue();
        assertThat(columnExists(dataSource, "interop_checkpoint", "publication_completion_json")).isTrue();
        assertThat(columnExists(dataSource, "interop_publish_item", "request_fingerprint")).isTrue();
        assertThat(columnExists(dataSource, "interop_publish_attempt", "lease_epoch")).isTrue();
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
        new JgitStorageSchemaMigrationConfig()
                .jgitStorageFlywayMigrationStrategy(false)
                .migrate(flyway);
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
                             + " where success = true and version is not null"
                             + " order by installed_rank")) {
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
