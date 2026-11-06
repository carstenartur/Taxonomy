package com.taxonomy.backup.recovery;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class NativeRecoveryRowDigestsTest {
    @Test void byteAndUnicodeLobChangesCannotBeHiddenByRowOrderOrJdbcLocators() throws Exception {
        try (var connection = database(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE source_rows (id INTEGER, payload BLOB, detail CLOB, amount DECIMAL(18,4), active BOOLEAN)");
            sql.execute("CREATE TABLE target_rows (id INTEGER, payload BLOB, detail CLOB, amount DECIMAL(18,4), active BOOLEAN)");
            sql.execute("INSERT INTO source_rows VALUES (1, X'0001ff00', 'Retained Ünicode 日本語', 2.3400, true), (2, NULL, NULL, NULL, false)");
            sql.execute("INSERT INTO target_rows SELECT * FROM source_rows ORDER BY id DESC");
            String original = digest(connection, "SOURCE_ROWS");
            assertThat(digest(connection, "TARGET_ROWS")).isEqualTo(original);
            sql.execute("UPDATE target_rows SET payload = X'0001ff01' WHERE id = 1");
            assertThat(digest(connection, "TARGET_ROWS")).isNotEqualTo(original);
            sql.execute("UPDATE target_rows SET payload = X'0001ff00', detail = 'Retained Ünicode 日本 語' WHERE id = 1");
            assertThat(digest(connection, "TARGET_ROWS")).isNotEqualTo(original);
            sql.execute("UPDATE target_rows SET detail = 'Retained Ünicode 日本語' WHERE id = 1");
            assertThat(digest(connection, "TARGET_ROWS")).isEqualTo(original);
            sql.execute("INSERT INTO target_rows SELECT * FROM source_rows WHERE id = 2");
            assertThat(digest(connection, "TARGET_ROWS")).as("Duplicate retained rows must count").isNotEqualTo(original);
        }
    }

    @Test void columnBoundariesNullAndEmptyRemainDistinct() throws Exception {
        try (var connection = database(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE framed (left_value VARCHAR(40), right_value VARCHAR(40))");
            sql.execute("INSERT INTO framed VALUES ('a', 'bc')");
            String original = digest(connection, "FRAMED");
            sql.execute("UPDATE framed SET left_value = 'ab', right_value = 'c'");
            assertThat(digest(connection, "FRAMED")).isNotEqualTo(original);
            sql.execute("UPDATE framed SET left_value = NULL, right_value = 'c'");
            String nullValue = digest(connection, "FRAMED");
            sql.execute("UPDATE framed SET left_value = ''");
            assertThat(digest(connection, "FRAMED")).isNotEqualTo(nullValue);
        }
    }

    private static Connection database() throws Exception { return DriverManager.getConnection("jdbc:hsqldb:mem:digest_" + UUID.randomUUID(), "sa", ""); }
    private static String digest(Connection connection, String table) throws Exception { return NativeRecoveryRowDigests.tables(connection, List.of(table)).get(table); }
}
