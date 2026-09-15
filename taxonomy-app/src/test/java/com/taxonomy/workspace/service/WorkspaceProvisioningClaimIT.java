package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
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
        "qwen.api.key=", "llama.api.key=", "mistral.api.key="})
class WorkspaceProvisioningClaimIT {
    @Autowired private UserWorkspaceRepository repository;
    @Autowired private SystemRepositoryService repositories;
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
            when(destination.commitDsl(anyString(), anyString(), anyString(), anyString())).thenReturn(base);
            when(destination.getHeadCommit("main")).thenReturn(base);
            when(factory.openWorkspaceRepository(id)).thenReturn(destination);
            var system = mock(SystemRepositoryService.class);
            when(system.getPrimaryRepository()).thenReturn(central);
            var first = new WorkspaceManager(selected, 50, system, factory);
            var second = new WorkspaceManager(selected, 50, system, factory);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Callable<Integer> a = () -> provisionStatus(first, id);
                Callable<Integer> b = () -> provisionStatus(second, id);
                var results = executor.invokeAll(List.of(a, b), 20, TimeUnit.SECONDS);
                var statuses = results.stream().map(future -> {
                    try { return future.get(); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }).sorted().toList();
                assertEquals(List.of(200, 409), statuses);
            }
            verify(destination, times(1)).commitDsl(anyString(), anyString(), anyString(), anyString());
            var retained = repository.findByWorkspaceId(id).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, retained.getProvisioningStatus());
            assertEquals(base, retained.getCurrentCommit());
            assertNull(retained.getProvisioningError());
        } finally {
            repository.findByWorkspaceId(id).ifPresent(repository::delete);
        }
    }

    @Test
    void defaultProvisioningDoesNotInheritAReadOnlyCallerTransaction() {
        String id = UUID.randomUUID().toString();
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId(id);
        workspace.setUsername("readonly-provision-owner");
        workspace.setDisplayName(id);
        workspace.setCreatedAt(Instant.now());
        workspace.setDefault(true);
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        repository.saveAndFlush(workspace);
        try {
            var readOnly = new org.springframework.transaction.support.TransactionTemplate(transactions);
            readOnly.setReadOnly(true);
            UserWorkspace result = assertDoesNotThrow(() -> readOnly.execute(status -> {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly());
                var ready = managedWorkspaceManager.provisionDefaultWorkspaceRepository(
                        workspace.getUsername(), id);
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
        } finally {
            repository.findByWorkspaceId(id).ifPresent(repository::delete);
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
