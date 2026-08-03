package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Unit tests for {@link WorkspaceContextResolver}. */
@ExtendWith(MockitoExtension.class)
class WorkspaceContextResolverTest {

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    @Test
    void nullBlankAndDefaultUsersReturnSharedContext() {
        WorkspaceContextResolver resolver = resolver(false);

        assertThat(resolver.resolveForUser(null)).isEqualTo(WorkspaceContext.SHARED);
        assertThat(resolver.resolveForUser("")).isEqualTo(WorkspaceContext.SHARED);
        assertThat(resolver.resolveForUser("  ")).isEqualTo(WorkspaceContext.SHARED);
        assertThat(resolver.resolveForUser(WorkspaceManager.DEFAULT_USER))
                .isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void authenticatedUserWithoutWorkspaceFailsClosed() {
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(null);
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(null);

        assertThatThrownBy(() -> resolver(false).resolveForUser("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No isolated workspace")
                .hasMessageContaining("alice");
    }

    @Test
    void explicitSharedModeAllowsLegacySharedScope() {
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(null);
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(null);

        assertThat(resolver(true).resolveForUser("alice"))
                .isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void userWithProvisionedWorkspaceReturnsContext() {
        UserWorkspace workspace = workspace("alice", "alice-ws-123", "alice/workspace");
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(workspace);

        WorkspaceContext context = resolver(false).resolveForUser("alice");

        assertThat(context.username()).isEqualTo("alice");
        assertThat(context.workspaceId()).isEqualTo("alice-ws-123");
        assertThat(context.currentBranch()).isEqualTo("alice/workspace");
    }

    @Test
    void legacyWorkspaceLookupRemainsSupported() {
        UserWorkspace workspace = workspace("alice", "alice-ws-123", "alice/workspace");
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(null);
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(workspace);

        assertThat(resolver(false).resolveForUser("alice").workspaceId())
                .isEqualTo("alice-ws-123");
    }

    @Test
    void provisionedWorkspaceWithNullBranchUsesConfiguredSharedBranchName() {
        UserWorkspace workspace = workspace("bob", "bob-ws", null);
        when(workspaceManager.findActiveWorkspace("bob")).thenReturn(workspace);
        when(systemRepositoryService.getSharedBranch()).thenReturn("draft");

        assertThat(resolver(false).resolveForUser("bob").currentBranch())
                .isEqualTo("draft");
    }

    @Test
    void differentWorkspacesAreIsolated() {
        when(workspaceManager.findActiveWorkspace("alice"))
                .thenReturn(workspace("alice", "alice-ws", "alice/workspace"));
        when(workspaceManager.findActiveWorkspace("bob"))
                .thenReturn(workspace("bob", "bob-ws", "bob/workspace"));

        WorkspaceContextResolver resolver = resolver(false);
        WorkspaceContext alice = resolver.resolveForUser("alice");
        WorkspaceContext bob = resolver.resolveForUser("bob");

        assertThat(alice.workspaceId()).isNotEqualTo(bob.workspaceId());
        assertThat(alice.currentBranch()).isNotEqualTo(bob.currentBranch());
    }

    private WorkspaceContextResolver resolver(boolean sharedModeEnabled) {
        return new WorkspaceContextResolver(
                workspaceManager, systemRepositoryService, sharedModeEnabled);
    }

    private UserWorkspace workspace(String username, String workspaceId, String branch) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setWorkspaceId(workspaceId);
        workspace.setCurrentBranch(branch);
        return workspace;
    }
}
