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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL evidence for the exact relation branch projection checkpoint. */
@Testcontainers
@Tag("db-postgres")
@Tag("docker-required")
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

    @Test
    void vectorGenerationsRetainTheirBytesAcrossConnectionsAndPurgeWithOnlyTheirOwningCheckpoint() throws Exception {
        DataSource dataSource = isolatedDataSource("branch_graph_vectors");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "52");
        insertRepository(dataSource, "vector-repo", true);
        for (String branch : java.util.List.of("main", "draft")) {
            insertCheckpoint(dataSource, "vector-repo", null, "__shared__", branch, "a".repeat(40), 1);
            try (var connection = dataSource.getConnection();
                 var insert = connection.prepareStatement("""
                    insert into branch_graph_vector_generation
                        (checkpoint_id,generation_id,state,source_commit,source_key,catalogue_revision,definition_key,
                         model_key,model_identity,text_format,index_format,relations_key,node_count,relation_count,index_sha256,
                         index_bytes,started_at,started_by,published_at)
                    select id,?,'READY',authoritative_commit_id,?,'edition',?,?,?,'text-v1','lucene-v1',?,1,1,?,
                           ?,current_timestamp,'operator',current_timestamp
                    from relation_decision_projection_checkpoint where repository_id=? and workspace_scope_key=? and branch=?
                    """)) {
                insert.setString(1, java.util.UUID.randomUUID().toString());
                insert.setString(2, "a".repeat(64)); insert.setString(3, "b".repeat(64));
                insert.setString(4, "c".repeat(64)); insert.setString(5, "{}"); insert.setString(6, "d".repeat(64));
                insert.setString(7, "e".repeat(64)); insert.setBytes(8, new byte[]{1,2,3});
                insert.setString(9, "vector-repo"); insert.setString(10, "__shared__"); insert.setString(11, branch);
                assertThat(insert.executeUpdate()).isOne();
            }
        }
        assertThat(singleLong(dataSource, "select count(*) from branch_graph_vector_generation where index_bytes=decode('010203','hex')")).isEqualTo(2);
        execute(dataSource, "delete from relation_decision_projection_checkpoint where repository_id='vector-repo' and branch='draft'");
        assertThat(singleLong(dataSource, "select count(*) from branch_graph_vector_generation")).isOne();
        assertThat(singleLong(dataSource, """
                select count(*) from branch_graph_vector_generation v join relation_decision_projection_checkpoint p
                on p.id=v.checkpoint_id where p.repository_id='vector-repo' and p.branch='main'
                """)).isOne();
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
        new JgitStorageSchemaMigrationConfig()
                .jgitStorageFlywayMigrationStrategy(false)
                .migrate(flyway);
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
