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

/** Real PostgreSQL evidence for proposal review branch/commit/causation integrity. */
@Testcontainers
@Tag("db-postgres")
class ProposalReviewAuthorityPostgresIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void migratesLegacyProposalsAndEnforcesCompleteGitAuthorityMetadata()
            throws Exception {
        DataSource dataSource = isolatedDataSource("proposal_review_authority");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "10");
        insertRepository(dataSource);
        insertNodes(dataSource);
        insertProposal(dataSource);

        migrateApplicationTo(dataSource, "11");

        assertThat(singleString(dataSource, """
                select review_commit_id
                from relation_proposal
                where provenance = 'legacy-proposal'
                """))
                .isNull();

        String commit = "a".repeat(40);
        execute(dataSource, """
                update relation_proposal
                set review_branch = 'review',
                    review_commit_id = '%s',
                    review_causation_id = 'proposal-17'
                where provenance = 'legacy-proposal'
                """.formatted(commit));
        assertThat(singleString(dataSource, """
                select review_commit_id
                from relation_proposal
                where provenance = 'legacy-proposal'
                """))
                .isEqualTo(commit);

        assertThatThrownBy(() -> execute(dataSource, """
                update relation_proposal
                set review_branch = 'review',
                    review_commit_id = null,
                    review_causation_id = 'proposal-partial'
                where provenance = 'legacy-proposal'
                """))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> execute(dataSource, """
                update relation_proposal
                set review_branch = 'review',
                    review_commit_id = 'not-a-git-commit',
                    review_causation_id = 'proposal-invalid'
                where provenance = 'legacy-proposal'
                """))
                .isInstanceOf(SQLException.class);
    }

    private static void insertRepository(DataSource dataSource) throws SQLException {
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
                    'repo-a',
                    'Repository A',
                    'INTERNAL_SHARED',
                    'draft',
                    true,
                    current_timestamp,
                    'storage-repo-a',
                    'repo-a',
                    'ORGANIZATION',
                    'ACTIVE',
                    'SYSTEM',
                    'system',
                    'system',
                    current_timestamp)
                """);
    }

    private static void insertNodes(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                insert into taxonomy_node (
                    code,
                    name_en,
                    node_level,
                    has_embedding,
                    incoming_relation_count,
                    outgoing_relation_count,
                    requirement_coverage_count)
                values
                    ('APP-1', 'Application', 0, false, 0, 0, 0),
                    ('SVC-1', 'Service', 0, false, 0, 0, 0)
                """);
    }

    private static void insertProposal(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                insert into relation_proposal (
                    repository_id,
                    source_node_id,
                    target_node_id,
                    relation_type,
                    status,
                    confidence,
                    provenance,
                    created_at,
                    workspace_scope_key)
                select
                    'repo-a',
                    source.id,
                    target.id,
                    'USES',
                    'PENDING',
                    0.9,
                    'legacy-proposal',
                    current_timestamp,
                    '__shared__'
                from taxonomy_node source, taxonomy_node target
                where source.code = 'APP-1'
                  and target.code = 'SVC-1'
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
