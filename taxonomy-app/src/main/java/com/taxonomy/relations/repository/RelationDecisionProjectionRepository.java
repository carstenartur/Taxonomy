package com.taxonomy.relations.repository;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Exact repository/workspace/branch access to rebuildable relation projections. */
@Repository
public interface RelationDecisionProjectionRepository
        extends JpaRepository<RelationDecisionProjection, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT projection
            FROM RelationDecisionProjection projection
            WHERE projection.repositoryId = :repositoryId
              AND projection.workspaceScopeKey = :workspaceScopeKey
              AND projection.branch = :branch
              AND projection.sourceCode = :sourceCode
              AND projection.relationType = :relationType
              AND projection.targetCode = :targetCode
            """)
    Optional<RelationDecisionProjection> findExactForUpdate(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("branch") String branch,
            @Param("sourceCode") String sourceCode,
            @Param("relationType") RelationType relationType,
            @Param("targetCode") String targetCode);

    List<RelationDecisionProjection>
            findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderBySourceCodeAscTargetCodeAsc(
                    String repositoryId,
                    String workspaceScopeKey,
                    String branch);

    long countByRepositoryIdAndWorkspaceScopeKeyAndBranch(
            String repositoryId,
            String workspaceScopeKey,
            String branch);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            DELETE FROM RelationDecisionProjection projection
            WHERE projection.repositoryId = :repositoryId
              AND projection.workspaceScopeKey = :workspaceScopeKey
              AND projection.branch = :branch
            """)
    int deleteExactBranch(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("branch") String branch);
}
