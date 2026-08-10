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

/** Focused migration evidence for repository-scoped relation proposals. */
@Testcontainers
@Tag("db-postgres")
class RelationProposalTenantMigrationPostgresIT {

    @Container
    @SuppressWarnings("rawtypes")
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("taxonomy")
            .withUsername("taxonomy")
            .withPassword("taxonomy");

    @Test
    void backfillsLegacyProposalsAndMakesUniquenessRepositoryLocal() throws Exception {
        DataSource dataSource = isolatedDataSource("proposal_tenant_upgrade");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "5");
        insertRepository(dataSource, "repo-a", "repo-a", true);
        insertNodes(dataSource);
        insertLegacyProposal(dataSource);

        migrateApplicationTo(dataSource, "6");

        assertThat(singleString(dataSource, """
                select repository_id
                from relation_proposal
                where provenance = 'legacy'
                """))
                .isEqualTo("repo-a");
        assertThat(singleString(dataSource, """
                select workspace_scope_key
                from relation_proposal
                where provenance = 'legacy'
                """))
                .isEqualTo("__shared__");

        insertRepository(dataSource, "repo-b", "repo-b", false);
        insertProposal(dataSource, "repo-b", "same-key-other-repository");
        assertThat(singleLong(dataSource, "select count(*) from relation_proposal"))
                .isEqualTo(2L);

        assertThatThrownBy(() -> insertProposal(
                dataSource, "repo-a", "duplicate-in-same-repository"))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> insertProposal(
                dataSource, "missing-repository", "invalid-repository"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesLegacyProposalBackfillWithoutExactlyOnePrimaryRepository() throws Exception {
        DataSource dataSource = isolatedDataSource("proposal_tenant_upgrade_failure");
        migrateJgit(dataSource);
        migrateApplicationTo(dataSource, "5");
        insertNodes(dataSource);
        insertLegacyProposal(dataSource);

        assertThatThrownBy(() -> migrateApplicationTo(dataSource, "6"))
                .hasStackTraceContaining("expected exactly one primary repository");
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
                    ('BP', 'Business Process', 0, false, 0, 0, 0),
                    ('CP', 'Capability', 0, false, 0, 0, 0)
                """);
    }

    private static void insertLegacyProposal(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                insert into relation_proposal (
                    source_node_id,
                    target_node_id,
                    relation_type,
                    status,
                    confidence,
                    provenance,
                    created_at,
                    workspace_scope_key)
                select
                    source.id,
                    target.id,
                    'SUPPORTS',
                    'PENDING',
                    0.75,
                    'legacy',
                    current_timestamp,
                    '__shared__'
                from taxonomy_node source, taxonomy_node target
                where source.code = 'BP'
                  and target.code = 'CP'
                """);
    }

    private static void insertProposal(
            DataSource dataSource,
            String repositoryId,
            String provenance) throws SQLException {
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
                    '%s',
                    source.id,
                    target.id,
                    'SUPPORTS',
                    'PENDING',
                    0.75,
                    '%s',
                    current_timestamp,
                    '__shared__'
                from taxonomy_node source, taxonomy_node target
                where source.code = 'BP'
                  and target.code = 'CP'
                """.formatted(repositoryId, provenance));
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
        dataSource.setUrl(database.getJdbcUrl() + "?currentSchema=" + schema);
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
