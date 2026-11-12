package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
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
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private RepositoryMembershipService memberships;

    private RepositoryWorkspaceService service;
    private SystemRepository source;

    // Snapshot tests exercise the real object transfer. These tests control each persistence boundary.
    private MockedStatic<GitHistoryTransfer> transfer;

    @AfterEach
    void closeTransfer() { transfer.close(); }

    @BeforeEach
    void setUp() {
        transfer = org.mockito.Mockito.mockStatic(GitHistoryTransfer.class);
        service = new RepositoryWorkspaceService(
                workspaceRepository, systemRepositoryService, repositoryFactory, memberships);
        source = new SystemRepository();
        source.setRepositoryId("source-repository");
        source.setSlug("reference");
        org.mockito.Mockito.lenient().when(memberships.canContribute(source, "alice")).thenReturn(true);
        source.setDefaultBranch("main");
        source.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        source.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
    }

    @Test
    void workingCopyPublishesTheCapturedCommitAndCreatesTrackingBase() throws Exception {
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        List<WorkspaceProvisioningStatus> persistedStates = new ArrayList<>();
        when(workspaceRepository.save(any(UserWorkspace.class))).thenAnswer(invocation -> {
            UserWorkspace workspace = invocation.getArgument(0);
            persistedStates.add(workspace.getProvisioningStatus());
            return workspace;
        });
        when(repositoryFactory.getCentralRepository("source-repository")).thenReturn(sourceGit);
        when(sourceGit.getDslAtCommit("source-commit", GitHistoryTransfer.MAX_OBJECT_BYTES))
                .thenReturn("meta { language: \"taxdsl\"; }\n");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-commit");
        when(repositoryFactory.openWorkspaceRepository(anyString()))
                .thenReturn(workspaceGit);
        when(workspaceGit.createBranchAtCommit(eq("sync-base"), anyString()))
                .thenReturn("source-commit");

        UserWorkspace workspace = service.createWorkingCopy(
                "alice", "source-repository", "main", "Alice workspace", "Description");

        assertEquals(List.of(
                WorkspaceProvisioningStatus.PROVISIONING,
                WorkspaceProvisioningStatus.READY), persistedStates);
        assertEquals("draft", workspace.getCurrentBranch());
        assertEquals("main", workspace.getSourceBranch());
        assertEquals("source-commit", workspace.getBaseCommit());
        assertEquals("source-commit", workspace.getCurrentCommit());
        assertEquals("source-commit", workspace.getLastFetchedCommit());
        assertEquals("source-commit", workspace.getLastIntegratedCommit());
        verify(workspaceGit).createBranchAtCommit("sync-base", "source-commit");
        transfer.verify(() -> GitHistoryTransfer.copyCommitClosure(sourceGit, workspaceGit, "source-commit", List.of()));
        verify(workspaceGit).fastForwardIfHeadMatches(eq("draft"), isNull(), eq("source-commit"),
                eq("alice"), anyString());
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
        when(sourceGit.getDslAtCommit("source-commit", GitHistoryTransfer.MAX_OBJECT_BYTES))
                .thenReturn("meta { language: \"taxdsl\"; }\n");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-commit");
        when(repositoryFactory.openWorkspaceRepository(anyString()))
                .thenReturn(workspaceGit);
        when(workspaceGit.createBranchAtCommit(eq("sync-base"), anyString()))
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
    void failedFactorySeedStillCleansAttemptedStorage() throws Exception {
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
        when(sourceGit.getDslAtCommit("source-commit", GitHistoryTransfer.MAX_OBJECT_BYTES))
                .thenReturn("meta { language: \"taxdsl\"; }\n");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-commit");
        when(repositoryFactory.openWorkspaceRepository(anyString()))
                .thenThrow(new IllegalStateException("seed failed"));

        assertThrows(IllegalStateException.class,
                () -> service.createWorkingCopy(
                        "alice", "source-repository", "main", "Alice workspace", null));

        assertEquals(List.of(
                WorkspaceProvisioningStatus.PROVISIONING,
                WorkspaceProvisioningStatus.FAILED), persistedStates);
        assertEquals("seed failed", persistedError.get());
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

    @Test
    void inactiveSourceIsRejectedBeforeWorkspaceMetadataOrStorageIsCreated() {
        source.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createWorkingCopy(
                        "alice", "source-repository", "main", "Alice", null));

        assertTrue(failure.getMessage().contains("not active"));
        verify(workspaceRepository, never()).save(any());
        verify(repositoryFactory, never()).createWorkspaceRepository(
                anyString(), anyString(), anyString());
        verify(repositoryFactory, never()).openWorkspaceRepository(anyString());
    }

    @Test
    void blankRequestedBranchUsesSourceDefaultAndTrimsRequiredText() throws Exception {
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        when(workspaceRepository.save(any(UserWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repositoryFactory.getCentralRepository("source-repository")).thenReturn(sourceGit);
        when(sourceGit.getDslAtCommit("source-head", GitHistoryTransfer.MAX_OBJECT_BYTES)).thenReturn("portfolio dsl");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-head");
        when(repositoryFactory.openWorkspaceRepository(anyString())).thenReturn(workspaceGit);
        when(workspaceGit.createBranchAtCommit(eq("sync-base"), anyString())).thenReturn("source-head");

        UserWorkspace workspace = service.createWorkingCopy(
                " alice ", "source-repository", " ", " Workspace ", "description");

        assertEquals("alice", workspace.getUsername());
        assertEquals("Workspace", workspace.getDisplayName());
        assertEquals("main", workspace.getSourceBranch());
        assertFalse(workspace.isShared());
    }

    @Test
    void emptySourceContentFailsWithoutDeletingUnattemptedWorkspaceStorage() throws Exception {
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        when(workspaceRepository.save(any(UserWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repositoryFactory.getCentralRepository("source-repository")).thenReturn(sourceGit);
        when(sourceGit.getDslAtCommit("source-head", GitHistoryTransfer.MAX_OBJECT_BYTES)).thenReturn("  ");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-head");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createWorkingCopy(
                        "alice", "source-repository", "main", "Workspace", null));

        assertTrue(failure.getCause().getMessage().contains("no architecture content"));
        verify(repositoryFactory, never()).deleteWorkspaceRepository(anyString());
    }

    @Test
    void lostContributionAfterTransferCleansTheAllocationWithoutPublishingRefs() throws Exception {
        configureValidSourceAndWorkspace();
        when(memberships.canContribute(source, "alice")).thenReturn(true, false);
        org.mockito.Mockito.doAnswer(call -> {
            call.<Consumer<SystemRepository>>getArgument(1).accept(source);
            return null;
        }).when(systemRepositoryService).readCurrentAuthorization(eq("source-repository"), any());

        assertThrows(AccessDeniedException.class, () -> service.createWorkingCopy(
                "alice", "source-repository", "main", "Workspace", null));

        transfer.verify(() -> GitHistoryTransfer.copyCommitClosure(sourceGit, workspaceGit, "source-head", List.of()));
        verify(workspaceGit, never()).fastForwardIfHeadMatches(anyString(), isNull(), anyString(), anyString(), anyString());
        verify(workspaceGit, never()).createBranchAtCommit(anyString(), anyString());
        verify(repositoryFactory).deleteWorkspaceRepository(anyString());
    }

    @Test
    void cleanupFailureIsSuppressedOnProvisioningFailure() throws Exception {
        configureValidSourceAndWorkspace();
        when(workspaceGit.fastForwardIfHeadMatches(
                eq("draft"), isNull(), eq("source-head"), eq("alice"), anyString()))
                .thenThrow(new IOException("seed CAS failed"));
        doThrow(new IllegalStateException("cleanup failed"))
                .when(repositoryFactory).deleteWorkspaceRepository(anyString());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createWorkingCopy(
                        "alice", "source-repository", "main", "Workspace", null));

        assertTrue(failure.getCause().getMessage().contains("seed CAS failed"));
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("cleanup failed", failure.getCause().getSuppressed()[0].getMessage());
    }

    @Test
    void requiredIdentityAndDisplayNameRejectBlankValues() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createWorkingCopy(
                        " ", "source-repository", "main", "Workspace", null));
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        assertThrows(IllegalArgumentException.class,
                () -> service.createWorkingCopy(
                        "alice", "source-repository", "main", null, null));
    }

    private void configureValidSourceAndWorkspace() throws Exception {
        when(systemRepositoryService.getRepository("source-repository")).thenReturn(source);
        when(workspaceRepository.save(any(UserWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repositoryFactory.getCentralRepository("source-repository")).thenReturn(sourceGit);
        when(sourceGit.getDslAtCommit("source-head", GitHistoryTransfer.MAX_OBJECT_BYTES)).thenReturn("portfolio dsl");
        when(sourceGit.getHeadCommit("main")).thenReturn("source-head");
        when(repositoryFactory.openWorkspaceRepository(anyString())).thenReturn(workspaceGit);
    }
}
