package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Separate service instances share only the real JPA repository, never a Java monitor. */
@SpringBootTest
@TestPropertySource(properties = {"gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key=", "taxonomy.git.bootstrap=false"})
class WorkspaceProvisioningClaimIT {
    @Autowired private UserWorkspaceRepository repository;
    @Autowired private SystemRepositoryService repositories;
    @Autowired private SystemRepositoryRepository centralRows;
    @Autowired private DslGitRepositoryFactory gitRepositories;
    @Autowired private WorkspaceManager managedWorkspaceManager;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void twoInstancesCannotBothClaimTheSameUnprovisionedRow() throws Exception {
        String id = UUID.randomUUID().toString();
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId(id);
        workspace.setUsername("claim-owner");
        workspace.setDisplayName(id);
        workspace.setCreatedAt(Instant.now());
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        repository.saveAndFlush(workspace);
        try {
            var selected = mock(UserWorkspaceRepository.class, delegatesTo(repository));
            var barrier = new CyclicBarrier(2);
            doAnswer(call -> {
                var row = repository.findByWorkspaceId(id);
                // Both independent instances observe NOT_PROVISIONED before either may claim it.
                assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED, row.orElseThrow().getProvisioningStatus());
                barrier.await(10, TimeUnit.SECONDS);
                return row;
            }).when(selected).findByWorkspaceId(id);
            var factory = mock(DslGitRepositoryFactory.class);
            var central = repositories.getPrimaryRepository();
            var source = new DslGitRepository();
            String base = source.commitDsl(central.getDefaultBranch(), "meta { language: \"taxdsl\"; }", "system", "Initial source");
            when(factory.getSystemRepository()).thenReturn(source);
            var destination = mock(DslGitRepository.class);
            when(destination.commitDslIfHeadMatches(anyString(), isNull(), anyString(), anyString(), anyString())).thenReturn(base);
            when(destination.getHeadCommit("main")).thenReturn(null);
            when(factory.openWorkspaceRepository(id)).thenReturn(destination);
            var system = mock(SystemRepositoryService.class);
            when(system.getPrimaryRepository()).thenReturn(central);
            var first = new WorkspaceManager(selected, 50, system, factory);
            var second = new WorkspaceManager(selected, 50, system, factory);
            var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
            try {
                Callable<Integer> a = () -> provisionStatus(first, id);
                Callable<Integer> b = () -> provisionStatus(second, id);
                var results = executor.invokeAll(List.of(a, b), 20, TimeUnit.SECONDS);
                var statuses = results.stream().map(future -> {
                    try { return future.get(); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }).sorted().toList();
                assertEquals(List.of(200, 409), statuses);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                        "Provisioning test workers did not terminate");
            }
            verify(destination, times(1)).commitDslIfHeadMatches(anyString(), isNull(), anyString(), anyString(), anyString());
            verify(destination, never()).commitDsl(anyString(), anyString(), anyString(), anyString());
            var retained = repository.findByWorkspaceId(id).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, retained.getProvisioningStatus());
            assertEquals(base, retained.getCurrentCommit());
            assertNull(retained.getProvisioningError());
        } finally {
            repository.findByWorkspaceId(id).ifPresent(repository::delete);
        }
    }

    @Test
    void defaultProvisioningDoesNotInheritAReadOnlyCallerTransaction() throws Exception {
        String id = UUID.randomUUID().toString();
        String username = "readonly-provision-owner";
        String sourceDsl = "meta { language: \"taxdsl\"; namespace: \"readonly-provision\"; }\n";
        var source = repositories.createCentralRepository("Read-only provisioning source",
                "readonly-provision-" + UUID.randomUUID(), null,
                RepositoryVisibility.PRIVATE, username, "draft");
        try {
            // Commit a real, uniquely named source before entering the caller's read-only
            // transaction. The JVM-wide primary bootstrap is deliberately not a fixture.
            var sourceGit = gitRepositories.createCentralRepository(
                    source.getRepositoryId(), source.getStorageRepositoryName());
            String sourceCommit = sourceGit.commitDsl("draft", sourceDsl, username, "Provisioning test source");
            repositories.markProvisioningReady(source.getRepositoryId());
            assertEquals(sourceDsl, sourceGit.getDslAtCommit(sourceCommit));
            var workspace = new UserWorkspace();
            workspace.setWorkspaceId(id);
            workspace.setUsername(username);
            workspace.setDisplayName(id);
            workspace.setCreatedAt(Instant.now());
            workspace.setDefault(true);
            workspace.setSourceRepositoryId(source.getRepositoryId());
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
            repository.saveAndFlush(workspace);
            var readOnly = new org.springframework.transaction.support.TransactionTemplate(transactions);
            readOnly.setReadOnly(true);
            UserWorkspace result = assertDoesNotThrow(() -> readOnly.execute(status -> {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly());
                var ready = managedWorkspaceManager.provisionDefaultWorkspaceRepository(username, id);
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly());
                return ready;
            }));
            assertNotNull(result);
            assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
            var retained = repository.findByWorkspaceId(id).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, retained.getProvisioningStatus());
            assertNotNull(retained.getCurrentCommit());
            assertEquals("draft", retained.getCurrentBranch());
            assertEquals(source.getRepositoryId(), retained.getSourceRepositoryId());
            assertEquals(sourceCommit, retained.getBaseCommit());
            assertEquals(sourceDsl, gitRepositories.openWorkspaceRepository(id)
                    .getDslAtCommit(retained.getCurrentCommit()));
        } finally {
            try {
                gitRepositories.deleteWorkspaceRepository(id);
                repository.findByWorkspaceId(id).ifPresent(repository::delete);
            } finally {
                gitRepositories.deleteCentralRepository(source.getRepositoryId());
                centralRows.findByRepositoryId(source.getRepositoryId()).ifPresent(centralRows::delete);
            }
        }
    }

    private static int provisionStatus(WorkspaceManager manager, String id) {
        try {
            manager.provisionWorkspaceRepository("claim-owner", id);
            return 200;
        } catch (ResponseStatusException failure) {
            return failure.getStatusCode().value();
        }
    }
}
