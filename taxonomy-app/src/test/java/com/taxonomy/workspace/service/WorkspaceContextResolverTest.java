package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkspaceContextResolver}.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceContextResolverTest {

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    private WorkspaceContextResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkspaceContextResolver(workspaceManager, systemRepositoryService);
    }

    @Test
    void nullUsernameReturnsSHARED() {
        assertThat(resolver.resolveForUser(null)).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void blankUsernameReturnsSHARED() {
        assertThat(resolver.resolveForUser("")).isEqualTo(WorkspaceContext.SHARED);
        assertThat(resolver.resolveForUser("  ")).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void anonymousUserReturnsSHARED() {
        assertThat(resolver.resolveForUser("anonymous")).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void userWithoutProvisionedWorkspaceReturnsSHARED() {
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(null);
        assertThat(resolver.resolveForUser("alice")).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void userWithProvisionedWorkspaceReturnsContext() {
        UserWorkspace ws = new UserWorkspace();
        ws.setUsername("alice");
        ws.setWorkspaceId("alice-ws-123");
        ws.setCurrentBranch("alice/workspace");

        when(workspaceManager.findUserWorkspace("alice")).thenReturn(ws);

        WorkspaceContext ctx = resolver.resolveForUser("alice");
        assertThat(ctx.username()).isEqualTo("alice");
        assertThat(ctx.workspaceId()).isEqualTo("alice-ws-123");
        assertThat(ctx.currentBranch()).isEqualTo("alice/workspace");
    }

    @Test
    void provisionedWorkspaceWithNullBranchFallsBackToSharedBranch() {
        UserWorkspace ws = new UserWorkspace();
        ws.setUsername("bob");
        ws.setWorkspaceId("bob-ws");
        ws.setCurrentBranch(null);

        when(workspaceManager.findUserWorkspace("bob")).thenReturn(ws);
        when(systemRepositoryService.getSharedBranch()).thenReturn("draft");

        WorkspaceContext ctx = resolver.resolveForUser("bob");
        assertThat(ctx.currentBranch()).isEqualTo("draft");
    }

    @Test
    void sharedContextHasNullWorkspaceId() {
        assertThat(WorkspaceContext.SHARED.username()).isEqualTo("system");
        assertThat(WorkspaceContext.SHARED.workspaceId()).isNull();
        assertThat(WorkspaceContext.SHARED.currentBranch()).isEqualTo("draft");
    }

    @Test
    void differentWorkspacesAreIsolated() {
        UserWorkspace aliceWs = new UserWorkspace();
        aliceWs.setUsername("alice");
        aliceWs.setWorkspaceId("alice-ws");
        aliceWs.setCurrentBranch("alice/workspace");

        UserWorkspace bobWs = new UserWorkspace();
        bobWs.setUsername("bob");
        bobWs.setWorkspaceId("bob-ws");
        bobWs.setCurrentBranch("bob/workspace");

        when(workspaceManager.findUserWorkspace("alice")).thenReturn(aliceWs);
        when(workspaceManager.findUserWorkspace("bob")).thenReturn(bobWs);

        WorkspaceContext aliceCtx = resolver.resolveForUser("alice");
        WorkspaceContext bobCtx = resolver.resolveForUser("bob");

        assertThat(aliceCtx.workspaceId()).isNotEqualTo(bobCtx.workspaceId());
        assertThat(aliceCtx.currentBranch()).isNotEqualTo(bobCtx.currentBranch());
    }
}
