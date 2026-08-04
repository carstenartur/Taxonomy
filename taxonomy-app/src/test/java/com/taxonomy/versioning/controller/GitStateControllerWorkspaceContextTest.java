package com.taxonomy.versioning.controller;

import com.taxonomy.dto.ProjectionState;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private void verifyWorkspaceProvisioning() {
        verify(stateService).ensureWorkspaceState("architect");
        verify(workspaceResolver).resolveCurrentContext();
    }
}
