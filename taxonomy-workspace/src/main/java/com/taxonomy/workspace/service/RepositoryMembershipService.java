package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryOwnerType;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.RepositoryMembershipRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
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
    private final Optional<RepositoryRoleGrantProvider> grantProvider;

    private final Optional<RepositoryMembershipIdentity> identities;

    public RepositoryMembershipService(RepositoryMembershipRepository membershipRepository) {
        this(membershipRepository, Optional.empty());
    }

    public RepositoryMembershipService(RepositoryMembershipRepository membershipRepository,
            Optional<RepositoryRoleGrantProvider> grantProvider) {
        this(membershipRepository, grantProvider, Optional.empty());
    }

    @Autowired
    public RepositoryMembershipService(RepositoryMembershipRepository membershipRepository,
            Optional<RepositoryRoleGrantProvider> grantProvider, Optional<RepositoryMembershipIdentity> identities) {
        this.identities = identities;
        this.membershipRepository = membershipRepository;
        this.grantProvider = grantProvider;
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
        return saveRole(repository, member, role, changedBy, membership, null, null);
    }

    /** Assign or change a membership after enforcing repository-owner authority. */
    @Transactional
    public RepositoryMembership assignRole(
            SystemRepository repository,
            String username,
            RepositoryRole role,
            String actor) {
        return assignRole(repository, username, role, actor, null);
    }

    /**
     * Version-preconditioned membership update for external callers.
     * -1 requires that this member has never had a grant. Regranting reuses the locked row and advances
     * its version so a previously removed grant cannot match the new one.
     * Internal migration/bootstrap callers retain the distinct, trusted four-argument API.
     */
    @Transactional
    public RepositoryMembership assignRole(
            SystemRepository repository,
            String username,
            RepositoryRole role,
            String actor,
            Long expectedVersion) {
        String repositoryId = requireRepositoryId(repository);
        String member = requireText(username, "username");
        String changedBy = requireText(actor, "actor");
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }

        List<RepositoryMembership> lockedMemberships = lockMemberships(repositoryId, repository.getVersion());
        requireOwner(repository, actor, lockedMemberships);
        if (isUserCatalogOwner(repository, member) && role != RepositoryRole.OWNER) {
            throw new IllegalArgumentException(
                    "The repository catalog owner must retain the OWNER role");
        }
        RepositoryMembership existing = findMembership(lockedMemberships, member)
                .orElse(null);
        requireExpectedVersion(existing, expectedVersion);
        if (existing != null && existing.isActive()
                && existing.getRole() == RepositoryRole.OWNER
                && role != RepositoryRole.OWNER
                && !hasImplicitUserOwner(repository)
                && ownerCount(lockedMemberships) <= 1) {
            throw new IllegalStateException("A repository must retain at least one OWNER");
        }
        return saveRole(repositoryId, member, role, changedBy, existing, null, null);
    }

    /** Transfer the personal catalogue and both direct memberships under its observed policy revision. */
    @Transactional
    public void transferPersonalOwnership(SystemRepository repository, long expectedVersion,
                                          String previousOwner, String successor, java.util.UUID previousPrincipal, java.util.UUID successorPrincipal) {
        String repositoryId = requireRepositoryId(repository);
        var locked = lockMemberships(repositoryId, expectedVersion);
        if (!isActive(repository) || !isUserCatalogOwner(repository, previousOwner)) {
            throw new AccessDeniedException("The current personal catalogue owner is required");
        }
        if (previousOwner.equals(successor)) throw new IllegalArgumentException("A different owner is required");
        saveRole(repositoryId, successor, RepositoryRole.OWNER, previousOwner, findMembership(locked, successor).orElse(null),
                previousPrincipal, successorPrincipal);
        Instant now = Instant.now();
        findMembership(locked, previousOwner).filter(RepositoryMembership::isActive)
                .filter(member -> member.getRole() == RepositoryRole.OWNER).ifPresent(member -> {
                    member.setRevokedAt(now);
                    member.setUpdatedAt(now);
                    membershipRepository.save(member);
                });
        repository.setOwnerType(RepositoryOwnerType.USER);
        repository.setOwnerId(successor);
        repository.setUpdatedAt(now);
    }

    /** Remove a membership after enforcing repository-owner authority and owner retention. */
    @Transactional
    public void removeMembership(
            SystemRepository repository,
            String username,
            String actor) {
        removeMembership(repository, username, actor, null);
    }

    /** Version-preconditioned deletion: an outdated editor cannot remove a changed grant. */
    @Transactional
    public void removeMembership(
            SystemRepository repository,
            String username,
            String actor,
            Long expectedVersion) {
        String repositoryId = requireRepositoryId(repository);
        String member = requireText(username, "username");
        requireText(actor, "actor");

        List<RepositoryMembership> lockedMemberships = lockMemberships(repositoryId, repository.getVersion());
        requireOwner(repository, actor, lockedMemberships);
        if (isUserCatalogOwner(repository, member)) {
            throw new IllegalArgumentException(
                    "The repository catalog owner membership cannot be removed");
        }
        Optional<RepositoryMembership> current = findMembership(lockedMemberships, member);
        requireExpectedVersion(current.orElse(null), expectedVersion);
        RepositoryMembership membership = current.filter(RepositoryMembership::isActive).orElseThrow(() -> new IllegalArgumentException(
                "Repository membership does not exist: " + member));
        if (membership.getRole() == RepositoryRole.OWNER
                && !hasImplicitUserOwner(repository)
                && ownerCount(lockedMemberships) <= 1) {
            throw new IllegalStateException("A repository must retain at least one OWNER");
        }
        Instant revokedAt = Instant.now();
        membership.setRevokedAt(revokedAt);
        membership.setUpdatedAt(revokedAt);
        membershipRepository.save(membership);
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
        Optional<RepositoryRole> stored = membershipRepository.findByRepositoryIdAndUsername(
                        repository.getRepositoryId(), member)
                .filter(RepositoryMembership::isActive)
                .filter(row -> belongsToActor(row, member))
                .map(RepositoryMembership::getRole);
        Optional<RepositoryRole> granted = trustedRole(repository, username);
        return granted.filter(role -> stored.map(role::grants).orElse(true))
                .or(() -> stored);
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

    /** The publishing caller holds the catalogue lock. Read memberships in a fresh transaction. */
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean canPublishLockedSource(SystemRepository source, String username) {
        return canRead(source, username) && canContribute(source, username);
    }

    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean canCollaborateOnLockedSource(SystemRepository source, String username) {
        return canCollaborate(source, username);
    }

    public boolean canMaintain(SystemRepository repository, String username) {
        return isActive(repository)
                && effectiveRole(repository, username)
                .map(role -> role.grants(RepositoryRole.MAINTAINER))
                .orElse(false);
    }

    /** Shared drafts require explicit repository authority; public/primary compatibility is insufficient. */
    public boolean canCollaborate(SystemRepository repository, String username) {
        return isActive(repository) && effectiveRole(repository, username)
                .map(role -> role.grants(RepositoryRole.CONTRIBUTOR)).orElse(false);
    }

    /** Does not provision, select or disclose a foreign private working copy. */
    public boolean canUseWorkspace(SystemRepository repository, com.taxonomy.workspace.model.UserWorkspace workspace, String username) {
        // A retained workspace record never grants authority after the source has been
        // archived or the actor's current repository membership was revoked.
        return isActive(repository) && username != null && !username.isBlank()
                && canRead(repository, username)
                && workspace != null && !workspace.isShared() && !workspace.isArchived()
                && java.util.Objects.equals(workspace.getSourceRepositoryId(), repository.getRepositoryId())
                && (java.util.Objects.equals(workspace.getUsername(), username)
                || (workspace.isCollaborative() && canCollaborate(repository, username)));
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

    /** List memberships only for an effective repository owner. */
    public List<RepositoryMembership> listMemberships(
            SystemRepository repository, String actor) {
        requireOwner(repository, actor);
        return listMemberships(repository.getRepositoryId());
    }

    public record MembershipRevision(boolean active, long version) {}

    /** Read the version needed for an owner's next grant, regrant or role change. */
    @Transactional(readOnly = true)
    public MembershipRevision membershipRevision(SystemRepository repository, String username, String actor) {
        requireOwner(repository, actor);
        return membershipRepository.findByRepositoryIdAndUsername(
                        requireRepositoryId(repository), requireText(username, "username"))
                .map(member -> new MembershipRevision(member.isActive(), member.getVersion()))
                .orElse(new MembershipRevision(false, -1L));
    }

    public long countOwners(String repositoryId) {
        return membershipRepository.countByRepositoryIdAndRole(
                requireText(repositoryId, "repositoryId"), RepositoryRole.OWNER);
    }

    private static void requireExpectedVersion(RepositoryMembership existing, Long expectedVersion) {
        if (expectedVersion == null) {
            return; // Only trusted internal callers use the unversioned API.
        }
        if (expectedVersion < -1) {
            throw new IllegalArgumentException("expectedVersion must be -1 or nonnegative");
        }
        long currentVersion = existing == null ? -1L : existing.getVersion();
        if (currentVersion != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Repository membership changed; reload the latest version");
        }
    }

    private RepositoryMembership saveRole(
            String repositoryId,
            String username,
            RepositoryRole role,
            String actor,
            RepositoryMembership existing, java.util.UUID transferActor, java.util.UUID transferHolder) {
        Instant now = Instant.now();
        RepositoryMembership membership = existing;
        if (membership == null) {
            membership = new RepositoryMembership();
            membership.setRepositoryId(repositoryId);
            membership.setUsername(username);
        }
        if (existing == null || !membership.isActive()) {
            var grantor = transferActor != null ? Optional.of(transferActor) : identities.flatMap(i -> i.currentActor(actor));
            var holder = transferHolder != null ? Optional.of(transferHolder)
                    : grantor.flatMap(id -> username.equals(actor) ? Optional.of(id) : identities.flatMap(i -> i.activeHolder(username)));
            if (grantor.isPresent() && holder.isEmpty()) {
                throw new IllegalArgumentException("A verified current member principal is required for a new grant");
            }
            membership.setHolderPrincipalId(holder.map(java.util.UUID::toString).orElse(null));
            membership.setGrantorPrincipalId(grantor.map(java.util.UUID::toString).orElse(null));
            membership.setCreatedAt(now);
            membership.setCreatedBy(actor);
            membership.setRevokedAt(null);
        }
        if (transferHolder != null) membership.setHolderPrincipalId(transferHolder.toString());
        membership.setRole(role);
        membership.setUpdatedAt(now);
        return membershipRepository.save(membership);
    }

    private List<RepositoryMembership> lockMemberships(String repositoryId, long expectedVersion) {
        if (membershipRepository.lockCatalogueRevision(repositoryId, expectedVersion) != 1) {
            throw new OptimisticLockingFailureException("Repository policy changed; reload the repository");
        }
        return membershipRepository.findByRepositoryIdForUpdate(repositoryId);
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
                        .filter(RepositoryMembership::isActive)
                        .filter(row -> belongsToActor(row, username))
                        .map(RepositoryMembership::getRole)
                        .filter(role -> role.grants(RepositoryRole.OWNER))
                        .isPresent()
                || trustedRole(repository, actor)
                        .filter(role -> role.grants(RepositoryRole.OWNER))
                        .isPresent();
        if (!owner) {
            throw new AccessDeniedException("Repository OWNER role required");
        }
    }

    private boolean belongsToActor(RepositoryMembership row, String scope) {
        if (row.getHolderPrincipalId() == null) return true;
        return RepositoryMembershipIdentity.RecordedActor.currentPrincipal(scope)
                .or(() -> identities.flatMap(identity -> identity.currentActor(scope)).map(java.util.UUID::toString))
                .filter(row.getHolderPrincipalId()::equals).isPresent();
    }

    private Optional<RepositoryRole> trustedRole(SystemRepository repository, String actor) {
        return grantProvider.flatMap(provider -> provider.findRole(repository.getRepositoryId(), actor));
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
                .filter(RepositoryMembership::isActive)
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
