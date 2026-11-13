package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceBranchSyncState;
import com.taxonomy.workspace.model.WorkspaceSyncScope;
import com.taxonomy.workspace.repository.WorkspaceSyncStateUpdatesImpl;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceBranchSyncStateTest {
    private static final Instant AT = Instant.parse("2025-06-11T10:30:00Z");
    private static final WorkspaceSyncScope FIRST = new WorkspaceSyncScope("alice", "repo-one", "work-one", "draft");

    @Test
    void restartingWithAnOldUsernameWideRecordRetainsItsEvidenceAndNeverAttributesItToANewBranch(@TempDir Path directory) {
        String db = "jdbc:hsqldb:file:" + directory.resolve("progress") + ";shutdown=true;hsqldb.tx=mvcc";
        try (var old = application(db, false)) {
            old.inTransaction(session -> {
                var state = new SyncState();
                state.setUsername("alice");
                state.setWorkspaceId("work-one");
                state.setLastPublishedCommitId("old-published-without-branch");
                state.setCreatedAt(AT);
                session.persist(state);
            });
        }
        var others = List.of(new WorkspaceSyncScope("alice", "repo-one", "work-one", "review"),
                new WorkspaceSyncScope("alice", "repo-one", "work-two", "draft"),
                new WorkspaceSyncScope("alice", "repo-two", "work-one", "draft"),
                new WorkspaceSyncScope("bob", "repo-one", "work-one", "draft"),
                new WorkspaceSyncScope("alice", "repo-one", null, "draft"));
        try (var current = application(db, true)) {
            createWorkspace(current, FIRST);
            var observations = handle(current);
            assertThat(observations.readScope(FIRST).getLastPublishedCommitId()).isNull();
            observations.recordSync(FIRST, "private-commit", AT.plusSeconds(1), true);
            for (var other : others) assertThat(observations.readScope(other).getLastPublishedCommitId()).isNull();
        }
        try (var reloaded = application(db, true)) {
            assertThat(handle(reloaded).readScope(FIRST).getLastPublishedCommitId()).isEqualTo("private-commit");
            String historical = reloaded.fromTransaction(session -> session.createSelectionQuery(
                    "select s.lastPublishedCommitId from SyncState s where s.username='alice'", String.class).getSingleResult());
            assertThat(historical).as("historical branchless evidence").isEqualTo("old-published-without-branch");
            long counts = reloaded.fromTransaction(session -> session.createSelectionQuery(
                    "select count(s) from WorkspaceBranchSyncState s", Long.class).getSingleResult());
            assertThat(counts).as("reads of unknown scopes allocate nothing").isOne();
        }
    }

    @Test
    void concurrentFirstWritersAndAnOlderAcknowledgementPreserveBothSourcesAndNewerCounts(@TempDir Path directory)
            throws Exception {
        String db = "jdbc:hsqldb:file:" + directory.resolve("concurrent") + ";shutdown=true;hsqldb.tx=mvcc";
        try (var first = application(db, true); var second = application(db, true);
             var workers = Executors.newFixedThreadPool(2)) {
            createWorkspace(first, FIRST);
            var a = handle(first);
            var b = handle(second);
            var start = new CountDownLatch(1);
            var oldPublish = workers.submit(() -> { start.await(); a.recordSync(FIRST, "publish", AT, true); return null; });
            var newPull = workers.submit(() -> { start.await(); b.recordSync(FIRST, "pull", AT.plusSeconds(5), false); return null; });
            start.countDown();
            oldPublish.get(30, TimeUnit.SECONDS);
            newPull.get(30, TimeUnit.SECONDS);
            b.recordLocalChanges(FIRST, 4, 2, AT.plusSeconds(8));
            a.recordSync(FIRST, "late-publish", AT.minusSeconds(1), true);
            var actual = a.readScope(FIRST);
            assertThat(actual.getLastSyncedCommitId()).isEqualTo("pull");
            assertThat(actual.getLastPublishedCommitId()).isEqualTo("publish");
            assertThat(actual.getSyncStatus()).isEqualTo("DIVERGED");
            assertThat(actual.getUnpublishedCommitCount()).isEqualTo(4);
        }
    }

    @Test
    void latePullBookkeepingRetainsTheOriginalBusinessTimeAndANewerObservedDivergence(@TempDir Path directory) {
        String db="jdbc:hsqldb:file:"+directory.resolve("pulled")+";shutdown=true;hsqldb.tx=mvcc";
        try (var factory=application(db,true)) {
            createWorkspace(factory,FIRST);
            var progress=handle(factory);
            progress.recordSync(FIRST,"last-publication",AT.minusSeconds(3),true);
            progress.recordLocalChanges(FIRST,2,1,AT.plusSeconds(2));
            progress.recordPull(FIRST,"original-source",AT,AT.plusSeconds(1),AT.plusSeconds(30));
            var state=progress.readScope(FIRST);
            assertThat(state.getLastSyncedCommitId()).isEqualTo("original-source");
            assertThat(state.getLastSyncTimestamp()).isEqualTo(AT);
            assertThat(state.getLastPublishedCommitId()).isEqualTo("last-publication");
            assertThat(state.getSyncStatus()).isEqualTo("DIVERGED");
            assertThat(state.getUnpublishedCommitCount()).isEqualTo(2);
        }
    }

    @Test
    void deletedWorkingCopyCannotBeRecreatedByALateObserverAndLeavesCentralProgressAlone(@TempDir Path directory) {
        String db = "jdbc:hsqldb:file:" + directory.resolve("delete") + ";shutdown=true;hsqldb.tx=mvcc";
        var central = new WorkspaceSyncScope(FIRST.username(), FIRST.repositoryId(), null, FIRST.branch());
        try (var factory = application(db, true)) {
            createWorkspace(factory, FIRST);
            var observations = handle(factory);
            observations.recordSync(FIRST, "private", AT, true);
            observations.recordSync(central, "central", AT, true);
            factory.inTransaction(session -> session.createMutationQuery("delete from UserWorkspace where workspaceId=:id")
                    .setParameter("id", FIRST.workspaceId()).executeUpdate());
            assertThat(observations.readScope(FIRST).getLastPublishedCommitId()).isNull();
            assertThatThrownBy(() -> observations.recordSync(FIRST, "delayed", AT.plusSeconds(1), true))
                    .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                    .hasCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
            assertThat(observations.readScope(central).getLastPublishedCommitId()).isEqualTo("central");
        }
    }

    private static void createWorkspace(SessionFactory factory, WorkspaceSyncScope scope) {
        factory.inTransaction(session -> {
            var owner = new UserWorkspace();
            owner.setWorkspaceId(scope.workspaceId());
            owner.setUsername(scope.username());
            owner.setSourceRepositoryId(scope.repositoryId());
            owner.setCreatedAt(AT);
            session.persist(owner);
        });
    }

    private static WorkspaceSyncStateUpdatesImpl handle(SessionFactory factory) {
        return new WorkspaceSyncStateUpdatesImpl(SharedEntityManagerCreator.createSharedEntityManager(factory),
                new JpaTransactionManager(factory));
    }

    private static SessionFactory application(String url, boolean current) {
        var configuration = new Configuration().addAnnotatedClass(SyncState.class).addAnnotatedClass(UserWorkspace.class)
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "SA")
                .setProperty("hibernate.connection.password", "").setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.search.enabled", "false");
        if (current) configuration.addAnnotatedClass(WorkspaceBranchSyncState.class);
        return configuration.buildSessionFactory();
    }
}
