package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryWorkspaceServiceTest {

    @Mock
    private UserWorkspaceRepository workspaceRepository;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    @Mock
    private DslGitRepositoryFactory repositoryFactory;

    @Mock
    private DslGitRepository sourceGit;

    @Mock
    private DslGitRepository workspaceGit;

    private RepositoryWorkspaceService service;
    private SystemRepository source;

    @BeforeEach
    void setUp() {
        service = new RepositoryWorkspaceService(
                workspaceRepository, systemRepositoryService, repositoryFactory);
        source = new SystemRepository();
        source.setRepositoryId("source-repository");
        source.setSlug("reference");
        source.setDefaultBranch("main");
        source.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        source.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
    }

    @Test
    void workingCopyUsesOneSeedCommitAndCreatesTrackingBase() throws Exception {
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        List<WorkspaceProvisioningStatus> persistedStates = new ArrayList<>();
        when(workspaceRepository.save(any(UserWorkspace.class))).thenAnswer(invocation -> {
            UserWorkspace workspace = invocation.getArgument(0);
            persistedStates.add(workspace.getProvisioningStatus());
            return workspace;
        });
        when(repositoryFactory.getCentralRepository("source-repository")).thenReturn(sourceGit);
        when(sourceGit.getDslAtHead("main"))
                .thenReturn("meta { language: \"taxdsl\"; }\n");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-commit");
        when(repositoryFactory.createWorkspaceRepository(
                anyString(), eq("source-repository"), eq("main")))
                .thenReturn(workspaceGit);
        when(workspaceGit.getHeadCommit("draft")).thenReturn("workspace-seed-commit");
        when(workspaceGit.createBranch("sync-base", "draft"))
                .thenReturn("workspace-seed-commit");

        UserWorkspace workspace = service.createWorkingCopy(
                "alice", "source-repository", "main", "Alice workspace", "Description");

        assertEquals(List.of(
                WorkspaceProvisioningStatus.PROVISIONING,
                WorkspaceProvisioningStatus.READY), persistedStates);
        assertEquals("draft", workspace.getCurrentBranch());
        assertEquals("main", workspace.getSourceBranch());
        assertEquals("source-commit", workspace.getBaseCommit());
        assertEquals("workspace-seed-commit", workspace.getCurrentCommit());
        assertEquals("source-commit", workspace.getLastFetchedCommit());
        assertEquals("source-commit", workspace.getLastIntegratedCommit());
        verify(workspaceGit).createBranch("sync-base", "draft");
        verify(workspaceGit, never()).commitDsl(
                anyString(), anyString(), anyString(), anyString());
        verify(repositoryFactory, never()).deleteWorkspaceRepository(anyString());
    }

    @Test
    void failedProvisioningPersistsFailedStateAndCleansPartialStorage() throws Exception {
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        List<WorkspaceProvisioningStatus> persistedStates = new ArrayList<>();
        AtomicReference<String> persistedError = new AtomicReference<>();
        when(workspaceRepository.save(any(UserWorkspace.class))).thenAnswer(invocation -> {
            UserWorkspace workspace = invocation.getArgument(0);
            persistedStates.add(workspace.getProvisioningStatus());
            persistedError.set(workspace.getProvisioningError());
            return workspace;
        });
        when(repositoryFactory.getCentralRepository("source-repository")).thenReturn(sourceGit);
        when(sourceGit.getDslAtHead("main"))
                .thenReturn("meta { language: \"taxdsl\"; }\n");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-commit");
        when(repositoryFactory.createWorkspaceRepository(
                anyString(), eq("source-repository"), eq("main")))
                .thenReturn(workspaceGit);
        when(workspaceGit.getHeadCommit("draft")).thenReturn("workspace-seed-commit");
        when(workspaceGit.createBranch("sync-base", "draft"))
                .thenThrow(new IOException("tracking ref failed"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createWorkingCopy(
                        "alice", "source-repository", "main", "Alice workspace", null));

        assertTrue(failure.getMessage().contains("source-repository"));
        assertEquals(List.of(
                WorkspaceProvisioningStatus.PROVISIONING,
                WorkspaceProvisioningStatus.FAILED), persistedStates);
        assertEquals("tracking ref failed", persistedError.get());
        verify(repositoryFactory).deleteWorkspaceRepository(anyString());
    }

    @Test
    void provisioningMethodHasNoOuterTransactionThatCouldRollbackFailedMetadata()
            throws NoSuchMethodException {
        Method method = RepositoryWorkspaceService.class.getMethod(
                "createWorkingCopy",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class);

        assertNull(method.getAnnotation(Transactional.class));
    }
}
