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

/** Focused migration evidence for repository-scoped relation hypotheses. */
@Testcontainers
@Tag("db-postgres")
class RelationHypothesisTenantMigrationPostgresIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void preservesCentralAndWorkspaceRepositoryProvenanceAndLocalUniqueness() throws Exception {
        DataSource dataSource = isolatedDataSource("hypothesis_tenant_upgrade");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "6");
        insertRepository(dataSource, "repo-a", "repo-a", true);
        insertRepository(dataSource, "repo-b", "repo-b", false);
        insertWorkspace(dataSource, "workspace-b", "repo-b");
        insertLegacyHypothesis(dataSource, null, "session-central", "legacy");
        insertLegacyHypothesis(
                dataSource, "workspace-b", "session-workspace", "legacy-workspace");

        migrateApplicationTo(dataSource, "7");

        assertThat(singleString(dataSource, """
                select repository_id
                from relation_hypothesis
                where analysis_session_id = 'session-central'
                """))
                .isEqualTo("repo-a");
        assertThat(singleString(dataSource, """
                select repository_id
                from relation_hypothesis
                where analysis_session_id = 'session-workspace'
                """))
                .isEqualTo("repo-b");
        assertThat(singleString(dataSource, """
                select workspace_scope_key
                from relation_hypothesis
                where analysis_session_id = 'session-central'
                """))
                .isEqualTo("__shared__");
        assertThat(singleString(dataSource, """
                select workspace_scope_key
                from relation_hypothesis
                where analysis_session_id = 'session-workspace'
                """))
                .isEqualTo("workspace-b");
        assertThat(singleString(dataSource, """
                select analysis_session_scope_key
                from relation_hypothesis
                where analysis_session_id = 'session-central'
                """))
                .isEqualTo("session-central");

        insertScopedHypothesis(
                dataSource, "repo-b", null, "session-central", "same-key-other-repository");
        assertThat(singleLong(dataSource, "select count(*) from relation_hypothesis"))
                .isEqualTo(3L);

        assertThatThrownBy(() -> insertScopedHypothesis(
                dataSource, "repo-a", null, "session-central", "duplicate-same-repository"))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> insertScopedHypothesis(
                dataSource, "missing-repository", null, "session-new", "invalid-repository"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesLegacyCentralBackfillWithoutExactlyOnePrimaryRepository() throws Exception {
        DataSource dataSource = isolatedDataSource("hypothesis_primary_failure");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "6");
        insertLegacyHypothesis(dataSource, null, "session-central", "legacy");

        assertThatThrownBy(() -> migrateApplicationTo(dataSource, "7"))
                .hasStackTraceContaining("expected exactly one primary repository");
    }

    @Test
    void refusesWorkspaceHypothesisWhenSourceProvenanceIsMissing() throws Exception {
        DataSource dataSource = isolatedDataSource("hypothesis_workspace_failure");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "6");
        insertRepository(dataSource, "repo-a", "repo-a", true);
        insertLegacyHypothesis(
                dataSource, "workspace-b", "session-workspace", "legacy-workspace");

        assertThatThrownBy(() -> migrateApplicationTo(dataSource, "7"))
                .hasStackTraceContaining("workspace source repository provenance is missing or ambiguous");
    }

    @Test
    void refusesNullSessionDuplicatesThatTheHistoricConstraintAllowed() throws Exception {
        DataSource dataSource = isolatedDataSource("hypothesis_null_duplicate_failure");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "6");
        insertRepository(dataSource, "repo-a", "repo-a", true);
        insertLegacyHypothesis(dataSource, null, null, "legacy-null-one");
        insertLegacyHypothesis(dataSource, null, null, "legacy-null-two");

        assertThatThrownBy(() -> migrateApplicationTo(dataSource, "7"))
                .hasStackTraceContaining("duplicate repository/workspace/session relation group");
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

    private static void insertWorkspace(
            DataSource dataSource,
            String workspaceId,
            String sourceRepositoryId) throws SQLException {
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
                    source_repository_id,
                    archived,
                    is_default)
                values (
                    '%s',
                    'workspace-user',
                    '%s',
                    'draft',
                    'draft',
                    false,
                    current_timestamp,
                    'ACTIVE',
                    'INTERNAL_SHARED',
                    '%s',
                    false,
                    false)
                """.formatted(workspaceId, workspaceId, sourceRepositoryId));
    }

    private static void insertLegacyHypothesis(
            DataSource dataSource,
            String workspaceId,
            String sessionId,
            String ownerUsername) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        String session = sessionId == null ? "null" : "'" + sessionId + "'";
        execute(dataSource, """
                insert into relation_hypothesis (
                    source_node_id,
                    target_node_id,
                    relation_type,
                    status,
                    confidence,
                    analysis_session_id,
                    applied_in_current_analysis,
                    created_at,
                    workspace_id,
                    owner_username)
                values (
                    'BP',
                    'CP',
                    'SUPPORTS',
                    'PROVISIONAL',
                    0.75,
                    %s,
                    false,
                    current_timestamp,
                    %s,
                    '%s')
                """.formatted(session, workspace, ownerUsername));
    }

    private static void insertScopedHypothesis(
            DataSource dataSource,
            String repositoryId,
            String workspaceId,
            String sessionId,
            String ownerUsername) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        String workspaceScope = workspaceId == null ? "__shared__" : workspaceId;
        String session = sessionId == null ? "null" : "'" + sessionId + "'";
        String sessionScope = sessionId == null ? "__unspecified__" : sessionId;
        execute(dataSource, """
                insert into relation_hypothesis (
                    repository_id,
                    source_node_id,
                    target_node_id,
                    relation_type,
                    status,
                    confidence,
                    analysis_session_id,
                    analysis_session_scope_key,
                    applied_in_current_analysis,
                    created_at,
                    workspace_id,
                    workspace_scope_key,
                    owner_username)
                values (
                    '%s',
                    'BP',
                    'CP',
                    'SUPPORTS',
                    'PROVISIONAL',
                    0.75,
                    %s,
                    '%s',
                    false,
                    current_timestamp,
                    %s,
                    '%s',
                    '%s')
                """.formatted(
                        repositoryId,
                        session,
                        sessionScope,
                        workspace,
                        workspaceScope,
                        ownerUsername));
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
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
    }

    private static DataSource baseDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(database.getJdbcUrl());
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
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
