package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceOverlayScopeTest {

    @Test
    void centralStateUsesStableSharedScope() {
        assertThat(WorkspaceOverlayScope.keyFor(null))
                .isEqualTo(WorkspaceOverlayScope.CENTRAL_SCOPE_KEY);
        assertThat(WorkspaceOverlayScope.keyFor("   "))
                .isEqualTo(WorkspaceOverlayScope.CENTRAL_SCOPE_KEY);
        assertThat(WorkspaceOverlayScope.CENTRAL_SCOPE_KEY)
                .isEqualTo("__shared__");
    }

    @Test
    void workspaceStateUsesNormalizedWorkspaceIdentity() {
        assertThat(WorkspaceOverlayScope.keyFor("  workspace-a  "))
                .isEqualTo("workspace-a");
    }
}
