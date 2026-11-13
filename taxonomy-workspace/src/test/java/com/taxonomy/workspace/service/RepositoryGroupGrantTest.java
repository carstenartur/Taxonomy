package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepositoryGroupGrantTest {
    private RepositoryMembershipRepository memberships;
    private RepositoryMembershipService service;
    private RepositoryRole grant;
    private SystemRepository repository;

    @BeforeEach
    void setUp() {
        memberships = mock(RepositoryMembershipRepository.class);
        when(memberships.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new RepositoryMembershipService(memberships, Optional.of((repositoryId, actor) ->
                "repo-a".equals(repositoryId) && "alice".equals(actor)
                        ? Optional.ofNullable(grant) : Optional.empty()));
        repository = new SystemRepository();
        repository.setRepositoryId("repo-a");
        repository.setLifecycleState(RepositoryLifecycleState.ACTIVE);
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setOwnerType(RepositoryOwnerType.ORGANIZATION);
        repository.setOwnerId("organization");
    }

    @Test
    void readerGrantAllowsReadWithoutContribution() {
        grant = RepositoryRole.READER;
        assertTrue(service.canRead(repository, "alice"));
        assertFalse(service.canContribute(repository, "alice"));
        assertFalse(service.canMaintain(repository, "alice"));
        assertFalse(service.isOwner(repository, "alice"));
    }

    @Test
    void grantsDoNotApplyToOtherRepositoriesOrActorsOrNormalizedActors() {
        grant = RepositoryRole.OWNER;
        assertFalse(service.canRead(repository, "bob"));
        assertFalse(service.canRead(repository, " alice "));
        repository.setRepositoryId("repo-b");
        assertFalse(service.canRead(repository, "alice"));
    }

    @Test
    void grantsAndStoredMembershipUseTheirMaximumCapability() {
        grant = RepositoryRole.READER;
        when(memberships.findByRepositoryIdAndUsername("repo-a", "alice"))
                .thenReturn(Optional.of(member("alice", RepositoryRole.MAINTAINER)));
        assertTrue(service.canMaintain(repository, "alice"));
        assertFalse(service.isOwner(repository, "alice"));
        grant = RepositoryRole.OWNER;
        assertTrue(service.isOwner(repository, "alice"));
    }

    @Test
    void trustedOwnerCanAssignRemoveAndListMemberships() {
        grant = RepositoryRole.OWNER;
        var reader = member("bob", RepositoryRole.READER);
        when(memberships.findByRepositoryIdForUpdate("repo-a")).thenReturn(List.of(reader));
        when(memberships.findByRepositoryIdOrderByUsernameAsc("repo-a")).thenReturn(List.of(reader));
        assertEquals(RepositoryRole.CONTRIBUTOR,
                service.assignRole(repository, "charlie", RepositoryRole.CONTRIBUTOR, "alice").getRole());
        assertEquals(List.of(reader), service.listMemberships(repository, "alice"));
        service.removeMembership(repository, "bob", "alice");
        assertFalse(reader.isActive());
        verify(memberships).save(reader);
    }

    @Test
    void trustedOwnerCannotRemoveOrDemoteLastPersistedOwner() {
        grant = RepositoryRole.OWNER;
        when(memberships.findByRepositoryIdForUpdate("repo-a"))
                .thenReturn(List.of(member("bob", RepositoryRole.OWNER)));
        assertThrows(IllegalStateException.class, () -> service.removeMembership(repository, "bob", "alice"));
        assertThrows(IllegalStateException.class, () ->
                service.assignRole(repository, "bob", RepositoryRole.READER, "alice"));
        verify(memberships, never()).delete(any());
        verify(memberships, never()).save(any());
    }

    @Test
    void trustedOwnerMayDemoteOneOfTwoPersistedOwners() {
        grant = RepositoryRole.OWNER;
        when(memberships.findByRepositoryIdForUpdate("repo-a")).thenReturn(List.of(
                member("bob", RepositoryRole.OWNER), member("charlie", RepositoryRole.OWNER)));
        assertEquals(RepositoryRole.MAINTAINER,
                service.assignRole(repository, "bob", RepositoryRole.MAINTAINER, "alice").getRole());
    }

    @Test
    void ownerGrantDoesNotBypassUserCatalogOwnerProtection() {
        grant = RepositoryRole.OWNER;
        repository.setOwnerType(RepositoryOwnerType.USER);
        repository.setOwnerId("bob");
        when(memberships.findByRepositoryIdForUpdate("repo-a")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.removeMembership(repository, "bob", "alice"));
        assertThrows(IllegalArgumentException.class, () ->
                service.assignRole(repository, "bob", RepositoryRole.READER, "alice"));
    }

    @Test
    void grantsCannotAuthorizeMembershipMutationsForAnotherOrNormalizedActor() {
        grant = RepositoryRole.OWNER;
        when(memberships.findByRepositoryIdForUpdate("repo-a")).thenReturn(List.of());
        assertThrows(AccessDeniedException.class, () ->
                service.assignRole(repository, "bob", RepositoryRole.READER, "mallory"));
        assertThrows(AccessDeniedException.class, () ->
                service.assignRole(repository, "bob", RepositoryRole.READER, " alice "));
        assertThrows(AccessDeniedException.class, () -> service.listMemberships(repository, "mallory"));
    }

    @Test
    void inactiveRepositoryRejectsEveryGrantedCapabilityAndOwnerOperation() {
        grant = RepositoryRole.OWNER;
        repository.setLifecycleState(RepositoryLifecycleState.FAILED);
        assertFalse(service.canRead(repository, "alice"));
        assertFalse(service.canContribute(repository, "alice"));
        assertFalse(service.canMaintain(repository, "alice"));
        assertFalse(service.isOwner(repository, "alice"));
        assertThrows(AccessDeniedException.class, () ->
                service.assignRole(repository, "bob", RepositoryRole.READER, "alice"));
        assertThrows(AccessDeniedException.class, () -> service.removeMembership(repository, "bob", "alice"));
        assertThrows(AccessDeniedException.class, () -> service.listMemberships(repository, "alice"));
    }

    private static RepositoryMembership member(String actor, RepositoryRole role) {
        var member = new RepositoryMembership();
        member.setRepositoryId("repo-a");
        member.setUsername(actor);
        member.setRole(role);
        return member;
    }
}
