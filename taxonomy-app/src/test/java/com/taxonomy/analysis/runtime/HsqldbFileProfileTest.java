package com.taxonomy.analysis.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HsqldbFileProfileTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    static class ConfigurationOnly { }

    private ConfigurableApplicationContext configuration(String... extra) {
        SpringApplication app = new SpringApplication(ConfigurationOnly.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("hsqldb-file");
        app.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
        String[] args = new String[extra.length + 2];
        args[0] = "--TAXONOMY_HSQLDB_FILE_PATH=" + directory.resolve("taxonomydb");
        args[1] = "--TAXONOMY_SEARCH_DIRECTORY_ROOT=" + directory.resolve("index");
        System.arraycopy(extra, 0, args, 2, extra.length);
        return app.run(args);
    }

    @Test
    void fileProfilePreservesDataAndMovesBothStoresOffHeap() {
        try (var context = configuration()) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:hsqldb:file:")
                    .contains("hsqldb.default_table_type=cached", "hsqldb.cache_size=4096",
                            "hsqldb.cache_rows=10000", "hsqldb.write_delay_millis=0", "shutdown=true");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.search.backend.directory.type"))
                    .isEqualTo("local-filesystem");
            assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("1");
            assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider"))
                    .endsWith("JgitStorageHibernateSchemaFilterProvider");
        }
    }

    @Test
    void realHsqldbCreatesCachedTablesAndKeepsRowsAfterRestart() throws Exception {
        String url;
        try (var context = configuration()) {
            url = context.getEnvironment().getProperty("spring.datasource.url");
            assertThat(url).startsWith("jdbc:hsqldb:file:");
        }
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE restart_probe (id INTEGER PRIMARY KEY, payload VARCHAR(100))");
            connection.createStatement().execute("INSERT INTO restart_probe VALUES (1, 'accepted analysis')");
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT HSQLDB_TYPE FROM INFORMATION_SCHEMA.SYSTEM_TABLES WHERE TABLE_NAME='RESTART_PROBE'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CACHED");
            }
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var rows = connection.createStatement().executeQuery("SELECT payload FROM restart_probe WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("accepted analysis");
        }
        assertThat(directory.resolve("taxonomydb.properties")).exists();
    }

    @Test
    void explicitStorageLimitsRemainConfigurable() {
        try (var context = configuration("--TAXONOMY_HSQLDB_CACHE_SIZE_KB=2048", "--TAXONOMY_HSQLDB_CACHE_ROWS=5000")) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .contains("hsqldb.cache_size=2048", "hsqldb.cache_rows=5000");
        }
    }
}
