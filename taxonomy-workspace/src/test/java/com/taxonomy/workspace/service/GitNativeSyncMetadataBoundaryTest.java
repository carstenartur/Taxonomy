package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Invalid provenance must fail before Git, projection, versioning or metadata writes. */
class GitNativeSyncMetadataBoundaryTest {
    enum InvalidMetadata {
        NULL_WORKSPACE, BLANK_WORKSPACE, UNKNOWN_WORKSPACE,
        NULL_SOURCE, BLANK_SOURCE, UNKNOWN_SOURCE, NULL_CATALOG_ID, BLANK_CATALOG_ID
    }

    @ParameterizedTest
    @EnumSource(InvalidMetadata.class)
    void rejectsInvalidMetadataForBothExplicitSynchronizationDirections(InvalidMetadata invalid) {
        var rows = mock(UserWorkspaceRepository.class);
        var catalog = mock(SystemRepositoryService.class);
        var factory = mock(DslGitRepositoryFactory.class);
        var syncStates = mock(SyncStateRepository.class);
        var merger = mock(SemanticGitMergeService.class);
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var contexts = mock(WorkspaceContextResolver.class);
        var versions = mock(WorkspaceArchitectureVersionPort.class);
        var service = new GitNativeSyncIntegrationService(
                syncStates, rows, catalog, factory, merger, portfolio, contexts, versions);
        // The legacy superclass obtains its shared handle during construction;
        // this test concerns side effects of the requested operation itself.
        clearInvocations(factory);

        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("boundary-workspace");
        workspace.setUsername("boundary-owner");
        workspace.setSourceRepositoryId("boundary-source");
        var source = new SystemRepository();
        source.setRepositoryId("boundary-source");
        when(rows.findByWorkspaceId("boundary-workspace")).thenReturn(Optional.of(workspace));
        when(catalog.getRepository("boundary-source")).thenReturn(source);

        String requestedWorkspace = switch (invalid) {
            case NULL_WORKSPACE -> null;
            case BLANK_WORKSPACE -> " \t ";
            case UNKNOWN_WORKSPACE -> "missing-workspace";
            default -> "boundary-workspace";
        };
        switch (invalid) {
            case NULL_SOURCE -> workspace.setSourceRepositoryId(null);
            case BLANK_SOURCE -> workspace.setSourceRepositoryId(" \t ");
            case UNKNOWN_SOURCE -> when(catalog.getRepository("boundary-source")).thenReturn(null);
            case NULL_CATALOG_ID -> source.setRepositoryId(null);
            case BLANK_CATALOG_ID -> source.setRepositoryId(" \t ");
            default -> { }
        }
        Class<? extends RuntimeException> expected = switch (invalid) {
            case NULL_WORKSPACE, BLANK_WORKSPACE -> IllegalArgumentException.class;
            default -> IllegalStateException.class;
        };
        String message = switch (invalid) {
            case NULL_WORKSPACE, BLANK_WORKSPACE -> "workspaceId must not be blank";
            case UNKNOWN_WORKSPACE -> "Workspace metadata not found";
            case NULL_SOURCE, BLANK_SOURCE -> "Workspace has no sourceRepositoryId";
            default -> "Source repository has no repositoryId";
        };

        RuntimeException pull = assertThrows(expected,
                () -> service.syncFromSharedToWorkspace("boundary-owner", requestedWorkspace));
        RuntimeException push = assertThrows(expected,
                () -> service.publishFromWorkspaceToShared("boundary-owner", requestedWorkspace));

        assertTrue(pull.getMessage().contains(message), pull.getMessage());
        assertTrue(push.getMessage().contains(message), push.getMessage());
        verifyNoInteractions(factory, syncStates, merger, portfolio, contexts, versions);
        verify(rows, never()).save(any(UserWorkspace.class));
        verify(catalog, never()).save(any(SystemRepository.class));
        assertNull(workspace.getCurrentCommit());
        assertNull(workspace.getLastFetchedCommit());
        assertNull(workspace.getLastIntegratedCommit());
    }
}
