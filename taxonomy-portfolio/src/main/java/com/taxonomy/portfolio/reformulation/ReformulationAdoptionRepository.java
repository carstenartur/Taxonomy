package com.taxonomy.portfolio.reformulation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ReformulationAdoptionRepository extends JpaRepository<ReformulationAdoption,String> {
    boolean existsByPreviewId(String previewId);
    List<ReformulationAdoption> findByProposalIdAndScopeKeyOrderByCreatedAtDesc(String proposalId,String scopeKey);
}
