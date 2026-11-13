package com.taxonomy;

import com.taxonomy.backup.restore.RestoreSchemaMigration;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceBranchSyncState;
import com.taxonomy.workspace.model.WorkspaceSyncScope;
import com.taxonomy.workspace.repository.WorkspaceSyncStateUpdatesImpl;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent worker sessions against the real schema of each application's database container. */
final class WorkspaceBranchProgressDatabaseContractTest {
    @Test
    void restoredHsqlSchemaUsesTheSameIndependentWorkerContractAsTheThreeApplicationContainers() throws Exception {
        var source = new DriverManagerDataSource("jdbc:hsqldb:mem:progress-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
        var active = new DriverManagerDataSource("jdbc:hsqldb:mem:active-" + UUID.randomUUID(), "sa", "");
        RestoreSchemaMigration.bootstrap(active, source, () -> {});
        verify(source);
    }

    static void verify(DataSource database) throws Exception {
        var id = UUID.randomUUID().toString();
        var scope = new WorkspaceSyncScope("sync-contract", "source-" + id, "workspace-" + id, "main");
        var other = new WorkspaceSyncScope(scope.username(), scope.repositoryId(), scope.workspaceId(), "review");
        var time = Instant.parse("2025-11-12T12:00:00Z");
        try (var first = factory(database); var second = factory(database); var workers = Executors.newFixedThreadPool(2)) {
            var a = new WorkspaceSyncStateUpdatesImpl(SharedEntityManagerCreator.createSharedEntityManager(first), new JpaTransactionManager(first));
            var b = new WorkspaceSyncStateUpdatesImpl(SharedEntityManagerCreator.createSharedEntityManager(second), new JpaTransactionManager(second));
            var parent = new UserWorkspace();
            parent.setWorkspaceId(scope.workspaceId());
            parent.setUsername(scope.username());
            parent.setSourceRepositoryId(scope.repositoryId());
            parent.setCreatedAt(time);
            first.inTransaction(session -> session.persist(parent));
            try {
                var start = new CountDownLatch(1);
                var publish = workers.submit(() -> { start.await(); a.recordSync(scope, "a".repeat(40), time, true); return null; });
                var pull = workers.submit(() -> { start.await(); b.recordSync(scope, "b".repeat(40), time.plusSeconds(1), false); return null; });
                start.countDown();
                publish.get(60, TimeUnit.SECONDS);
                pull.get(60, TimeUnit.SECONDS);
                b.recordLocalChanges(scope, 3, 1, time.plusSeconds(2));
                a.recordSync(scope, "c".repeat(40), time.minusSeconds(1), true);
                var actual = a.readScope(scope);
                assertThat(actual.getLastPublishedCommitId()).isEqualTo("a".repeat(40));
                assertThat(actual.getLastSyncedCommitId()).isEqualTo("b".repeat(40));
                assertThat(actual.getUnpublishedCommitCount()).isEqualTo(3);
                assertThat(actual.getSyncStatus()).isEqualTo("DIVERGED");
                assertThat(b.readScope(other).getLastPublishedCommitId()).isNull();
            } finally {
                first.inTransaction(session -> session.createMutationQuery("delete from UserWorkspace w where w.workspaceId=:id")
                        .setParameter("id", scope.workspaceId()).executeUpdate());
            }
            assertThat(b.readScope(scope).getLastSyncedCommitId()).isNull();
        }
    }

    private static SessionFactory factory(DataSource database) {
        var config = new Configuration().addAnnotatedClass(UserWorkspace.class).addAnnotatedClass(WorkspaceBranchSyncState.class)
                .setProperty("hibernate.hbm2ddl.auto", "validate").setProperty("hibernate.search.enabled", "false");
        config.getProperties().put("hibernate.connection.datasource", database);
        return config.buildSessionFactory();
    }
}
