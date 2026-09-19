package com.taxonomy.relations.repository;

import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Exact repository/workspace/branch access to relation projection checkpoints. */
@Repository
public interface RelationDecisionProjectionCheckpointRepository
        extends JpaRepository<RelationDecisionProjectionCheckpoint, Long> {

    Optional<RelationDecisionProjectionCheckpoint>
            findByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                    String repositoryId,
                    String workspaceScopeKey,
                    String branch);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT checkpoint
            FROM RelationDecisionProjectionCheckpoint checkpoint
            WHERE checkpoint.repositoryId = :repositoryId
              AND checkpoint.workspaceScopeKey = :workspaceScopeKey
              AND checkpoint.branch = :branch
            """)
    Optional<RelationDecisionProjectionCheckpoint> findExactForUpdate(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("branch") String branch);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            DELETE FROM RelationDecisionProjectionCheckpoint checkpoint
            WHERE checkpoint.repositoryId = :repositoryId
              AND checkpoint.workspaceScopeKey = :workspaceScopeKey
              AND checkpoint.branch = :branch
            """)
    int deleteExact(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("branch") String branch);
}
