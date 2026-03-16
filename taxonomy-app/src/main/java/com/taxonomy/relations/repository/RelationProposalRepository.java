package com.taxonomy.relations.repository;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.model.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
