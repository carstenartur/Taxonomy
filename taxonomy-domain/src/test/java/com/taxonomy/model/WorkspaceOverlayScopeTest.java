package com.taxonomy.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceOverlayScopeTest {

    @Test
    void storedScopeDistinguishesTheCentralOwnerFromEveryNonblankWorkingCopyId() {
        assertEquals(" central", WorkspaceOverlayScope.storageKeyFor(null));
        assertEquals("central", WorkspaceOverlayScope.storageKeyFor(" central"));
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
    void explicitAndLegacyDisplayPinsSelectTheExactWorkspace() {
        assertNull(WorkspaceOverlayScope.resolveWorkspaceId("__shared__", null));
        assertNull(WorkspaceOverlayScope.resolveWorkspaceId("__shared__", ""));
        assertEquals("__shared__", WorkspaceOverlayScope.resolveWorkspaceId("__shared__", "__shared__"));
        assertEquals("copy", WorkspaceOverlayScope.resolveWorkspaceId("copy", null));
        assertEquals("copy", WorkspaceOverlayScope.resolveWorkspaceId("copy", "copy"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceOverlayScope.resolveWorkspaceId("__shared__", "copy"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceOverlayScope.resolveWorkspaceId("copy", ""));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceOverlayScope.resolveWorkspaceId("copy", " copy "));
    }

    @Test
    void workspaceStateUsesNormalizedWorkspaceIdentity() {
        assertEquals("workspace-a", WorkspaceOverlayScope.keyFor("  workspace-a  "));
    }
}
