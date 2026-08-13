package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchitectureRepositoryControllerTest {

    private SystemRepositoryService repositoryService;
    private ArchitectureRepositoryProvisioningService provisioningService;
    private RepositoryWorkspaceService workspaceService;
    private RepositoryMembershipService membershipService;
    private WorkspaceResolver workspaceResolver;
    private ArchitectureRepositoryController controller;

    @BeforeEach
    void setUp() {
        repositoryService = mock(SystemRepositoryService.class);
        provisioningService = mock(ArchitectureRepositoryProvisioningService.class);
        workspaceService = mock(RepositoryWorkspaceService.class);
        membershipService = mock(RepositoryMembershipService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        controller = new ArchitectureRepositoryController(
                repositoryService,
                provisioningService,
                workspaceService,
                membershipService,
                workspaceResolver);
    }

    @Test
    void readerCannotCreateWorkingCopyWithoutContributorRole() {
        SystemRepository repository = repository();
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("reader");
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canRead(repository, "reader")).thenReturn(true);
        when(membershipService.canContribute(repository, "reader")).thenReturn(false);

        ResponseEntity<?> response = controller.createWorkspace(
                "repo-a",
                new ArchitectureRepositoryController.CreateWorkspaceRequest(
                        "Reader copy", null, "main"));

        assertEquals(403, response.getStatusCode().value());
        verify(workspaceService, never()).createWorkingCopy(
                any(), any(), any(), any(), any());
    }

    @Test
    void readerCannotCreateForkWithoutContributorRole() {
        SystemRepository repository = repository();
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("reader");
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canRead(repository, "reader")).thenReturn(true);
        when(membershipService.canContribute(repository, "reader")).thenReturn(false);

        ResponseEntity<?> response = controller.createFork(
                "repo-a",
                new ArchitectureRepositoryController.CreateForkRequest(
                        "Derived", "derived", null, RepositoryVisibility.PRIVATE, "main"));

        assertEquals(403, response.getStatusCode().value());
        verify(provisioningService, never()).createFork(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ownerCanAssignMembership() {
        SystemRepository repository = repository();
        RepositoryMembership membership = membership("bob", RepositoryRole.CONTRIBUTOR);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("owner");
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canRead(repository, "owner")).thenReturn(true);
        when(membershipService.isOwner(repository, "owner")).thenReturn(true);
        when(membershipService.assignRole(
                        repository, "bob", RepositoryRole.CONTRIBUTOR, "owner"))
                .thenReturn(membership);

        ResponseEntity<?> response = controller.updateMembership(
                "repo-a",
                "bob",
                new ArchitectureRepositoryController.UpdateMembershipRequest(
                        RepositoryRole.CONTRIBUTOR));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("bob", body.get("username"));
        assertEquals(RepositoryRole.CONTRIBUTOR, body.get("role"));
    }

    @Test
    void visibleRepositoryReaderCannotListMemberships() {
        SystemRepository repository = repository();
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("reader");
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canRead(repository, "reader")).thenReturn(true);
        when(membershipService.isOwner(repository, "reader")).thenReturn(false);

        ResponseEntity<?> response = controller.listMemberships("repo-a");

        assertEquals(403, response.getStatusCode().value());
        verify(membershipService, never()).listMemberships(repository, "reader");
    }

    @Test
    void hiddenRepositoryReturnsNotFoundBeforeMembershipDisclosure() {
        SystemRepository repository = repository();
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("outsider");
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canRead(repository, "outsider")).thenReturn(false);

        ResponseEntity<?> response = controller.updateMembership(
                "repo-a",
                "bob",
                new ArchitectureRepositoryController.UpdateMembershipRequest(
                        RepositoryRole.READER));

        assertEquals(404, response.getStatusCode().value());
        verify(membershipService, never()).isOwner(repository, "outsider");
    }

    @Test
    void ownerCanListAndRemoveMemberships() {
        SystemRepository repository = repository();
        RepositoryMembership reader = membership("reader", RepositoryRole.READER);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("owner");
        when(repositoryService.getRepository("repo-a")).thenReturn(repository);
        when(membershipService.canRead(repository, "owner")).thenReturn(true);
        when(membershipService.isOwner(repository, "owner")).thenReturn(true);
        when(membershipService.listMemberships(repository, "owner"))
                .thenReturn(List.of(reader));

        ResponseEntity<?> listResponse = controller.listMemberships("repo-a");
        ResponseEntity<?> deleteResponse = controller.removeMembership("repo-a", "reader");

        assertEquals(200, listResponse.getStatusCode().value());
        assertEquals(204, deleteResponse.getStatusCode().value());
        verify(membershipService).removeMembership(repository, "reader", "owner");
    }

    private static SystemRepository repository() {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("repo-a");
        repository.setOwnerType(RepositoryOwnerType.USER);
        repository.setOwnerId("owner");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        repository.setPrimaryRepo(false);
        return repository;
    }

    private static RepositoryMembership membership(
            String username, RepositoryRole role) {
        RepositoryMembership membership = new RepositoryMembership();
        membership.setRepositoryId("repo-a");
        membership.setUsername(username);
        membership.setRole(role);
        membership.setCreatedAt(Instant.parse("2026-08-09T12:00:00Z"));
        membership.setCreatedBy("owner");
        membership.setUpdatedAt(Instant.parse("2026-08-09T12:00:00Z"));
        return membership;
    }
}
