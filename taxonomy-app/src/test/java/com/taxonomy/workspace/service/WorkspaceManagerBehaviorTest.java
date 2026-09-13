package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Behavioral tests for persistent workspace selection and ownership rules. */
class WorkspaceManagerBehaviorTest {

    private UserWorkspaceRepository repository;
    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        repository = mock(UserWorkspaceRepository.class);
        when(repository.existsByUsername(any())).thenReturn(true);
        when(repository.findByUsernameAndIsDefaultTrue(any())).thenReturn(Optional.empty());
        when(repository.findByUsernameAndSharedFalse(any())).thenReturn(Optional.empty());
        when(repository.save(any(UserWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        manager = new WorkspaceManager(
                repository,
                20,
                mock(SystemRepositoryService.class),
                mock(DslGitRepository.class));
    }

    @Test
    void selectionSkipsArchivedMetadataAndUsesFirstUsableWorkspace() {
        UserWorkspace archivedDefault = workspace("old", "alice");
        archivedDefault.setDefault(true);
        archivedDefault.setArchived(true);
        UserWorkspace selected = workspace("current", "alice");
        when(repository.findByUsernameAndIsDefaultTrue("alice"))
                .thenReturn(Optional.of(archivedDefault));
        when(repository.findByUsernameAndSharedFalse("alice"))
                .thenReturn(Optional.of(selected));
        when(repository.findByWorkspaceId("current")).thenReturn(Optional.of(selected));

        UserWorkspaceState state = manager.getOrCreateWorkspace("alice");

        assertEquals("alice", state.getUsername());
        assertSame(selected, manager.findActiveWorkspace("alice"));
    }

    @Test
    void selectionRejectsArchivedFallbackAndUsesSyntheticIdentity() {
        UserWorkspace archived = workspace("old", "alice");
        archived.setArchived(true);
        when(repository.findByUsernameAndSharedFalse("alice"))
                .thenReturn(Optional.of(archived));

        manager.getOrCreateWorkspace("alice");

        assertNull(manager.findActiveWorkspace("alice"));
        assertNotNull(manager.getWorkspace("alice"));
    }

    @Test
    void switchingEnforcesExistenceOwnershipArchiveAndSharedBoundaries() {
        when(repository.findByWorkspaceId("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> manager.switchWorkspace("alice", "missing"));

        UserWorkspace candidate = workspace("candidate", "bob");
        when(repository.findByWorkspaceId("candidate")).thenReturn(Optional.of(candidate));
        assertThrows(IllegalArgumentException.class,
                () -> manager.switchWorkspace("alice", "candidate"));
        candidate.setUsername("alice");
        candidate.setArchived(true);
        assertThrows(IllegalArgumentException.class,
                () -> manager.switchWorkspace("alice", "candidate"));
        candidate.setArchived(false);
        candidate.setShared(true);
        assertThrows(IllegalArgumentException.class,
                () -> manager.switchWorkspace("alice", "candidate"));

        candidate.setShared(false);
        UserWorkspace switched = manager.switchWorkspace("alice", "candidate");
        assertSame(candidate, switched);
        assertNotNull(candidate.getLastAccessedAt());
        assertNotNull(manager.getWorkspace("alice"));
    }

    @Test
    void renameEnforcesOwnershipAndMutablePrivateWorkspaceRules() {
        UserWorkspace candidate = workspace("candidate", "bob");
        when(repository.findByWorkspaceId("candidate")).thenReturn(Optional.of(candidate));
        assertThrows(IllegalArgumentException.class,
                () -> manager.renameWorkspace("alice", "candidate", "Renamed"));
        candidate.setUsername("alice");
        candidate.setArchived(true);
        assertThrows(IllegalStateException.class,
                () -> manager.renameWorkspace("alice", "candidate", "Renamed"));
        candidate.setArchived(false);
        candidate.setShared(true);
        assertThrows(IllegalStateException.class,
                () -> manager.renameWorkspace("alice", "candidate", "Renamed"));

        candidate.setShared(false);
        assertSame(candidate, manager.renameWorkspace("alice", "candidate", "Renamed"));
        assertEquals("Renamed", candidate.getDisplayName());
    }

    @Test
    void archiveEnforcesOwnershipAndProtectedWorkspaceRulesThenEvictsState() {
        UserWorkspace candidate = workspace("candidate", "bob");
        when(repository.findByWorkspaceId("candidate")).thenReturn(Optional.of(candidate));
        assertThrows(IllegalArgumentException.class,
                () -> manager.archiveWorkspace("candidate", "alice"));
        candidate.setUsername("alice");
        candidate.setShared(true);
        assertThrows(IllegalArgumentException.class,
                () -> manager.archiveWorkspace("candidate", "alice"));
        candidate.setShared(false);
        candidate.setDefault(true);
        assertThrows(IllegalArgumentException.class,
                () -> manager.archiveWorkspace("candidate", "alice"));

        candidate.setDefault(false);
        manager.getOrCreateWorkspace("alice", "candidate");
        assertSame(candidate, manager.archiveWorkspace("candidate", "alice"));
        assertTrue(candidate.isArchived());
        assertNull(manager.getWorkspace("alice"));
    }

    @Test
    void descriptionUpdateEnforcesOwnershipAndArchiveState() {
        UserWorkspace candidate = workspace("candidate", "bob");
        when(repository.findByWorkspaceId("candidate")).thenReturn(Optional.of(candidate));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateDescription("alice", "candidate", "new"));
        candidate.setUsername("alice");
        candidate.setArchived(true);
        assertThrows(IllegalStateException.class,
                () -> manager.updateDescription("alice", "candidate", "new"));

        candidate.setArchived(false);
        assertSame(candidate, manager.updateDescription("alice", "candidate", "new"));
        assertEquals("new", candidate.getDescription());
    }

    @Test
    void lookupFallbacksReturnNullOnRepositoryFailures() {
        assertNull(manager.findActiveWorkspace("alice"));
        manager.getOrCreateWorkspace("alice", "active");
        when(repository.findByWorkspaceId("active"))
                .thenThrow(new IllegalStateException("read failed"));
        assertNull(manager.findActiveWorkspace("alice"));
        assertNull(manager.findUserWorkspace("alice"));

        when(repository.findByWorkspaceId("candidate"))
                .thenThrow(new IllegalStateException("read failed"));
        assertNull(manager.getWorkspaceById("candidate"));
    }

    @Test
    void createsPersistentDefaultMetadataWhenUserHasNoRow() {
        when(repository.existsByUsername("new-user")).thenReturn(false);
        ArgumentCaptor<UserWorkspace> saved = ArgumentCaptor.forClass(UserWorkspace.class);

        UserWorkspaceState state = manager.getOrCreateWorkspace("new-user");

        verify(repository).save(saved.capture());
        UserWorkspace metadata = saved.getValue();
        assertEquals("new-user", state.getUsername());
        assertEquals("new-user", metadata.getUsername());
        assertTrue(metadata.isDefault());
        assertFalse(metadata.isArchived());
        assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED,
                metadata.getProvisioningStatus());
    }

    @Test
    void refreshesExistingMetadataAccessTimeAndSurvivesPersistenceProbeFailure() {
        UserWorkspace existing = workspace("existing", "alice");
        when(repository.findByUsernameAndSharedFalse("alice"))
                .thenReturn(Optional.of(existing));

        manager.getOrCreateWorkspace("alice");

        assertNotNull(existing.getLastAccessedAt());
        verify(repository).save(existing);

        clearInvocations(repository);
        when(repository.existsByUsername("bob"))
                .thenThrow(new IllegalStateException("probe failed"));
        assertEquals("bob", manager.getOrCreateWorkspace("bob").getUsername());
        verify(repository, never()).save(any());
    }

    @Test
    void workspaceInfoUsesPersistedProvisioningAndTopologyMetadata() {
        UserWorkspace workspace = workspace("selected", "alice");
        workspace.setDisplayName("Delivery workspace");
        workspace.setDescription("Current delivery model");
        workspace.setDefault(true);
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
        workspace.setTopologyMode(RepositoryTopologyMode.EXTERNAL_CANONICAL);
        workspace.setSourceRepositoryId("central-1");
        when(repository.findByWorkspaceId("selected")).thenReturn(Optional.of(workspace));
        manager.getOrCreateWorkspace("alice", "selected");

        var info = manager.getWorkspaceInfo("alice");

        assertEquals("selected", info.workspaceId());
        assertEquals("Delivery workspace", info.displayName());
        assertEquals("PROVISIONING", info.provisioningStatus());
        assertEquals("EXTERNAL_CANONICAL", info.topologyMode());
        assertEquals("central-1", info.sourceRepositoryId());
        assertEquals("Current delivery model", info.description());
        assertTrue(info.isDefault());
    }

    @Test
    void createAndListDelegateDurableWorkspaceState() {
        UserWorkspace created = manager.createWorkspace("alice", "Review", "desc");
        when(repository.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc("alice"))
                .thenReturn(List.of(created));

        assertEquals("draft", created.getCurrentBranch());
        assertEquals(WorkspaceProvisioningStatus.NOT_PROVISIONED,
                created.getProvisioningStatus());
        assertEquals(List.of(created), manager.listUserWorkspaces("alice"));
    }

    private static UserWorkspace workspace(String id, String username) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(id);
        workspace.setUsername(username);
        workspace.setDisplayName(id);
        workspace.setCurrentBranch("draft");
        workspace.setBaseBranch("draft");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        workspace.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        return workspace;
    }
}
