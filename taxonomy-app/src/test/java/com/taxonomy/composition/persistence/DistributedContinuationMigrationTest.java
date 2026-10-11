package com.taxonomy.composition.persistence;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class DistributedContinuationMigrationTest {
    @Test void ownershipUpgradeRetainsLocalAnswersWithoutActivatingDistributedGrants() throws Exception {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:continuation-upgrade-" + UUID.randomUUID()); database.setUser("sa");
        try (var connection = database.getConnection()) {
            connection.createStatement().execute("SET DATABASE SQL SYNTAX PGS TRUE");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/taxonomy/postgresql/V30__resumable_analysis_questions.sql"));
        }
        var jdbc = new JdbcTemplate(database);
        jdbc.update("insert into analysis_continuation(id,username,workspace_id,branch_name,repository_id,input_hash,request_json,result_json,state,claim_until,updated_at,payload_characters,row_version) values('old','owner','workspace','draft','repository',?,'{}','{saved}','PAUSED',0,1,7,4)", "a".repeat(64));
        jdbc.update("insert into analysis_question_checkpoint(id,run_id,question_key,input_hash,provider,node_codes,detail_json,prompt_text,state,attempts,started_at) values('old:q','old','q',?,'MOCK','[CP]','{answer}','original prompt','SUCCESS',1,1)", "b".repeat(64));
        try (var connection = database.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/taxonomy/postgresql/V36__distributed_continuation_ownership.sql"));
        }
        var run = jdbc.queryForMap("select result_json,row_version,state,cluster_operation_id,cluster_source_json,cluster_catalogue_hash from analysis_continuation where id='old'");
        assertThat(run.get("RESULT_JSON")).isEqualTo("{saved}"); assertThat(run.get("ROW_VERSION")).isEqualTo(4L);
        assertThat(run.get("STATE")).isEqualTo("PAUSED");
        assertThat(run.get("CLUSTER_OPERATION_ID")).isNull(); assertThat(run.get("CLUSTER_SOURCE_JSON")).isNull(); assertThat(run.get("CLUSTER_CATALOGUE_HASH")).isNull();
        var question = jdbc.queryForMap("select detail_json,prompt_text,state,attempt_owner,cluster_operation_id,cluster_task_id,cluster_delivery from analysis_question_checkpoint where id='old:q'");
        assertThat(question.get("DETAIL_JSON")).isEqualTo("{answer}"); assertThat(question.get("PROMPT_TEXT")).isEqualTo("original prompt");
        assertThat(question.get("STATE")).isEqualTo("SUCCESS");
        for (var name : new String[]{"ATTEMPT_OWNER","CLUSTER_OPERATION_ID","CLUSTER_TASK_ID","CLUSTER_DELIVERY"}) assertThat(question.get(name)).as(name).isNull();
    }
}
