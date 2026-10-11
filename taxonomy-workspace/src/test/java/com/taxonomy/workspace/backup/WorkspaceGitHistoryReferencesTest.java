package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.workspace.backup.WorkspaceGitHistoryReferences.Field.*;

class WorkspaceGitHistoryReferencesTest {
    private static final BackupRepositoryKey WORKSPACE = new BackupRepositoryKey("repo-a", "private-a");
    private static final BackupRepositoryKey CENTRAL = new BackupRepositoryKey("repo-a", null);
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitHistoryCapture.Limits LIMITS = GitHistoryCapture.Limits.defaults();

    @Test void collectsOriginalDatabaseOnlyCommitsAndEveryStoredBranchWithoutForeignWorkingCopies() throws Exception {
        try (var f = new Fixture()) {
            String head = f.commit("ws-private-a", "CURRENT");
            String orphan = f.orphan("ws-private-a", "DELETED BRANCH");
            String expected = f.orphan("ws-private-a", "INTERRUPTED CHECKPOINT BASE");
            f.base.saved(WORKSPACE, "draft", "RAW CURRENT", 4, head, 2, null);
            f.base.saved(WORKSPACE, "review", "RAW OTHER BRANCH", 9, orphan, 3, null);
            f.base.saved(new BackupRepositoryKey("repo-other", "private-a"), "foreign", "PRIVATE", 7, head, 0, null);
            f.base.sql("update user_workspace set current_commit=?", head);
            f.checkpoint("checkpoint-a", "review", expected, orphan);
            f.base.sql("insert into architecture_commit_index values(1,'repo-a','private-a',?)", orphan);
            var capture = f.inspect(workspace());
            assertThat(capture.requiredCommits().get(WORKSPACE)).containsExactlyInAnyOrder(head, orphan, expected);
            assertThat(capture.workingStates().get(WORKSPACE)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "draft", new SnapshotContext.WorkingState(4, head, 2),
                    "review", new SnapshotContext.WorkingState(9, orphan, 3)));
            assertThat(capture.references()).contains(
                    proof("workspace.checkpoint", "checkpoint-a", CHECKPOINT_EXPECTED, WORKSPACE, expected),
                    proof("workspace.checkpoint", "checkpoint-a", CHECKPOINT_COMMIT, WORKSPACE, orphan),
                    proof("workspace.commit-index", "1", INDEX_COMMIT, WORKSPACE, orphan));
            assertThat(capture.references()).allMatch(reference -> reference.repository().equals(WORKSPACE));
            try (var session = f.base.repositories.open(workspace(), WORKSPACE, CHECK)) {
                var history = session.captureHistory(capture.requiredCommits().get(WORKSPACE),
                        capture.workingStates().get(WORKSPACE), LIMITS, CHECK);
                assertThat(history.state().requiredCommits()).contains(head, orphan, expected);
            }
        }
    }

    @Test void crossRepositoryLegacyFieldsRecordTheProvenSourceKeyForEachTypedField() throws Exception {
        try (var f = new Fixture()) {
            String central = f.commit("central-a", "SOURCE BASE");
            String local = f.commit("ws-private-a", "SEPARATE WORKSPACE HISTORY");
            f.base.sql("update user_workspace set base_commit=?,current_commit=?,last_fetched_commit=?,last_integrated_commit=?", central, local, central, central);
            f.base.sql("insert into sync_state values(1,'private-a',?,?)", central, local);
            var capture = f.inspect(repositories());
            assertThat(capture.references()).contains(
                    proof("workspace.workspace", "private-a", WORKSPACE_BASE, CENTRAL, central),
                    proof("workspace.workspace", "private-a", WORKSPACE_CURRENT, WORKSPACE, local),
                    proof("workspace.sync", "1", SYNC_LAST_SYNCED, CENTRAL, central),
                    proof("workspace.sync", "1", SYNC_LAST_PUBLISHED, WORKSPACE, local));
            assertThat(capture.requiredCommits().get(CENTRAL)).containsExactly(central);
            assertThat(capture.requiredCommits().get(WORKSPACE)).containsExactly(local);
        }
    }

    @Test void doesNotOpenAnUnselectedCentralRepositoryToRepairAMissingWorkspaceClosure() throws Exception {
        try (var f = new Fixture()) {
            String central = f.commit("central-a", "NOT AUTHORIZED FOR THIS EXPORT");
            f.commit("ws-private-a", "LOCAL");
            f.base.sql("update user_workspace set base_commit=?", central);
            assertThatThrownBy(() -> f.inspect(workspace())).isInstanceOf(IOException.class);
        }
    }

    @Test void knownWorkspaceOwnershipCannotBeRepairedByFindingTheSameHashInAnotherRepository() throws Exception {
        try (var f = new Fixture()) {
            String central = f.commit("central-a", "WRONG CURRENT OWNER");
            f.commit("ws-private-a", "LOCAL");
            f.base.sql("update user_workspace set current_commit=?", central);
            assertThatThrownBy(() -> f.inspect(repositories())).isInstanceOf(IOException.class);
        }
    }

    @Test void installationNavigationUsesOnlyTheApprovedCatalogueAndNeverTreatsContextUuidAsRepositoryIdentity() throws Exception {
        try (var f = new Fixture()) {
            String central = f.commit("central-a", "CENTRAL");
            String local = f.commit("ws-private-a", "WORKSPACE");
            f.base.sql("insert into context_history_record values(1,?,?,?,?)", UUID.randomUUID().toString(), UUID.randomUUID().toString(), central, local);
            var auth = authorized(new BackupRequest(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation(),
                    new BackupTime.History(), GitRepresentation.BARE, SecretsSelection.EXCLUDE));
            var capture = f.source().inspect(auth, Set.of(CENTRAL, WORKSPACE), LIMITS, CHECK);
            assertThat(capture.references()).containsExactlyInAnyOrder(
                    proof("workspace.context-history", "1", CONTEXT_FROM, CENTRAL, central),
                    proof("workspace.context-history", "1", CONTEXT_TO, WORKSPACE, local));
        }
    }

    @Test void referenceBudgetAndCancellationAreEnforcedBeforeAnUnboundedDatabaseClosure() throws Exception {
        try (var f = new Fixture()) {
            String head = f.commit("ws-private-a", "LOCAL");
            f.base.sql("update user_workspace set base_commit=?,current_commit=?", head, head);
            assertThatThrownBy(() -> new WorkspaceGitHistoryReferences(f.base.database, f.base.repositories, 1)
                    .inspect(workspace(), Set.of(WORKSPACE), LIMITS, CHECK)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> f.source().inspect(workspace(), Set.of(WORKSPACE), LIMITS,
                    () -> { throw new IOException("cancelled"); })).isInstanceOf(IOException.class);
        }
    }

    @Test void caseInsensitiveDatabaseMatchesCannotWidenTheExactWorkingCopyScope() throws Exception {
        try (var f = new Fixture()) {
            f.commit("ws-private-a", "LOCAL");
            f.base.sql("alter table editor_workspace alter column repository_id varchar_ignorecase(255)");
            f.base.saved(new BackupRepositoryKey("REPO-A", "private-a"), "draft", "FOREIGN CASE", 1, null, 0, null);
            assertThatThrownBy(() -> f.inspect(workspace())).isInstanceOf(IOException.class);
        }
    }

    @Test void historyMembershipRequiresTheAuthorizedLiveSessionAndAnActualCommit() throws Exception {
        try (var f = new Fixture()) {
            String head = f.commit("ws-private-a", "LOCAL");
            var session = f.base.repositories.open(workspace(), WORKSPACE, CHECK);
            assertThat(session.containsHistoryCommit(head, LIMITS, CHECK)).isTrue();
            assertThat(session.containsHistoryCommit("a".repeat(40), LIMITS, CHECK)).isFalse();
            session.close();
            assertThatThrownBy(() -> session.containsHistoryCommit(head, LIMITS, CHECK)).isInstanceOf(IOException.class);
            var auth = authorized(new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo-a", "private-a"),
                    new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
            try (var current = f.base.repositories.open(auth, WORKSPACE, CHECK)) {
                assertThatThrownBy(() -> current.containsHistoryCommit(head, LIMITS, CHECK)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void savedWorkingBodiesHaveRawContentProofEvenWhenRevisionAndCheckpointDidNotChange() throws Exception {
        try (var f = new Fixture()) {
            String head = f.commit("ws-private-a", "COMMITTED");
            f.base.saved(WORKSPACE, "draft", "CURRENT embedded-secret", 4, head, 2, null);
            f.base.saved(WORKSPACE, "review", "OLD RAW BRANCH", 1, null, 0, null);
            var before = f.inspect(workspace());
            assertThat(before.workingCopies()).anySatisfy(proof -> {
                assertThat(proof.branch()).isEqualTo("draft");
                assertThat(proof.sha256()).isEqualTo(hash("CURRENT embedded-secret"));
            });
            f.base.sql("update editor_workspace set dsl='1:CHANGED WITHOUT REVISION' where branch='review'");
            var after = f.inspect(workspace());
            assertThat(after.workingStates()).isEqualTo(before.workingStates());
            assertThat(after.workingCopies()).isNotEqualTo(before.workingCopies());
        }
    }

    @Test void pendingCheckpointIsRetainedAsHistoricalEvidenceAndInvalidBodyFramingFailsClosed() throws Exception {
        try (var f = new Fixture()) {
            f.commit("ws-private-a", "COMMITTED");
            String pending = UUID.randomUUID().toString();
            f.base.saved(WORKSPACE, "draft", "INTERRUPTED RAW DRAFT", 1, null, 0, pending);
            assertThat(f.inspect(workspace()).workingCopies()).singleElement().satisfies(proof -> {
                assertThat(proof.interruptedCheckpoint()).isEqualTo(pending);
                assertThat(proof.sha256()).isEqualTo(hash("INTERRUPTED RAW DRAFT"));
            });
            f.base.sql("update editor_workspace set dsl='2:UNSUPPORTED BODY'");
            assertThatThrownBy(() -> f.inspect(workspace())).isInstanceOf(IOException.class);
        }
    }

    private static String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    private static WorkspaceGitHistoryReferences.CommitReference proof(String kind, String id,
            WorkspaceGitHistoryReferences.Field field, BackupRepositoryKey key, String commit) {
        return new WorkspaceGitHistoryReferences.CommitReference(new SourceRecordId(kind, id), field, key, commit);
    }
    private static AuthorizedBackupRequest workspace() {
        return authorized(new BackupRequest(BackupProfile.REPOSITORY_HISTORY, new BackupScope.Workspace("repo-a", "private-a"),
                new BackupTime.History(), GitRepresentation.BARE, SecretsSelection.EXCLUDE));
    }
    private static AuthorizedBackupRequest repositories() {
        return authorized(new BackupRequest(BackupProfile.REPOSITORY_HISTORY, new BackupScope.Repositories(Map.of("repo-a", Set.of("private-a"))),
                new BackupTime.History(), GitRepresentation.BARE, SecretsSelection.EXCLUDE));
    }
    private static AuthorizedBackupRequest authorized(BackupRequest request) {
        return new AuthorizedBackupRequest(request, PrincipalId.create(), "current-policy", Instant.now(), EnumSet.allOf(BackupCapability.class));
    }
    static final class Fixture implements AutoCloseable {
        final GitStandBackupSourceTest.Fixture base = new GitStandBackupSourceTest.Fixture();
        Fixture() throws Exception {
            for (String name : List.of("upstream_repository_id", "fork_point_commit", "last_fetch_commit"))
                base.sql("alter table system_repository add column " + name + " varchar(255)");
            for (String name : List.of("base_commit", "current_commit", "last_fetched_commit", "last_integrated_commit"))
                base.sql("alter table user_workspace add column " + name + " varchar(40)");
            base.sql("create table sync_state(id bigint primary key,workspace_id varchar(255),last_synced_commit_id varchar(40),last_published_commit_id varchar(40))");
            base.sql("create table architecture_commit_index(id bigint primary key,repository_id varchar(255),workspace_id varchar(255),commit_id varchar(40))");
            base.sql("create table context_history_record(id bigint primary key,from_context_id varchar(36),to_context_id varchar(36),from_commit_id varchar(40),to_commit_id varchar(40))");
        }
        WorkspaceGitHistoryReferences source() { return new WorkspaceGitHistoryReferences(base.database, base.repositories); }
        WorkspaceGitHistoryReferences.Capture inspect(AuthorizedBackupRequest auth) throws IOException {
            return source().inspect(auth, auth.request().scope().selectedRepositories(), LIMITS, CHECK);
        }
        String commit(String storage, String content) throws Exception {
            return base.commit(storage, Map.of("architecture.taxdsl", content.getBytes(StandardCharsets.UTF_8)));
        }
        String orphan(String storage, String content) throws Exception {
            try (var repository = new DslGitRepository(new DefaultHibernateRepositoryFactory(base.persistence.factory), storage);
                 var insert = repository.getGitRepository().newObjectInserter()) {
                var tree = new TreeFormatter(); tree.append("architecture.taxdsl", FileMode.REGULAR_FILE,
                        insert.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)));
                var commit = new CommitBuilder(); commit.setTreeId(insert.insert(tree));
                var actor = new PersonIdent("Original Author", "original@example.invalid");
                commit.setAuthor(actor); commit.setCommitter(actor); commit.setMessage(content);
                var id = insert.insert(commit); insert.flush(); return id.name();
            }
        }
        void checkpoint(String id, String branch, String expected, String commit) throws Exception {
            String scope = RepositoryContext.workspace("repo-a", "private-a", branch, "actor").repositoryWorkspaceScopeKey();
            base.sql("insert into editor_checkpoint(id,scope_id,command_id,actor,occurred_at,rationale,fingerprint,from_revision,semantic_revision,expected_commit,dsl,commit_id,completed,commit_created,origin) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, scope, UUID.randomUUID().toString(), "actor", Instant.now().toString(), "checkpoint", "f".repeat(64), 0, 3, expected, "1:RAW CHECKPOINT", commit, true, true, "USER");
        }
        public void close() { base.close(); }
    }
}
