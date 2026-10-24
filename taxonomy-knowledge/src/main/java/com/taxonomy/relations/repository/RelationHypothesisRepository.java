package com.taxonomy.relations.repository;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelationHypothesisRepository extends org.springframework.data.repository.Repository<RelationHypothesis, Long> {

    <S extends RelationHypothesis> S save(S entity);
    <S extends RelationHypothesis> List<S> saveAll(Iterable<S> entities);
    <S extends RelationHypothesis> S saveAndFlush(S entity);
    void flush();

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND h.workspaceId IS NULL
            ORDER BY h.id
            """)
    List<RelationHypothesis> findCentralByRepositoryAndBranch(
            @Param("repositoryId") String repositoryId, @Param("branchName") String branchName);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND h.workspaceId IS NULL
              AND h.status = :status
            ORDER BY h.id
            """)
    List<RelationHypothesis> findCentralByRepositoryAndStatusAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("status") HypothesisStatus status, @Param("branchName") String branchName);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)
            ORDER BY h.id
            """)
    List<RelationHypothesis> findVisibleByRepositoryAndWorkspaceAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId, @Param("branchName") String branchName);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)
              AND h.status = :status
            ORDER BY h.id
            """)
    List<RelationHypothesis> findVisibleByRepositoryAndWorkspaceAndStatusAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("status") HypothesisStatus status, @Param("branchName") String branchName);

    /** Looks up a hypothesis in exactly one mutable repository/workspace scope. */
    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND h.id = :id
              AND ((:workspaceId IS NULL AND h.workspaceId IS NULL)
                   OR h.workspaceId = :workspaceId)
            """)
    Optional<RelationHypothesis> findByIdInRepositoryWorkspaceAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId, @Param("branchName") String branchName);

    /** Looks up a readable hypothesis, including the selected repository's central baseline. */
    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND h.id = :id
              AND ((:workspaceId IS NULL AND h.workspaceId IS NULL)
                   OR (:workspaceId IS NOT NULL
                       AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)))
            """)
    Optional<RelationHypothesis> findByIdVisibleInRepositoryWorkspaceAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId, @Param("branchName") String branchName);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND h.analysisSessionId = :analysisSessionId
              AND ((:workspaceId IS NULL AND h.workspaceId IS NULL)
                   OR h.workspaceId = :workspaceId)
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByAnalysisSessionIdInRepositoryWorkspaceAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("analysisSessionId") String analysisSessionId, @Param("branchName") String branchName);

    @Query("""
            SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END
            FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.branchName = :branchName
              AND h.sourceNodeId = :sourceNodeId
              AND h.targetNodeId = :targetNodeId
              AND h.relationType = :relationType
              AND h.analysisSessionScopeKey = :analysisSessionScopeKey
              AND h.workspaceScopeKey = :workspaceScopeKey
            """)
    boolean existsInRepositoryWorkspaceSessionAndBranch(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("analysisSessionScopeKey") String analysisSessionScopeKey,
            @Param("sourceNodeId") String sourceNodeId,
            @Param("targetNodeId") String targetNodeId,
            @Param("relationType") RelationType relationType, @Param("branchName") String branchName);

    @Query("""
            SELECT COUNT(h) FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId AND h.branchName = :branchName
              AND (h.workspaceId IS NULL OR h.workspaceId = :workspaceId)
            """)
    long countVisibleInRepositoryWorkspaceAndBranch(@Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId, @Param("branchName") String branchName);
}
