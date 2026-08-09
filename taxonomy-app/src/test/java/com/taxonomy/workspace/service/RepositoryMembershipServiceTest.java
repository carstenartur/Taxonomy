package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryMembershipServiceTest {

    private RepositoryMembershipRepository membershipRepository;
    private RepositoryMembershipService service;

    @BeforeEach
    void setUp() {
        membershipRepository = mock(RepositoryMembershipRepository.class);
        when(membershipRepository.save(any(RepositoryMembership.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new RepositoryMembershipService(membershipRepository);
    }

    @Test
    void assignOwnerCreatesExplicitOwnerMembership() {
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "alice"))
                .thenReturn(Optional.empty());

        RepositoryMembership membership = service.assignOwner("repo-a", "alice");

        assertEquals("repo-a", membership.getRepositoryId());
        assertEquals("alice", membership.getUsername());
        assertEquals(RepositoryRole.OWNER, membership.getRole());
        assertEquals("alice", membership.getCreatedBy());
        assertNotNull(membership.getCreatedAt());
        assertNotNull(membership.getUpdatedAt());
        verify(membershipRepository).save(membership);
    }

    @Test
    void assignOwnerUpgradesExistingMembershipIdempotently() {
        RepositoryMembership existing = membership(
                "repo-a", "alice", RepositoryRole.READER);
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "alice"))
                .thenReturn(Optional.of(existing));

        RepositoryMembership result = service.assignOwner("repo-a", "alice");

        assertEquals(RepositoryRole.OWNER, result.getRole());
        assertEquals("creator", result.getCreatedBy());
        verify(membershipRepository).save(existing);
    }

    @Test
    void activePublicAndPrimaryRepositoriesRemainReadableWithoutMembership() {
        SystemRepository publicRepository = repository(
                RepositoryVisibility.PUBLIC, RepositoryLifecycleState.ACTIVE, false);
        SystemRepository primaryRepository = repository(
                RepositoryVisibility.ORGANIZATION, RepositoryLifecycleState.ACTIVE, true);

        assertTrue(service.canRead(publicRepository, "anonymous-user"));
        assertTrue(service.canRead(primaryRepository, "anonymous-user"));
        verify(membershipRepository, never())
                .findByRepositoryIdAndUsername(any(), any());
    }

    @Test
    void organizationAndPrivateRepositoriesRequireOwnerOrMembership() {
        SystemRepository organization = repository(
                RepositoryVisibility.ORGANIZATION, RepositoryLifecycleState.ACTIVE, false);
        SystemRepository privateRepository = repository(
                RepositoryVisibility.PRIVATE, RepositoryLifecycleState.ACTIVE, false);
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "reader"))
                .thenReturn(Optional.of(membership(
                        "repo-a", "reader", RepositoryRole.READER)));
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "outsider"))
                .thenReturn(Optional.empty());

        assertTrue(service.canRead(organization, "owner"));
        assertTrue(service.canRead(organization, "reader"));
        assertTrue(service.canRead(privateRepository, "reader"));
        assertFalse(service.canRead(organization, "outsider"));
        assertFalse(service.canRead(privateRepository, "outsider"));
    }

    @Test
    void repositoryRolesGrantOnlyTheirDeclaredCapabilityLevel() {
        SystemRepository repository = repository(
                RepositoryVisibility.PRIVATE, RepositoryLifecycleState.ACTIVE, false);
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "reader"))
                .thenReturn(Optional.of(membership(
                        "repo-a", "reader", RepositoryRole.READER)));
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "contributor"))
                .thenReturn(Optional.of(membership(
                        "repo-a", "contributor", RepositoryRole.CONTRIBUTOR)));
        when(membershipRepository.findByRepositoryIdAndUsername("repo-a", "maintainer"))
                .thenReturn(Optional.of(membership(
                        "repo-a", "maintainer", RepositoryRole.MAINTAINER)));

        assertTrue(service.canRead(repository, "reader"));
        assertFalse(service.canContribute(repository, "reader"));
        assertTrue(service.canContribute(repository, "contributor"));
        assertFalse(service.canMaintain(repository, "contributor"));
        assertTrue(service.canMaintain(repository, "maintainer"));
        assertFalse(service.isOwner(repository, "maintainer"));
        assertTrue(service.isOwner(repository, "owner"));
    }

    @Test
    void inactiveRepositoriesAreNeverReadableOrMutable() {
        SystemRepository failed = repository(
                RepositoryVisibility.PUBLIC, RepositoryLifecycleState.FAILED, true);

        assertFalse(service.canRead(failed, "owner"));
        assertFalse(service.canContribute(failed, "owner"));
        assertFalse(service.canMaintain(failed, "owner"));
        assertFalse(service.isOwner(failed, "owner"));
    }

    private static SystemRepository repository(
            RepositoryVisibility visibility,
            RepositoryLifecycleState lifecycleState,
            boolean primary) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId("repo-a");
        repository.setVisibility(visibility);
        repository.setLifecycleState(lifecycleState);
        repository.setOwnerId("owner");
        repository.setPrimaryRepo(primary);
        return repository;
    }

    private static RepositoryMembership membership(
            String repositoryId,
            String username,
            RepositoryRole role) {
        RepositoryMembership membership = new RepositoryMembership();
        membership.setRepositoryId(repositoryId);
        membership.setUsername(username);
        membership.setRole(role);
        membership.setCreatedBy("creator");
        return membership;
    }
}
