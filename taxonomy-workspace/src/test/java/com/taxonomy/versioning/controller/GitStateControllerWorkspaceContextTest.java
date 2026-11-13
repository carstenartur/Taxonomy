package com.taxonomy.versioning.controller;

import com.taxonomy.dto.ProjectionState;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression coverage for workspace-aware Git state reads. */
class GitStateControllerWorkspaceContextTest {

    private RepositoryStateService stateService;
    private WorkspaceResolver workspaceResolver;
    private WorkspaceContext workspaceContext;
    private GitStateController controller;

    @BeforeEach
    void setUp() {
        stateService = mock(RepositoryStateService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        workspaceContext = new WorkspaceContext("architect", "architect-workspace", "draft");
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        when(workspaceResolver.resolveCurrentContext()).thenReturn(workspaceContext);
        controller = new GitStateController(stateService, workspaceResolver);
    }

    @Test
    void stateReadsTheResolvedWorkspaceRepository() {
        controller.getState("draft");

        verifyWorkspaceProvisioning();
        verify(stateService).getState("architect", "draft", workspaceContext);
        verify(stateService, never()).getState("architect", "draft");
    }

    @Test
    void branchListingReadsTheResolvedWorkspaceRepository() {
        controller.listBranches("feature");

        verifyWorkspaceProvisioning();
        verify(stateService).getState("architect", "feature", workspaceContext);
        verify(stateService, never()).getState("architect", "feature");
    }

    @Test
    void projectionReadsTheResolvedWorkspaceRepository() {
        controller.getProjectionState("draft");

        verifyWorkspaceProvisioning();
        verify(stateService).getProjectionState("architect", "draft", workspaceContext);
        verify(stateService, never()).getProjectionState("architect", "draft");
    }

    @Test
    void staleCheckReadsTheResolvedWorkspaceRepository() {
        when(stateService.getProjectionState("architect", "draft", workspaceContext))
                .thenReturn(new ProjectionState(null, null, null, null, null, true, false));

        var response = controller.isStale("draft");

        verifyWorkspaceProvisioning();
        verify(stateService).getProjectionState("architect", "draft", workspaceContext);
        verify(stateService, never()).getProjectionState("architect", "draft");
        assertThat(response.getBody())
                .containsEntry("projectionStale", true)
                .containsEntry("indexStale", false);
    }

    @Test
    void pinnedPortfolioReadsSelectedBranchWithoutProvisioning() {
        var selected = new WorkspaceContext("architect", "other-workspace", "review", "repo-a");
        when(workspaceResolver.resolveUnambiguousContextForRead()).thenReturn(selected);
        when(workspaceResolver.hasExplicitSourceSelection()).thenReturn(true);
        controller.getState("review");
        verify(stateService).getState("architect", "review", selected);
        verify(stateService, never()).ensureWorkspaceState("architect");
    }

    @Test
    void pinnedPortfolioPageRejectsAnOldBranchBeforeReadingGitState() throws Exception {
        when(workspaceResolver.hasExplicitSourceSelection()).thenCallRealMethod();
        when(workspaceResolver.resolveUnambiguousContextForRead()).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/git/state")
                        .param("repositoryId", "repo-a").param("branch", "old-page"))
                .andExpect(status().isConflict());
        verifyNoInteractions(stateService);
    }

    private void verifyWorkspaceProvisioning() {
        verify(stateService).ensureWorkspaceState("architect");
        verify(workspaceResolver).resolveCurrentContext();
    }
}
