package com.taxonomy.workspace.service;

import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceAccessServiceTest {

    @Mock
    private UserWorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceActorAccess actors;

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
        verifyNoInteractions(workspaceRepository);
    }

    @Test
    void ownerAndSharedWorkspaceAreVisibleWithoutMaterializingRows() {
        when(workspaceRepository.countVisibleWorkspaceMetadata("owned", "alice", null))
                .thenReturn(1L);
        when(workspaceRepository.countVisibleWorkspaceMetadata("shared", "alice", null))
                .thenReturn(1L);

        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                " owned ", " alice ")).isTrue();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "shared", "alice")).isTrue();

        verify(workspaceRepository, never()).findByWorkspaceId("owned");
        verify(workspaceRepository, never()).findByWorkspaceId("shared");
    }

    @Test
    void foreignPrivateAndMissingWorkspaceAreIndistinguishable() {
        when(workspaceRepository.countVisibleWorkspaceMetadata("foreign", "alice", null))
                .thenReturn(0L);
        when(workspaceRepository.countVisibleWorkspaceMetadata("missing", "alice", null))
                .thenReturn(0L);

        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "foreign", "alice")).isFalse();
        assertThat(workspaceAccessService.canReadWorkspaceMetadata(
                "missing", "alice")).isFalse();

        verify(workspaceRepository, never()).findByWorkspaceId("foreign");
        verify(workspaceRepository, never()).findByWorkspaceId("missing");
    }
}
