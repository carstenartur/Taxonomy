package com.taxonomy.workspace.service;

import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissingPortfolioMutationBoundaryTest {
    @Test void rejectsAllSyncWritesBeforeContextProvisioningOrGitMutation() {
        var sync = mock(SyncStateRepository.class);
        var workspaces = mock(UserWorkspaceRepository.class);
        var system = mock(SystemRepositoryService.class);
        var repositories = mock(DslGitRepositoryFactory.class);
        var merge = mock(SemanticGitMergeService.class);
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var context = mock(WorkspaceContextResolver.class);
        var versions = mock(WorkspaceArchitectureVersionPort.class);
        var service = new GitNativeSyncIntegrationService(sync, workspaces, system, repositories, merge,
                portfolio, context, versions);
        clearInvocations(repositories);
        doThrow(new IllegalStateException("Feature unavailable")).when(portfolio).requireAvailable();
        assertThatThrownBy(() -> service.syncFromShared("alice", "draft")).hasMessage("Feature unavailable");
        assertThatThrownBy(() -> service.publishToShared("alice", "draft")).hasMessage("Feature unavailable");
        assertThatThrownBy(() -> service.syncFromSharedToWorkspace("alice", "ws")).hasMessage("Feature unavailable");
        assertThatThrownBy(() -> service.publishFromWorkspaceToShared("alice", "ws")).hasMessage("Feature unavailable");
        assertThatThrownBy(() -> service.resolveDiverged("alice", "draft", SyncIntegrationService.DivergedStrategy.KEEP_MINE))
                .hasMessage("Feature unavailable");
        verifyNoInteractions(sync, workspaces, system, repositories, merge, context, versions);
    }
}
