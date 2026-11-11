package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchitectureRepositoryProvisioningServiceTest {

    @Mock
    private SystemRepositoryService systemRepositoryService;

    @Mock
    private RepositoryMembershipService membershipService;

    @Mock
    private DslGitRepositoryFactory repositoryFactory;

    @Mock
    private DslGitRepository repositoryGit;

    private ArchitectureRepositoryProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new ArchitectureRepositoryProvisioningService(
                systemRepositoryService, membershipService, repositoryFactory, namespace -> "meta { language: \"taxdsl\"; }\n");
    }

    @Test
    void independentRepositoryBecomesActiveOnlyAfterOwnerAndInitialCommit() throws Exception {
        SystemRepository provisioning = repository(
                "repo-a", "central-repo-a", "customer-a", RepositoryLifecycleState.PROVISIONING);
        SystemRepository active = repository(
                "repo-a", "central-repo-a", "customer-a", RepositoryLifecycleState.ACTIVE);
        when(systemRepositoryService.createCentralRepository(
                "Customer A", "customer-a", "Architecture", RepositoryVisibility.PRIVATE,
                "alice", "main"))
                .thenReturn(provisioning);
        when(repositoryFactory.createCentralRepository("repo-a", "central-repo-a"))
                .thenReturn(repositoryGit);
        when(systemRepositoryService.markProvisioningReady("repo-a")).thenReturn(active);

        SystemRepository result = service.createRepository(
                "Customer A", "customer-a", "Architecture",
                RepositoryVisibility.PRIVATE, "alice", "main");

        assertSame(active, result);
        InOrder order = inOrder(
                systemRepositoryService, membershipService, repositoryFactory, repositoryGit);
        order.verify(systemRepositoryService).createCentralRepository(
                "Customer A", "customer-a", "Architecture", RepositoryVisibility.PRIVATE,
                "alice", "main");
        order.verify(membershipService).assignOwner("repo-a", "alice");
        order.verify(repositoryFactory).createCentralRepository("repo-a", "central-repo-a");
        order.verify(repositoryGit).commitDsl(
                "main", "meta { language: \"taxdsl\"; }\n", "alice",
                "Initialize architecture repository");
        order.verify(systemRepositoryService).markProvisioningReady("repo-a");
        verify(systemRepositoryService, never()).markProvisioningFailed(
                eq("repo-a"), anyString());
        verify(repositoryFactory, never()).deleteCentralRepository("repo-a");
    }

    @Test
    void failedInitialCommitCleansStorageAndPersistsFailedState() throws Exception {
        SystemRepository provisioning = repository(
                "repo-a", "central-repo-a", "customer-a", RepositoryLifecycleState.PROVISIONING);
        when(systemRepositoryService.createCentralRepository(
                "Customer A", "customer-a", null, RepositoryVisibility.PRIVATE,
                "alice", "main"))
                .thenReturn(provisioning);
        when(repositoryFactory.createCentralRepository("repo-a", "central-repo-a"))
                .thenReturn(repositoryGit);
        when(repositoryGit.commitDsl(
                "main", "meta { language: \"taxdsl\"; }\n", "alice",
                "Initialize architecture repository"))
                .thenThrow(new IOException("ref update failed"));

        assertThrows(IllegalStateException.class,
                () -> service.createRepository(
                        "Customer A", "customer-a", null,
                        RepositoryVisibility.PRIVATE, "alice", "main"));

        verify(membershipService).assignOwner("repo-a", "alice");
        verify(repositoryFactory).deleteCentralRepository("repo-a");
        verify(systemRepositoryService).markProvisioningFailed("repo-a", "ref update failed");
        verify(systemRepositoryService, never()).markProvisioningReady("repo-a");
    }

    @Test
    void failedOwnerAssignmentPersistsFailedStateWithoutAllocatingStorage() {
        SystemRepository provisioning = repository(
                "repo-a", "central-repo-a", "customer-a", RepositoryLifecycleState.PROVISIONING);
        when(systemRepositoryService.createCentralRepository(
                "Customer A", "customer-a", null, RepositoryVisibility.PRIVATE,
                "alice", "main"))
                .thenReturn(provisioning);
        when(membershipService.assignOwner("repo-a", "alice"))
                .thenThrow(new IllegalStateException("membership write failed"));

        assertThrows(IllegalStateException.class,
                () -> service.createRepository(
                        "Customer A", "customer-a", null,
                        RepositoryVisibility.PRIVATE, "alice", "main"));

        verify(systemRepositoryService).markProvisioningFailed(
                "repo-a", "membership write failed");
        verify(repositoryFactory, never()).createCentralRepository(anyString(), anyString());
        verify(repositoryFactory, never()).deleteCentralRepository(anyString());
    }

    @Test
    void forkUsesSelectedSourceAndActivatesAtItsOriginalCommit() throws Exception {
        SystemRepository source = repository(
                "source", "central-source", "reference", RepositoryLifecycleState.ACTIVE);
        SystemRepository provisioningFork = repository(
                "fork", "central-fork", "reference-fork", RepositoryLifecycleState.PROVISIONING);
        SystemRepository activeFork = repository(
                "fork", "central-fork", "reference-fork", RepositoryLifecycleState.ACTIVE);
        when(systemRepositoryService.getRepository("source")).thenReturn(source);
        when(membershipService.canContribute(source, "alice")).thenReturn(true);
        doAnswer(call -> {
            Consumer<SystemRepository> decision = call.getArgument(1);
            decision.accept(source);
            return null;
        }).when(systemRepositoryService).readCurrentAuthorization(eq("source"), any());
        try (var sourceGit = new DslGitRepository(); var target = new DslGitRepository()) {
            String captured = sourceGit.commitDsl("main", "architecture DSL", "author", "Source history");
            provisioningFork.setUpstreamRepositoryId("source");
            provisioningFork.setUpstreamBranch("main");
            provisioningFork.setForkPointCommit(captured);
            when(repositoryFactory.getCentralRepository("source")).thenReturn(sourceGit);
            when(systemRepositoryService.createForkMetadata(
                    "source", "main", captured, "Fork", "reference-fork", null,
                    RepositoryVisibility.PRIVATE, "alice")).thenReturn(provisioningFork);
            when(repositoryFactory.createCentralRepository("fork", "central-fork")).thenReturn(target);
            when(systemRepositoryService.markProvisioningReady("fork")).thenAnswer(call -> {
                assertEquals(captured, target.getHeadCommit("main"));
                return activeFork;
            });

            SystemRepository result = service.createFork(
                    "source", "main", "Fork", "reference-fork", null,
                    RepositoryVisibility.PRIVATE, "alice");

            assertSame(activeFork, result);
            InOrder order = inOrder(membershipService, repositoryFactory, systemRepositoryService);
            order.verify(membershipService).assignOwner("fork", "alice");
            order.verify(repositoryFactory).createCentralRepository("fork", "central-fork");
            order.verify(systemRepositoryService).markProvisioningReady("fork");
        }
    }

    @Test
    void inactiveSourceIsRejectedBeforeGitAccess() {
        SystemRepository failedSource = repository(
                "source", "central-source", "reference", RepositoryLifecycleState.FAILED);
        when(systemRepositoryService.getRepository("source")).thenReturn(failedSource);

        assertThrows(IllegalStateException.class,
                () -> service.createFork(
                        "source", "main", "Fork", "reference-fork", null,
                        RepositoryVisibility.PRIVATE, "alice"));

        verify(repositoryFactory, never()).getCentralRepository(anyString());
        verify(membershipService, never()).assignOwner(anyString(), anyString());
    }

    private static SystemRepository repository(
            String repositoryId,
            String storageName,
            String slug,
            RepositoryLifecycleState lifecycleState) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        repository.setStorageRepositoryName(storageName);
        repository.setSlug(slug);
        repository.setDefaultBranch("main");
        repository.setLifecycleState(lifecycleState);
        repository.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        return repository;
    }
}
