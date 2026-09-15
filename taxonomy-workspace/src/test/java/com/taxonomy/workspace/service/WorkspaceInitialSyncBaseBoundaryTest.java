package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceInitialSyncBaseBoundaryTest {
    enum BaseState { MISSING_SNAPSHOT, BLANK_SNAPSHOT, EXISTING_TRACKING }
    private static final String CAPTURED = "1".repeat(40);
    private static final String LOCAL = "meta { version: \"local\"; }";
    private static final String REMOTE = "meta { version: \"remote\"; }";
    private static final String TRACKED = "meta { version: \"tracked\"; }";
    private static final WorkspaceArchitectureVersionPort DIRECT = new WorkspaceArchitectureVersionPort() {
        @Override
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };

    @ParameterizedTest
    @EnumSource(BaseState.class)
    void neverSubstitutesLiveHeadForRecordedProvenance(BaseState baseState) throws Exception {
        var source = mock(DslGitRepository.class);
        var destination = mock(DslGitRepository.class);
        var factory = mock(DslGitRepositoryFactory.class);
        when(factory.getSystemRepository()).thenReturn(source);
        when(factory.getCentralRepository("source-id")).thenReturn(source);
        when(factory.openWorkspaceRepository("workspace-id")).thenReturn(destination);
        when(source.getHeadCommit("draft")).thenReturn("2".repeat(40));
        when(source.getDslAtCommit("2".repeat(40))).thenReturn(REMOTE);
        when(destination.getHeadCommit("main")).thenReturn("3".repeat(40));
        when(destination.getDslAtCommit("3".repeat(40))).thenReturn(LOCAL);
        when(destination.getDslAtHead("main")).thenReturn(LOCAL);
        when(source.getDslAtCommit(CAPTURED))
                .thenReturn(baseState == BaseState.BLANK_SNAPSHOT ? " \t\n" : null);
        if (baseState == BaseState.EXISTING_TRACKING) {
            when(destination.getDslAtHead("sync-base")).thenReturn(TRACKED);
        }
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("workspace-id");
        workspace.setUsername("base-owner");
        workspace.setSourceRepositoryId("source-id");
        workspace.setBaseCommit(CAPTURED);
        var sourceMetadata = new SystemRepository();
        sourceMetadata.setRepositoryId("source-id");
        sourceMetadata.setDefaultBranch("draft");
        var rows = mock(UserWorkspaceRepository.class);
        when(rows.findByWorkspaceId("workspace-id")).thenReturn(Optional.of(workspace));
        var catalog = mock(SystemRepositoryService.class);
        when(catalog.getRepository("source-id")).thenReturn(sourceMetadata);
        var syncRows = mock(SyncStateRepository.class);
        when(syncRows.findByUsername("base-owner")).thenReturn(Optional.of(new SyncState()));
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var merger = mock(SemanticGitMergeService.class);
        when(merger.mergeContent(anyString(), anyString(), anyString()))
                .thenReturn(new TaxDslMergeResult(LOCAL, List.of()));
        var service = new GitNativeSyncIntegrationService(syncRows, rows, catalog, factory,
                merger, portfolio, mock(WorkspaceContextResolver.class), DIRECT);

        if (baseState == BaseState.EXISTING_TRACKING) {
            service.syncFromSharedToWorkspace("base-owner", "workspace-id");
            service.publishFromWorkspaceToShared("base-owner", "workspace-id");
            verify(merger).mergeContent(TRACKED, LOCAL, REMOTE);
            verify(merger).mergeContent(TRACKED, REMOTE, LOCAL);
            verify(source, never()).getDslAtCommit(CAPTURED);
        } else {
            assertThrows(IOException.class,
                    () -> service.syncFromSharedToWorkspace("base-owner", "workspace-id"));
            assertThrows(IOException.class,
                    () -> service.publishFromWorkspaceToShared("base-owner", "workspace-id"));
            verify(source, times(2)).getDslAtCommit(CAPTURED);
            verify(source, never()).getDslAtHead(anyString());
            verify(source, never()).commitDsl(anyString(), anyString(), anyString(), anyString());
            verify(destination, never()).commitDsl(anyString(), anyString(), anyString(), anyString());
            verify(rows, never()).save(any(UserWorkspace.class));
            verifyNoInteractions(merger, portfolio, syncRows);
            assertNull(workspace.getCurrentCommit());
        }
        assertEquals(CAPTURED, workspace.getBaseCommit());
    }
}
