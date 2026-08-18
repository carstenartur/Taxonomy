package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import org.springframework.security.access.AccessDeniedException;
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

        RepositoryMembership membership = membershipRepository
                .findByRepositoryIdAndUsername(repository, member)
                .orElse(null);
        return saveRole(repository, member, role, changedBy, membership);
    }

    /** Assign or change a membership after enforcing repository-owner authority. */
    @Transactional
    public RepositoryMembership assignRole(
            SystemRepository repository,
            String username,
            RepositoryRole role,
            String actor) {
        String repositoryId = requireRepositoryId(repository);
        String member = requireText(username, "username");
        String changedBy = requireText(actor, "actor");
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }

        List<RepositoryMembership> lockedMemberships = membershipRepository
                .findByRepositoryIdForUpdate(repositoryId);
        requireOwner(repository, changedBy, lockedMemberships);
        if (isUserCatalogOwner(repository, member) && role != RepositoryRole.OWNER) {
            throw new IllegalArgumentException(
                    "The repository catalog owner must retain the OWNER role");
        }
        RepositoryMembership existing = findMembership(lockedMemberships, member)
                .orElse(null);
        if (existing != null
                && existing.getRole() == RepositoryRole.OWNER
                && role != RepositoryRole.OWNER
                && !hasImplicitUserOwner(repository)
                && ownerCount(lockedMemberships) <= 1) {
            throw new IllegalStateException("A repository must retain at least one OWNER");
        }
        return saveRole(repositoryId, member, role, changedBy, existing);
    }

    /** Remove a membership after enforcing repository-owner authority and owner retention. */
    @Transactional
    public void removeMembership(
            SystemRepository repository,
            String username,
            String actor) {
        String repositoryId = requireRepositoryId(repository);
        String member = requireText(username, "username");
        String changedBy = requireText(actor, "actor");

        List<RepositoryMembership> lockedMemberships = membershipRepository
                .findByRepositoryIdForUpdate(repositoryId);
        requireOwner(repository, changedBy, lockedMemberships);
        if (isUserCatalogOwner(repository, member)) {
            throw new IllegalArgumentException(
                    "The repository catalog owner membership cannot be removed");
        }
        RepositoryMembership membership = findMembership(lockedMemberships, member)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Repository membership does not exist: " + member));
        if (membership.getRole() == RepositoryRole.OWNER
                && !hasImplicitUserOwner(repository)
                && ownerCount(lockedMemberships) <= 1) {
            throw new IllegalStateException("A repository must retain at least one OWNER");
        }
        membershipRepository.delete(membership);
    }

    public Optional<RepositoryRole> effectiveRole(
            SystemRepository repository, String username) {
        if (repository == null || username == null || username.isBlank()) {
            return Optional.empty();
        }
        String member = username.strip();
        if (isUserCatalogOwner(repository, member)) {
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
        if (!isActive(repository)) {
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

    /**
     * Working-copy creation requires CONTRIBUTOR. The historic primary repository remains a
     * compatibility exception for authenticated users until installations can bootstrap explicit
     * memberships for that pre-existing shared repository.
     */
    public boolean canContribute(SystemRepository repository, String username) {
        if (!isActive(repository) || username == null || username.isBlank()) {
            return false;
        }
        if (repository.isPrimaryRepo()) {
            return true;
        }
        return effectiveRole(repository, username)
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

    /** List memberships only for an explicit repository owner. */
    public List<RepositoryMembership> listMemberships(
            SystemRepository repository, String actor) {
        requireOwner(repository, actor);
        return listMemberships(repository.getRepositoryId());
    }

    public long countOwners(String repositoryId) {
        return membershipRepository.countByRepositoryIdAndRole(
                requireText(repositoryId, "repositoryId"), RepositoryRole.OWNER);
    }

    private RepositoryMembership saveRole(
            String repositoryId,
            String username,
            RepositoryRole role,
            String actor,
            RepositoryMembership existing) {
        Instant now = Instant.now();
        RepositoryMembership membership = existing;
        if (membership == null) {
            membership = new RepositoryMembership();
            membership.setRepositoryId(repositoryId);
            membership.setUsername(username);
            membership.setCreatedAt(now);
            membership.setCreatedBy(actor);
        }
        membership.setRole(role);
        membership.setUpdatedAt(now);
        return membershipRepository.save(membership);
    }

    private void requireOwner(
            SystemRepository repository,
            String actor,
            List<RepositoryMembership> lockedMemberships) {
        if (!isActive(repository)) {
            throw new AccessDeniedException("Repository OWNER role required");
        }
        String username = requireText(actor, "actor");
        boolean owner = isUserCatalogOwner(repository, username)
                || findMembership(lockedMemberships, username)
                        .map(RepositoryMembership::getRole)
                        .filter(role -> role.grants(RepositoryRole.OWNER))
                        .isPresent();
        if (!owner) {
            throw new AccessDeniedException("Repository OWNER role required");
        }
    }

    private void requireOwner(SystemRepository repository, String actor) {
        if (!isOwner(repository, actor)) {
            throw new AccessDeniedException("Repository OWNER role required");
        }
    }

    private static Optional<RepositoryMembership> findMembership(
            List<RepositoryMembership> memberships,
            String username) {
        return memberships.stream()
                .filter(membership -> username.equals(membership.getUsername()))
                .findFirst();
    }

    private static long ownerCount(List<RepositoryMembership> memberships) {
        return memberships.stream()
                .map(RepositoryMembership::getRole)
                .filter(RepositoryRole.OWNER::equals)
                .count();
    }

    private static String requireRepositoryId(SystemRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        return requireText(repository.getRepositoryId(), "repositoryId");
    }

    private static boolean isUserCatalogOwner(
            SystemRepository repository, String username) {
        if (repository == null || username == null) {
            return false;
        }
        RepositoryOwnerType ownerType = repository.getOwnerType();
        return (ownerType == null || ownerType == RepositoryOwnerType.USER)
                && username.equals(repository.getOwnerId());
    }

    private static boolean hasImplicitUserOwner(SystemRepository repository) {
        return repository != null
                && (repository.getOwnerType() == null
                        || repository.getOwnerType() == RepositoryOwnerType.USER)
                && repository.getOwnerId() != null
                && !repository.getOwnerId().isBlank();
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
