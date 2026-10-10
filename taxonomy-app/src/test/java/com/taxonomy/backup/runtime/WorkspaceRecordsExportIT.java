package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.model.ContextHistoryRecord;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor;
import com.taxonomy.workspace.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.taxonomy.backup.runtime.CurrentStateExportIT.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceRecordsExportIT {
    @ParameterizedTest @CsvSource({
            "system_repository,repository_id", "repository_membership,repository_id",
            "user_workspace,source_repository_id", "user_workspace,workspace_id",
            "editor_workspace,repository_id", "editor_workspace,workspace_id",
            "architecture_commit_index,repository_id", "architecture_commit_index,workspace_id"})
    void caseInsensitiveSqlCannotExportRowsOutsideTheExactCapturedKeys(String table, String column) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            String selected = column.equals("workspace_id") ? "private-a" : "repo-a";
            fixture.jdbc.update("update " + table + " set " + column + "=?", selected.toUpperCase(java.util.Locale.ROOT));
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Integer.class, selected)).isEqualTo(1);
            assertThatThrownBy(() -> new WorkspaceBackupContributor(fixture.database, dsl -> dsl).write(
                    snapshot(BackupProfile.REPOSITORY_HISTORY, new BackupScope.Workspace("repo-a", "private-a")), new Contents()))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @ParameterizedTest @CsvSource({"sync_state,user_workspace,workspace_id,CURRENT_STATE",
            "sync_state,user_workspace,workspace_id,INSTALLATION_CURRENT",
            "editor_operation,editor_workspace,scope_id,REPOSITORY_HISTORY",
            "editor_operation,editor_workspace,scope_id,INSTALLATION_FULL",
            "editor_checkpoint,editor_workspace,scope_id,REPOSITORY_HISTORY",
            "editor_checkpoint,editor_workspace,scope_id,INSTALLATION_FULL"})
    void caseAliasedChildrenCannotBorrowTheirParentsCapturedScope(String child, String parent, String column, BackupProfile profile) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            fixture.jdbc.update("update " + child + " set " + column + "=upper(" + column + ")");
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + child + " c join " + parent + " p on p." + column + "=c." + column, Integer.class)).isEqualTo(1);
            BackupScope scope = profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace("repo-a", "private-a");
            assertThatThrownBy(() -> new WorkspaceBackupContributor(fixture.database, dsl -> dsl).write(snapshot(profile, scope), new Contents()))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    private static Fixture caseInsensitiveFixture() {
        return new Fixture(configuration -> new org.springframework.jdbc.core.JdbcTemplate(
                (javax.sql.DataSource) configuration.getProperties().get("hibernate.connection.datasource"))
                .execute("SET DATABASE SQL IGNORECASE TRUE"));
    }

    @Test void aCentralCaptureKeepsCentralCommitEvidenceAndExcludesPrivateWorkspaceBodies() throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            try (var em = fixture.factory.createEntityManager()) {
                var tx = em.getTransaction(); tx.begin();
                var commit = em.createQuery("from ArchitectureCommitIndex", ArchitectureCommitIndex.class).getSingleResult();
                commit.setWorkspaceId(null); commit.setMessage("CENTRAL-HISTORY"); tx.commit();
            }
            var output = new Contents();
            new WorkspaceBackupContributor(fixture.database, dsl -> dsl).write(snapshot(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Repositories(java.util.Map.of("repo-a", java.util.Set.of()))), output);
            assertThat(records(output, "commit-index")).hasSize(1);
            assertThat(records(output, "commit-index").getFirst().path("workspaceId").isNull()).isTrue();
            for (String dataset : List.of("workspaces", "synchronization", "working-states", "operations", "checkpoints"))
                assertThat(records(output, dataset)).as(dataset).isEmpty();
            assertThat(output.text()).contains("CENTRAL-HISTORY").doesNotContain("CURRENT-ALICE", "JOURNAL-OLD-ALICE", "CHECKPOINT-OLD-ALICE");
        }
    }

    @Test void scopedProfilesPreserveOwnershipAndCurrentWorkWhileSeparatingJournalHistory() throws Exception {
        try (var fixture = new Fixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            graph(fixture, "repo-b", "private-b", "BOB");
            var contributor = new WorkspaceBackupContributor(fixture.database, dsl -> dsl.replace("EMBEDDED-OLD-ALICE", "PROJECTED"));
            var scope = new BackupScope.Workspace("repo-a", "private-a");
            var current = new Contents();
            contributor.write(snapshot(BackupProfile.CURRENT_STATE, scope), current);
            assertThat(current.entries).hasSize(9);
            for (String dataset : List.of("repositories", "workspaces", "memberships", "synchronization", "working-states")) {
                assertThat(records(current, dataset)).as(dataset).hasSize(1);
            }
            for (String dataset : List.of("operations", "checkpoints", "commit-index", "context-history")) {
                assertThat(records(current, dataset)).as(dataset).isEmpty();
            }
            assertThat(records(current, "repositories").getFirst().path("repositoryId").asText()).isEqualTo("repo-a");
            assertThat(records(current, "memberships").getFirst().path("principalScope").asText()).isEqualTo("ALICE");
            var working = records(current, "working-states").getFirst();
            var editorId = PortableRows.json().valueToTree(new SourceRecordId("workspace.editor", "editor-ALICE"));
            assertThat(working.path("sourceId")).isEqualTo(editorId);
            assertThat(working.path("dsl").asText()).isEqualTo("CURRENT-ALICE PROJECTED");
            assertThat(working.path("semanticRevision").asLong()).isEqualTo(2);
            assertThat(working.path("checkpointCommit").asText()).isEqualTo(COMMIT);
            assertThat(current.text()).doesNotContain("BOB", "JOURNAL-OLD", "CHECKPOINT-OLD", "EMBEDDED-OLD", "NAVIGATION", "SOURCE-STORAGE", "SOURCE-CREDENTIAL", "PROVISIONING-ERROR");

            var history = new Contents();
            contributor.write(snapshot(BackupProfile.REPOSITORY_HISTORY, scope), history);
            for (String dataset : List.of("operations", "checkpoints", "commit-index")) {
                assertThat(records(history, dataset)).as(dataset).hasSize(1);
            }
            assertThat(history.text()).contains("JOURNAL-OLD-ALICE", "CHECKPOINT-OLD-ALICE", "EMBEDDED-OLD-ALICE")
                    .doesNotContain("BOB", "NAVIGATION", "SOURCE-STORAGE", "SOURCE-CREDENTIAL", "PROVISIONING-ERROR");
            assertThat(records(history, "working-states").getFirst().path("sourceId")).isEqualTo(editorId);
            assertThat(records(history, "operations").getFirst().path("editor")).isEqualTo(editorId);
            assertThat(records(history, "checkpoints").getFirst().path("editor")).isEqualTo(editorId);
            assertThat(fixture.jdbc.queryForObject("select dsl from editor_workspace where scope_id='editor-ALICE'", String.class))
                    .isEqualTo("1:CURRENT-ALICE EMBEDDED-OLD-ALICE");
            assertThat(fixture.jdbc.queryForObject("select count(*) from editor_operation", Integer.class)).isEqualTo(2);
        }
    }

    @Test void fullInstallationIncludesNavigationButSelectedCommitHasNoUnversionedRows() throws Exception {
        try (var fixture = new Fixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            graph(fixture, "repo-b", "private-b", "BOB");
            fixture.jdbc.update("update system_repository set visibility=null where repository_id='repo-b'");
            var contributor = new WorkspaceBackupContributor(fixture.database, dsl -> dsl);
            var full = new Contents();
            contributor.write(snapshot(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation()), full);
            for (String dataset : List.of("repositories", "workspaces", "memberships", "synchronization", "working-states", "operations", "checkpoints", "commit-index", "context-history")) {
                assertThat(records(full, dataset)).as(dataset).hasSize(2);
            }
            assertThat(full.text()).contains("NAVIGATION-ALICE", "NAVIGATION-BOB").doesNotContain("SOURCE-STORAGE", "SOURCE-CREDENTIAL");
            var selected = new Contents();
            contributor.write(snapshot(BackupProfile.SELECTED_VERSION, new BackupScope.Workspace("repo-a", "private-a")), selected);
            assertThat(selected.entries).hasSize(9);
            for (var entry : selected.entries.entrySet()) {
                assertThat(new String(entry.getValue(), StandardCharsets.UTF_8).lines()).as(entry.getKey())
                        .hasSize(1).allMatch(line -> line.contains("\"schemaVersion\":1") && line.contains("SELECTED_VERSION"));
            }
            assertThat(contributor.componentId()).isEqualTo(new BackupComponentId("workspace"));
            assertThat(contributor.schemaVersion()).isEqualTo(1);
            assertThat(contributor.categories()).hasSize(9).contains(UserWorkspace.class.getName(), ContextHistoryRecord.class.getName());
            assertThat(contributor.omissions(BackupProfile.SELECTED_VERSION)).anyMatch(value -> value.contains("selected Git state is authoritative"));
            assertThat(contributor.omissions(BackupProfile.INSTALLATION_FULL)).noneMatch(value -> value.contains("context navigation"));
        }
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> records(Contents contents, String dataset) {
        return new String(contents.entries.get("data/workspace/" + dataset + ".ndjson"), StandardCharsets.UTF_8)
                .lines().skip(1).map(line -> {
                    try { return PortableRows.json().readTree(line); }
                    catch (java.io.IOException error) { throw new AssertionError(error); }
                }).toList();
    }

    private static void graph(Fixture fixture, String repositoryId, String workspaceId, String owner) {
        try (var em = fixture.factory.createEntityManager()) {
            var tx = em.getTransaction(); tx.begin();
            var repository = new SystemRepository();
            repository.setRepositoryId(repositoryId); repository.setSlug(repositoryId);
            repository.setDisplayName("Repository " + owner); repository.setDescription("Description " + owner);
            repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED); repository.setOwnerId(owner);
            repository.setStorageRepositoryName("SOURCE-STORAGE-" + owner); repository.setProvisioningError("PROVISIONING-ERROR-" + owner);
            repository.setCreatedAt(NOW); repository.setUpdatedAt(NOW); repository.setCreatedBy(owner);
            repository.setForkPointCommit(COMMIT); repository.setLastFetchAt(NOW); repository.setLastPushAt(NOW);
            repository.setLastFetchCommit(COMMIT); em.persist(repository);
            var workspace = new UserWorkspace();
            workspace.setWorkspaceId(workspaceId); workspace.setUsername(owner); workspace.setDisplayName("Workspace " + owner);
            workspace.setSourceRepositoryId(repositoryId); workspace.setSourceBranch("draft"); workspace.setCreatedAt(NOW);
            workspace.setLastAccessedAt(NOW); workspace.setProvisionedAt(NOW); workspace.setCurrentCommit(COMMIT);
            workspace.setBaseCommit(COMMIT); workspace.setLastFetchedCommit(COMMIT); workspace.setLastIntegratedCommit(COMMIT);
            workspace.setSyncTargetBranch("draft"); workspace.setDescription("Saved work " + owner); em.persist(workspace);
            var membership = new RepositoryMembership();
            membership.setRepositoryId(repositoryId); membership.setUsername(owner); membership.setRole(RepositoryRole.OWNER);
            membership.setCreatedBy(owner); membership.setCreatedAt(NOW); membership.setUpdatedAt(NOW); em.persist(membership);
            var sync = new SyncState();
            sync.setUsername(owner); sync.setWorkspaceId(workspaceId); sync.setLastSyncedCommitId(COMMIT);
            sync.setLastSyncTimestamp(NOW); sync.setLastPublishedCommitId(COMMIT); sync.setLastPublishTimestamp(NOW);
            sync.setSyncStatus("UP_TO_DATE"); sync.setUnpublishedCommitCount(1); sync.setCreatedAt(NOW); sync.setUpdatedAt(NOW); em.persist(sync);
            var commit = new ArchitectureCommitIndex();
            commit.setRepositoryId(repositoryId); commit.setWorkspaceId(workspaceId); commit.setCommitId(COMMIT);
            commit.setAuthor(owner); commit.setCommitTimestamp(NOW); commit.setMessage("Historical commit " + owner);
            commit.setChangedFiles("architecture.tax"); commit.setTokenizedChangeText("saved element " + owner);
            commit.setAffectedElementIds("element-1"); commit.setAffectedRelationIds("relation-1"); commit.setBranch("draft"); commit.setIndexedAt(NOW); em.persist(commit);
            var context = new ContextHistoryRecord();
            context.setUsername(owner); context.setFromContextId("source-" + owner); context.setToContextId("target-" + owner);
            context.setFromBranch("draft"); context.setToBranch("main"); context.setFromCommitId(COMMIT); context.setToCommitId(COMMIT);
            context.setReason("NAVIGATION-" + owner); context.setOriginContextId(workspaceId); context.setCreatedAt(NOW); em.persist(context);
            tx.commit();
        }
        fixture.jdbc.update("update system_repository set external_auth_token=? where repository_id=?", "SOURCE-CREDENTIAL-" + owner, repositoryId);
        String editor = "editor-" + owner;
        fixture.editor(editor, repositoryId, workspaceId, "CURRENT-" + owner + " EMBEDDED-OLD-" + owner);
        fixture.operation(editor, "JOURNAL-OLD-" + owner);
        fixture.jdbc.update("insert into editor_checkpoint(id,scope_id,command_id,actor,occurred_at,rationale,fingerprint,from_revision,semantic_revision,expected_commit,dsl,commit_id,completed,commit_created,origin) values(?,?,?,?,?,?,?,1,2,?,?,?,true,true,?)",
                UUID.randomUUID().toString(), editor, UUID.randomUUID().toString(), owner, NOW.toString(), "Checkpoint", "b".repeat(64), COMMIT,
                "1:CHECKPOINT-OLD-" + owner, COMMIT, "EDITOR");
    }
}
