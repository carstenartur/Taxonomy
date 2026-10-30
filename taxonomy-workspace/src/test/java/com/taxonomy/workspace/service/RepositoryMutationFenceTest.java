package com.taxonomy.workspace.service;

import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMutationFenceFixture;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryMutationFenceTest {
    @Test void ownerFenceNeedsTheCallersTransactionAndAnExistingRepository() throws Exception {
        try (var fixture = fixture()) {
            var fence = RepositoryMutationFenceFixture.repositoryFence(fixture.factory, "repo");
            assertThrows(IllegalTransactionStateException.class, () -> fence.lock("repo"));
            var tx = new TransactionTemplate(new JpaTransactionManager(fixture.factory));
            assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(s -> fence.lock("missing")));
        }
    }

    @Test void realOwnerLockLivesUntilOuterCommitWithoutInvalidatingDetachedGitMetadata() throws Exception {
        try (var fixture = fixture()) {
            var fence = RepositoryMutationFenceFixture.repositoryFence(fixture.factory, "repo");
            var tx = new TransactionTemplate(new JpaTransactionManager(fixture.factory));
            var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.factory);
            var detached = tx.execute(s -> em.createQuery("select r from SystemRepository r where r.repositoryId='repo'", SystemRepository.class).getSingleResult());
            long priorVersion = detached.getVersion();
            var firstLocked = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            var secondLocked = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> tx.executeWithoutResult(s -> {
                    fence.lock("repo"); firstLocked.countDown();
                    try { if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owner transaction not released"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }));
                assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> tx.executeWithoutResult(s -> {
                    secondStarted.countDown(); fence.lock("repo"); secondLocked.countDown();
                }));
                try {
                    assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                    assertFalse(secondLocked.await(300, TimeUnit.MILLISECONDS), "A second writer crossed the owner's uncommitted transaction");
                } finally { releaseFirst.countDown(); }
                first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            } finally { releaseFirst.countDown(); }
            long currentVersion = tx.execute(s -> em.find(SystemRepository.class, detached.getId()).getVersion());
            assertEquals(priorVersion, currentVersion);
            detached.setLastFetchCommit("git-head-after-materialization");
            tx.executeWithoutResult(s -> em.merge(detached));
            assertEquals("git-head-after-materialization", tx.execute(s -> em.find(SystemRepository.class, detached.getId()).getLastFetchCommit()));
        }
    }

    private EditorPersistenceFixture fixture() {
        return new EditorPersistenceFixture("jdbc:hsqldb:mem:repository-fence-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", SystemRepository.class);
    }
}
