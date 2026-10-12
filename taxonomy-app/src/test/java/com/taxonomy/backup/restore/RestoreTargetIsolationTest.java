package com.taxonomy.backup.restore;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.io.IOException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RestoreTargetIsolationTest {
    @Test void distinctWrappersAndConnectionPropertiesCannotDisguiseTheSamePhysicalDatabase() throws Exception {
        String name = "target-isolation-" + UUID.randomUUID();
        var active = database(name, ";hsqldb.tx=mvcc");
        var alias = new DelegatingDataSource(database(name, ";user=sa"));
        assertThatThrownBy(() -> TargetDatabaseIdentity.proveIsolated(active, alias, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("isolated");
        assertThat(TargetDatabaseIdentity.inspect(active, () -> {}))
                .isEqualTo(TargetDatabaseIdentity.inspect(alias, () -> {}));
    }
    @Test void actualDifferentDatabasesRemainDistinctAndExposeOnlyAnOpaqueDestinationFingerprint() throws Exception {
        var source = database("source-" + UUID.randomUUID(), "");
        var target = database("PRIVATE-TARGET-" + UUID.randomUUID(), "");
        var identity = TargetDatabaseIdentity.proveIsolated(source, target, () -> {});
        assertThat(identity.destinationFingerprint()).matches("[0-9a-f]{64}");
        assertThat(identity.toString()).doesNotContain("jdbc:", "PRIVATE-TARGET", "sa;");
        assertThat(TargetDatabaseIdentity.inspect(target, () -> {})).isEqualTo(identity);
    }
    @Test void actualMemoryNameCaseAliasesCannotDisguiseTheSamePhysicalRows() throws Exception {
        String name = "isolation-case-" + UUID.randomUUID();
        var active = database(name.toUpperCase(java.util.Locale.ROOT), "");
        var alias = database(name.toLowerCase(java.util.Locale.ROOT), "");
        try (var connection = active.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create table actual_alias_marker(id integer)");
            statement.execute("insert into actual_alias_marker values (42)");
        }
        try (var connection = alias.getConnection(); var result = connection.createStatement().executeQuery("select id from actual_alias_marker")) {
            assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(42);
        }
        assertThatThrownBy(() -> TargetDatabaseIdentity.proveIsolated(active, alias, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("isolated");
    }
    private static JDBCDataSource database(String name, String properties) {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:" + name + properties); database.setUser("sa"); return database;
    }
}
