package com.taxonomy.composition.persistence;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RelationCallLedgerMigrationTest {
    @Test void legacyEvidenceKeepsAnUninitializedLedgerWithoutChangingItsAuthorityOrAnswers() throws Exception {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:relation-ledger-upgrade-" + UUID.randomUUID()); database.setUser("sa");
        try (var connection = database.getConnection()) {
            connection.createStatement().execute("SET DATABASE SQL SYNTAX PGS TRUE");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/taxonomy/postgresql/V30__resumable_analysis_questions.sql"));
        }
        var jdbc = new JdbcTemplate(database);
        jdbc.update("insert into analysis_continuation(id,username,workspace_id,branch_name,repository_id,input_hash,request_json,result_json,state,claim_until,updated_at,payload_characters,row_version) values('old','owner','workspace','draft','repository',?,'{}','{saved}','STOPPED',0,1,7,4)", "a".repeat(64));
        var migration = new ClassPathResource("db/migration/taxonomy/postgresql/V41__relation_call_ledger.sql");
        assertThat(migration.exists()).as("A nullable ledger migration is required for previously saved answers").isTrue();
        try (var connection = database.getConnection()) { ScriptUtils.executeSqlScript(connection, migration); }
        assertThat(jdbc.queryForList("select COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS where TABLE_NAME='ANALYSIS_CONTINUATION'", String.class))
                .contains("RELATION_CALLS_SPENT", "RELATION_SOURCE_COHORT_SIZE");
        var run = jdbc.queryForMap("select result_json,row_version,state,relation_calls_spent,relation_source_cohort_size from analysis_continuation where id='old'");
        assertThat(run.get("RESULT_JSON")).isEqualTo("{saved}"); assertThat(run.get("ROW_VERSION")).isEqualTo(4L);
        assertThat(run.get("STATE")).isEqualTo("STOPPED"); assertThat(run.get("RELATION_CALLS_SPENT")).isNull();
        assertThat(run.get("RELATION_SOURCE_COHORT_SIZE")).isNull();
    }
}
