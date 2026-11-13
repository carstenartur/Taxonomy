package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link UserWorkspace} entities.
 */
@Repository
public interface UserWorkspaceRepository extends JpaRepository<UserWorkspace, Long>, WorkspaceProvisioningFence {

    List<UserWorkspace> findByUsername(String username);

    Optional<UserWorkspace> findByWorkspaceId(String workspaceId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserWorkspace w where w.workspaceId = :workspaceId")
    Optional<UserWorkspace> findByWorkspaceIdForUpdate(@Param("workspaceId") String workspaceId);

    /** Called only by the owner-authorized sharing service while holding the workspace row lock. */
    @Modifying
    @Query("update UserWorkspace w set w.collaborative = :enabled where w.workspaceId = :workspaceId")
    int updateCollaborationFlag(@Param("workspaceId") String workspaceId, @Param("enabled") boolean enabled);

    /**
     * Compatibility entry point for implicit private-workspace selection.
     * Multiple workspaces are valid: prefer a non-archived default, then the
     * most recently accessed active private workspace, with an ID tie-breaker.
     * Explicit request pins and process-local active selection remain caller-owned.
     *
     * <p>The final ordering is deliberately performed in Java. Supported databases
     * disagree about where {@code NULL} appears for descending timestamp ordering;
     * a never-accessed row must never outrank a recently accessed row merely because
     * of database-specific NULL placement.</p>
     */
    default Optional<UserWorkspace> findByUsernameAndSharedFalse(String username) {
        Comparator<UserWorkspace> selectionOrder = Comparator
                .comparing(UserWorkspace::isDefault).reversed()
                .thenComparing(UserWorkspace::getLastAccessedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UserWorkspace::getWorkspaceId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
        return findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(username).stream()
                .filter(workspace -> !workspace.isShared())
                .sorted(selectionOrder)
                .findFirst();
    }

    Optional<UserWorkspace> findFirstByUsernameAndSharedFalseAndArchivedFalseOrderByIsDefaultDescLastAccessedAtDescWorkspaceIdAsc(
            String username);

    Optional<UserWorkspace> findBySharedTrue();

    boolean existsByUsername(String username);

    List<UserWorkspace> findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(String username);

    Optional<UserWorkspace> findByUsernameAndIsDefaultTrue(String username);

    Optional<UserWorkspace> findByUsernameAndDisplayName(String username, String displayName);

    long countByUsernameAndArchivedFalse(String username);

    /** Start a single grace period for legacy running rows first encountered after an ORM-managed schema upgrade. */
    @Transactional
    @Modifying
    @Query("""
            update UserWorkspace w set w.provisioningLeaseUntil = :expiresAt where w.workspaceId = :workspaceId
                and w.username = :username and w.archived = false and w.shared = false
                and w.provisioningStatus = :inProgress and w.provisioningLeaseOwner is null
                and w.provisioningLeaseUntil is null
            """)
    int observeLegacyProvisioning(@Param("workspaceId") String workspaceId, @Param("username") String username,
            @Param("inProgress") WorkspaceProvisioningStatus inProgress, @Param("expiresAt") java.time.Instant expiresAt);

    /** Claim new work or take over one expired lease atomically across application instances. */
    @Transactional
    @Modifying
    @Query("""
            update UserWorkspace w set w.provisioningStatus = :inProgress,
                w.provisioningLeaseOwner = :leaseOwner, w.provisioningLeaseUntil = :expiresAt,
                w.provisioningAttemptAt = :now, w.provisioningAttempt = w.provisioningAttempt + 1
            where w.workspaceId = :workspaceId and w.username = :username
              and w.archived = false and w.shared = false
              and (w.provisioningStatus in :previousStates or
                   (w.provisioningStatus = :inProgress and w.provisioningLeaseUntil <= :now))
            """)
    int claimProvisioning(@Param("workspaceId") String workspaceId,
                          @Param("username") String username,
                          @Param("inProgress") WorkspaceProvisioningStatus inProgress,
                          @Param("previousStates") Collection<WorkspaceProvisioningStatus> previousStates,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("now") java.time.Instant now,
                          @Param("expiresAt") java.time.Instant expiresAt);

    /**
     * Count active, disclosure-authorized rows without materializing metadata.
     * Archived, missing and unauthorized identifiers intentionally return zero.
     * Archiving retains the row and Git history; it does not grant metadata access.
     */
    @Query("""
            select count(workspace)
            from UserWorkspace workspace
            where workspace.workspaceId = :workspaceId
              and workspace.archived = false
              and (workspace.shared = true or (workspace.username = :username
                   and (workspace.ownerPrincipalId is null or workspace.ownerPrincipalId = :principalId)))
            """)
    long countVisibleWorkspaceMetadata(
            @Param("workspaceId") String workspaceId,
            @Param("username") String username, @Param("principalId") String principalId);

    @Query("""
            select count(w) from UserWorkspace w
            where w.workspaceId=:workspace and w.username=:actor and w.sourceRepositoryId=:repository
              and w.shared=false and w.archived=false
              and (w.ownerPrincipalId is null or w.ownerPrincipalId=:principalId)
            """)
    long countOwnedPrivateWorkspace(@Param("workspace") String workspace, @Param("actor") String actor,
                                  @Param("repository") String repository, @Param("principalId") String principalId);

    /** Scalar preflight keeps metadata out of the persistence context before the journal lock. */
    @Query("""
            select count(w) from UserWorkspace w
            where w.workspaceId=:workspace and w.username=:actor and w.sourceRepositoryId=:repository
              and w.currentBranch=:branch and w.provisioningStatus=:status
              and w.shared=false and w.archived=false
            """)
    long countReadyOwnedWorkspace(@Param("workspace") String workspace, @Param("actor") String actor,
                                 @Param("repository") String repository, @Param("branch") String branch,
                                 @Param("status") WorkspaceProvisioningStatus status);
}
