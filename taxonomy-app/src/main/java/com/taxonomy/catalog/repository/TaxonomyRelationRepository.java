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

    List<TaxonomyRelation> findBySourceNodeCode(String code);

    List<TaxonomyRelation> findByTargetNodeCode(String code);

    List<TaxonomyRelation> findByRelationType(RelationType relationType);

    List<TaxonomyRelation> findBySourceNodeCodeOrTargetNodeCode(String sourceCode, String targetCode);

    List<TaxonomyRelation> findByRelationTypeIn(List<RelationType> relationTypes);

    List<TaxonomyRelation> findBySourceNodeCodeAndRelationTypeIn(String sourceCode, List<RelationType> types);

    List<TaxonomyRelation> findByTargetNodeCodeAndRelationTypeIn(String targetCode, List<RelationType> types);

    List<TaxonomyRelation> findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
            String sourceCode, String targetCode, RelationType relationType);

    // ── Visible workspace scope: shared baseline + current workspace ──

    @Query("SELECT r FROM TaxonomyRelation r WHERE (r.workspaceId = :wsId OR r.workspaceId IS NULL) " +
           "AND (r.sourceNode.code = :code OR r.targetNode.code = :code)")
    List<TaxonomyRelation> findVisibleByWorkspaceAndNodeCode(@Param("wsId") String workspaceId,
                                                              @Param("code") String code);

    @Query("SELECT r FROM TaxonomyRelation r WHERE r.workspaceId = :wsId OR r.workspaceId IS NULL")
    List<TaxonomyRelation> findVisibleByWorkspace(@Param("wsId") String workspaceId);

    @Query("SELECT r FROM TaxonomyRelation r WHERE (r.workspaceId = :wsId OR r.workspaceId IS NULL) " +
           "AND r.relationType = :type")
    List<TaxonomyRelation> findVisibleByWorkspaceAndRelationType(@Param("wsId") String workspaceId,
                                                                  @Param("type") RelationType type);

    @Query("SELECT COUNT(r) FROM TaxonomyRelation r WHERE r.workspaceId = :wsId OR r.workspaceId IS NULL")
    long countVisibleByWorkspace(@Param("wsId") String workspaceId);

    // ── Shared scope only ────────────────────────────────────────────

    List<TaxonomyRelation> findByWorkspaceIdIsNull();

    List<TaxonomyRelation> findByRelationTypeAndWorkspaceIdIsNull(RelationType relationType);

    @Query("SELECT r FROM TaxonomyRelation r WHERE r.workspaceId IS NULL " +
           "AND (r.sourceNode.code = :code OR r.targetNode.code = :code)")
    List<TaxonomyRelation> findSharedByNodeCode(@Param("code") String code);

    long countByWorkspaceIdIsNull();

    // ── Exact mutable workspace scope ────────────────────────────────

    @Query("""
            SELECT r FROM TaxonomyRelation r
            WHERE r.id = :id
              AND ((:wsId IS NULL AND r.workspaceId IS NULL) OR r.workspaceId = :wsId)
            """)
    Optional<TaxonomyRelation> findByIdInWorkspace(@Param("id") Long id,
                                                    @Param("wsId") String workspaceId);

    List<TaxonomyRelation> findByWorkspaceId(String workspaceId);

    List<TaxonomyRelation> findByWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
            String workspaceId, String sourceCode, String targetCode, RelationType type);

    @Query("SELECT r FROM TaxonomyRelation r WHERE r.workspaceId IS NULL " +
           "AND r.sourceNode.code = :sourceCode AND r.targetNode.code = :targetCode " +
           "AND r.relationType = :type")
    List<TaxonomyRelation> findSharedBySourceTargetType(@Param("sourceCode") String sourceCode,
                                                         @Param("targetCode") String targetCode,
                                                         @Param("type") RelationType type);

    // ── Backward-compatible aliases for visible-scope callers ───────

    default List<TaxonomyRelation> findByWorkspaceAndNodeCode(String workspaceId, String code) {
        return findVisibleByWorkspaceAndNodeCode(workspaceId, code);
    }

    default List<TaxonomyRelation> findByWorkspaceIdIsNullOrWorkspaceId(String workspaceId) {
        return findVisibleByWorkspace(workspaceId);
    }

    default List<TaxonomyRelation> findByWorkspaceAndRelationType(String workspaceId, RelationType type) {
        return findVisibleByWorkspaceAndRelationType(workspaceId, type);
    }

    default List<TaxonomyRelation> findByWorkspaceAndSourceTargetType(String workspaceId,
                                                                       String sourceCode,
                                                                       String targetCode,
                                                                       RelationType type) {
        List<TaxonomyRelation> visible = findVisibleByWorkspace(workspaceId);
        return visible.stream()
                .filter(relation -> relation.getSourceNode().getCode().equals(sourceCode))
                .filter(relation -> relation.getTargetNode().getCode().equals(targetCode))
                .filter(relation -> relation.getRelationType() == type)
                .toList();
    }

    default long countByWorkspaceIdIsNullOrWorkspaceId(String workspaceId) {
        return countVisibleByWorkspace(workspaceId);
    }
}
