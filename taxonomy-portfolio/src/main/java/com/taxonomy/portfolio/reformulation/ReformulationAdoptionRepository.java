package com.taxonomy.portfolio.reformulation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface ReformulationAdoptionRepository extends JpaRepository<ReformulationAdoption,String> {
    Optional<ReformulationAdoption> findByIdAndProposalIdAndRequirementIdAndScopeKey(
            String id, String proposalId, Long requirementId, String scopeKey);
    boolean existsByPreviewId(String previewId);
    List<ReformulationAdoption> findByProposalIdAndScopeKeyOrderByCreatedAtDesc(String proposalId,String scopeKey);
    List<ReformulationAdoption> findByScopeKeyOrderByCreatedAtAsc(String scopeKey);
}
