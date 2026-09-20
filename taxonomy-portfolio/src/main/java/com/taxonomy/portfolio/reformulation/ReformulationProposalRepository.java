package com.taxonomy.portfolio.reformulation;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
public interface ReformulationProposalRepository extends JpaRepository<ReformulationProposal,String> {
    Optional<ReformulationProposal> findByIdAndProjectIdAndRequirementIdAndScopeKey(String id,Long projectId,Long requirementId,String scopeKey);
    List<ReformulationProposal> findByProjectIdAndRequirementIdAndScopeKeyOrderByCreatedAtDesc(Long projectId,Long requirementId,String scopeKey);

    @Query("""
            select new com.taxonomy.portfolio.reformulation.ProposalSummary(
                p.id, p.sourceVersionId, p.snapshotId, p.createdBy, p.createdAt, p.currentRevision)
            from ReformulationProposal p
            where p.projectId = :projectId and p.requirementId = :requirementId and p.scopeKey = :scopeKey
            order by p.createdAt desc, p.id desc
            """)
    List<ProposalSummary> findSummaries(Long projectId, Long requirementId, String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ReformulationProposal p where p.id=:id and p.projectId=:projectId and p.requirementId=:requirementId and p.scopeKey=:scopeKey")
    Optional<ReformulationProposal> lockScoped(String id,Long projectId,Long requirementId,String scopeKey);
}
