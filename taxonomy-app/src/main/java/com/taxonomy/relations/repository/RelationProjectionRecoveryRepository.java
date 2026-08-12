package com.taxonomy.relations.repository;

import com.taxonomy.relations.model.RelationProjectionRecovery;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Exact repository/workspace/branch access to durable projection recovery state. */
@Repository
public interface RelationProjectionRecoveryRepository
        extends JpaRepository<RelationProjectionRecovery, Long> {

    Optional<RelationProjectionRecovery>
            findByRepositoryIdAndWorkspaceScopeKeyAndBranchAndAuthoritativeCommitId(
                    String repositoryId,
                    String workspaceScopeKey,
                    String branch,
                    String authoritativeCommitId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT recovery
            FROM RelationProjectionRecovery recovery
            WHERE recovery.repositoryId = :repositoryId
              AND recovery.workspaceScopeKey = :workspaceScopeKey
              AND recovery.branch = :branch
              AND recovery.authoritativeCommitId = :authoritativeCommitId
            """)
    Optional<RelationProjectionRecovery> findAuthorityForUpdate(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("branch") String branch,
            @Param("authoritativeCommitId") String authoritativeCommitId);

    List<RelationProjectionRecovery>
            findByRepositoryIdAndWorkspaceScopeKeyAndBranchAndStatusOrderByIdAsc(
                    String repositoryId,
                    String workspaceScopeKey,
                    String branch,
                    RecoveryStatus status);

    long countByRepositoryIdAndWorkspaceScopeKeyAndBranchAndStatus(
            String repositoryId,
            String workspaceScopeKey,
            String branch,
            RecoveryStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT recovery
            FROM RelationProjectionRecovery recovery
            WHERE recovery.repositoryId = :repositoryId
              AND recovery.workspaceScopeKey = :workspaceScopeKey
              AND recovery.branch = :branch
              AND recovery.status = :status
            ORDER BY recovery.id
            """)
    List<RelationProjectionRecovery> findStatusForUpdate(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("branch") String branch,
            @Param("status") RecoveryStatus status);
}
