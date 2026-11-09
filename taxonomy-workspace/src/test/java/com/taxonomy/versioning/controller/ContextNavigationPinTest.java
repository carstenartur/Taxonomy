package com.taxonomy.versioning.controller;

import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.TransferSelection;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.versioning.service.SelectiveTransferService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextNavigationPinTest {
    private static final String USER = "alice";
    private static final String TARGET_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }
            element CP-1023 type Capability {
              title: "Working model";
            }
            """;
    private static final String INCOMING_ELEMENT = """
            element CP-1024 type Capability {
              title: "Incoming model";
            }
            """;
    private final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
    private final DslGitRepositoryFactory factory = new DslGitRepositoryFactory(null);
    private final WorkspaceManager manager = new WorkspaceManager(rows, 50, repositories, factory);
    private final WorkspaceContextResolver contextResolver = new WorkspaceContextResolver(
            manager, repositories, rows, memberships);
    private final WorkspaceResolver resolver = new WorkspaceResolver(contextResolver);
    private final RepositoryStateService state = new RepositoryStateService(factory, manager, repositories);
    private final ContextNavigationService navigation = new ContextNavigationService(factory, state, manager, 50);
    private final ContextNavigationController controller = new ContextNavigationController(
            navigation, new ContextCompareService(factory), new SelectiveTransferService(factory, navigation, resolver), resolver);

    @BeforeEach
    void authenticate() {
        authenticateAs(USER);
        when(repositories.getPrimaryRepository()).thenReturn(central("repo-a", "main"));
    }

    @AfterEach
    void clearRequest() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void earlierTabKeepsItsContextAndHistoryAfterAnotherWorkspaceBecomesActive() throws IOException {
        DslGitRepository oldRepo = workspace("older", USER, "repo-a");
        DslGitRepository newRepo = workspace("newer", USER, "repo-b");
        String oldCommit = oldRepo.commitDsl("review", "old source", USER, "old source");
        String newCommit = newRepo.commitDsl("review", "new source", USER, "new source");

        manager.switchWorkspace(USER, "older");
        pin("older");
        var original = controller.openContext("review", null, false, null, null).getBody();
        assertEquals(oldCommit, original.commitId());
        manager.switchWorkspace(USER, "newer");
        pin("newer");
        var other = controller.openContext("review", null, false, null, null).getBody();
        assertEquals(newCommit, other.commitId());

        pin("older");
        assertEquals(original, controller.getCurrentContext().getBody());
        var readOnly = controller.openContext("review", oldCommit, true, null, null).getBody();
        assertEquals(original.contextId(), readOnly.originContextId());
        var guard = new RepositoryStateGuard(state, navigation);
        assertFalse(guard.checkWriteOperation(resolver.resolveCurrentContext(), "commit").allowed());
        assertEquals(oldCommit, controller.back().getBody().commitId());
        assertEquals(1, controller.getHistory().getBody().size());
        controller.openContext("review", oldCommit, true, null, null);
        assertEquals(oldCommit, controller.returnToOrigin().getBody().commitId());
        assertNotEquals(oldCommit, oldRepo.commitDsl("review", "moved source", USER, "moved source"));
        assertEquals("from-old-tab", controller.createVariant("from-old-tab").getBody().get("branch"));
        assertEquals(oldCommit, oldRepo.getHeadCommit("from-old-tab"));
        assertFalse(newRepo.getBranchNames().contains("from-old-tab"));

        pin("newer");
        assertEquals(other, controller.getCurrentContext().getBody());
        assertTrue(guard.checkWriteOperation(resolver.resolveCurrentContext(), "commit").allowed());
        assertEquals(1, controller.getHistory().getBody().size());
        assertEquals("newer", manager.findActiveWorkspace(USER).getWorkspaceId());
    }

    @Test
    void foreignWorkspacePinsFailBeforeAnyNavigationStateChanges() {
        workspace("available", USER, "repo-a");
        workspace("foreign", "bob", "repo-b");
        manager.switchWorkspace(USER, "available");
        var before = manager.getWorkspace(USER).getCurrentContext();

        pin("foreign");
        assertAll(
                () -> assertThrows(AccessDeniedException.class, controller::getCurrentContext),
                () -> assertThrows(AccessDeniedException.class, controller::getHistory),
                () -> assertThrows(AccessDeniedException.class, controller::back),
                () -> assertThrows(AccessDeniedException.class, controller::returnToOrigin),
                () -> assertThrows(AccessDeniedException.class,
                        () -> controller.openContext("review", "other-commit", false, null, null)),
                () -> assertThrows(AccessDeniedException.class, () -> controller.createVariant("not-mine")));
        assertEquals(before, manager.getWorkspace(USER).getCurrentContext());
    }

    @Test
    void explicitCentralReadDoesNotBorrowAnActivePrivateWorkspace() {
        workspace("private", USER, "repo-a");
        manager.switchWorkspace(USER, "private");
        var before = manager.getWorkspace(USER).getCurrentContext();
        pin("");

        var central = controller.getCurrentContext().getBody();
        assertEquals(ContextMode.READ_ONLY, central.mode());
        assertEquals("main", central.branch());
        assertNotEquals(before.contextId(), central.contextId());
        assertEquals(List.of(), controller.getHistory().getBody());
        assertThrows(AccessDeniedException.class,
                () -> controller.openContext("review", null, false, null, null));
        assertEquals(before, manager.getWorkspace(USER).getCurrentContext());
    }

    @Test
    void collaboratorsKeepSeparateNavigationOnTheSameWorkspace() throws IOException {
        DslGitRepository shared = workspace("shared-copy", USER, "repo-a");
        rows.findByWorkspaceId("shared-copy").orElseThrow().setCollaborative(true);
        when(memberships.canCollaborate(any(), eq("bob"))).thenReturn(true);
        contextResolver.setActorAccess(actor -> {});
        String commit = shared.commitDsl("review", "shared source", USER, "shared source");
        pin("shared-copy");
        var owner = controller.openContext("review", commit, true, "owner's search", null).getBody();

        authenticateAs("bob");
        pin("shared-copy");
        var collaborator = controller.openContext("review", commit, false, null, null).getBody();
        assertNotEquals(owner.contextId(), collaborator.originContextId());
        assertEquals(ContextMode.EDITABLE, collaborator.mode());
        assertEquals(1, controller.getHistory().getBody().size());

        authenticateAs(USER);
        pin("shared-copy");
        assertEquals(owner, controller.getCurrentContext().getBody());
        assertEquals(1, controller.getHistory().getBody().size());
    }

    @Test
    void copyBackFromReadOnlyWritesOnlyToThePinnedWorkspacesEditableOrigin() throws IOException {
        DslGitRepository older = workspace("older", USER, "repo-a");
        DslGitRepository newer = workspace("newer", USER, "repo-b");
        String source = older.commitDsl("historical", TARGET_DSL + INCOMING_ELEMENT, USER, "Historical source");
        String origin = older.commitDsl("working", TARGET_DSL, USER, "Editable origin");
        String other = newer.commitDsl("other-tab", TARGET_DSL, USER, "Other workspace");
        manager.switchWorkspace(USER, "older");
        pin("older");
        controller.openContext("working", origin, false, null, null);
        var historical = controller.openContext("historical", source, true, null, null).getBody();
        manager.switchWorkspace(USER, "newer");
        pin("newer");
        controller.openContext("other-tab", other, false, null, null);

        pin("older");
        var result = controller.applyTransfer(selection(source, origin), historical.contextId());

        assertEquals(200, result.getStatusCode().value());
        assertEquals(result.getBody().get("commitId"), older.getHeadCommit("working"));
        assertTrue(older.getDslAtHead("working").contains("Incoming model"));
        assertEquals(source, older.getHeadCommit("historical"));
        assertNull(older.getHeadCommit("other-tab"));
        assertEquals(other, newer.getHeadCommit("other-tab"));
    }

    @Test
    void copyBackCannotOverwriteNewEditsAfterPreview() throws IOException {
        DslGitRepository git = workspace("selected", USER, "repo-a");
        manager.switchWorkspace(USER, "selected");
        pin("selected");
        String source = git.commitDsl("historical", TARGET_DSL + INCOMING_ELEMENT, USER, "Historical source");
        String origin = git.commitDsl("working", TARGET_DSL, USER, "Editable origin");
        controller.openContext("working", origin, false, null, null);
        var transfer = selection(source, origin);
        assertEquals(200, controller.previewTransfer(transfer).getStatusCode().value());
        String newDsl = TARGET_DSL.replace("Working model", "Concurrent edit");
        String moved = git.commitDsl("working", newDsl, USER, "Independent edit");

        var result = controller.applyTransfer(transfer, null);

        assertEquals(409, result.getStatusCode().value());
        assertEquals(moved, git.getHeadCommit("working"));
        assertEquals(newDsl, git.getDslAtHead("working"));
        assertEquals(source, git.getHeadCommit("historical"));
    }

    @Test
    void nestedReadOnlySelectionCannotTreatItsReadOnlyOriginAsEditable() throws IOException {
        DslGitRepository git = workspace("selected", USER, "repo-a");
        manager.switchWorkspace(USER, "selected");
        pin("selected");
        String first = git.commitDsl("first-read", TARGET_DSL, USER, "First read-only snapshot");
        String second = git.commitDsl("second-read", TARGET_DSL + INCOMING_ELEMENT, USER, "Second read-only snapshot");
        controller.openContext("first-read", first, true, null, null);
        controller.openContext("second-read", second, true, null, null);

        assertThrows(AccessDeniedException.class, () -> controller.applyTransfer(selection(second, first), null));
        assertEquals(first, git.getHeadCommit("first-read"));
        assertEquals(second, git.getHeadCommit("second-read"));
    }

    @Test
    void oldDialogCannotRetargetAnotherBranchWithTheSameHead() throws IOException {
        DslGitRepository git = workspace("selected", USER, "repo-a");
        manager.switchWorkspace(USER, "selected");
        pin("selected");
        String source = git.commitDsl("historical", TARGET_DSL + INCOMING_ELEMENT, USER, "Historical source");
        String head = git.commitDsl("working", TARGET_DSL, USER, "Editable origin");
        git.createBranchAtCommit("other", head);
        var first = controller.openContext("working", head, false, null, null).getBody();
        controller.openContext("other", head, false, null, null);

        var conflict = assertThrows(ResponseStatusException.class,
                () -> controller.applyTransfer(selection(source, head), first.contextId()));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(head, git.getHeadCommit("working"));
        assertEquals(head, git.getHeadCommit("other"));
    }

    private static TransferSelection selection(String source, String target) {
        return new TransferSelection(source, target, Set.of("CP-1024"), Set.of(), TransferSelection.TransferMode.COPY);
    }

    private static void authenticateAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, "", List.of()));
    }

    private DslGitRepository workspace(String workspaceId, String owner, String repositoryId) {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setUsername(owner);
        workspace.setSourceRepositoryId(repositoryId);
        workspace.setCurrentBranch("review");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        when(rows.findByWorkspaceId(workspaceId)).thenReturn(Optional.of(workspace));
        when(repositories.getRepository(repositoryId)).thenReturn(central(repositoryId, "main"));
        return factory.getWorkspaceRepository(workspaceId);
    }

    private static SystemRepository central(String id, String branch) {
        var repository = new SystemRepository();
        repository.setRepositoryId(id);
        repository.setDefaultBranch(branch);
        return repository;
    }

    private static void pin(String workspaceId) {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, workspaceId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
