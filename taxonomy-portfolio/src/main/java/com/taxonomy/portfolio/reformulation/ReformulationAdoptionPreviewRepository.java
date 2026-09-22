package com.taxonomy.portfolio.reformulation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ReformulationAdoptionPreviewRepository extends JpaRepository<ReformulationAdoptionPreview,String> {
    Optional<ReformulationAdoptionPreview> findByIdAndProposalIdAndScopeKey(String id,String proposalId,String scopeKey);
}
