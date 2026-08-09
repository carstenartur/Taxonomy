package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchitectureRepositoryProvisioningServiceTest {

    @Mock
    private SystemRepositoryService systemRepositoryService;

    @Mock
    private DslGitRepositoryFactory repositoryFactory;

    @Mock
    private DslGitRepository repositoryGit;

    @Mock
    private DslGitRepository sourceGit;

    private ArchitectureRepositoryProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new ArchitectureRepositoryProvisioningService(
                systemRepositoryService, repositoryFactory);
    }

    @Test
    void independentRepositoryBecomesActiveOnlyAfterInitialCommit() throws Exception {
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
        InOrder order = inOrder(systemRepositoryService, repositoryFactory, repositoryGit);
        order.verify(systemRepositoryService).createCentralRepository(
                "Customer A", "customer-a", "Architecture", RepositoryVisibility.PRIVATE,
                "alice", "main");
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

        verify(repositoryFactory).deleteCentralRepository("repo-a");
        verify(systemRepositoryService).markProvisioningFailed("repo-a", "ref update failed");
        verify(systemRepositoryService, never()).markProvisioningReady("repo-a");
    }

    @Test
    void forkUsesSelectedSourceAndActivatesAfterForkCommit() throws Exception {
        SystemRepository source = repository(
                "source", "central-source", "reference", RepositoryLifecycleState.ACTIVE);
        SystemRepository provisioningFork = repository(
                "fork", "central-fork", "reference-fork", RepositoryLifecycleState.PROVISIONING);
        SystemRepository activeFork = repository(
                "fork", "central-fork", "reference-fork", RepositoryLifecycleState.ACTIVE);
        when(systemRepositoryService.getRepository("source")).thenReturn(source);
        when(repositoryFactory.getCentralRepository("source")).thenReturn(sourceGit);
        when(sourceGit.getDslAtHead("main"))
                .thenReturn("meta { language: \"taxdsl\"; }\n");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-commit");
        when(systemRepositoryService.createForkMetadata(
                "source", "main", "source-commit", "Fork", "reference-fork", null,
                RepositoryVisibility.PRIVATE, "alice"))
                .thenReturn(provisioningFork);
        when(repositoryFactory.createCentralRepository("fork", "central-fork"))
                .thenReturn(repositoryGit);
        when(systemRepositoryService.markProvisioningReady("fork")).thenReturn(activeFork);

        SystemRepository result = service.createFork(
                "source", "main", "Fork", "reference-fork", null,
                RepositoryVisibility.PRIVATE, "alice");

        assertSame(activeFork, result);
        verify(repositoryGit).commitDsl(
                "main", "meta { language: \"taxdsl\"; }\n", "alice",
                "Fork from reference/main");
        verify(systemRepositoryService).markProvisioningReady("fork");
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
