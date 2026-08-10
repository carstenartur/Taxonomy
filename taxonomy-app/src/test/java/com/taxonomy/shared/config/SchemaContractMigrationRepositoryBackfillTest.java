package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaContractMigrationRepositoryBackfillTest {

    @Test
    void mapsAllRelationWorkflowsToWorkspaceSourceRepositories() throws Exception {
        DataSource dataSource = createLegacySchema();
        insertRepositories(dataSource);
        execute(dataSource, """
                insert into user_workspace (workspace_id, source_repository_id)
                values ('workspace-b', 'repo-b')
                """);
        insertLegacyRelationRow(dataSource, "taxonomy_relation", null, "__shared__");
        insertLegacyRelationRow(dataSource, "taxonomy_relation", "workspace-b", "workspace-b");
        insertLegacyRelationRow(dataSource, "relation_proposal", null, "__shared__");
        insertLegacyRelationRow(dataSource, "relation_proposal", "workspace-b", "workspace-b");
        insertLegacyHypothesis(dataSource, null, "central-session", "central");
        insertLegacyHypothesis(dataSource, "workspace-b", "workspace-session", "workspace");

        SchemaContractMigration migration = new SchemaContractMigration(dataSource);
        migration.migrate();
        migration.migrate();

        assertTenantAssignments(dataSource, "taxonomy_relation");
        assertTenantAssignments(dataSource, "relation_proposal");
        assertTenantAssignments(dataSource, "relation_hypothesis");
        assertThat(singleString(dataSource, """
                select analysis_session_scope_key
                from relation_hypothesis
                where owner_username = 'central'
                """))
                .isEqualTo("central-session");

        insertScopedHypothesis(dataSource, "repo-b", null, "central-session", "repo-b");
        assertThatThrownBy(() -> insertScopedHypothesis(
                dataSource, "repo-a", null, "central-session", "duplicate"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertScopedHypothesis(
                dataSource, "missing-repository", null, "new-session", "invalid"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesHypothesisWorkspaceRowsWithoutSourceRepositoryProvenance() throws Exception {
        DataSource dataSource = createLegacySchema();
        insertRepositories(dataSource);
        insertLegacyHypothesis(
                dataSource,
                "workspace-without-metadata",
                "workspace-session",
                "missing-provenance");

        assertThatThrownBy(() -> new SchemaContractMigration(dataSource).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("workspace source repository provenance is missing or ambiguous");
    }

    @Test
    void refusesHistoricNullSessionDuplicatesBeforeAddingNonNullScopeKey() throws Exception {
        DataSource dataSource = createLegacySchema();
        insertRepositories(dataSource);
        insertLegacyHypothesis(dataSource, null, null, "first-null-session");
        insertLegacyHypothesis(dataSource, null, null, "second-null-session");

        assertThatThrownBy(() -> new SchemaContractMigration(dataSource).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("duplicate repository/workspace/session relation groups");
    }

    private static void assertTenantAssignments(
            DataSource dataSource,
            String table) throws SQLException {
        assertThat(singleString(dataSource, "select repository_id from " + table
                + " where workspace_id is null"))
                .isEqualTo("repo-a");
        assertThat(singleString(dataSource, "select repository_id from " + table
                + " where workspace_id = 'workspace-b'"))
                .isEqualTo("repo-b");
    }

    private static DataSource createLegacySchema() throws SQLException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        dataSource.setUrl("jdbc:hsqldb:mem:schema-contract-"
                + UUID.randomUUID().toString().replace("-", ""));
        dataSource.setUsername("SA");
        dataSource.setPassword("");

        execute(dataSource, """
                create table system_repository (
                    repository_id varchar(255) primary key,
                    primary_repo boolean not null)
                """);
        execute(dataSource, """
                create table user_workspace (
                    workspace_id varchar(255) primary key,
                    source_repository_id varchar(255))
                """);
        createLegacyRelationTable(dataSource, "taxonomy_relation", "uk_taxonomy_relation_scope");
        createLegacyRelationTable(dataSource, "relation_proposal", "uk_relation_proposal_scope");
        execute(dataSource, """
                create table relation_hypothesis (
                    id bigint generated by default as identity primary key,
                    repository_id varchar(255),
                    source_node_id varchar(255) not null,
                    target_node_id varchar(255) not null,
                    relation_type varchar(255) not null,
                    analysis_session_id varchar(255),
                    workspace_id varchar(255),
                    owner_username varchar(255),
                    constraint uq_hypothesis_workspace_session_relation
                        unique (workspace_id, source_node_id, target_node_id,
                                relation_type, analysis_session_id))
                """);
        return dataSource;
    }

    private static void createLegacyRelationTable(
            DataSource dataSource,
            String table,
            String constraint) throws SQLException {
        execute(dataSource, """
                create table %s (
                    id bigint generated by default as identity primary key,
                    repository_id varchar(255),
                    source_node_id bigint not null,
                    target_node_id bigint not null,
                    relation_type varchar(255) not null,
                    workspace_id varchar(255),
                    workspace_scope_key varchar(255),
                    constraint %s
                        unique (source_node_id, target_node_id, relation_type, workspace_scope_key))
                """.formatted(table, constraint));
    }

    private static void insertRepositories(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                insert into system_repository (repository_id, primary_repo)
                values ('repo-a', true), ('repo-b', false)
                """);
    }

    private static void insertLegacyRelationRow(
            DataSource dataSource,
            String table,
            String workspaceId,
            String workspaceScopeKey) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        execute(dataSource, """
                insert into %s (
                    source_node_id,
                    target_node_id,
                    relation_type,
                    workspace_id,
                    workspace_scope_key)
                values (1, 2, 'SUPPORTS', %s, '%s')
                """.formatted(table, workspace, workspaceScopeKey));
    }

    private static void insertLegacyHypothesis(
            DataSource dataSource,
            String workspaceId,
            String sessionId,
            String owner) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        String session = sessionId == null ? "null" : "'" + sessionId + "'";
        execute(dataSource, """
                insert into relation_hypothesis (
                    source_node_id,
                    target_node_id,
                    relation_type,
                    analysis_session_id,
                    workspace_id,
                    owner_username)
                values ('BP', 'CP', 'SUPPORTS', %s, %s, '%s')
                """.formatted(session, workspace, owner));
    }

    private static void insertScopedHypothesis(
            DataSource dataSource,
            String repositoryId,
            String workspaceId,
            String sessionId,
            String owner) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        String workspaceScope = workspaceId == null ? "__shared__" : workspaceId;
        String session = sessionId == null ? "null" : "'" + sessionId + "'";
        String sessionScope = sessionId == null ? "__unspecified__" : sessionId;
        execute(dataSource, """
                insert into relation_hypothesis (
                    repository_id,
                    workspace_scope_key,
                    source_node_id,
                    target_node_id,
                    relation_type,
                    analysis_session_id,
                    analysis_session_scope_key,
                    workspace_id,
                    owner_username)
                values ('%s', '%s', 'BP', 'CP', 'SUPPORTS', %s, '%s', %s, '%s')
                """.formatted(
                        repositoryId,
                        workspaceScope,
                        session,
                        sessionScope,
                        workspace,
                        owner));
    }

    private static String singleString(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
