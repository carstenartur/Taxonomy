package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.PortfolioGitController;
import com.taxonomy.portfolio.service.PortfolioGitApplicationService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PortfolioGitControllerWorkspaceIsolationTest {

    @Test
    void doesNotFallBackToSharedRepositoryWhenWorkspaceProvisioningFails() {
        PortfolioGitApplicationService gitService =
                mock(PortfolioGitApplicationService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        RepositoryStateService repositoryStateService = mock(RepositoryStateService.class);
        PortfolioGitController controller = new PortfolioGitController(
                gitService, workspaceResolver, repositoryStateService);

        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        doThrow(new IllegalStateException("workspace database unavailable"))
                .when(repositoryStateService).ensureWorkspaceState("architect");

        assertThatThrownBy(controller::exportPortfolio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace database unavailable");
        verifyNoInteractions(gitService);
    }
}
