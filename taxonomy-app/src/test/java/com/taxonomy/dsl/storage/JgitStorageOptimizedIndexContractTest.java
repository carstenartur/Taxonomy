package com.taxonomy.dsl.storage;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import org.flywaydb.core.Flyway;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JgitStorageOptimizedIndexContractTest {

    @Test
    void acceptsReleasedOptimizedIndexesThroughLeadingKeyCoverage() {
        DataSource dataSource = dataSource("optimized");
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), false);

        // Core 0.1.17 already installs the optimized form: redundant standalone
        // pack/reflog indexes are absent and their access paths are covered by
        // the remaining unique/ordered indexes. A second startup must accept it.
        assertDoesNotThrow(() ->
                JgitStorageSchemaMigrationConfig.migrateCoreSchema(
                        flyway(dataSource), false));
    }

    @Test
    void stillRejectsOptimizedSchemaWithoutPackIdentityAccessPath() throws Exception {
        DataSource dataSource = dataSource("missing-pack-identity");
        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), false);
        execute(dataSource,
                "alter table git_packs drop constraint uk_pack_repo_name_ext");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(
                        flyway(dataSource), false));

        assertTrue(error.getMessage().contains("REPOSITORY_NAME, PACK_NAME"));
    }

    private static DataSource dataSource(String purpose) {
        JDBCDataSource dataSource = new JDBCDataSource();
        String databaseName = purpose + "_"
                + UUID.randomUUID().toString().replace("-", "");
        dataSource.setUrl("jdbc:hsqldb:mem:" + databaseName
                + ";DB_CLOSE_DELAY=-1");
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

    private static void execute(DataSource dataSource, String sql)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
