package com.taxonomy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceOverlayScopeTest {

    @Test
    void storedScopeDistinguishesTheCentralOwnerFromEveryNonblankWorkingCopyId() {
        assertEquals("", WorkspaceOverlayScope.storageKeyFor(null));
        assertEquals("__shared__", WorkspaceOverlayScope.storageKeyFor("__shared__"));
        assertEquals("a:b", WorkspaceOverlayScope.storageKeyFor(" a:b "));
    }

    @Test
    void centralStateUsesStableSharedScope() {
        assertEquals(WorkspaceOverlayScope.CENTRAL_SCOPE_KEY, WorkspaceOverlayScope.keyFor(null));
        assertEquals(WorkspaceOverlayScope.CENTRAL_SCOPE_KEY, WorkspaceOverlayScope.keyFor("   "));
        assertEquals("__shared__", WorkspaceOverlayScope.CENTRAL_SCOPE_KEY);
    }

    @Test
    void workspaceStateUsesNormalizedWorkspaceIdentity() {
        assertEquals("workspace-a", WorkspaceOverlayScope.keyFor("  workspace-a  "));
    }
}
