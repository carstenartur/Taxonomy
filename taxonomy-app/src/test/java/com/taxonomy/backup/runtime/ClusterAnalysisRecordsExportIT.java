package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.analysis.backup.AnalysisBackupContributor;
import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import static com.taxonomy.backup.runtime.CurrentStateExportIT.*;
import static org.assertj.core.api.Assertions.*;

class ClusterAnalysisRecordsExportIT {
    @Test void exportsExactScopedDurableClosureWithoutOperationalDispatch() throws Exception {
        try (var fixture = fixture()) {
            run(fixture, "current", "alice", "repo-a", "private-a", 20, "RUNNING", "CURRENT");
            run(fixture, "other-owner", "bob", "repo-a", "private-a", 20, "RUNNING", "PRIVATE-OWNER");
            run(fixture, "other-workspace", "alice", "repo-a", "private-b", 20, "RUNNING", "PRIVATE-WORKSPACE");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.REPOSITORY_HISTORY), output);
            assertThat(records(output, "cluster-runs")).hasSize(1);
            for (String kind : List.of("cluster-work", "cluster-inputs", "cluster-events"))
                assertThat(records(output, kind)).singleElement().satisfies(record ->
                        assertThat(record.path("run").path("value").asText()).isEqualTo("current"));
            assertThat(output.text()).contains("CURRENT", "FROZEN-CP", "REVIEW_REQUIRED")
                    .doesNotContain("PRIVATE-OWNER", "PRIVATE-WORKSPACE", "dispatch_attempts", "claim_token");
        }
    }

    @Test void currentProfileUsesLatestExactIdentityAndCurrentDraftText() throws Exception {
        try (var fixture = fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"CURRENT\"}");
            run(fixture, "old", "alice", "repo-a", "private-a", 10, "COMPLETED", "OLD");
            run(fixture, "current", "alice", "repo-a", "private-a", 20, "COMPLETED", "CURRENT");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output);
            assertThat(records(output, "cluster-runs")).singleElement().satisfies(record ->
                    assertThat(record.path("sourceId").path("value").asText()).isEqualTo("current"));
            assertThat(output.text()).doesNotContain("OLD");
            fixture.jdbc.update("update analysis_working_draft set payload_json='{\"businessText\":\"REVISED\"}'");
            var revised = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), revised);
            assertThat(records(revised, "cluster-runs")).isEmpty();
            assertThat(records(revised, "cluster-inputs")).isEmpty();
        }
    }

    @Test void runPayloadCannotContradictTheScopedOwner() throws Exception {
        try (var fixture = fixture()) {
            run(fixture, "mismatch", "alice", "repo-a", "private-a", 20, "RUNNING", "PRIVATE-BODY");
            String command = fixture.jdbc.queryForObject("select command_json from analysis_cluster_run", String.class);
            fixture.jdbc.update("update analysis_cluster_run set command_json=?", command.replace("alice", "bob"));
            var output = new Contents();
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice")
                    .write(scoped(BackupProfile.REPOSITORY_HISTORY), output)).isInstanceOf(IOException.class);
            assertThat(output.text()).doesNotContain("PRIVATE-BODY");
        }
    }

    @Test void currentProjectLineagesRemainDistinctFromEachOtherAndTheAdHocDraft() throws Exception {
        try (var fixture = fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"AD-HOC\"}");
            run(fixture, "project-old", "alice", "repo-a", "private-a", 10, "COMPLETED", "OLD-VERSION", 1L, 11L);
            run(fixture, "project-current", "alice", "repo-a", "private-a", 20, "COMPLETED", "REQUIREMENT-ONE", 1L, 11L);
            run(fixture, "second-requirement", "alice", "repo-a", "private-a", 30, "COMPLETED", "REQUIREMENT-TWO", 1L, 12L);
            run(fixture, "another-project", "alice", "repo-a", "private-a", 40, "COMPLETED", "REQUIREMENT-OTHER-PROJECT", 2L, 11L);
            run(fixture, "ad-hoc", "alice", "repo-a", "private-a", 50, "COMPLETED", "AD-HOC");
            // The older snapshot may finish later, but must not replace the newer admission.
            fixture.jdbc.update("update analysis_cluster_run set updated_at=500 where id='project-old'");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output);
            assertThat(records(output, "cluster-runs")).extracting(row -> row.path("sourceId").path("value").asText())
                    .containsExactlyInAnyOrder("project-current", "second-requirement", "another-project", "ad-hoc");
            assertThat(output.text()).contains("REQUIREMENT-ONE", "REQUIREMENT-TWO", "REQUIREMENT-OTHER-PROJECT", "AD-HOC")
                    .doesNotContain("OLD-VERSION", "project-old-snapshot");
            assertThat(records(output, "cluster-runs").stream().filter(row -> row.path("sourceId").path("value").asText().equals("project-current")).toList())
                    .singleElement().satisfies(row -> {
                        assertThat(row.path("context").path("requirement").path("projectId").asLong()).isEqualTo(1);
                        assertThat(row.path("context").path("requirement").path("requirementId").asLong()).isEqualTo(11);
                        assertThat(row.path("context").path("requirement").path("snapshotId").asText()).isEqualTo("project-current-snapshot");
                    });
        }
    }

    @Test void childEnvelopeCannotPointAtAnotherOperation() throws Exception {
        try (var fixture = fixture()) {
            run(fixture, "parent", "alice", "repo-a", "private-a", 20, "RUNNING", "CURRENT");
            fixture.jdbc.update("update analysis_cluster_work set task_id='foreign:subtaxonomy:CP'");
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice")
                    .write(scoped(BackupProfile.REPOSITORY_HISTORY), new Contents())).isInstanceOf(IOException.class);
        }
    }

    static Fixture fixture() {
        return new Fixture(ClusterAnalysisRun.class, ClusterAnalysisWork.class, ClusterAnalysisInput.class, ClusterAnalysisEvent.class);
    }
    static void run(Fixture fixture, String id, String owner, String repository, String workspace,
                    long updated, String state, String text) {
        run(fixture, id, owner, repository, workspace, updated, state, text, null, null);
    }
    static void run(Fixture fixture, String id, String owner, String repository, String workspace,
                    long updated, String state, String text, Long project, Long requirement) {
        var mapper = new ObjectMapper();
        var reference = project == null && requirement == null ? RequirementReference.adHoc(text) : RequirementReference.of(project, requirement, id + "-snapshot", text);
        var context = new AnalysisOperationContext(id, new AnalysisSourceAuthority(repository, workspace, "draft", COMMIT), reference, id);
        var command = new AnalyzeRequirementCommand(text, false, 20, "LOCAL", owner, new WorkspaceContext(owner, workspace, "draft", repository));
        var messages = new AnalysisMessageFactory(context, Clock.systemUTC());
        var task = messages.task(AnalysisTaskGraph.plan(id, List.of(TaxonomyShardRoot.of("CP")), false).tasks().getFirst());
        var codec = new AnalysisMessageCodec();
        fixture.jdbc.update("insert into analysis_cluster_run(id,username,scope_key,project_id,requirement_id,context_json,command_json,state,total_roots,completed_roots,event_revision,created_at,updated_at,row_version) values(?,?,?,?,?,?,?,?,1,0,1,?,?,0)", id, owner, ClusterAnalysisStore.scopeKey(command.workspaceContext()), project, requirement, mapper.writeValueAsString(context), mapper.writeValueAsString(command), state, updated, updated);
        fixture.jdbc.update("insert into analysis_cluster_work(id,operation_id,task_id,task_type,root_code,ordinal_number,message_json,state,settled,delivery_attempts) values(?,?,?,?, 'CP',0,?,'RUNNING',false,1)", id + "-work", id, task.taskId().value(), task.taskType().name(), new String(codec.encode(task), java.nio.charset.StandardCharsets.UTF_8));
        fixture.jdbc.update("insert into analysis_cluster_input(id,operation_id,root_code,input_json) values(?,?,'CP','{\"source\":\"FROZEN-CP\"}')", id + "-input", id);
        fixture.jdbc.update("insert into analysis_cluster_event(id,operation_id,event_revision,event_json) values(?,?,1,?)", id + "-event", id, new String(codec.encode(messages.progress(1, AnalysisProgressPhase.PLANNED, null, 0, 1)), java.nio.charset.StandardCharsets.UTF_8));
    }
    static SnapshotContext scoped(BackupProfile profile) {
        return snapshot(profile, new BackupScope.Workspace("repo-a", "private-a"));
    }
    private static List<com.fasterxml.jackson.databind.JsonNode> records(Contents contents, String dataset) {
        byte[] bytes = contents.entries.get("data/analysis/" + dataset + ".ndjson");
        assertThat(bytes).as(dataset).isNotNull();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).map(line -> {
            try { return com.taxonomy.exchange.backup.PortableRows.json().readTree(line); }
            catch (IOException failure) { throw new AssertionError(failure); }
        }).toList();
    }
}
