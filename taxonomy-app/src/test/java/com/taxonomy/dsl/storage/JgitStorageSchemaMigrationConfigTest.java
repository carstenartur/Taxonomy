package com.taxonomy.dsl.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoptionException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

class JgitStorageSchemaMigrationConfigTest {

    @Test
    void installsCoreSchemaIntoEmptyDatabase() throws Exception {
        DataSource dataSource = dataSource("fresh");

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), false);

        assertTrue(tableExists(dataSource, "git_packs"));
        assertTrue(tableExists(dataSource, "git_reflog"));
        assertEquals(
                List.of("0.1.4", "0.1.5"),
                successfulVersions(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));
    }

    @Test
    void installsIntoSharedSchemaUsingPreMigrationBaseline() throws Exception {
        DataSource dataSource = dataSource("shared");
        execute(dataSource, "create table application_marker (id integer primary key)");

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), false);

        assertTrue(tableExists(dataSource, "application_marker"));
        assertTrue(tableExists(dataSource, "git_packs"));
        assertEquals(
                List.of("0", "0.1.4", "0.1.5"),
                successfulVersions(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));
    }

    @Test
    void refusesLegacySchemaWithoutExplicitOperatorOptIn() throws Exception {
        DataSource dataSource = dataSource("legacy-refusal");
        installLegacySchema(dataSource);
        byte[] original = new byte[] {1, 2, 3, 4};
        insertLegacyPack(dataSource, "legacy", "pack-a", "pack", original);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(
                        flyway(dataSource), false));

        assertTrue(error.getMessage().contains("TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true"));
        assertFalse(columns(dataSource, "git_packs").contains("COMMITTED"));
        assertEquals(255, columnSize(dataSource, "git_reflog", "ref_name"));
        assertFalse(tableExists(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));
        assertArrayEquals(original, packData(dataSource, "legacy", "pack-a", "pack"));
    }

    @Test
    void adoptsActualTaxonomySchemaWithoutRewritingPackOrReflogData() throws Exception {
        DataSource dataSource = dataSource("legacy-adoption");
        installLegacySchema(dataSource);
        byte[] original = new byte[] {9, 8, 7, 6};
        String refName = "refs/heads/" + "a".repeat(200);
        insertLegacyPack(dataSource, "legacy", "pack-a", "reftable", original);
        insertLegacyReflog(dataSource, refName);

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), true);

        Set<String> packColumns = columns(dataSource, "git_packs");
        assertTrue(packColumns.contains("COMMITTED"));
        assertTrue(packColumns.contains("COMMITTED_AT"));
        assertArrayEquals(original, packData(dataSource, "legacy", "pack-a", "reftable"));
        assertEquals(refName, reflogRefName(dataSource));
        assertTrue(columnSize(dataSource, "git_reflog", "ref_name") >= 1024);
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select committed, committed_at from git_packs "
                             + "where repository_name = ? and pack_name = ? and pack_extension = ?")) {
            statement.setString(1, "legacy");
            statement.setString(2, "pack-a");
            statement.setString(3, "reftable");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getBoolean(1));
                assertNotNull(resultSet.getTimestamp(2));
            }
        }
        assertEquals(
                List.of("0", "1"),
                successfulVersions(
                        dataSource,
                        CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE));
        assertEquals(
                List.of(CoreSchemaMigrations.CURRENT_SCHEMA_VERSION),
                successfulVersions(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));

        assertThrows(
                SQLException.class,
                () -> insertLegacyPack(dataSource, "legacy", "pack-a", "reftable", original));
    }

    @Test
    void rejectsDuplicateLegacyPackIdentitiesBeforeChangingSchema() throws Exception {
        DataSource dataSource = dataSource("legacy-duplicate");
        installLegacySchema(dataSource);
        insertLegacyPack(dataSource, "legacy", "pack-a", "pack", new byte[] {1});
        insertLegacyPack(dataSource, "legacy", "pack-a", "pack", new byte[] {2});

        assertThrows(
                LegacyCoreSchemaAdoptionException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(
                        flyway(dataSource), true));

        assertFalse(columns(dataSource, "git_packs").contains("COMMITTED"));
        assertEquals(255, columnSize(dataSource, "git_reflog", "ref_name"));
        assertFalse(tableExists(
                dataSource,
                CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE));
    }

    @Test
    void rejectsPartialCoreSchema() throws Exception {
        DataSource dataSource = dataSource("partial");
        execute(dataSource, """
                create table git_packs (
                    id bigint generated by default as identity not null,
                    repository_name varchar(255) not null,
                    pack_name varchar(255) not null,
                    pack_extension varchar(255) not null,
                    data blob not null,
                    file_size bigint not null,
                    created_at timestamp(6) not null,
                    primary key (id)
                )
                """);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(
                        flyway(dataSource), false));

        assertTrue(error.getMessage().contains("Only one of git_packs and git_reflog exists"));
    }

    @Test
    void establishesHistoryForExactUnversionedCoreSchema() throws Exception {
        DataSource dataSource = dataSource("unversioned-current");
        flyway(dataSource).migrate();
        execute(dataSource, "drop table " + CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway(dataSource), false);

        assertEquals(
                List.of("0.1.4", "0.1.5"),
                successfulVersions(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));
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

    private static void installLegacySchema(DataSource dataSource) throws SQLException {
        execute(dataSource, """
                create table git_packs (
                    id bigint generated by default as identity not null,
                    repository_name varchar(255) not null,
                    pack_name varchar(255) not null,
                    pack_extension varchar(255) not null,
                    data blob not null,
                    file_size bigint not null,
                    created_at timestamp(6) not null,
                    primary key (id)
                )
                """);
        execute(dataSource, "create index idx_pack_repo on git_packs (repository_name)");
        execute(dataSource,
                "create index idx_pack_repo_name on git_packs (repository_name, pack_name)");
        execute(dataSource, """
                create table git_reflog (
                    id bigint generated by default as identity not null,
                    version bigint,
                    repository_name varchar(255) not null,
                    ref_name varchar(255) not null,
                    old_id varchar(40),
                    new_id varchar(40),
                    who_name varchar(255),
                    who_email varchar(255),
                    who_when timestamp(6) not null,
                    message varchar(2048),
                    primary key (id)
                )
                """);
        execute(dataSource, "create index idx_reflog_repo on git_reflog (repository_name)");
        execute(dataSource,
                "create index idx_reflog_repo_ref on git_reflog (repository_name, ref_name)");
    }

    private static void insertLegacyPack(
            DataSource dataSource,
            String repositoryName,
            String packName,
            String extension,
            byte[] data) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     insert into git_packs
                         (repository_name, pack_name, pack_extension, data, file_size, created_at)
                     values (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, repositoryName);
            statement.setString(2, packName);
            statement.setString(3, extension);
            statement.setBytes(4, data);
            statement.setLong(5, data.length);
            statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private static void insertLegacyReflog(DataSource dataSource, String refName)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     insert into git_reflog
                         (version, repository_name, ref_name, old_id, new_id,
                          who_name, who_email, who_when, message)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setLong(1, 0);
            statement.setString(2, "legacy");
            statement.setString(3, refName);
            statement.setString(4, null);
            statement.setString(5, "0123456789012345678901234567890123456789");
            statement.setString(6, "Migration Test");
            statement.setString(7, "migration@example.invalid");
            statement.setTimestamp(8, Timestamp.from(Instant.now()));
            statement.setString(9, "commit: preserve legacy reflog");
            statement.executeUpdate();
        }
    }

    private static byte[] packData(
            DataSource dataSource,
            String repositoryName,
            String packName,
            String extension) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select data from git_packs
                     where repository_name = ? and pack_name = ? and pack_extension = ?
                     """)) {
            statement.setString(1, repositoryName);
            statement.setString(2, packName);
            statement.setString(3, extension);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getBytes(1);
            }
        }
    }

    private static String reflogRefName(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select ref_name from git_reflog")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static List<String> successfulVersions(
            DataSource dataSource, String historyTable) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select version from " + historyTable
                             + " where success = true order by installed_rank")) {
            while (resultSet.next()) {
                versions.add(resultSet.getString(1));
            }
        }
        return versions;
    }

    private static boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getTables(
                    null, connection.getSchema(), "%", new String[] {"TABLE"})) {
                while (resultSet.next()) {
                    if (tableName.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Set<String> columns(DataSource dataSource, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getColumns(
                    null, connection.getSchema(), tableName.toUpperCase(Locale.ROOT), "%")) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static int columnSize(
            DataSource dataSource, String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getColumns(
                    null,
                    connection.getSchema(),
                    tableName.toUpperCase(Locale.ROOT),
                    columnName.toUpperCase(Locale.ROOT))) {
                assertTrue(resultSet.next());
                return resultSet.getInt("COLUMN_SIZE");
            }
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
