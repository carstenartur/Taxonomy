package com.taxonomy.portfolio.reformulation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReformulationRevisionRepository extends JpaRepository<ReformulationRevision,String> {
    Optional<ReformulationRevision> findByProposalIdAndNumberAndScopeKey(String proposalId,long number,String scopeKey);
}
