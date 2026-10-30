package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Protects publication from overlapping a transfer/checkpoint on the same live JGit handle. */
class DslGitRepositoryHistoryMonitorTest {
    enum Publication { FAST_FORWARD, MERGE }

    @ParameterizedTest
    @EnumSource(Publication.class)
    void publicationThroughAnotherFacadeWaitsForTheSharedRepositoryMonitor(Publication publication) throws Exception {
        try (var owner = new DslGitRepository();
             var publisher = new DslGitRepository(owner.getGitRepository())) {
            String base = owner.commitDsl("main", "# accepted", "alice", "Accepted");
            owner.createBranch("incoming", "main");
            String incoming = owner.commitDsl("incoming", "# incoming", "bob", "Incoming");
            var result = new FutureTask<>(() -> publish(publication, publisher, base, incoming));
            Thread worker = daemon(result);
            try {
                // A closure transfer or checkpoint holds this same object, not either facade.
                synchronized (owner.getGitRepository()) {
                    worker.start();
                    awaitRepositoryMonitor(worker, result, owner.getGitRepository());
                    assertThat(owner.getHeadCommit("main")).isEqualTo(base);
                }
                String committed = result.get(5, TimeUnit.SECONDS);
                assertThat(owner.getHeadCommit("main")).isEqualTo(committed);
                assertThat(owner.getDslAtHead("main"))
                        .isEqualTo(publication == Publication.FAST_FORWARD ? "# incoming" : "# reviewed");
                assertThat(owner.isAncestor(incoming, committed)).isTrue();
            } finally {
                cancel(worker, result);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Publication.class)
    void waitingForTheMonitorDoesNotAdoptAConcurrentHead(Publication publication) throws Exception {
        try (var owner = new DslGitRepository();
             var publisher = new DslGitRepository(owner.getGitRepository())) {
            String base = owner.commitDsl("main", "# accepted", "alice", "Accepted");
            owner.createBranch("incoming", "main");
            String incoming = owner.commitDsl("incoming", "# incoming", "bob", "Incoming");
            var result = new FutureTask<>(() -> publish(publication, publisher, base, incoming));
            Thread worker = daemon(result);
            try {
                String winner;
                synchronized (owner.getGitRepository()) {
                    worker.start();
                    awaitRepositoryMonitor(worker, result, owner.getGitRepository());
                    winner = owner.commitDsl("main", "# concurrent", "carol", "Concurrent checkpoint");
                }
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(BranchHeadConflictException.class);
                assertThat(owner.getHeadCommit("main")).isEqualTo(winner);
                assertThat(owner.getDslAtHead("main")).isEqualTo("# concurrent");
            } finally {
                cancel(worker, result);
            }
        }
    }

    private static String publish(Publication publication, DslGitRepository repository,
                                  String expected, String incoming) throws Exception {
        return publication == Publication.FAST_FORWARD
                ? repository.fastForwardIfHeadMatches("main", expected, incoming, "alice", "Adopt history")
                : repository.commitMergeDslIfHeadMatches("main", expected, incoming,
                        "# reviewed", "alice", "Publish reviewed history", null);
    }

    private static Thread daemon(FutureTask<String> result) {
        return Thread.ofPlatform().daemon().name("history-publication-monitor").unstarted(result);
    }

    private static void awaitRepositoryMonitor(Thread worker, FutureTask<?> result, Repository repository) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var threads = ManagementFactory.getThreadMXBean();
        while (System.nanoTime() < deadline) {
            var state = threads.getThreadInfo(worker.threadId());
            if (state != null && state.getThreadState() == Thread.State.BLOCKED
                    && state.getLockOwnerId() == Thread.currentThread().threadId()
                    && state.getLockInfo() != null
                    && state.getLockInfo().getIdentityHashCode() == System.identityHashCode(repository)) {
                return;
            }
            assertThat(result.isDone())
                    .as("publication must wait for the transfer/checkpoint's shared Repository monitor")
                    .isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Publication did not wait on the shared Repository monitor within five seconds");
    }

    private static void cancel(Thread worker, FutureTask<?> result) throws InterruptedException {
        result.cancel(true);
        worker.interrupt();
        // A broken lock order must fail this test without keeping the test JVM alive.
        worker.join(200);
    }
}
