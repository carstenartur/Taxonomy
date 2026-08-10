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
    void mapsRelationsAndProposalsToWorkspaceSourceRepositories() throws Exception {
        DataSource dataSource = createLegacySchema();
        insertRepositories(dataSource);
        execute(dataSource, """
                insert into user_workspace (workspace_id, source_repository_id)
                values ('workspace-b', 'repo-b')
                """);
        insertLegacyRow(dataSource, "taxonomy_relation", null, "__shared__", 1L);
        insertLegacyRow(dataSource, "taxonomy_relation", "workspace-b", "workspace-b", 1L);
        insertLegacyRow(dataSource, "relation_proposal", null, "__shared__", 1L);
        insertLegacyRow(dataSource, "relation_proposal", "workspace-b", "workspace-b", 1L);

        SchemaContractMigration migration = new SchemaContractMigration(dataSource);
        migration.migrate();
        migration.migrate();

        assertTenantAssignments(dataSource, "taxonomy_relation");
        assertTenantAssignments(dataSource, "relation_proposal");

        insertScopedRow(dataSource, "taxonomy_relation", "repo-b", "__shared__", 1L);
        insertScopedRow(dataSource, "relation_proposal", "repo-b", "__shared__", 1L);

        assertThatThrownBy(() -> insertScopedRow(
                dataSource, "taxonomy_relation", "repo-a", "__shared__", 1L))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertScopedRow(
                dataSource, "relation_proposal", "repo-a", "__shared__", 1L))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertScopedRow(
                dataSource, "relation_proposal", "missing-repository", "__shared__", 9L))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesProposalWorkspaceRowsWithoutSourceRepositoryProvenance() throws Exception {
        DataSource dataSource = createLegacySchema();
        insertRepositories(dataSource);
        execute(dataSource, """
                insert into user_workspace (workspace_id, source_repository_id)
                values ('workspace-b', 'repo-b')
                """);
        // Include one valid workspace row so all supported JDBC drivers enter
        // batch mode before the remaining unbound proposal is diagnosed.
        insertLegacyRow(
                dataSource,
                "relation_proposal",
                "workspace-b",
                "workspace-b",
                10L);
        insertLegacyRow(
                dataSource,
                "relation_proposal",
                "workspace-without-metadata",
                "workspace-without-metadata",
                20L);

        assertThatThrownBy(() -> new SchemaContractMigration(dataSource).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("workspace source repository provenance is missing or ambiguous");
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
        createLegacyTenantTable(dataSource, "taxonomy_relation", "uk_taxonomy_relation_scope");
        createLegacyTenantTable(dataSource, "relation_proposal", "uk_relation_proposal_scope");
        return dataSource;
    }

    private static void createLegacyTenantTable(
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

    private static void insertLegacyRow(
            DataSource dataSource,
            String table,
            String workspaceId,
            String workspaceScopeKey,
            long sourceId) throws SQLException {
        String workspace = workspaceId == null ? "null" : "'" + workspaceId + "'";
        execute(dataSource, """
                insert into %s (
                    source_node_id,
                    target_node_id,
                    relation_type,
                    workspace_id,
                    workspace_scope_key)
                values (%d, %d, 'SUPPORTS', %s, '%s')
                """.formatted(table, sourceId, sourceId + 1, workspace, workspaceScopeKey));
    }

    private static void insertScopedRow(
            DataSource dataSource,
            String table,
            String repositoryId,
            String workspaceScopeKey,
            long sourceId) throws SQLException {
        execute(dataSource, """
                insert into %s (
                    repository_id,
                    source_node_id,
                    target_node_id,
                    relation_type,
                    workspace_scope_key)
                values ('%s', %d, %d, 'SUPPORTS', '%s')
                """.formatted(
                        table,
                        repositoryId,
                        sourceId,
                        sourceId + 1,
                        workspaceScopeKey));
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
