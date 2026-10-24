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
        assertThat(rows.queryForObject("select count(*) from \"restore_business_schema_history\" where \"success\"=true", Long.class)).isEqualTo(13);
        assertReasonLengthColumn(target);
        assertLanguageColumn(target);
        assertRelationLedgerColumns(target);
        assertHypothesisAuthorityColumns(target);
        assertThat(source.queryForObject("select id from active_marker", Integer.class)).isEqualTo(42);
        assertThat(tableNames(active)).containsExactly("ACTIVE_MARKER");
        rows.execute("alter table source_artifact drop column canonical_identifier");
        assertThatThrownBy(() -> RestoreSchemaMigration.openValidatedFactory(active, target, model(), () -> {}))
                .isInstanceOf(IOException.class).hasMessage("Staged restore schema validation failed");
    }
    @Test void restoredHypothesesKeepBranchIdentityAndRejectForeignEvidenceAuthority() throws Exception {
        var active = database(); var target = database();
        RestoreSchemaMigration.bootstrap(active, target, () -> {});
        var rows = new JdbcTemplate(target);
        insertHypothesis(rows, 1, "main");
        insertHypothesis(rows, 2, "draft");
        assertThat(rows.queryForList("select branch_name from relation_hypothesis order by id", String.class))
                .containsExactly("main", "draft");
        assertThatThrownBy(() -> insertHypothesis(rows, 3, "main"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertEvidence(rows, 1, "repository", "WORKSPACE:workspace", "main");
        for (var authority : List.of(
                List.of("foreign-repository", "WORKSPACE:workspace", "main"),
                List.of("repository", "WORKSPACE:foreign-workspace", "main"),
                List.of("repository", "WORKSPACE:workspace", "draft"))) {
            assertThatThrownBy(() -> insertEvidence(rows, 2, authority.get(0), authority.get(1), authority.get(2)))
                    .as("evidence must match its exact parent authority: %s", authority)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(rows.queryForObject("select count(*) from relation_evidence", Long.class)).isEqualTo(1);
        assertHypothesisAuthorityColumns(target);
    }
    @Test void oldStagedHypothesesWithoutRecordedBranchFailWithoutLosingEvidence() throws Exception {
        var target = database();
        migrateBusinessSchema(target, "1");
        PrincipalSchemaMigration.migrate(target);
        migrateBusinessSchema(target, "12");
        var rows = new JdbcTemplate(target);
        rows.execute("""
                insert into relation_hypothesis (id, repository_id, workspace_id, workspace_scope_key,
                    source_node_id, target_node_id, relation_type, status, analysis_session_scope_key, created_at)
                values (1, 'repository', 'workspace', 'WORKSPACE:workspace', 'A', 'B', 'DEPENDS_ON',
                    'PROPOSED', '__unspecified__', timestamp '2026-01-01 12:00:00')
                """);
        rows.execute("""
                insert into relation_evidence (id, hypothesis_id, evidence_type, summary, created_at)
                values (1, 1, 'MANUAL', 'retained evidence', timestamp '2026-01-01 12:00:00')
                """);
        var hypothesis = rows.queryForMap("select * from relation_hypothesis where id=1");
        var evidence = rows.queryForMap("select * from relation_evidence where id=1");

        assertThatThrownBy(() -> migrateBusinessSchema(target, "13"))
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);

        assertThat(rows.queryForMap("select * from relation_hypothesis where id=1"))
                .containsAllEntriesOf(hypothesis).containsEntry("BRANCH_NAME", null);
        assertThat(rows.queryForMap("select * from relation_evidence where id=1"))
                .containsAllEntriesOf(evidence);
        assertThat(rows.queryForObject("select count(*) from \"restore_business_schema_history\" where \"version\"='13' and \"success\"=true", Long.class))
                .isZero();
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
        var beforeLanguage = rows.queryForMap("select * from req_analysis_job where id = 'legacy-job'");
        migrateBusinessSchema(target, "12");
        assertLanguageColumn(target);
        var afterLanguage = rows.queryForMap("select * from req_analysis_job where id = 'legacy-job'");
        assertThat(afterLanguage).containsEntry("ANALYSIS_LANGUAGE", null);
        afterLanguage.remove("ANALYSIS_LANGUAGE");
        assertThat(afterLanguage).isEqualTo(beforeLanguage);
        for (String language : List.of("GERMAN", "ENGLISH")) {
            rows.update("update req_analysis_job set analysis_language = ? where id = 'legacy-job'", language);
            assertThat(rows.queryForObject("select analysis_language from req_analysis_job where id = 'legacy-job'", String.class))
                    .isEqualTo(language);
        }
        rows.update("update req_analysis_job set analysis_language = null where id = 'legacy-job'");
        for (String invalid : List.of("fr", "de", "UNKNOWN", ""))
            assertThatThrownBy(() -> rows.update("update req_analysis_job set analysis_language = ? where id = 'legacy-job'", invalid))
                    .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void relationLedgerMigrationPreservesLegacyEvidenceAndUnknownAccounting() throws Exception {
        var target = database();
        migrateBusinessSchema(target, "1");
        PrincipalSchemaMigration.migrate(target);
        migrateBusinessSchema(target, "10");
        var rows = new JdbcTemplate(target);
        rows.execute("""
                insert into analysis_continuation (id, username, workspace_id, branch_name, repository_id,
                    input_hash, request_json, result_json, state, claim_until, updated_at, payload_characters, row_version)
                values ('legacy-continuation', 'owner', 'workspace', 'main', 'repository',
                    'original-signature', '{"businessText":"retained requirement"}', '{"accepted":true}',
                    'STOPPED', 0, 42, 64, 7)
                """);
        var legacy = rows.queryForMap("select * from analysis_continuation where id='legacy-continuation'");
        assertThat(legacy).doesNotContainKeys("RELATION_CALLS_SPENT", "RELATION_SOURCE_COHORT_SIZE");

        migrateBusinessSchema(target, "11");

        assertRelationLedgerColumns(target);
        var migrated = rows.queryForMap("select * from analysis_continuation where id='legacy-continuation'");
        assertThat(migrated).containsEntry("RELATION_CALLS_SPENT", null).containsEntry("RELATION_SOURCE_COHORT_SIZE", null);
        migrated.remove("RELATION_CALLS_SPENT"); migrated.remove("RELATION_SOURCE_COHORT_SIZE");
        assertThat(migrated).isEqualTo(legacy);
        rows.update("update analysis_continuation set relation_calls_spent=256, relation_source_cohort_size=32 where id='legacy-continuation'");
        migrateBusinessSchema(target, "11");
        assertThat(rows.queryForObject("select relation_calls_spent from analysis_continuation where id='legacy-continuation'", Integer.class))
                .isEqualTo(256);
        assertThat(rows.queryForObject("select relation_source_cohort_size from analysis_continuation where id='legacy-continuation'", Integer.class))
                .isEqualTo(32);
        assertThat(rows.queryForObject("select row_version from analysis_continuation where id='legacy-continuation'", Long.class)).isEqualTo(7);
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
    private static void assertLanguageColumn(javax.sql.DataSource database) throws Exception {
        try (var connection = database.getConnection();
             var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(),
                     "REQ_ANALYSIS_JOB", "ANALYSIS_LANGUAGE")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.VARCHAR);
            assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(16);
            assertThat(columns.getInt("NULLABLE")).isEqualTo(java.sql.DatabaseMetaData.columnNullable);
            assertThat(columns.getString("COLUMN_DEF")).isNull();
            assertThat(columns.next()).isFalse();
        }
    }
    private static void assertRelationLedgerColumns(javax.sql.DataSource database) throws Exception {
        for (String name : List.of("RELATION_CALLS_SPENT", "RELATION_SOURCE_COHORT_SIZE")) {
            try (var connection = database.getConnection();
                 var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(),
                         "ANALYSIS_CONTINUATION", name)) {
                assertThat(columns.next()).as("restored continuation column %s", name).isTrue();
                assertThat(columns.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.INTEGER);
                assertThat(columns.getInt("NULLABLE")).isEqualTo(java.sql.DatabaseMetaData.columnNullable);
                assertThat(columns.getString("COLUMN_DEF")).isNull();
                assertThat(columns.next()).isFalse();
            }
        }
    }
    private static void assertHypothesisAuthorityColumns(javax.sql.DataSource database) throws Exception {
        for (var table : Map.of("RELATION_HYPOTHESIS", List.of("BRANCH_NAME"),
                "RELATION_EVIDENCE", List.of("REPOSITORY_ID", "WORKSPACE_SCOPE_KEY", "BRANCH_NAME")).entrySet()) {
            for (String name : table.getValue()) {
                try (var connection = database.getConnection();
                     var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), table.getKey(), name)) {
                    assertThat(columns.next()).as("restored authority column %s.%s", table.getKey(), name).isTrue();
                    assertThat(columns.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.VARCHAR);
                    assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(255);
                    assertThat(columns.getInt("NULLABLE")).isEqualTo(java.sql.DatabaseMetaData.columnNoNulls);
                    assertThat(columns.getString("COLUMN_DEF")).isNull();
                    assertThat(columns.next()).isFalse();
                }
            }
        }
    }
    private static void insertHypothesis(JdbcTemplate rows, long id, String branch) {
        rows.update("""
                insert into relation_hypothesis (id, repository_id, workspace_id, workspace_scope_key,
                    branch_name, source_node_id, target_node_id, relation_type, status,
                    analysis_session_id, analysis_session_scope_key, created_at)
                values (?, 'repository', 'workspace', 'WORKSPACE:workspace', ?, 'A', 'B', 'DEPENDS_ON',
                    'PROPOSED', 'same-session', 'same-session', timestamp '2026-01-01 12:00:00')
                """, id, branch);
    }
    private static void insertEvidence(JdbcTemplate rows, long id, String repository, String workspaceScope, String branch) {
        rows.update("""
                insert into relation_evidence (id, hypothesis_id, repository_id, workspace_scope_key,
                    branch_name, evidence_type, created_at)
                values (?, 1, ?, ?, ?, 'MANUAL', timestamp '2026-01-01 12:00:00')
                """, id, repository, workspaceScope, branch);
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
