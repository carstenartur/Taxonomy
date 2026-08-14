package com.taxonomy.dsl.storage;

import static com.taxonomy.dsl.storage.DatabaseIdentifierTestSupport.quoteExistingColumn;
import static com.taxonomy.dsl.storage.DatabaseIdentifierTestSupport.quoteExistingTable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

class JgitStorageSchema091CompatibilityTest {

    @Test
    void acceptsReleased091ReflogShapeWhileRunningAgainstPinnedCore() throws Exception {
        DataSource dataSource = dataSource("reflog-091-shape");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();

        assertDoesNotThrow(
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));
    }

    @Test
    void rejectsPre091ShapeWithoutReleasedIdOrderingColumn() throws Exception {
        DataSource dataSource = dataSource("reflog-pre-091-short-index");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        revertReleased091Migration(dataSource);
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_id");
        execute(
                dataSource,
                "create index idx_reflog_repo_ref_id "
                        + "on git_reflog (repository_name, ref_name)");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("REPOSITORY_NAME, REF_NAME, ID"));
    }

    @Test
    void stillRejectsUnknownReflogColumns() throws Exception {
        DataSource dataSource = dataSource("reflog-unknown-shape");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        execute(dataSource, "alter table git_reflog add column unsupported_probe integer");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("neither the exact pre-0.9.1 shape"));
        assertTrue(error.getMessage().contains("UNSUPPORTED_PROBE"));
    }

    private static void revertReleased091Migration(DataSource dataSource)
            throws SQLException {
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_key_id");
        execute(dataSource, "alter table git_reflog drop column ref_name_key");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String historyTable = quoteExistingTable(
                    connection, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
            String versionColumn = quoteExistingColumn(
                    connection, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE, "version");
            statement.execute(
                    "delete from " + historyTable
                            + " where " + versionColumn + " = '0.9.1'");
        }
    }

    private static DataSource dataSource(String purpose) {
        JDBCDataSource dataSource = new JDBCDataSource();
        String databaseName = purpose + "_" + UUID.randomUUID().toString().replace("-", "");
        dataSource.setUrl("jdbc:hsqldb:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.HSQLDB_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
