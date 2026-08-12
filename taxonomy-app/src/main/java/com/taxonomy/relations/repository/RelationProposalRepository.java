package com.taxonomy.relations.repository;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelationProposalRepository extends JpaRepository<RelationProposal, Long> {

    String PRIMARY_SCOPE = " p.repositoryId IN ("
            + "SELECT repository.repositoryId FROM SystemRepository repository "
            + "WHERE repository.primaryRepo = true)";

    // ── Explicit repository/workspace tenant scope ─────────────────

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND p.workspaceId IS NULL
            ORDER BY p.id
            """)
    List<RelationProposal> findCentralByRepository(
            @Param("repositoryId") String repositoryId);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND p.workspaceId IS NULL
              AND p.status = :status
            ORDER BY p.id
            """)
    List<RelationProposal> findCentralByRepositoryAndStatus(
            @Param("repositoryId") String repositoryId,
            @Param("status") ProposalStatus status);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND p.workspaceId IS NULL
              AND p.sourceNode.code = :sourceCode
            ORDER BY p.id
            """)
    List<RelationProposal> findCentralByRepositoryAndSourceNodeCode(
            @Param("repositoryId") String repositoryId,
            @Param("sourceCode") String sourceCode);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND (p.workspaceId = :workspaceId OR p.workspaceId IS NULL)
            ORDER BY p.id
            """)
    List<RelationProposal> findVisibleByRepositoryAndWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND (p.workspaceId = :workspaceId OR p.workspaceId IS NULL)
              AND p.status = :status
            ORDER BY p.id
            """)
    List<RelationProposal> findVisibleByRepositoryAndWorkspaceAndStatus(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("status") ProposalStatus status);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND (p.workspaceId = :workspaceId OR p.workspaceId IS NULL)
              AND p.sourceNode.code = :sourceCode
            ORDER BY p.id
            """)
    List<RelationProposal> findVisibleByRepositoryAndWorkspaceAndSourceNodeCode(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("sourceCode") String sourceCode);

    /** Looks up a proposal in exactly one mutable repository/workspace scope. */
    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND p.id = :id
              AND ((:workspaceId IS NULL AND p.workspaceId IS NULL)
                   OR p.workspaceId = :workspaceId)
            """)
    Optional<RelationProposal> findByIdInRepositoryWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    /** Locks one exact mutable proposal row for the final review-state transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND p.id = :id
              AND ((:workspaceId IS NULL AND p.workspaceId IS NULL)
                   OR p.workspaceId = :workspaceId)
            """)
    Optional<RelationProposal> findByIdInRepositoryWorkspaceForUpdate(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    /** Checks a proposal triple in exactly one repository/workspace scope. */
    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM RelationProposal p
            WHERE p.repositoryId = :repositoryId
              AND p.sourceNode.code = :sourceCode
              AND p.targetNode.code = :targetCode
              AND p.relationType = :relationType
              AND ((:workspaceId IS NULL AND p.workspaceId IS NULL)
                   OR p.workspaceId = :workspaceId)
            """)
    boolean existsInRepositoryWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("relationType") RelationType relationType,
            @Param("workspaceId") String workspaceId);

    List<RelationProposal> findByRepositoryIdAndWorkspaceId(
            String repositoryId, String workspaceId);

    // ── Primary-repository compatibility boundary ──────────────────
    // Historic signatures remain available for internal metrics/tests while
    // callers migrate. They are deliberately primary-scoped, never global.

    @Override
    @Query("SELECT p FROM RelationProposal p WHERE " + PRIMARY_SCOPE + " ORDER BY p.id")
    List<RelationProposal> findAll();

    @Override
    @Query("SELECT COUNT(p) FROM RelationProposal p WHERE " + PRIMARY_SCOPE)
    long count();

    @Override
    @Query("SELECT p FROM RelationProposal p WHERE p.id = :id AND " + PRIMARY_SCOPE)
    Optional<RelationProposal> findById(@Param("id") Long id);

    @Override
    default void deleteAll() {
        deleteAll(findAll());
    }

    @Override
    default void deleteAllInBatch() {
        deleteAll(findAll());
    }

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.status = :status
            ORDER BY p.id
            """)
    List<RelationProposal> findByStatus(@Param("status") ProposalStatus status);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
            ORDER BY p.id
            """)
    List<RelationProposal> findBySourceNodeCode(
            @Param("sourceCode") String sourceCode);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
              AND p.relationType = :relationType
            ORDER BY p.id
            """)
    List<RelationProposal> findBySourceNodeCodeAndRelationType(
            @Param("sourceCode") String sourceCode,
            @Param("relationType") RelationType relationType);

    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
              AND p.targetNode.code = :targetCode
              AND p.relationType = :relationType
            """)
    boolean existsBySourceNodeCodeAndTargetNodeCodeAndRelationType(
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("relationType") RelationType relationType);

    @Query("""
            SELECT COUNT(p) FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.status = :status
            """)
    long countByStatus(@Param("status") ProposalStatus status);

    @Query("""
            SELECT COUNT(p) FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.relationType = :relationType
              AND p.status = :status
            """)
    long countByRelationTypeAndStatus(
            @Param("relationType") RelationType relationType,
            @Param("status") ProposalStatus status);

    @Query("SELECT DISTINCT p.relationType FROM RelationProposal p WHERE " + PRIMARY_SCOPE)
    List<RelationType> findDistinctRelationTypes();

    @Query("SELECT DISTINCT p.provenance FROM RelationProposal p WHERE " + PRIMARY_SCOPE)
    List<String> findDistinctProvenances();

    @Query("""
            SELECT COUNT(p) FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.provenance = :provenance
              AND p.status = :status
            """)
    long countByProvenanceAndStatus(
            @Param("provenance") String provenance,
            @Param("status") ProposalStatus status);

    @Query("""
            SELECT AVG(p.confidence) FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.status = :status
            """)
    Double avgConfidenceByStatus(@Param("status") ProposalStatus status);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.status = :status
            ORDER BY p.confidence DESC, p.id
            """)
    List<RelationProposal> findByStatusOrderByConfidenceDesc(
            @Param("status") ProposalStatus status);

    @Query("""
            SELECT COUNT(p) FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.taxonomyRoot = :sourceRoot
              AND p.targetNode.taxonomyRoot = :targetRoot
              AND p.relationType = :relationType
              AND p.status = :status
            """)
    long countBySourceNodeTaxonomyRootAndTargetNodeTaxonomyRootAndRelationTypeAndStatus(
            @Param("sourceRoot") String sourceRoot,
            @Param("targetRoot") String targetRoot,
            @Param("relationType") RelationType relationType,
            @Param("status") ProposalStatus status);

    // ── Legacy workspace aliases, constrained to the primary repository ──

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.id = :id
              AND """ + PRIMARY_SCOPE + """
              AND ((:workspaceId IS NULL AND p.workspaceId IS NULL)
                   OR p.workspaceId = :workspaceId)
            """)
    Optional<RelationProposal> findByIdInWorkspace(
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
              AND p.targetNode.code = :targetCode
              AND p.relationType = :relationType
              AND ((:workspaceId IS NULL AND p.workspaceId IS NULL)
                   OR p.workspaceId = :workspaceId)
            """)
    boolean existsInWorkspace(
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("relationType") RelationType relationType,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
              AND p.targetNode.code = :targetCode
              AND p.relationType = :relationType
              AND p.workspaceId = :workspaceId
            """)
    boolean existsBySourceNodeCodeAndTargetNodeCodeAndRelationTypeAndWorkspaceId(
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("relationType") RelationType relationType,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.workspaceId = :workspaceId
            ORDER BY p.id
            """)
    List<RelationProposal> findByWorkspaceId(
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.workspaceId IS NULL
            ORDER BY p.id
            """)
    List<RelationProposal> findByWorkspaceIdIsNull();

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.status = :status
              AND p.workspaceId IS NULL
            ORDER BY p.id
            """)
    List<RelationProposal> findByStatusAndWorkspaceIdIsNull(
            @Param("status") ProposalStatus status);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
              AND p.workspaceId IS NULL
            ORDER BY p.id
            """)
    List<RelationProposal> findBySourceNodeCodeAndWorkspaceIdIsNull(
            @Param("sourceCode") String sourceCode);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND (p.workspaceId = :workspaceId OR p.workspaceId IS NULL)
            ORDER BY p.id
            """)
    List<RelationProposal> findByWorkspaceIdIsNullOrWorkspaceId(
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.status = :status
              AND (p.workspaceId = :workspaceId OR p.workspaceId IS NULL)
            ORDER BY p.id
            """)
    List<RelationProposal> findByStatusAndWorkspace(
            @Param("status") ProposalStatus status,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT p FROM RelationProposal p
            WHERE """ + PRIMARY_SCOPE + """
              AND p.sourceNode.code = :sourceCode
              AND (p.workspaceId = :workspaceId OR p.workspaceId IS NULL)
            ORDER BY p.id
            """)
    List<RelationProposal> findBySourceNodeCodeAndWorkspace(
            @Param("sourceCode") String sourceCode,
            @Param("workspaceId") String workspaceId);
}
