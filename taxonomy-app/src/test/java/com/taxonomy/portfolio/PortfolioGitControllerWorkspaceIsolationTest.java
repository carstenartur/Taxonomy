package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.PortfolioGitController;
import com.taxonomy.portfolio.dto.PortfolioGitDtos.ExportedPortfolioDsl;
import com.taxonomy.portfolio.service.PortfolioGitApplicationService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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

    @Test
    void exportNegotiationKeepsWildcardClientsOnPlainDslAndRequiresExplicitJson() throws Exception {
        PortfolioGitApplicationService gitService =
                mock(PortfolioGitApplicationService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        RepositoryStateService repositoryStateService = mock(RepositoryStateService.class);
        PortfolioGitController controller = new PortfolioGitController(
                gitService, workspaceResolver, repositoryStateService);
        WorkspaceContext context = new WorkspaceContext("architect", "ws-1", "draft");
        ExportedPortfolioDsl exported = new ExportedPortfolioDsl(
                "ws-1", "architect", "draft", "head-1", "project SAMPLE {}\n",
                1, 0, 0, 0, null);

        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        when(gitService.export(context)).thenReturn(exported);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(get("/api/projects/git/export").accept(MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(exported.dsl()));

        mockMvc.perform(get("/api/projects/git/export").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workspaceId").value("ws-1"))
                .andExpect(jsonPath("$.dsl").value(exported.dsl()));
    }
}
