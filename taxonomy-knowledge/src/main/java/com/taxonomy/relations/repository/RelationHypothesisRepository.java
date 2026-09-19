package com.taxonomy.relations.repository;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelationHypothesisRepository extends JpaRepository<RelationHypothesis, Long> {

    String PRIMARY_SCOPE = " h.repositoryId IN ("
            + "SELECT repository.repositoryId FROM SystemRepository repository "
            + "WHERE repository.primaryRepo = true)";

    // ── Explicit repository/workspace tenant scope ─────────────────

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.workspaceId IS NULL
            ORDER BY h.id
            """)
    List<RelationHypothesis> findCentralByRepository(
            @Param("repositoryId") String repositoryId);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.workspaceId IS NULL
              AND h.status = :status
            ORDER BY h.id
            """)
    List<RelationHypothesis> findCentralByRepositoryAndStatus(
            @Param("repositoryId") String repositoryId,
            @Param("status") HypothesisStatus status);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)
            ORDER BY h.id
            """)
    List<RelationHypothesis> findVisibleByRepositoryAndWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)
              AND h.status = :status
            ORDER BY h.id
            """)
    List<RelationHypothesis> findVisibleByRepositoryAndWorkspaceAndStatus(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("status") HypothesisStatus status);

    /** Looks up a hypothesis in exactly one mutable repository/workspace scope. */
    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.id = :id
              AND ((:workspaceId IS NULL AND h.workspaceId IS NULL)
                   OR h.workspaceId = :workspaceId)
            """)
    Optional<RelationHypothesis> findByIdInRepositoryWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    /** Looks up a readable hypothesis, including the selected repository's central baseline. */
    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.id = :id
              AND ((:workspaceId IS NULL AND h.workspaceId IS NULL)
                   OR (:workspaceId IS NOT NULL
                       AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)))
            """)
    Optional<RelationHypothesis> findByIdVisibleInRepositoryWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.analysisSessionId = :analysisSessionId
              AND ((:workspaceId IS NULL AND h.workspaceId IS NULL)
                   OR h.workspaceId = :workspaceId)
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByAnalysisSessionIdInRepositoryWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("analysisSessionId") String analysisSessionId);

    @Query("""
            SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END
            FROM RelationHypothesis h
            WHERE h.repositoryId = :repositoryId
              AND h.sourceNodeId = :sourceNodeId
              AND h.targetNodeId = :targetNodeId
              AND h.relationType = :relationType
              AND h.analysisSessionScopeKey = :analysisSessionScopeKey
              AND h.workspaceScopeKey = :workspaceScopeKey
            """)
    boolean existsInRepositoryWorkspaceSession(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceScopeKey") String workspaceScopeKey,
            @Param("analysisSessionScopeKey") String analysisSessionScopeKey,
            @Param("sourceNodeId") String sourceNodeId,
            @Param("targetNodeId") String targetNodeId,
            @Param("relationType") RelationType relationType);

    List<RelationHypothesis> findByRepositoryIdAndWorkspaceId(
            String repositoryId, String workspaceId);

    // ── Primary-repository compatibility boundary ──────────────────
    // Historic signatures remain available while callers migrate. They are
    // deliberately primary-repository scoped and never query every tenant.

    @Override
    @Query("SELECT h FROM RelationHypothesis h WHERE " + PRIMARY_SCOPE + " ORDER BY h.id")
    List<RelationHypothesis> findAll();

    @Override
    @Query("SELECT COUNT(h) FROM RelationHypothesis h WHERE " + PRIMARY_SCOPE)
    long count();

    @Override
    @Query("SELECT h FROM RelationHypothesis h WHERE h.id = :id AND " + PRIMARY_SCOPE)
    Optional<RelationHypothesis> findById(@Param("id") Long id);

    @Override
    default void deleteAll() {
        deleteAll(findAll());
    }

    @Override
    default void deleteAllInBatch() {
        deleteAll(findAll());
    }

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.status = :status
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByStatus(@Param("status") HypothesisStatus status);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.analysisSessionId = :analysisSessionId
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByAnalysisSessionId(
            @Param("analysisSessionId") String analysisSessionId);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.sourceNodeId = :sourceNodeId
              AND h.targetNodeId = :targetNodeId
              AND h.relationType = :relationType
            ORDER BY h.id
            """)
    List<RelationHypothesis> findBySourceNodeIdAndTargetNodeIdAndRelationType(
            @Param("sourceNodeId") String sourceNodeId,
            @Param("targetNodeId") String targetNodeId,
            @Param("relationType") RelationType relationType);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.status IN :statuses
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByStatusIn(
            @Param("statuses") List<HypothesisStatus> statuses);

    // ── Legacy workspace aliases constrained to the primary repository ──

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.workspaceId = :workspaceId
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByWorkspaceId(
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.workspaceId IS NULL
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByWorkspaceIdIsNull();

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.status = :status
              AND h.workspaceId IS NULL
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByStatusAndWorkspaceIdIsNull(
            @Param("status") HypothesisStatus status);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByWorkspaceIdIsNullOrWorkspaceId(
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT h FROM RelationHypothesis h
            WHERE """ + PRIMARY_SCOPE + """
              AND h.status = :status
              AND (h.workspaceId = :workspaceId OR h.workspaceId IS NULL)
            ORDER BY h.id
            """)
    List<RelationHypothesis> findByStatusAndWorkspace(
            @Param("status") HypothesisStatus status,
            @Param("workspaceId") String workspaceId);
}
