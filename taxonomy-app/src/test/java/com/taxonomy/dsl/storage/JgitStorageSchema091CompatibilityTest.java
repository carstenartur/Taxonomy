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
        revertReleased092Migration(dataSource);

        assertDoesNotThrow(
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));
    }

    @Test
    void rejectsPre091ShapeWithoutReleasedIdOrderingColumn() throws Exception {
        DataSource dataSource = dataSource("reflog-pre-091-short-index");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        revertReleased092Migration(dataSource);
        revertReleased091Migration(dataSource);
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_id");
        execute(
                dataSource,
                "create index idx_reflog_repo_ref_id "
                        + "on git_reflog (repository_name, ref_name)");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains(
                "REPOSITORY_NAME, REF_NAME, ID"));
        assertTrue(error.getMessage().contains(
                "IDX_REFLOG_REPO_REF_ID"));
    }

    @Test
    void rejectsApplied091HistoryWithoutReferenceKeyColumn() throws Exception {
        DataSource dataSource = dataSource("reflog-091-history-without-column");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        revertReleased092Migration(dataSource);
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_key_id");
        execute(dataSource, "alter table git_reflog drop column ref_name_key");
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_id");
        execute(
                dataSource,
                "create index idx_reflog_repo_ref_id "
                        + "on git_reflog (repository_name, ref_name, id desc)");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("Core migration 0.9.1"));
        assertTrue(error.getMessage().contains("migration applied=true"));
        assertTrue(error.getMessage().contains("REF_NAME_KEY"));
    }

    @Test
    void rejectsCurrentPhysicalShapeWithout091History() throws Exception {
        DataSource dataSource = dataSource("reflog-091-column-without-history");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        revertReleased092Migration(dataSource);
        deleteCoreHistoryVersion(dataSource, "0.9.1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("Core migration 0.9.1"));
        assertTrue(error.getMessage().contains("migration applied=false"));
        assertTrue(error.getMessage().contains("REF_NAME_KEY"));
    }

    @Test
    void rejectsApplied091HistoryWithoutReleasedReferenceKeyIndex() throws Exception {
        DataSource dataSource = dataSource("reflog-091-history-without-index");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        revertReleased092Migration(dataSource);
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_key_id");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("REPOSITORY_NAME, REF_NAME_KEY, ID"));
    }

    @Test
    void rejectsApplied092HistoryWithoutDeliveryIdColumn() throws Exception {
        DataSource dataSource = dataSource("reflog-092-history-without-column");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        execute(dataSource, "drop index if exists idx_reflog_repo_delivery");
        execute(dataSource, "alter table git_reflog drop column delivery_id");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("Core migration 0.9.2"));
        assertTrue(error.getMessage().contains("migration applied=true"));
        assertTrue(error.getMessage().contains("DELIVERY_ID"));
    }

    @Test
    void rejectsCurrentPhysicalShapeWithout092History() throws Exception {
        DataSource dataSource = dataSource("reflog-092-column-without-history");
        Flyway flyway = flyway(dataSource);
        flyway.migrate();
        deleteCoreHistoryVersion(dataSource, "0.9.2");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false));

        assertTrue(error.getMessage().contains("Core migration 0.9.2"));
        assertTrue(error.getMessage().contains("migration applied=false"));
        assertTrue(error.getMessage().contains("DELIVERY_ID"));
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

        assertTrue(error.getMessage().contains(
                "neither the exact pre-0.9.1, 0.9.1 nor 0.9.2 shape"));
        assertTrue(error.getMessage().contains("UNSUPPORTED_PROBE"));
    }

    private static void revertReleased092Migration(DataSource dataSource)
            throws SQLException {
        execute(dataSource, "drop index if exists idx_reflog_repo_delivery");
        execute(dataSource, "alter table git_reflog drop column delivery_id");
        deleteCoreHistoryVersion(dataSource, "0.9.2");
    }

    private static void revertReleased091Migration(DataSource dataSource)
            throws SQLException {
        execute(dataSource, "drop index if exists idx_reflog_repo_ref_key_id");
        execute(dataSource, "alter table git_reflog drop column ref_name_key");
        deleteCoreHistoryVersion(dataSource, "0.9.1");
    }

    private static void deleteCoreHistoryVersion(DataSource dataSource, String version)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String historyTable = quoteExistingTable(
                    connection, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
            String versionColumn = quoteExistingColumn(
                    connection, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE, "version");
            statement.execute(
                    "delete from " + historyTable
                            + " where " + versionColumn + " = '" + version + "'");
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
