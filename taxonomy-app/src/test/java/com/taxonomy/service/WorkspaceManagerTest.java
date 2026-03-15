package com.taxonomy.service;

import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link WorkspaceManager}.
 *
 * <p>Tests workspace lifecycle: creation, retrieval, eviction, and listing.
 */
class WorkspaceManagerTest {

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        manager = new WorkspaceManager(wsRepo, 50);
    }

    @Test
    void getOrCreateWorkspaceCreatesNewState() {
        UserWorkspaceState state = manager.getOrCreateWorkspace("alice");
        assertNotNull(state);
        assertEquals("alice", state.getUsername());
    }

    @Test
    void getOrCreateWorkspaceReturnsSameInstance() {
        UserWorkspaceState first = manager.getOrCreateWorkspace("alice");
        UserWorkspaceState second = manager.getOrCreateWorkspace("alice");
        assertSame(first, second);
    }

    @Test
    void differentUsersGetDifferentInstances() {
        UserWorkspaceState alice = manager.getOrCreateWorkspace("alice");
        UserWorkspaceState bob = manager.getOrCreateWorkspace("bob");
        assertNotSame(alice, bob);
    }

    @Test
    void getWorkspaceReturnsNullForUnknownUser() {
        assertNull(manager.getWorkspace("unknown"));
    }

    @Test
    void getWorkspaceReturnsExistingState() {
        manager.getOrCreateWorkspace("alice");
        assertNotNull(manager.getWorkspace("alice"));
    }

    @Test
    void evictWorkspaceRemovesState() {
        manager.getOrCreateWorkspace("alice");
        assertEquals(1, manager.getActiveWorkspaceCount());

        manager.evictWorkspace("alice");
        assertEquals(0, manager.getActiveWorkspaceCount());
        assertNull(manager.getWorkspace("alice"));
    }

    @Test
    void evictNonexistentWorkspaceIsNoOp() {
        assertDoesNotThrow(() -> manager.evictWorkspace("unknown"));
    }

    @Test
    void activeWorkspaceCountReflectsState() {
        assertEquals(0, manager.getActiveWorkspaceCount());

        manager.getOrCreateWorkspace("alice");
        assertEquals(1, manager.getActiveWorkspaceCount());

        manager.getOrCreateWorkspace("bob");
        assertEquals(2, manager.getActiveWorkspaceCount());

        manager.evictWorkspace("alice");
        assertEquals(1, manager.getActiveWorkspaceCount());
    }

    @Test
    void listActiveWorkspacesReturnsAllUsers() {
        manager.getOrCreateWorkspace("alice");
        manager.getOrCreateWorkspace("bob");
        manager.getOrCreateWorkspace("carol");

        List<WorkspaceInfo> workspaces = manager.listActiveWorkspaces();
        assertEquals(3, workspaces.size());
    }

    @Test
    void getWorkspaceInfoReturnsCorrectData() {
        WorkspaceInfo info = manager.getWorkspaceInfo("alice");

        assertEquals("alice", info.username());
        assertEquals("draft", info.currentBranch());
        assertNotNull(info.currentContext());
        assertFalse(info.shared());
    }

    @Test
    void nullUsernameDefaultsToAnonymous() {
        UserWorkspaceState state = manager.getOrCreateWorkspace(null);
        assertEquals(WorkspaceManager.DEFAULT_USER, state.getUsername());
    }

    @Test
    void blankUsernameDefaultsToAnonymous() {
        UserWorkspaceState state = manager.getOrCreateWorkspace("");
        assertEquals(WorkspaceManager.DEFAULT_USER, state.getUsername());
    }

    @Test
    void initialWorkspaceStateHasDraftContext() {
        UserWorkspaceState state = manager.getOrCreateWorkspace("alice");
        assertNotNull(state.getCurrentContext());
        assertEquals("draft", state.getCurrentContext().branch());
        assertFalse(state.isOperationInProgress());
        assertNull(state.getLastProjectionCommit());
        assertNull(state.getLastIndexCommit());
        assertTrue(state.isHistoryEmpty());
    }
}
