package com.taxonomy.composition.persistence;

import com.taxonomy.analysis.cluster.ClusterAnalysisEvent;
import com.taxonomy.analysis.cluster.ClusterAnalysisInput;
import com.taxonomy.analysis.cluster.ClusterAnalysisRun;
import com.taxonomy.analysis.cluster.ClusterAnalysisWork;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executes V32 SQL on HSQL's PostgreSQL syntax mode and checks its explicit mapping contract.
 * PostgreSQL installation/upgrade remains covered by TaxonomySchemaPostgresMigrationIT. */
class ClusterAnalysisSchemaMigrationTest {
    private static final String MIGRATION = "db/migration/taxonomy/postgresql/V32__ClusterAnalysis.sql";

    @Test void migratedSchemaMatchesAllClusterEntityMappings() throws Exception {
        var database = migrate();
        String sql = new ClassPathResource(MIGRATION).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (Class<?> entity : Set.of(ClusterAnalysisRun.class, ClusterAnalysisWork.class, ClusterAnalysisInput.class, ClusterAnalysisEvent.class)) {
            String table = entity.getAnnotation(Table.class).name();
            var definition = java.util.regex.Pattern.compile("CREATE TABLE " + table + " \\((.*?)\\n\\);", java.util.regex.Pattern.DOTALL).matcher(sql);
            assertThat(definition.find()).as(table).isTrue();
            var actual = new java.util.TreeMap<String, String>();
            for (String line : definition.group(1).strip().split("\\n")) {
                String normalized = line.strip().replaceAll(",$", "");
                if (!normalized.startsWith("CONSTRAINT ")) actual.put(normalized.substring(0, normalized.indexOf(' ')), normalized);
            }
            var expected = new HashSet<String>();
            for (var field : entity.getDeclaredFields()) {
                var column = field.getAnnotation(Column.class);
                if (column == null) continue;
                String name = column.name().isEmpty() ? field.getName() : column.name(); expected.add(name);
                var jdbcType = field.getAnnotation(JdbcTypeCode.class);
                String type = jdbcType != null && jdbcType.value() == SqlTypes.LONG32VARCHAR ? "TEXT"
                        : field.getType() == int.class ? "INTEGER"
                        : field.getType() == long.class || field.getType() == Long.class ? "BIGINT"
                        : field.getType() == boolean.class ? "BOOLEAN" : "VARCHAR(" + column.length() + ")";
                assertThat(actual.get(name)).as(table + "." + name).startsWith(name + " " + type);
                if (field.isAnnotationPresent(Id.class)) assertThat(actual.get(name)).contains("PRIMARY KEY");
                else assertThat(actual.get(name).contains("NOT NULL")).as(table + "." + name + " nullability").isEqualTo(!column.nullable());
            }
            assertThat(actual.keySet()).as(table + " columns").containsExactlyInAnyOrderElementsOf(expected);
        }
        var indexes = new HashSet<String>();
        try (var connection = database.getConnection()) {
            for (String table : Set.of("ANALYSIS_CLUSTER_RUN", "ANALYSIS_CLUSTER_WORK", "ANALYSIS_CLUSTER_INPUT", "ANALYSIS_CLUSTER_EVENT")) {
                try (var result = connection.getMetaData().getIndexInfo(null, "PUBLIC", table, false, false)) {
                    while (result.next()) if (result.getString("INDEX_NAME") != null)
                        indexes.add(result.getString("INDEX_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        assertThat(indexes).contains("idx_analysis_cluster_owner", "idx_analysis_cluster_scope", "idx_analysis_cluster_work_run",
                "idx_analysis_cluster_input_run", "idx_analysis_cluster_event_run");
    }

    @Test void preservesLargeFrozenInputAndEnforcesMonotonicEventIdentity() throws Exception {
        var database = migrate(); var jdbc = new JdbcTemplate(database);
        String payload = "{\"snapshot\":\"" + "evidence".repeat(20_000) + "\"}";
        jdbc.update("insert into analysis_cluster_run(id,username,scope_key,context_json,command_json,relation_plan_json,state,total_roots,completed_roots,event_revision,created_at,updated_at) values('run','owner',?,?,? ,?,'RUNNING',2,0,0,1,1)", "a".repeat(64), payload, payload, payload);
        jdbc.update("insert into analysis_cluster_work(id,operation_id,task_id,task_type,ordinal_number,message_json,input_json,result_json,failure_reason,state,settled,delivery_attempts) values('work','run','task','SUBTAXONOMY',0,?,?,?,'RESTORE_INTERRUPTED','FAILED',true,1)", payload, payload, payload);
        jdbc.update("insert into analysis_cluster_input(id,operation_id,root_code,input_json) values('input','run','CP',?)", payload);
        jdbc.update("insert into analysis_cluster_event(id,operation_id,event_revision,event_json) values('event','run',1,?)", payload);
        assertThat(jdbc.queryForObject("select relation_plan_json from analysis_cluster_run", String.class)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("select row_version from analysis_cluster_run", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select input_json from analysis_cluster_input", String.class)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("select failure_reason from analysis_cluster_work", String.class)).isEqualTo("RESTORE_INTERRUPTED");
        assertThatThrownBy(() -> jdbc.update("insert into analysis_cluster_event values('another','run',1,'{}')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update analysis_cluster_work set failure_reason=?", "x".repeat(65)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static JDBCDataSource migrate() throws Exception {
        var migration = new ClassPathResource(MIGRATION);
        assertThat(migration.exists()).as("clustered execution requires its application migration").isTrue();
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:cluster-schema-" + UUID.randomUUID()); database.setUser("sa");
        try (var connection = database.getConnection()) {
            connection.createStatement().execute("SET DATABASE SQL SYNTAX PGS TRUE");
            ScriptUtils.executeSqlScript(connection, migration);
        }
        return database;
    }
}
