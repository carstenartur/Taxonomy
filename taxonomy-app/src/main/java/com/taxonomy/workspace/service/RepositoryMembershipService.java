package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Evaluates and persists repository-scoped memberships independently of global app roles. */
@Service
public class RepositoryMembershipService {

    private final RepositoryMembershipRepository membershipRepository;

    public RepositoryMembershipService(RepositoryMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /** Assign the repository creator as its explicit owner, idempotently. */
    @Transactional
    public RepositoryMembership assignOwner(String repositoryId, String username) {
        return assignRole(repositoryId, username, RepositoryRole.OWNER, username);
    }

    /**
     * Persist a role assignment. Authorization of the actor belongs to the calling API/service;
     * this method owns validation and idempotent persistence only.
     */
    @Transactional
    public RepositoryMembership assignRole(
            String repositoryId,
            String username,
            RepositoryRole role,
            String actor) {
        String repository = requireText(repositoryId, "repositoryId");
        String member = requireText(username, "username");
        String changedBy = requireText(actor, "actor");
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }

        Instant now = Instant.now();
        RepositoryMembership membership = membershipRepository
                .findByRepositoryIdAndUsername(repository, member)
                .orElseGet(() -> {
                    RepositoryMembership created = new RepositoryMembership();
                    created.setRepositoryId(repository);
                    created.setUsername(member);
                    created.setCreatedAt(now);
                    created.setCreatedBy(changedBy);
                    return created;
                });
        membership.setRole(role);
        membership.setUpdatedAt(now);
        return membershipRepository.save(membership);
    }

    public Optional<RepositoryRole> effectiveRole(
            SystemRepository repository, String username) {
        if (repository == null || username == null || username.isBlank()) {
            return Optional.empty();
        }
        String member = username.strip();
        if (member.equals(repository.getOwnerId())) {
            return Optional.of(RepositoryRole.OWNER);
        }
        return membershipRepository.findByRepositoryIdAndUsername(
                        repository.getRepositoryId(), member)
                .map(RepositoryMembership::getRole);
    }

    /**
     * Fail-closed read decision. Public repositories and the historic primary repository are
     * readable without an explicit membership; all other visibility modes require membership.
     */
    public boolean canRead(SystemRepository repository, String username) {
        if (repository == null
                || repository.getLifecycleState() != RepositoryLifecycleState.ACTIVE) {
            return false;
        }
        if (repository.isPrimaryRepo()
                || repository.getVisibility() == RepositoryVisibility.PUBLIC) {
            return true;
        }
        return effectiveRole(repository, username)
                .map(role -> role.grants(RepositoryRole.READER))
                .orElse(false);
    }

    public boolean canContribute(SystemRepository repository, String username) {
        return isActive(repository)
                && effectiveRole(repository, username)
                .map(role -> role.grants(RepositoryRole.CONTRIBUTOR))
                .orElse(false);
    }

    public boolean canMaintain(SystemRepository repository, String username) {
        return isActive(repository)
                && effectiveRole(repository, username)
                .map(role -> role.grants(RepositoryRole.MAINTAINER))
                .orElse(false);
    }

    public boolean isOwner(SystemRepository repository, String username) {
        return isActive(repository)
                && effectiveRole(repository, username)
                .map(role -> role.grants(RepositoryRole.OWNER))
                .orElse(false);
    }

    public List<RepositoryMembership> listMemberships(String repositoryId) {
        return membershipRepository.findByRepositoryIdOrderByUsernameAsc(
                requireText(repositoryId, "repositoryId"));
    }

    public long countOwners(String repositoryId) {
        return membershipRepository.countByRepositoryIdAndRole(
                requireText(repositoryId, "repositoryId"), RepositoryRole.OWNER);
    }

    private static boolean isActive(SystemRepository repository) {
        return repository != null
                && repository.getLifecycleState() == RepositoryLifecycleState.ACTIVE;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
