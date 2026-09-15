package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchitectureRepositoryControllerLifecycleTest {
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final ArchitectureRepositoryProvisioningService provisioning = mock(ArchitectureRepositoryProvisioningService.class);
    private final RepositoryWorkspaceService workspaces = mock(RepositoryWorkspaceService.class);
    private final RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureRepositoryController controller =
            new ArchitectureRepositoryController(repositories, provisioning, workspaces, memberships, resolver);
    private final SystemRepository repository = new SystemRepository();
    private final ArchitectureRepositoryController.CreateRepositoryRequest create =
            new ArchitectureRepositoryController.CreateRepositoryRequest("Repository", "repo", "description",
                    RepositoryVisibility.PRIVATE, "draft");
    private final ArchitectureRepositoryController.CreateWorkspaceRequest copy =
            new ArchitectureRepositoryController.CreateWorkspaceRequest("Working copy", "description", "source");
    private final ArchitectureRepositoryController.CreateForkRequest fork =
            new ArchitectureRepositoryController.CreateForkRequest("Fork", "fork", "description",
                    RepositoryVisibility.PRIVATE, "source");

    @BeforeEach
    void setUp() {
        repository.setRepositoryId("repo-a");
        repository.setDisplayName("Repository");
        repository.setSlug("repo");
        repository.setOwnerId("alice");
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(repositories.getRepository("repo-a")).thenReturn(repository);
        when(memberships.canRead(repository, "alice")).thenReturn(true);
        when(memberships.canContribute(repository, "alice")).thenReturn(true);
    }

    @Test
    void listsOnlyVisibleRepositoriesAndReturnsPublicRepositoryMetadata() {
        SystemRepository hidden = new SystemRepository();
        hidden.setRepositoryId("hidden");
        when(repositories.listActiveRepositories()).thenReturn(List.of(repository, hidden));
        var listed = controller.listRepositories();
        assertEquals(200, listed.getStatusCode().value());
        assertEquals(1, listed.getBody().size());
        assertEquals("repo-a", listed.getBody().getFirst().get("repositoryId"));
        var details = controller.getRepository("repo-a");
        assertEquals(200, details.getStatusCode().value());
        assertEquals("alice", details.getBody().get("ownerId"));
        assertFalse(details.getBody().containsKey("externalAuthToken"));
    }

    @Test
    void creationForwardsOwnershipAndRequestedBranch() {
        when(provisioning.createRepository("Repository", "repo", "description",
                RepositoryVisibility.PRIVATE, "alice", "draft")).thenReturn(repository);
        assertEquals("repo-a", body(controller.createRepository(create), 200).get("repositoryId"));
        verify(provisioning).createRepository("Repository", "repo", "description",
                RepositoryVisibility.PRIVATE, "alice", "draft");
    }

    @Test
    void workingCopyResponseKeepsSourceAndCurrentVersionsDistinct() {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId("workspace-a");
        workspace.setDisplayName("Working copy");
        workspace.setSourceRepositoryId("repo-a");
        workspace.setSourceBranch("source");
        workspace.setCurrentBranch("working");
        workspace.setBaseCommit("base");
        workspace.setCurrentCommit("current");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        when(workspaces.createWorkingCopy("alice", "repo-a", "source", "Working copy", "description"))
                .thenReturn(workspace);
        var result = body(controller.createWorkspace("repo-a", copy), 200);
        assertEquals("workspace-a", result.get("workspaceId"));
        assertEquals("repo-a", result.get("sourceRepositoryId"));
        assertEquals("source", result.get("sourceBranch"));
        assertEquals("working", result.get("currentBranch"));
        assertEquals("base", result.get("baseCommit"));
        assertEquals("current", result.get("currentCommit"));
        assertEquals("READY", result.get("provisioningStatus"));
    }

    @Test
    void forkReturnsTheNewRepositoryRatherThanItsSource() {
        SystemRepository result = new SystemRepository();
        result.setRepositoryId("fork-a");
        result.setUpstreamRepositoryId("repo-a");
        when(provisioning.createFork("repo-a", "source", "Fork", "fork", "description",
                RepositoryVisibility.PRIVATE, "alice")).thenReturn(result);
        var response = body(controller.createFork("repo-a", fork), 200);
        assertEquals("fork-a", response.get("repositoryId"));
        assertEquals("repo-a", response.get("upstreamRepositoryId"));
    }

    @Test
    void hiddenRepositoryCannotBeReadCopiedOrForked() {
        when(memberships.canRead(repository, "alice")).thenReturn(false);
        assertEquals(404, controller.getRepository("repo-a").getStatusCode().value());
        assertEquals(404, controller.createWorkspace("repo-a", copy).getStatusCode().value());
        assertEquals(404, controller.createFork("repo-a", fork).getStatusCode().value());
        verifyNoInteractions(provisioning, workspaces);
        verify(memberships, never()).canContribute(any(), any());
    }

    @Test
    void missingRepositoryReturnsNotFound() {
        when(repositories.getRepository("repo-a")).thenThrow(new IllegalArgumentException("not found"));
        assertEquals(404, controller.getRepository("repo-a").getStatusCode().value());
    }

    @Test
    void provisioningFailuresDistinguishInvalidRequestsFromStorageFailures() {
        when(provisioning.createRepository(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid slug"), new IllegalStateException());
        assertEquals("invalid slug", body(controller.createRepository(create), 400).get("error"));
        assertEquals("IllegalStateException", body(controller.createRepository(create), 500).get("message"));
        when(provisioning.createFork(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid source"), new IllegalStateException("storage unavailable"));
        assertEquals("invalid source", body(controller.createFork("repo-a", fork), 400).get("error"));
        assertEquals("storage unavailable", body(controller.createFork("repo-a", fork), 500).get("message"));
        when(workspaces.createWorkingCopy(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("already exists"));
        assertEquals("already exists", body(controller.createWorkspace("repo-a", copy), 400).get("error"));
    }

    enum MembershipAction { LIST, UPDATE, REMOVE }

    @ParameterizedTest
    @EnumSource(MembershipAction.class)
    void membershipOperationsHideMissingRepositories(MembershipAction action) {
        when(repositories.getRepository("repo-a")).thenThrow(new IllegalArgumentException("not found"));
        assertEquals(404, membershipOperation(action).getStatusCode().value());
        verifyNoInteractions(memberships);
    }

    @ParameterizedTest
    @EnumSource(MembershipAction.class)
    void membershipOperationsRequireOwnership(MembershipAction action) {
        assertEquals(403, membershipOperation(action).getStatusCode().value());
        verify(memberships, never()).assignRole(any(SystemRepository.class), any(), any(), any());
        verify(memberships, never()).removeMembership(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = MembershipAction.class, names = {"UPDATE", "REMOVE"})
    void membershipServiceRejectionsAreNotReportedAsSuccess(MembershipAction action) {
        when(memberships.isOwner(repository, "alice")).thenReturn(true);
        when(memberships.assignRole(any(SystemRepository.class), any(), any(), any()))
                .thenThrow(new AccessDeniedException("denied"), new IllegalStateException("last owner"));
        doThrow(new AccessDeniedException("denied"), new IllegalStateException("last owner"))
                .when(memberships).removeMembership(any(), any(), any());
        assertEquals("denied", body(membershipOperation(action), 403).get("error"));
        assertEquals("last owner", body(membershipOperation(action), 400).get("error"));
    }

    private ResponseEntity<?> membershipOperation(MembershipAction action) {
        return switch (action) {
            case LIST -> controller.listMemberships("repo-a");
            case UPDATE -> controller.updateMembership("repo-a", "bob",
                    new ArchitectureRepositoryController.UpdateMembershipRequest(RepositoryRole.READER));
            case REMOVE -> controller.removeMembership("repo-a", "bob");
        };
    }

    private static Map<?, ?> body(ResponseEntity<?> response, int status) {
        assertEquals(status, response.getStatusCode().value());
        return assertInstanceOf(Map.class, response.getBody());
    }
}
