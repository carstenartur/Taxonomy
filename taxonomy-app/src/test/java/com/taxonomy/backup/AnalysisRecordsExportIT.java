package com.taxonomy.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.analysis.backup.AnalysisBackupContributor;
import com.taxonomy.exchange.backup.PortableRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.IntConsumer;
import static com.taxonomy.backup.CurrentStateExportIT.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisRecordsExportIT {
    @ParameterizedTest @CsvSource({
            "Alice,INSTALLATION_CURRENT", "Alice,INSTALLATION_FULL",
            "' alice ',INSTALLATION_CURRENT", "' alice ',INSTALLATION_FULL"})
    void installationCaptureRejectsNoncanonicalDraftOwnersBeforeReadingPayload(String persistedOwner, BackupProfile profile) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"PRIVATE-DRAFT\"}");
            fixture.jdbc.update("update analysis_working_draft set username=?", persistedOwner);
            String id = run(fixture, "repo-a", "private-a", "draft", "Alice", 10, "PRIVATE-RUN");
            question(fixture, id, "PRIVATE-QUESTION");
            var output = new Contents();
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "Alice")
                    .write(snapshot(profile, new BackupScope.Installation()), output))
                    .isInstanceOf(IOException.class).hasMessage("Analysis draft owner is not canonical");
            assertThat(output.text()).doesNotContain("PRIVATE-DRAFT", "PRIVATE-RUN", "PRIVATE-QUESTION");
            assertThat(fixture.jdbc.queryForObject("select username from analysis_working_draft", String.class))
                    .isEqualTo(persistedOwner);
        }
    }

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "REPOSITORY_HISTORY", "INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void mixedCaseOwnersKeepBothSourceRepresentationsAndTheirCompletedCurrentRun(BackupProfile profile) throws Exception {
        try (var fixture = new Fixture()) {
            String draftOwner = com.taxonomy.workspace.service.WorkspaceScope.username("Alice", null);
            assertThat(draftOwner).isEqualTo("alice");
            fixture.draft("repo-a", "private-a", draftOwner, "{\"businessText\":\"CURRENT\"}");
            String id = run(fixture, "repo-a", "private-a", "draft", "Alice", 10, "CURRENT");
            fixture.jdbc.update("update analysis_continuation set state='COMPLETED' where id=?", id);
            question(fixture, id, "CURRENT-QUESTION");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "Alice").write(selected(profile), output);
            assertThat(records(output, "drafts")).hasSize(1);
            assertThat(records(output, "drafts").getFirst().path("principalScope").asText()).isEqualTo("alice");
            assertThat(ids(output, "continuations")).containsExactly(id);
            assertThat(records(output, "continuations").getFirst().path("principalScope").asText()).isEqualTo("Alice");
            assertThat(output.text()).contains("CURRENT-QUESTION");
        }
    }
    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "INSTALLATION_CURRENT"})
    void mixedCaseRunOwnerCannotBypassTheNormalizedDraftsCurrentText(BackupProfile profile) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", com.taxonomy.workspace.service.WorkspaceScope.username("Alice", null), "{\"businessText\":\"CURRENT\"}");
            String id = run(fixture, "repo-a", "private-a", "draft", "Alice", 10, "DELETED-TEXT");
            question(fixture, id, "DELETED-QUESTION");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "Alice").write(selected(profile), output);
            assertThat(ids(output, "continuations")).isEmpty();
            assertThat(records(output, "questions")).isEmpty();
            assertThat(output.text()).contains("CURRENT").doesNotContain("DELETED-TEXT", "DELETED-QUESTION");
        }
    }
    @ParameterizedTest @CsvSource({
            "analysis_working_draft,username,alice,CURRENT_STATE", "analysis_working_draft,username,alice,REPOSITORY_HISTORY",
            "analysis_working_draft,workspace_id,private-a,CURRENT_STATE", "analysis_working_draft,workspace_id,private-a,REPOSITORY_HISTORY",
            "analysis_continuation,username,alice,CURRENT_STATE", "analysis_continuation,username,alice,REPOSITORY_HISTORY",
            "analysis_continuation,repository_id,repo-a,CURRENT_STATE", "analysis_continuation,repository_id,repo-a,REPOSITORY_HISTORY",
            "analysis_continuation,workspace_id,private-a,CURRENT_STATE", "analysis_continuation,workspace_id,private-a,REPOSITORY_HISTORY"})
    void databaseCollationCannotBroadenCapturedOwnership(String table, String column, String selected, BackupProfile profile) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            if (table.equals("analysis_working_draft")) fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"PRIVATE-BODY\"}");
            else run(fixture, "repo-a", "private-a", "draft", "alice", 10, "PRIVATE-BODY");
            fixture.jdbc.update("update " + table + " set " + column + "=?", selected.toUpperCase(Locale.ROOT));
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Integer.class, selected)).isEqualTo(1);
            var output = new Contents();
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice").write(scoped(profile), output)).isInstanceOf(IOException.class);
            assertThat(output.text()).doesNotContain("PRIVATE-BODY");
        }
    }
    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void installationCaptureStillRejectsContradictoryDraftWorkspaceIdentity(BackupProfile profile) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"MISROUTED\"}");
            fixture.jdbc.update("update analysis_working_draft set workspace_id='private-b'");
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice").write(snapshot(profile, new BackupScope.Installation()), new Contents())).isInstanceOf(IOException.class);
        }
    }
    @ParameterizedTest @CsvSource({"branch_name,DRAFT,CURRENT_STATE", "branch_name,DRAFT,INSTALLATION_CURRENT",
            "username,ALICE,INSTALLATION_CURRENT", "repository_id,REPO-A,INSTALLATION_CURRENT", "workspace_id,PRIVATE-A,INSTALLATION_CURRENT"})
    void newestRunSelectionCannotCollapseDistinctExactIdentities(String column, String alias, BackupProfile profile) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            String first = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "FIRST-IDENTITY");
            String second = run(fixture, "repo-a", "private-a", "draft", "alice", 20, "SECOND-IDENTITY");
            fixture.jdbc.update("update analysis_continuation set " + column + "=? where id=?", alias, second);
            question(fixture, first, "FIRST-QUESTION"); question(fixture, second, "SECOND-QUESTION");
            assertThat(fixture.jdbc.queryForObject("select count(*) from analysis_continuation a join analysis_continuation b "
                    + "on a.repository_id=b.repository_id and a.workspace_id=b.workspace_id and a.branch_name=b.branch_name and a.username=b.username "
                    + "where a.id=? and b.id=?", Integer.class, first, second)).isEqualTo(1);
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(selected(profile), output);
            assertThat(ids(output, "continuations")).containsExactlyInAnyOrder(first, second);
            assertThat(output.text()).contains("FIRST-IDENTITY", "SECOND-IDENTITY", "FIRST-QUESTION", "SECOND-QUESTION");
        }
    }
    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "REPOSITORY_HISTORY", "INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void aCaseAliasedQuestionReferenceFailsInsteadOfDisappearing(BackupProfile profile) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            String run = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "CURRENT");
            question(fixture, run, "PRIVATE-QUESTION");
            fixture.jdbc.update("update analysis_question_checkpoint set run_id=?", run.toUpperCase(Locale.ROOT));
            assertThat(fixture.jdbc.queryForObject("select count(*) from analysis_question_checkpoint q join analysis_continuation r on r.id=q.run_id", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice").write(selected(profile), new Contents())).isInstanceOf(IOException.class);
        }
    }
    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "REPOSITORY_HISTORY", "INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void profilesKeepCurrentWorkAndExactQuestionReferencesWithoutWorkerClaims(BackupProfile profile) throws Exception {
        try (var fixture = new Fixture()) {
            var selectedIds = new ArrayList<String>(); var allIds = new ArrayList<String>();
            for (String owner : List.of("alice", "bob")) {
                String workspace = owner.equals("alice") ? "private-a" : "private-b";
                fixture.draft("repo-a", workspace, owner, "{\"businessText\":\"CURRENT-" + owner
                        + "\",\"lastAnalyzedText\":\"CURRENT-" + owner + "\",\"storedBusinessText\":\"CURRENT-" + owner
                        + "\",\"scores\":{\"N1\":80},\"analysisOptions\":{\"maxNodes\":20},\"history\":[\"OLD-DRAFT-" + owner + "\"]}");
                String old = run(fixture, "repo-a", workspace, "draft", owner, 10, "OLD-RUN-" + owner);
                String current = run(fixture, "repo-a", workspace, "draft", owner, 20, "CURRENT-" + owner);
                question(fixture, old, "OLD-QUESTION-" + owner); question(fixture, current, "CURRENT-QUESTION-" + owner);
                if (owner.equals("alice") || profile.isInstallation()) { selectedIds.add(current); allIds.add(old); allIds.add(current); }
            }
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(selected(profile), output);
            assertThat(output.entries).hasSize(3);
            List<String> expected = profile.includesHistory() ? allIds : selectedIds;
            assertThat(ids(output, "continuations")).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(records(output, "questions")).extracting(r -> r.path("run").path("value").asText()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(output.text()).contains("CURRENT-alice", "REVIEW_REQUIRED", "maxNodes", "\"N1\":80").doesNotContain("WORKER-CLAIM", "PRIVATE-WORKER-ERROR");
            if (!profile.includesHistory()) assertThat(output.text()).doesNotContain("OLD-RUN-", "OLD-DRAFT-", "OLD-QUESTION-");
            if (!profile.isInstallation()) assertThat(output.text()).doesNotContain("bob");
            assertThat(fixture.jdbc.queryForObject("select count(*) from analysis_continuation", Integer.class)).isEqualTo(4);
        }
    }
    @Test void latestSelectionUsesTimestampThenExactIdAndDoesNotParseOldBodies() throws Exception {
        try (var fixture = new Fixture()) {
            String old = run(fixture, "repo-a", "private-a", "draft", "alice", 1, "OLD");
            fixture.jdbc.update("update analysis_continuation set request_json='not-json' where id=?", old);
            String lower = run(fixture, "repo-a", "private-a", "draft", "alice", 20, "TIED-LOWER");
            String higher = run(fixture, "repo-a", "private-a", "draft", "alice", 20, "TIED-HIGHER");
            fixture.jdbc.update("update analysis_continuation set id=? where id=?", "aaaaaaaa-0000-0000-0000-000000000001", lower);
            fixture.jdbc.update("update analysis_continuation set id=? where id=?", "bbbbbbbb-0000-0000-0000-000000000001", higher);
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output);
            assertThat(ids(output, "continuations")).containsExactly("bbbbbbbb-0000-0000-0000-000000000001");
            assertThat(output.text()).contains("TIED-HIGHER").doesNotContain("TIED-LOWER", "not-json");
        }
    }
    @Test void staleAndCompletedUnattachedRunsDoNotLeakIntoCurrentDraftCapture() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"CURRENT-TEXT\"}");
            String stale = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "OLD-TEXT");
            String completed = run(fixture, "repo-a", "private-a", "other", "alice", 20, "FINISHED-TEXT");
            fixture.jdbc.update("update analysis_continuation set state='COMPLETED' where id=?", completed);
            String pending = run(fixture, "repo-a", "private-a", "pending", "alice", 20, "PENDING-TEXT");
            question(fixture, stale, "OLD-QUESTION"); question(fixture, completed, "FINISHED-QUESTION"); question(fixture, pending, "PENDING-QUESTION");
            var output = new Contents();
            new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output);
            assertThat(ids(output, "continuations")).containsExactly(pending);
            assertThat(output.text()).contains("CURRENT-TEXT", "PENDING-TEXT", "PENDING-QUESTION").doesNotContain("OLD-TEXT", "OLD-QUESTION", "FINISHED-TEXT", "FINISHED-QUESTION");
        }
    }
    @Test void aCurrentCaptureKeepsMoreThanOneBatchOfDistinctBranches() throws Exception {
        try (var fixture = new Fixture()) {
            var expected = new ArrayList<String>();
            for (int i = 0; i < 205; i++) { String id = run(fixture, "repo-a", "private-a", "branch-" + i, "alice", i, "CURRENT-" + i); expected.add(id); question(fixture, id, "QUESTION-" + i); }
            var output = new Contents(); var database = new CaptureDatabase(fixture, ignored -> { });
            new AnalysisBackupContributor(database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output);
            assertThat(ids(output, "continuations")).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(records(output, "questions")).hasSize(205);
            assertThat(database.sql).hasSize(6);
            assertThat(database.sql.get(1)).doesNotContain("request_json", "result_json");
            assertThat(database.sql.subList(2, 6).stream().map(query -> query.chars().filter(c -> c == '?').count())).containsExactly(200L, 5L, 200L, 5L);
            database.assertClosed();
        }
    }
    @ParameterizedTest @CsvSource({"username,bob", "repository_id,repo-b", "workspace_id,private-b", "branch_name,other",
            "input_hash,changed", "state,COMPLETED", "updated_at,20", "row_version,1", "current_node,N2", "id,missing"})
    void selectedRunMetadataMustStillMatchBeforeAnyBodyIsRead(String column, String replacement) throws Exception {
        try (var fixture = new Fixture()) {
            String id = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "PRIVATE-BODY");
            var database = new CaptureDatabase(fixture, number -> { if (number == 3) fixture.jdbc.update("update analysis_continuation set " + column + "=? where id=?", replacement, id); });
            var output = new Contents();
            assertThatThrownBy(() -> new AnalysisBackupContributor(database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output)).isInstanceOf(IOException.class);
            assertThat(output.text()).doesNotContain("PRIVATE-BODY"); database.assertClosed();
        }
    }
    @Test void aDeletedSelectedRunCannotBecomeSuccessfulEmptyOutput() throws Exception {
        try (var fixture = new Fixture()) {
            String id = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "CURRENT");
            var database = new CaptureDatabase(fixture, number -> { if (number == 3) fixture.jdbc.update("delete from analysis_continuation where id=?", id); });
            assertThatThrownBy(() -> new AnalysisBackupContributor(database, "alice").write(scoped(BackupProfile.CURRENT_STATE), new Contents())).isInstanceOf(IOException.class).hasMessage("Analysis continuation disappeared during capture");
            database.assertClosed();
        }
    }
    @Test void aCaseAliasedBodyIdCannotReplaceTheSelectedId() throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            String id = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "PRIVATE-BODY");
            var database = new CaptureDatabase(fixture, number -> { if (number == 3) fixture.jdbc.update("update analysis_continuation set id=? where id=?", id.toUpperCase(Locale.ROOT), id); });
            assertThatThrownBy(() -> new AnalysisBackupContributor(database, "alice").write(scoped(BackupProfile.CURRENT_STATE), new Contents())).isInstanceOf(IOException.class).hasMessage("Analysis continuation changed during capture");
            database.assertClosed();
        }
    }
    @Test void questionParentMustStillMatchTheEmittedContinuation() throws Exception {
        try (var fixture = new Fixture()) {
            String id = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "CURRENT"); question(fixture, id, "PRIVATE-QUESTION");
            var database = new CaptureDatabase(fixture, number -> { if (number == 4) fixture.jdbc.update("update analysis_continuation set row_version=row_version+1 where id=?", id); });
            var output = new Contents();
            assertThatThrownBy(() -> new AnalysisBackupContributor(database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output)).isInstanceOf(IOException.class).hasMessage("Analysis question reference is inconsistent");
            assertThat(output.text()).doesNotContain("PRIVATE-QUESTION"); database.assertClosed();
        }
    }
    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void cancellationClosesMetadataBodyAndQuestionTransactions(int phase) throws Exception {
        try (var fixture = new Fixture()) {
            String id = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "CURRENT"); question(fixture, id, "QUESTION");
            var database = new CaptureDatabase(fixture, number -> { if (number == phase) Thread.currentThread().interrupt(); });
            try {
                assertThatThrownBy(() -> new AnalysisBackupContributor(database, "alice").write(scoped(BackupProfile.CURRENT_STATE), new Contents())).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            database.assertClosed();
        }
    }
    @ParameterizedTest @ValueSource(strings = {"legacy-scope", "v2|r06:repo-a|s19:WORKSPACE:private-a|b5:draft", "v2|r6:repo-a|s7:CENTRAL|b5:draft"})
    void installationDraftIdentityMustBeCanonicalAndDescribeItsWorkspace(String tenant) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{\"businessText\":\"PRIVATE-DRAFT\"}"); fixture.jdbc.update("update analysis_working_draft set scope_key=?", tenant);
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice").write(snapshot(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation()), new Contents())).isInstanceOf(IOException.class).hasMessage("Analysis draft scope is inconsistent");
        }
    }
    @Test void emptyCurrentDraftStillRetainsItsMatchingUnfinishedRun() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", "{}"); String id = run(fixture, "repo-a", "private-a", "draft", "alice", 10, "old"); fixture.jdbc.update("update analysis_continuation set request_json='{}' where id=?", id);
            var output = new Contents(); new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), output);
            assertThat(ids(output, "continuations")).containsExactly(id); assertThat(records(output, "questions")).isEmpty();
        }
    }
    @Test void selectedVersionDoesNotOpenThePresentDayDatabase() throws Exception {
        var unavailable = new AbstractDataSource() {
            @Override public Connection getConnection() { throw new AssertionError("Selected Git capture must not read current analysis rows"); }
            @Override public Connection getConnection(String username, String password) { return getConnection(); }
        };
        var output = new Contents(); new AnalysisBackupContributor(unavailable, "alice").write(scoped(BackupProfile.SELECTED_VERSION), output);
        assertThat(output.entries).hasSize(3);
        for (String dataset : List.of("drafts", "continuations", "questions")) assertThat(records(output, dataset)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{\"businessText\":false}"})
    void malformedAnalysisBodiesFailRatherThanBecomingEmptyRecords(String payload) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.draft("repo-a", "private-a", "alice", payload);
            assertThatThrownBy(() -> new AnalysisBackupContributor(fixture.database, "alice").write(scoped(BackupProfile.CURRENT_STATE), new Contents())).isInstanceOf(IOException.class);
        }
    }
    private static SnapshotContext scoped(BackupProfile profile) { return snapshot(profile, new BackupScope.Workspace("repo-a", "private-a")); }
    private static SnapshotContext selected(BackupProfile profile) { return profile.isInstallation() ? snapshot(profile, new BackupScope.Installation()) : scoped(profile); }
    private static Fixture caseInsensitiveFixture() {
        return new Fixture(configuration -> new org.springframework.jdbc.core.JdbcTemplate((javax.sql.DataSource) configuration.getProperties().get("hibernate.connection.datasource")).execute("SET DATABASE SQL IGNORECASE TRUE"));
    }
    private static String run(Fixture fixture, String repository, String workspace, String branch, String owner, long updated, String text) {
        String id = "a" + UUID.randomUUID().toString().substring(1);
        fixture.jdbc.update("insert into analysis_continuation(id,username,repository_id,workspace_id,branch_name,input_hash,request_json,result_json,state,claim_token,claim_until,updated_at,payload_characters,current_node,row_version) values(?,?,?,?,?,?,?,?, 'PAUSED','WORKER-CLAIM',999,?,0,'N1',0)",
                id, owner, repository, workspace, branch, "a".repeat(64), "{\"businessText\":\"" + text + "\"}", "{\"text\":\"" + text + "\"}", updated);
        return id;
    }
    private static void question(Fixture fixture, String run, String text) {
        fixture.jdbc.update("insert into analysis_question_checkpoint(id,run_id,question_key,input_hash,provider,node_codes,detail_json,prompt_text,state,attempts,started_at,error_text) values(?,?,'first-question',?,'LOCAL','[\"N1\"]',?,?,'SUCCESS',1,10,'PRIVATE-WORKER-ERROR')",
                run + ":question", run, "b".repeat(64), "{\"text\":\"" + text + "\"}", text);
    }
    private static List<String> ids(Contents contents, String dataset) { return records(contents, dataset).stream().map(r -> r.path("sourceId").path("value").asText()).toList(); }
    private static List<JsonNode> records(Contents contents, String dataset) {
        byte[] bytes = contents.entries.get("data/analysis/" + dataset + ".ndjson"); assertThat(bytes).as(dataset).isNotNull();
        return new String(bytes, StandardCharsets.UTF_8).lines().skip(1).map(line -> { try { return PortableRows.json().readTree(line); } catch (IOException failure) { throw new AssertionError(failure); } }).toList();
    }
    private static final class CaptureDatabase extends AbstractDataSource {
        private final Fixture fixture; private final IntConsumer beforeConnection;
        private final List<Connection> connections = new ArrayList<>(); private final List<String> sql = new ArrayList<>();
        private int rollbacks; private int transactions;
        CaptureDatabase(Fixture fixture, IntConsumer beforeConnection) { this.fixture = fixture; this.beforeConnection = beforeConnection; }
        @Override public Connection getConnection() throws SQLException {
            beforeConnection.accept(connections.size() + 1);
            var connection = fixture.database.getConnection(); connections.add(connection);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement")) { assertThat(connection.isReadOnly()).isTrue(); assertThat(connection.getAutoCommit()).isFalse(); sql.add((String) arguments[0]); }
                if (method.getName().equals("setAutoCommit") && Boolean.FALSE.equals(arguments[0])) transactions++;
                if (method.getName().equals("rollback")) rollbacks++;
                try { return method.invoke(connection, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        void assertClosed() throws SQLException { assertThat(rollbacks).isGreaterThanOrEqualTo(transactions); for (var connection : connections) assertThat(connection.isClosed()).isTrue(); }
    }
}
