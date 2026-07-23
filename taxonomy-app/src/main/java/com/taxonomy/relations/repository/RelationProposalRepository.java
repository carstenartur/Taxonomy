package com.taxonomy.relations.repository;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.model.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelationProposalRepository extends JpaRepository<RelationProposal, Long> {

    List<RelationProposal> findByStatus(ProposalStatus status);

    List<RelationProposal> findBySourceNodeCode(String sourceCode);

    List<RelationProposal> findBySourceNodeCodeAndRelationType(String sourceCode, RelationType relationType);

    boolean existsBySourceNodeCodeAndTargetNodeCodeAndRelationType(
            String sourceCode, String targetCode, RelationType relationType);

    long countByStatus(ProposalStatus status);

    long countByRelationTypeAndStatus(RelationType relationType, ProposalStatus status);

    @Query("SELECT DISTINCT p.relationType FROM RelationProposal p")
    List<RelationType> findDistinctRelationTypes();

    @Query("SELECT DISTINCT p.provenance FROM RelationProposal p")
    List<String> findDistinctProvenances();

    long countByProvenanceAndStatus(String provenance, ProposalStatus status);

    @Query("SELECT AVG(p.confidence) FROM RelationProposal p WHERE p.status = :status")
    Double avgConfidenceByStatus(@Param("status") ProposalStatus status);

    List<RelationProposal> findByStatusOrderByConfidenceDesc(ProposalStatus status);

    long countBySourceNodeTaxonomyRootAndTargetNodeTaxonomyRootAndRelationTypeAndStatus(
            String sourceRoot, String targetRoot, RelationType relationType, ProposalStatus status);

    // ── Workspace-scoped queries ─────────────────────────────────────

    /**
     * Looks up a proposal in exactly one workspace. A null workspace ID means
     * shared scope only; it never means all workspaces.
     */
    @Query("""
            SELECT p FROM RelationProposal p
            WHERE p.id = :id
              AND ((:wsId IS NULL AND p.workspaceId IS NULL) OR p.workspaceId = :wsId)
            """)
    Optional<RelationProposal> findByIdInWorkspace(@Param("id") Long id,
                                                    @Param("wsId") String workspaceId);

    /**
     * Checks a proposal triple in exactly one workspace. A null workspace ID
     * is matched with IS NULL so shared mode cannot observe foreign workspaces.
     */
    @Query("""
            SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
            FROM RelationProposal p
            WHERE p.sourceNode.code = :sourceCode
              AND p.targetNode.code = :targetCode
              AND p.relationType = :relationType
              AND ((:wsId IS NULL AND p.workspaceId IS NULL) OR p.workspaceId = :wsId)
            """)
    boolean existsInWorkspace(@Param("sourceCode") String sourceCode,
                              @Param("targetCode") String targetCode,
                              @Param("relationType") RelationType relationType,
                              @Param("wsId") String workspaceId);

    boolean existsBySourceNodeCodeAndTargetNodeCodeAndRelationTypeAndWorkspaceId(
            String sourceCode, String targetCode, RelationType relationType, String workspaceId);

    List<RelationProposal> findByWorkspaceId(String workspaceId);

    List<RelationProposal> findByWorkspaceIdIsNull();

    List<RelationProposal> findByStatusAndWorkspaceIdIsNull(ProposalStatus status);

    List<RelationProposal> findBySourceNodeCodeAndWorkspaceIdIsNull(String sourceCode);

    @Query("SELECT p FROM RelationProposal p WHERE p.workspaceId = :wsId OR p.workspaceId IS NULL")
    List<RelationProposal> findByWorkspaceIdIsNullOrWorkspaceId(@Param("wsId") String workspaceId);

    @Query("SELECT p FROM RelationProposal p WHERE p.status = :status AND (p.workspaceId = :wsId OR p.workspaceId IS NULL)")
    List<RelationProposal> findByStatusAndWorkspace(@Param("status") ProposalStatus status,
                                                     @Param("wsId") String workspaceId);

    @Query("SELECT p FROM RelationProposal p WHERE p.sourceNode.code = :code AND (p.workspaceId = :wsId OR p.workspaceId IS NULL)")
    List<RelationProposal> findBySourceNodeCodeAndWorkspace(@Param("code") String sourceCode,
                                                             @Param("wsId") String workspaceId);
}
