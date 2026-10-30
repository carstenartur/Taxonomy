package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceSourceDiscoveryServiceTest {
    private final SystemRepositoryRepository repositories=mock(SystemRepositoryRepository.class);
    private final UserWorkspaceRepository workspaces=mock(UserWorkspaceRepository.class);
    private final RepositoryMembershipService membership=mock(RepositoryMembershipService.class);
    private final WorkspaceSourceDiscoveryService service=new WorkspaceSourceDiscoveryService(repositories,membership,workspaces);

    @Test void discoveryIsBoundedAuthorizedAndReturnsImmutableMetadata() {
        var readable=repository("readable");
        var denied=repository("denied");
        var archived=repository("archived");
        archived.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
        when(membership.canRead(readable,"alice")).thenReturn(true);
        when(membership.canRead(archived,"alice")).thenReturn(true);
        when(repositories.findByLifecycleStateOrderByDisplayNameAsc(eq(RepositoryLifecycleState.ACTIVE),any()))
                .thenReturn(new SliceImpl<>(List.of(readable,denied,archived),PageRequest.of(0,16),true));
        var result=service.readableSources("alice");
        readable.setDisplayName("Changed after capture");
        assertThat(result.truncated()).isTrue();
        assertThat(result.sources()).containsExactly(new WorkspaceSourceDiscoveryService.Source("readable","draft","readable title","readable description"));
        assertThatThrownBy(()->result.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(repositories).findByLifecycleStateOrderByDisplayNameAsc(RepositoryLifecycleState.ACTIVE,PageRequest.of(0,16));
        verifyNoMoreInteractions(repositories);
    }

    @Test void unreadableFirstPageRemainsPartialWithoutUnboundedScanning() {
        when(repositories.findByLifecycleStateOrderByDisplayNameAsc(eq(RepositoryLifecycleState.ACTIVE),any()))
                .thenReturn(new SliceImpl<>(List.of(repository("private")),PageRequest.of(0,16),true));
        var result=service.readableSources("alice");
        assertThat(result.sources()).isEmpty();assertThat(result.truncated()).isTrue();
        verify(repositories).findByLifecycleStateOrderByDisplayNameAsc(RepositoryLifecycleState.ACTIVE,PageRequest.of(0,16));
        verifyNoMoreInteractions(repositories);
    }

    @ParameterizedTest @ValueSource(strings={"owner","source","branch","shared","archived","provisioning"})
    void exactPrivateWorkspaceAuthorityIsRequired(String changed) {
        var repository=repository("repo");
        when(repositories.findByRepositoryId("repo")).thenReturn(Optional.of(repository));
        when(membership.canRead(repository,"alice")).thenReturn(true);
        var workspace=new UserWorkspace();workspace.setUsername("alice");workspace.setSourceRepositoryId("repo");
        workspace.setCurrentBranch("draft");
        when(workspaces.findByWorkspaceId("work")).thenReturn(Optional.of(workspace));
        var context=new WorkspaceContext("alice","work","draft","repo");
        assertThat(service.canReadContext("alice",context)).isTrue();
        switch(changed) {
            case "owner" -> workspace.setUsername("other");
            case "source" -> workspace.setSourceRepositoryId("other");
            case "branch" -> workspace.setCurrentBranch("other");
            case "shared" -> workspace.setShared(true);
            case "archived" -> workspace.setArchived(true);
            case "provisioning" -> workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
        }
        assertThat(service.canReadContext("alice",context)).isFalse();
    }

    private SystemRepository repository(String id) {
        var repository=new SystemRepository();repository.setRepositoryId(id);
        repository.setDisplayName(id+" title");repository.setDescription(id+" description");
        return repository;
    }
}
