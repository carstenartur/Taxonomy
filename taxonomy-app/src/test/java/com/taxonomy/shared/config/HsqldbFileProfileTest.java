package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real HSQLDB engine, not only the profile's text. */
class HsqldbFileProfileTest {
    @TempDir Path directory;

    private Properties profile(String name) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application-" + name + ".properties")) {
            assertNotNull(input, "The selectable " + name + " profile must be packaged");
            properties.load(input);
        }
        return properties;
    }

    private String resolve(Properties properties, String key) {
        assertNotNull(properties.getProperty(key), key);
        return new MockEnvironment().resolveRequiredPlaceholders(properties.getProperty(key));
    }

    @Test void fileProfilePreservesSchemaAndMovesTheSearchIndexOffHeap() throws Exception {
        Properties properties = profile("hsqldb-file");
        assertEquals("update", resolve(properties, "spring.jpa.hibernate.ddl-auto"));
        assertEquals("local-filesystem", resolve(properties,
                "spring.jpa.properties.hibernate.search.backend.directory.type"));
        assertEquals("true", resolve(properties, "spring.flyway.enabled"));
        assertEquals("com.zaxxer.hikari.HikariDataSource", resolve(properties, "spring.datasource.type"));
        assertEquals("1", resolve(properties, "spring.datasource.hikari.minimum-idle"));
        assertEquals("com.taxonomy.workspace.storage.JgitStorageHibernateSchemaFilterProvider",
                resolve(properties, "spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider"));
    }

    @Test void cachedTablesAndCommittedRowsSurviveAnActualDatabaseReopen() throws Exception {
        Properties properties = profile("hsqldb-file");
        String configured = resolve(properties, "spring.datasource.url");
        assertTrue(configured.startsWith("jdbc:hsqldb:file:./data/taxonomydb;"));
        assertTrue(configured.contains("hsqldb.cache_size=2048"));
        assertTrue(configured.contains("hsqldb.cache_rows=10000"));
        String url = configured.replace("./data/taxonomydb", directory.resolve("taxonomydb").toString());
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE PROFILE_PROBE (ID INTEGER PRIMARY KEY, NOTE VARCHAR(100))");
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO PROFILE_PROBE VALUES (1, 'committed checkpoint')");
            connection.commit();
            statement.executeUpdate("INSERT INTO PROFILE_PROBE VALUES (2, 'rolled back')");
            connection.rollback();
            statement.execute("SHUTDOWN");
        }
        assertTrue(Files.exists(directory.resolve("taxonomydb.data")));
        assertTrue(Files.readString(directory.resolve("taxonomydb.script"))
                .contains("CREATE CACHED TABLE PUBLIC.PROFILE_PROBE"),
                "A file URL with MEMORY tables would still retain all row data in the heap");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT ID, NOTE FROM PROFILE_PROBE")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("committed checkpoint", rows.getString(2));
                assertFalse(rows.next(), "The rolled-back row must not become durable");
            }
            statement.execute("SHUTDOWN");
        }
    }

    @Test void operatorCanChooseWritableDatabaseAndIndexPaths() throws Exception {
        Properties properties = profile("hsqldb-file");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("TAXONOMY_DATASOURCE_URL", "jdbc:hsqldb:file:/volume/custom;shutdown=true")
                .withProperty("TAXONOMY_SEARCH_DIRECTORY_ROOT", "/volume/index")
                .withProperty("TAXONOMY_DB_MAX_POOL_SIZE", "2");
        assertEquals("jdbc:hsqldb:file:/volume/custom;shutdown=true",
                environment.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.url")));
        assertEquals("/volume/index", environment.resolveRequiredPlaceholders(properties.getProperty(
                "spring.jpa.properties.hibernate.search.backend.directory.root")));
        assertEquals("2", environment.resolveRequiredPlaceholders(properties.getProperty(
                "spring.datasource.hikari.maximum-pool-size")));
    }

    @Test void existingInMemoryProfileRemainsAvailable() throws Exception {
        assertTrue(resolve(profile("hsqldb"), "spring.datasource.url").startsWith("jdbc:hsqldb:mem:"));
    }
}
