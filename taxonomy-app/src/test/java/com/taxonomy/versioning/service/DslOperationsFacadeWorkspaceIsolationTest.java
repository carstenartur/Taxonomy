package com.taxonomy.versioning.service;

import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DslOperationsFacadeWorkspaceIsolationTest {
    private static final WorkspaceArchitectureVersionPort DIRECT_VERSIONS = new WorkspaceArchitectureVersionPort() {
        @Override
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };

    @Test
    void doesNotFallBackToSharedRepositoryWhenWorkspaceProvisioningFails() {
        DslGitRepositoryFactory repositoryFactory = mock(DslGitRepositoryFactory.class);
        CommitIndexService commitIndexService = mock(CommitIndexService.class);
        ConflictDetectionService conflictDetectionService = mock(ConflictDetectionService.class);
        RepositoryStateGuard stateGuard = mock(RepositoryStateGuard.class);
        RepositoryStateService repositoryStateService = mock(RepositoryStateService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);

        DslOperationsFacade facade = new DslOperationsFacade(
                repositoryFactory,
                commitIndexService,
                conflictDetectionService,
                stateGuard,
                repositoryStateService,
                workspaceResolver, DIRECT_VERSIONS);

        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        doThrow(new IllegalStateException("workspace database unavailable"))
                .when(repositoryStateService).ensureWorkspaceState("architect");

        assertThatThrownBy(() -> facade.getDslHistory("draft"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace database unavailable");
        verifyNoInteractions(repositoryFactory);
    }
}
