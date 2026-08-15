package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceAccessServiceTest {

    @Mock
    private UserWorkspaceRepository workspaceRepository;

    @InjectMocks
    private WorkspaceAccessService workspaceAccessService;

    @Test
    void rejectsMissingIdentityWithoutRepositoryLookup() {
        assertThat(workspaceAccessService.canReadWorkspaceMetadata(null, "alice"))
                .isFalse();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata("", "alice"))
                .isFalse();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata("workspace", null))
                .isFalse();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata("workspace", " "))
                .isFalse();
        verify(workspaceRepository, never()).findByWorkspaceId("workspace");
    }

    @Test
    void ownerAndSharedWorkspaceAreVisible() {
        when(workspaceRepository.findByWorkspaceId("owned"))
                .thenReturn(Optional.of(workspace("alice", false)));
        when(workspaceRepository.findByWorkspaceId("shared"))
                .thenReturn(Optional.of(workspace("system", true)));

        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "owned", "alice")).isTrue();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "shared", "alice")).isTrue();
    }

    @Test
    void foreignPrivateAndMissingWorkspaceAreHidden() {
        when(workspaceRepository.findByWorkspaceId("foreign"))
                .thenReturn(Optional.of(workspace("bob", false)));
        when(workspaceRepository.findByWorkspaceId("missing"))
                .thenReturn(Optional.empty());

        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "foreign", "alice")).isFalse();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "missing", "alice")).isFalse();
    }

    private static UserWorkspace workspace(String username, boolean shared) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setShared(shared);
        return workspace;
    }
}
