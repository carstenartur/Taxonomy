package com.taxonomy.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceScopeKeyTest {

    @Test
    void usesSharedScopeWhenWorkspaceIsAbsentOrBlank() {
        assertThat(WorkspaceScopeKey.forWorkspace(null)).isEqualTo(WorkspaceScopeKey.SHARED);
        assertThat(WorkspaceScopeKey.forWorkspace("   ")).isEqualTo(WorkspaceScopeKey.SHARED);
    }

    @Test
    void normalizesConcreteWorkspaceIdentifiers() {
        assertThat(WorkspaceScopeKey.forWorkspace("  workspace-a  ")).isEqualTo("workspace-a");
    }
}
