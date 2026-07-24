package com.taxonomy.dsl.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JgitStorageSchemaMigrationTest {

    @Test
    void installsCoreSchemaBesideExistingTaxonomyTablesAndIsIdempotent() throws Exception {
        DataSource dataSource = newDataSource();
        execute(dataSource, "create table taxonomy_marker (id integer primary key)");
        Flyway flyway = normalFlyway(dataSource);

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false);

        assertThat(tableExists(dataSource, "git_packs")).isTrue();
        assertThat(tableExists(dataSource, "git_reflog")).isTrue();
        assertThat(tableExists(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)).isTrue();
        assertThatCode(() -> JgitStorageSchemaMigrationConfig.migrateCoreSchema(flyway, false))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesLegacyTaxonomySchemaWithoutExplicitOperatorOptIn() throws Exception {
        DataSource dataSource = newDataSource();
        createLegacyTaxonomySchema(dataSource);
        insertPack(dataSource, "taxonomy-dsl", "pack-a", "pack", "payload-a");

        assertThatThrownBy(() -> JgitStorageSchemaMigrationConfig
                .migrateCoreSchema(normalFlyway(dataSource), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TAXONOMY_JGIT_STORAGE_LEGACY_ADOPTION=true");

        assertThat(columnExists(dataSource, "git_packs", "committed")).isFalse();
        assertThat(tableExists(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)).isFalse();
        assertThat(tableExists(
                dataSource, CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)).isFalse();
    }

    @Test
    void adoptsExactLegacyTaxonomySchemaWithoutChangingPackOrReflogData() throws Exception {
        DataSource dataSource = newDataSource();
        createLegacyTaxonomySchema(dataSource);
        byte[] packBytes = "immutable-pack-bytes".getBytes(StandardCharsets.UTF_8);
        insertPack(dataSource, "taxonomy-dsl", "pack-a", "pack", packBytes);
        String refName = "refs/heads/" + "a".repeat(200);
        insertReflog(dataSource, refName, "0123456789012345678901234567890123456789");

        JgitStorageSchemaMigrationConfig.migrateCoreSchema(normalFlyway(dataSource), true);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select data, committed, committed_at from git_packs")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBytes(1)).containsExactly(packBytes);
            assertThat(resultSet.getBoolean(2)).isTrue();
            assertThat(resultSet.getTimestamp(3)).isNotNull();
        }
        assertThat(queryString(dataSource, "select ref_name from git_reflog")).isEqualTo(refName);
        assertThat(columnSize(dataSource, "git_reflog", "ref_name")).isGreaterThanOrEqualTo(1024);
        assertThat(tableExists(dataSource, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)).isTrue();
        assertThat(tableExists(
                dataSource, CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)).isTrue();
    }

    @Test
    void rejectsDuplicatePackIdentityBeforeAnyMigrationDdl() throws Exception {
        DataSource dataSource = newDataSource();
        createLegacyTaxonomySchema(dataSource);
        insertPack(dataSource, "taxonomy-dsl", "pack-a", "pack", "first");
        insertPack(dataSource, "taxonomy-dsl", "pack-a", "pack", "second");

        assertThatThrownBy(() -> JgitStorageSchemaMigrationConfig
                .migrateCoreSchema(normalFlyway(dataSource), true))
                .hasMessageContaining("duplicate");

        assertThat(columnExists(dataSource, "git_packs", "committed")).isFalse();
        assertThat(columnSize(dataSource, "git_reflog", "ref_name")).isEqualTo(255);
        assertThat(tableExists(
                dataSource, CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)).isFalse();
    }

    private static DataSource newDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        dataSource.setUrl("jdbc:hsqldb:mem:migration_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("SA");
        dataSource.setPassword("");
        return dataSource;
    }

    private static Flyway normalFlyway(DataSource dataSource) {
        return new FluentConfiguration(JgitStorageSchemaMigrationTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations(CoreSchemaMigrations.HSQLDB_LOCATION)
                .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
                .load();
    }

    private static void createLegacyTaxonomySchema(DataSource dataSource) throws SQLException {
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
        execute(dataSource, "create index idx_pack_repo on git_packs(repository_name)");
        execute(dataSource,
                "create index idx_pack_repo_name on git_packs(repository_name, pack_name)");
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
        execute(dataSource, "create index idx_reflog_repo on git_reflog(repository_name)");
        execute(dataSource,
                "create index idx_reflog_repo_ref on git_reflog(repository_name, ref_name)");
    }

    private static void insertPack(
            DataSource dataSource,
            String repository,
            String packName,
            String extension,
            String payload) throws SQLException {
        insertPack(dataSource, repository, packName, extension,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void insertPack(
            DataSource dataSource,
            String repository,
            String packName,
            String extension,
            byte[] payload) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into git_packs
                       (repository_name, pack_name, pack_extension, data, file_size, created_at)
                     values (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, repository);
            statement.setString(2, packName);
            statement.setString(3, extension);
            statement.setBytes(4, payload);
            statement.setLong(5, payload.length);
            statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-07-24T00:00:00Z")));
            statement.executeUpdate();
        }
    }

    private static void insertReflog(
            DataSource dataSource, String refName, String newId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into git_reflog
                       (version, repository_name, ref_name, old_id, new_id,
                        who_name, who_email, who_when, message)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setLong(1, 0);
            statement.setString(2, "taxonomy-dsl");
            statement.setString(3, refName);
            statement.setString(4, null);
            statement.setString(5, newId);
            statement.setString(6, "Migration Test");
            statement.setString(7, "migration@example.invalid");
            statement.setTimestamp(8, Timestamp.from(Instant.parse("2026-07-24T00:00:00Z")));
            statement.setString(9, "commit: preserved");
            statement.executeUpdate();
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean tableExists(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getTables(
                    null, connection.getSchema(), table.toUpperCase(Locale.ROOT), null)) {
                return resultSet.next();
            }
        }
    }

    private static boolean columnExists(
            DataSource dataSource, String table, String column) throws SQLException {
        return columnSize(dataSource, table, column) >= 0;
    }

    private static int columnSize(
            DataSource dataSource, String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet resultSet = metadata.getColumns(
                    null,
                    connection.getSchema(),
                    table.toUpperCase(Locale.ROOT),
                    column.toUpperCase(Locale.ROOT))) {
                return resultSet.next() ? resultSet.getInt("COLUMN_SIZE") : -1;
            }
        }
    }

    private static String queryString(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
