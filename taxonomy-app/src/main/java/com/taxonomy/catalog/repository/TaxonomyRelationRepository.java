package com.taxonomy.catalog.repository;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.model.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxonomyRelationRepository extends JpaRepository<TaxonomyRelation, Long> {

    String PRIMARY_SCOPE = "r.repositoryId IN ("
            + "SELECT repository.repositoryId FROM SystemRepository repository "
            + "WHERE repository.primaryRepo = true)";

    // ── Explicit repository/workspace tenant scope ─────────────────

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
              AND (r.sourceNode.code = :code OR r.targetNode.code = :code)
            """)
    List<TaxonomyRelation> findVisibleByRepositoryAndWorkspaceAndNodeCode(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("code") String code);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
            """)
    List<TaxonomyRelation> findVisibleByRepositoryAndWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findVisibleByRepositoryAndWorkspaceAndRelationType(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("type") RelationType type);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
              AND r.sourceNode.code = :sourceCode
              AND r.targetNode.code = :targetCode
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findVisibleByRepositoryAndWorkspaceAndSourceTargetType(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId,
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("type") RelationType type);

    @Query("""
            SELECT COUNT(r) FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
            """)
    long countVisibleByRepositoryAndWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND r.workspaceId IS NULL
            """)
    List<TaxonomyRelation> findCentralByRepository(
            @Param("repositoryId") String repositoryId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND r.workspaceId IS NULL
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findCentralByRepositoryAndRelationType(
            @Param("repositoryId") String repositoryId,
            @Param("type") RelationType relationType);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND r.workspaceId IS NULL
              AND (r.sourceNode.code = :code OR r.targetNode.code = :code)
            """)
    List<TaxonomyRelation> findCentralByRepositoryAndNodeCode(
            @Param("repositoryId") String repositoryId,
            @Param("code") String code);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND r.workspaceId IS NULL
              AND r.sourceNode.code = :sourceCode
              AND r.targetNode.code = :targetCode
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findCentralByRepositoryAndSourceTargetType(
            @Param("repositoryId") String repositoryId,
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("type") RelationType type);

    @Query("""
            SELECT COUNT(r) FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND r.workspaceId IS NULL
            """)
    long countCentralByRepository(@Param("repositoryId") String repositoryId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.repositoryId = :repositoryId
              AND r.id = :id
              AND ((:workspaceId IS NULL AND r.workspaceId IS NULL)
                   OR r.workspaceId = :workspaceId)
            """)
    Optional<TaxonomyRelation> findByIdInRepositoryWorkspace(
            @Param("repositoryId") String repositoryId,
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    List<TaxonomyRelation> findByRepositoryIdAndWorkspaceId(
            String repositoryId, String workspaceId);

    List<TaxonomyRelation>
            findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                    String repositoryId,
                    String workspaceId,
                    String sourceCode,
                    String targetCode,
                    RelationType type);

    // ── Primary-repository compatibility boundary ──────────────────
    // These signatures predate multi-repository routing. They remain available
    // for internal callers during migration, but are deliberately constrained to
    // the one catalog entry marked primary instead of reading every tenant.

    @Override
    @Query("SELECT r FROM TaxonomyRelation r WHERE " + PRIMARY_SCOPE)
    List<TaxonomyRelation> findAll();

    @Override
    @Query("SELECT COUNT(r) FROM TaxonomyRelation r WHERE " + PRIMARY_SCOPE)
    long count();

    @Override
    @Query("SELECT r FROM TaxonomyRelation r WHERE r.id = :id AND " + PRIMARY_SCOPE)
    Optional<TaxonomyRelation> findById(@Param("id") Long id);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.sourceNode.code = :code
            """)
    List<TaxonomyRelation> findBySourceNodeCode(@Param("code") String code);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.targetNode.code = :code
            """)
    List<TaxonomyRelation> findByTargetNodeCode(@Param("code") String code);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findByRelationType(@Param("type") RelationType relationType);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND (r.sourceNode.code = :sourceCode OR r.targetNode.code = :targetCode)
            """)
    List<TaxonomyRelation> findBySourceNodeCodeOrTargetNodeCode(
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.relationType IN :types
            """)
    List<TaxonomyRelation> findByRelationTypeIn(
            @Param("types") List<RelationType> relationTypes);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.sourceNode.code = :sourceCode
              AND r.relationType IN :types
            """)
    List<TaxonomyRelation> findBySourceNodeCodeAndRelationTypeIn(
            @Param("sourceCode") String sourceCode,
            @Param("types") List<RelationType> types);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.targetNode.code = :targetCode
              AND r.relationType IN :types
            """)
    List<TaxonomyRelation> findByTargetNodeCodeAndRelationTypeIn(
            @Param("targetCode") String targetCode,
            @Param("types") List<RelationType> types);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.sourceNode.code = :sourceCode
              AND r.targetNode.code = :targetCode
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("type") RelationType relationType);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
              AND (r.sourceNode.code = :code OR r.targetNode.code = :code)
            """)
    List<TaxonomyRelation> findVisibleByWorkspaceAndNodeCode(
            @Param("workspaceId") String workspaceId,
            @Param("code") String code);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
            """)
    List<TaxonomyRelation> findVisibleByWorkspace(
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findVisibleByWorkspaceAndRelationType(
            @Param("workspaceId") String workspaceId,
            @Param("type") RelationType type);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
              AND r.sourceNode.code = :sourceCode
              AND r.targetNode.code = :targetCode
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findVisibleByWorkspaceAndSourceTargetType(
            @Param("workspaceId") String workspaceId,
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("type") RelationType type);

    @Query("""
            SELECT COUNT(r) FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND (r.workspaceId = :workspaceId OR r.workspaceId IS NULL)
            """)
    long countVisibleByWorkspace(@Param("workspaceId") String workspaceId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId IS NULL
            """)
    List<TaxonomyRelation> findByWorkspaceIdIsNull();

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId IS NULL
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findByRelationTypeAndWorkspaceIdIsNull(
            @Param("type") RelationType relationType);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId IS NULL
              AND (r.sourceNode.code = :code OR r.targetNode.code = :code)
            """)
    List<TaxonomyRelation> findSharedByNodeCode(@Param("code") String code);

    @Query("""
            SELECT COUNT(r) FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId IS NULL
            """)
    long countByWorkspaceIdIsNull();

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.id = :id
              AND """ + PRIMARY_SCOPE + """
              AND ((:workspaceId IS NULL AND r.workspaceId IS NULL)
                   OR r.workspaceId = :workspaceId)
            """)
    Optional<TaxonomyRelation> findByIdInWorkspace(
            @Param("id") Long id,
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId = :workspaceId
            """)
    List<TaxonomyRelation> findByWorkspaceId(
            @Param("workspaceId") String workspaceId);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId = :workspaceId
              AND r.sourceNode.code = :sourceCode
              AND r.targetNode.code = :targetCode
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findByWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
            @Param("workspaceId") String workspaceId,
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("type") RelationType type);

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE """ + PRIMARY_SCOPE + """
              AND r.workspaceId IS NULL
              AND r.sourceNode.code = :sourceCode
              AND r.targetNode.code = :targetCode
              AND r.relationType = :type
            """)
    List<TaxonomyRelation> findSharedBySourceTargetType(
            @Param("sourceCode") String sourceCode,
            @Param("targetCode") String targetCode,
            @Param("type") RelationType type);

    default List<TaxonomyRelation> findByWorkspaceAndNodeCode(
            String workspaceId, String code) {
        return findVisibleByWorkspaceAndNodeCode(workspaceId, code);
    }

    default List<TaxonomyRelation> findByWorkspaceIdIsNullOrWorkspaceId(
            String workspaceId) {
        return findVisibleByWorkspace(workspaceId);
    }

    default List<TaxonomyRelation> findByWorkspaceAndRelationType(
            String workspaceId, RelationType type) {
        return findVisibleByWorkspaceAndRelationType(workspaceId, type);
    }

    default List<TaxonomyRelation> findByWorkspaceAndSourceTargetType(
            String workspaceId,
            String sourceCode,
            String targetCode,
            RelationType type) {
        return findVisibleByWorkspaceAndSourceTargetType(
                workspaceId, sourceCode, targetCode, type);
    }

    default long countByWorkspaceIdIsNullOrWorkspaceId(String workspaceId) {
        return countVisibleByWorkspace(workspaceId);
    }
}
