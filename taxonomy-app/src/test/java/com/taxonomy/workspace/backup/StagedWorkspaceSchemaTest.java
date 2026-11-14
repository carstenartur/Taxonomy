package com.taxonomy.workspace.backup;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import org.flywaydb.core.Flyway;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedWorkspaceSchemaTest {
    @Test
    void recognizesOnlyValidatedPrePublicationHistory() throws IOException {
        var database = database();
        assertThat(StagedWorkspaceSchema.predatesPublications(database)).isFalse();

        migrateBusinessSchema(database, "1");
        assertThat(StagedWorkspaceSchema.predatesPublications(database)).isFalse();

        PrincipalSchemaMigration.migrate(database);
        migrateBusinessSchema(database, "32");
        assertThat(StagedWorkspaceSchema.predatesPublications(database)).isTrue();

        new JdbcTemplate(database).update("""
                update "restore_business_schema_history" set "checksum" = "checksum" + 1 where "version" = '32'
                """);
        assertThat(StagedWorkspaceSchema.predatesPublications(database)).isFalse();
    }

    @Test
    void missingPublicationTablesAfterTheirMigrationAreNeverTreatedAsLegacy() throws IOException {
        var database = migratedDatabase("32.1");
        assertThat(StagedWorkspaceSchema.predatesPublications(database)).isFalse();

        var rows = new JdbcTemplate(database);
        rows.execute("drop table workspace_publication_decision");
        assertThatThrownBy(() -> StagedWorkspaceSchema.predatesPublications(database))
                .isInstanceOf(IOException.class).hasMessageContaining("incomplete");

        rows.execute("drop table workspace_publication_operation");
        assertThat(StagedWorkspaceSchema.predatesPublications(database)).isFalse();
    }

    @Test
    void recognizesTheProjectionScopeMigrationAsPredatingTheHistoryIndex() throws IOException {
        var database = migratedDatabase("32.2");
        assertThat(StagedWorkspaceSchema.predatesHistoryIndex(database)).isTrue();
    }

    @Test
    void onlyAnExistingTableWithVerifiedOlderHistoryCanOmitAColumn() throws IOException {
        var database = database();
        assertThat(StagedWorkspaceSchema.missingColumnBefore(database, "system_repository", "fork_initiator_key", "32"))
                .isFalse();

        migrateBusinessSchema(database, "1");
        PrincipalSchemaMigration.migrate(database);
        migrateBusinessSchema(database, "27");
        assertThat(StagedWorkspaceSchema.missingColumnBefore(database, "workspace_publication_operation", "operation_id", "32.1"))
                .isFalse();
        assertThat(StagedWorkspaceSchema.missingColumnBefore(database, "system_repository", "repository_id", "32"))
                .isFalse();
        assertThat(StagedWorkspaceSchema.missingColumnBefore(database, "system_repository", "fork_initiator_key", "32"))
                .isTrue();

        migrateBusinessSchema(database, "32");
        new JdbcTemplate(database).execute("alter table system_repository drop column fork_initiator_key");
        assertThat(StagedWorkspaceSchema.missingColumnBefore(database, "system_repository", "fork_initiator_key", "32"))
                .isFalse();
    }

    private static JDBCDataSource database() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:staged-workspace-schema-" + UUID.randomUUID());
        database.setUser("sa");
        return database;
    }

    private static JDBCDataSource migratedDatabase(String target) {
        var database = database();
        migrateBusinessSchema(database, "1");
        PrincipalSchemaMigration.migrate(database);
        migrateBusinessSchema(database, target);
        return database;
    }

    private static void migrateBusinessSchema(JDBCDataSource database, String target) {
        Flyway.configure().dataSource(database).table("restore_business_schema_history")
                .locations("classpath:db/restore/hsqldb").target(target).load().migrate();
    }
}
