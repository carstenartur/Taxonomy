package com.taxonomy.versioning.service;

import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommitIndexSearchAsyncLifecycleTest {
    private static final RepositoryContext SCOPE = RepositoryContext.workspace("repo-a", "alice-copy", "draft", "alice");

    @Test
    void backgroundRecoveryDoesNotBlockStartupAndHistoryReadsWaitForVerification() throws Exception {
        var rebuild = new BlockingRebuilder(false);
        var lifecycle = asynchronous(rebuild);
        var service = guardedService(lifecycle);
        try (var startup = Executors.newSingleThreadExecutor()) {
            var started = startup.submit(() -> lifecycle.run(new DefaultApplicationArguments()));
            try {
                assertThat(rebuild.entered.await(5, TimeUnit.SECONDS)).isTrue();
                started.get(1, TimeUnit.SECONDS);
                assertThat(lifecycle.getState()).isEqualTo(CommitIndexSearchLifecycle.State.REBUILDING);
                assertUnavailable(service);

                rebuild.release.countDown();
                await().atMost(Duration.ofSeconds(5)).until(lifecycle::isReady);
                assertThat(service.search("", 0, SCOPE)).isEmpty();
            } finally {
                rebuild.release.countDown();
                lifecycle.stop();
            }
        }
    }

    @Test
    void aFailedBackgroundRebuildKeepsHistoryUnavailable() throws Exception {
        var rebuild = new BlockingRebuilder(true);
        var lifecycle = asynchronous(rebuild);
        var service = guardedService(lifecycle);
        try {
            lifecycle.run(new DefaultApplicationArguments());
            assertThat(rebuild.entered.await(5, TimeUnit.SECONDS)).isTrue();
            rebuild.release.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> lifecycle.getState() == CommitIndexSearchLifecycle.State.FAILED);
            assertUnavailable(service);
        } finally {
            rebuild.release.countDown();
            lifecycle.stop();
        }
    }

    @Test
    void aRebuildThatFinishesAfterShutdownCannotReenableHistory() throws Exception {
        var rebuild = new BlockingRebuilder(false);
        var lifecycle = asynchronous(rebuild);
        try {
            lifecycle.run(new DefaultApplicationArguments());
            assertThat(rebuild.entered.await(5, TimeUnit.SECONDS)).isTrue();
            lifecycle.stop();
            rebuild.release.countDown();
            assertThat(rebuild.verified.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(lifecycle.getState()).isEqualTo(CommitIndexSearchLifecycle.State.STOPPED);
            assertThat(lifecycle.isReady()).isFalse();
            assertUnavailable(guardedService(lifecycle));
        } finally {
            rebuild.release.countDown();
            lifecycle.stop();
        }
    }

    private static CommitIndexSearchLifecycle asynchronous(BlockingRebuilder rebuild) {
        var lifecycle = new CommitIndexSearchLifecycle(mock(ArchitectureCommitIndexRepository.class), rebuild);
        ReflectionTestUtils.setField(lifecycle, "asyncInitialization", true);
        return lifecycle;
    }

    private static CommitIndexService guardedService(CommitIndexSearchLifecycle lifecycle) {
        var service = new CommitIndexService(null, null);
        ReflectionTestUtils.setField(service, "searchLifecycle", lifecycle);
        var manager = mock(EntityManager.class);
        when(manager.unwrap(any())).thenThrow(new AssertionError("Search backend was consulted before history verification"));
        ReflectionTestUtils.setField(service, "entityManager", manager);
        return service;
    }

    private static void assertUnavailable(CommitIndexService service) {
        assertThatThrownBy(() -> service.search("medical", 10, SCOPE)).isInstanceOf(SearchUnavailableException.class);
        assertThatThrownBy(() -> service.searchVersioned("medical", 10, SCOPE, "a".repeat(40)))
                .isInstanceOf(SearchUnavailableException.class);
        assertThatThrownBy(() -> service.findByElement("CP-1", SCOPE)).isInstanceOf(SearchUnavailableException.class);
        assertThatThrownBy(() -> service.findByRelation("CP-1 SUPPORTS CP-2", SCOPE)).isInstanceOf(SearchUnavailableException.class);
        verifyNoInteractions(ReflectionTestUtils.getField(service, "entityManager"));
    }

    private static final class BlockingRebuilder extends CommitIndexSearchRebuilder {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch verified = new CountDownLatch(1);
        private final boolean fail;
        private volatile boolean built;

        BlockingRebuilder(boolean fail) { super(null); this.fail = fail; }

        @Override public boolean hasCompleteCoverage(long expectedCount) {
            if (built) verified.countDown();
            return built;
        }

        @Override public void rebuildAll() {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test release");
                    break;
                } catch (InterruptedException cancellation) {
                    // Simulate a storage operation that completes despite cancellation.
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (fail) throw new IllegalStateException("fixture rebuild failure");
            built = true;
        }
    }
}
