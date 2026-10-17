package com.taxonomy.backup.restore;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import org.flywaydb.core.Flyway;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
        assertThat(rows.queryForObject("select count(*) from \"restore_business_schema_history\" where \"success\"=true", Long.class)).isEqualTo(9);
        assertReasonLengthColumn(target);
        assertThat(source.queryForObject("select id from active_marker", Integer.class)).isEqualTo(42);
        assertThat(tableNames(active)).containsExactly("ACTIVE_MARKER");
        rows.execute("alter table source_artifact drop column canonical_identifier");
        assertThatThrownBy(() -> RestoreSchemaMigration.openValidatedFactory(active, target, model(), () -> {}))
                .isInstanceOf(IOException.class).hasMessage("Staged restore schema validation failed");
    }
    @Test void reasonLengthMigrationPreservesUnknownLegacyJobsAndEnforcesRecordedValues() throws Exception {
        var target = database();
        migrateBusinessSchema(target, "1");
        PrincipalSchemaMigration.migrate(target);
        migrateBusinessSchema(target, "8");
        var rows = new JdbcTemplate(target);
        rows.execute("""
                insert into arch_project (id, scope_key, workspace_scope, project_key, title,
                    owner_username, status, created_at, updated_at, row_version, branch_name, repository_id)
                values (1, 'restore-reason-scope', 'restore-workspace', 'LEGACY', 'Existing project',
                    'owner', 'ACTIVE', timestamp '2026-01-01 12:00:00', timestamp '2026-01-01 12:00:00',
                    0, 'main', 'restore-repository')
                """);
        rows.execute("""
                insert into req_analysis_job (id, scope_key, project_id, status, idempotency_key,
                    provider, max_architecture_nodes, requested_by, created_at, total_items,
                    successful_items, partial_items, failed_items, row_version)
                values ('legacy-job', 'restore-reason-scope', 1, 'SUCCESS', 'legacy-request',
                    'LOCAL_ONNX', 100, 'owner', timestamp '2026-01-01 12:00:00', 1, 1, 0, 0, 0)
                """);
        var legacy = rows.queryForMap("select * from req_analysis_job where id = 'legacy-job'");
        assertThat(legacy).doesNotContainKey("ANALYSIS_REASON_LENGTH");

        migrateBusinessSchema(target, "9");

        assertReasonLengthColumn(target);
        var migrated = rows.queryForMap("select * from req_analysis_job where id = 'legacy-job'");
        assertThat(migrated).containsEntry("ANALYSIS_REASON_LENGTH", null);
        migrated.remove("ANALYSIS_REASON_LENGTH");
        assertThat(migrated).isEqualTo(legacy);
        for (String recorded : List.of("SHORT", "STANDARD", "DETAILED")) {
            assertThat(rows.update("update req_analysis_job set analysis_reason_length = ? where id = 'legacy-job'", recorded))
                    .isEqualTo(1);
            assertThat(rows.queryForObject("select analysis_reason_length from req_analysis_job where id = 'legacy-job'", String.class))
                    .isEqualTo(recorded);
        }
        rows.update("update req_analysis_job set analysis_reason_length = null where id = 'legacy-job'");
        for (String invalid : List.of("UNKNOWN", "detailed", "")) {
            assertThatThrownBy(() -> rows.update("update req_analysis_job set analysis_reason_length = ? where id = 'legacy-job'", invalid))
                    .as("invalid recorded reason length: %s", invalid)
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(rows.queryForObject("select analysis_reason_length from req_analysis_job where id = 'legacy-job'", String.class))
                    .isNull();
        }
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
    private static void assertReasonLengthColumn(javax.sql.DataSource database) throws Exception {
        try (var connection = database.getConnection();
             var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(),
                     "REQ_ANALYSIS_JOB", "ANALYSIS_REASON_LENGTH")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.VARCHAR);
            assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(16);
            assertThat(columns.getInt("NULLABLE")).isEqualTo(java.sql.DatabaseMetaData.columnNullable);
            assertThat(columns.getString("COLUMN_DEF")).isNull();
            assertThat(columns.next()).isFalse();
        }
    }
    private static void migrateBusinessSchema(javax.sql.DataSource database, String target) {
        Flyway.configure().dataSource(database).table("restore_business_schema_history")
                .locations("classpath:db/restore/hsqldb").baselineOnMigrate(false).target(target).load().migrate();
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
