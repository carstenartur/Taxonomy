package com.taxonomy.analysis.runtime;

import org.hibernate.type.descriptor.java.DataHelper;
import org.hsqldb.DatabaseManager;
import org.hsqldb.DatabaseType;
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

    @Test
    void repeatedCheckpointUpdatesReuseDiscardedLobsAndPreserveHistoricalPayloads() throws Exception {
        String url = isolatedDefaultUrl();
        String name = url.substring("jdbc:hsqldb:mem:".length(), url.indexOf(';'));
        String original = variedText(1703);
        String current = variedText(1704);
        byte[] originalBytes = new byte[128 * 1024];
        byte[] currentBytes = new byte[128 * 1024];
        new Random(1705).nextBytes(originalBytes);
        new Random(1706).nextBytes(currentBytes);
        try (Connection owner = open(url)) {
            try {
                owner.setAutoCommit(false);
                try (var statement = owner.createStatement()) {
                    statement.execute("CREATE TABLE payload_probe (id INTEGER PRIMARY KEY, reason CLOB, document BLOB)");
                    statement.execute("CREATE TABLE journal_probe (id INTEGER PRIMARY KEY, detail CLOB)");
                }
                try (var insert = owner.prepareStatement("INSERT INTO payload_probe VALUES (?, ?, ?)")) {
                    for (int id : new int[]{1, 2, 3}) {
                        insert.setInt(1, id);
                        insert.setCharacterStream(2, new StringReader(original));
                        insert.setBinaryStream(3, new ByteArrayInputStream(originalBytes));
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }
                }
                try (var insert = owner.prepareStatement("INSERT INTO journal_probe VALUES (?, ?)")) {
                    for (int id = 0; id < 64; id++) {
                        insert.setInt(1, id);
                        insert.setString(2, "Initial complete checkpoint evidence " + id);
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }
                }
                owner.commit();
                for (int generation = 0; generation < 64; generation++) {
                    try (var update = owner.prepareStatement("UPDATE payload_probe SET reason = ?, document = ? WHERE id = 2")) {
                        update.setCharacterStream(1, new StringReader(current));
                        update.setBinaryStream(2, new ByteArrayInputStream(currentBytes));
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                    try (var update = owner.prepareStatement("UPDATE journal_probe SET detail = ? WHERE id = ?")) {
                        for (int id = 0; id < 64; id++) {
                            update.setString(1, "Complete accepted checkpoint evidence " + generation + ":" + id);
                            update.setInt(2, id);
                            assertThat(update.executeUpdate()).isEqualTo(1);
                        }
                    }
                    owner.commit();
                }
                // Observe allocated database blocks, not JVM garbage or a forced collection.
                // No SQL read transaction may hold up the engine's asynchronous checkpoint.
                var database = DatabaseManager.lookupDatabaseObject(DatabaseType.DB_MEM, name);
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                while (database.lobManager.getLobCount() > 1_300 && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertThat(database.lobManager.getLobCount())
                        .as("Discarded checkpoint LOB generations must be reclaimed automatically")
                        .isLessThanOrEqualTo(1_300);
                assertThat(database.lobManager.getLobStore().getLength())
                        .as("Repeated updates must reuse obsolete blocks instead of retaining every large version")
                        .isLessThanOrEqualTo(48L * 1024 * 1024);
                assertPayload(url, 1, original, originalBytes);
                assertPayload(url, 2, current, currentBytes);
                assertPayload(url, 3, original, originalBytes);
                try (var observer = open(url); var statement = observer.createStatement();
                     var rows = statement.executeQuery("SELECT id, detail FROM journal_probe ORDER BY id")) {
                    for (int id = 0; id < 64; id++) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(id);
                        assertThat(rows.getString(2)).isEqualTo("Complete accepted checkpoint evidence 63:" + id);
                    }
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                shutdown(owner);
            }
        }
    }

    private static String variedText(long seed) {
        var random = new Random(seed);
        var text = new StringBuilder("Complete Unicode evidence – 水位 🌊; e\u0301; \u0000;\n");
        for (int i = 0; i < 1024 * 1024; i++) text.append((char) (' ' + random.nextInt(95)));
        return text.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:hsqldb:mem:explicit-database;hsqldb.lob_compressed=false",
            "jdbc:hsqldb:mem:explicit-database;hsqldb.lob_compressed=true;hsqldb.lob_file_scale=32",
            "jdbc:hsqldb:mem:explicit-database;hsqldb.log_size=0",
            "jdbc:hsqldb:mem:explicit-database;hsqldb.log_size=16",
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
