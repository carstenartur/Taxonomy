package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitIndexSchemaMigrationTest {

    @Test
    void removesAmbiguousLegacyProjectionAndEnforcesTenantLocalIdentity()
            throws Exception {
        DataSource dataSource = createLegacySchema();
        insertRepositories(dataSource);
        execute(dataSource, """
                insert into architecture_commit_index (
                    commit_id, branch, commit_timestamp, indexed_at)
                values (
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'draft',
                    current_timestamp,
                    current_timestamp)
                """);

        SchemaContractMigration migration = new SchemaContractMigration(dataSource);
        migration.migrate();
        migration.migrate();

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
                dataSource, "missing", null, "__shared__", "draft",
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

    @Test
    void preservesAndCanonicalizesRowsOnlyWhenTheFullTargetContractAlreadyExists()
            throws Exception {
        DataSource dataSource = createTargetSchema();
        insertRepositories(dataSource);
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
                    'repo-a',
                    '  workspace-a  ',
                    'stale-scope',
                    'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                    '  draft  ',
                    current_timestamp,
                    current_timestamp)
                """);

        SchemaContractMigration migration = new SchemaContractMigration(dataSource);
        migration.migrate();
        migration.migrate();

        assertThat(singleLong(dataSource,
                "select count(*) from architecture_commit_index"))
                .isEqualTo(1L);
        assertThat(singleString(dataSource, """
                select repository_id
                from architecture_commit_index
                """)).isEqualTo("repo-a");
        assertThat(singleString(dataSource, """
                select workspace_id
                from architecture_commit_index
                """)).isEqualTo("workspace-a");
        assertThat(singleString(dataSource, """
                select workspace_scope_key
                from architecture_commit_index
                """)).isEqualTo("workspace-a");
        assertThat(singleString(dataSource, """
                select branch
                from architecture_commit_index
                """)).isEqualTo("draft");
    }

    @Test
    void purgesEveryRowFromAPartiallyMigratedProjectionInsteadOfKeepingAMixedIndex()
            throws Exception {
        DataSource dataSource = createPartiallyMigratedSchema();
        insertRepositories(dataSource);
        insertScopedWithoutConstraint(
                dataSource,
                "repo-a",
                "workspace-a",
                "workspace-a",
                "draft",
                "1111111111111111111111111111111111111111");
        execute(dataSource, """
                insert into architecture_commit_index (
                    repository_id,
                    workspace_scope_key,
                    commit_id,
                    branch,
                    commit_timestamp,
                    indexed_at)
                values (
                    null,
                    '__shared__',
                    '2222222222222222222222222222222222222222',
                    'draft',
                    current_timestamp,
                    current_timestamp)
                """);

        new SchemaContractMigration(dataSource).migrate();

        assertThat(singleLong(dataSource,
                "select count(*) from architecture_commit_index"))
                .isZero();
    }

    @Test
    void purgesConflictingPartiallyMigratedProjectionInsteadOfGuessing()
            throws Exception {
        DataSource dataSource = createPartiallyMigratedSchema();
        insertRepositories(dataSource);
        String duplicate = "ffffffffffffffffffffffffffffffffffffffff";
        insertScopedWithoutConstraint(
                dataSource, "repo-a", null, "__shared__", "draft", duplicate);
        insertScopedWithoutConstraint(
                dataSource, "repo-a", null, "__shared__", "draft", duplicate);

        new SchemaContractMigration(dataSource).migrate();

        assertThat(singleLong(dataSource,
                "select count(*) from architecture_commit_index"))
                .isZero();
        insertScoped(dataSource, "repo-a", null, "__shared__", "draft", duplicate);
        assertThatThrownBy(() -> insertScoped(
                dataSource, "repo-a", null, "__shared__", "draft", duplicate))
                .isInstanceOf(SQLException.class);
    }

    private static DataSource createLegacySchema() throws SQLException {
        DataSource dataSource = dataSource();
        createRepositoryTable(dataSource);
        execute(dataSource, """
                create table architecture_commit_index (
                    id bigint generated by default as identity primary key,
                    commit_id varchar(40) not null,
                    branch varchar(255),
                    commit_timestamp timestamp not null,
                    indexed_at timestamp not null,
                    constraint uq_commit_index_legacy unique (commit_id))
                """);
        return dataSource;
    }

    private static DataSource createPartiallyMigratedSchema() throws SQLException {
        DataSource dataSource = dataSource();
        createRepositoryTable(dataSource);
        execute(dataSource, """
                create table architecture_commit_index (
                    id bigint generated by default as identity primary key,
                    repository_id varchar(255),
                    workspace_id varchar(255),
                    workspace_scope_key varchar(255),
                    commit_id varchar(40) not null,
                    branch varchar(255),
                    commit_timestamp timestamp not null,
                    indexed_at timestamp not null)
                """);
        return dataSource;
    }

    private static DataSource createTargetSchema() throws SQLException {
        DataSource dataSource = dataSource();
        createRepositoryTable(dataSource);
        execute(dataSource, """
                create table architecture_commit_index (
                    id bigint generated by default as identity primary key,
                    repository_id varchar(255) not null,
                    workspace_id varchar(255),
                    workspace_scope_key varchar(255) not null,
                    commit_id varchar(40) not null,
                    branch varchar(255) not null,
                    commit_timestamp timestamp not null,
                    indexed_at timestamp not null,
                    constraint fk_commit_index_repository
                        foreign key (repository_id)
                        references system_repository (repository_id),
                    constraint uq_commit_index_repository_workspace_branch_commit
                        unique (
                            repository_id,
                            workspace_scope_key,
                            branch,
                            commit_id))
                """);
        return dataSource;
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        dataSource.setUrl("jdbc:hsqldb:mem:commit-index-"
                + UUID.randomUUID().toString().replace("-", ""));
        dataSource.setUsername("SA");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createRepositoryTable(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                create table system_repository (
                    repository_id varchar(255) primary key,
                    primary_repo boolean not null)
                """);
    }

    private static void insertRepositories(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                insert into system_repository (repository_id, primary_repo)
                values ('repo-a', true), ('repo-b', false)
                """);
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

    private static void insertScopedWithoutConstraint(
            DataSource dataSource,
            String repositoryId,
            String workspaceId,
            String workspaceScope,
            String branch,
            String commitId) throws SQLException {
        insertScoped(dataSource,
                repositoryId, workspaceId, workspaceScope, branch, commitId);
    }

    private static boolean columnExists(
            DataSource dataSource,
            String table,
            String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(
                     connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnNullable(
            DataSource dataSource,
            String table,
            String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(
                     connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return columns.getInt("NULLABLE")
                            != DatabaseMetaData.columnNoNulls;
                }
            }
        }
        throw new IllegalArgumentException("Column not found: " + table + "." + column);
    }

    private static long singleLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
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
