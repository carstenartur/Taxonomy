package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceBranchDeletionTest {
    private final DslGitRepositoryFactory stores = mock(DslGitRepositoryFactory.class);
    private final DslGitRepository git = mock(DslGitRepository.class);
    private final RepositoryMembershipService members = mock(RepositoryMembershipService.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
    private final WorkspaceBranchDeletion.Actor actor = mock(WorkspaceBranchDeletion.Actor.class);
    private final WorkspaceBranchDeletion service = new WorkspaceBranchDeletion(rows, repositories, members, stores, actor);
    private final RepositoryContext selected = RepositoryContext.workspace("repo", "copy", "draft", "ann");
    private final SystemRepository repo = new SystemRepository();
    private final UserWorkspace copy = new UserWorkspace();
    private final String head = "a".repeat(40);

    @BeforeEach void setup() throws Exception {
        copy.setWorkspaceId("copy"); copy.setCurrentBranch("draft"); copy.setUsername("ann");
        copy.setSourceRepositoryId("repo");
        when(rows.findByWorkspaceId("copy")).thenReturn(Optional.of(copy));
        when(rows.findByWorkspaceIdForUpdate("copy")).thenReturn(Optional.of(copy));
        when(repositories.getRepository("repo")).thenReturn(repo);
        when(members.canUseWorkspace(repo, copy, "ann")).thenReturn(true);
        when(members.canContribute(repo, "ann")).thenReturn(true);
        when(actor.require("ann")).thenReturn("stable-actor");
        when(stores.resolveRepository(selected)).thenReturn(git);
        when(git.getHeadCommit("alternative")).thenReturn(head);
    }

    @Test void confirmedDeletionUsesReviewedHeadAndVerifiedActor() throws Exception {
        var receipt = service.preview(selected, "alternative");
        assertThat(receipt.head()).isEqualTo(head);
        service.delete(selected, "alternative", receipt.etag());
        verify(repositories).lock("repo");
        verify(rows).findByWorkspaceIdForUpdate("copy");
        verify(git).deleteBranch("alternative", head, "stable-actor");
    }

    @Test void centralAndRevokedAuthorityCannotReachTheRef() throws Exception {
        assertThatThrownBy(() -> service.delete(RepositoryContext.centralRead("repo","draft","ann"),"alternative",null))
                .isInstanceOf(AccessDeniedException.class);
        var receipt = service.preview(selected, "alternative");
        when(members.canContribute(repo, copy.getUsername())).thenReturn(false);
        assertThatThrownBy(() -> service.delete(selected,"alternative",receipt.etag())).isInstanceOf(AccessDeniedException.class);
        verify(git, never()).deleteBranch(anyString(), anyString(), anyString());
    }

    @Test void identicalHeadForAnotherBranchCannotReuseTheReview() throws Exception {
        var receipt = service.preview(selected, "alternative");
        assertThatThrownBy(() -> service.delete(selected, "other-branch", receipt.etag()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(412));
        verify(git, never()).deleteBranch(anyString(), anyString(), anyString());
    }

    @Test void identicalHeadAndBranchOnAnotherCopyCannotReuseTheReview() throws Exception {
        var receipt = service.preview(selected, "alternative");
        var other = RepositoryContext.workspace("repo", "other-copy", "draft", "ann");
        var second = new UserWorkspace();
        second.setWorkspaceId("other-copy"); second.setUsername("ann");
        second.setSourceRepositoryId("repo"); second.setCurrentBranch("draft");
        when(rows.findByWorkspaceIdForUpdate("other-copy")).thenReturn(Optional.of(second));
        when(members.canUseWorkspace(repo, second, "ann")).thenReturn(true);
        assertThatThrownBy(() -> service.delete(other, "alternative", receipt.etag()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(412));
        verify(git, never()).deleteBranch(anyString(), anyString(), anyString());
    }

    @Test void sharedMaintainerMustHoldFreshDeletePermission() throws Exception {
        copy.setUsername("owner"); copy.setCollaborative(true);
        when(members.canMaintain(repo, "ann")).thenReturn(true);
        var receipt = service.preview(selected, "alternative");
        when(actor.require("ann")).thenThrow(new AccessDeniedException("Permission revoked"));
        assertThatThrownBy(() -> service.delete(selected, "alternative", receipt.etag())).isInstanceOf(AccessDeniedException.class);
        verify(git, never()).deleteBranch(anyString(), anyString(), anyString());
    }

    @Test void missingAndWildcardPreconditionsCannotReachTheRef() {
        assertThatThrownBy(() -> service.delete(selected,"alternative",null))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(428));
        assertThatThrownBy(() -> service.delete(selected,"alternative","*"))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(git);
    }

    @Test void ordinarySharedEditingDoesNotGrantDeletion() {
        copy.setUsername("owner");
        assertThatThrownBy(() -> service.preview(selected, "alternative")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(git);
    }
}
