package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies coordination between workspace metadata and logical Git storage deletion. */
class WorkspaceManagerGitDeletionTest {

    private UserWorkspaceRepository workspaceRepository;
    private DslGitRepositoryFactory repositoryFactory;
    private WorkspaceManager manager;
    private UserWorkspace workspace;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(UserWorkspaceRepository.class);
        repositoryFactory = mock(DslGitRepositoryFactory.class);
        DslGitRepository systemRepository = mock(DslGitRepository.class);
        when(repositoryFactory.getSystemRepository()).thenReturn(systemRepository);

        manager = new WorkspaceManager(
                workspaceRepository,
                50,
                mock(SystemRepositoryService.class),
                repositoryFactory);

        workspace = new UserWorkspace();
        workspace.setWorkspaceId("workspace-42");
        workspace.setUsername("alice");
        workspace.setShared(false);
        workspace.setDefault(false);
        when(workspaceRepository.findByWorkspaceId("workspace-42"))
                .thenReturn(Optional.of(workspace));
    }

    @Test
    void deletesLogicalGitRepositoryBeforeWorkspaceMetadata() {
        when(repositoryFactory.deleteWorkspaceRepository("workspace-42"))
                .thenReturn(new RepositoryDeletionResult(3, 2, 0));

        manager.deleteWorkspace("workspace-42", "alice");

        InOrder order = inOrder(repositoryFactory, workspaceRepository);
        order.verify(repositoryFactory).deleteWorkspaceRepository("workspace-42");
        order.verify(workspaceRepository).delete(workspace);
    }

    @Test
    void preservesWorkspaceMetadataWhenGitDeletionFails() {
        doThrow(new IllegalStateException("repository still open"))
                .when(repositoryFactory)
                .deleteWorkspaceRepository("workspace-42");

        assertThrows(
                IllegalStateException.class,
                () -> manager.deleteWorkspace("workspace-42", "alice"));

        verify(workspaceRepository, never()).delete(workspace);
    }
}
