package com.taxonomy.catalog.repository;

import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.model.TaxonomyRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    // ── Workspace-scoped queries ─────────────────────────────────────

    @Query("SELECT r FROM TaxonomyRelation r WHERE (r.workspaceId = :wsId OR r.workspaceId IS NULL) " +
           "AND (r.sourceNode.code = :code OR r.targetNode.code = :code)")
    List<TaxonomyRelation> findByWorkspaceAndNodeCode(@Param("wsId") String workspaceId,
                                                       @Param("code") String code);

    @Query("SELECT r FROM TaxonomyRelation r WHERE r.workspaceId = :wsId OR r.workspaceId IS NULL")
    List<TaxonomyRelation> findByWorkspaceIdIsNullOrWorkspaceId(@Param("wsId") String workspaceId);

    @Query("SELECT r FROM TaxonomyRelation r WHERE (r.workspaceId = :wsId OR r.workspaceId IS NULL) " +
           "AND r.relationType = :type")
    List<TaxonomyRelation> findByWorkspaceAndRelationType(@Param("wsId") String workspaceId,
                                                           @Param("type") RelationType type);

    List<TaxonomyRelation> findByWorkspaceId(String workspaceId);

    @Query("SELECT r FROM TaxonomyRelation r WHERE (r.workspaceId = :wsId OR r.workspaceId IS NULL) " +
           "AND r.sourceNode.code = :sourceCode AND r.targetNode.code = :targetCode " +
           "AND r.relationType = :type")
    List<TaxonomyRelation> findByWorkspaceAndSourceTargetType(@Param("wsId") String workspaceId,
                                                               @Param("sourceCode") String sourceCode,
                                                               @Param("targetCode") String targetCode,
                                                               @Param("type") RelationType type);
}
