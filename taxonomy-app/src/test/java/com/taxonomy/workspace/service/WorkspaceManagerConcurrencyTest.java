package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression coverage for concurrent first access to one user's workspace. */
class WorkspaceManagerConcurrencyTest {

    @Test
    void concurrentFirstAccessCreatesOnlyOnePersistentWorkspace() throws Exception {
        UserWorkspaceRepository workspaceRepository = mock(UserWorkspaceRepository.class);
        SystemRepositoryService systemRepositoryService = mock(SystemRepositoryService.class);
        DslGitRepository gitRepository = mock(DslGitRepository.class);
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicInteger saveCount = new AtomicInteger();

        when(workspaceRepository.existsByUsername("alice"))
                .thenAnswer(invocation -> persisted.get());
        when(workspaceRepository.findByUsernameAndIsDefaultTrue("alice"))
                .thenReturn(Optional.empty());
        when(workspaceRepository.findByUsernameAndSharedFalse("alice"))
                .thenReturn(Optional.empty());
        when(workspaceRepository.save(any(UserWorkspace.class)))
                .thenAnswer(invocation -> {
                    saveCount.incrementAndGet();
                    Thread.sleep(75);
                    persisted.set(true);
                    return invocation.getArgument(0);
                });

        WorkspaceManager manager = new WorkspaceManager(
                workspaceRepository, 50, systemRepositoryService, gitRepository);
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<UserWorkspaceState>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return manager.getOrCreateWorkspace("alice");
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            UserWorkspaceState expected = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<UserWorkspaceState> future : futures) {
                assertSame(expected, future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, saveCount.get(),
                    "concurrent first access must persist one default workspace");
        } finally {
            executor.shutdownNow();
        }
    }
}
