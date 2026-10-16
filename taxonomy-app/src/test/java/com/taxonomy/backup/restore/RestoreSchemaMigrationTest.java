package com.taxonomy.backup.restore;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import testfixtures.restore.UnknownFutureOwner;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RestoreSchemaMigrationTest {
    @Test void freshIsolatedTargetUsesVersionedDdlAndValidatesActualBusinessModel() throws Exception {
        var active = database(); var target = database();
        var source = new JdbcTemplate(active);
        source.execute("create table active_marker(id integer)");
        source.execute("insert into active_marker values(42)");
        RestoreSchemaMigration.bootstrap(active, target, () -> {});
        try (var factory = RestoreSchemaMigration.openValidatedFactory(active, target, model(), () -> {})) {
            try (var session = factory.openSession()) {
                session.doWork(connection -> assertThat(connection.getMetaData().getURL()).contains("restore-schema-"));
            }
        }
        var rows = new JdbcTemplate(target);
        for (String table : List.of("source_artifact", "source_version", "source_fragment", "user_workspace", "app_user", "app_principal", "principal_binding", "backup_capability_grant", "git_packs", "workspace_git_reflog_provenance", "analysis_cluster_call", "analysis_cluster_attempt"))
            assertThat(rows.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        assertThat(rows.queryForObject("select count(*) from principal_installation", Long.class)).isEqualTo(1);
        assertThat(rows.queryForObject("select count(*) from \"restore_business_schema_history\" where \"success\"=true", Long.class)).isEqualTo(8);
        assertThat(source.queryForObject("select id from active_marker", Integer.class)).isEqualTo(42);
        assertThat(tableNames(active)).containsExactly("ACTIVE_MARKER");
        rows.execute("alter table source_artifact drop column canonical_identifier");
        assertThatThrownBy(() -> RestoreSchemaMigration.openValidatedFactory(active, target, model(), () -> {}))
                .isInstanceOf(IOException.class).hasMessage("Staged restore schema validation failed");
    }
    @Test void aliasedOrPartlyPopulatedTargetIsRejectedBeforeAnyDdl() throws Exception {
        var active = database();
        assertThatThrownBy(() -> RestoreSchemaMigration.bootstrap(active, new DelegatingDataSource(active), () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("isolated");
        assertThat(tableNames(active)).isEmpty();
        var partial = database(); var rows = new JdbcTemplate(partial);
        rows.execute("create table private_marker(id integer)"); rows.execute("insert into private_marker values(42)");
        assertThatThrownBy(() -> RestoreSchemaMigration.bootstrap(active, partial, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("empty");
        assertThat(rows.queryForObject("select id from private_marker", Integer.class)).isEqualTo(42);
        assertThat(tableNames(partial)).containsExactly("PRIVATE_MARKER");
        var cancelled = database();
        try {
            assertThatThrownBy(() -> RestoreSchemaMigration.bootstrap(active, cancelled, () -> { throw new java.io.InterruptedIOException("sensitive"); }))
                    .isInstanceOf(java.io.InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(tableNames(cancelled)).isEmpty();
    }
    @Test void mutableTargetRoutingCannotRedirectMigrationToTheActiveDatabase() throws Exception {
        var active = database(); var target = database();
        var rows = new JdbcTemplate(active);
        rows.execute("create table active_marker(id integer)"); rows.execute("insert into active_marker values(42)");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var rerouted = new DelegatingDataSource(target) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                return (calls.incrementAndGet() == 1 ? target : active).getConnection();
            }
        };
        RestoreSchemaMigration.bootstrap(active, rerouted, () -> {});
        assertThat(tableNames(active)).containsExactly("ACTIVE_MARKER");
        assertThat(rows.queryForObject("select id from active_marker", Integer.class)).isEqualTo(42);
        assertThat(tableNames(target)).contains("SOURCE_ARTIFACT", "GIT_PACKS");
        assertThat(calls).hasValue(1);
    }
    @Test void aChangedRegisteredModelCannotReuseAnOlderRestoreBaseline() throws Exception {
        var active = database(); var target = database();
        RestoreSchemaMigration.bootstrap(active, target, () -> {});
        var expected = tableNames(target);
        var changed = new ArrayList<>(model()); changed.add(UnknownFutureOwner.class);
        assertThatThrownBy(() -> RestoreSchemaMigration.openValidatedFactory(active, target, changed, () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("model");
        assertThat(tableNames(target)).isEqualTo(expected).doesNotContain("UNKNOWN_FUTURE_OWNER");
        assertThatThrownBy(() -> RestoreSchemaMigration.openValidatedFactory(active, target, List.of(), () -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("model");
    }
    private static Set<String> tableNames(javax.sql.DataSource database) throws Exception {
        var names = new TreeSet<String>();
        try (var c = database.getConnection(); var tables = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME"));
        }
        return names;
    }
    private static java.util.List<Class<?>> model() throws Exception {
        try (var input = RestoreSchemaMigrationTest.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")) {
            if (input == null) throw new IllegalStateException("Missing approved restore model");
            var classes = new ArrayList<Class<?>>();
            for (String name : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList())
                if (!name.startsWith("#") && !name.isBlank()) classes.add(Class.forName(name));
            return classes;
        }
    }
    private static JDBCDataSource database() {
        var database = new JDBCDataSource(); database.setURL("jdbc:hsqldb:mem:restore-schema-" + UUID.randomUUID()); database.setUser("sa"); return database;
    }
}
