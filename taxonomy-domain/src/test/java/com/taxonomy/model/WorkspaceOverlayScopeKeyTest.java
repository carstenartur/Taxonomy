package com.taxonomy.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceOverlayScopeKeyTest {

    @Test
    void usesSharedScopeWhenWorkspaceIsAbsentOrBlank() {
        assertThat(WorkspaceOverlayScopeKey.forWorkspace(null)).isEqualTo(WorkspaceOverlayScopeKey.SHARED);
        assertThat(WorkspaceOverlayScopeKey.forWorkspace("   ")).isEqualTo(WorkspaceOverlayScopeKey.SHARED);
    }

    @Test
    void normalizesConcreteWorkspaceIdentifiers() {
        assertThat(WorkspaceOverlayScopeKey.forWorkspace("  workspace-a  ")).isEqualTo("workspace-a");
    }
}
