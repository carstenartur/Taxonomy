package com.nato.taxonomy.repository;

import com.nato.taxonomy.model.ProposalStatus;
import com.nato.taxonomy.model.RelationProposal;
import com.nato.taxonomy.model.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelationProposalRepository extends JpaRepository<RelationProposal, Long> {

    List<RelationProposal> findByStatus(ProposalStatus status);

    List<RelationProposal> findBySourceNodeCode(String sourceCode);

    List<RelationProposal> findBySourceNodeCodeAndRelationType(String sourceCode, RelationType relationType);

    boolean existsBySourceNodeCodeAndTargetNodeCodeAndRelationType(
            String sourceCode, String targetCode, RelationType relationType);
}
