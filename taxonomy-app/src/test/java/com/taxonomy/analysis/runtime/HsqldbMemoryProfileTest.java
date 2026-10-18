package com.taxonomy.analysis.runtime;

import org.hibernate.type.descriptor.java.DataHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped MEM default must preserve complete LOBs with its selected native storage settings. */
class HsqldbMemoryProfileTest {

    @Test
    void freshDefaultMemoryDatabaseUsesNativeLobCompression() throws Exception {
        try (Connection connection = open(isolatedDefaultUrl())) {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT PROPERTY_VALUE FROM INFORMATION_SCHEMA.SYSTEM_PROPERTIES
                          WHERE PROPERTY_NAME = 'hsqldb.lob_compressed'
                         """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).as("Active HSQL LOB compression in the fresh default MEM database")
                        .isEqualTo("true");
            } finally {
                shutdown(connection);
            }
        }
    }

    @Test
    void freshDefaultMemoryDatabaseUsesOneKibibyteLobBlocks() throws Exception {
        try (Connection connection = open(isolatedDefaultUrl())) {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                         SELECT PROPERTY_VALUE FROM INFORMATION_SCHEMA.SYSTEM_PROPERTIES
                          WHERE PROPERTY_NAME = 'hsqldb.lob_file_scale'
                         """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).as("Active HSQL LOB block size in KiB in the fresh default MEM database")
                        .isEqualTo("1");
            } finally {
                shutdown(connection);
            }
        }
    }

    @Test
    void largerUnicodeClobsAndBinaryBlobsSurviveUpdatesAndFreshConnectionReads() throws Exception {
        String url = isolatedDefaultUrl();
        String originalText = "Überflutung – 水位 🌊; e\u0301; \u0000; Quelle und Beobachtungszeit.\n".repeat(12_000);
        String updatedText = "Geänderte Warnung – 更新 🛰️; Ω; \u0000; unavailable/stale.\n".repeat(13_000);
        byte[] originalBytes = new byte[256 * 1024];
        byte[] updatedBytes = new byte[320 * 1024];
        new Random(1701).nextBytes(originalBytes);
        new Random(1702).nextBytes(updatedBytes);

        try (Connection owner = open(url)) {
            try {
                owner.createStatement().execute("CREATE TABLE payload_probe (id INTEGER PRIMARY KEY, reason CLOB, document BLOB)");
                owner.setAutoCommit(false);
                try (var insert = owner.prepareStatement("INSERT INTO payload_probe VALUES (?, ?, ?)")) {
                    for (int id : new int[]{1, 2}) {
                        insert.setInt(1, id);
                        insert.setCharacterStream(2, new StringReader(originalText));
                        insert.setBinaryStream(3, new ByteArrayInputStream(originalBytes));
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }
                }
                owner.commit();
                assertPayload(url, 1, originalText, originalBytes);

                try (var update = owner.prepareStatement("UPDATE payload_probe SET reason = ?, document = ? WHERE id = 2")) {
                    update.setCharacterStream(1, new StringReader(updatedText));
                    update.setBinaryStream(2, new ByteArrayInputStream(updatedBytes));
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                owner.commit();
                assertPayload(url, 1, originalText, originalBytes);
                assertPayload(url, 2, updatedText, updatedBytes);
            } finally {
                shutdown(owner);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:hsqldb:mem:explicit-database;hsqldb.lob_compressed=false",
            "jdbc:hsqldb:mem:explicit-database;hsqldb.lob_compressed=true;hsqldb.lob_file_scale=32",
            "jdbc:hsqldb:file:/existing/database;shutdown=true;hsqldb.write_delay_millis=0",
            "jdbc:hsqldb:file:/existing/database;hsqldb.lob_file_scale=8;shutdown=true"
    })
    void explicitDatasourceUrlIsUsedExactlyWithoutAddingStorageSettings(String override) throws Exception {
        assertThat(resolveUrl(Map.of("TAXONOMY_DATASOURCE_URL", override))).isEqualTo(override);
    }

    private static String isolatedDefaultUrl() throws Exception {
        String url = resolveUrl(Map.of());
        String prefix = "jdbc:hsqldb:mem:taxonomydb";
        assertThat(url).startsWith(prefix + ";");
        // Preserve every shipped storage option; only isolate the newly created test database name.
        return "jdbc:hsqldb:mem:profile-lobs-" + UUID.randomUUID() + url.substring(prefix.length());
    }

    private static String resolveUrl(Map<String, Object> overrides) throws Exception {
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("explicit-overrides", overrides));
        sources.addLast(new PropertiesPropertySource("shipped-hsqldb-profile",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-hsqldb.properties"))));
        return new PropertySourcesPropertyResolver(sources).getRequiredProperty("spring.datasource.url");
    }

    private static Connection open(String url) throws Exception {
        return DriverManager.getConnection(url, "sa", "");
    }

    private static void assertPayload(String url, int id, String text, byte[] bytes) throws Exception {
        try (Connection connection = open(url);
             var statement = connection.prepareStatement("SELECT reason, document FROM payload_probe WHERE id = ?")) {
            statement.setInt(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(text);
                assertThat(DataHelper.extractString(result.getClob(1))).isEqualTo(text);
                assertThat(DataHelper.extractString(result.getNClob(1))).isEqualTo(text);
                assertThat(result.getBytes(2)).containsExactly(bytes);
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static void shutdown(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
